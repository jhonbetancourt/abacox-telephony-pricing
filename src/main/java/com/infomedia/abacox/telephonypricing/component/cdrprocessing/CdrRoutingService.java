package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrRoutingService {

    private final CommunicationLocationLookupService commLocationLookupService;
    private final CdrProcessorService cdrProcessorService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final List<CdrProcessor> cdrProcessors;
    private final EmployeeLookupService employeeLookupService;
    private final FileProcessingTrackerService trackerService;

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    private void processStreamContent(FileInfo fileInfo, InputStream contentStream,
            Map<Long, ExtensionLimits> extensionLimits,
            Map<Long, List<ExtensionRange>> extensionRanges) {

        // METRICS: Start Timer
        long startTime = System.currentTimeMillis();

        trackerService.initFile(fileInfo.getId());

        Long plantTypeId = fileInfo.getParentId().longValue();
        CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
        Map<String, Integer> currentFileHeaderMap = null;

        // METRICS: Counters
        long lineCount = 0;
        long totalProcessedCount = 0;
        long unroutableCdrCount = 0;

        List<LineProcessingContext> batch = new ArrayList<>(CdrConfigService.CDR_PROCESSING_BATCH_SIZE);

        try (InputStreamReader reader = new InputStreamReader(contentStream, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)) {

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty())
                    continue;

                if (currentFileHeaderMap == null && initialParser.isHeaderLine(trimmedLine)) {
                    currentFileHeaderMap = initialParser.parseHeader(trimmedLine);
                    continue;
                }

                if (currentFileHeaderMap == null) {
                    CdrData tempData = new CdrData();
                    tempData.setRawCdrLine(trimmedLine);
                    tempData.setFileInfo(fileInfo);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER,
                            "CDR data encountered before header",
                            "RouteStream_HeaderCheck", null);
                    unroutableCdrCount++;
                    continue;
                }

                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null,
                        currentFileHeaderMap);
                if (preliminaryCdrData == null)
                    continue;

                preliminaryCdrData.setRawCdrLine(trimmedLine);
                preliminaryCdrData.setFileInfo(fileInfo);

                Optional<CommunicationLocation> targetCommLocationOpt = commLocationLookupService
                        .findBestCommunicationLocation(
                                plantTypeId,
                                preliminaryCdrData.getCallingPartyNumber(),
                                preliminaryCdrData.getCallingPartyNumberPartition(),
                                preliminaryCdrData.getFinalCalledPartyNumber(),
                                preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                                preliminaryCdrData.getLastRedirectDn(),
                                preliminaryCdrData.getLastRedirectDnPartition(),
                                preliminaryCdrData.getDateTimeOrigination());

                if (targetCommLocationOpt.isPresent()) {
                    CommunicationLocation targetCommLocation = targetCommLocationOpt.get();
                    CdrProcessor finalProcessor = getProcessorForPlantType(targetCommLocation.getPlantTypeId());

                    LineProcessingContext lineProcessingContext = LineProcessingContext.builder()
                            .cdrLine(trimmedLine)
                            .commLocation(targetCommLocation)
                            .cdrProcessor(finalProcessor)
                            .extensionRanges(extensionRanges)
                            .extensionLimits(extensionLimits)
                            .fileInfo(fileInfo)
                            .headerPositions(currentFileHeaderMap)
                            .build();
                    batch.add(lineProcessingContext);
                } else {
                    unroutableCdrCount++;
                    preliminaryCdrData.setCommLocationId(null);
                    failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                            QuarantineErrorType.PENDING_ASSOCIATION,
                            "Could not route CDR to a CommunicationLocation",
                            "CdrRoutingService_Unroutable", null);
                }

                if (batch.size() >= CdrConfigService.CDR_PROCESSING_BATCH_SIZE) {
                    cdrProcessorService.processCdrBatch(batch);
                    totalProcessedCount += batch.size();
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                cdrProcessorService.processCdrBatch(batch);
                totalProcessedCount += batch.size();
                batch.clear();
            }

            trackerService.markParsingComplete(fileInfo.getId());

            // METRICS: Final Calculation
            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;
            double seconds = durationMs / 1000.0;
            double linesPerSecond = (seconds > 0) ? (lineCount / seconds) : 0.0;

            log.info(
                    "Outcome for file [{}]: SUCCESS. Time: {}ms. Speed: {} lines/sec. Read: {}, Routed: {}, Unroutable: {}",
                    fileInfo.getFilename(),
                    durationMs,
                    String.format("%.2f", linesPerSecond),
                    lineCount,
                    totalProcessedCount,
                    unroutableCdrCount);

        } catch (IOException e) {
            log.error("Outcome for file [{}]: FAILED. IO Error.", fileInfo.getFilename(), e);
            trackerService.markParsingComplete(fileInfo.getId());
            fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.FAILED);
        }
    }

    public void processFileInfo(Long fileInfoId) {
        log.info("Starting processing for FileInfo ID: {}", fileInfoId);
        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        if (fileInfo == null) {
            log.error("FileInfo ID {}: FAILED (Missing Data)", fileInfoId);
            return;
        }

        Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfoId);

        if (fileDataOpt.isPresent()) {
            try (InputStream contentStream = fileDataOpt.get().content()) {
                Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
                Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

                processStreamContent(fileInfo, contentStream, extensionLimits, extensionRanges);
            } catch (IOException e) {
                log.error("Stream read error for file {}", fileInfoId, e);
            }
        }
    }

    @Transactional
    public void cleanupRecordsForFile(Long fileInfoId) {
        log.debug("Cleaning up existing records for FileInfo ID: {}", fileInfoId);
        int deletedCallRecords = callRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        int deletedFailedRecords = failedCallRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        log.info("Cleanup complete for FileInfo ID {}: Deleted {} CallRecords and {} FailedCallRecords.",
                fileInfoId, deletedCallRecords, deletedFailedRecords);
    }

    public void reprocessFileInfo(Long fileInfoId, boolean cleanupExistingRecords) {
        log.info("Starting reprocessing for FileInfo ID: {}", fileInfoId);
        if (cleanupExistingRecords) {
            cleanupRecordsForFile(fileInfoId);
        }
        processFileInfo(fileInfoId);
    }

    @Transactional
    public CdrProcessingResultDto routeAndProcessCdrStreamSync(FileInfo fileInfo) {
        long startTime = System.currentTimeMillis();
        log.info("Starting SYNCHRONOUS CDR stream processing for FileInfo ID: {}", fileInfo.getId());

        fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.IN_PROGRESS);

        CdrProcessingResultDto.CdrProcessingResultDtoBuilder resultBuilder = CdrProcessingResultDto.builder()
                .fileInfoId(fileInfo.getId());
        long lineCount = 0;
        int successfulRecords = 0;
        int quarantinedRecords = 0;
        int skippedLines = 0;
        FileInfo.ProcessingStatus finalStatus = FileInfo.ProcessingStatus.FAILED;
        String finalMessage = "An unexpected error occurred.";

        try {
            Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfo.getId());

            if (!fileDataOpt.isPresent()) {
                throw new RuntimeException("Failed to retrieve file content for FileInfo ID: " + fileInfo.getId());
            }

            Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
            Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();
            CdrProcessor initialParser = getProcessorForPlantType(fileInfo.getParentId().longValue());
            Map<String, Integer> currentHeaderMap = null;

            try (InputStream inputStream = fileDataOpt.get().content();
                    InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                    BufferedReader bufferedReader = new BufferedReader(reader)) {

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    lineCount++;
                    String trimmedLine = line.trim();
                    if (trimmedLine.isEmpty())
                        continue;

                    if (currentHeaderMap == null && initialParser.isHeaderLine(trimmedLine)) {
                        currentHeaderMap = initialParser.parseHeader(trimmedLine);
                        continue;
                    }

                    if (currentHeaderMap == null) {
                        CdrData tempData = new CdrData();
                        tempData.setRawCdrLine(trimmedLine);
                        tempData.setFileInfo(fileInfo);
                        failedCallRecordPersistenceService.quarantineRecord(
                                tempData,
                                QuarantineErrorType.MISSING_HEADER,
                                "CDR data encountered before header (sync processing)",
                                "SyncProcessing_HeaderCheck",
                                null);
                        quarantinedRecords++;
                        continue;
                    }

                    CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null,
                            currentHeaderMap);
                    if (preliminaryCdrData == null) {
                        skippedLines++;
                        continue;
                    }

                    Optional<CommunicationLocation> targetCommLocationOpt = commLocationLookupService
                            .findBestCommunicationLocation(
                                    fileInfo.getParentId().longValue(),
                                    preliminaryCdrData.getCallingPartyNumber(),
                                    preliminaryCdrData.getCallingPartyNumberPartition(),
                                    preliminaryCdrData.getFinalCalledPartyNumber(),
                                    preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                                    preliminaryCdrData.getLastRedirectDn(),
                                    preliminaryCdrData.getLastRedirectDnPartition(),
                                    preliminaryCdrData.getDateTimeOrigination());

                    if (targetCommLocationOpt.isPresent()) {
                        CommunicationLocation targetCommLocation = targetCommLocationOpt.get();
                        CdrProcessor finalProcessor = getProcessorForPlantType(targetCommLocation.getPlantTypeId());

                        LineProcessingContext context = LineProcessingContext.builder()
                                .cdrLine(trimmedLine)
                                .commLocation(targetCommLocation)
                                .cdrProcessor(finalProcessor)
                                .extensionRanges(extensionRanges)
                                .extensionLimits(extensionLimits)
                                .fileInfo(fileInfo)
                                .headerPositions(currentHeaderMap)
                                .build();

                        ProcessingOutcome outcome = cdrProcessorService.processSingleCdrLineSync(context);
                        switch (outcome) {
                            case SUCCESS -> successfulRecords++;
                            case QUARANTINED -> quarantinedRecords++;
                            case SKIPPED -> skippedLines++;
                        }
                    } else {
                        quarantinedRecords++;
                        preliminaryCdrData.setRawCdrLine(trimmedLine);
                        preliminaryCdrData.setFileInfo(fileInfo);
                        preliminaryCdrData.setCommLocationId(null);
                        failedCallRecordPersistenceService.quarantineRecord(
                                preliminaryCdrData,
                                QuarantineErrorType.PENDING_ASSOCIATION,
                                "Could not route CDR to a CommunicationLocation",
                                "CdrRoutingService_SyncUnroutable",
                                null);
                    }
                }
            }

            finalStatus = FileInfo.ProcessingStatus.COMPLETED;
            finalMessage = "File processed successfully.";

            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;

            log.info(
                    "Sync processing completed for FileInfo ID {}: {} lines. Time: {}ms. {} successful, {} quarantined, {} skipped",
                    fileInfo.getId(), lineCount, durationMs, successfulRecords, quarantinedRecords, skippedLines);

        } catch (Exception e) {
            log.error("Critical failure during synchronous processing of file ID: {}. Rolling back.",
                    fileInfo.getId(), e);
            finalStatus = FileInfo.ProcessingStatus.FAILED;
            finalMessage = "Processing failed: " + e.getMessage();
            throw new RuntimeException("Synchronous processing failed for FileInfo ID " + fileInfo.getId(), e);
        } finally {
            fileInfoPersistenceService.updateStatus(fileInfo.getId(), finalStatus);
        }

        return resultBuilder
                .status(finalStatus.name())
                .message(finalMessage)
                .linesRead(lineCount)
                .successfulRecords(successfulRecords)
                .quarantinedRecords(quarantinedRecords)
                .skippedLines(skippedLines)
                .build();
    }
}