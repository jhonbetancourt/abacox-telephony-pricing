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
    private final CallTypeDeterminationService callTypeDeterminationService;
    private final CdrConfigService cdrConfigService;


    // Each CDR processed in its own transaction to allow others to succeed if one fails
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleCdrLine(String cdrLine, FileInfo fileInfo, CommunicationLocation commLocation, ICdrTypeProcessor processor) {
        CdrData cdrData = null;
        try {
            cdrData = processor.evaluateFormat(cdrLine);
            if (cdrData == null) { // e.g., "INTEGER" line or skipped by parser
                return;
            }
            cdrData.setFileInfo(fileInfo);
            cdrData.setCommLocationId(commLocation.getId()); // Set commLocationId early

            // Initial Validation (mimics ValidarCampos_CDR)
            // PHP: ValidarCampos_CDR($info_arr, $link);
            List<String> validationErrors = cdrValidationService.validateInitialCdrData(cdrData, commLocation.getIndicator().getOriginCountryId());
            if (!validationErrors.isEmpty()) {
                String errorMsg = String.join("; ", validationErrors);
                log.warn("Initial CDR validation failed for line: {} - Errors: {}", cdrLine, errorMsg);
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        "INITIAL_VALIDATION_ERROR", errorMsg, "Validation", cdrData.getCallingPartyNumber(), null);
                return;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, commLocation);

            if (cdrData.isMarkedForQuarantine()) {
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:") ? "PARSER_QUARANTINE" : "ENRICHMENT_ERROR",
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
        CommunicationLocation commLocation = communicationLocationLookupService.findById(commLocationId)
                .orElseThrow(() -> new IllegalArgumentException("CommunicationLocation not found: " + commLocationId));

        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, commLocationId, "STREAM_INPUT", inputStream);
        callTypeDeterminationService.resetExtensionLimitsCache(commLocation);

        ICdrTypeProcessor processor = ciscoCm60Processor; // Could be a factory based on commLocation.getPlantTypeId()
        boolean headerProcessed = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            long lineCount = 0;
            long processedCdrCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.trim().isEmpty()) continue;

                if (!headerProcessed && line.toLowerCase().contains(CiscoCm60CdrProcessor.CDR_RECORD_TYPE_HEADER)) {
                    processor.parseHeader(line);
                    headerProcessed = true;
                    log.info("Processed header line from stream: {}", filename);
                    continue;
                }

                if (!headerProcessed) {
                    log.warn("Skipping line {} as header has not been processed yet: {}", lineCount, line);
                    failedCallRecordPersistenceService.saveFailedRecord(line, fileInfo, commLocationId,
                            "MISSING_HEADER", "CDR data encountered before header", "StreamRead", null, null);
                    continue;
                }

                processSingleCdrLine(line, fileInfo, commLocation, processor);
                processedCdrCount++;

                if (processedCdrCount >= cdrConfigService.CDR_PROCESSING_BATCH_SIZE) {
                    log.info("Processed a batch of {} CDRs from stream {}. Pausing if necessary or continuing.",
                            cdrConfigService.CDR_PROCESSING_BATCH_SIZE, filename);
                    // In a real scenario, you might yield here or check for stop signals
                    processedCdrCount = 0; // Reset for next batch
                }
            }
            log.info("Finished processing stream: {}. Total lines read: {}", filename, lineCount);
        } catch (IOException e) {
            log.error("Error reading CDR stream: {}", filename, e);
            failedCallRecordPersistenceService.saveFailedRecord("STREAM_READ_ERROR", fileInfo, commLocationId,
                    "IO_EXCEPTION", e.getMessage(), "StreamRead", null, null);
        }
    }
}