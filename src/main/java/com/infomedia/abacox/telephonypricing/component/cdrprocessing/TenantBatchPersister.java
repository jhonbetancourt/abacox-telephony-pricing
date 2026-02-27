package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class TenantBatchPersister {

    private final CallRecordPersistenceService callRecordService;
    private final FailedCallRecordPersistenceService failedRecordService;
    private final FileProcessingTrackerService trackerService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRED)
    public void persistTenantBatch(List<ProcessedCdrResult> tenantBatch) {
        long batchStartTime = System.currentTimeMillis();
        String tenantId = TenantContext.getTenant(); // For logging

        // 1. Separate Success vs Failed
        List<ProcessedCdrResult> successResults = tenantBatch.stream()
                .filter(r -> r.getOutcome() == ProcessingOutcome.SUCCESS)
                .toList();
        List<ProcessedCdrResult> failedResults = tenantBatch.stream()
                .filter(r -> r.getOutcome() != ProcessingOutcome.SUCCESS)
                .toList();

        if (!successResults.isEmpty()) {
            processSuccessfulBatch(successResults);
        }
        if (!failedResults.isEmpty()) {
            processFailedBatch(failedResults);
        }

        // 2. Measure Database Flush
        long flushStart = System.currentTimeMillis();
        entityManager.flush();
        entityManager.clear();
        long flushTime = System.currentTimeMillis() - flushStart;

        long totalTime = System.currentTimeMillis() - batchStartTime;
        log.info("Persisted Batch of {} records for tenant [{}] in {} ms. (DB Flush took {} ms)",
                tenantBatch.size(), tenantId, totalTime, flushTime);

        // 3. Update Tracker
        updateFileTrackers(tenantBatch);
    }

    private void processSuccessfulBatch(List<ProcessedCdrResult> results) {
        Map<UUID, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        List<ProcessedCdrResult> inBatchDuplicates = new ArrayList<>();

        // 1. Check for duplicates WITHIN the exact same batch
        for (ProcessedCdrResult res : results) {
            UUID hash = res.getCdrData().getCtlHash();
            if (uniqueBatch.containsKey(hash)) {
                // It's a duplicate of another line in this exact same batch
                res.setOutcome(ProcessingOutcome.QUARANTINED);
                res.setErrorType(QuarantineErrorType.DUPLICATE_RECORD);
                res.setErrorMessage("Duplicate CDR detected within the same processing batch.");
                res.setErrorStep("BATCH_DEDUPLICATION");
                res.getCdrData().setMarkedForQuarantine(true);
                inBatchDuplicates.add(res);
            } else {
                uniqueBatch.put(hash, res);
            }
        }

        // Process the in-batch duplicates immediately
        if (!inBatchDuplicates.isEmpty()) {
            processFailedBatch(inBatchDuplicates);
        }

        if (uniqueBatch.isEmpty()) return;

        // 2. Check for duplicates AGAINST the database
        List<UUID> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        Set<UUID> existingInDb = callRecordService.findExistingHashes(hashesToCheck);

        if (!existingInDb.isEmpty()) {
            List<ProcessedCdrResult> dbDuplicates = new ArrayList<>();
            
            for (UUID duplicateHash : existingInDb) {
                // Remove the duplicate from the success map
                ProcessedCdrResult duplicateResult = uniqueBatch.remove(duplicateHash);
                
                if (duplicateResult != null) {
                    // Alter its state to failed/quarantined
                    duplicateResult.setOutcome(ProcessingOutcome.QUARANTINED);
                    duplicateResult.setErrorType(QuarantineErrorType.DUPLICATE_RECORD);
                    duplicateResult.setErrorMessage("Duplicate CDR detected. Hash already exists in database.");
                    duplicateResult.setErrorStep("DB_INSERTION");
                    duplicateResult.getCdrData().setMarkedForQuarantine(true);
                    
                    dbDuplicates.add(duplicateResult);
                }
            }
            
            // Route these DB duplicates to the failed batch processor
            processFailedBatch(dbDuplicates);
        }

        // 3. Persist the remaining genuinely new records
        if (uniqueBatch.isEmpty()) return;

        for (ProcessedCdrResult res : uniqueBatch.values()) {
            CallRecord entity = callRecordService.createEntityFromDto(res.getCdrData(), res.getCommLocation());
            if (entity != null) {
                entityManager.persist(entity);
            }
        }
    }

    private void processFailedBatch(List<ProcessedCdrResult> results) {
        // Changed Map Key to UUID
        Map<UUID, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        for (ProcessedCdrResult res : results) {
            uniqueBatch.put(res.getCdrData().getCtlHash(), res);
        }

        // Changed List<Long> to List<UUID>
        List<UUID> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        List<FailedCallRecord> existingRecords = failedRecordService.findExistingRecordsByHashes(hashesToCheck);

        // Changed Map Key to UUID
        Map<UUID, FailedCallRecord> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(FailedCallRecord::getCtlHash, r -> r));

        for (ProcessedCdrResult res : uniqueBatch.values()) {
            UUID hash = res.getCdrData().getCtlHash(); // Changed Long to UUID
            FailedCallRecord existing = existingMap.get(hash);

            if (existing != null) {
                failedRecordService.updateEntityFromDto(existing, res);
                entityManager.merge(existing);
            } else {
                FailedCallRecord newRecord = failedRecordService.createEntityFromDto(res);
                entityManager.persist(newRecord);
            }
        }
    }

    private void updateFileTrackers(List<ProcessedCdrResult> batch) {
        Map<Long, Integer> processedCounts = new HashMap<>();
        for (ProcessedCdrResult res : batch) {
            if (res.getCdrData() != null && res.getCdrData().getFileInfo() != null) {
                Long fileId = res.getCdrData().getFileInfo().getId();
                processedCounts.merge(fileId, 1, Integer::sum);
            }
        }
        processedCounts.forEach(trackerService::decrementPendingCount);
    }
}