package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Log4j2
public class FailedCallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void saveFailedRecord(String cdrLine, FileInfo fileInfo, Long commLocationId,
                                 String errorType, String errorMessage, String processingStep,
                                 String employeeExtension, Long originalCallRecordId) {
        try {
            FailedCallRecord failedRecord = new FailedCallRecord();
            failedRecord.setCdrString(cdrLine);
            if (fileInfo != null) {
                failedRecord.setFileInfoId(fileInfo.getId());
            }
            failedRecord.setCommLocationId(commLocationId);
            failedRecord.setErrorType(errorType.length() > 50 ? errorType.substring(0, 50) : errorType);
            failedRecord.setErrorMessage(errorMessage);
            failedRecord.setProcessingStep(processingStep.length() > 100 ? processingStep.substring(0, 100) : processingStep);
            failedRecord.setEmployeeExtension(employeeExtension != null && employeeExtension.length() > 50 ? employeeExtension.substring(0, 50) : employeeExtension);
            failedRecord.setOriginalCallRecordId(originalCallRecordId);
            // Audited fields set by Spring

            entityManager.persist(failedRecord);
            log.info("Saved failed CDR to quarantine. Type: {}, Step: {}", errorType, processingStep);
        } catch (Exception e) {
            log.error("CRITICAL: Could not save failed CDR to quarantine. CDR: {}, Error: {}", cdrLine, e.getMessage(), e);
        }
    }
}
