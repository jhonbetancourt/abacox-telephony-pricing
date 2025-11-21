package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrProcessingExecutor {

    // CONSTANT: The max number of parallel files
    private static final int MAX_THREADS = 4;

    // CHANGED: Use ThreadPoolExecutor directly to access metrics (getActiveCount)
    // We use a LinkedBlockingQueue but we will manually manage flow control in the Worker
    private final ThreadPoolExecutor taskExecutor = new ThreadPoolExecutor(
            MAX_THREADS, 
            MAX_THREADS,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()
    );
    
    private final CdrRoutingService cdrRoutingService;
    private final CdrProcessorService cdrProcessorService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;

    /**
     * Calculates how many threads are currently idle.
     * Use this to determine how many new files to fetch from the DB.
     */
    public int getAvailableSlots() {
        // Active count is approximate, but sufficient for this logic.
        int active = taskExecutor.getActiveCount();
        // Also check the queue size. If there are tasks in the queue, we have 0 slots available.
        int queued = taskExecutor.getQueue().size();
        
        if (queued > 0) {
            return 0;
        }
        
        int available = MAX_THREADS - active;
        return Math.max(0, available);
    }

    public Future<?> submitCdrStreamProcessing(String filename, InputStream inputStream, Long plantTypeId, boolean forceProcess) {
        log.debug("Submitting CDR stream processing task for file: {}, PlantTypeID: {}, Force: {}", filename, plantTypeId, forceProcess);
        return taskExecutor.submit(() -> {
            try {
                cdrRoutingService.routeAndProcessCdrStreamInternal(filename, inputStream, plantTypeId, forceProcess);
            } catch (Exception e) {
                log.error("Uncaught exception during execution of routeAndProcessCdrStream for file: {}", filename, e);
                CdrData streamErrorData = new CdrData();
                streamErrorData.setRawCdrLine("STREAM_PROCESSING_FATAL_ERROR: " + filename);
                failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.UNHANDLED_EXCEPTION,
                        "Fatal error in executor task: " + e.getMessage(), "ExecutorTask", null);
            }
        });
    }

    public void submitTask(Runnable task) {
        taskExecutor.submit(task);
    }

    public Future<?> submitFileReprocessing(Long fileInfoId, boolean cleanupExistingRecords) {
        return taskExecutor.submit(() -> {
            try {
                cdrRoutingService.reprocessFileInfo(fileInfoId, cleanupExistingRecords);
            } catch (Exception e) {
                log.error("Uncaught exception during execution of reprocessFile for FileInfo ID: {}", fileInfoId, e);
            }
        });
    }

    public Future<?> submitCallRecordReprocessing(Long callRecordId) {
        return taskExecutor.submit(() -> {
            try {
                cdrProcessorService.reprocessCallRecord(callRecordId);
            } catch (Exception e) {
                log.error("Uncaught exception during execution of reprocessCallRecord for ID: {}", callRecordId, e);
            }
        });
    }

    public Future<?> submitFailedCallRecordReprocessing(Long failedCallRecordId) {
        return taskExecutor.submit(() -> {
            try {
                cdrProcessorService.reprocessFailedCallRecord(failedCallRecordId);
            } catch (Exception e) {
                log.error("Uncaught exception during execution of reprocessFailedCallRecord for ID: {}", failedCallRecordId, e);
            }
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.debug("Shutting down CDR Processing executor...");
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("CDR Processing executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = taskExecutor.shutdownNow();
                log.debug("CDR Processing executor was forcefully shut down. {} tasks were dropped.", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            log.debug("CDR Processing executor shutdown interrupted.", e);
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.debug("CDR Processing executor shut down.");
    }
}