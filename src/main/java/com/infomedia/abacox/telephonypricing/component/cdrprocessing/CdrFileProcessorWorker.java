package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.multitenancy.MultitenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Log4j2
@RequiredArgsConstructor
public class CdrFileProcessorWorker {

    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrRoutingService cdrRoutingService;
    private final CdrProcessingExecutor cdrProcessingExecutor;
    private final MultitenantRunner multitenantRunner;

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void processPendingFilesForAllTenants() { // <-- RENAME for clarity
        multitenantRunner.runForALlTenants(this::processPendingFilesForCurrentTenant);
    }

    private void processPendingFilesForCurrentTenant() { // <-- NEW METHOD with original logic
        int availableSlots = cdrProcessingExecutor.getAvailableSlots();

        if (availableSlots <= 0) {
            log.trace("Thread pool is full. Skipping DB fetch for current tenant this cycle.");
            return;
        }

        log.debug("Thread pool has {} open slots. Fetching pending files for current tenant...", availableSlots);

        List<FileInfo> filesToProcess = fileInfoPersistenceService.findAndLockPendingFiles(availableSlots);

        if (filesToProcess.isEmpty()) {
            return;
        }

        log.info("Worker fetched batch of {} files for current tenant. Submitting to executor...", filesToProcess.size());

        for (FileInfo fileInfo : filesToProcess) {
            // Because this Runnable is created while TenantContext is set,
            // the TenantAwareTaskDecorator will correctly propagate the context to the execution thread.
            cdrProcessingExecutor.submitTask(() -> {
                log.info("Worker starting processing for file ID={}, Name={}", fileInfo.getId(), fileInfo.getFilename());
                try {
                    cdrRoutingService.processFileInfo(fileInfo.getId());
                    // The 'updateStatus' call inside markParsingComplete will also be in the correct tenant context
                } catch (Exception e) {
                    log.error("Critical failure processing file ID: {}. Marking FAILED.", fileInfo.getId(), e);
                    fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.FAILED);
                }
            });
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class StartupRecoveryService {
        private final FileInfoPersistenceService fileInfoPersistenceService;
        private final MultitenantRunner multitenantRunner;

        @EventListener(ContextRefreshedEvent.class)
        public void onApplicationEvent() {
            log.info("Application started. Recovering stalled files...");
            multitenantRunner.runForALlTenants(fileInfoPersistenceService::resetInProgressToPending);
        }
    }
}