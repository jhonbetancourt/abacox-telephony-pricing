package com.infomedia.abacox.telephonypricing.migration;

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
import java.util.*;

@Component
@RequiredArgsConstructor
@Log4j2
public class MigrationRowProcessor {

    @PersistenceContext
    private final EntityManager entityManager; // Inject EntityManager here

    /**
     * Processes a single row insertion in its own transaction.
     * Handles existence check and saving.
     *
     * @return true if processed successfully (inserted or skipped existing), false if an error occurred.
     */
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

            // Create and Populate Entity
            Object targetEntity = targetEntityClass.getDeclaredConstructor().newInstance();
            idField.setAccessible(true);
            idField.set(targetEntity, targetIdValue); // Set ID

            // Populate other fields
            for (Map.Entry<String, String> entry : tableConfig.getColumnMapping().entrySet()) {
                String sourceCol = entry.getKey();
                String targetField = entry.getValue();

                if (targetField.equals(idFieldName)) continue; // Skip ID field itself

                ForeignKeyInfo fkInfo = foreignKeyInfoMap.get(targetField);

                // Skip self-reference FK field population in Pass 1 - will be handled in Pass 2
                if (fkInfo != null && fkInfo.isSelfReference()) {
                    log.trace("Skipping self-ref FK field '{}' population in Pass 1 for ID {}", targetField, targetIdValue);
                    continue;
                }

                if (sourceRow.containsKey(sourceCol)) {
                    Object sourceValue = sourceRow.get(sourceCol);
                    Object targetValue = null; // Initialize target value

                    // --- Handle "0 as NULL" for Foreign Keys ---
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
                    // --- End Handle "0 as NULL" ---

                    if (!treatAsNull && sourceValue != null) {
                        try {
                            // Convert non-null, non-zero source value
                            targetValue = MigrationUtils.convertToFieldType(sourceValue, targetEntityClass, targetField);
                        } catch (Exception e) {
                            log.warn("Skipping field '{}' for row with ID {} due to conversion error: {}. Source Col: {}, Source type: {}, Value: '{}'",
                                     targetField, targetIdValue, e.getMessage(), sourceCol,
                                     (sourceValue != null ? sourceValue.getClass().getName() : "null"), sourceValue);
                            continue; // Skip setting this field
                        }
                    } else {
                        // targetValue remains null if source was null or treated as null
                        targetValue = null;
                    }

                    // Set the property
                    try {
                         MigrationUtils.setProperty(targetEntity, targetField, targetValue);
                    } catch (Exception e) {
                        // Log the error but continue processing other fields for this entity
                        log.warn("Skipping field '{}' for row with ID {} due to setting error: {}. Target Value: {}, Target Type: {}",
                                 targetField, targetIdValue, e.getMessage(), targetValue, (targetValue != null ? targetValue.getClass().getName() : "null"));
                    }
                } // end if sourceRow.containsKey
            } // end for columnMapping loop

            // Save using internal method
            saveEntityWithForcedIdInternal(
                    targetEntity,
                    idField,
                    targetIdValue,
                    isGeneratedId,
                    tableName,
                    (selfReferenceFkInfo != null) ? selfReferenceFkInfo.getDbColumnName() : null // Pass self-ref DB column name
            );

