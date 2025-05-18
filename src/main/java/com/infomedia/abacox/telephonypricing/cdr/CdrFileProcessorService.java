package com.infomedia.abacox.telephonypricing.cdr;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.FileInfo; // Assuming you have this
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
public class CdrFileProcessorService {

    private final CiscoCm60CdrProcessor ciscoCm60Processor; // Inject specific processor
    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CommunicationLocationLookupService communicationLocationLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService; // To save FileInfo
    private final CdrValidationService cdrValidationService;
    private final CallTypeDeterminationService callTypeDeterminationService;


    public CdrFileProcessorService(CiscoCm60CdrProcessor ciscoCm60Processor,
                                   CdrEnrichmentService cdrEnrichmentService,
                                   CallRecordPersistenceService callRecordPersistenceService,
                                   FailedCallRecordPersistenceService failedCallRecordPersistenceService,
                                   CommunicationLocationLookupService communicationLocationLookupService,
                                   FileInfoPersistenceService fileInfoPersistenceService,
                                   CdrValidationService cdrValidationService,
                                   CallTypeDeterminationService callTypeDeterminationService) {
        this.ciscoCm60Processor = ciscoCm60Processor;
        this.cdrEnrichmentService = cdrEnrichmentService;
        this.callRecordPersistenceService = callRecordPersistenceService;
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
        this.communicationLocationLookupService = communicationLocationLookupService;
        this.fileInfoPersistenceService = fileInfoPersistenceService;
        this.cdrValidationService = cdrValidationService;
        this.callTypeDeterminationService = callTypeDeterminationService;
    }
    
    // Each CDR processed in its own transaction to allow others to succeed if one fails
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleCdrLine(String cdrLine, FileInfo fileInfo, CommunicationLocation commLocation, ICdrTypeProcessor processor) {
        CdrData cdrData = null;
        try {
            cdrData = processor.evaluateFormat(cdrLine);
            if (cdrData == null) { // e.g., "INTEGER" line or skipped by parser
                return;
            }
            cdrData.setFileInfo(fileInfo); // Associate file info

            // Initial Validation (mimics ValidarCampos_CDR)
            List<String> validationErrors = cdrValidationService.validateInitialCdrData(cdrData, commLocation.getIndicator().getOriginCountryId());
            if (!validationErrors.isEmpty()) {
                String errorMsg = String.join("; ", validationErrors);
                log.warn("Initial CDR validation failed for line: {} - Errors: {}", cdrLine, errorMsg);
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        "INITIAL_VALIDATION_ERROR", errorMsg, "Validation", cdrData.getCallingPartyNumber(), null);
                return; // Stop processing this CDR
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, commLocation);

            if (cdrData.isMarkedForQuarantine()) {
                failedCallRecordPersistenceService.saveFailedRecord(cdrLine, fileInfo, commLocation.getId(),
                        "ENRICHMENT_ERROR", cdrData.getQuarantineReason(), cdrData.getQuarantineStep(),
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

        // Create/get FileInfo for this stream
        // PHP's CDR_Actual_FileInfo logic is complex. Here, we create a new one per stream.
        FileInfo fileInfo = fileInfoPersistenceService.createOrGetFileInfo(filename, commLocationId, "STREAM_INPUT", inputStream);
        callTypeDeterminationService.resetExtensionLimitsCache(commLocation); // Reset for new file/stream

        // For now, directly use CiscoCm60Processor. A factory could be used for multiple types.
        ICdrTypeProcessor processor = ciscoCm60Processor;
        boolean headerProcessed = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            long lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.trim().isEmpty()) continue;

                // Basic header detection (Cisco specific)
                if (!headerProcessed && line.toLowerCase().contains(CiscoCm60CdrProcessor.CDR_RECORD_TYPE_HEADER)) {
                    processor.parseHeader(line);
                    headerProcessed = true;
                    log.info("Processed header line from stream: {}", filename);
                    continue;
                }

                if (!headerProcessed) {
                    log.warn("Skipping line {} as header has not been processed yet: {}", lineCount, line);
                    // Potentially save to failed records if strict header requirement
                    failedCallRecordPersistenceService.saveFailedRecord(line, fileInfo, commLocationId,
                            "MISSING_HEADER", "CDR data encountered before header", "StreamRead", null, null);
                    continue;
                }
                
                processSingleCdrLine(line, fileInfo, commLocation, processor);

            }
            log.info("Finished processing stream: {}. Total lines read: {}", filename, lineCount);
        } catch (IOException e) {
            log.error("Error reading CDR stream: {}", filename, e);
            // Save a general failure for the file if needed
            failedCallRecordPersistenceService.saveFailedRecord("STREAM_READ_ERROR", fileInfo, commLocationId,
                    "IO_EXCEPTION", e.getMessage(), "StreamRead", null, null);
        }
    }
}