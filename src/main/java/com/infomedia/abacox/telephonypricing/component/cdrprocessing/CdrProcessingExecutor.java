// E:/Github/abacox/abacox-telephony-pricing/src/main/java/com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrProcessingExecutor.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantAwareTaskDecorator;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

@Service
@Log4j2
// The constructor now injects the bean defined in its own nested class
@RequiredArgsConstructor
public class CdrProcessingExecutor {

    // Inject the specific, qualified bean.
    @Qualifier("cdrTaskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;

    private final CdrRoutingService cdrRoutingService;
    private final CdrProcessorService cdrProcessorService;

    // This nested static class is a standard Spring Configuration.
    // It's co-located here for organizational purposes.
    @Configuration
    public static class CdrExecutorConfig {

        // This method creates the Spring bean.
        @Bean("cdrTaskExecutor") // Give the bean a specific name
        public ThreadPoolTaskExecutor cdrTaskExecutor(
                // Inject the property directly into the bean creation method
                @Value("${app.cdr.processing.max-threads:4}") int maxThreads) {

            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

            // Use the configured value to set pool sizes
            executor.setCorePoolSize(maxThreads);
            executor.setMaxPoolSize(maxThreads);
            executor.setQueueCapacity(200);
            executor.setThreadNamePrefix("cdr-exec-");

            // IMPORTANT: Apply the tenant-aware decorator
            executor.setTaskDecorator(new TenantAwareTaskDecorator());

            executor.initialize();
            return executor;
        }
    }

    /**
     * Calculates how many threads are currently idle.
     * Use this to determine how many new files to fetch from the DB.
     */
    public int getAvailableSlots() {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
        int active = executor.getActiveCount();
        int queued = executor.getQueue().size();

        if (queued > 0) {
            return 0;
        }

        int maxThreads = taskExecutor.getMaxPoolSize();
        int available = maxThreads - active;
        return Math.max(0, available);
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
                log.error("Uncaught exception during execution of reprocessFailedCallRecord for ID: {}",
                        failedCallRecordId, e);
            }
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.debug("Shutting down CDR Processing executor...");
        taskExecutor.shutdown();
        log.debug("CDR Processing executor shut down signal sent.");
    }
}