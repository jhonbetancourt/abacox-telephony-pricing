// File: com/infomedia/abacox/telephonypricing/cdr/CdrFileProcessorService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import jakarta.persistence.EntityManager; // Added for find
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrFileProcessorService {

    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CommunicationLocationLookupService communicationLocationLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrValidationService cdrValidationService;
    private final CdrConfigService cdrConfigService;
    private final List<CdrTypeProcessor> cdrTypeProcessors;
    private final EntityManager entityManager; // Added

    private CdrTypeProcessor getProcessorForPlantType(Long plantTypeId) {
        String plantTypeIdStr = String.valueOf(plantTypeId);
        return cdrTypeProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifier().equals(plantTypeIdStr))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleCdrLine(String cdrLine, Long fileInfoId, CommunicationLocation targetCommLocation, CdrTypeProcessor processor) {
        CdrData cdrData = null;
        FileInfo currentFileInfo = null;
        log.trace("Processing single CDR line for CommLocation {}: {}, FileInfo ID: {}", targetCommLocation.getId(), cdrLine, fileInfoId);
        try {
            if (fileInfoId != null) {
                currentFileInfo = entityManager.find(FileInfo.class, fileInfoId);
                if (currentFileInfo == null) {
                    log.error("FileInfo not found for ID: {} during single CDR line processing. This should not happen.", fileInfoId);
                    // Fallback or throw, for now, attempt to quarantine without full FileInfo
                    CdrData tempData = new CdrData();
                    tempData.setRawCdrLine(cdrLine);
                    tempData.setCommLocationId(targetCommLocation.getId());
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.UNHANDLED_EXCEPTION, "Critical: FileInfo missing in transaction for ID: " + fileInfoId,
                            "ProcessSingleLine_FileInfoMissing", null);
                    return;
                }
            } else {
                 log.warn("fileInfoId is null in processSingleCdrLine. This might lead to issues if FileInfo is required downstream.");
            }


            cdrData = processor.evaluateFormat(cdrLine, targetCommLocation);
            if (cdrData == null) {
                log.trace("Processor returned null for line, skipping: {}", cdrLine);
                return;
            }
            cdrData.setFileInfo(currentFileInfo); // Use the managed FileInfo
            cdrData.setCommLocationId(targetCommLocation.getId());

            boolean isValid = cdrValidationService.validateInitialCdrData(cdrData);
            if (!isValid || cdrData.isMarkedForQuarantine()) {
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                                                QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                                                QuarantineErrorType.INITIAL_VALIDATION_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
                return;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, targetCommLocation);

            if (cdrData.isMarkedForQuarantine()) {
                log.warn("CDR marked for quarantine after enrichment. Reason: {}, Step: {}", cdrData.getQuarantineReason(), cdrData.getQuarantineStep());
                 QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                                                QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                                                QuarantineErrorType.ENRICHMENT_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
            } else {
                callRecordPersistenceService.saveOrUpdateCallRecord(cdrData, targetCommLocation);
            }
        } catch (Exception e) {
            log.error("Unhandled exception processing CDR line: {} for CommLocation: {}", cdrLine, targetCommLocation.getId(), e);
            String step = (cdrData != null && cdrData.getQuarantineStep() != null) ? cdrData.getQuarantineStep() : "UNKNOWN_SINGLE_LINE_PROCESSING_STEP";
            if (cdrData == null) {
                cdrData = new CdrData();
                cdrData.setRawCdrLine(cdrLine);
            }
            // Ensure FileInfo is set on cdrData if available, even in error case
            if (cdrData.getFileInfo() == null && currentFileInfo != null) {
                cdrData.setFileInfo(currentFileInfo);
            }
            cdrData.setCommLocationId(targetCommLocation.getId());

            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.UNHANDLED_EXCEPTION, e.getMessage(), step, null);
        }
    }


    @Transactional
    public void processCdrStreamForKnownCommLocation(String filename, InputStream inputStream, Long commLocationId) {
        log.info("Starting CDR stream processing for KNOWN CommLocationID: {}, File: {}", commLocationId, filename);
        CommunicationLocation commLocation = communicationLocationLookupService.findById(commLocationId)
                .orElseThrow(() -> {
                    log.error("CommunicationLocation not found for ID: {}", commLocationId);
                    return new IllegalArgumentException("CommunicationLocation not found: " + commLocationId);
                });

        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, null, "PRE_ROUTED_STREAM");
        if (fileInfo == null || fileInfo.getId() == null) {
            log.error("Failed to create or get FileInfo for stream: {}. Aborting processing.", filename);
            // Optionally quarantine a generic stream error record
            CdrData streamErrorData = new CdrData();
            streamErrorData.setRawCdrLine("STREAM_FILEINFO_ERROR: " + filename);
            streamErrorData.setCommLocationId(commLocationId);
            failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.IO_EXCEPTION,
                "Failed to establish FileInfo for stream " + filename, "StreamInit_FileInfo", null);
            return;
        }
        Long fileInfoId = fileInfo.getId(); // Get the ID after it's persisted/fetched
        log.debug("Using FileInfo ID: {} for pre-routed stream: {}", fileInfoId, filename);

        CdrTypeProcessor processor = getProcessorForPlantType(commLocation.getPlantTypeId());
        boolean headerProcessed = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            long lineCount = 0;
            long processedCdrCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) continue;

                if (!headerProcessed && processor.isHeaderLine(trimmedLine)) {
                    processor.parseHeader(trimmedLine);
                    headerProcessed = true;
                    log.info("Processed header line for known CommLocation stream: {}", filename);
                    continue;
                }

                if (!headerProcessed) {
                    log.warn("Skipping line {} as header has not been processed (known CommLocation stream): {}", lineCount, trimmedLine);
                    CdrData tempData = new CdrData(); tempData.setRawCdrLine(trimmedLine); tempData.setFileInfo(fileInfo); tempData.setCommLocationId(commLocationId);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER, "CDR data encountered before header", "KnownCommLocStream_HeaderCheck", null);
                    continue;
                }

                processSingleCdrLine(trimmedLine, fileInfoId, commLocation, processor); // Pass ID
                processedCdrCount++;
                 if (processedCdrCount > 0 && processedCdrCount % cdrConfigService.CDR_PROCESSING_BATCH_SIZE == 0) {
                    log.info("Processed a batch of {} CDRs from known CommLocation stream {}. Total processed so far: {}",
                            cdrConfigService.CDR_PROCESSING_BATCH_SIZE, filename, processedCdrCount);
                }
            }
            log.info("Finished processing known CommLocation stream: {}. Total lines read: {}, Total CDRs processed: {}", filename, lineCount, processedCdrCount);
        } catch (IOException e) {
            log.error("Error reading known CommLocation CDR stream: {}", filename, e);
            CdrData tempData = new CdrData(); tempData.setRawCdrLine("KNOWN_COMMLOC_STREAM_READ_ERROR"); tempData.setFileInfo(fileInfo); tempData.setCommLocationId(commLocationId);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION, e.getMessage(), "KnownCommLocStream_IOException", null);
        }
    }
}