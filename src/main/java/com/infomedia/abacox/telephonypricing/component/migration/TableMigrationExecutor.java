package com.infomedia.abacox.telephonypricing.component.migration;

import jakarta.persistence.GeneratedValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
@Log4j2
@RequiredArgsConstructor
public class TableMigrationExecutor {

    private final SourceDataFetcher sourceDataFetcher;
    private final MigrationRowProcessor migrationRowProcessor;

    // --- CONFIGURATION ---
    // Increased batch size for high-speed streaming
    private static final int FETCH_BATCH_SIZE = 10000;
    private static final int UPDATE_BATCH_SIZE = 1000;

    // Async Configuration
    private static final int WRITER_THREADS = 4; // Number of parallel connections to Target DB
    private static final int MAX_QUEUED_BATCHES = 8; // Backpressure limit (prevents OOM)
    private static final int MIN_SPLIT_BATCH_SIZE = 100; // Threshold to drop to row-by-row during batch failure

    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig)
            throws Exception {
        long startTime = System.currentTimeMillis();

        // Stats Counters
        AtomicInteger processedCountPass1 = new AtomicInteger(0);
        AtomicInteger failedInsertCountPass1 = new AtomicInteger(0);
        AtomicInteger updatedFkCountPass2 = new AtomicInteger(0);
        AtomicInteger failedUpdateBatchCountPass2 = new AtomicInteger(0);

        // Error handling for async threads
        AtomicReference<Exception> asyncError = new AtomicReference<>(null);

        log.debug("Executing migration for source table: {}", tableConfig.getSourceTableName());

        // --- METADATA ANALYSIS ---
        Class<?> targetEntityClass;
        Field idField;
        String idFieldName;
        String idColumnName;
        String targetTableName;
        boolean isGeneratedId;
        Map<String, ForeignKeyInfo> foreignKeyInfoMap;
        ForeignKeyInfo selfReferenceFkInfo;
        boolean isSelfReferencing;

        try {
            targetEntityClass = Class.forName(tableConfig.getTargetEntityClassName());
            idField = MigrationUtils.findIdField(targetEntityClass);
            if (idField == null)
                throw new IllegalArgumentException(
                        "Entity class " + targetEntityClass.getName() + " does not have an @Id field");
            idField.setAccessible(true);
            idFieldName = idField.getName();
            idColumnName = MigrationUtils.getIdColumnName(idField);
            targetTableName = MigrationUtils.getTableName(targetEntityClass);
            isGeneratedId = idField.isAnnotationPresent(GeneratedValue.class);
            foreignKeyInfoMap = MigrationUtils.inferForeignKeyInfo(targetEntityClass);
            selfReferenceFkInfo = MigrationUtils.findSelfReference(foreignKeyInfoMap, targetEntityClass);
            isSelfReferencing = selfReferenceFkInfo != null;

            logMetadata(targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing,
                    foreignKeyInfoMap);

        } catch (ClassNotFoundException | IllegalArgumentException e) {
            log.error("Failed to load or analyze target entity class '{}': {}", tableConfig.getTargetEntityClassName(),
                    e.getMessage(), e);
            throw new RuntimeException("Migration failed due to target entity metadata error", e);
        }

        // --- PASS 1: INSERT ROWS (ASYNC PARALLEL WRITING) ---
        log.debug("Starting Pass 1: Streaming & Async Writing for table {} (Batch: {}, Threads: {})",
                targetTableName, FETCH_BATCH_SIZE, WRITER_THREADS);

        ExecutorService writerPool = Executors.newFixedThreadPool(WRITER_THREADS);
        Semaphore queuePermits = new Semaphore(MAX_QUEUED_BATCHES);

        String tenant = TenantContext.getTenant();

        try {
            Consumer<List<Map<String, Object>>> pass1AsyncProcessor = batch -> {
                // 1. Check if a previous batch failed
                if (asyncError.get() != null) {
                    throw new RuntimeException("Stopping fetch due to write error: " + asyncError.get().getMessage());
                }

                try {
                    // 2. Acquire permit (Blocks here if writers are too slow, pausing the fetcher)
                    queuePermits.acquire();

                    // 3. Submit to thread pool
                    writerPool.submit(() -> {
                        TenantContext.setTenant(tenant);
                        try {
                            if (asyncError.get() != null)
                                return; // Skip if already failed

                            // *** THE HEAVY LIFTING ***
                            try {
                                int skipped = processBatchWithRecursiveFallback(
                                        batch, tableConfig, targetEntityClass, idField, idFieldName,
                                        idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                                        selfReferenceFkInfo);
                                failedInsertCountPass1.addAndGet(skipped);
                            } catch (Exception e) {
                                log.error("Async Write Failed during fallback processing!", e);
                                asyncError.set(e);
                            }

                            processedCountPass1.addAndGet(batch.size());

                        } catch (Exception e) {
                            log.error("Async Write Failed!", e);
                            asyncError.set(e); // Flag error to stop fetcher
                        } finally {
                            // 4. Release permit so fetcher can get more data
                            queuePermits.release();
                            TenantContext.clear();
                        }
                    });

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Fetcher interrupted", e);
                }
            };

            // START THE STREAM
            sourceDataFetcher.fetchData(
                    sourceDbConfig,
                    tableConfig,
                    tableConfig.getWhereClause(),
                    FETCH_BATCH_SIZE,
                    pass1AsyncProcessor);

        } catch (Exception e) {
            log.error("Error during Pass 1 Fetch: {}", e.getMessage());
            throw e;
        } finally {
            // Wait for all writers to finish remaining batches
            log.debug("Fetcher finished. Waiting for pending async writes to complete...");
            writerPool.shutdown();
            writerPool.awaitTermination(1, TimeUnit.HOURS);

            if (asyncError.get() != null) {
                throw new RuntimeException("Migration failed during async write phase.", asyncError.get());
            }
        }

        log.debug("Finished Pass 1 (Async). Total Processed: {}, Failed/Skipped: {}",
                processedCountPass1.get(), failedInsertCountPass1.get());

        // --- PASS 2: SELF-REFERENCING FK UPDATES (SYNC) ---
        if (isSelfReferencing) {
            log.debug("Starting Pass 2: Updating self-reference FK '{}' for table {} (Batch: {})",
                    selfReferenceFkInfo.getDbColumnName(), targetTableName, FETCH_BATCH_SIZE);
            try {
                Consumer<List<Map<String, Object>>> pass2BatchProcessor = batch -> {
                    if (batch.isEmpty())
                        return;
                    try {
                        int updated = migrationRowProcessor.processSelfRefUpdateBatch(
                                batch, tableConfig, targetEntityClass, targetTableName, idColumnName,
                                idFieldName, selfReferenceFkInfo, UPDATE_BATCH_SIZE);
                        updatedFkCountPass2.addAndGet(updated);
                    } catch (SQLException sqle) {
                        log.error("SQLException processing self-ref update batch: {}. Skipping.", sqle.getMessage());
                        failedUpdateBatchCountPass2.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Unexpected error processing self-ref update batch: {}. Skipping.", e.getMessage(),
                                e);
                        failedUpdateBatchCountPass2.incrementAndGet();
                    }
                };

                sourceDataFetcher.fetchData(
                        sourceDbConfig,
                        tableConfig,
                        tableConfig.getWhereClause(),
                        FETCH_BATCH_SIZE,
                        pass2BatchProcessor);
            } catch (Exception e) {
                log.error("Fatal error during Pass 2: {}", e.getMessage(), e);
            }
            log.debug("Finished Pass 2. Total Updated FKs: {}", updatedFkCountPass2.get());
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Migration Complete for {} -> {} (Processed: {}, Failed/Skipped: {}, FK Updates: {}). Duration: {} ms",
                tableConfig.getSourceTableName(), targetTableName, processedCountPass1.get(),
                failedInsertCountPass1.get(), updatedFkCountPass2.get(), duration);
    }

    private void logMetadata(String targetTableName, String idFieldName, String idColumnName, boolean isGeneratedId,
            boolean isSelfReferencing, Map<String, ForeignKeyInfo> foreignKeyInfoMap) {
        log.debug("Target Table: {}, ID Field: {}, ID Column: {}, IsGenerated: {}, IsSelfReferencing: {}",
                targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing);
        if (!foreignKeyInfoMap.isEmpty()) {
            log.debug("Inferred Foreign Keys: {}",
                    foreignKeyInfoMap.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getDbColumnName())));
        }
    }

    private String extractShortErrorMessage(Exception e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("was aborted:")) {
                return msg.substring(msg.indexOf("was aborted:"));
            }
            if (current.getCause() == current)
                break;
            current = current.getCause();
        }

        String msg = e.getMessage();
        if (msg != null && msg.length() > 500) {
            return "..." + msg.substring(msg.length() - 500);
        }
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    private int processBatchWithRecursiveFallback(
            List<Map<String, Object>> batch, TableMigrationConfig tableConfig,
            Class<?> targetEntityClass, Field idField, String idFieldName,
            String idColumnName, String targetTableName, boolean isGeneratedId,
            Map<String, ForeignKeyInfo> foreignKeyInfoMap, ForeignKeyInfo selfReferenceFkInfo) {

        try {
            // Attempt full batch insert
            return migrationRowProcessor.processBatchInsert(
                    batch, tableConfig, targetEntityClass, idField, idFieldName,
                    idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                    selfReferenceFkInfo);
        } catch (Exception batchEx) {
            int batchSize = batch.size();

            // If the batch failed but is still large enough, split it in half
            if (batchSize > MIN_SPLIT_BATCH_SIZE) {
                log.warn("Batch of size {} failed. Splitting into halves. Reason: {}",
                        batchSize, extractShortErrorMessage(batchEx));

                int mid = batchSize / 2;
                List<Map<String, Object>> leftHalf = batch.subList(0, mid);
                List<Map<String, Object>> rightHalf = batch.subList(mid, batchSize);

                int failedCount = 0;
                // Recursively process both halves
                failedCount += processBatchWithRecursiveFallback(
                        leftHalf, tableConfig, targetEntityClass, idField, idFieldName,
                        idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                        selfReferenceFkInfo);

                failedCount += processBatchWithRecursiveFallback(
                        rightHalf, tableConfig, targetEntityClass, idField, idFieldName,
                        idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                        selfReferenceFkInfo);

                return failedCount;
            } else {
                // If the batch is small enough, drop to row-by-row
                log.warn("Sub-batch of size {} failed. Falling back to row-by-row processing. Reason: {}",
                        batchSize, extractShortErrorMessage(batchEx));

                int failedCount = 0;
                for (Map<String, Object> sourceRow : batch) {
                    try {
                        boolean success = migrationRowProcessor.processSingleRowInsert(
                                sourceRow, tableConfig, targetEntityClass, idField, idFieldName,
                                idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                                selfReferenceFkInfo);
                        if (!success) {
                            failedCount++;
                        }
                    } catch (Exception ex) {
                        failedCount++;
                    }
                }
                return failedCount;
            }
        }
    }
}