package com.epam.healenium.service.impl;

import com.epam.healenium.exception.MissingSelectorException;
import com.epam.healenium.mapper.HealingMapper;
import com.epam.healenium.model.domain.Healing;
import com.epam.healenium.model.domain.HealingResult;
import com.epam.healenium.model.domain.Selector;
import com.epam.healenium.model.dto.HealingDto;
import com.epam.healenium.model.dto.HealingRequestDto;
import com.epam.healenium.model.dto.HealingResultDto;
import com.epam.healenium.model.dto.RecordDto;
import com.epam.healenium.model.dto.RequestDto;
import com.epam.healenium.repository.HealingRepository;
import com.epam.healenium.repository.HealingResultRepository;
import com.epam.healenium.repository.SelectorRepository;
import com.epam.healenium.rest.AmazonRestService;
import com.epam.healenium.service.HealingService;
import com.epam.healenium.service.ReportService;
import com.epam.healenium.service.SelectorService;
import com.epam.healenium.specification.HealingSpecBuilder;
import com.epam.healenium.util.StreamUtils;
import com.epam.healenium.util.Utils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.epam.healenium.constants.Constants.EMPTY_PROJECT;
import static com.epam.healenium.constants.Constants.HOST_PROJECT;
import static com.epam.healenium.constants.Constants.SESSION_KEY_V1;
import static com.epam.healenium.constants.Constants.SESSION_KEY_V2;
import static com.epam.healenium.constants.Constants.SUCCESSFUL_HEALING_BUCKET;
import static com.epam.healenium.constants.Constants.UNSUCCESSFUL_HEALING_BUCKET;

@Slf4j(topic = "healenium")
@Service
@RequiredArgsConstructor
@Transactional
public class HealingServiceImpl implements HealingService {

    @Value("${app.selector.key.url-for-key}")
    private boolean urlForKey;
    @Value("${app.metrics.allow}")
    private boolean allowCollectMetrics;

    private final HealingRepository healingRepository;
    private final SelectorRepository selectorRepository;
    private final SelectorService selectorService;
    private final HealingResultRepository resultRepository;
    private final ReportService reportService;
    private final HealingMapper healingMapper;
    private final AmazonRestService amazonRestService;

