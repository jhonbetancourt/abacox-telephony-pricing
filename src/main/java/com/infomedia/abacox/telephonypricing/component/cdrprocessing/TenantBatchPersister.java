package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                .collect(Collectors.toList());
        List<ProcessedCdrResult> failedResults = tenantBatch.stream()
                .filter(r -> r.getOutcome() != ProcessingOutcome.SUCCESS)
                .collect(Collectors.toList());

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
        Map<Long, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        for (ProcessedCdrResult res : results) {
            uniqueBatch.put(res.getCdrData().getCtlHash(), res);
        }

        List<Long> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        Set<Long> existingInDb = callRecordService.findExistingHashes(hashesToCheck);

        existingInDb.forEach(uniqueBatch::remove);
        if (uniqueBatch.isEmpty()) return;

        uniqueBatch.values().parallelStream().forEach(this::compressResultData);

        for (ProcessedCdrResult res : uniqueBatch.values()) {
            CallRecord entity = callRecordService.createEntityFromDto(res.getCdrData(), res.getCommLocation());
            if (entity != null) {
                entityManager.persist(entity);
            }
        }
    }

    private void processFailedBatch(List<ProcessedCdrResult> results) {
        Map<Long, ProcessedCdrResult> uniqueBatch = new HashMap<>();
        for (ProcessedCdrResult res : results) {
            uniqueBatch.put(res.getCdrData().getCtlHash(), res);
        }

        List<Long> hashesToCheck = new ArrayList<>(uniqueBatch.keySet());
        List<FailedCallRecord> existingRecords = failedRecordService.findExistingRecordsByHashes(hashesToCheck);
        
        Map<Long, FailedCallRecord> existingMap = existingRecords.stream()
                .collect(Collectors.toMap(FailedCallRecord::getCtlHash, r -> r));

        uniqueBatch.values().parallelStream().forEach(this::compressResultData);

        for (ProcessedCdrResult res : uniqueBatch.values()) {
            Long hash = res.getCdrData().getCtlHash();
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