package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Log4j2
@RequiredArgsConstructor
public class BatchPersistenceWorker {

    private final PersistenceQueueService queueService;
    private final TenantBatchPersister tenantBatchPersister;
    private final CdrConfigService cdrConfigService;
    private final FileProcessingTrackerService trackerService;

    private static final int BATCH_SIZE = 1000;

    @Scheduled(fixedDelay = 200)
    public void processPersistenceQueue() {
        List<ProcessedCdrResult> batch = new ArrayList<>(BATCH_SIZE);
        int count = queueService.drainTo(batch, BATCH_SIZE);

        if (count == 0)
            return;

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
                if (!cdrConfigService.isCdrProcessingEnabled()) {
                    log.debug("CDR processing disabled for tenant [{}]. Discarding {} queued results.",
                            tenantId, tenantBatch.size());
                    // Decrement tracker so internal state stays correct, and reset FileInfo
                    // records back to PENDING so they get reprocessed when CDR is re-enabled.
                    discardBatch(tenantBatch);
                    continue;
                }
                // Call the new service. This is an external call, so the proxy will initiate a
                // transaction.
                tenantBatchPersister.persistTenantBatch(tenantBatch);
            } catch (Exception e) {
                log.error("Failed to persist batch for tenant [{}]. Records might be lost.", tenantId, e);
                // Optional: Implement logic to re-queue the failed tenantBatch.
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * Called when CDR processing is disabled and a queued batch must be discarded.
     * Marks each affected file as abandoned via the tracker service, which ensures
     * checkCompletion() resets the FileInfo to PENDING (not COMPLETED) once all
     * in-flight records are drained — regardless of when markParsingComplete()
     * fires.
     */
    private void discardBatch(List<ProcessedCdrResult> batch) {
        Map<Long, Integer> countsByFile = new HashMap<>();
        for (ProcessedCdrResult r : batch) {
            if (r.getCdrData() != null && r.getCdrData().getFileInfo() != null) {
                Long fileId = r.getCdrData().getFileInfo().getId();
                countsByFile.merge(fileId, 1, Integer::sum);
            }
        }
        // abandonFile marks the file as discarded AND decrements the tracker count.
        // When both parsing is complete and count reaches 0, the tracker will set
        // PENDING.
        countsByFile.forEach(trackerService::abandonFile);
    }
}