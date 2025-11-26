// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrProcessorService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.Compression7zUtil;
import com.infomedia.abacox.telephonypricing.db.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
public class CdrProcessorService {

    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrValidationService cdrValidationService;
    private final List<CdrProcessor> cdrProcessors;
    private final CommunicationLocationLookupService commLocationLookupService;
    private final EmployeeLookupService employeeLookupService;
    private final FileInfoPersistenceService fileInfoPersistenceService;

    @PersistenceContext
    private EntityManager entityManager;

    public CdrProcessorService(CdrEnrichmentService cdrEnrichmentService,
                               CallRecordPersistenceService callRecordPersistenceService,
                               FailedCallRecordPersistenceService failedCallRecordPersistenceService,
                               CdrValidationService cdrValidationService,
                               List<CdrProcessor> cdrProcessors,
                               CommunicationLocationLookupService commLocationLookupService,
                               EmployeeLookupService employeeLookupService,
                               FileInfoPersistenceService fileInfoPersistenceService) {
        this.cdrEnrichmentService = cdrEnrichmentService;
        this.callRecordPersistenceService = callRecordPersistenceService;
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
        this.cdrValidationService = cdrValidationService;
        this.cdrProcessors = cdrProcessors;
        this.commLocationLookupService = commLocationLookupService;
        this.employeeLookupService = employeeLookupService;
        this.fileInfoPersistenceService = fileInfoPersistenceService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCdrBatch(List<LineProcessingContext> batch) {
        log.debug("Starting transactional processing for a batch of {} records.", batch.size());
        for (LineProcessingContext context : batch) {
            processSingleCdrLineInternal(context);
        }
        log.debug("Flushing and clearing EntityManager after processing batch.");
        entityManager.flush();
        entityManager.clear();
        log.debug("Completed transactional processing for batch of {} records.", batch.size());
    }

    public ProcessingOutcome processSingleCdrLineSync(LineProcessingContext lineProcessingContext) {
        return processSingleCdrLineInternal(lineProcessingContext);
    }

    private ProcessingOutcome processSingleCdrLineInternal(LineProcessingContext lineProcessingContext) {
        String cdrLine = lineProcessingContext.getCdrLine();
        CommunicationLocation targetCommLocation = lineProcessingContext.getCommLocation();

        log.debug("Processing CDR line for CommLocation {}: {}", targetCommLocation.getId(), cdrLine);

        CdrData cdrData = null;

        try {
            FileInfo currentFileInfo = lineProcessingContext.getFileInfo();

            CdrProcessor processor = lineProcessingContext.getCdrProcessor();
            
            // Pass the map from context
            cdrData = processor.evaluateFormat(
                    cdrLine, 
                    targetCommLocation, 
                    lineProcessingContext.getCommLocationExtensionLimits(), 
                    lineProcessingContext.getHeaderPositions()
            );

            if (cdrData == null) {
                log.trace("Processor returned null for line, skipping: {}", cdrLine);
                return ProcessingOutcome.SKIPPED;
            }

            cdrData.setFileInfo(currentFileInfo);
            cdrData.setCommLocationId(targetCommLocation.getId());

            boolean isValid = cdrValidationService.validateInitialCdrData(cdrData);
            if (!isValid || cdrData.isMarkedForQuarantine()) {
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                        QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                        QuarantineErrorType.INITIAL_VALIDATION_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
                log.debug("Outcome for CDR line -> Status: QUARANTINED, Details: Validation failed. Reason: {}", cdrData.getQuarantineReason());
                return ProcessingOutcome.QUARANTINED;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, lineProcessingContext);

            if (cdrData.isMarkedForQuarantine()) {
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                        QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                        QuarantineErrorType.ENRICHMENT_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
                log.debug("Outcome for CDR line -> Status: QUARANTINED, Details: Enrichment failed. Reason: {}", cdrData.getQuarantineReason());
                return ProcessingOutcome.QUARANTINED;
            } else {
                CallRecord savedRecord = callRecordPersistenceService.saveOrUpdateCallRecord(cdrData, targetCommLocation);
                if (savedRecord != null) {
                    log.debug("Outcome for CDR line -> Status: SUCCESS, Details: CallRecord ID: {}", savedRecord.getId());
                    return ProcessingOutcome.SUCCESS;
                } else {
                    log.debug("Outcome for CDR line -> Status: QUARANTINED, Details: Persistence failed. Reason: {}", cdrData.getQuarantineReason());
                    return ProcessingOutcome.QUARANTINED;
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception processing CDR line: {} for CommLocation: {}", cdrLine, targetCommLocation.getId(), e);
            String step = (cdrData != null && cdrData.getQuarantineStep() != null) ? cdrData.getQuarantineStep() : "UNKNOWN_SINGLE_LINE_PROCESSING_STEP";
            if (cdrData == null) {
                cdrData = new CdrData();
                cdrData.setRawCdrLine(cdrLine);
            }
            if (cdrData.getFileInfo() == null && lineProcessingContext.getFileInfo() != null) {
                cdrData.setFileInfo(lineProcessingContext.getFileInfo());
            }
            cdrData.setCommLocationId(targetCommLocation.getId());

            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.UNHANDLED_EXCEPTION, e.getMessage(), step, null);
            log.debug("Outcome for CDR line -> Status: QUARANTINED, Details: Unhandled Exception: {}", e.getMessage());
            return ProcessingOutcome.QUARANTINED;
        }
    }

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifiers().contains(plantTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CDR processor found for plant type ID: " + plantTypeId));
    }

    private Optional<LineProcessingContext> buildReprocessingContext(String cdrLine, Long commLocationId, Long fileInfoId) {
        if (commLocationId == null) {
            log.debug("Cannot reprocess record without a CommunicationLocation ID. CDR Line: {}", cdrLine);
            return Optional.empty();
        }

        Optional<CommunicationLocation> commLocationOpt = commLocationLookupService.findById(commLocationId);
        if (commLocationOpt.isEmpty()) {
            log.debug("CommunicationLocation with ID {} not found for reprocessing. CDR Line: {}", commLocationId, cdrLine);
            return Optional.empty();
        }
        CommunicationLocation commLocation = commLocationOpt.get();

        FileInfo fileInfo = fileInfoPersistenceService.findById(fileInfoId);

        CdrProcessor processor = getProcessorForPlantType(commLocation.getPlantTypeId());
        Map<Long, ExtensionLimits> extensionLimits = employeeLookupService.getExtensionLimits();
        Map<Long, List<ExtensionRange>> extensionRanges = employeeLookupService.getExtensionRanges();

        // *** Key Fix: Retrieve Header Map for Reprocessing ***
        Map<String, Integer> headerPositions = new HashMap<>();
        if (fileInfo != null) {
            Optional<FileInfoData> fileDataOpt = fileInfoPersistenceService.getOriginalFileData(fileInfoId);
            if (fileDataOpt.isPresent()) {
                try (InputStream is = fileDataOpt.get().content();
                     InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(reader)) {
                    
                    String line;
                    // Scan the first 5 lines looking for a header
                    int linesChecked = 0;
                    while ((line = br.readLine()) != null && linesChecked < 5) {
                        line = line.trim();
                        if (!line.isEmpty() && processor.isHeaderLine(line)) {
                            headerPositions = processor.parseHeader(line);
                            log.debug("Found header for reprocessing in file ID {}", fileInfoId);
                            break;
                        }
                        linesChecked++;
                    }
                    if (headerPositions.isEmpty()) {
                        log.warn("Header not found in first 50 lines of FileInfo ID {} during reprocessing setup.", fileInfoId);
                    }
                } catch (IOException e) {
                    log.error("Failed to read header from FileInfo ID {} for reprocessing.", fileInfoId, e);
                }
            }
        } else {
            log.warn("FileInfo ID {} not found. Reprocessing will likely fail if parser requires header map.", fileInfoId);
        }

        LineProcessingContext context = LineProcessingContext.builder()
                .cdrLine(cdrLine)
                .commLocation(commLocation)
                .cdrProcessor(processor)
                .extensionRanges(extensionRanges)
                .extensionLimits(extensionLimits)
                .fileInfo(fileInfo)
                .headerPositions(headerPositions) // Add retrieved map
                .build();

        return Optional.of(context);
    }

    private CdrData executeReprocessingLogic(LineProcessingContext context) {
        // Pass the map from context
        CdrData cdrData = context.getCdrProcessor().evaluateFormat(
                context.getCdrLine(), 
                context.getCommLocation(), 
                context.getCommLocationExtensionLimits(), 
                context.getHeaderPositions()
        );

        if (cdrData == null) {
            log.debug("Processor returned null for line during reprocessing, skipping: {}", context.getCdrLine());
            cdrData = new CdrData();
            cdrData.setRawCdrLine(context.getCdrLine());
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Parser returned null during reprocessing.");
            cdrData.setQuarantineStep(QuarantineErrorType.PARSER_ERROR.name());
        }
        cdrData.setFileInfo(context.getFileInfo());
        cdrData.setCommLocationId(context.getCommLocationId());

        boolean isValid = cdrValidationService.validateInitialCdrData(cdrData);
        if (!isValid || cdrData.isMarkedForQuarantine()) {
            return cdrData;
        }

        return cdrEnrichmentService.enrichCdr(cdrData, context);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reprocessCallRecord(Long callRecordId) {
        CallRecord callRecord = entityManager.find(CallRecord.class, callRecordId);
        if (callRecord == null) {
            log.info("Reprocessing request for CallRecord ID {} failed: Record not found.", callRecordId);
            return false;
        }

        String cdrString;
        try {
            cdrString = Compression7zUtil.decompressToString(callRecord.getCdrString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress CDR string for CallRecord ID " + callRecordId, e);
        }

        log.info("Reprocessing CallRecord ID: {}, CDR Line: {}", callRecordId, cdrString);

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                cdrString,
                callRecord.getCommLocationId(),
                callRecord.getFileInfoId()
        );

        if (contextOpt.isEmpty()) {
            log.info("Outcome for CallRecord ID {}: REPROCESS_FAILED. Could not build context (e.g., CommLocation inactive).", callRecordId);
            return false;
        }

        CdrData processedCdrData = executeReprocessingLogic(contextOpt.get());

        if (processedCdrData.isMarkedForQuarantine()) {
            QuarantineErrorType errorType = QuarantineErrorType.ENRICHMENT_ERROR;
            try {
                if (processedCdrData.getQuarantineStep() != null && !processedCdrData.getQuarantineStep().isEmpty()) {
                    errorType = QuarantineErrorType.valueOf(processedCdrData.getQuarantineStep());
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid quarantine step string '{}'. Defaulting to ENRICHMENT_ERROR.", processedCdrData.getQuarantineStep());
            }
            failedCallRecordPersistenceService.quarantineRecord(
                    processedCdrData,
                    errorType,
                    processedCdrData.getQuarantineReason(),
                    "reprocessCallRecord_" + processedCdrData.getQuarantineStep(),
                    callRecordId
            );
            entityManager.remove(callRecord);
            log.info("Outcome for CallRecord ID {}: REPROCESSED_TO_QUARANTINE. Reason: {}", callRecordId, processedCdrData.getQuarantineReason());
            return false;
        } else {
            callRecordPersistenceService.mapCdrDataToCallRecord(processedCdrData, callRecord, contextOpt.get().getCommLocation());
            entityManager.merge(callRecord);
            log.info("Outcome for CallRecord ID {}: REPROCESSED_SUCCESS. Record updated.", callRecordId);
            return true;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reprocessFailedCallRecord(Long failedCallRecordId) {
        FailedCallRecord failedCallRecord = entityManager.find(FailedCallRecord.class, failedCallRecordId);
        if (failedCallRecord == null) {
            log.info("Reprocessing request for FailedCallRecord ID {} failed: Record not found.", failedCallRecordId);
            return false;
        }

        String cdrString;
        try {
            cdrString = Compression7zUtil.decompressToString(failedCallRecord.getCdrString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress CDR string for FailedCallRecord ID " + failedCallRecordId, e);
        }

        log.info("Reprocessing FailedCallRecord ID: {}, CDR Line: {}", failedCallRecordId, cdrString);

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                cdrString,
                failedCallRecord.getCommLocationId(),
                failedCallRecord.getFileInfoId()
        );

        if (contextOpt.isEmpty()) {
            CdrData tempData = new CdrData();
            tempData.setRawCdrLine(cdrString);
            tempData.setCommLocationId(failedCallRecord.getCommLocationId());
            failedCallRecordPersistenceService.quarantineRecord(
                tempData,
                QuarantineErrorType.UNHANDLED_EXCEPTION,
                "Failed to build reprocessing context. CommLocation might be missing or inactive.",
                "reprocessFailedCallRecord_Context",
                failedCallRecord.getOriginalCallRecordId()
            );
            log.info("Outcome for FailedCallRecord ID {}: REPROCESS_FAILED. Could not build context (e.g., CommLocation inactive).", failedCallRecordId);
            return false;
        }

        CdrData processedCdrData = executeReprocessingLogic(contextOpt.get());

        if (processedCdrData.isMarkedForQuarantine()) {
            QuarantineErrorType errorType = QuarantineErrorType.ENRICHMENT_ERROR;
            try {
                if (processedCdrData.getQuarantineStep() != null && !processedCdrData.getQuarantineStep().isEmpty()) {
                    errorType = QuarantineErrorType.valueOf(processedCdrData.getQuarantineStep());
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid quarantine step string '{}'. Defaulting to ENRICHMENT_ERROR.", processedCdrData.getQuarantineStep());
            }
            failedCallRecordPersistenceService.quarantineRecord(
                    processedCdrData,
                    errorType,
                    processedCdrData.getQuarantineReason(),
                    "reprocessFailedCallRecord_" + processedCdrData.getQuarantineStep(),
                    failedCallRecord.getOriginalCallRecordId()
            );
            log.info("Outcome for FailedCallRecord ID {}: REPROCESS_FAILED_AGAIN. Reason: {}", failedCallRecordId, processedCdrData.getQuarantineReason());
            return false;
        } else {
            CallRecord newCallRecord = callRecordPersistenceService.saveOrUpdateCallRecord(processedCdrData, contextOpt.get().getCommLocation());
            if (newCallRecord != null) {
                entityManager.remove(failedCallRecord);
                log.info("Outcome for FailedCallRecord ID {}: REPROCESSED_SUCCESS. New CallRecord ID: {}.", failedCallRecordId, newCallRecord.getId());
                return true;
            } else {
                log.info("Outcome for FailedCallRecord ID {}: REPROCESS_FAILED_PERSISTENCE. Could not save new CallRecord.", failedCallRecordId);
                return false;
            }
        }
    }
}