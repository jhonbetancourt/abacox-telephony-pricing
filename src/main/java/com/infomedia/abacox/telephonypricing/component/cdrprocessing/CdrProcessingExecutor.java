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

    private final ExecutorService sequentialExecutor = Executors.newSingleThreadExecutor();
    private final CdrRoutingService cdrRoutingService;
    private final CdrProcessorService cdrProcessorService; // Added for direct access
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;

    public Future<?> submitCdrStreamProcessing(String filename, InputStream inputStream, Long plantTypeId) {
        return submitCdrStreamProcessing(filename, inputStream, plantTypeId, false);
    }

    public Future<?> submitCdrStreamProcessing(String filename, InputStream inputStream, Long plantTypeId, boolean forceProcess) {
        log.debug("Submitting CDR stream processing task for file: {}, PlantTypeID: {}, Force: {}", filename, plantTypeId, forceProcess);
        return sequentialExecutor.submit(() -> {
            try {
                cdrRoutingService.routeAndProcessCdrStreamInternal(filename, inputStream, plantTypeId, forceProcess);
            } catch (Exception e) {
                log.debug("Uncaught exception during sequential execution of routeAndProcessCdrStream for file: {}", filename, e);
                CdrData streamErrorData = new CdrData();
                streamErrorData.setRawCdrLine("STREAM_PROCESSING_FATAL_ERROR: " + filename);
                failedCallRecordPersistenceService.quarantineRecord(streamErrorData, QuarantineErrorType.UNHANDLED_EXCEPTION,
                        "Fatal error in sequential executor task: " + e.getMessage(), "SequentialExecutorTask", null);
            }
        });
    }

    public Future<?> submitFileReprocessing(Long fileInfoId) {
        return submitFileReprocessing(fileInfoId, true); // Default to cleaning up
    }

    public Future<?> submitFileReprocessing(Long fileInfoId, boolean cleanupExistingRecords) {
        log.debug("Submitting file reprocessing task for FileInfo ID: {}, Cleanup: {}", fileInfoId, cleanupExistingRecords);
        return sequentialExecutor.submit(() -> {
            try {
                cdrRoutingService.reprocessFile(fileInfoId, cleanupExistingRecords);
            } catch (Exception e) {
                log.debug("Uncaught exception during sequential execution of reprocessFile for FileInfo ID: {}", fileInfoId, e);
            }
        });
    }

    public Future<?> submitCallRecordReprocessing(Long callRecordId) {
        log.debug("Submitting CallRecord reprocessing task for ID: {}", callRecordId);
        return sequentialExecutor.submit(() -> {
            try {
                cdrProcessorService.reprocessCallRecord(callRecordId);
            } catch (Exception e) {
                log.debug("Uncaught exception during sequential execution of reprocessCallRecord for ID: {}", callRecordId, e);
            }
        });
    }

    public Future<?> submitFailedCallRecordReprocessing(Long failedCallRecordId) {
        log.debug("Submitting FailedCallRecord reprocessing task for ID: {}", failedCallRecordId);
        return sequentialExecutor.submit(() -> {
            try {
                cdrProcessorService.reprocessFailedCallRecord(failedCallRecordId);
            } catch (Exception e) {
                log.debug("Uncaught exception during sequential execution of reprocessFailedCallRecord for ID: {}", failedCallRecordId, e);
            }
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.debug("Shutting down CDR Routing sequential executor...");
        sequentialExecutor.shutdown();
        try {
            if (!sequentialExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("CDR Routing executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = sequentialExecutor.shutdownNow();
                log.debug("CDR Routing executor was forcefully shut down. {} tasks were dropped.", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            log.debug("CDR Routing executor shutdown interrupted.", e);
            sequentialExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.debug("CDR Routing sequential executor shut down.");
    }
}