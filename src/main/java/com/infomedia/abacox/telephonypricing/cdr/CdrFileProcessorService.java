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

    // Keep existing dependencies
    private final CiscoCm60CdrProcessor ciscoCm60Processor; // Example, could be injected based on plant type
    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CommunicationLocationLookupService communicationLocationLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrValidationService cdrValidationService;
    private final CallTypeDeterminationService callTypeDeterminationService;
    private final CdrConfigService cdrConfigService;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleCdrLine(String cdrLine, FileInfo fileInfo, CommunicationLocation commLocation, ICdrTypeProcessor processor) {
        CdrData cdrData = null;
        log.trace("Processing single CDR line: {}", cdrLine);
        try {
            cdrData = processor.evaluateFormat(cdrLine, commLocation);
            if (cdrData == null) {
                log.trace("Processor returned null for line, skipping: {}", cdrLine);
                return;
            }
            cdrData.setFileInfo(fileInfo);
            cdrData.setCommLocationId(commLocation.getId());

            boolean isValid = cdrValidationService.validateInitialCdrData(cdrData, commLocation.getIndicator().getOriginCountryId());
            if (!isValid || cdrData.isMarkedForQuarantine()) {
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                                                QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                                                QuarantineErrorType.INITIAL_VALIDATION_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
                return;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, commLocation);

            if (cdrData.isMarkedForQuarantine()) {
                log.warn("CDR marked for quarantine after enrichment. Reason: {}, Step: {}", cdrData.getQuarantineReason(), cdrData.getQuarantineStep());
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                                                QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                                                QuarantineErrorType.ENRICHMENT_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
            } else {
                callRecordPersistenceService.saveOrUpdateCallRecord(cdrData, commLocation);
            }
        } catch (Exception e) {
            log.error("Unhandled exception processing CDR line: {}", cdrLine, e);
            String step = (cdrData != null && cdrData.getQuarantineStep() != null) ? cdrData.getQuarantineStep() : "UNKNOWN_PROCESSING_STEP";
            if (cdrData == null) {
                cdrData = new CdrData();
                cdrData.setRawCdrLine(cdrLine);
                cdrData.setFileInfo(fileInfo);
                cdrData.setCommLocationId(commLocation.getId());
            }
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.UNHANDLED_EXCEPTION, e.getMessage(), step, null);
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

        callTypeDeterminationService.resetExtensionLimitsCache(commLocation);

        // TODO: Implement a factory or strategy to select the correct processor based on commLocation.getPlantTypeId()
        // For now, we are hardcoding to CiscoCm60CdrProcessor as per the initial request context.
        ICdrTypeProcessor processor = ciscoCm60Processor;
        boolean headerProcessed = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            long lineCount = 0;
            long processedCdrCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) {
                    log.trace("Skipping empty line at line number {}", lineCount);
                    continue;
                }
                log.trace("Read line {}: {}", lineCount, trimmedLine);

                // Use the processor's method to check if it's a header
                if (!headerProcessed && processor.isHeaderLine(trimmedLine)) {
                    processor.parseHeader(trimmedLine);
                    headerProcessed = true;
                    log.info("Processed header line using {} for stream: {}", processor.getClass().getSimpleName(), filename);
                    continue;
                }

                if (!headerProcessed) {
                    log.warn("Skipping line {} as header has not been processed yet (checked by {}): {}",
                            lineCount, processor.getClass().getSimpleName(), trimmedLine);
                    CdrData tempData = new CdrData(); tempData.setRawCdrLine(trimmedLine); tempData.setFileInfo(fileInfo); tempData.setCommLocationId(commLocationId);
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.MISSING_HEADER, "CDR data encountered before header", "StreamRead_HeaderCheck", null);
                    continue;
                }

                processSingleCdrLine(trimmedLine, fileInfo, commLocation, processor);
                processedCdrCount++;

                if (processedCdrCount > 0 && processedCdrCount % cdrConfigService.CDR_PROCESSING_BATCH_SIZE == 0) {
                    log.info("Processed a batch of {} CDRs from stream {}. Total processed so far: {}",
                            cdrConfigService.CDR_PROCESSING_BATCH_SIZE, filename, processedCdrCount);
                }
            }
            log.info("Finished processing stream: {}. Total lines read: {}, Total CDRs processed: {}", filename, lineCount, processedCdrCount);
        } catch (IOException e) {
            log.error("Error reading CDR stream: {}", filename, e);
            CdrData tempData = new CdrData(); tempData.setRawCdrLine("STREAM_READ_ERROR"); tempData.setFileInfo(fileInfo); tempData.setCommLocationId(commLocationId);
            failedCallRecordPersistenceService.quarantineRecord(tempData,
                    QuarantineErrorType.IO_EXCEPTION, e.getMessage(), "StreamRead_IOException", null);
        }
    }
}