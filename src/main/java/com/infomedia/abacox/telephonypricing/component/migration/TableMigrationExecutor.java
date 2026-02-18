// File: com/infomedia/abacox/telephonypricing/component/migration/TableMigrationExecutor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import jakarta.persistence.GeneratedValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final int ID_FETCH_BATCH_SIZE = 10000; // Larger batch for fetching just IDs

    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig)
            throws Exception {
        long startTime = System.currentTimeMillis();
        AtomicInteger processedCountPass1 = new AtomicInteger(0);
        AtomicInteger failedInsertCountPass1 = new AtomicInteger(0);
        AtomicInteger updatedFkCountPass2 = new AtomicInteger(0);
        AtomicInteger failedUpdateBatchCountPass2 = new AtomicInteger(0);
        AtomicInteger updatedActiveCountPass3 = new AtomicInteger(0);
        AtomicInteger failedUpdateBatchCountPass3 = new AtomicInteger(0);

        log.info("Executing migration for source table: {}", tableConfig.getSourceTableName());
        if (tableConfig.getMaxEntriesToMigrate() != null && tableConfig.getMaxEntriesToMigrate() > 0) {
            log.info("Migration will be limited to a maximum of {} entries.", tableConfig.getMaxEntriesToMigrate());
        }
        if (tableConfig.getOrderByClause() != null) {
            log.info("Using custom order by clause: {}", tableConfig.getOrderByClause());
        }

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

            if (tableConfig.isProcessHistoricalActiveness()) {
                if (tableConfig.getSourceHistoricalControlIdColumn() == null
                        || tableConfig.getSourceHistoricalControlIdColumn().trim().isEmpty() ||
                        tableConfig.getSourceValidFromDateColumn() == null
                        || tableConfig.getSourceValidFromDateColumn().trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Source historical control ID and valid from date columns must be specified for historical activeness processing for table "
                                    + tableConfig.getSourceTableName());
                }
                log.info(
                        "Historical activeness processing enabled for {}. Source columns: HistCtl='{}', ValidFrom='{}'",
                        targetTableName, tableConfig.getSourceHistoricalControlIdColumn(),
                        tableConfig.getSourceValidFromDateColumn());
            }

            logMetadata(targetTableName, idFieldName, idColumnName, isGeneratedId, isSelfReferencing,
                    foreignKeyInfoMap);
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            log.error("Failed to load or analyze target entity class '{}': {}", tableConfig.getTargetEntityClassName(),
                    e.getMessage(), e);
            throw new RuntimeException("Migration failed due to target entity metadata error for "
                    + tableConfig.getTargetEntityClassName(), e);
        }

        Set<String> columnsToFetch = determineColumnsToFetch(tableConfig, foreignKeyInfoMap, selfReferenceFkInfo);
        if (tableConfig.isProcessHistoricalActiveness()) {
            columnsToFetch.add(tableConfig.getSourceHistoricalControlIdColumn());
            columnsToFetch.add(tableConfig.getSourceValidFromDateColumn());
        }

        if (columnsToFetch.isEmpty()) {
            log.warn("No source columns derived from mapping for {}. Skipping.", tableConfig.getSourceTableName());
            return;
        }
        log.debug("Requesting source columns for {}: {}", tableConfig.getSourceTableName(), columnsToFetch);

        // --- Pass 1: Insert rows ---
        log.info("Starting Pass 1: Processing rows for table {} (Fetch Batch Size: {})", targetTableName,
                FETCH_BATCH_SIZE);
        try {
            Consumer<List<Map<String, Object>>> pass1BatchProcessor = batch -> {
                log.debug("Processing Pass 1 batch of size {} for target table {}", batch.size(), targetTableName);
                processedCountPass1.addAndGet(batch.size());

                // Try batch insert first
                try {
                    int skipped = migrationRowProcessor.processBatchInsert(
                            batch, tableConfig, targetEntityClass, idField, idFieldName,
                            idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap, selfReferenceFkInfo);
                    failedInsertCountPass1.addAndGet(skipped);
                    log.debug("Batch insert succeeded for {} rows in target table {}", batch.size(), targetTableName);
                } catch (Exception batchEx) {
                    // Batch failed — fall back to row-by-row processing
                    log.debug(
                            "Batch insert failed for target table {} ({}). Falling back to row-by-row processing for this batch of {} rows.",
                            targetTableName, batchEx.getMessage(), batch.size());
                    for (Map<String, Object> sourceRow : batch) {
                        Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName());
                        try {
                            boolean success = migrationRowProcessor.processSingleRowInsert(
                                    sourceRow, tableConfig, targetEntityClass, idField, idFieldName,
                                    idColumnName, targetTableName, isGeneratedId, foreignKeyInfoMap,
                                    selfReferenceFkInfo);
                            if (!success) {
                                failedInsertCountPass1.incrementAndGet();
                            }
                        } catch (Exception e) {
                            log.debug("Row-by-row fallback also failed for target table {} (Source ID: {}): {}",
                                    targetTableName, sourceId != null ? sourceId : "UNKNOWN", e.getMessage(), e);
                            failedInsertCountPass1.incrementAndGet();
                        }
                    }
                }
                log.debug("Finished processing Pass 1 batch for target table {}", targetTableName);
            };

            sourceDataFetcher.fetchData(
                    sourceDbConfig,
                    tableConfig.getSourceTableName(),
                    columnsToFetch,
                    tableConfig.getSourceIdColumnName(),
                    tableConfig.getOrderByClause(),
                    tableConfig.getMaxEntriesToMigrate(),
                    FETCH_BATCH_SIZE,
                    pass1BatchProcessor);
        } catch (Exception e) {
            log.error("Fatal error during Pass 1 fetch/process for table {}: {}. Aborting migration for this table.",
                    targetTableName, e.getMessage(), e);
            throw new RuntimeException(
                    "Fatal error during migration pass 1 for table " + targetTableName + ", stopping.", e);
        }
        log.info("Finished Pass 1 for {}. Total Processed Rows: {}, Failed/Skipped Rows: {}",
                targetTableName, processedCountPass1.get(), failedInsertCountPass1.get());

        // --- Pass 2: Update self-referencing FKs ---
        if (isSelfReferencing) {
            log.info("Starting Pass 2: Updating self-reference FK '{}' for table {} (Fetch Batch Size: {})",
                    selfReferenceFkInfo.getDbColumnName(), targetTableName, FETCH_BATCH_SIZE);
            try {
                Consumer<List<Map<String, Object>>> pass2BatchProcessor = batch -> {
                    if (batch.isEmpty())
                        return;
                    log.debug("Processing Pass 2 batch of size {} for self-ref update on {}", batch.size(),
                            targetTableName);
                    try {
                        int updatedInBatch = migrationRowProcessor.processSelfRefUpdateBatch(
                                batch, tableConfig, targetEntityClass, targetTableName, idColumnName,
                                idFieldName, selfReferenceFkInfo, UPDATE_BATCH_SIZE);
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
                        tableConfig.getOrderByClause(),
                        tableConfig.getMaxEntriesToMigrate(),
                        FETCH_BATCH_SIZE,
                        pass2BatchProcessor);
            } catch (Exception e) {
                log.error(
                        "Fatal error during Pass 2 fetch setup for table {}: {}. Aborting self-ref updates for this table.",
                        targetTableName, e.getMessage(), e);
            }
            log.info("Finished Pass 2 for {}. Total Updated FKs reported: {}. Failed Update Batch Calls: {}",
                    targetTableName, updatedFkCountPass2.get(), failedUpdateBatchCountPass2.get());
        } else {
            log.info("Skipping Pass 2 for {}: No self-referencing FK detected.", targetTableName);
        }

        // --- Pass 3: Update 'active' based on historical data ---
        if (tableConfig.isProcessHistoricalActiveness()) {
            executeHistoricalActivenessPass(tableConfig, sourceDbConfig, targetEntityClass, targetTableName,
                    idColumnName, idFieldName, columnsToFetch, updatedActiveCountPass3, failedUpdateBatchCountPass3);
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

    private void executeHistoricalActivenessPass(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig,
            Class<?> targetEntityClass, String targetTableName, String idColumnName, String idFieldName,
            Set<String> allColumnsToFetch, AtomicInteger updatedActiveCountPass3,
            AtomicInteger failedUpdateBatchCountPass3) {
        log.info("Starting Pass 3: Updating 'active' flag for table {} based on historical data", targetTableName);

        // --- Pass 3.A & 3.B: Discover and Group Historical Chains ---
        Map<Object, List<Object>> historicalGroups = new HashMap<>();
        List<Object> standaloneIds = new ArrayList<>();
        Set<String> idDiscoveryColumns = Set.of(tableConfig.getSourceIdColumnName(),
                tableConfig.getSourceHistoricalControlIdColumn());

        log.info("Pass 3.A: Discovering historical chains from source table '{}'...", tableConfig.getSourceTableName());
        try {
            Consumer<List<Map<String, Object>>> discoveryProcessor = batch -> {
                for (Map<String, Object> row : batch) {
                    Object sourceId = row.get(tableConfig.getSourceIdColumnName());
                    Object histCtlId = row.get(tableConfig.getSourceHistoricalControlIdColumn());

                    if (sourceId == null)
                        continue;

                    long histCtlIdLong = -1;
                    if (histCtlId instanceof Number) {
                        histCtlIdLong = ((Number) histCtlId).longValue();
                    } else if (histCtlId instanceof String && !((String) histCtlId).trim().isEmpty()) {
                        try {
                            histCtlIdLong = Long.parseLong(((String) histCtlId).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if (histCtlIdLong > 0) {
                        historicalGroups.computeIfAbsent(histCtlId, k -> new ArrayList<>()).add(sourceId);
                    } else {
                        standaloneIds.add(sourceId);
                    }
                }
            };

            sourceDataFetcher.fetchData(sourceDbConfig, tableConfig.getSourceTableName(), idDiscoveryColumns,
                    tableConfig.getSourceIdColumnName(), tableConfig.getOrderByClause(),
                    tableConfig.getMaxEntriesToMigrate(), ID_FETCH_BATCH_SIZE, discoveryProcessor);
            log.info("Pass 3.B: Discovered {} historical groups and {} standalone records.", historicalGroups.size(),
                    standaloneIds.size());

        } catch (Exception e) {
            log.error("Fatal error during Pass 3.A/B (Discovery) for table {}: {}. Aborting 'active' flag updates.",
                    targetTableName, e.getMessage(), e);
            return;
        }

        // --- Pass 3.C & 3.D: Batch process groups and update ---
        List<Object> allIdsToProcess = new ArrayList<>();
        historicalGroups.values().forEach(allIdsToProcess::addAll);
        allIdsToProcess.addAll(standaloneIds);

        log.info("Pass 3.C: Starting to process {} total records in batches...", allIdsToProcess.size());
        for (int i = 0; i < allIdsToProcess.size(); i += FETCH_BATCH_SIZE) {
            List<Object> idBatch = allIdsToProcess.subList(i, Math.min(i + FETCH_BATCH_SIZE, allIdsToProcess.size()));
            if (idBatch.isEmpty())
                continue;

            log.debug("Fetching full data for ID batch of size {} (starting from index {})", idBatch.size(), i);
            try {
                // Fetch full row data for the current batch of IDs
                List<Map<String, Object>> fullRowDataBatch = sourceDataFetcher.fetchFullDataForIds(sourceDbConfig,
                        tableConfig.getSourceTableName(), allColumnsToFetch, tableConfig.getSourceIdColumnName(),
                        idBatch);

                // Process this batch of full data
                int updatedInBatch = migrationRowProcessor.processHistoricalActivenessUpdateBatch(
                        fullRowDataBatch, tableConfig, targetEntityClass, targetTableName, idColumnName, idFieldName,
                        tableConfig.getSourceHistoricalControlIdColumn(), tableConfig.getSourceValidFromDateColumn(),
                        UPDATE_BATCH_SIZE);
                updatedActiveCountPass3.addAndGet(updatedInBatch);

            } catch (SQLException sqle) {
                log.error(
                        "SQLException processing 'active' flag update for an ID batch in table {}: {}. Skipping batch.",
                        targetTableName, sqle.getMessage());
                failedUpdateBatchCountPass3.incrementAndGet();
            } catch (Exception e) {
                log.error(
                        "Unexpected error processing 'active' flag update for an ID batch in table {}: {}. Skipping batch.",
                        targetTableName, e.getMessage(), e);
                failedUpdateBatchCountPass3.incrementAndGet();
            }
        }

        log.info("Finished Pass 3 for {}. Total 'active' flags updated reported: {}. Failed Update Batch Calls: {}",
                targetTableName, updatedActiveCountPass3.get(), failedUpdateBatchCountPass3.get());
    }

    // ... (logMetadata and determineColumnsToFetch remain the same) ...
    private void logMetadata(String targetTableName, String idFieldName, String idColumnName, boolean isGeneratedId,
            boolean isSelfReferencing, Map<String, ForeignKeyInfo> foreignKeyInfoMap) {
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

    private Set<String> determineColumnsToFetch(TableMigrationConfig tableConfig,
            Map<String, ForeignKeyInfo> foreignKeyInfoMap, ForeignKeyInfo selfReferenceFkInfo) {
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

        if (selfReferenceFkInfo != null && selfReferenceFkInfo.getForeignKeyField() != null
                && tableConfig.getColumnMapping() != null) {
            tableConfig.getColumnMapping().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(selfReferenceFkInfo.getForeignKeyField().getName()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(columnsToFetch::add);
        }

        if (tableConfig.getSourceIdColumnName() != null && !tableConfig.getSourceIdColumnName().trim().isEmpty()) {
            columnsToFetch.add(tableConfig.getSourceIdColumnName());
        } else {
            log.warn(
                    "Source ID column name is missing or empty in configuration for source table {}. This might cause issues if paging is attempted.",
                    tableConfig.getSourceTableName());
        }
        return columnsToFetch;
    }
}