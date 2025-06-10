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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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

    // ... (processSingleRowInsert and processSelfRefUpdateBatch are unchanged) ...
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

            targetIdValue = MigrationUtils.convertToFieldType(sourceIdValue, targetEntityClass, idFieldName);

            boolean exists = checkEntityExistsInternal(tableName, idColumnName, targetIdValue);
            if (exists) {
                log.trace("Skipping existing row in table {} with ID: {}", tableName, targetIdValue);
                return true; // Skipped existing successfully
            }

            Object targetEntity = targetEntityClass.getDeclaredConstructor().newInstance();
            idField.setAccessible(true);
            idField.set(targetEntity, targetIdValue);

            for (Map.Entry<String, String> entry : tableConfig.getColumnMapping().entrySet()) {
                String sourceCol = entry.getKey();
                String targetField = entry.getValue();

                if (targetField.equals(idFieldName)) continue;

                ForeignKeyInfo fkInfo = foreignKeyInfoMap.get(targetField);

                if (fkInfo != null && fkInfo.isSelfReference()) {
                    log.trace("Skipping self-ref FK field '{}' population in Pass 1 for ID {}", targetField, targetIdValue);
                    continue;
                }

                if (sourceRow.containsKey(sourceCol)) {
                    Object sourceValue = sourceRow.get(sourceCol);
                    Object convertedTargetValue = null;

                    boolean treatAsNull = false;
                    if (fkInfo != null
                            && tableConfig.isTreatZeroIdAsNullForForeignKeys()
                            && sourceValue instanceof Number
                            && ((Number) sourceValue).longValue() == 0L)
                    {
                        log.trace("Treating source value 0 as NULL for FK field '{}' (Source Col: {}) for ID {}",
                                  targetField, sourceCol, targetIdValue);
                        treatAsNull = true;
                    }

                    if (!treatAsNull && sourceValue != null) {
                        try {
                            convertedTargetValue = MigrationUtils.convertToFieldType(sourceValue, targetEntityClass, targetField);
                        } catch (Exception e) {
                            log.warn("Skipping field '{}' for row with ID {} due to conversion error: {}. Source Col: {}, Source type: {}, Value: '{}'",
                                     targetField, targetIdValue, e.getMessage(), sourceCol,
                                     (sourceValue != null ? sourceValue.getClass().getName() : "null"), sourceValue);
                            continue;
                        }
                    }

                    try {
                         MigrationUtils.setProperty(targetEntity, targetField, convertedTargetValue);
                    } catch (Exception e) {
                        log.warn("Skipping field '{}' for row with ID {} due to setting error: {}. Target Value: {}, Target Type: {}",
                                 targetField, targetIdValue, e.getMessage(), convertedTargetValue, (convertedTargetValue != null ? convertedTargetValue.getClass().getName() : "null"));
                    }
                }
            }

            saveEntityWithForcedIdInternal(
                    targetEntity,
                    idField,
                    targetIdValue,
                    isGeneratedId,
                    tableName,
                    (selfReferenceFkInfo != null) ? selfReferenceFkInfo.getDbColumnName() : null
            );

            log.trace("Successfully inserted row in table {} with ID: {}", tableName, targetIdValue);
            return true;

        } catch (Exception e) {
            log.error("Error processing row for table {} (Source ID: {}, Target ID: {}): {}",
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

        String updateSql = "UPDATE \"" + tableName + "\" SET \"" + selfRefDbColumn + "\" = ? WHERE \"" + idColumnName + "\" = ?";
        log.debug("Executing Self-Ref Update Batch (max size: {}) using SQL: {}", batchData.size(), updateSql);

        Session session = entityManager.unwrap(Session.class);
        final int[] totalUpdatedInBatch = {0};

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

                        if (sourceIdValue == null || sourceParentCol == null || !sourceRow.containsKey(sourceParentCol)) {
                            continue;
                        }

                        Object sourceParentIdValue = sourceRow.get(sourceParentCol);
                        Object targetId = null;
                        Object targetParentId = null;

                        try {
                            targetId = MigrationUtils.convertToFieldType(sourceIdValue, targetEntityClass, idFieldName);

                            boolean treatParentAsNull = false;
                            if (tableConfig.isTreatZeroIdAsNullForForeignKeys()
                                    && sourceParentIdValue instanceof Number
                                    && ((Number) sourceParentIdValue).longValue() == 0L) {
                                treatParentAsNull = true;
                            }

                            if (!treatParentAsNull && sourceParentIdValue != null) {
                                targetParentId = MigrationUtils.convertToFieldType(sourceParentIdValue, selfRefFkType, null);
                            }

                            if (targetParentId != null) {
                                MigrationUtils.setPreparedStatementParameters(updateStmt, List.of(targetParentId, targetId));
                                updateStmt.addBatch();
                                currentBatchCount++;

                                if (currentBatchCount % updateBatchSize == 0) {
                                    log.trace("Executing intermediate self-ref update batch ({} statements)", currentBatchCount);
                                    int[] batchResult = updateStmt.executeBatch();
                                    totalUpdatedInBatch[0] += Arrays.stream(batchResult).filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                                    updateStmt.clearBatch();
                                    currentBatchCount = 0;
                                }
                            }
                        } catch (Exception e) {
                             log.error("Error preparing self-ref update for table {} (Target ID: {}, Source Parent Col: {}, Source Parent Val: {}): {}",
                                     tableName, targetId != null ? targetId : sourceIdValue, sourceParentCol, sourceParentIdValue, e.getMessage(), e);
                        }
                    }

                    if (currentBatchCount > 0) {
                        log.trace("Executing final self-ref update batch ({} statements)", currentBatchCount);
                        int[] batchResult = updateStmt.executeBatch();
                        totalUpdatedInBatch[0] += Arrays.stream(batchResult).filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                    }
                    log.debug("Self-ref update batch executed for table {}. Statements processed reported by driver: {}", tableName, totalUpdatedInBatch[0]);

                } catch (SQLException e) {
                    log.error("SQLException during batch update execution for table {}: SQLState: {}, ErrorCode: {}, Message: {}",
                              tableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    throw new SQLException("Batch update failed for table " + tableName + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                 } catch (Exception e) {
                    log.error("Unexpected error during batch update work for table {}: {}", tableName, e.getMessage(), e);
                    throw new RuntimeException("Unexpected error during batch update: " + e.getMessage(), e);
                 }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                 log.error("Runtime exception during self-ref update processing for table {}: {}", tableName, e.getMessage(), e);
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
        log.debug("Processing historical activeness for batch of size {} for table {}", batchData.size(), targetTableName);

        // Group all rows in the current batch by their historical control ID.
        // Since we fetched by complete groups, we know each group here is complete.
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
                    LocalDateTime ldt = (LocalDateTime) MigrationUtils.convertToFieldType(dateVal, LocalDateTime.class, null);
                    return ldt != null ? ldt : LocalDateTime.MIN;
                } catch (Exception e) {
                    log.warn("Unparseable date for sorting historical chain: {} for histCtlId {}. Error: {}. Using MIN_DATE.",
                             dateVal, histCtlId, e.getMessage());
                    return LocalDateTime.MIN;
                }
            }).reversed()); // <-- CRITICAL FIX: reversed() for DESC order

            // The first record in the sorted list is the most recent one
            Map<String, Object> mostRecentRecord = historicalChain.get(0);
            LocalDateTime validFrom = null;
            try {
                Object validFromRaw = mostRecentRecord.get(sourceValidFromDateColumn);
                validFrom = (LocalDateTime) MigrationUtils.convertToFieldType(validFromRaw, LocalDateTime.class, null);
            } catch (Exception e) {
                log.error("Could not convert validFrom date for most recent record of histCtlId {}: {}", histCtlId, e.getMessage());
            }

            // Determine if the conceptual employee is active
            boolean isEmployeeActive = (validFrom != null && !validFrom.isAfter(now));
            log.trace("Determined active status for histCtlId {}: {}. (Most recent date: {})", histCtlId, isEmployeeActive, validFrom);

            // Create an update instruction for every record in this chain
            for (Map<String, Object> recordInChain : historicalChain) {
                Object sourceId = recordInChain.get(tableConfig.getSourceIdColumnName());
                if (sourceId == null) continue;
                try {
                    Object targetId = MigrationUtils.convertToFieldType(sourceId, targetEntityClass, targetIdFieldName);
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
                if (histCtlIdRaw == null) return true;
                if (histCtlIdRaw instanceof Number) return ((Number) histCtlIdRaw).longValue() <= 0;
                if (histCtlIdRaw instanceof String) {
                    try { return Long.parseLong(((String) histCtlIdRaw).trim()) <= 0; } catch (Exception e) { return true; }
                }
                return true;
            })
            .forEach(sourceRow -> {
                Object sourceId = sourceRow.get(tableConfig.getSourceIdColumnName());
                if (sourceId == null) return;
                try {
                    Object targetId = MigrationUtils.convertToFieldType(sourceId, targetEntityClass, targetIdFieldName);
                    Object validFromRaw = sourceRow.get(sourceValidFromDateColumn);
                    LocalDateTime validFrom = (LocalDateTime) MigrationUtils.convertToFieldType(validFromRaw, LocalDateTime.class, null);
                    boolean isActive = (validFrom != null && !validFrom.isAfter(now));
                    updatesToPerform.add(new HistoricalRecordUpdate(targetId, isActive));
                } catch (Exception e) {
                    log.error("Error processing standalone record for active status (Source ID: {}): {}", sourceId, e.getMessage());
                }
            });


        // Now, execute the batch update
        String updateActiveSql = "UPDATE \"" + targetTableName + "\" SET active = ? WHERE \"" + targetIdColumnName + "\" = ?";
        log.debug("Executing 'active' flag Update Batch ({} potential updates) using SQL: {}", updatesToPerform.size(), updateActiveSql);

        Session session = entityManager.unwrap(Session.class);
        final int[] totalUpdatedInThisBatchCall = {0};

        try {
            session.doWork(connection -> {
                try (PreparedStatement updateStmt = connection.prepareStatement(updateActiveSql)) {
                    int currentStatementsInJdbcBatch = 0;
                    for (HistoricalRecordUpdate update : updatesToPerform) {
                        if (update.targetId == null) {
                            log.warn("Skipping historical update due to null targetId.");
                            continue;
                        }
                        log.trace("Adding 'active' update batch: SET active = {} WHERE {} = {}", update.isActive, targetIdColumnName, update.targetId);
                        MigrationUtils.setPreparedStatementParameters(updateStmt, List.of(update.isActive, update.targetId));
                        updateStmt.addBatch();
                        currentStatementsInJdbcBatch++;

                        if (currentStatementsInJdbcBatch >= updateBatchSize) {
                            log.trace("Executing intermediate 'active' update batch ({} statements)", currentStatementsInJdbcBatch);
                            int[] batchResult = updateStmt.executeBatch();
                            totalUpdatedInThisBatchCall[0] += Arrays.stream(batchResult).filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                            updateStmt.clearBatch();
                            currentStatementsInJdbcBatch = 0;
                        }
                    }
                    // Execute any remaining statements in the batch
                    if (currentStatementsInJdbcBatch > 0) {
                        log.trace("Executing final 'active' update batch ({} statements)", currentStatementsInJdbcBatch);
                        int[] batchResult = updateStmt.executeBatch();
                        totalUpdatedInThisBatchCall[0] += Arrays.stream(batchResult).filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO).count();
                    }
                    log.debug("'active' flag update batch executed for table {}. Statements processed reported by driver: {}", targetTableName, totalUpdatedInThisBatchCall[0]);
                } catch (SQLException e) {
                    log.error("SQLException during 'active' flag batch update execution for table {}: SQLState: {}, ErrorCode: {}, Message: {}",
                              targetTableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    throw new SQLException("Batch 'active' update failed for table " + targetTableName + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                } catch (Exception e) {
                    log.error("Unexpected error during 'active' flag batch update work for table {}: {}", targetTableName, e.getMessage(), e);
                    throw new RuntimeException("Unexpected error during 'active' flag batch update: " + e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                log.error("Runtime exception during 'active' flag update processing for table {}: {}", targetTableName, e.getMessage(), e);
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

    private boolean checkEntityExistsInternal(String tableName, String idColumnName, Object idValue) throws SQLException {
        if (idValue == null) return false;
        String sql = "SELECT 1 FROM \"" + tableName + "\" WHERE \"" + idColumnName + "\" = ? LIMIT 1";
        Session session = entityManager.unwrap(Session.class);
        final boolean[] exists = {false};
        try {
            session.doWork(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    MigrationUtils.setPreparedStatementParameters(stmt, List.of(idValue));
                    try (ResultSet rs = stmt.executeQuery()) {
                        exists[0] = rs.next();
                    }
                } catch (SQLException e) {
                    throw new SQLException("Existence check failed for table " + tableName + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
            });
        } catch (RuntimeException e) {
             if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause();
             else throw e;
        }
        return exists[0];
    }

    private <T> void saveEntityWithForcedIdInternal(T entity, Field idField, Object idValue, boolean isGeneratedId, String tableName, String selfRefForeignKeyColumnNameToNull) throws Exception {
        Class<?> entityClass = entity.getClass();
        if (idValue == null) throw new IllegalArgumentException("ID value cannot be null for saving entity");

        if (isGeneratedId) {
            log.trace("Entity {} has @GeneratedValue, using native SQL INSERT for ID: {}", entityClass.getSimpleName(), idValue);
            StringBuilder columns = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            List<Object> values = new ArrayList<>();
            List<Field> allFields = MigrationUtils.getAllFields(entityClass);
            Set<String> processedColumnNames = new HashSet<>();

            for (Field field : allFields) {
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                        java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                        field.isAnnotationPresent(jakarta.persistence.Transient.class) ||
                        field.isAnnotationPresent(OneToMany.class) ||
                        field.isAnnotationPresent(ManyToMany.class) ) {
                    continue;
                }

                String columnName = MigrationUtils.getColumnNameForField(field);
                String columnNameKey = columnName.toLowerCase();

                if (processedColumnNames.contains(columnNameKey)) continue;

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

                if (columns.length() > 0) {
                    columns.append(", ");
                    placeholders.append(", ");
                }
                columns.append("\"").append(columnName).append("\"");
                placeholders.append("?");
                values.add(value);
                processedColumnNames.add(columnNameKey);
            }

            if (columns.length() == 0) return;

            String sql = "INSERT INTO \"" + tableName + "\" (" + columns + ") VALUES (" + placeholders + ")";
            log.trace("Executing native SQL: {}", sql);
            log.trace("With values: {}", values);

            Session session = entityManager.unwrap(Session.class);
            try {
                session.doWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        MigrationUtils.setPreparedStatementParameters(stmt, values);
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        throw new SQLException("Native SQL insert failed: " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause();
                else throw e;
            }
        } else {
            log.trace("Entity {} does not have @GeneratedValue, using entityManager.merge() for ID: {}", entityClass.getSimpleName(), idValue);
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