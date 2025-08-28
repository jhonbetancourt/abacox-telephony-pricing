// Create new file: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrFileProcessorWorker.java
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

    @Scheduled(fixedDelay = 10000, initialDelay = 5000) // Check for new files every 10 seconds
    public void processPendingFiles() {
        log.trace("Checking for pending CDR files to process...");
        Optional<FileInfo> fileToProcessOpt = fileInfoPersistenceService.findAndLockPendingFile();

        fileToProcessOpt.ifPresent(fileInfo -> {
            log.info("Worker picked up file for processing: ID={}, Name={}", fileInfo.getId(), fileInfo.getFilename());
            try {
                // This is a synchronous call within the scheduled task.
                // The existing reprocessFile logic is perfect for this.
                cdrRoutingService.reprocessFile(fileInfo.getId(), true);
                fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.COMPLETED, null);
                log.info("Successfully processed file ID: {}", fileInfo.getId());
            } catch (Exception e) {
                log.error("Critical failure while processing file ID: {}. Marking as FAILED.", fileInfo.getId(), e);
                fileInfoPersistenceService.updateStatus(fileInfo.getId(), FileInfo.ProcessingStatus.FAILED, e.getMessage());
            }
        });
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