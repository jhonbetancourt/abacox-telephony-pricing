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
        long flushStart = System.currentTimeMillis();
        entityManager.flush();
        entityManager.clear();
        long flushTime = System.currentTimeMillis() - flushStart;

        long totalTime = System.currentTimeMillis() - batchStartTime;
        log.info("Persisted Batch of {} records in {} ms. (DB Write/Flush took {} ms)",
                count, totalTime, flushTime);
        // 3. Update Tracker (Decrement counts)
        // Must be done AFTER flush ensures data is committed (or prepared to commit)
        updateFileTrackers(batch);
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

    private void processSuccessfulBatch(List<ProcessedCdrResult> results) {
        long start = System.currentTimeMillis();

        List<Long> hashes = results.stream()
                .map(r -> r.getCdrData().getCtlHash())
                .collect(Collectors.toList());

        long dbCheckStart = System.currentTimeMillis();
        Set<Long> existingHashes = callRecordService.findExistingHashes(hashes);
        long dbCheckTime = System.currentTimeMillis() - dbCheckStart;

        List<ProcessedCdrResult> toPersist = results.stream()
                .filter(res -> !existingHashes.contains(res.getCdrData().getCtlHash()))
                .collect(Collectors.toList());

        int skipped = results.size() - toPersist.size();

        if (toPersist.isEmpty()) {
            if (skipped > 0) log.debug("Batch: Skipped {} duplicate successful records.", skipped);
            return;
        }

        long compressStart = System.currentTimeMillis();
        toPersist.parallelStream().forEach(this::compressResultData);
        long compressTime = System.currentTimeMillis() - compressStart;

        long contextStart = System.currentTimeMillis();
        int saved = 0;
        for (ProcessedCdrResult res : toPersist) {
            CallRecord entity = callRecordService.createEntityFromDto(res.getCdrData(), res.getCommLocation());
            if (entity != null) {
                entityManager.persist(entity);
                saved++;
            }
        }
        long contextTime = System.currentTimeMillis() - contextStart;
        
        log.debug("Success Batch -> DB Check: {}ms | Compress: {}ms | Context: {}ms | Saved: {}, Skipped: {}", 
                dbCheckTime, compressTime, contextTime, saved, skipped);
    }

    private void processFailedBatch(List<ProcessedCdrResult> results) {
        long start = System.currentTimeMillis();

        List<Long> hashes = results.stream()
                .map(r -> r.getCdrData().getCtlHash())
                .collect(Collectors.toList());

        long dbLookupStart = System.currentTimeMillis();
        List<FailedCallRecord> existingRecords = failedRecordService.findExistingRecordsByHashes(hashes);
        Map<Long, FailedCallRecord> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(FailedCallRecord::getCtlHash, r -> r));
        long dbLookupTime = System.currentTimeMillis() - dbLookupStart;

        long compressStart = System.currentTimeMillis();
        results.parallelStream().forEach(this::compressResultData);
        long compressTime = System.currentTimeMillis() - compressStart;

        long contextStart = System.currentTimeMillis();
        int saved = 0;
        int updated = 0;

        for (ProcessedCdrResult res : results) {
            FailedCallRecord existing = existingMap.get(res.getCdrData().getCtlHash());
            
            if (existing != null) {
                failedRecordService.updateEntityFromDto(existing, res);
                entityManager.merge(existing);
                updated++;
            } else {
                FailedCallRecord newRecord = failedRecordService.createEntityFromDto(res);
                entityManager.persist(newRecord);
                saved++;
            }
        }
        long contextTime = System.currentTimeMillis() - contextStart;

        log.debug("Failed Batch -> DB Lookup: {}ms | Compress: {}ms | Context: {}ms | New: {}, Updated: {}", 
                dbLookupTime, compressTime, contextTime, saved, updated);
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