package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@Log4j2
public class PersistenceQueueService {

    // Capacity of 50k items to buffer spikes
    private final BlockingQueue<ProcessedCdrResult> queue = new LinkedBlockingQueue<>(50000);

    public void submit(ProcessedCdrResult result) {
        // Offer allows us to handle full queue gracefully if needed, 
        // but put() blocks the producer, creating backpressure.
        try {
            queue.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while submitting to persistence queue", e);
        }
    }

    public int drainTo(List<ProcessedCdrResult> target, int maxElements) {
        return queue.drainTo(target, maxElements);
    }
    
    public int size() {
        return queue.size();
    }
}