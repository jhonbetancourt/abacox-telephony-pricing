package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
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

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void processPendingFiles() {
        
        // 1. Check how many slots are open in the thread pool
        int availableSlots = cdrProcessingExecutor.getAvailableSlots();

        // 2. If system is busy, skip this cycle entirely. 
        // This prevents memory buildup.
        if (availableSlots <= 0) {
            log.trace("Thread pool is full (or has queued items). Skipping DB fetch this cycle.");
            return;
        }

        log.debug("Thread pool has {} open slots. Fetching pending files...", availableSlots);

        // 3. Only fetch exactly what we can handle immediately
        List<FileInfo> filesToProcess = fileInfoPersistenceService.findAndLockPendingFiles(availableSlots);

        if (filesToProcess.isEmpty()) {
            return;
        }

        log.info("Worker fetched batch of {} files. Submitting to executor...", filesToProcess.size());

        for (FileInfo fileInfo : filesToProcess) {
            cdrProcessingExecutor.submitTask(() -> {
                log.info("Worker starting processing for file ID={}, Name={}", fileInfo.getId(), fileInfo.getFilename());
                try {
                    cdrRoutingService.processFileInfo(fileInfo.getId());
                    fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.COMPLETED);
                    log.info("Successfully processed file ID {}", fileInfo.getId());
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

        @EventListener(ContextRefreshedEvent.class)
        public void onApplicationEvent() {
            log.info("Application started. Recovering stalled files...");
            fileInfoPersistenceService.resetInProgressToPending();
        }
    }
}