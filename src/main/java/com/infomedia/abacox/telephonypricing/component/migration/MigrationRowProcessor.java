// File: com/infomedia/abacox/telephonypricing/component/migration/MigrationRowProcessor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Log4j2
public class MigrationRowProcessor {

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Helper to determine if a custom transformer should be used.
     * Uses reflection on targetEntityClass to find the field type for default
     * conversion.
     */
    private Object convertValueByFieldLookup(Object sourceValue,
            String targetFieldName,
            Class<?> targetEntityClass,
            TableMigrationConfig config) throws Exception {

        // 0. Check for Specific Value Replacements
        if (config.getSpecificValueReplacements() != null &&
                config.getSpecificValueReplacements().containsKey(targetFieldName)) {
            Map<Object, Object> replacements = config.getSpecificValueReplacements().get(targetFieldName);
            // Try direct match first (handling type differences if possible)
            if (replacements.containsKey(sourceValue)) {
                return replacements.get(sourceValue);
            }
            // Fallback: Try string comparison if types don't match (e.g. Integer vs Long)
            for (Map.Entry<Object, Object> entry : replacements.entrySet()) {
                if (String.valueOf(entry.getKey()).equals(String.valueOf(sourceValue))) {
                    return entry.getValue();
                }
            }
        }

        // 1. Check for Custom Transformer
        if (config.getCustomValueTransformers() != null &&
                config.getCustomValueTransformers().containsKey(targetFieldName)) {
            try {
                return config.getCustomValueTransformers().get(targetFieldName).apply(sourceValue);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Custom value transformation failed for field '" + targetFieldName + "': " + e.getMessage(), e);
            }
        }

        // 2. Default Fallback (Look up field type inside MigrationUtils)
        return MigrationUtils.convertToFieldType(sourceValue, targetEntityClass, targetFieldName);
    }

    /**
     * Helper to determine if a custom transformer should be used.
     * Uses the provided explicit Class type for default conversion.
     */
    private Object convertValueByType(Object sourceValue,
            String targetFieldName,
            Class<?> targetType,
            TableMigrationConfig config) throws Exception {

        // 0. Check for Specific Value Replacements
        if (config.getSpecificValueReplacements() != null &&
                config.getSpecificValueReplacements().containsKey(targetFieldName)) {
            Map<Object, Object> replacements = config.getSpecificValueReplacements().get(targetFieldName);
            if (replacements.containsKey(sourceValue)) {
                return replacements.get(sourceValue);
            }
            // Fallback: Try string comparison
            for (Map.Entry<Object, Object> entry : replacements.entrySet()) {
                if (String.valueOf(entry.getKey()).equals(String.valueOf(sourceValue))) {
                    return entry.getValue();
                }
            }
        }

        // 1. Check for Custom Transformer
        if (config.getCustomValueTransformers() != null &&
                config.getCustomValueTransformers().containsKey(targetFieldName)) {
            try {
                return config.getCustomValueTransformers().get(targetFieldName).apply(sourceValue);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Custom value transformation failed for field '" + targetFieldName + "': " + e.getMessage(), e);
            }
        }

        // 2. Default Fallback (Use explicit type)
        return MigrationUtils.convertToFieldType(sourceValue, targetType, null);
    }

    /**
     * Represents a prepared row for batch SQL execution.
     */
    private static class PreparedRow {
        final Object targetIdValue;
        final Object targetEntity;

        PreparedRow(Object targetIdValue, Object targetEntity) {
            this.targetIdValue = targetIdValue;
            this.targetEntity = targetEntity;
        }
    }

    /**
     * Metadata about the columns/fields for batch SQL execution.
     * Computed once per batch and reused for all rows.
     */
    private static class BatchSqlMetadata {
        final String insertSql;
        final String updateSql;
        final List<Field> orderedFields; // Fields in column order (excluding ID for update)
        final List<String> orderedColumnNames; // Column names matching orderedFields
        final String idColumnName;
        final String selfRefForeignKeyColumnNameToNull;

        BatchSqlMetadata(String insertSql, String updateSql, List<Field> orderedFields,
                List<String> orderedColumnNames, String idColumnName,
                String selfRefForeignKeyColumnNameToNull) {
            this.insertSql = insertSql;
            this.updateSql = updateSql;
            this.orderedFields = orderedFields;
            this.orderedColumnNames = orderedColumnNames;
            this.idColumnName = idColumnName;
            this.selfRefForeignKeyColumnNameToNull = selfRefForeignKeyColumnNameToNull;
        }
    }

