// File: com/infomedia/abacox/telephonypricing/cdr/FailedCallRecordPersistenceService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.Compression7zUtil;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
@Log4j2
public class FailedCallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public FailedCallRecord quarantineRecord(CdrData cdrData,
                                             QuarantineErrorType errorType, String errorMessage, String processingStep,
                                             Long originalCallRecordId) {
        if (cdrData == null) {
            log.debug("Cannot quarantine null CdrData.");
            return null;
        }
        log.debug("Quarantining CDR. Type: {}, Step: {}, Message: {}, Hash: {}",
                errorType, processingStep, errorMessage, cdrData.getCtlHash());

        byte[] cdrStringToStore = null;
        try {
            cdrStringToStore = Compression7zUtil.compressString(cdrData.getRawCdrLine());
            log.trace("Compressed quarantined CDR from {} to {} bytes",
                    cdrData.getRawCdrLine().length(), cdrStringToStore.length);
        } catch (IOException e) {
            log.warn("Failed to compress quarantined CDR for hash {}: {}.",
                    cdrData.getCtlHash(), e.getMessage());
        }

        Long commLocationIdToStore = cdrData.getCommLocationId();
        String extensionToStore = cdrData.getCallingPartyNumber();
        Long ctlHash = cdrData.getCtlHash();

        FailedCallRecord existingRecord = findByCtlHash(ctlHash);
        FailedCallRecord recordToSave;

        if (existingRecord != null) {
            log.debug("Updating existing quarantine record ID: {} for hash: {}", existingRecord.getId(), ctlHash);
            recordToSave = existingRecord;
            recordToSave.setErrorType(errorType.name());
            recordToSave.setErrorMessage(errorMessage);
            recordToSave.setProcessingStep(processingStep != null && processingStep.length() > 100 ? processingStep.substring(0, 100) : processingStep);
            recordToSave.setEmployeeExtension(extensionToStore != null && extensionToStore.length() > 50 ? extensionToStore.substring(0, 50) : extensionToStore);
            if (originalCallRecordId != null) {
                recordToSave.setOriginalCallRecordId(originalCallRecordId);
            }
        } else {
            log.debug("Creating new quarantine record for hash: {}", ctlHash);
            recordToSave = new FailedCallRecord();
            recordToSave.setCtlHash(ctlHash);
            recordToSave.setCdrString(cdrStringToStore); // Store compressed CDR
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

        try {
            entityManager.persist(recordToSave);
            log.debug("Successfully {} quarantine record ID: {}", (existingRecord != null ? "updated" : "saved"), recordToSave.getId());
            return recordToSave;
        } catch (Exception e) {
            log.debug("CRITICAL: Could not save/update quarantine record. Hash: {}, Error: {}", ctlHash, e.getMessage(), e);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public FailedCallRecord findByCtlHash(Long ctlHash) {
        try {
            return entityManager.createQuery("SELECT fr FROM FailedCallRecord fr WHERE fr.ctlHash = :hash", FailedCallRecord.class)
                    .setParameter("hash", ctlHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional
    public int deleteByFileInfoId(Long fileInfoId) {
        if (fileInfoId == null) return 0;
        int deletedCount = entityManager.createQuery("DELETE FROM FailedCallRecord fr WHERE fr.fileInfoId = :fileInfoId")
                .setParameter("fileInfoId", fileInfoId)
                .executeUpdate();
        log.debug("Deleted {} FailedCallRecord(s) for FileInfo ID: {}", deletedCount, fileInfoId);
        return deletedCount;
    }
}