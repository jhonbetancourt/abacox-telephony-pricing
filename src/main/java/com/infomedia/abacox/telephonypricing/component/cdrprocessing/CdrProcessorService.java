package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrProcessorService {

    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrValidationService cdrValidationService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Processes a batch of CDR lines within a single, new transaction.
     * This method is designed to be called by a non-transactional orchestrator.
     * After processing the batch, it flushes changes and clears the persistence context
     * to manage memory efficiently.
     *
     * @param batch A list of CdrLineContext objects to be processed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCdrBatch(List<LineProcessingContext> batch) {
        log.info("Starting transactional processing for a batch of {} records.", batch.size());
        for (LineProcessingContext context : batch) {
            // The original processSingleCdrLine logic is now called here for each item in the batch.
            processSingleCdrLine(context);
        }
        // After processing all items in the batch, flush and clear the persistence context.
        // This is the key to efficiency and preventing memory leaks.
        log.debug("Flushing and clearing EntityManager after processing batch.");
        entityManager.flush();
        entityManager.clear();
        log.info("Completed transactional processing for batch of {} records.", batch.size());
    }

    /**
     * This is now a private helper method containing the logic to process one line.
     * It is called by the public transactional `processCdrBatch` method.
     */
    private void processSingleCdrLine(LineProcessingContext lineProcessingContext) {
        CommunicationLocation targetCommLocation = lineProcessingContext.getCommLocation();
        String cdrLine = lineProcessingContext.getCdrLine();
        Long fileInfoId = lineProcessingContext.getFileInfoId();
        CdrData cdrData = null;
        FileInfo currentFileInfo = null;
        CdrProcessor processor = lineProcessingContext.getCdrProcessor();
        log.trace("Processing single CDR line for CommLocation {}: {}, FileInfo ID: {}", targetCommLocation.getId(), cdrLine, fileInfoId);
        try {
            if (fileInfoId != null) {
                // Find the FileInfo within the current transaction's persistence context
                currentFileInfo = entityManager.find(FileInfo.class, fileInfoId.intValue());
                if (currentFileInfo == null) {
                    log.error("FileInfo not found for ID: {} within batch transaction. This indicates a problem.", fileInfoId);
                    CdrData tempData = new CdrData();
                    tempData.setRawCdrLine(cdrLine);
                    tempData.setCommLocationId(targetCommLocation.getId());
                    failedCallRecordPersistenceService.quarantineRecord(tempData,
                            QuarantineErrorType.UNHANDLED_EXCEPTION, "Critical: FileInfo missing in transaction for ID: " + fileInfoId,
                            "ProcessSingleLine_FileInfoMissing", null);
                    return;
                }
            }

            cdrData = processor.evaluateFormat(cdrLine, targetCommLocation, lineProcessingContext.getCommLocationExtensionLimits());
            if (cdrData == null) {
                log.trace("Processor returned null for line, skipping: {}", cdrLine);
                return;
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
                return;
            }

            cdrData = cdrEnrichmentService.enrichCdr(cdrData, lineProcessingContext);

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
            log.error("Unhandled exception processing CDR line within batch: {} for CommLocation: {}", cdrLine, targetCommLocation.getId(), e);
            String step = (cdrData != null && cdrData.getQuarantineStep() != null) ? cdrData.getQuarantineStep() : "UNKNOWN_SINGLE_LINE_PROCESSING_STEP";
            if (cdrData == null) {
                cdrData = new CdrData();
                cdrData.setRawCdrLine(cdrLine);
            }
            if (cdrData.getFileInfo() == null && currentFileInfo != null) {
                cdrData.setFileInfo(currentFileInfo);
            }
            cdrData.setCommLocationId(targetCommLocation.getId());

            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.UNHANDLED_EXCEPTION, e.getMessage(), step, null);
        }
    }
}