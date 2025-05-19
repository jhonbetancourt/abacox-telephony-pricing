// File: com/infomedia/abacox/telephonypricing/cdr/CdrFileProcessorService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
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

    private final CiscoCm60CdrProcessor ciscoCm60Processor;
    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CommunicationLocationLookupService communicationLocationLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrValidationService cdrValidationService;
    private final CallTypeDeterminationService callTypeDeterminationService; // For cache reset
    private final CdrConfigService cdrConfigService;


    // Each CDR processed in its own transaction to allow others to succeed if one fails
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleCdrLine(String cdrLine, FileInfo fileInfo, CommunicationLocation commLocation, ICdrTypeProcessor processor) {
        CdrData cdrData = null;
        log.trace("Processing single CDR line: {}", cdrLine);
        try {
            cdrData = processor.evaluateFormat(cdrLine, commLocation);
            if (cdrData == null) { // e.g., "INTEGER" line or skipped by parser
                log.trace("Parser returned null for line, skipping: {}", cdrLine);
                return;
            }
            cdrData.setFileInfo(fileInfo);
            cdrData.setCommLocationId(commLocation.getId());

            List<String> validationErrors = cdrValidationService.validateInitialCdrData(cdrData, commLocation.getIndicator().getOriginCountryId());
            if (!validationErrors.isEmpty() || cdrData.isMarkedForQuarantine()) {
                String errorMsg = cdrData.isMarkedForQuarantine() ? cdrData.getQuarantineReason() : String.join("; ", validationErrors);
                String errorType = cdrData.isMarkedForQuarantine() ? cdrData.getQuarantineStep().contains("Warning") ? "INITIAL_VALIDATION_WARNING" : "INITIAL_VALIDATION_ERROR" : "INITIAL_VALIDATION_ERROR";
                String step = cdrData.isMarkedForQuarantine() ? cdrData.getQuarantineStep() : "Validation";

                log.warn("Initial CDR validation failed or warning for line: {} - Reason: {}", cdrLine, errorMsg);
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        errorType, errorMsg, step, cdrData.getCallingPartyNumber(), null);
                return;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, commLocation);

            if (cdrData.isMarkedForQuarantine()) {
                log.warn("CDR marked for quarantine after enrichment. Reason: {}, Step: {}", cdrData.getQuarantineReason(), cdrData.getQuarantineStep());
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:") ? "PARSER_QUARANTINE" :
                        cdrData.getQuarantineStep().contains("Warning") ? "ENRICHMENT_WARNING" : "ENRICHMENT_ERROR",
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(),
                        cdrData.getCallingPartyNumber(), null);
            } else {
                callRecordPersistenceService.saveOrUpdateCallRecord(cdrData, commLocation);
            }
        } catch (Exception e) {
            log.error("Unhandled exception processing CDR line: {}", cdrLine, e);
            String step = (cdrData != null && cdrData.getQuarantineStep() != null) ? cdrData.getQuarantineStep() : "UNKNOWN_PROCESSING_STEP";
            String ext = (cdrData != null) ? cdrData.getCallingPartyNumber() : "UNKNOWN_EXT";
            failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                    "UNHANDLED_EXCEPTION", e.getMessage(), step, ext, null);
        }
    }


    public void processCdrStream(String filename, InputStream inputStream, Long commLocationId) {
        log.info("Starting CDR stream processing for file: {}, CommLocationID: {}", filename, commLocationId);
        CommunicationLocation commLocation = communicationLocationLookupService.findById(commLocationId)
                .orElseThrow(() -> {
                    log.error("CommunicationLocation not found for ID: {}", commLocationId);
                    return new IllegalArgumentException("CommunicationLocation not found: " + commLocationId);
                });

        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, commLocationId, "STREAM_INPUT", inputStream);
        log.debug("Using FileInfo ID: {} for stream: {}", fileInfo.getId(), filename);

        // Reset cache for this specific commLocation at the start of processing its file
        callTypeDeterminationService.resetExtensionLimitsCache(commLocation);

        ICdrTypeProcessor processor = ciscoCm60Processor; // Could be a factory based on commLocation.getPlantTypeId()
        boolean headerProcessed = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            long lineCount = 0;
            long processedCdrCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.trim().isEmpty()) {
                    log.trace("Skipping empty line at line number {}", lineCount);
                    continue;
                }
                log.trace("Read line {}: {}", lineCount, line);

                if (!headerProcessed && line.toLowerCase().startsWith(CiscoCm60CdrProcessor.CDR_RECORD_TYPE_HEADER.toLowerCase())) {
                    processor.parseHeader(line);
                    headerProcessed = true;
                    log.info("Processed header line from stream: {}", filename);
                    continue;
                }

                if (!headerProcessed) {
                    log.warn("Skipping line {} as header has not been processed yet: {}", lineCount, line);
                    failedCallRecordPersistenceService.saveFailedRecord(line, fileInfo, commLocationId,
                            "MISSING_HEADER", "CDR data encountered before header", "StreamRead_HeaderCheck", null, null);
                    continue;
                }

                processSingleCdrLine(line, fileInfo, commLocation, processor);
                processedCdrCount++;

                if (processedCdrCount > 0 && processedCdrCount % cdrConfigService.CDR_PROCESSING_BATCH_SIZE == 0) {
                    log.info("Processed a batch of {} CDRs from stream {}. Total processed so far: {}",
                            cdrConfigService.CDR_PROCESSING_BATCH_SIZE, filename, processedCdrCount);
                }
            }
            log.info("Finished processing stream: {}. Total lines read: {}, Total CDRs processed: {}", filename, lineCount, processedCdrCount);
        } catch (IOException e) {
            log.error("Error reading CDR stream: {}", filename, e);
            failedCallRecordPersistenceService.saveFailedRecord("STREAM_READ_ERROR", fileInfo, commLocationId,
                    "IO_EXCEPTION", e.getMessage(), "StreamRead_IOException", null, null);
        }
    }
}