    @Override
    public void saveHealing(HealingRequestDto dto, Map<String, String> headers) {
        // obtain healing
        Healing healing = getHealing(dto);
        // collect healing results
        Collection<HealingResult> healingResults = buildHealingResults(dto.getResults(), healing);
        HealingResult selectedResult = healingResults.stream()
                .filter(it -> {
                    String firstLocator, secondLocator;
                    firstLocator = it.getLocator().getValue();
                    secondLocator = dto.getUsedResult().getLocator().getValue();
                    return firstLocator.equals(secondLocator);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("[Save Healing] Internal exception! Somehow we lost selected healing result on save"));
        // add report record
        reportService.createReportRecord(selectedResult, healing, getSessionKey(headers), dto.getScreenshot());
        if (allowCollectMetrics) {
            pushMetrics(dto.getMetrics(), headers, selectedResult, dto.getUrl());
        }
    }

    @Override
    public Set<HealingDto> getHealings(RequestDto dto) {
        Set<HealingDto> result = new HashSet<>();
        healingRepository.findAll(HealingSpecBuilder.buildSpec(dto)).stream()
                .collect(Collectors.groupingBy(Healing::getSelector))
                .forEach((selector, healingList) -> {
                    // collect healing results
                    Set<HealingResultDto> healingResults = healingList.stream()
                            .flatMap(it -> it.getResults().stream())
                            .sorted(Comparator.comparing(HealingResult::getScore, Comparator.reverseOrder()))
                            .filter(StreamUtils.distinctByKey(HealingResult::getLocator))
                            .map(healingMapper::modelToResultDto)
                            .collect(Collectors.toSet());
                    // build healing dto
                    HealingDto healingDto = new HealingDto()
                            .setClassName(selector.getClassName())
                            .setMethodName(selector.getMethodName())
                            .setLocator(selector.getLocator().getValue())
                            .setResults(healingResults);
                    // add dto to result collection
                    result.add(healingDto);
                });
        return result;
    }

    @Override
    public Set<HealingResultDto> getHealingResults(RequestDto dto) {
        String selectorId = selectorService.getSelectorId(dto.getLocator(), dto.getUrl(), dto.getCommand(), urlForKey);
        log.debug("[Get Healing Result] Selector ID: {}", selectorId);
        return healingRepository.findBySelectorId(selectorId).stream()
                .flatMap(it -> healingMapper.modelToResultDto(it.getResults()).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public void saveSuccessHealing(RecordDto.ReportRecord dto) {
        Optional<HealingResult> healingResultOptional = resultRepository.findById(dto.getHealingResultId());
        if (healingResultOptional.isPresent()) {
            HealingResult healingResult = healingResultOptional.get();
            healingResult.setSuccessHealing(dto.isSuccessHealing());
            resultRepository.save(healingResult);
            if (allowCollectMetrics) {
                moveMetrics(dto, healingResult);
            }
        }
    }

    private Healing getHealing(HealingRequestDto dto) {
        // build selector key
        String selectorId = selectorService.getSelectorId(dto.getLocator(), dto.getUrl(), dto.getCommand(), urlForKey);
        // build healing key
        String healingId = Utils.buildHealingKey(selectorId, dto.getPageContent());
        return healingRepository.findById(healingId).orElseGet(() -> {
            // if no healing present
            Optional<Selector> optionalSelector = selectorRepository.findById(selectorId);
            return optionalSelector.map(element -> healingRepository.save(new Healing(healingId, element, dto.getPageContent())))
                    .orElseThrow(MissingSelectorException::new);
        });
    }

    private List<HealingResult> buildHealingResults(List<HealingResultDto> dtos, Healing healing) {
        List<HealingResult> results = dtos.stream().map(healingMapper::resultDtoToModel).peek(it -> it.setHealing(healing)).collect(Collectors.toList());
        return resultRepository.saveAll(results);
    }

    /**
     * Persist healing results
     *
     * @param healing
     * @param healingResults
     */
    private void saveHealingResults(Collection<HealingResult> healingResults, Healing healing) {
        if (!CollectionUtils.isEmpty(healing.getResults())) {
            // remove old results for given healing object
            resultRepository.deleteAll(healing.getResults());
        }

        // save new results
        List<HealingResult> results = resultRepository.saveAll(healingResults);
    }

    private void pushMetrics(String metrics, Map<String, String> headers, HealingResult selectedResult, String url) {
        try {
            if (metrics != null) {
                log.debug("[Save Healing] Push Metrics: {}", selectedResult);
                amazonRestService.uploadMetrics(metrics, selectedResult,
                        StringUtils.defaultIfEmpty(headers.get(HOST_PROJECT), EMPTY_PROJECT), url);
            }
        } catch (Exception ex) {
            log.warn("[Save Healing] Error during push metrics: {}", ex.getMessage());
        }
    }

    private void moveMetrics(RecordDto.ReportRecord dto, HealingResult healingResult) {
        try {
            if (!dto.isSuccessHealing()) {
                log.debug("[Set Healing Status] Set 'Unsuccessful' status");
                amazonRestService.moveMetrics(SUCCESSFUL_HEALING_BUCKET, healingResult);
            } else {
                log.debug("[Set Healing Status] Set 'Successful' status");
                amazonRestService.moveMetrics(UNSUCCESSFUL_HEALING_BUCKET, healingResult);
            }
        } catch (Exception ex) {
            log.warn("[Set Healing Status] Error during move metrics: {}", ex.getMessage());
        }
    }

    private String getSessionKey(Map<String, String> headers) {
        return !headers.get(SESSION_KEY_V1).isEmpty() ? headers.get(SESSION_KEY_V1) : headers.get(SESSION_KEY_V2);
    }
}
