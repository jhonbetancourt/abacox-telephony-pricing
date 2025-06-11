// File: com/infomedia/abacox/telephonypricing/cdr/FailedCallRecordPersistenceService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Log4j2
public class FailedCallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * PHP equivalent: CDRInvalido
     */
    @Transactional
    public FailedCallRecord quarantineRecord(CdrData cdrData,
                                             QuarantineErrorType errorType, String errorMessage, String processingStep,
                                             Long originalCallRecordId) {
        if (cdrData == null) {
            log.error("Cannot quarantine null CdrData.");
            return null;
        }
        log.warn("Quarantining CDR. Type: {}, Step: {}, Message: {}, Hash: {}",
                errorType, processingStep, errorMessage, cdrData.getCtlHash());

        String cdrStringToStore = cdrData.getRawCdrLine();
        Long commLocationIdToStore = cdrData.getCommLocationId();
        String extensionToStore = cdrData.getCallingPartyNumber();
        String ctlHash = CdrUtil.generateCtlHash(cdrStringToStore, commLocationIdToStore);

        FailedCallRecord existingRecord = findByCtlHash(ctlHash);
        FailedCallRecord recordToSave;

        if (existingRecord != null) {
            log.info("Updating existing quarantine record ID: {} for hash: {}", existingRecord.getId(), ctlHash);
            recordToSave = existingRecord;
            // Update fields that might change on a re-quarantine
            recordToSave.setErrorType(errorType.name());
            recordToSave.setErrorMessage(errorMessage);
            recordToSave.setProcessingStep(processingStep != null && processingStep.length() > 100 ? processingStep.substring(0, 100) : processingStep);
            recordToSave.setEmployeeExtension(extensionToStore != null && extensionToStore.length() > 50 ? extensionToStore.substring(0, 50) : extensionToStore);
            if (originalCallRecordId != null) { // If this re-quarantine is for a specific acumtotal record
                recordToSave.setOriginalCallRecordId(originalCallRecordId);
            }
            // fileInfoId and commLocationId typically don't change for the same raw CDR
        } else {
            log.info("Creating new quarantine record for hash: {}", ctlHash);
            recordToSave = new FailedCallRecord();
            recordToSave.setCtlHash(ctlHash);
            recordToSave.setCdrString(cdrStringToStore); // Store raw CDR
            recordToSave.setCommLocationId(commLocationIdToStore);
            recordToSave.setEmployeeExtension(extensionToStore != null && extensionToStore.length() > 50 ? extensionToStore.substring(0, 50) : extensionToStore);
            recordToSave.setOriginalCallRecordId(originalCallRecordId);
            if (cdrData.getFileInfo() != null) {
                recordToSave.setFileInfoId(cdrData.getFileInfo().getId().longValue());
            }
            recordToSave.setErrorType(errorType.name());
            recordToSave.setErrorMessage(errorMessage);
            recordToSave.setProcessingStep(processingStep != null && processingStep.length() > 100 ? processingStep.substring(0, 100) : processingStep);
        }
        // Audited fields (createdBy, createdDate, lastModifiedBy, lastModifiedDate) are handled by Spring Data JPA Auditing

        try {
            entityManager.persist(recordToSave); // Persist will also handle update if entity is managed
            log.info("Successfully {} quarantine record ID: {}", (existingRecord != null ? "updated" : "saved"), recordToSave.getId());
            return recordToSave;
        } catch (Exception e) {
            log.error("CRITICAL: Could not save/update quarantine record. Hash: {}, Error: {}", ctlHash, e.getMessage(), e);
            return null; // Or throw a runtime exception to rollback transaction if this is critical
        }
    }

    @Transactional(readOnly = true)
    public FailedCallRecord findByCtlHash(String ctlHash) {
        try {
            return entityManager.createQuery("SELECT fr FROM FailedCallRecord fr WHERE fr.ctlHash = :hash", FailedCallRecord.class)
                    .setParameter("hash", ctlHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    // Overloaded method from previous implementation, adapted to use CdrData
    public void saveFailedRecord(String cdrLine, FileInfo fileInfo, Long commLocationId,
                                 String errorTypeString, String errorMessage, String processingStep,
                                 String employeeExtension, Long originalCallRecordId) {
        CdrData tempData = new CdrData();
        tempData.setRawCdrLine(cdrLine);
        tempData.setFileInfo(fileInfo);
        tempData.setCommLocationId(commLocationId);
        tempData.setCallingPartyNumber(employeeExtension); // Best guess for extension

        QuarantineErrorType qet;
        try {
            qet = QuarantineErrorType.valueOf(errorTypeString);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown errorType string '{}' received for quarantining. Mapping to UNHANDLED_EXCEPTION.", errorTypeString);
            qet = QuarantineErrorType.UNHANDLED_EXCEPTION;
            errorMessage = "Original Error Type: " + errorTypeString + "; Original Message: " + errorMessage;
        }
        quarantineRecord(tempData, qet, errorMessage, processingStep, originalCallRecordId);
    }
}