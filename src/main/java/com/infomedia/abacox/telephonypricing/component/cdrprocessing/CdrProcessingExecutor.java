package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrProcessingExecutor {

    // Single-threaded executor to ensure sequential processing
    private final ExecutorService sequentialExecutor = Executors.newSingleThreadExecutor();
    private final EmployeeLookupService employeeLookupService; // For cache reset
    private final CdrRoutingService cdrRoutingService;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;

    /**
     * Submits the CDR stream processing task to a sequential executor.
     * This method returns immediately, and the processing happens asynchronously
     * in a single-threaded queue.
     *
     * @param filename       Name of the CDR file/stream.
     * @param inputStream    The CDR data stream.
     * @param plantTypeId    The ID of the plant type for initial parsing.
     * @return A Future representing the pending completion of the task.
     */
    public Future<?> submitCdrStreamProcessing(String filename, InputStream inputStream, Long plantTypeId) {
        log.info("Submitting CDR stream processing task for file: {}, PlantTypeID: {}", filename, plantTypeId);
        return sequentialExecutor.submit(() -> {
            try {
                // Reset caches at the beginning of each stream processing run
                employeeLookupService.resetCachesForNewStream();
                cdrRoutingService.routeAndProcessCdrStreamInternal(filename, inputStream, plantTypeId);
            } catch (Exception e) {
                log.error("Uncaught exception during sequential execution of routeAndProcessCdrStream for file: {}", filename, e);
                // Basic error handling for the task itself
                CdrData streamErrorData = new CdrData();
                streamErrorData.setRawCdrLine("STREAM_PROCESSING_FATAL_ERROR: " + filename);
                failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.UNHANDLED_EXCEPTION,
                        "Fatal error in sequential executor task: " + e.getMessage(), "SequentialExecutorTask", null);
            }
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.info("Shutting down CDR Routing sequential executor...");
        sequentialExecutor.shutdown();
        try {
            if (!sequentialExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("CDR Routing executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = sequentialExecutor.shutdownNow();
                log.warn("CDR Routing executor was forcefully shut down. {} tasks were dropped.", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            log.error("CDR Routing executor shutdown interrupted.", e);
            sequentialExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("CDR Routing sequential executor shut down.");
    }
}
