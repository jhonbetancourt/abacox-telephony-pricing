package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Log4j2
@RequiredArgsConstructor
public class FileProcessingTrackerService {

    private final FileInfoPersistenceService fileInfoPersistenceService;

    // Maps FileInfoID -> Number of records currently in Queue or Processing (In-Flight)
    private final Map<Long, AtomicInteger> pendingRecords = new ConcurrentHashMap<>();
    
    // Maps FileInfoID -> Boolean indicating if the InputStream has been fully read by the router
    private final Map<Long, Boolean> fileParsingFinished = new ConcurrentHashMap<>();

    /**
     * Initializes tracking for a new file. Called before routing starts.
     */
    public void initFile(Long fileInfoId) {
        pendingRecords.put(fileInfoId, new AtomicInteger(0));
        fileParsingFinished.put(fileInfoId, false);
    }

    /**
     * Increments the pending count. Called by the Producer (CdrProcessorService) before submitting to queue.
     */
    public void incrementPendingCount(Long fileInfoId, int delta) {
        if (fileInfoId == null) return;
        pendingRecords.computeIfAbsent(fileInfoId, k -> new AtomicInteger(0))
                      .addAndGet(delta);
    }

    /**
     * Decrements the pending count. Called by the Consumer (BatchPersistenceWorker) after DB flush.
     */
    public void decrementPendingCount(Long fileInfoId, int delta) {
        if (fileInfoId == null) return;
        
        AtomicInteger counter = pendingRecords.get(fileInfoId);
        if (counter != null) {
            int current = counter.addAndGet(-delta);
            checkCompletion(fileInfoId, current);
        }
    }

    /**
     * Marks that the file stream has been fully read and all batches submitted.
     */
    public void markParsingComplete(Long fileInfoId) {
        fileParsingFinished.put(fileInfoId, true);
        
        // Check immediately. If the file was empty or very small, or processing was faster than routing,
        // the count might already be 0.
        AtomicInteger counter = pendingRecords.get(fileInfoId);
        int current = (counter != null) ? counter.get() : 0;
        
        checkCompletion(fileInfoId, current);
    }

    /**
     * Checks if processing is fully complete for a file.
     */
    private void checkCompletion(Long fileInfoId, int pendingCount) {
        boolean isParsingDone = fileParsingFinished.getOrDefault(fileInfoId, false);

        // If parser is done AND no records are left in the queue/processing pipeline
        if (isParsingDone && pendingCount <= 0) {
            log.info("File processing finished. All records persisted. Marking File ID {} as COMPLETED.", fileInfoId);
            
            // Clean up memory maps
            pendingRecords.remove(fileInfoId);
            fileParsingFinished.remove(fileInfoId);

            // Update Database Status
            try {
                fileInfoPersistenceService.updateStatus(fileInfoId, FileInfo.ProcessingStatus.COMPLETED);
            } catch (Exception e) {
                log.error("Failed to update status to COMPLETED for file {}", fileInfoId, e);
            }
        }
    }
}