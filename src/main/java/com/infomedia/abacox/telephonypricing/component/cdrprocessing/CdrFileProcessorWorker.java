// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrFileProcessorWorker.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Log4j2
@RequiredArgsConstructor
public class CdrFileProcessorWorker {

    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final CdrRoutingService cdrRoutingService;

    /**
     * This scheduled method acts as a trigger for the processing loop.
     * It will continuously process all available pending files sequentially until the queue is empty.
     * The fixedDelay now represents the idle polling interval when no work is found.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000) // Check for work every 5 seconds when idle
    public void processPendingFiles() {
        log.trace("Worker triggered. Checking for pending files...");

        // Loop continuously until no more pending files are found
        while (true) {
            Optional<FileInfo> fileToProcessOpt = fileInfoPersistenceService.findAndLockPendingFile();

            if (fileToProcessOpt.isEmpty()) {
                // No more pending files, break the loop and wait for the next schedule
                log.trace("No pending files found. Worker going idle.");
                break;
            }

            FileInfo fileInfo = fileToProcessOpt.get();
            log.info("Worker picked up file for processing: ID={}, Name={}", fileInfo.getId(), fileInfo.getFilename());

            try {
                cdrRoutingService.processFileInfo(fileInfo.getId());

                // If reprocessFile completes without throwing an exception, we mark it as COMPLETED.
                // The internal logic of reprocessFile already handles logging the outcome.
                fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.COMPLETED);
                log.info("Successfully processed and marked file ID {} as COMPLETED.", fileInfo.getId());

            } catch (Exception e) {
                // This catch block handles unexpected, critical failures in the routing service itself.
                log.error("Critical failure while processing file ID: {}. Marking as FAILED.", fileInfo.getId(), e);
                fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.FAILED);
            }
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class StartupRecoveryService {
        private final FileInfoPersistenceService fileInfoPersistenceService;

        @EventListener(ContextRefreshedEvent.class)
        public void onApplicationEvent() {
            log.info("Application started. Checking for files to recover...");
            fileInfoPersistenceService.resetInProgressToPending();
        }
    }
}