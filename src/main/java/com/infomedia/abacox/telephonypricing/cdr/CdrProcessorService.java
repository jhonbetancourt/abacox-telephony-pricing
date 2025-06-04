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


import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrProcessorService {

    private final CdrEnrichmentService cdrEnrichmentService;
    private final CallRecordPersistenceService callRecordPersistenceService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;
    private final CdrValidationService cdrValidationService;
    private final EntityManager entityManager;

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
}