    /**
     * Builds the SQL templates and field ordering metadata for batch INSERT and
     * UPDATE.
     * This is computed once and reused for all rows in a batch.
     */
    private BatchSqlMetadata buildBatchSqlMetadata(Class<?> targetEntityClass, Field idField, String tableName,
            String selfRefForeignKeyColumnNameToNull) {
        List<Field> allFields = MigrationUtils.getAllFields(targetEntityClass);
        String idColumnName = MigrationUtils.getIdColumnName(idField);

        List<Field> orderedFields = new ArrayList<>();
        List<String> orderedColumnNames = new ArrayList<>();
        Set<String> processedColumnNames = new HashSet<>();

        StringBuilder insertColumns = new StringBuilder();
        StringBuilder insertPlaceholders = new StringBuilder();
        StringBuilder updateSetClause = new StringBuilder();

        for (Field field : allFields) {
            field.setAccessible(true);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                    field.isAnnotationPresent(jakarta.persistence.Transient.class) ||
                    field.isAnnotationPresent(OneToMany.class) ||
                    field.isAnnotationPresent(ManyToMany.class)) {
                continue;
            }

            String columnName = MigrationUtils.getColumnNameForField(field);
            String columnNameKey = columnName.toLowerCase();

            if (processedColumnNames.contains(columnNameKey))
                continue;

            orderedFields.add(field);
            orderedColumnNames.add(columnName);
            processedColumnNames.add(columnNameKey);

            // INSERT includes all columns (including ID)
            if (insertColumns.length() > 0) {
                insertColumns.append(", ");
                insertPlaceholders.append(", ");
            }
            insertColumns.append("\"").append(columnName).append("\"");
            insertPlaceholders.append("?");

            // UPDATE excludes the ID column from SET
            if (!columnName.equalsIgnoreCase(idColumnName)) {
                if (updateSetClause.length() > 0) {
                    updateSetClause.append(", ");
                }
                updateSetClause.append("\"").append(columnName).append("\" = ?");
            }
        }

        String insertSql = "INSERT INTO \"" + tableName + "\" (" + insertColumns + ") VALUES (" + insertPlaceholders
                + ")";
        String updateSql = "UPDATE \"" + tableName + "\" SET " + updateSetClause + " WHERE \"" + idColumnName
                + "\" = ?";

