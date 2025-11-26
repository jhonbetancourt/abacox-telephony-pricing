package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
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
     * Used ONLY for Admin Reprocessing or specific synchronous flows where BatchWorker is bypassed.
     */
    @Transactional
    public FailedCallRecord quarantineRecord(CdrData cdrData,
                                             QuarantineErrorType errorType, String errorMessage, String processingStep,
                                             Long originalCallRecordId) {
        if (cdrData == null) {
            return null;
        }

        Long ctlHash = cdrData.getCtlHash();
        FailedCallRecord existingRecord = findByCtlHash(ctlHash);
        FailedCallRecord recordToSave;

        if (existingRecord != null) {
            recordToSave = existingRecord;
            recordToSave.setErrorType(errorType.name());
            recordToSave.setErrorMessage(errorMessage);
            recordToSave.setProcessingStep(truncate(processingStep, 100));
            recordToSave.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
            if (originalCallRecordId != null) {
                recordToSave.setOriginalCallRecordId(originalCallRecordId);
            }
        } else {
            recordToSave = new FailedCallRecord();
            recordToSave.setCtlHash(ctlHash);
            
            // Inline compression for sync flow
            try {
                byte[] compressed = CompressionZipUtil.compressString(cdrData.getRawCdrLine());
                recordToSave.setCdrString(compressed);
            } catch (IOException e) {
                log.warn("Failed to compress quarantined CDR for hash {}", ctlHash);
            }

            recordToSave.setCommLocationId(cdrData.getCommLocationId());
            recordToSave.setEmployeeExtension(truncate(cdrData.getCallingPartyNumber(), 50));
            recordToSave.setOriginalCallRecordId(originalCallRecordId);
            if (cdrData.getFileInfo() != null) {
                recordToSave.setFileInfoId(cdrData.getFileInfo().getId().longValue());
            }
            recordToSave.setErrorType(errorType.name());
            recordToSave.setErrorMessage(errorMessage);
            recordToSave.setProcessingStep(truncate(processingStep, 100));
        }

        try {
            entityManager.persist(recordToSave);
            return recordToSave;
        } catch (Exception e) {
            log.error("CRITICAL: Could not save/update quarantine record. Hash: {}, Error: {}", ctlHash, e.getMessage());
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

    private String truncate(String input, int limit) {
        if (input == null) return null;
        return input.length() > limit ? input.substring(0, limit) : input;
    }
}