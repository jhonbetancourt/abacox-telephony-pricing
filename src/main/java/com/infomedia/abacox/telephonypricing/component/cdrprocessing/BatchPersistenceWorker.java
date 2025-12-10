package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Log4j2
@RequiredArgsConstructor
public class BatchPersistenceWorker {

    private final PersistenceQueueService queueService;
    private final CallRecordPersistenceService callRecordService;
    private final FailedCallRecordPersistenceService failedRecordService;
    private final FileProcessingTrackerService trackerService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final int BATCH_SIZE = 1000;

    @Scheduled(fixedDelay = 200)
    @Transactional(propagation = Propagation.REQUIRED)
    public void processPersistenceQueue() {
        List<ProcessedCdrResult> batch = new ArrayList<>(BATCH_SIZE);
        int count = queueService.drainTo(batch, BATCH_SIZE);

        if (count == 0) return;

        long batchStartTime = System.currentTimeMillis();

        // 1. Separate Success vs Failed
        List<ProcessedCdrResult> successResults = new ArrayList<>();
        List<ProcessedCdrResult> failedResults = new ArrayList<>();

        for (ProcessedCdrResult result : batch) {
            if (result.getOutcome() == ProcessingOutcome.SUCCESS) {
                successResults.add(result);
            } else {
                failedResults.add(result);
            }
        }

        if (!successResults.isEmpty()) {
            processSuccessfulBatch(successResults);
        }

        if (!failedResults.isEmpty()) {
            processFailedBatch(failedResults);
        }

        // 2. Measure Database Flush
        // Since we deduplicated internally, this flush should be safe from Unique Constraint Violations
        long flushStart = System.currentTimeMillis();
        entityManager.flush();
        entityManager.clear();
        long flushTime = System.currentTimeMillis() - flushStart;

        long totalTime = System.currentTimeMillis() - batchStartTime;
        log.info("Persisted Batch of {} records in {} ms. (DB Write/Flush took {} ms)",
                count, totalTime, flushTime);

        // 3. Update Tracker (Decrement counts)
        updateFileTrackers(batch);
    }

    private void processSuccessfulBatch(List<ProcessedCdrResult> results) {
        // Step 1: In-Batch Deduplication
        // We use a Map to ensure that if the batch contains the same hash multiple times,
        // we only attempt to persist one of them.
        Map<Long, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        for (ProcessedCdrResult res : results) {
            uniqueBatch.put(res.getCdrData().getCtlHash(), res);
        }

        // Step 2: Check DB for existing hashes based on our unique keys
        List<Long> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        Set<Long> existingInDb = callRecordService.findExistingHashes(hashesToCheck);

        // Step 3: Remove those that are already in the DB
        existingInDb.forEach(uniqueBatch::remove);

        if (uniqueBatch.isEmpty()) {
            return;
        }

        // Step 4: Compress remaining items (Parallel for CPU efficiency)
        uniqueBatch.values().parallelStream().forEach(this::compressResultData);

        // Step 5: Persist
        for (ProcessedCdrResult res : uniqueBatch.values()) {
            CallRecord entity = callRecordService.createEntityFromDto(res.getCdrData(), res.getCommLocation());
            if (entity != null) {
                entityManager.persist(entity);
            }
        }
    }

    private void processFailedBatch(List<ProcessedCdrResult> results) {
        // Step 1: In-Batch Deduplication
        // Collapses multiple failures for the same hash into a single entry (the last one processed)
        Map<Long, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        for (ProcessedCdrResult res : results) {
            uniqueBatch.put(res.getCdrData().getCtlHash(), res);
        }

        // Step 2: Check DB for existing records
        List<Long> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        List<FailedCallRecord> existingRecords = failedRecordService.findExistingRecordsByHashes(hashesToCheck);
        
        Map<Long, FailedCallRecord> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(FailedCallRecord::getCtlHash, r -> r));

        // Step 3: Compress (Parallel)
        uniqueBatch.values().parallelStream().forEach(this::compressResultData);

        // Step 4: Merge or Persist
        for (ProcessedCdrResult res : uniqueBatch.values()) {
            Long hash = res.getCdrData().getCtlHash();
            FailedCallRecord existing = existingMap.get(hash);

            if (existing != null) {
                // Update existing record
                failedRecordService.updateEntityFromDto(existing, res);
                entityManager.merge(existing);
            } else {
                // Insert new record
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

    private void compressResultData(ProcessedCdrResult result) {
        CdrData cdrData = result.getCdrData();
        if (cdrData.getPreCompressedData() == null && cdrData.getRawCdrLine() != null) {
            try {
                byte[] compressed = CompressionZipUtil.compressString(cdrData.getRawCdrLine());
                cdrData.setPreCompressedData(compressed);
            } catch (IOException e) {
                log.error("Failed to compress CDR data for hash {}", cdrData.getCtlHash(), e);
            }
        }
    }
}