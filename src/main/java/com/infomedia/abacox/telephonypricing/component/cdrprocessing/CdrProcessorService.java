package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Processes a batch of CDR lines within a single, new transaction for ASYNC operations.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCdrBatch(List<LineProcessingContext> batch) {
        log.debug("Starting transactional processing for a batch of {} records.", batch.size());
        for (LineProcessingContext context : batch) {
            // This method now calls the refactored private helper
            processSingleCdrLineInternal(context);
        }
        log.debug("Flushing and clearing EntityManager after processing batch.");
        entityManager.flush();
        entityManager.clear();
        log.debug("Completed transactional processing for batch of {} records.", batch.size());
    }

    /**
     * Processes a single CDR line within the CALLER'S transaction for SYNC operations.
     * It returns the outcome for result aggregation.
     *
     * @param lineProcessingContext The context for the line to be processed.
     * @return The outcome of the processing (SUCCESS, QUARANTINED, SKIPPED).
     */
    public ProcessingOutcome processSingleCdrLineSync(LineProcessingContext lineProcessingContext) {
        // This method is not transactional itself; it joins the caller's transaction.
        return processSingleCdrLineInternal(lineProcessingContext);
    }

    /**
     * Private helper containing the core logic to process one line.
     * It is called by both the async batch processor and the sync single-line processor.
     */
    private ProcessingOutcome processSingleCdrLineInternal(LineProcessingContext lineProcessingContext) {
        String cdrLine = lineProcessingContext.getCdrLine();
        CommunicationLocation targetCommLocation = lineProcessingContext.getCommLocation();

        log.info("Processing CDR line for CommLocation {}: {}", targetCommLocation.getId(), cdrLine);

        CdrData cdrData = null;

        try {
            // FileInfo is already managed by the calling service, so we just use it.
            FileInfo currentFileInfo = lineProcessingContext.getFileInfo();

            CdrProcessor processor = lineProcessingContext.getCdrProcessor();
            cdrData = processor.evaluateFormat(cdrLine, targetCommLocation, lineProcessingContext.getCommLocationExtensionLimits());

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
                log.info("Outcome for CDR line -> Status: QUARANTINED, Details: Validation failed. Reason: {}", cdrData.getQuarantineReason());
                return ProcessingOutcome.QUARANTINED;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, lineProcessingContext);

            if (cdrData.isMarkedForQuarantine()) {
                QuarantineErrorType errorType = cdrData.getQuarantineStep() != null && !cdrData.getQuarantineStep().isEmpty() ?
                        QuarantineErrorType.valueOf(cdrData.getQuarantineStep()) :
                        QuarantineErrorType.ENRICHMENT_ERROR;
                failedCallRecordPersistenceService.quarantineRecord(cdrData, errorType,
                        cdrData.getQuarantineReason(), cdrData.getQuarantineStep(), null);
                log.info("Outcome for CDR line -> Status: QUARANTINED, Details: Enrichment failed. Reason: {}", cdrData.getQuarantineReason());
                return ProcessingOutcome.QUARANTINED;
            } else {
                CallRecord savedRecord = callRecordPersistenceService.saveOrUpdateCallRecord(cdrData, targetCommLocation);
                if (savedRecord != null) {
                    log.info("Outcome for CDR line -> Status: SUCCESS, Details: CallRecord ID: {}", savedRecord.getId());
                    return ProcessingOutcome.SUCCESS;
                } else {
                    log.info("Outcome for CDR line -> Status: QUARANTINED, Details: Persistence failed. Reason: {}", cdrData.getQuarantineReason());
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
            log.info("Outcome for CDR line -> Status: QUARANTINED, Details: Unhandled Exception: {}", e.getMessage());
            return ProcessingOutcome.QUARANTINED;
        }
    }

    private CdrProcessor getProcessorForPlantType(Long plantTypeId) {
        return cdrProcessors.stream()
                .filter(p -> p.getPlantTypeIdentifier().equals(plantTypeId))
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

        LineProcessingContext context = LineProcessingContext.builder()
                .cdrLine(cdrLine)
                .commLocation(commLocation)
                .cdrProcessor(processor)
                .extensionRanges(extensionRanges)
                .extensionLimits(extensionLimits)
                .fileInfo(fileInfo)
                .build();

        return Optional.of(context);
    }

    private CdrData executeReprocessingLogic(LineProcessingContext context) {
        CdrData cdrData = context.getCdrProcessor().evaluateFormat(context.getCdrLine(), context.getCommLocation(), context.getCommLocationExtensionLimits());
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

        log.info("Reprocessing CallRecord ID: {}, CDR Line: {}", callRecordId, callRecord.getCdrString());

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                callRecord.getCdrString(),
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
            String newHash = CdrUtil.generateCtlHash(processedCdrData.getRawCdrLine(), processedCdrData.getCommLocationId());
            callRecordPersistenceService.mapCdrDataToCallRecord(processedCdrData, callRecord, contextOpt.get().getCommLocation(), newHash);
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

        log.info("Reprocessing FailedCallRecord ID: {}, CDR Line: {}", failedCallRecordId, failedCallRecord.getCdrString());

        Optional<LineProcessingContext> contextOpt = buildReprocessingContext(
                failedCallRecord.getCdrString(),
                failedCallRecord.getCommLocationId(),
                failedCallRecord.getFileInfoId()
        );

        if (contextOpt.isEmpty()) {
            CdrData tempData = new CdrData();
            tempData.setRawCdrLine(failedCallRecord.getCdrString());
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