        return new BatchSqlMetadata(insertSql, updateSql, orderedFields, orderedColumnNames,
                idColumnName, selfRefForeignKeyColumnNameToNull);
    }

    /**
     * Extracts the parameter values from an entity for a batch SQL operation.
     * For INSERT: all fields in order.
     * For UPDATE: all fields except ID, then ID at the end (for WHERE clause).
     */
    private List<Object> extractValuesForBatch(Object entity, BatchSqlMetadata metadata, Field idField,
            Object idValue, boolean isUpdate) throws Exception {
        List<Object> values = new ArrayList<>();

        for (int i = 0; i < metadata.orderedFields.size(); i++) {
            Field field = metadata.orderedFields.get(i);
            String columnName = metadata.orderedColumnNames.get(i);

            // For UPDATE, skip the ID column (it goes in the WHERE clause)
            if (isUpdate && columnName.equalsIgnoreCase(metadata.idColumnName)) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(entity);

            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
            OneToOne oneToOne = field.getAnnotation(OneToOne.class);

            if (columnName.equalsIgnoreCase(metadata.selfRefForeignKeyColumnNameToNull)) {
                value = null;
            } else if (value != null && (manyToOne != null || oneToOne != null) && joinColumn != null) {
                Field relatedIdField = MigrationUtils.findIdField(value.getClass());
                if (relatedIdField != null) {
                    relatedIdField.setAccessible(true);
                    value = relatedIdField.get(value);
                } else {
                    value = null;
                }
            }

            values.add(value);
        }

        // For UPDATE, append the ID value for the WHERE clause
        if (isUpdate) {
            values.add(idValue);
        }

        return values;
    }

    /**
     * Processes a batch of source rows, performing batch INSERT and UPDATE
     * operations.
     * Returns the number of rows that FAILED processing.
     * <p>
     * This method runs in a single transaction. If the batch fails (e.g., due to a
     * constraint
     * violation on one row), the transaction rolls back and the caller should fall
     * back to
     * row-by-row processing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int processBatchInsert(List<Map<String, Object>> batchRows,
            TableMigrationConfig tableConfig,
            Class<?> targetEntityClass,
            Field idField,
            String idFieldName,
            String idColumnName,
            String tableName,
            boolean isGeneratedId,
            Map<String, ForeignKeyInfo> foreignKeyInfoMap,
            ForeignKeyInfo selfReferenceFkInfo) throws Exception {

        if (batchRows == null || batchRows.isEmpty())
            return 0;

        String selfRefColToNull = (selfReferenceFkInfo != null) ? selfReferenceFkInfo.getDbColumnName() : null;

        // 1. Build entity objects and collect target IDs
        List<PreparedRow> preparedRows = new ArrayList<>(batchRows.size());
        List<Object> allTargetIds = new ArrayList<>(batchRows.size());
        int skipCount = 0;

        for (Map<String, Object> sourceRow : batchRows) {
            Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());
            if (sourceIdValue == null) {
                log.warn("Skipping row in table {} due to null source ID.", tableName);
                skipCount++;
                continue;
            }

            Object targetIdValue;
            try {
                targetIdValue = convertValueByFieldLookup(sourceIdValue, idFieldName, targetEntityClass, tableConfig);
            } catch (Exception e) {
                log.warn("Skipping row in table {} due to ID conversion error: {}", tableName, e.getMessage());
                skipCount++;
                continue;
            }

            // Build entity
            Object targetEntity = targetEntityClass.getDeclaredConstructor().newInstance();
            idField.setAccessible(true);
            idField.set(targetEntity, targetIdValue);

            for (Map.Entry<String, String> entry : tableConfig.getColumnMapping().entrySet()) {
                String sourceCol = entry.getKey();
                String targetField = entry.getValue();

                if (targetField.equals(idFieldName))
                    continue;

                ForeignKeyInfo fkInfo = foreignKeyInfoMap.get(targetField);
                if (fkInfo != null && fkInfo.isSelfReference())
                    continue;

                if (sourceRow.containsKey(sourceCol)) {
                    Object sourceValue = sourceRow.get(sourceCol);
                    Object convertedTargetValue = null;

                    boolean treatAsNull = false;
                    if (fkInfo != null
                            && tableConfig.isTreatZeroIdAsNullForForeignKeys()
                            && sourceValue instanceof Number
                            && ((Number) sourceValue).longValue() == 0L) {
                        treatAsNull = true;
                    }

                    if (!treatAsNull && sourceValue != null) {
                        try {
                            convertedTargetValue = convertValueByFieldLookup(sourceValue, targetField,
                                    targetEntityClass, tableConfig);
                        } catch (Exception e) {
                            log.warn("Skipping field '{}' for row with ID {} due to conversion error: {}",
                                    targetField, targetIdValue, e.getMessage());
                            continue;
                        }
                    }

                    try {
                        MigrationUtils.setProperty(targetEntity, targetField, convertedTargetValue);
                    } catch (Exception e) {
                        log.warn("Skipping field '{}' for row with ID {} due to setting error: {}",
                                targetField, targetIdValue, e.getMessage());
                    }
                }
            }

            preparedRows.add(new PreparedRow(targetIdValue, targetEntity));
            allTargetIds.add(targetIdValue);
        }

        if (preparedRows.isEmpty())
            return skipCount;

        // 2. Batch existence check & build SQL metadata
        Session session = entityManager.unwrap(Session.class);
        BatchSqlMetadata metadata = buildBatchSqlMetadata(targetEntityClass, idField, tableName, selfRefColToNull);

        session.doWork(connection -> {
            // Batch check which IDs already exist
            Set<Object> existingIds = MigrationUtils.checkExistingIds(connection, tableName, idColumnName,
                    allTargetIds);
            log.debug("Batch existence check for table {}: {} of {} IDs already exist",
                    tableName, existingIds.size(), allTargetIds.size());

            // Build a string-based set for flexible comparison (DB might return Long while
            // we have Integer, etc.)
            Set<String> existingIdStrings = new HashSet<>();
            for (Object eid : existingIds) {
                if (eid != null)
                    existingIdStrings.add(eid.toString());
            }

            // Separate into inserts and updates
            List<PreparedRow> inserts = new ArrayList<>();
            List<PreparedRow> updates = new ArrayList<>();
            for (PreparedRow row : preparedRows) {
                if (row.targetIdValue != null && existingIdStrings.contains(row.targetIdValue.toString())) {
                    updates.add(row);
                } else {
                    inserts.add(row);
                }
            }

            // 3. Execute batch INSERTs
            if (!inserts.isEmpty()) {
                log.debug("Executing batch INSERT for {} rows in table {}", inserts.size(), tableName);
                try (PreparedStatement insertStmt = connection.prepareStatement(metadata.insertSql)) {
                    for (PreparedRow row : inserts) {
                        List<Object> values = extractValuesForBatch(row.targetEntity, metadata, idField,
                                row.targetIdValue, false);
                        MigrationUtils.setPreparedStatementParameters(insertStmt, values);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                    log.debug("Batch INSERT completed for {} rows in table {}", inserts.size(), tableName);
                } catch (Exception e) {
                    throw new SQLException("Batch INSERT failed for table " + tableName + ": " + e.getMessage(), e);
                }
            }

            // 4. Execute batch UPDATEs
            if (!updates.isEmpty()) {
                log.debug("Executing batch UPDATE for {} rows in table {}", updates.size(), tableName);
                try (PreparedStatement updateStmt = connection.prepareStatement(metadata.updateSql)) {
                    for (PreparedRow row : updates) {
                        List<Object> values = extractValuesForBatch(row.targetEntity, metadata, idField,
                                row.targetIdValue, true);
                        MigrationUtils.setPreparedStatementParameters(updateStmt, values);
                        updateStmt.addBatch();
                    }
                    updateStmt.executeBatch();
                    log.debug("Batch UPDATE completed for {} rows in table {}", updates.size(), tableName);
                } catch (Exception e) {
                    throw new SQLException("Batch UPDATE failed for table " + tableName + ": " + e.getMessage(), e);
                }
            }
        });

        log.trace("Batch processed {} rows ({} inserts, {} updates) for table {}",
                preparedRows.size(), preparedRows.size(), 0, tableName);
        return skipCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean processSingleRowInsert(Map<String, Object> sourceRow,
            TableMigrationConfig tableConfig,
            Class<?> targetEntityClass,
            Field idField,
            String idFieldName,
            String idColumnName,
            String tableName,
            boolean isGeneratedId,
            Map<String, ForeignKeyInfo> foreignKeyInfoMap,
            ForeignKeyInfo selfReferenceFkInfo) {

        Object targetIdValue = null;
        Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());

        try {
            if (sourceIdValue == null) {
                log.warn("Skipping row in table {} due to null source ID (Source Row: {}).", tableName, sourceRow);
                return true; // Treat as skipped successfully
            }

            // Convert ID using override logic (field lookup)
            targetIdValue = convertValueByFieldLookup(sourceIdValue, idFieldName, targetEntityClass, tableConfig);

            boolean exists = checkEntityExistsInternal(tableName, idColumnName, targetIdValue);
            if (exists) {
                log.trace("Updating existing row in table {} with ID: {}", tableName, targetIdValue);
                // Fall through to update logic
            }

            Object targetEntity = targetEntityClass.getDeclaredConstructor().newInstance();
            idField.setAccessible(true);
            idField.set(targetEntity, targetIdValue);

            for (Map.Entry<String, String> entry : tableConfig.getColumnMapping().entrySet()) {
                String sourceCol = entry.getKey();
                String targetField = entry.getValue();

                if (targetField.equals(idFieldName))
                    continue;

                ForeignKeyInfo fkInfo = foreignKeyInfoMap.get(targetField);

                if (fkInfo != null && fkInfo.isSelfReference()) {
                    log.trace("Skipping self-ref FK field '{}' population in Pass 1 for ID {}", targetField,
                            targetIdValue);
                    continue;
                }

                if (sourceRow.containsKey(sourceCol)) {
                    Object sourceValue = sourceRow.get(sourceCol);
                    Object convertedTargetValue = null;

                    boolean treatAsNull = false;
                    if (fkInfo != null
                            && tableConfig.isTreatZeroIdAsNullForForeignKeys()
                            && sourceValue instanceof Number
                            && ((Number) sourceValue).longValue() == 0L) {
                        log.trace("Treating source value 0 as NULL for FK field '{}' (Source Col: {}) for ID {}",
                                targetField, sourceCol, targetIdValue);
                        treatAsNull = true;
                    }

                    if (!treatAsNull && sourceValue != null) {
                        try {
                            // Convert Field Value using override logic (field lookup)
                            convertedTargetValue = convertValueByFieldLookup(sourceValue, targetField,
                                    targetEntityClass, tableConfig);
                        } catch (Exception e) {
                            log.warn(
                                    "Skipping field '{}' for row with ID {} due to conversion error: {}. Source Col: {}, Source type: {}, Value: '{}'",
                                    targetField, targetIdValue, e.getMessage(), sourceCol,
                                    (sourceValue != null ? sourceValue.getClass().getName() : "null"), sourceValue);
                            continue;
                        }
                    }

                    try {
                        MigrationUtils.setProperty(targetEntity, targetField, convertedTargetValue);
                    } catch (Exception e) {
                        log.warn(
                                "Skipping field '{}' for row with ID {} due to setting error: {}. Target Value: {}, Target Type: {}",
                                targetField, targetIdValue, e.getMessage(), convertedTargetValue,
                                (convertedTargetValue != null ? convertedTargetValue.getClass().getName() : "null"));
                    }
                }
            }

            saveEntityWithForcedIdInternal(
                    targetEntity,
                    idField,
                    targetIdValue,
                    isGeneratedId,
                    exists, // isUpdate
                    tableName,
                    (selfReferenceFkInfo != null) ? selfReferenceFkInfo.getDbColumnName() : null);

            log.trace("Successfully inserted row in table {} with ID: {}", tableName, targetIdValue);
            return true;

        } catch (Exception e) {
            log.debug("Error processing row for table {} (Source ID: {}, Target ID: {}): {}",
                    tableName, sourceIdValue,
                    targetIdValue != null ? targetIdValue : "UNKNOWN",
                    e.getMessage(), e);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = SQLException.class)
    public int processSelfRefUpdateBatch(List<Map<String, Object>> batchData,
            TableMigrationConfig tableConfig,
            Class<?> targetEntityClass,
            String tableName,
            String idColumnName,
            String idFieldName,
            ForeignKeyInfo selfReferenceFkInfo,
            int updateBatchSize) throws SQLException {
        if (selfReferenceFkInfo == null || batchData == null || batchData.isEmpty()) {
            return 0;
        }

        String selfRefDbColumn = selfReferenceFkInfo.getDbColumnName();
        Field selfRefFkField = selfReferenceFkInfo.getForeignKeyField();
        Class<?> selfRefFkType = selfReferenceFkInfo.getTargetTypeId();

        String updateSql = "UPDATE \"" + tableName + "\" SET \"" + selfRefDbColumn + "\" = ? WHERE \"" + idColumnName
                + "\" = ?";
        log.debug("Executing Self-Ref Update Batch (max size: {}) using SQL: {}", batchData.size(), updateSql);

        Session session = entityManager.unwrap(Session.class);
        final int[] totalUpdatedInBatch = { 0 };

        try {
            session.doWork(connection -> {
                try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                    int currentBatchCount = 0;
                    for (Map<String, Object> sourceRow : batchData) {
                        Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());
                        String sourceParentCol = tableConfig.getColumnMapping().entrySet().stream()
                                .filter(e -> e.getValue().equals(selfRefFkField.getName()))
                                .map(Map.Entry::getKey)
                                .findFirst().orElse(null);

                        if (sourceIdValue == null || sourceParentCol == null
                                || !sourceRow.containsKey(sourceParentCol)) {
                            continue;
                        }

                        Object sourceParentIdValue = sourceRow.get(sourceParentCol);
                        Object targetId = null;
                        Object targetParentId = null;

                        try {
                            // Ensure ID conversion logic matches Insert Pass
                            targetId = convertValueByFieldLookup(sourceIdValue, idFieldName, targetEntityClass,
                                    tableConfig);

                            boolean treatParentAsNull = false;
                            if (tableConfig.isTreatZeroIdAsNullForForeignKeys()
                                    && sourceParentIdValue instanceof Number
                                    && ((Number) sourceParentIdValue).longValue() == 0L) {
                                treatParentAsNull = true;
                            }

                            if (!treatParentAsNull && sourceParentIdValue != null) {
                                // Apply conversion override for the Parent FK field using explicit type
                                targetParentId = convertValueByType(sourceParentIdValue, selfRefFkField.getName(),
                                        selfRefFkType, tableConfig);
                            }

                            if (targetParentId != null) {
                                MigrationUtils.setPreparedStatementParameters(updateStmt,
                                        List.of(targetParentId, targetId));
                                updateStmt.addBatch();
                                currentBatchCount++;

                                if (currentBatchCount % updateBatchSize == 0) {
                                    log.trace("Executing intermediate self-ref update batch ({} statements)",
                                            currentBatchCount);
                                    int[] batchResult = updateStmt.executeBatch();
                                    totalUpdatedInBatch[0] += Arrays.stream(batchResult)
                                            .filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                                    updateStmt.clearBatch();
                                    currentBatchCount = 0;
                                }
                            }
                        } catch (Exception e) {
                            log.error(
                                    "Error preparing self-ref update for table {} (Target ID: {}, Source Parent Col: {}, Source Parent Val: {}): {}",
                                    tableName, targetId != null ? targetId : sourceIdValue, sourceParentCol,
                                    sourceParentIdValue, e.getMessage(), e);
                        }
                    }

                    if (currentBatchCount > 0) {
                        log.trace("Executing final self-ref update batch ({} statements)", currentBatchCount);
                        int[] batchResult = updateStmt.executeBatch();
                        totalUpdatedInBatch[0] += Arrays.stream(batchResult)
                                .filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                    }
                    log.debug(
                            "Self-ref update batch executed for table {}. Statements processed reported by driver: {}",
                            tableName, totalUpdatedInBatch[0]);

                } catch (SQLException e) {
                    log.error(
                            "SQLException during batch update execution for table {}: SQLState: {}, ErrorCode: {}, Message: {}",
                            tableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    throw new SQLException("Batch update failed for table " + tableName + ": " + e.getMessage(),
                            e.getSQLState(), e.getErrorCode(), e);
                } catch (Exception e) {
                    log.error("Unexpected error during batch update work for table {}: {}", tableName, e.getMessage(),
                            e);
                    throw new RuntimeException("Unexpected error during batch update: " + e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                log.error("Runtime exception during self-ref update processing for table {}: {}", tableName,
                        e.getMessage(), e);
                throw e;
            }
        }
        return totalUpdatedInBatch[0];
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = SQLException.class)
    public int processHistoricalActivenessUpdateBatch(List<Map<String, Object>> batchData,
            TableMigrationConfig tableConfig,
            Class<?> targetEntityClass,
            String targetTableName,
            String targetIdColumnName,
            String targetIdFieldName,
            String sourceHistoricalControlIdColumn,
            String sourceValidFromDateColumn,
            int updateBatchSize) throws SQLException {
        if (batchData == null || batchData.isEmpty()) {
            return 0;
        }
        log.debug("Processing historical activeness for batch of size {} for table {}", batchData.size(),
                targetTableName);

        // Group all rows in the current batch by their historical control ID.
        Map<Object, List<Map<String, Object>>> groupedByHistoricalControlId = batchData.stream()
                .filter(row -> row.get(sourceHistoricalControlIdColumn) != null)
                .collect(Collectors.groupingBy(row -> row.get(sourceHistoricalControlIdColumn)));

        List<HistoricalRecordUpdate> updatesToPerform = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // Process the historical chains
        for (Map.Entry<Object, List<Map<String, Object>>> entry : groupedByHistoricalControlId.entrySet()) {
            Object histCtlId = entry.getKey();
            List<Map<String, Object>> historicalChain = entry.getValue();

            // Correctly sort by date DESCENDING to find the most recent record first
            historicalChain.sort(Comparator.comparing((Map<String, Object> row) -> {
                Object dateVal = row.get(sourceValidFromDateColumn);
                try {
                    // We don't support custom mapping for validFrom date logic here currently
                    // (unless passed via map),
                    // relying on standard conversion for sorting
                    LocalDateTime ldt = (LocalDateTime) MigrationUtils.convertToFieldType(dateVal, LocalDateTime.class,
                            null);
                    return ldt != null ? ldt : LocalDateTime.MIN;
                } catch (Exception e) {
                    log.warn(
                            "Unparseable date for sorting historical chain: {} for histCtlId {}. Error: {}. Using MIN_DATE.",
                            dateVal, histCtlId, e.getMessage());
                    return LocalDateTime.MIN;
                }
            }).reversed());

            // The first record in the sorted list is the most recent one
            Map<String, Object> mostRecentRecord = historicalChain.get(0);
            LocalDateTime validFrom = null;
            try {
                Object validFromRaw = mostRecentRecord.get(sourceValidFromDateColumn);
                validFrom = (LocalDateTime) MigrationUtils.convertToFieldType(validFromRaw, LocalDateTime.class, null);
            } catch (Exception e) {
                log.error("Could not convert validFrom date for most recent record of histCtlId {}: {}", histCtlId,
                        e.getMessage());
            }

            // Determine if the conceptual employee is active
            boolean isEmployeeActive = (validFrom != null && !validFrom.isAfter(now));
            log.trace("Determined active status for histCtlId {}: {}. (Most recent date: {})", histCtlId,
                    isEmployeeActive, validFrom);

            // Create an update instruction for every record in this chain
            for (Map<String, Object> recordInChain : historicalChain) {
                Object sourceId = recordInChain.get(tableConfig.getSourceIdColumnName());
                if (sourceId == null)
                    continue;
                try {
                    // Ensure ID conversion uses overrides
                    Object targetId = convertValueByFieldLookup(sourceId, targetIdFieldName, targetEntityClass,
                            tableConfig);
                    updatesToPerform.add(new HistoricalRecordUpdate(targetId, isEmployeeActive));
                } catch (Exception e) {
                    log.error("Could not convert source ID {} for historical update.", sourceId, e);
                }
            }
        }

        // Process standalone records (those not in a historical group)
        batchData.stream()
                .filter(row -> {
                    Object histCtlIdRaw = row.get(sourceHistoricalControlIdColumn);
                    if (histCtlIdRaw == null)
                        return true;
                    if (histCtlIdRaw instanceof Number)
                        return ((Number) histCtlIdRaw).longValue() <= 0;
                    if (histCtlIdRaw instanceof String) {
                        try {
                            return Long.parseLong(((String) histCtlIdRaw).trim()) <= 0;
                        } catch (Exception e) {
                            return true;
                        }
                    }
                    return true;
                })
                .forEach(sourceRow -> {
                    Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName());
                    if (sourceId == null)
                        return;
                    try {
                        // Ensure ID conversion uses overrides
                        Object targetId = convertValueByFieldLookup(sourceId, targetIdFieldName, targetEntityClass,
                                tableConfig);
                        Object validFromRaw = sourceRow.get(sourceValidFromDateColumn);
                        LocalDateTime validFrom = (LocalDateTime) MigrationUtils.convertToFieldType(validFromRaw,
                                LocalDateTime.class, null);
                        boolean isActive = (validFrom != null && !validFrom.isAfter(now));
                        updatesToPerform.add(new HistoricalRecordUpdate(targetId, isActive));
                    } catch (Exception e) {
                        log.error("Error processing standalone record for active status (Source ID: {}): {}", sourceId,
                                e.getMessage());
                    }
                });

        // Now, execute the batch update
        String updateActiveSql = "UPDATE \"" + targetTableName + "\" SET active = ? WHERE \"" + targetIdColumnName
                + "\" = ?";
        log.debug("Executing 'active' flag Update Batch ({} potential updates) using SQL: {}", updatesToPerform.size(),
                updateActiveSql);

        Session session = entityManager.unwrap(Session.class);
        final int[] totalUpdatedInThisBatchCall = { 0 };

        try {
            session.doWork(connection -> {
                try (PreparedStatement updateStmt = connection.prepareStatement(updateActiveSql)) {
                    int currentStatementsInJdbcBatch = 0;
                    for (HistoricalRecordUpdate update : updatesToPerform) {
                        if (update.targetId == null) {
                            log.warn("Skipping historical update due to null targetId.");
                            continue;
                        }
                        log.trace("Adding 'active' update batch: SET active = {} WHERE {} = {}", update.isActive,
                                targetIdColumnName, update.targetId);
                        MigrationUtils.setPreparedStatementParameters(updateStmt,
                                List.of(update.isActive, update.targetId));
                        updateStmt.addBatch();
                        currentStatementsInJdbcBatch++;

                        if (currentStatementsInJdbcBatch >= updateBatchSize) {
                            log.trace("Executing intermediate 'active' update batch ({} statements)",
                                    currentStatementsInJdbcBatch);
                            int[] batchResult = updateStmt.executeBatch();
                            totalUpdatedInThisBatchCall[0] += Arrays.stream(batchResult)
                                    .filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                            updateStmt.clearBatch();
                            currentStatementsInJdbcBatch = 0;
                        }
                    }
                    // Execute any remaining statements in the batch
                    if (currentStatementsInJdbcBatch > 0) {
                        log.trace("Executing final 'active' update batch ({} statements)",
                                currentStatementsInJdbcBatch);
                        int[] batchResult = updateStmt.executeBatch();
                        totalUpdatedInThisBatchCall[0] += Arrays.stream(batchResult)
                                .filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                    }
                    log.debug(
                            "'active' flag update batch executed for table {}. Statements processed reported by driver: {}",
                            targetTableName, totalUpdatedInThisBatchCall[0]);
                } catch (SQLException e) {
                    log.error(
                            "SQLException during 'active' flag batch update execution for table {}: SQLState: {}, ErrorCode: {}, Message: {}",
                            targetTableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    throw new SQLException(
                            "Batch 'active' update failed for table " + targetTableName + ": " + e.getMessage(),
                            e.getSQLState(), e.getErrorCode(), e);
                } catch (Exception e) {
                    log.error("Unexpected error during 'active' flag batch update work for table {}: {}",
                            targetTableName, e.getMessage(), e);
                    throw new RuntimeException("Unexpected error during 'active' flag batch update: " + e.getMessage(),
                            e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                log.error("Runtime exception during 'active' flag update processing for table {}: {}", targetTableName,
                        e.getMessage(), e);
                throw e;
            }
        }
        return totalUpdatedInThisBatchCall[0];
    }

    // Helper DTO for clarity
    private static class HistoricalRecordUpdate {
        final Object targetId;
        final boolean isActive;

        HistoricalRecordUpdate(Object targetId, boolean isActive) {
            this.targetId = targetId;
            this.isActive = isActive;
        }
    }

    private boolean checkEntityExistsInternal(String tableName, String idColumnName, Object idValue)
            throws SQLException {
        if (idValue == null)
            return false;
        String sql = "SELECT 1 FROM \"" + tableName + "\" WHERE \"" + idColumnName + "\" = ? LIMIT 1";
        Session session = entityManager.unwrap(Session.class);
        final boolean[] exists = { false };
        try {
            session.doWork(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    MigrationUtils.setPreparedStatementParameters(stmt, List.of(idValue));
                    try (ResultSet rs = stmt.executeQuery()) {
                        exists[0] = rs.next();
                    }
                } catch (SQLException e) {
                    throw new SQLException("Existence check failed for table " + tableName + ": " + e.getMessage(),
                            e.getSQLState(), e.getErrorCode(), e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException)
                throw (SQLException) e.getCause();
            else
                throw e;
        }
        return exists[0];
    }

    private <T> void saveEntityWithForcedIdInternal(T entity, Field idField, Object idValue, boolean isGeneratedId,
            boolean isUpdate,
            String tableName, String selfRefForeignKeyColumnNameToNull) throws Exception {
        Class<?> entityClass = entity.getClass();
        if (idValue == null)
            throw new IllegalArgumentException("ID value cannot be null for saving entity");

        if (isGeneratedId) {
            log.trace("Entity {} has @GeneratedValue, using native SQL {} for ID: {}", entityClass.getSimpleName(),
                    isUpdate ? "UPDATE" : "INSERT", idValue);
            StringBuilder columns = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            StringBuilder updateSetClause = new StringBuilder();
            List<Object> values = new ArrayList<>();
            List<Field> allFields = MigrationUtils.getAllFields(entityClass);
            Set<String> processedColumnNames = new HashSet<>();
            String idColumnName = MigrationUtils.getIdColumnName(idField);

            for (Field field : allFields) {
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                        java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                        field.isAnnotationPresent(jakarta.persistence.Transient.class) ||
                        field.isAnnotationPresent(OneToMany.class) ||
                        field.isAnnotationPresent(ManyToMany.class)) {
                    continue;
                }

                String columnName = MigrationUtils.getColumnNameForField(field);
                String columnNameKey = columnName.toLowerCase();

                if (processedColumnNames.contains(columnNameKey))
                    continue;

                // Skip ID column in the set list for UPDATE, or VALUES list for INSERT (if
                // purely identity, but here we forced insert ID so we included it, but for
                // update we filter it)
                if (columnName.equalsIgnoreCase(idColumnName) && isUpdate) {
                    continue;
                }

                Object value = field.get(entity);
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);

                if (columnName.equalsIgnoreCase(selfRefForeignKeyColumnNameToNull)) {
                    value = null;
                } else if (value != null && (manyToOne != null || oneToOne != null) && joinColumn != null) {
                    Field relatedIdField = MigrationUtils.findIdField(value.getClass());
                    if (relatedIdField != null) {
                        relatedIdField.setAccessible(true);
                        value = relatedIdField.get(value);
                    } else {
                        value = null;
                    }
                }

                if (isUpdate) {
                    if (updateSetClause.length() > 0) {
                        updateSetClause.append(", ");
                    }
                    updateSetClause.append("\"").append(columnName).append("\" = ?");
                } else {
                    if (columns.length() > 0) {
                        columns.append(", ");
                        placeholders.append(", ");
                    }
                    columns.append("\"").append(columnName).append("\"");
                    placeholders.append("?");
                }

                values.add(value);
                processedColumnNames.add(columnNameKey);
            }

            if (!isUpdate && columns.length() == 0)
                return;
            if (isUpdate && updateSetClause.length() == 0)
                return;

            String sql;
            if (isUpdate) {
                sql = "UPDATE \"" + tableName + "\" SET " + updateSetClause + " WHERE \"" + idColumnName + "\" = ?";
                values.add(idValue);
            } else {
                sql = "INSERT INTO \"" + tableName + "\" (" + columns + ") VALUES (" + placeholders + ")";
            }

            log.trace("Executing native SQL: {}", sql);
            log.trace("With values: {}", values);

            Session session = entityManager.unwrap(Session.class);
            try {
                session.doWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        MigrationUtils.setPreparedStatementParameters(stmt, values);
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        throw new SQLException(
                                "Native SQL " + (isUpdate ? "update" : "insert") + " failed: " + e.getMessage(),
                                e.getSQLState(), e.getErrorCode(), e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof SQLException)
                    throw (SQLException) e.getCause();
                else
                    throw e;
            }
        } else {
            log.trace("Entity {} does not have @GeneratedValue, using entityManager.merge() for ID: {}",
                    entityClass.getSimpleName(), idValue);
            if (selfRefForeignKeyColumnNameToNull != null) {
                Field fkField = MigrationUtils.findFieldByColumnName(entityClass, selfRefForeignKeyColumnNameToNull);
                if (fkField != null) {
                    fkField.setAccessible(true);
                    if (!fkField.getType().isPrimitive()) {
                        if (fkField.get(entity) != null) {
                            fkField.set(entity, null);
                        }
                    }
                }
            }
            entityManager.merge(entity);
            log.trace("Merge operation completed for ID {}", idValue);
        }
    }
}