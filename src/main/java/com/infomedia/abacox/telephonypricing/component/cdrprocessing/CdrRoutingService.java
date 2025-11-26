// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrRoutingService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
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

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    private void processStreamContent(FileInfo fileInfo, InputStream contentStream,
                                      Map<Long, ExtensionLimits> extensionLimits,
                                      Map<Long, List<ExtensionRange>> extensionRanges) {
        // TIMER START: Core Processing
        long startTime = System.currentTimeMillis();

        Long plantTypeId = fileInfo.getParentId().longValue();
        CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
        
        // Local variable to store header map for this specific file stream
        Map<String, Integer> currentFileHeaderMap = null;
        
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
                if (trimmedLine.isEmpty()) continue;

                if (currentFileHeaderMap == null && initialParser.isHeaderLine(trimmedLine)) {
                    currentFileHeaderMap = initialParser.parseHeader(trimmedLine);
                    log.debug("Processed header line for file: {}", fileInfo.getFilename());
                    continue;
                }

                if (currentFileHeaderMap == null) {
                    log.warn("Skipping line {} as header has not been processed by initial parser: {}", lineCount, trimmedLine);
                    CdrData tempData = new CdrData();
                    tempData.setRawCdrLine(trimmedLine);
                    tempData.setFileInfo(fileInfo);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER,
                            "CDR data encountered before header (routing stage)",
                            "RouteStream_HeaderCheck", null);
                    unroutableCdrCount++;
                    continue;
                }

                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null, currentFileHeaderMap);

                if (preliminaryCdrData == null) {
                    log.trace("Initial parser returned null for line, skipping routing: {}", trimmedLine);
                    continue;
                }
                preliminaryCdrData.setRawCdrLine(trimmedLine);
                preliminaryCdrData.setFileInfo(fileInfo);

                Optional<CommunicationLocation> targetCommLocationOpt =
                        commLocationLookupService.findBestCommunicationLocation(
                                plantTypeId,
                                preliminaryCdrData.getCallingPartyNumber(),
                                preliminaryCdrData.getCallingPartyNumberPartition(),
                                preliminaryCdrData.getFinalCalledPartyNumber(),
                                preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                                preliminaryCdrData.getLastRedirectDn(),
                                preliminaryCdrData.getLastRedirectDnPartition(),
                                preliminaryCdrData.getDateTimeOrigination()
                        );

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
                    log.debug("Could not route CDR at line {} to a CommunicationLocation", lineCount);
                    preliminaryCdrData.setCommLocationId(null);
                    failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                            QuarantineErrorType.PENDING_ASSOCIATION,
                            "Could not route CDR to a CommunicationLocation",
                            "CdrRoutingService_Unroutable", null);
                    unroutableCdrCount++;
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

            // TIMER END: Calculate Stats
            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;
            double seconds = durationMs / 1000.0;
            double linesPerSecond = (seconds > 0) ? (lineCount / seconds) : 0.0;

            // FIX: Use String.format to format numbers before passing to SLF4J
            log.info("Outcome for file [{}]: SUCCESS. Time: {}ms ({}s). Speed: {} lines/sec. Read: {}, Processed: {}, Unroutable: {}",
                    fileInfo.getFilename(),
                    durationMs,
                    String.format("%.2f", seconds),
                    String.format("%.2f", linesPerSecond),
                    lineCount,
                    totalProcessedCount,
                    unroutableCdrCount);

        } catch (IOException e) {
            log.error("Outcome for file [{}]: FAILED. Reason: Critical error reading from stream.",
                    fileInfo.getFilename(), e);
            CdrData tempData = new CdrData();
            tempData.setRawCdrLine("STREAM_ROUTING_READ_ERROR");
            tempData.setFileInfo(fileInfo);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION,
                    e.getMessage(),
                    "RouteStream_IOException", null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupRecordsForFile(Long fileInfoId) {
        log.debug("Cleaning up existing records for FileInfo ID: {}", fileInfoId);
        int deletedCallRecords = callRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        int deletedFailedRecords = failedCallRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        log.info("Cleanup complete for FileInfo ID {}: Deleted {} CallRecords and {} FailedCallRecords.",
                fileInfoId, deletedCallRecords, deletedFailedRecords);
    }

    @Transactional
    public void processFileInfo(Long fileInfoId) {
        long startTime = System.currentTimeMillis();
        log.info("Starting processing for FileInfo ID: {}", fileInfoId);
        
        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        if (fileInfo == null) {
            log.error("Outcome for FileInfo ID {}: FAILED. Reason: FileInfo record not found.", fileInfoId);
            return;
        }

        if (fileInfo.getFileContent() == null) {
            log.error("Outcome for FileInfo ID {}: FAILED. Reason: No file content is archived for this record.",
                    fileInfoId);
            return;
        }

        Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfoId);

        if (!fileDataOpt.isPresent()) {
            log.error("Outcome for FileInfo ID {}: FAILED. Reason: Failed to retrieve file content stream.",
                    fileInfoId);
            return;
        }

        try (InputStream contentStream = fileDataOpt.get().content()) {
            Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
            Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

            processStreamContent(fileInfo, contentStream, extensionLimits, extensionRanges);

        } catch (IOException e) {
            log.error("Outcome for FileInfo ID {}: FAILED. Reason: Failed to read file content stream.",
                    fileInfoId, e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            log.info("Total processing time for FileInfo ID {}: {} ms", fileInfoId, duration);
        }
    }

    @Transactional
    public void reprocessFileInfo(Long fileInfoId, boolean cleanupExistingRecords) {
        long startTime = System.currentTimeMillis();
        log.info("Starting reprocessing for FileInfo ID: {}, Cleanup: {}", fileInfoId, cleanupExistingRecords);

        if (cleanupExistingRecords) {
            cleanupRecordsForFile(fileInfoId);
        }
        processFileInfo(fileInfoId);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Reprocessing complete for FileInfo ID {}. Total Time: {} ms", fileInfoId, duration);
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
            
            // Local map
            Map<String, Integer> currentHeaderMap = null;

            try (InputStream inputStream = fileDataOpt.get().content();
                 InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                 BufferedReader bufferedReader = new BufferedReader(reader)) {

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    lineCount++;
                    String trimmedLine = line.trim();
                    if (trimmedLine.isEmpty()) continue;

                    if (currentHeaderMap == null && initialParser.isHeaderLine(trimmedLine)) {
                        currentHeaderMap = initialParser.parseHeader(trimmedLine);
                        continue;
                    }

                    if (currentHeaderMap == null) {
                        log.warn("Quarantining line {} - header not processed: {}", lineCount, trimmedLine);
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

                    CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null, currentHeaderMap);
                    if (preliminaryCdrData == null) {
                        skippedLines++;
                        continue;
                    }

                    Optional<CommunicationLocation> targetCommLocationOpt =
                            commLocationLookupService.findBestCommunicationLocation(
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
                        CdrProcessor finalProcessor = getProcessorForPlantType(
                                targetCommLocation.getPlantTypeId());

                        LineProcessingContext context = LineProcessingContext.builder()
                                .cdrLine(trimmedLine)
                                .commLocation(targetCommLocation)
                                .cdrProcessor(finalProcessor)
                                .extensionRanges(extensionRanges)
                                .extensionLimits(extensionLimits)
                                .fileInfo(fileInfo)
                                .headerPositions(currentHeaderMap) // Pass map
                                .build();

                        ProcessingOutcome outcome = cdrProcessorService.processSingleCdrLineSync(context);
                        switch (outcome) {
                            case SUCCESS -> successfulRecords++;
                            case QUARANTINED -> quarantinedRecords++;
                            case SKIPPED -> skippedLines++;
                        }
                    } else {
                        log.debug("Could not route CDR at line {} to a CommunicationLocation", lineCount);
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
            
            log.info("Sync processing completed for FileInfo ID {}: {} lines. Time: {}ms. {} successful, {} quarantined, {} skipped",
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