            log.trace("Successfully inserted row in table {} with ID: {}", tableName, targetIdValue);
            return true; // Inserted successfully

        } catch (Exception e) {
            // Log the specific error for this row
            log.error("Error processing row for table {} (Source ID: {}, Target ID: {}): {}",
                      tableName, sourceIdValue,
                      targetIdValue != null ? targetIdValue : "UNKNOWN",
                      e.getMessage(), e);
            // Return false to indicate failure for this specific row's transaction.
            // The @Transactional annotation ensures rollback.
            return false;
        }
    }


    /**
     * Processes a batch of self-reference FK updates in its own transaction.
     *
     * @return number of rows updated successfully in this batch.
     * @throws SQLException If the batch execution fails at the DB level.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = SQLException.class)
    public int processSelfRefUpdateBatch(List<Map<String, Object>> batchData,
                                         TableMigrationConfig tableConfig,
                                         Class<?> targetEntityClass,
                                         String tableName,
                                         String idColumnName,
                                         String idFieldName,
                                         ForeignKeyInfo selfReferenceFkInfo,
                                         int batchSize) throws SQLException { // Propagate SQLException for rollback
        if (selfReferenceFkInfo == null || batchData == null || batchData.isEmpty()) {
            return 0;
        }

        String selfRefDbColumn = selfReferenceFkInfo.getDbColumnName();
        Field selfRefFkField = selfReferenceFkInfo.getForeignKeyField();
        Class<?> selfRefFkType = selfReferenceFkInfo.getTargetTypeId();

        String updateSql = "UPDATE \"" + tableName + "\" SET \"" + selfRefDbColumn + "\" = ? WHERE \"" + idColumnName + "\" = ?";
        log.debug("Executing Update Batch (max size: {}) using SQL: {}", batchData.size(), updateSql);

        Session session = entityManager.unwrap(Session.class);
        final int[] totalUpdatedInBatch = {0}; // Use array to be modifiable in lambda

        try {
            session.doWork(connection -> {
                try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                    int currentBatchCount = 0;
                    for (Map<String, Object> sourceRow : batchData) {
                        Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());
                        // Find the source column mapped to the self-ref FK field
                        String sourceParentCol = tableConfig.getColumnMapping().entrySet().stream()
                                .filter(e -> e.getValue().equals(selfRefFkField.getName()))
                                .map(Map.Entry::getKey)
                                .findFirst().orElse(null);

                        if (sourceIdValue == null || sourceParentCol == null || !sourceRow.containsKey(sourceParentCol)) {
                            log.trace("Skipping update prep for source ID {} - missing ID, parent column mapping, or parent value.", sourceIdValue);
                            continue;
                        }

                        Object sourceParentIdValue = sourceRow.get(sourceParentCol);
                        Object targetId = null;
                        Object targetParentId = null; // Default to null

                        try {
                            targetId = MigrationUtils.convertToFieldType(sourceIdValue, targetEntityClass, idFieldName);

                            // --- Handle "0 as NULL" for Parent FK in Pass 2 ---
                            boolean treatParentAsNull = false;
                            if (tableConfig.isTreatZeroIdAsNullForForeignKeys()
                                    && sourceParentIdValue instanceof Number
                                    && ((Number) sourceParentIdValue).longValue() == 0L) {
                                log.trace("Treating source parent value 0 as NULL for FK update (Source Col: {}) for ID {}",
                                          sourceParentCol, targetId);
                                treatParentAsNull = true;
                            }
                            // --- End Handle "0 as NULL" ---

                            if (!treatParentAsNull && sourceParentIdValue != null) {
                                // Convert non-null, non-zero parent ID to the FK field's type
                                targetParentId = MigrationUtils.convertToFieldType(sourceParentIdValue,
                                                                                   selfRefFkType, // Target type is the FK field type (e.g., Long.class)
                                                                                   null); // Don't look up field name, we know the type
                            }

                            // Only add update to batch if parent ID is not null (after potential 0-as-null handling)
                            if (targetParentId != null) {
                                log.trace("Adding update batch: SET {} = {} WHERE {} = {}", selfRefDbColumn, targetParentId, idColumnName, targetId);
                                MigrationUtils.setPreparedStatementParameters(updateStmt, List.of(targetParentId, targetId));
                                updateStmt.addBatch();
                                currentBatchCount++;
                            } else {
                                log.trace("Skipping update for ID {} because target parent ID is null (Source Col: {}, Source Value: {})",
                                          targetId, sourceParentCol, sourceParentIdValue);
                            }

                        } catch (Exception e) {
                            // Log error for this specific row update preparation, but continue batching others
                             log.error("Error preparing self-ref update for table {} (Target ID: {}, Source Parent Col: {}, Source Parent Val: {}): {}",
                                     tableName, targetId != null ? targetId : sourceIdValue, sourceParentCol, sourceParentIdValue, e.getMessage(), e);
                        }
                    } // End loop for batch preparation

                    if (currentBatchCount > 0) {
                        log.debug("Executing update batch ({} statements)", currentBatchCount);
                        int[] batchResult = updateStmt.executeBatch();
                        // Sum successful updates (count >= 0). Statement.SUCCESS_NO_INFO (-2) is also success.
                        // Statement.EXECUTE_FAILED (-3) indicates failure.
                        long successfulUpdates = Arrays.stream(batchResult)
                                                     .filter(i -> i >= 0 || i == Statement.SUCCESS_NO_INFO)
                                                     .count();
                        totalUpdatedInBatch[0] = (int) successfulUpdates; // Assign the count of successful ops

                        long failedUpdates = Arrays.stream(batchResult)
                                                  .filter(i -> i == Statement.EXECUTE_FAILED)
                                                  .count();
                        if(failedUpdates > 0) {
                             log.warn("Update batch for table {} reported {} failed statements.", tableName, failedUpdates);
                             // Depending on DB behavior, executeBatch might throw SQLException before this point if *any* statement fails.
                             // If it doesn't throw, but reports failures, we might need more robust error handling here.
                             // For now, we rely on executeBatch throwing SQLException on failure for rollback.
                        }

                        log.debug("Update batch executed for table {}. Statements processed reported by driver: {}", tableName, totalUpdatedInBatch[0]);
                    } else {
                         log.debug("No updates to execute in this batch for table {}.", tableName);
                    }

                } catch (SQLException e) {
                    // Log the specific SQL error and re-throw it to trigger the rollback
                    log.error("SQLException during batch update execution for table {}: SQLState: {}, ErrorCode: {}, Message: {}",
                              tableName, e.getSQLState(), e.getErrorCode(), e.getMessage());
                    // Rethrow wrapped in a specific exception or as is to ensure rollback
                    throw new SQLException("Batch update failed for table " + tableName + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                 } catch (Exception e) {
                    // Catch other potential errors during doWork
                    log.error("Unexpected error during batch update work for table {}: {}", tableName, e.getMessage(), e);
                    // Wrap in a runtime exception, assuming this shouldn't happen and indicates a coding error.
                    // This will also trigger rollback if the outer call catches RuntimeException.
                    throw new RuntimeException("Unexpected error during batch update: " + e.getMessage(), e);
                 }
            }); // End doWork
        } catch (RuntimeException e) {
            // Handle exceptions thrown from doWork (like the SQLException rethrown above or the RuntimeException)
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause(); // Propagate the SQLException
            } else {
                // Log and rethrow other runtime exceptions
                 log.error("Runtime exception during self-ref update processing for table {}: {}", tableName, e.getMessage(), e);
                throw e;
            }
        }
        return totalUpdatedInBatch[0];
    }


    // --- Internal DB Helper Methods using injected EntityManager ---

    /**
     * Checks if an entity exists using native SQL within the current transaction.
     */
    private boolean checkEntityExistsInternal(String tableName, String idColumnName, Object idValue) throws SQLException {
        if (idValue == null) {
            log.trace("Existence check skipped for null ID in table {}", tableName);
            return false;
        }
        // Use injected entityManager
        String sql = "SELECT 1 FROM \"" + tableName + "\" WHERE \"" + idColumnName + "\" = ? LIMIT 1";
        Session session = entityManager.unwrap(Session.class);
        final boolean[] exists = {false};
        try {
            log.trace("Executing existence check: {}", sql.replace("?", idValue.toString()));
            session.doWork(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    MigrationUtils.setPreparedStatementParameters(stmt, List.of(idValue)); // Use static util
                    try (ResultSet rs = stmt.executeQuery()) {
                        exists[0] = rs.next(); // Check if any row was returned
                    }
                } catch (SQLException e) {
                    // Log and wrap/rethrow is often better than just throwing SQLException
                    log.error("Existence check query failed for table {} id {}: {}", tableName, idValue, e.getMessage());
                    throw new SQLException("Existence check failed for table " + tableName + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
            });
        } catch (RuntimeException e) {
             if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause();
             else {
                 log.error("Runtime exception during existence check for {}.{} = {}: {}", tableName, idColumnName, idValue, e.getMessage(), e);
                 throw e; // Or wrap in a custom migration exception
             }
        }
        log.trace("Existence check result for {}.{} = {}: {}", tableName, idColumnName, idValue, exists[0]);
        return exists[0];
    }

    /**
     * Saves an entity, forcing the provided ID using native SQL (if generated) or merge.
     * If selfRefForeignKeyColumnName is provided for native insert, its value is forced to NULL.
     */
    private <T> void saveEntityWithForcedIdInternal(T entity, Field idField, Object idValue, boolean isGeneratedId, String tableName, String selfRefForeignKeyColumnNameToNull) throws Exception {
        // Use injected entityManager
        Class<?> entityClass = entity.getClass();
        if (idValue == null) throw new IllegalArgumentException("ID value cannot be null for saving entity");

        if (isGeneratedId) {
            // --- Native SQL Insert Logic ---
            log.trace("Entity {} has @GeneratedValue, using native SQL INSERT for ID: {}", entityClass.getSimpleName(), idValue);
            StringBuilder columns = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            List<Object> values = new ArrayList<>();
            List<Field> allFields = MigrationUtils.getAllFields(entityClass); // Use static util
            Set<String> processedColumnNames = new HashSet<>(); // Avoid duplicate columns if mapped differently

            for (Field field : allFields) {
                field.setAccessible(true);
                // Skip static, transient, or collection fields for direct insertion
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                        java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                        field.isAnnotationPresent(jakarta.persistence.Transient.class) ||
                        field.isAnnotationPresent(OneToMany.class) ||
                        field.isAnnotationPresent(ManyToMany.class) ) {
                    continue;
                }

                String columnName = MigrationUtils.getColumnNameForField(field); // Use static util
                String columnNameKey = columnName.toLowerCase(); // Use lower case for comparison consistency

                // Avoid adding the same column twice if multiple fields map to it (less common)
                if (processedColumnNames.contains(columnNameKey)) {
                    continue;
                }

                Object value = field.get(entity);

                // Handle potential entity associations that need their ID extracted
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);

                // Force self-ref FK column to NULL in Pass 1 native insert
                if (columnName.equalsIgnoreCase(selfRefForeignKeyColumnNameToNull)) {
                    log.trace("Forcing column '{}' to NULL for Pass 1 native insert.", columnName);
                    value = null; // Override value to null
                }
                // If it's a relationship field (@ManyToOne/@OneToOne with @JoinColumn) AND NOT the self-ref being nulled
                else if (value != null && (manyToOne != null || oneToOne != null) && joinColumn != null) {
                    // Assume 'value' is the related entity object loaded/set during population
                    // We need the ID of the related entity for the FK column
                    Field relatedIdField = MigrationUtils.findIdField(value.getClass()); // Use static util
                    if (relatedIdField != null) {
                        relatedIdField.setAccessible(true);
                        Object relatedIdValue = relatedIdField.get(value);
                        log.trace("Extracted FK value {} from entity field '{}' for column '{}'", relatedIdValue, field.getName(), columnName);
                        value = relatedIdValue; // Use the ID value for the insert
                    } else {
                        // This indicates a problem - we have a related entity object but can't find its ID.
                        log.warn("Could not find @Id field on related entity type {} for relationship field '{}'. Using null for column '{}'. This might cause FK constraint violations.",
                                 value.getClass().getSimpleName(), field.getName(), columnName);
                        value = null; // Set FK to null if ID cannot be found
                    }
                }
                // Else: It's a basic field, or the FK *ID* field itself (already holds the ID), or a non-JoinColumn relation (skip)

                // Append to SQL query parts
                if (columns.length() > 0) {
                    columns.append(", ");
                    placeholders.append(", ");
                }
                columns.append("\"").append(columnName).append("\""); // Quote column names
                placeholders.append("?");
                values.add(value);
                processedColumnNames.add(columnNameKey);
            } // End field loop

            if (columns.length() == 0) {
                 log.warn("No columns found to include in native INSERT statement for entity {} with ID {}", entityClass.getSimpleName(), idValue);
                 return; // Nothing to insert
            }

            String sql = "INSERT INTO \"" + tableName + "\" (" + columns + ") VALUES (" + placeholders + ")";
            log.trace("Executing native SQL: {}", sql);
            log.trace("With values: {}", values);

            Session session = entityManager.unwrap(Session.class);
            try {
                session.doWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        MigrationUtils.setPreparedStatementParameters(stmt, values); // Use static util
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        // Log with details before throwing to ensure it's captured
                        log.error("Native SQL insert failed for table {}. SQL: [{}]. Values: {}. Error: {}", tableName, sql, values, e.getMessage());
                        // Re-throw SQLException to be caught by the caller and trigger rollback
                      //  throw new SQLException("Native SQL insert failed: " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                    }
                });
            } catch (RuntimeException e) {
                // Handle exceptions thrown from doWork (like the SQLException rethrown above)
                if (e.getCause() instanceof SQLException) {
                    throw (SQLException) e.getCause(); // Propagate the underlying SQLException
                } else {
                    log.error("Runtime exception during native insert doWork for {}: {}", tableName, e.getMessage(), e);
                    throw e; // Propagate other runtime exceptions
                }
            }
        } else {
            // --- Merge Logic (For non-generated IDs) ---
            log.trace("Entity {} does not have @GeneratedValue, using entityManager.merge() for ID: {}", entityClass.getSimpleName(), idValue);
            // We still might need to nullify self-reference FK *field* before merge in Pass 1
            if (selfRefForeignKeyColumnNameToNull != null) {
                Field fkField = MigrationUtils.findFieldByColumnName(entityClass, selfRefForeignKeyColumnNameToNull); // Use static util
                if (fkField != null) {
                    fkField.setAccessible(true);
                    if (!fkField.getType().isPrimitive()) { // Can only set non-primitives to null
                        Object currentValue = fkField.get(entity);
                        if (currentValue != null) {
                             log.trace("Setting self-ref field '{}' (column '{}') to null before merge for Pass 1.", fkField.getName(), selfRefForeignKeyColumnNameToNull);
                             fkField.set(entity, null); // Set field on the object to null before merging
                        }
                    } else {
                        log.warn("Cannot set primitive self-ref field '{}' (column '{}') to null before merge.", fkField.getName(), selfRefForeignKeyColumnNameToNull);
                    }
                } else {
                    log.warn("Could not find field corresponding to self-ref FK column '{}' to set null before merge.", selfRefForeignKeyColumnNameToNull);
                }
            }
            entityManager.merge(entity); // Merge the (potentially modified) entity
            log.trace("Merge operation completed for ID {}", idValue);
        }
    }

}