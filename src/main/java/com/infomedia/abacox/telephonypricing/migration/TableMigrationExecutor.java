package com.infomedia.abacox.telephonypricing.migration;

import jakarta.persistence.GeneratedValue; // Correct import for Jakarta EE
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
@Log4j2
@RequiredArgsConstructor
public class TableMigrationExecutor {

    private final SourceDataFetcher sourceDataFetcher;
    private final MigrationRowProcessor migrationRowProcessor;

    // Batch size hint for the fetcher (can be overridden by config if needed)
    private static final int FETCH_BATCH_SIZE = 2000;
    // Batch size for Pass 2 updates (can differ from fetch size, used by MigrationRowProcessor)
    private static final int UPDATE_BATCH_SIZE = 100;

    // No @Transactional on this orchestrator method
    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig) throws Exception {
        long startTime = System.currentTimeMillis();
        // Use AtomicIntegers for safe counting within lambdas/consumers
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger failedInsertCount = new AtomicInteger(0); // Counts rows processor indicated failed/skipped
        AtomicInteger updatedFkCount = new AtomicInteger(0);    // Counts FKs processor indicated updated
        AtomicInteger failedUpdateBatchCount = new AtomicInteger(0); // Tracks failed *calls* to batch processor

        log.info("Executing migration for source table: {}", tableConfig.getSourceTableName());

        // --- Get Target Entity Metadata ---
        Class<?> targetEntityClass;
        Field idField;
        String idFieldName;
        String idColumnName;
        String targetTableName; // Renamed from tableName for clarity
        boolean isGeneratedId;
        Map<String, ForeignKeyInfo> foreignKeyInfoMap;
        ForeignKeyInfo selfReferenceFkInfo;
        boolean isSelfReferencing;

        try {
            targetEntityClass = Class.forName(tableConfig.getTargetEntityClassName());
            idField = MigrationUtils.findIdField(targetEntityClass);
            if (idField == null) throw new IllegalArgumentException("Entity class " + targetEntityClass.getName() + " does not have an @Id field");
            idField.setAccessible(true); // Ensure accessible
            idFieldName = idField.getName();
            idColumnName = MigrationUtils.getIdColumnName(idField);
            targetTableName = MigrationUtils.getTableName(targetEntityClass);
            isGeneratedId = idField.isAnnotationPresent(GeneratedValue.class); // Use Jakarta annotation
            foreignKeyInfoMap = MigrationUtils.inferForeignKeyInfo(targetEntityClass);
            selfReferenceFkInfo = MigrationUtils.findSelfReference(foreignKeyInfoMap, targetEntityClass);
            isSelfReferencing = selfReferenceFkInfo != null;

            logMetadata(targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing, foreignKeyInfoMap);
        } catch (ClassNotFoundException | IllegalArgumentException e) {
             log.error("Failed to load or analyze target entity class '{}': {}", tableConfig.getTargetEntityClassName(), e.getMessage(), e);
             throw new RuntimeException("Migration failed due to target entity metadata error for " + tableConfig.getTargetEntityClassName(), e);
        }
        // --- End Metadata ---

        // --- Determine Columns to Fetch ---
        Set<String> columnsToFetch = determineColumnsToFetch(tableConfig, foreignKeyInfoMap, selfReferenceFkInfo);
        if (columnsToFetch.isEmpty()) {
             log.warn("No source columns derived from mapping for {}. Skipping.", tableConfig.getSourceTableName());
             return;
        }
        log.debug("Requesting source columns for {}: {}", tableConfig.getSourceTableName(), columnsToFetch);
        // --- End Columns ---


        // ==================================================
        // Pass 1: Insert rows using batched fetching
        // ==================================================
        log.info("Starting Pass 1: Processing rows for table {} (Fetch Batch Size: {})", targetTableName, FETCH_BATCH_SIZE);

        try {
            // Define the processor for Pass 1 batches
            Consumer<List<Map<String, Object>>> pass1BatchProcessor = batch -> {
                log.debug("Processing Pass 1 batch of size {} for target table {}", batch.size(), targetTableName);
                for (Map<String, Object> sourceRow : batch) {
                    int currentProcessed = processedCount.incrementAndGet();
                    boolean success = false;
                    Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName()); // Get ID for logging
                    try {
                        // Call the transactional method in the other service for *each row*
                        success = migrationRowProcessor.processSingleRowInsert(
                                sourceRow, tableConfig, targetEntityClass, idField, idFieldName,
                                idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap, selfReferenceFkInfo
                        );
                        if (!success) {
                            // Row was processed but resulted in a handled failure (e.g., skipped duplicate, FK issue)
                            failedInsertCount.incrementAndGet();
                            log.debug("Pass 1: Row processing indicated failure/skip for Source ID: {}", sourceId != null ? sourceId : "UNKNOWN");
                        }
                    } catch (Exception e) {
                        // Catch unexpected errors *calling* the transactional method or fatal errors bubbling up.
                        log.error("Unexpected fatal error during Pass 1 processing for target table {} (Source ID: {}): {}. Row processing failed.",
                                  targetTableName, sourceId != null ? sourceId : "UNKNOWN", e.getMessage(), e);
                        failedInsertCount.incrementAndGet();
                        // Decide whether to continue with the batch or stop (current loop continues)
                    }
                    // Log progress within the batch processing if needed
                    // if (currentProcessed % 100 == 0) { log.trace(...)}
                } // End loop through rows in batch
                log.debug("Finished processing Pass 1 batch for target table {}", targetTableName);
            }; // End lambda definition

            // Execute the fetch, which calls the processor for each batch
            sourceDataFetcher.fetchData(
                    sourceDbConfig,
                    tableConfig.getSourceTableName(),
                    columnsToFetch,
                    tableConfig.getSourceIdColumnName(), // Crucial for paging ORDER BY
                    FETCH_BATCH_SIZE,
                    pass1BatchProcessor
            );

        } catch (Exception e) {
            // Catch errors during the fetchData setup or fatal errors from the processor rethrown by fetchData
             log.error("Fatal error during Pass 1 fetch/process for table {}: {}. Aborting migration for this table.", targetTableName, e.getMessage(), e);
             // Re-throw to stop the overall migration if desired
             throw new RuntimeException("Fatal error during migration pass 1 for table " + targetTableName + ", stopping.", e);
        }

        log.info("Finished Pass 1 for {}. Total Processed Rows: {}, Failed/Skipped Rows: {}",
                 targetTableName, processedCount.get(), failedInsertCount.get());


        // ==================================================
        // Pass 2: Update self-referencing FKs (only if needed)
        // Re-fetches data using batching
        // ==================================================
        if (isSelfReferencing) {
            log.info("Starting Pass 2: Updating self-reference FK '{}' for table {} (Fetch Batch Size: {})",
                     selfReferenceFkInfo.getDbColumnName(), targetTableName, FETCH_BATCH_SIZE);

            try {
                 // Define the processor for Pass 2 batches
                 Consumer<List<Map<String, Object>>> pass2BatchProcessor = batch -> {
                     if (batch.isEmpty()) {
                         log.trace("Skipping empty batch for Pass 2 self-ref update.");
                         return;
                     }
                     log.debug("Processing Pass 2 batch of size {} for self-ref update on {}", batch.size(), targetTableName);
                     try {
                         // Call the transactional method for the *entire batch* update
                         int updatedInBatch = migrationRowProcessor.processSelfRefUpdateBatch(
                                 batch, tableConfig, targetEntityClass, targetTableName, idColumnName,
                                 idFieldName, selfReferenceFkInfo, UPDATE_BATCH_SIZE // Pass configured update batch size
                         );
                         updatedFkCount.addAndGet(updatedInBatch);
                         log.debug("Self-ref update batch completed for {}. Rows updated reported by processor: {}", targetTableName, updatedInBatch);
                     } catch (SQLException sqle) {
                         // Catch SQL errors specifically from the batch update transaction failure
                         log.error("SQLException processing self-ref update batch for table {}: SQLState: {}, ErrorCode: {}, Message: {}. Skipping batch.",
                                   targetTableName, sqle.getSQLState(), sqle.getErrorCode(), sqle.getMessage());
                         failedUpdateBatchCount.incrementAndGet();
                     } catch (Exception e) {
                         // Catch other unexpected errors during the update batch call
                         log.error("Unexpected error processing self-ref update batch for table {}: {}. Skipping batch.",
                                   targetTableName, e.getMessage(), e);
                         failedUpdateBatchCount.incrementAndGet();
                     }
                 }; // End lambda definition

                 // Execute the fetch *again* for Pass 2, calling the Pass 2 processor
                 sourceDataFetcher.fetchData(
                         sourceDbConfig,
                         tableConfig.getSourceTableName(),
                         columnsToFetch, // Need the same columns, especially source ID and source FK column
                         tableConfig.getSourceIdColumnName(), // Crucial for paging ORDER BY consistency
                         FETCH_BATCH_SIZE, // Use fetch batch size for reading
                         pass2BatchProcessor
                 );

            } catch (Exception e) {
                 // Catch errors during the fetchData setup for Pass 2
                 log.error("Fatal error during Pass 2 fetch setup for table {}: {}. Aborting self-ref updates for this table.", targetTableName, e.getMessage(), e);
                 // Optionally re-throw if this is critical
                 // throw new RuntimeException("Fatal error during migration pass 2 fetch for table " + targetTableName + ", stopping.", e);
            }

            log.info("Finished Pass 2 for {}. Total Updated FKs reported: {}. Failed Update Batch Calls: {}",
                     targetTableName, updatedFkCount.get(), failedUpdateBatchCount.get());
        } else {
             log.info("Skipping Pass 2 for {}: No self-referencing FK detected.", targetTableName);
        } // End if isSelfReferencing

        long duration = System.currentTimeMillis() - startTime;
        log.info("Finished migrating table {}. Summary: Total Processed (Pass 1): {}, Failed/Skipped Inserts (Pass 1): {}, Updated FKs (Pass 2): {}, Failed Update Batches (Pass 2): {}. Duration: {} ms",
                targetTableName, processedCount.get(), failedInsertCount.get(), updatedFkCount.get(), failedUpdateBatchCount.get(), duration);
    }


    // --- Helper Methods ---

    private void logMetadata(String targetTableName, String idFieldName, String idColumnName, boolean isGeneratedId, boolean isSelfReferencing, Map<String, ForeignKeyInfo> foreignKeyInfoMap) {
        log.debug("Target Table: {}, ID Field: {}, ID Column: {}, IsGenerated: {}, IsSelfReferencing: {}",
                targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing);
        if (!foreignKeyInfoMap.isEmpty()) {
            log.debug("Inferred Foreign Keys (TargetFieldName -> DBColumnName): {}",
                    foreignKeyInfoMap.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getDbColumnName())));
        } else {
            log.debug("No foreign keys inferred for target table {}", targetTableName);
        }
    }

    private Set<String> determineColumnsToFetch(TableMigrationConfig tableConfig, Map<String, ForeignKeyInfo> foreignKeyInfoMap, ForeignKeyInfo selfReferenceFkInfo) {
        Set<String> columnsToFetch = new HashSet<>();

        // Add columns directly mapped in the config
        if (tableConfig.getColumnMapping() != null) {
             columnsToFetch.addAll(tableConfig.getColumnMapping().keySet());
        } else {
             log.warn("Column mapping is null for source table {}", tableConfig.getSourceTableName());
        }


        // Ensure source columns for *all* mapped FKs are included
        if (foreignKeyInfoMap != null && tableConfig.getColumnMapping() != null) {
            for (ForeignKeyInfo fkInfo : foreignKeyInfoMap.values()) {
                if (fkInfo != null && fkInfo.getForeignKeyField() != null) {
                    tableConfig.getColumnMapping().entrySet().stream()
                            .filter(entry -> entry.getValue().equals(fkInfo.getForeignKeyField().getName()))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .ifPresent(columnsToFetch::add);
                }
            }
        }

        // Explicitly add self-ref source column if mapped and not already present
        if (selfReferenceFkInfo != null && selfReferenceFkInfo.getForeignKeyField() != null && tableConfig.getColumnMapping() != null) {
             tableConfig.getColumnMapping().entrySet().stream()
                     .filter(entry -> entry.getValue().equals(selfReferenceFkInfo.getForeignKeyField().getName()))
                     .map(Map.Entry::getKey)
                     .findFirst()
                     .ifPresent(columnsToFetch::add);
        }

        // Ensure the source ID column itself is always fetched (needed for paging and processing)
        if (tableConfig.getSourceIdColumnName() != null && !tableConfig.getSourceIdColumnName().trim().isEmpty()) {
             columnsToFetch.add(tableConfig.getSourceIdColumnName());
        } else {
             log.warn("Source ID column name is missing or empty in configuration for source table {}. This might cause issues if paging is attempted.", tableConfig.getSourceTableName());
        }

        return columnsToFetch;
    }
}