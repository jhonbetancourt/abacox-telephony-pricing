package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.CompressionZipUtil;
import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
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
    private final TenantBatchPersister tenantBatchPersister;

    private static final int BATCH_SIZE = 1000;

    @Scheduled(fixedDelay = 200)
    public void processPersistenceQueue() {
        List<ProcessedCdrResult> batch = new ArrayList<>(BATCH_SIZE);
        int count = queueService.drainTo(batch, BATCH_SIZE);

        if (count == 0) return;

        // Group the drained results by their tenantId
        Map<String, List<ProcessedCdrResult>> batchesByTenant = batch.stream()
                .filter(r -> r.getTenantId() != null)
                .collect(Collectors.groupingBy(ProcessedCdrResult::getTenantId));

        // Process each tenant's batch in its own transaction and context
        for (Map.Entry<String, List<ProcessedCdrResult>> entry : batchesByTenant.entrySet()) {
            String tenantId = entry.getKey();
            List<ProcessedCdrResult> tenantBatch = entry.getValue();

            try {
                TenantContext.setTenant(tenantId);
                // Call the new service. This is an external call, so the proxy will initiate a transaction.
                tenantBatchPersister.persistTenantBatch(tenantBatch);
            } catch (Exception e) {
                log.error("Failed to persist batch for tenant [{}]. Records might be lost.", tenantId, e);
                // Optional: Implement logic to re-queue the failed tenantBatch.
            } finally {
                TenantContext.clear();
            }
        }
    }
}