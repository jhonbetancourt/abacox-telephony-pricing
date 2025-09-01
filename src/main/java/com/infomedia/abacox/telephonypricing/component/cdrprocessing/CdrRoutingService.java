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
import java.util.zip.DataFormatException;

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
    private final CdrFormatDetectorService cdrFormatDetectorService;

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifier().equals(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    protected void routeAndProcessCdrStreamInternal(String filename, InputStream inputStream, Long plantTypeId, boolean forceProcess) {
        log.info("Starting CDR stream processing for file: {}, PlantTypeID: {}, Force: {}", filename, plantTypeId, forceProcess);

        byte[] streamBytes;
        try {
            streamBytes = inputStream.readAllBytes();
            log.debug("Read {} bytes from input stream for file: {}", streamBytes.length, filename);
        } catch (IOException e) {
            log.info("Outcome for file [{}]: FAILED. Reason: Failed to read input stream.", filename, e);
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_READ_ERROR_ROUTING: " + filename);
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to read input stream " + filename, "RouteStreamInit_Read", null);
            return;
        }

        FileInfoPersistenceService.FileInfoCreationResult fileInfoResult = fileInfoPersistenceService.createOrGetFileInfo(filename, plantTypeId, "ROUTED_STREAM", streamBytes);
        FileInfo fileInfo = fileInfoResult.getFileInfo();

        if (!fileInfoResult.isNew() && !forceProcess) {
            log.info("Outcome for file [{}]: SKIPPED. Reason: File has already been processed (Checksum: {}). Use force flag to reprocess.", filename, fileInfo.getChecksum());
            return;
        }

        Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
        Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

        if (fileInfo == null || fileInfo.getId() == null) {
            log.info("Outcome for file [{}]: FAILED. Reason: Failed to create or get FileInfo.", filename);
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_FILEINFO_ERROR_ROUTING: " + filename);
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to establish FileInfo for routed stream " + filename, "RouteStreamInit_FileInfo", null);
            return;
        }
        log.debug("Using FileInfo ID: {} for routed stream: {}", fileInfo.getId(), filename);

        CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
        boolean headerProcessedByInitialParser = false;
        long lineCount = 0;
        long totalProcessedCount = 0;
        long unroutableCdrCount = 0;

        List<LineProcessingContext> batch = new ArrayList<>(CdrConfigService.CDR_PROCESSING_BATCH_SIZE);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(streamBytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) continue;

                log.trace("Routing line {}: {}", lineCount, trimmedLine);

                if (!headerProcessedByInitialParser && initialParser.isHeaderLine(trimmedLine)) {
                    initialParser.parseHeader(trimmedLine);
                    headerProcessedByInitialParser = true;
                    log.debug("Processed header line using initial parser {} for stream: {}", initialParser.getClass().getSimpleName(), filename);
                    continue;
                }

                if (!headerProcessedByInitialParser) {
                    log.debug("Skipping line {} as header has not been processed by initial parser: {}", lineCount, trimmedLine);
                    CdrData tempData = new CdrData(); tempData.setRawCdrLine(trimmedLine); tempData.setFileInfo(fileInfo);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER, "CDR data encountered before header (routing stage)", "RouteStream_HeaderCheck", null);
                    unroutableCdrCount++;
                    continue;
                }

                CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null);

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
                            .build();
                    batch.add(lineProcessingContext);
                } else {
                    log.debug("Could not determine target CommunicationLocation for line {}: {}", lineCount, trimmedLine);
                    preliminaryCdrData.setCommLocationId(null);
                    failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                            QuarantineErrorType.PENDING_ASSOCIATION, "Could not route CDR to a CommunicationLocation", "CdrRoutingService_Unroutable", null);
                    unroutableCdrCount++;
                }

                if (batch.size() >= CdrConfigService.CDR_PROCESSING_BATCH_SIZE) {
                    log.debug("Processing a batch of {} CDRs...", batch.size());
                    cdrProcessorService.processCdrBatch(batch);
                    totalProcessedCount += batch.size();
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                log.debug("Processing the final batch of {} CDRs...", batch.size());
                cdrProcessorService.processCdrBatch(batch);
                totalProcessedCount += batch.size();
                batch.clear();
            }

            log.info("Outcome for file [{}]: SUCCESS. Total lines read: {}, Processed CDRs: {}, Unroutable CDRs: {}",
                    filename, lineCount, totalProcessedCount, unroutableCdrCount);

        } catch (IOException e) {
            log.info("Outcome for file [{}]: FAILED. Reason: Critical error reading from in-memory stream.", filename, e);
            CdrData tempData = new CdrData(); tempData.setRawCdrLine("STREAM_ROUTING_IN_MEMORY_READ_ERROR"); tempData.setFileInfo(fileInfo);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION, e.getMessage(), "RouteStream_InMemory_IOException", null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupRecordsForFile(Long fileInfoId) {
        log.debug("Cleaning up existing records for FileInfo ID: {}", fileInfoId);
        int deletedCallRecords = callRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        int deletedFailedRecords = failedCallRecordPersistenceService.deleteByFileInfoId(fileInfoId);
        log.info("Cleanup complete for FileInfo ID {}: Deleted {} CallRecords and {} FailedCallRecords.", fileInfoId, deletedCallRecords, deletedFailedRecords);
    }

    public void processFileInfo(Long fileInfoId) {
        log.info("Starting reprocessing for FileInfo ID: {}", fileInfoId);
        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        if (fileInfo == null) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: FileInfo record not found.", fileInfoId);
            return;
        }

        if (fileInfo.getFileContent() == null || fileInfo.getFileContent().length == 0) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: No file content is archived for this record.", fileInfoId);
            return;
        }

        try {
            byte[] decompressedContent = CdrUtil.decompress(fileInfo.getFileContent());
            InputStream inputStream = new ByteArrayInputStream(decompressedContent);

            routeAndProcessCdrStreamInternal(fileInfo.getFilename(), inputStream, fileInfo.getParentId().longValue(), false);

        } catch (IOException | DataFormatException e) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: Failed to decompress file content.", fileInfoId, e);
        }
    }

    public void reprocessFileInfo(Long fileInfoId) {
        reprocessFileInfo(fileInfoId, false); // Default to cleaning up existing records
    }

    public void reprocessFileInfo(Long fileInfoId, boolean cleanupExistingRecords) {
        log.info("Starting reprocessing for FileInfo ID: {}, Cleanup: {}", fileInfoId, cleanupExistingRecords);
        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        if (fileInfo == null) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: FileInfo record not found.", fileInfoId);
            return;
        }

        if (fileInfo.getFileContent() == null || fileInfo.getFileContent().length == 0) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: No file content is archived for this record.", fileInfoId);
            return;
        }

        try {
            if (cleanupExistingRecords) {
                cleanupRecordsForFile(fileInfoId);
            }

            byte[] decompressedContent = CdrUtil.decompress(fileInfo.getFileContent());
            InputStream inputStream = new ByteArrayInputStream(decompressedContent);

            // Reprocessing is always "forced" in the sense that it bypasses the checksum check
            routeAndProcessCdrStreamInternal(fileInfo.getFilename(), inputStream, fileInfo.getParentId().longValue(), true);

        } catch (IOException | DataFormatException e) {
            log.info("Outcome for FileInfo ID {}: REPROCESS_FAILED. Reason: Failed to decompress file content.", fileInfoId, e);
        }
    }

    /**
     * Processes a single CDR file stream synchronously within a single transaction.
     *
     * @param filename    The name of the file.
     * @param inputStream The content of the file.
     * @return A DTO summarizing the processing results.
     */
    @Transactional // This annotation makes the entire method a single transaction.
    public CdrProcessingResultDto routeAndProcessCdrStreamSync(String filename, InputStream inputStream) {
        log.info("Starting SYNCHRONOUS CDR stream processing for file: {}", filename);

        byte[] streamBytes;
        try {
            streamBytes = inputStream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read input stream for sync processing of file: {}", filename, e);
            throw new UncheckedIOException(e);
        }

        Long plantTypeId = cdrFormatDetectorService.detectPlantType(streamBytes)
                .orElseThrow(() -> new ValidationException("Could not recognize CDR format for file: " + filename));

        // Create FileInfo, but fail if it already exists.
        FileInfoPersistenceService.FileInfoCreationResult fileInfoResult = fileInfoPersistenceService.createOrGetFileInfo(filename, plantTypeId, "SYNC_STREAM", streamBytes);
        if (!fileInfoResult.isNew()) {
            throw new ValidationException("File with the same content has already been processed. FileInfo ID: " + fileInfoResult.getFileInfo().getId());
        }
        FileInfo fileInfo = fileInfoResult.getFileInfo();

        // CORRECTED: Use the service to update the status to IN_PROGRESS
        fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.IN_PROGRESS);

        CdrProcessingResultDto.CdrProcessingResultDtoBuilder resultBuilder = CdrProcessingResultDto.builder().fileInfoId(fileInfo.getId());
        long lineCount = 0;
        int successfulRecords = 0;
        int quarantinedRecords = 0;
        int skippedLines = 0;
        FileInfo.ProcessingStatus finalStatus = FileInfo.ProcessingStatus.FAILED; // Default to FAILED
        String finalMessage = "An unexpected error occurred.";

        try {
            Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
            Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();
            CdrProcessor initialParser = getProcessorForPlantType(plantTypeId);
            boolean headerProcessed = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(streamBytes), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    String trimmedLine = line.trim();
                    if (trimmedLine.isEmpty()) continue;

                    if (!headerProcessed && initialParser.isHeaderLine(trimmedLine)) {
                        initialParser.parseHeader(trimmedLine);
                        headerProcessed = true;
                        continue;
                    }
                    if (!headerProcessed) {
                        quarantinedRecords++;
                        continue; // Skip lines before header
                    }

                    CdrData preliminaryCdrData = initialParser.evaluateFormat(trimmedLine, null, null);
                    if (preliminaryCdrData == null) {
                        skippedLines++;
                        continue;
                    }

                    Optional<CommunicationLocation> targetCommLocationOpt = commLocationLookupService.findBestCommunicationLocation(
                            plantTypeId, preliminaryCdrData.getCallingPartyNumber(), preliminaryCdrData.getCallingPartyNumberPartition(),
                            preliminaryCdrData.getFinalCalledPartyNumber(), preliminaryCdrData.getFinalCalledPartyNumberPartition(),
                            preliminaryCdrData.getLastRedirectDn(), preliminaryCdrData.getLastRedirectDnPartition(),
                            preliminaryCdrData.getDateTimeOrigination());

                    if (targetCommLocationOpt.isPresent()) {
                        LineProcessingContext context = LineProcessingContext.builder()
                                .cdrLine(trimmedLine).commLocation(targetCommLocationOpt.get())
                                .cdrProcessor(initialParser).extensionRanges(extensionRanges)
                                .extensionLimits(extensionLimits).fileInfo(fileInfo).build();

                        ProcessingOutcome outcome = cdrProcessorService.processSingleCdrLineSync(context);
                        switch (outcome) {
                            case SUCCESS -> successfulRecords++;
                            case QUARANTINED -> quarantinedRecords++;
                            case SKIPPED -> skippedLines++;
                        }
                    } else {
                        quarantinedRecords++;
                        // Manually quarantine unroutable records
                        preliminaryCdrData.setRawCdrLine(trimmedLine);
                        preliminaryCdrData.setFileInfo(fileInfo);
                        failedCallRecordPersistenceService.quarantineRecord(preliminaryCdrData,
                                QuarantineErrorType.PENDING_ASSOCIATION, "Could not route CDR to a CommunicationLocation", "CdrRoutingService_SyncUnroutable", null);
                    }
                }
            }

            finalStatus = FileInfo.ProcessingStatus.COMPLETED;
            finalMessage = "File processed successfully.";

        } catch (Exception e) {
            log.error("Critical failure during synchronous processing of file ID: {}. Rolling back.", fileInfo.getId(), e);
            finalStatus = FileInfo.ProcessingStatus.FAILED;
            finalMessage = "A critical error occurred: " + e.getMessage();
            // Re-throw to ensure transaction rollback
            throw new RuntimeException("Synchronous processing failed for file " + filename, e);
        } finally {
            // This ensures the final status is always set, even on exceptions.
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