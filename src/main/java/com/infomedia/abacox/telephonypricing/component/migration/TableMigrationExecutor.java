// File: com/infomedia/abacox/telephonypricing/component/migration/TableMigrationExecutor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import jakarta.persistence.GeneratedValue;
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

    private static final int FETCH_BATCH_SIZE = 2000;
    private static final int UPDATE_BATCH_SIZE = 100;

    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig) throws Exception {
        long startTime = System.currentTimeMillis();
        AtomicInteger processedCountPass1 = new AtomicInteger(0);
        AtomicInteger failedInsertCountPass1 = new AtomicInteger(0);
        AtomicInteger updatedFkCountPass2 = new AtomicInteger(0);
        AtomicInteger failedUpdateBatchCountPass2 = new AtomicInteger(0);
        AtomicInteger updatedActiveCountPass3 = new AtomicInteger(0);
        AtomicInteger failedUpdateBatchCountPass3 = new AtomicInteger(0);

        log.info("Executing migration for source table: {}", tableConfig.getSourceTableName());

        Class<?> targetEntityClass;
        Field idField;
        String idFieldName;
        String idColumnName;
        String targetTableName;
        boolean isGeneratedId;
        Map<String, ForeignKeyInfo> foreignKeyInfoMap;
        ForeignKeyInfo selfReferenceFkInfo;
        boolean isSelfReferencing;
        // REMOVE: These are not in the target entity
        // Field targetHistoricalControlIdField = null;
        // Field targetValidFromDateField = null;

        try {
            targetEntityClass = Class.forName(tableConfig.getTargetEntityClassName());
            idField = MigrationUtils.findIdField(targetEntityClass);
            if (idField == null) throw new IllegalArgumentException("Entity class " + targetEntityClass.getName() + " does not have an @Id field");
            idField.setAccessible(true);
            idFieldName = idField.getName();
            idColumnName = MigrationUtils.getIdColumnName(idField);
            targetTableName = MigrationUtils.getTableName(targetEntityClass);
            isGeneratedId = idField.isAnnotationPresent(GeneratedValue.class);
            foreignKeyInfoMap = MigrationUtils.inferForeignKeyInfo(targetEntityClass);
            selfReferenceFkInfo = MigrationUtils.findSelfReference(foreignKeyInfoMap, targetEntityClass);
            isSelfReferencing = selfReferenceFkInfo != null;

            if (tableConfig.isProcessHistoricalActiveness()) {
                if (tableConfig.getSourceHistoricalControlIdColumn() == null || tableConfig.getSourceHistoricalControlIdColumn().trim().isEmpty() ||
                    tableConfig.getSourceValidFromDateColumn() == null || tableConfig.getSourceValidFromDateColumn().trim().isEmpty()) {
                    throw new IllegalArgumentException("Source historical control ID and valid from date columns must be specified and non-empty for historical activeness processing for table " + tableConfig.getSourceTableName());
                }
                // We don't need to find these fields in the target entity,
                // as 'active' is the only target field related to this pass.
                // The source column names are sufficient for the processor.
                log.info("Historical activeness processing enabled for {}. Source columns: HistCtl='{}', ValidFrom='{}'",
                         targetTableName, tableConfig.getSourceHistoricalControlIdColumn(), tableConfig.getSourceValidFromDateColumn());
            }

            logMetadata(targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing, foreignKeyInfoMap);
        } catch (ClassNotFoundException | IllegalArgumentException e) {
             log.error("Failed to load or analyze target entity class '{}': {}", tableConfig.getTargetEntityClassName(), e.getMessage(), e);
             throw new RuntimeException("Migration failed due to target entity metadata error for " + tableConfig.getTargetEntityClassName(), e);
        }

        Set<String> columnsToFetch = determineColumnsToFetch(tableConfig, foreignKeyInfoMap, selfReferenceFkInfo);
        if (tableConfig.isProcessHistoricalActiveness()) {
            // Ensure these source columns are fetched for Pass 3
            columnsToFetch.add(tableConfig.getSourceHistoricalControlIdColumn());
            columnsToFetch.add(tableConfig.getSourceValidFromDateColumn());
        }

        if (columnsToFetch.isEmpty()) {
             log.warn("No source columns derived from mapping for {}. Skipping.", tableConfig.getSourceTableName());
             return;
        }
        log.debug("Requesting source columns for {}: {}", tableConfig.getSourceTableName(), columnsToFetch);

        // --- Pass 1: Insert rows ---
        log.info("Starting Pass 1: Processing rows for table {} (Fetch Batch Size: {})", targetTableName, FETCH_BATCH_SIZE);
        try {
            Consumer<List<Map<String, Object>>> pass1BatchProcessor = batch -> {
                log.debug("Processing Pass 1 batch of size {} for target table {}", batch.size(), targetTableName);
                for (Map<String, Object> sourceRow : batch) {
                    processedCountPass1.incrementAndGet();
                    boolean success = false;
                    Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName());
                    try {
                        success = migrationRowProcessor.processSingleRowInsert(
                                sourceRow, tableConfig, targetEntityClass, idField, idFieldName,
                                idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap, selfReferenceFkInfo
                        );
                        if (!success) {
                            failedInsertCountPass1.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Unexpected fatal error during Pass 1 processing for target table {} (Source ID: {}): {}. Row processing failed.",
                                  targetTableName, sourceId != null ? sourceId : "UNKNOWN", e.getMessage(), e);
                        failedInsertCountPass1.incrementAndGet();
                    }
                }
                log.debug("Finished processing Pass 1 batch for target table {}", targetTableName);
            };

            sourceDataFetcher.fetchData(
                    sourceDbConfig,
                    tableConfig.getSourceTableName(),
                    columnsToFetch,
                    tableConfig.getSourceIdColumnName(),
                    FETCH_BATCH_SIZE,
                    pass1BatchProcessor
            );
        } catch (Exception e) {
             log.error("Fatal error during Pass 1 fetch/process for table {}: {}. Aborting migration for this table.", targetTableName, e.getMessage(), e);
             throw new RuntimeException("Fatal error during migration pass 1 for table " + targetTableName + ", stopping.", e);
        }
        log.info("Finished Pass 1 for {}. Total Processed Rows: {}, Failed/Skipped Rows: {}",
                 targetTableName, processedCountPass1.get(), failedInsertCountPass1.get());

        // --- Pass 2: Update self-referencing FKs ---
        if (isSelfReferencing) {
            log.info("Starting Pass 2: Updating self-reference FK '{}' for table {} (Fetch Batch Size: {})",
                     selfReferenceFkInfo.getDbColumnName(), targetTableName, FETCH_BATCH_SIZE);
            try {
                 Consumer<List<Map<String, Object>>> pass2BatchProcessor = batch -> {
                     if (batch.isEmpty()) return;
                     log.debug("Processing Pass 2 batch of size {} for self-ref update on {}", batch.size(), targetTableName);
                     try {
                         int updatedInBatch = migrationRowProcessor.processSelfRefUpdateBatch(
                                 batch, tableConfig, targetEntityClass, targetTableName, idColumnName,
                                 idFieldName, selfReferenceFkInfo, UPDATE_BATCH_SIZE
                         );
                         updatedFkCountPass2.addAndGet(updatedInBatch);
                     } catch (SQLException sqle) {
                         log.error("SQLException processing self-ref update batch for table {}: {}. Skipping batch.",
                                   targetTableName, sqle.getMessage());
                         failedUpdateBatchCountPass2.incrementAndGet();
                     } catch (Exception e) {
                         log.error("Unexpected error processing self-ref update batch for table {}: {}. Skipping batch.",
                                   targetTableName, e.getMessage(), e);
                         failedUpdateBatchCountPass2.incrementAndGet();
                     }
                 };
                 sourceDataFetcher.fetchData(
                         sourceDbConfig,
                         tableConfig.getSourceTableName(),
                         columnsToFetch,
                         tableConfig.getSourceIdColumnName(),
                         FETCH_BATCH_SIZE,
                         pass2BatchProcessor
                 );
            } catch (Exception e) {
                 log.error("Fatal error during Pass 2 fetch setup for table {}: {}. Aborting self-ref updates for this table.", targetTableName, e.getMessage(), e);
            }
            log.info("Finished Pass 2 for {}. Total Updated FKs reported: {}. Failed Update Batch Calls: {}",
                     targetTableName, updatedFkCountPass2.get(), failedUpdateBatchCountPass2.get());
        } else {
             log.info("Skipping Pass 2 for {}: No self-referencing FK detected.", targetTableName);
        }

        // --- Pass 3: Update 'active' based on historical data ---
        if (tableConfig.isProcessHistoricalActiveness()) {
            log.info("Starting Pass 3: Updating 'active' flag for table {} based on historical data (Fetch Batch Size: {})",
                     targetTableName, FETCH_BATCH_SIZE);
            try {
                Consumer<List<Map<String, Object>>> pass3BatchProcessor = batch -> {
                    if (batch.isEmpty()) return;
                    log.debug("Processing Pass 3 batch of size {} for 'active' flag update on {}", batch.size(), targetTableName);
                    try {
                        int updatedInBatch = migrationRowProcessor.processHistoricalActivenessUpdateBatch(
                                batch,
                                tableConfig, // Contains source historical column names
                                targetEntityClass, // For target ID type conversion
                                targetTableName,
                                idColumnName,      // Target ID column name
                                idFieldName,       // Target ID field name
                                // We pass the SOURCE column names for historical data to the processor
                                tableConfig.getSourceHistoricalControlIdColumn(),
                                tableConfig.getSourceValidFromDateColumn(),
                                UPDATE_BATCH_SIZE
                        );
                        updatedActiveCountPass3.addAndGet(updatedInBatch);
                    } catch (SQLException sqle) {
                        log.error("SQLException processing 'active' flag update batch for table {}: {}. Skipping batch.",
                                  targetTableName, sqle.getMessage());
                        failedUpdateBatchCountPass3.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Unexpected error processing 'active' flag update batch for table {}: {}. Skipping batch.",
                                  targetTableName, e.getMessage(), e);
                        failedUpdateBatchCountPass3.incrementAndGet();
                    }
                };

                sourceDataFetcher.fetchData(
                        sourceDbConfig,
                        tableConfig.getSourceTableName(),
                        columnsToFetch, // Already includes historical source columns
                        tableConfig.getSourceIdColumnName(),
                        FETCH_BATCH_SIZE,
                        pass3BatchProcessor
                );

            } catch (Exception e) {
                log.error("Fatal error during Pass 3 fetch setup for table {}: {}. Aborting 'active' flag updates for this table.", targetTableName, e.getMessage(), e);
            }
            log.info("Finished Pass 3 for {}. Total 'active' flags updated reported: {}. Failed Update Batch Calls: {}",
                     targetTableName, updatedActiveCountPass3.get(), failedUpdateBatchCountPass3.get());
        } else {
            log.info("Skipping Pass 3 for {}: Historical activeness processing not enabled.", targetTableName);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Finished migrating table {}. Summary: " +
                        "Pass 1 (Inserts) - Processed: {}, Failed/Skipped: {}. " +
                        "Pass 2 (Self-Ref FKs) - Updated: {}, Failed Batches: {}. " +
                        "Pass 3 (Historical Active) - Updated: {}, Failed Batches: {}. " +
                        "Duration: {} ms",
                targetTableName,
                processedCountPass1.get(), failedInsertCountPass1.get(),
                updatedFkCountPass2.get(), failedUpdateBatchCountPass2.get(),
                updatedActiveCountPass3.get(), failedUpdateBatchCountPass3.get(),
                duration);
    }

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
        if (tableConfig.getColumnMapping() != null) {
             columnsToFetch.addAll(tableConfig.getColumnMapping().keySet());
        } else {
             log.warn("Column mapping is null for source table {}", tableConfig.getSourceTableName());
        }

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

        if (selfReferenceFkInfo != null && selfReferenceFkInfo.getForeignKeyField() != null && tableConfig.getColumnMapping() != null) {
             tableConfig.getColumnMapping().entrySet().stream()
                     .filter(entry -> entry.getValue().equals(selfReferenceFkInfo.getForeignKeyField().getName()))
                     .map(Map.Entry::getKey)
                     .findFirst()
                     .ifPresent(columnsToFetch::add);
        }

        if (tableConfig.getSourceIdColumnName() != null && !tableConfig.getSourceIdColumnName().trim().isEmpty()) {
             columnsToFetch.add(tableConfig.getSourceIdColumnName());
        } else {
             log.warn("Source ID column name is missing or empty in configuration for source table {}. This might cause issues if paging is attempted.", tableConfig.getSourceTableName());
        }
        return columnsToFetch;
    }
}