package com.infomedia.abacox.telephonypricing.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.sql.SQLException; // Keep if needed for rethrowing from processor
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Service
@Log4j2
@RequiredArgsConstructor
public class TableMigrationExecutor {

    private final SourceDataFetcher sourceDataFetcher;
    private final MigrationRowProcessor migrationRowProcessor; // Inject the new service

    // Optional: Keep EntityManager if used for tasks *outside* the row/batch processor
    // @PersistenceContext
    // private final EntityManager entityManager;

    // Batch size for Pass 2 updates. Pass 1 uses row-by-row.
    private static final int UPDATE_BATCH_SIZE = 100;

    // No @Transactional annotation on this orchestrator method
    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig) throws Exception {
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        int skippedExistingCount = 0; // Count rows found already present
        int failedInsertCount = 0;    // Count rows that failed insertion/processing in Pass 1
        int insertedCount = 0;        // Count rows successfully inserted in Pass 1
        int updatedFkCount = 0;       // Count FKs successfully updated in Pass 2
        int failedUpdateBatchCount = 0; // Count failed batches in Pass 2

        log.info("Executing migration for source table: {}", tableConfig.getSourceTableName());

        // --- Get Target Entity Metadata (using MigrationUtils) ---
        Class<?> targetEntityClass = Class.forName(tableConfig.getTargetEntityClassName());
        Field idField = MigrationUtils.findIdField(targetEntityClass);
        if (idField == null) {
            throw new IllegalArgumentException("Entity class " + targetEntityClass.getName() + " does not have an @Id field");
        }
        idField.setAccessible(true);
        String idFieldName = idField.getName();
        String idColumnName = MigrationUtils.getIdColumnName(idField);
        String tableName = MigrationUtils.getTableName(targetEntityClass);
        boolean isGeneratedId = idField.isAnnotationPresent(jakarta.persistence.GeneratedValue.class);

        // Infer FK info (using MigrationUtils)
        Map<String, ForeignKeyInfo> foreignKeyInfoMap = MigrationUtils.inferForeignKeyInfo(targetEntityClass);
        ForeignKeyInfo selfReferenceFkInfo = MigrationUtils.findSelfReference(foreignKeyInfoMap, targetEntityClass);
        boolean isSelfReferencing = selfReferenceFkInfo != null;

        log.debug("Target Table: {}, ID Field: {}, ID Column: {}, IsGenerated: {}, IsSelfReferencing: {}",
                tableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing);
        if (!foreignKeyInfoMap.isEmpty()) {
            log.debug("Inferred Foreign Keys (TargetFieldName -> DBColumnName): {}",
                    foreignKeyInfoMap.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getDbColumnName())));
        }
        // --- End Metadata ---

        // --- Fetch Source Data ---
        Set<String> columnsToFetch = new HashSet<>(tableConfig.getColumnMapping().keySet());
        // Ensure source columns for *all* FKs are included if mapped
        for (ForeignKeyInfo fkInfo : foreignKeyInfoMap.values()) {
            tableConfig.getColumnMapping().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(fkInfo.getForeignKeyField().getName()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(columnsToFetch::add);
        }
        // Explicitly add self-ref source column if not already covered (might be redundant but safe)
        if(selfReferenceFkInfo != null) {
             tableConfig.getColumnMapping().entrySet().stream()
                     .filter(entry -> entry.getValue().equals(selfReferenceFkInfo.getForeignKeyField().getName()))
                     .map(Map.Entry::getKey)
                     .findFirst()
                     .ifPresent(columnsToFetch::add);
        }
        // Ensure the source ID column itself is always fetched
        columnsToFetch.add(tableConfig.getSourceIdColumnName());

        log.debug("Fetching source columns: {}", columnsToFetch);
        List<Map<String, Object>> sourceData = sourceDataFetcher.fetchData(
                sourceDbConfig,
                tableConfig.getSourceTableName(),
                columnsToFetch,
                tableConfig.getSourceIdColumnName() // Ensure sorting by ID if needed for dependency order
        );

        if (sourceData == null || sourceData.isEmpty()) {
            log.info("No source data found for table {}. Migration skipped.", tableConfig.getSourceTableName());
            return;
        }
        log.info("Fetched {} rows from source table {}", sourceData.size(), tableConfig.getSourceTableName());
        // --- End Fetch ---

        // ==================================================
        // Pass 1: Insert rows, row-by-row transactionally
        // ==================================================
        log.info("Starting Pass 1: Processing {} potential rows for table {} (row-by-row transactions)", sourceData.size(), tableName);
        // List<Object> successfullyInsertedIds = new ArrayList<>(); // To track only truly inserted ones if needed

        for (Map<String, Object> sourceRow : sourceData) {
            processedCount++;
            boolean success = false;
            try {
                // Call the transactional method in the other service
                // This call is NOT itself transactional here.
                success = migrationRowProcessor.processSingleRowInsert(
                        sourceRow,
                        tableConfig,
                        targetEntityClass,
                        idField,
                        idFieldName,
                        idColumnName,
                        tableName,
                        isGeneratedId,
                        foreignKeyInfoMap,
                        selfReferenceFkInfo
                );

                if (success) {
                    // How to distinguish inserted vs skipped?
                    // The processor currently returns true for both.
                    // For accurate counts, processor needs to return more info (e.g., enum).
                    // Let's assume for now 'success' means potentially inserted OR skipped.
                    // We can refine counts later if needed.
                    // To estimate inserted, we could re-check existence AFTER the call, but that's inefficient.
                    // Let's just log progress based on processed count.
                    // insertedCount++; // Incrementing this here is inaccurate.
                } else {
                    // The method returned false, indicating a handled error (e.g., FK violation) during processing.
                    failedInsertCount++;
                }

            } catch (Exception e) {
                // Catch unexpected errors *calling* the transactional method or fatal errors bubbling up.
                Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName());
                log.error("Unexpected fatal error during Pass 1 processing for table {} (Source ID: {}): {}. Row processing aborted.",
                          tableName, sourceId != null ? sourceId : "UNKNOWN", e.getMessage(), e);
                failedInsertCount++;
                // Depending on requirements, you might want to:
                // 1. Continue to the next row (current behavior)
                // 2. Stop the entire migration for this table:
                //    throw new RuntimeException("Fatal error during migration pass 1 for table " + tableName + ", stopping.", e);
                // 3. Stop the entire application migration:
                //    throw e; // Let the exception propagate further up.
            }

             // Log progress periodically
             if (processedCount % 500 == 0 || processedCount == sourceData.size()) {
                 log.info("Pass 1 Progress for {}: Processed {} / {} rows...", tableName, processedCount, sourceData.size());
             }

        } // End Pass 1 loop

        // Note: insertedCount and skippedExistingCount cannot be accurately determined
        // without changing the return type of processSingleRowInsert.
        log.info("Finished Pass 1 for {}. Total Processed: {}, Failed Rows: {}",
                 tableName, processedCount, failedInsertCount);


        // ==================================================
        // Pass 2: Update self-referencing FKs (only if needed)
        // Batched Transactions for Updates
        // ==================================================
        if (isSelfReferencing) {
            log.info("Starting Pass 2: Updating self-reference FK '{}' for table {} (using batched transactions)",
                     selfReferenceFkInfo.getDbColumnName(), tableName);

            // Partition the original source data for batching updates
            // Process ALL source rows again, the processor will handle skipping those without parent FKs
            List<List<Map<String, Object>>> updateBatches = IntStream.range(0, (sourceData.size() + UPDATE_BATCH_SIZE - 1) / UPDATE_BATCH_SIZE)
                    .mapToObj(i -> sourceData.subList(i * UPDATE_BATCH_SIZE, Math.min((i + 1) * UPDATE_BATCH_SIZE, sourceData.size())))
                    .toList(); // Use toList() for Java 16+

            log.info("Processing {} update batches for self-reference FK.", updateBatches.size());
            int currentBatchNum = 0;

            for (List<Map<String, Object>> batch : updateBatches) {
                currentBatchNum++;
                log.info("Processing update batch {}/{} (Size: {})", currentBatchNum, updateBatches.size(), batch.size());
                try {
                    // Call the transactional method for the batch update
                    // This call is NOT itself transactional here.
                    int updatedInBatch = migrationRowProcessor.processSelfRefUpdateBatch(
                            batch,
                            tableConfig,
                            targetEntityClass,
                            tableName,
                            idColumnName,
                            idFieldName,
                            selfReferenceFkInfo,
                            UPDATE_BATCH_SIZE
                    );
                    updatedFkCount += updatedInBatch;
                    log.info("Update batch {} completed. Rows updated reported by driver in batch: {}", currentBatchNum, updatedInBatch);

                } catch (SQLException e) {
                    // Catch SQL errors specifically from the batch update transaction failure
                    log.error("SQLException processing self-ref update batch {} for table {}: SQLState: {}, ErrorCode: {}, Message: {}. Skipping batch.",
                              currentBatchNum, tableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    failedUpdateBatchCount++;
                    // Continue to the next batch
                } catch (Exception e) {
                     // Catch other unexpected errors during the update batch call
                     log.error("Unexpected error processing self-ref update batch {} for table {}: {}. Skipping batch.",
                               currentBatchNum, tableName, e.getMessage(), e);
                     failedUpdateBatchCount++;
                     // Continue to the next batch
                 }
            } // End Pass 2 batch loop

            log.info("Finished Pass 2 for {}. Total Updated FKs reported: {}. Failed Batches: {}",
                     tableName, updatedFkCount, failedUpdateBatchCount);
        } else {
             log.info("Skipping Pass 2 for {}: No self-referencing FK detected.", tableName);
        } // End if isSelfReferencing

        long duration = System.currentTimeMillis() - startTime;
        // Provide a summary. Note the counts for Pass 1 are estimates without more detailed return status.
        log.info("Finished migrating table {}. Summary: Total Processed: {}, Failed Inserts (Pass 1): {}, Updated FKs (Pass 2): {}, Failed Update Batches (Pass 2): {}. Duration: {} ms",
                tableName, processedCount, failedInsertCount, updatedFkCount, failedUpdateBatchCount, duration);
    }

}