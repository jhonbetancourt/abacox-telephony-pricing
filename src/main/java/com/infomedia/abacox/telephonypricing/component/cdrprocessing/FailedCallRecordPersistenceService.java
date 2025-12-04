package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor
public class FailedCallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Batch lookup for existing failed records.
     * Used by BatchPersistenceWorker to determine Insert vs Update.
     */
    @Transactional(readOnly = true)
    public List<FailedCallRecord> findExistingRecordsByHashes(List<Long> hashes) {
        if (hashes == null || hashes.isEmpty()) return Collections.emptyList();
        
        return entityManager.createQuery(
                "SELECT fr FROM FailedCallRecord fr WHERE fr.ctlHash IN :hashes", FailedCallRecord.class)
                .setParameter("hashes", hashes)
                .getResultList();
    }

    /**
     * Creates a new FailedCallRecord entity in memory from the processing result.
     * Does NOT persist.
     */
    public FailedCallRecord createEntityFromDto(ProcessedCdrResult result) {
        CdrData cdrData = result.getCdrData();
        FailedCallRecord record = new FailedCallRecord();
        
        record.setCtlHash(cdrData.getCtlHash());
        
        // Binary Data Handling (Prioritize pre-compressed)
        if (cdrData.getPreCompressedData() != null) {
            record.setCdrString(cdrData.getPreCompressedData());
        } else {
            try {
                record.setCdrString(CompressionZipUtil.compressString(cdrData.getRawCdrLine()));
            } catch (Exception e) {
                log.warn("Compression failed for new failed record", e);
            }
        }

        record.setCommLocationId(cdrData.getCommLocationId());
        record.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
        
        record.setOriginalCallRecordId(result.getOriginalCallRecordId());
        if (cdrData.getFileInfo() != null) {
            record.setFileInfoId(cdrData.getFileInfo().getId().longValue());
        }
        
        record.setErrorType(result.getErrorType() != null ? result.getErrorType().name() : "UNKNOWN");
        record.setErrorMessage(result.getErrorMessage());
        record.setProcessingStep(truncate(result.getErrorStep(), 100));
        
        return record;
    }

    /**
     * Updates an existing entity instance with new error details from the processing result.
     * Does NOT merge/persist.
     */
    public void updateEntityFromDto(FailedCallRecord record, ProcessedCdrResult result) {
        record.setErrorType(result.getErrorType() != null ? result.getErrorType().name() : "UNKNOWN");
        record.setErrorMessage(result.getErrorMessage());
        record.setProcessingStep(truncate(result.getErrorStep(), 100));
        
        CdrData cdrData = result.getCdrData();
        record.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
        
        // If the batch worker managed to compress (or re-compress) the data, update it.
        if (result.getCdrData().getPreCompressedData() != null) {
             record.setCdrString(result.getCdrData().getPreCompressedData());
        }
        
        if (result.getOriginalCallRecordId() != null) {
            record.setOriginalCallRecordId(result.getOriginalCallRecordId());
        }
    }

    /**
     * Synchronous quarantine method.
     * Uses REQUIRES_NEW to isolate the insert attempt from the main processing transaction.
     * This prevents the main transaction from being marked as RollbackOnly if a duplicate key occurs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailedCallRecord quarantineRecord(CdrData cdrData,
                                             QuarantineErrorType errorType, String errorMessage, String processingStep,
                                             Long originalCallRecordId) {
        if (cdrData == null) {
            return null;
        }

        Long ctlHash = cdrData.getCtlHash();
        
        // 1. Try to find existing record
        FailedCallRecord recordToSave = findByCtlHash(ctlHash);

        if (recordToSave != null) {
            // Update Existing
            updateQuarantineDetails(recordToSave, cdrData, errorType, errorMessage, processingStep, originalCallRecordId);
        } else {
            // 2. Prepare New Record
            recordToSave = new FailedCallRecord();
            recordToSave.setCtlHash(ctlHash);
            
            try {
                byte[] compressed = CompressionZipUtil.compressString(cdrData.getRawCdrLine());
                recordToSave.setCdrString(compressed);
            } catch (IOException e) {
                log.warn("Failed to compress quarantined CDR for hash {}", ctlHash);
            }

            recordToSave.setCommLocationId(cdrData.getCommLocationId());
            updateQuarantineDetails(recordToSave, cdrData, errorType, errorMessage, processingStep, originalCallRecordId);

            // 3. Attempt Insert with Flush
            try {
                entityManager.persist(recordToSave);
                entityManager.flush(); // Force execution to catch ConstraintViolation immediately
            } catch (PersistenceException | DataIntegrityViolationException e) {
                // Check for Duplicate Key violation
                if (isDuplicateKeyException(e)) {
                    log.debug("Race condition detected for hash {}. Switching to Update.", ctlHash);
                    // Clear the persistence context to detach the failed entity
                    entityManager.clear();
                    
                    // Fetch the winner of the race
                    recordToSave = findByCtlHash(ctlHash);
                    if (recordToSave != null) {
                        updateQuarantineDetails(recordToSave, cdrData, errorType, errorMessage, processingStep, originalCallRecordId);
                        entityManager.merge(recordToSave);
                    }
                } else {
                    log.error("Failed to save quarantine record. Hash: {}", ctlHash, e);
                    throw e; // Rethrow if it's not a duplicate key issue
                }
            }
        }

        return recordToSave;
    }

    private void updateQuarantineDetails(FailedCallRecord record, CdrData cdrData,
                                         QuarantineErrorType errorType, String errorMessage, String processingStep,
                                         Long originalCallRecordId) {
        record.setErrorType(errorType.name());
        record.setErrorMessage(errorMessage);
        record.setProcessingStep(truncate(processingStep, 100));
        record.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
        
        if (originalCallRecordId != null) {
            record.setOriginalCallRecordId(originalCallRecordId);
        }
        if (cdrData.getFileInfo() != null && record.getFileInfoId() == null) {
            record.setFileInfoId(cdrData.getFileInfo().getId().longValue());
        }
    }

    private boolean isDuplicateKeyException(Exception e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException || 
                (cause.getMessage() != null && cause.getMessage().contains("duplicate key"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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

    private String truncate(String input, int limit) {
        if (input == null) return null;
        return input.length() > limit ? input.substring(0, limit) : input;
    }
}