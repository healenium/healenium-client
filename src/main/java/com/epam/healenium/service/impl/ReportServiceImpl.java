package com.epam.healenium.service.impl;

import com.epam.healenium.constants.Constants;
import com.epam.healenium.model.domain.Healing;
import com.epam.healenium.model.domain.HealingResult;
import com.epam.healenium.model.domain.Report;
import com.epam.healenium.model.dto.RecordDto;
import com.epam.healenium.model.dto.RecordDto.ReportRecord;
import com.epam.healenium.model.dto.ReportLinkDto;
import com.epam.healenium.repository.HealingResultRepository;
import com.epam.healenium.repository.ReportRepository;
import com.epam.healenium.service.ReportService;
import com.epam.healenium.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j(topic = "healenium")
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    @Value("${app.baseUrl}")
    private String baseUrl;

    private final ReportRepository reportRepository;
    private final HealingResultRepository resultRepository;

    @Override
    public String initialize() {
        Report report = new Report()
                .setCreatedDate(LocalDateTime.now());
        return reportRepository.save(report).getUid();
    }

    @Override
    public String initialize(String uid) {
        Report report = new Report()
                .setUid(uid)
                .setCreatedDate(LocalDateTime.now());
        return reportRepository.save(report).getUid();
    }

    @Override
    public RecordDto generate() {
        RecordDto result = new RecordDto();
        List<Report> all = reportRepository.findAllByOrderByCreatedDateDesc();
        List<ReportLinkDto> reportLinks = getReportLinks(all);
        result.setReportLinks(reportLinks);
        if (all.size() > 0) {
            Optional<Report> optionalReport = reportRepository.findById(all.get(0).getUid());
            if (optionalReport.isPresent()) {
                Report report = optionalReport.get();
                reportLinks.stream()
                        .findFirst()
                        .ifPresent(r -> {
                            r.setExtendClass("active");
                            result.setName("Healing Report " + r.getName());
                        });
                result.setTime(report.getCreatedDate().format(DateTimeFormatter.ISO_DATE_TIME));
                buildReportRecords(result, report);
            }
        }
        return result;
    }

    @Override
    public RecordDto generate(String key) {
        RecordDto result = new RecordDto();
        List<Report> all = reportRepository.findAllByOrderByCreatedDateDesc();
        List<ReportLinkDto> reportLinks = getReportLinks(all);
        result.setReportLinks(reportLinks);
        Optional<Report> optionalReport = reportRepository.findById(key);
        if (optionalReport.isPresent()) {
            Report report = optionalReport.get();
            reportLinks.stream()
                    .filter(r -> report.getUid().equals(r.getId()))
                    .findFirst()
                    .ifPresent(r -> {
                        r.setExtendClass("active");
                        result.setName("Healing Report " + r.getName());
                    });
            result.setTime(report.getCreatedDate().format(DateTimeFormatter.ISO_DATE_TIME));
            buildReportRecords(result, report);
        }
        return result;
    }

    private List<ReportLinkDto> getReportLinks(List<Report> all) {
        List<ReportLinkDto> reportLinks = all.stream()
                .map(r -> new ReportLinkDto()
                        .setId(r.getUid())
                        .setName(r.getCreatedDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy - HH:mm:ss"))))
                .collect(Collectors.toList());
        return reportLinks;
    }

    private void buildReportRecords(RecordDto result, Report report) {
        report.getRecordWrapper().getRecords().forEach(it -> {
            ReportRecord reportRecord = new ReportRecord();
            reportRecord.setDeclaringClass(it.getClassName().contains(Constants.SINGLE_ELEMENT_PROXY_CLASS_PATH)
                    || it.getClassName().contains(Constants.MULTIPLE_ELEMENTS_PROXY_CLASS_PATH)
                    ? it.getFailedLocator().getValue()
                    : it.getClassName() + "." + it.getMethodName() + "()");
            reportRecord.setScreenShotPath(transformPath(it.getScreenShotPath()));
            reportRecord.setFailedLocatorType(it.getFailedLocator().getType());
            reportRecord.setFailedLocatorValue(it.getFailedLocator().getValue());
            reportRecord.setHealedLocatorType(it.getHealedLocator().getType());
            reportRecord.setHealedLocatorValue(it.getHealedLocator().getValue());
            Optional<HealingResult> healingResultOptional = resultRepository.findById(it.getHealingResultId());
            reportRecord.setScore(new DecimalFormat("#.###").format(healingResultOptional.get().getScore()));
            reportRecord.setSuccessHealing(healingResultOptional.get().isSuccessHealing());
            reportRecord.setHealingResultId(it.getHealingResultId());
            result.getData().add(reportRecord);
        });
    }

    private String transformPath(String sourcePath) {
        try {
            int i = sourcePath.lastIndexOf("screenshots");
            String imagePath = sourcePath.substring(i - 1);
            return String.format("%s%s", baseUrl, imagePath);
        } catch (Exception e) {
            log.warn("[Build Report] Error transform sourcePath: {}", sourcePath);
            return sourcePath;
        }
    }


    /**
     * Create record in report about healing
     *
     * @param result
     * @param healing
     * @param sessionId
     */
    @Override
    public void createReportRecord(HealingResult result, Healing healing, String sessionId, byte[] screenshot) {
        if (!StringUtils.isEmpty(sessionId)) {
            String screenshotDir = "/screenshots/" + sessionId;
            String screenshotPath = persistScreenshot(screenshot, screenshotDir);
            log.debug("[Save Healing] Screenshot Path: {}", screenshotPath);
            // if healing performs during test phase, add report record
            reportRepository.findById(sessionId).ifPresent(r -> {
                log.debug("[Save Healing] Add Report Record. Session Id: {}, Result: {}, Healing: {}", sessionId, result, healing);
                r.getRecordWrapper().addRecord(healing, result, screenshotPath);
                log.debug("[Save Healing] Report: {}", r);
                reportRepository.save(r);
            });
        }
    }

    /**
     * @param screenshotContent
     * @param filePath
     */
    private String persistScreenshot(byte[] screenshotContent, String filePath) {
        String rootDir = Paths.get("").toAbsolutePath().toString();
        String fileName = Paths.get(rootDir, filePath, Utils.buildScreenshotName()).toString();
        try {
            File file = new File(fileName);
            FileUtils.writeByteArrayToFile(file, screenshotContent);
        } catch (Exception ex) {
            log.warn("Failed to save screenshot {}", fileName);
        }
        return fileName;
    }
}