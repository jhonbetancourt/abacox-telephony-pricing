package com.infomedia.abacox.telephonypricing.migration;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.beanutils.PropertyUtils;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class TableMigrationExecutor {

    private final SourceDataFetcher sourceDataFetcher;

    @PersistenceContext
    private final EntityManager entityManager;

    private static final int UPDATE_BATCH_SIZE = 100;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeTableMigration(TableMigrationConfig tableConfig, SourceDbConfig sourceDbConfig) throws Exception {
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        int skippedExistingCount = 0;
        int insertedCount = 0;
        int updatedFkCount = 0;

        log.info("Executing migration for source table: {}", tableConfig.getSourceTableName());

        // --- Get Target Entity Metadata (including inferred FKs) ---
        Class<?> targetEntityClass = Class.forName(tableConfig.getTargetEntityClassName());
        Field idField = findIdField(targetEntityClass);
        if (idField == null) throw new IllegalArgumentException("Entity class " + targetEntityClass.getName() + " does not have an @Id field");
        idField.setAccessible(true);
        String idFieldName = idField.getName();
        String idColumnName = getIdColumnName(idField);
        String tableName = getTableName(targetEntityClass);
        boolean isGeneratedId = idField.isAnnotationPresent(GeneratedValue.class);

        // Infer FK info
        Map<String, ForeignKeyInfo> foreignKeyInfoMap = inferForeignKeyInfo(targetEntityClass);
        ForeignKeyInfo selfReferenceFkInfo = findSelfReference(foreignKeyInfoMap, targetEntityClass);
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
        // Ensure source column for FKs are included if mapped
        for (ForeignKeyInfo fkInfo : foreignKeyInfoMap.values()) {
             // Find the source column mapped to this FK field
             tableConfig.getColumnMapping().entrySet().stream()
                 .filter(entry -> entry.getValue().equals(fkInfo.getForeignKeyField().getName()))
                 .map(Map.Entry::getKey)
                 .findFirst()
                 .ifPresent(columnsToFetch::add);
        }
        List<Map<String, Object>> sourceData = sourceDataFetcher.fetchData(
                sourceDbConfig,
                tableConfig.getSourceTableName(),
                columnsToFetch,
                tableConfig.getSourceIdColumnName()
        );
        if (sourceData == null || sourceData.isEmpty()) { /* ... handle no data ... */ return; }
        // --- End Fetch ---


        // ==================================================
        // Pass 1: Insert rows, handling FKs (0 as NULL, self-ref as NULL)
        // ==================================================
        log.info("Starting Pass 1: Inserting {} rows for table {}", sourceData.size(), tableName);
        for (Map<String, Object> sourceRow : sourceData) {
            processedCount++;
            Object targetIdValue = null;

            try {
                Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());
                if (sourceIdValue == null) { /* ... handle null ID ... */ skippedExistingCount++; continue; }

                targetIdValue = convertToFieldType(sourceIdValue, targetEntityClass, idFieldName);

                boolean exists = checkEntityExistsInternal(tableName, idColumnName, targetIdValue);
                if (exists) { /* ... handle existing ... */ skippedExistingCount++; continue; }

                // Create and Populate Entity
                Object targetEntity = targetEntityClass.getDeclaredConstructor().newInstance();
                idField.set(targetEntity, targetIdValue); // Set ID

                // Populate other fields based on mapping
                for (Map.Entry<String, String> entry : tableConfig.getColumnMapping().entrySet()) {
                    String sourceCol = entry.getKey();
                    String targetField = entry.getValue();

                    if (targetField.equals(idFieldName)) continue; // Skip ID

                    ForeignKeyInfo fkInfo = foreignKeyInfoMap.get(targetField); // Check if target is an FK field

                    // Skip self-reference FK field in Pass 1
                    if (fkInfo != null && fkInfo.isSelfReference()) {
                        log.trace("Skipping self-ref FK field '{}' population in Pass 1 for ID {}", targetField, targetIdValue);
                        continue;
                    }

                    if (sourceRow.containsKey(sourceCol)) {
                        Object sourceValue = sourceRow.get(sourceCol);
                        Object targetValue = null; // Initialize target value

                        // --- Handle "0 as NULL" for Foreign Keys ---
                        boolean treatAsNull = false;
                        if (fkInfo != null // Is it an FK?
                            && tableConfig.isTreatZeroIdAsNullForForeignKeys() // Is the flag enabled?
                            && sourceValue instanceof Number // Is the source a number?
                            && ((Number) sourceValue).longValue() == 0L) // Is the value 0?
                        {
                            log.trace("Treating source value 0 as NULL for FK field '{}' (Source Col: {}) for ID {}",
                                      targetField, sourceCol, targetIdValue);
                            treatAsNull = true;
                        }
                        // --- End Handle "0 as NULL" ---

                        if (!treatAsNull && sourceValue != null) {
                            try {
                                // Convert non-null, non-zero source value
                                targetValue = convertToFieldType(sourceValue, targetEntityClass, targetField);
                            } catch (Exception e) {
                                log.warn("Skipping field '{}' for row with ID {} due to conversion error: {}. Source type: {}, Value: '{}'",
                                         targetField, targetIdValue, e.getMessage(),
                                         (sourceValue != null ? sourceValue.getClass().getName() : "null"), sourceValue);
                                continue; // Skip setting this field
                            }
                        } else {
                            // targetValue remains null if source was null or treated as null
                            targetValue = null;
                        }

                        // Set the property (handles null targetValue correctly)
                        try {
                             PropertyUtils.setProperty(targetEntity, targetField, targetValue);
                        } catch (Exception e) {
                             log.warn("Skipping field '{}' for row with ID {} due to setting error: {}. Target Value: {}",
                                      targetField, targetIdValue, e.getMessage(), targetValue);
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
                    isSelfReferencing ? selfReferenceFkInfo.getDbColumnName() : null // Pass self-ref DB column name to force NULL in native SQL
                );
                insertedCount++;

            } catch (Exception e) {
                log.error("Error during Pass 1 processing row for table {} (Target ID: {}): {}",
                        tableName, targetIdValue != null ? targetIdValue : "UNKNOWN", e.getMessage(), e);
                skippedExistingCount++;
            }
        } // End Pass 1 loop
        log.info("Finished Pass 1 for {}. Inserted: {}, Skipped Existing/Errors: {}", tableName, insertedCount, skippedExistingCount);


        // ==================================================
        // Pass 2: Update self-referencing FKs (only if needed)
        // ==================================================
        if (isSelfReferencing) {
            String selfRefDbColumn = selfReferenceFkInfo.getDbColumnName();
            Field selfRefFkField = selfReferenceFkInfo.getForeignKeyField();
            Class<?> selfRefFkType = selfReferenceFkInfo.getTargetTypeId();

            log.info("Starting Pass 2: Updating self-reference FK '{}' for table {}", selfRefDbColumn, tableName);
            String updateSql = "UPDATE \"" + tableName + "\" SET \"" + selfRefDbColumn + "\" = ? WHERE \"" + idColumnName + "\" = ?";
            log.debug("Using Update SQL: {}", updateSql);

            Session session = entityManager.unwrap(Session.class);
            final int[] batchUpdateCounts = {0};

            try {
                 session.doWork(connection -> {
                     try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                         int currentBatchSize = 0;
                         for (Map<String, Object> sourceRow : sourceData) {
                             Object sourceIdValue = sourceRow.get(tableConfig.getSourceIdColumnName());
                             // Find the source column mapped to the self-ref FK field
                             String sourceParentCol = tableConfig.getColumnMapping().entrySet().stream()
                                 .filter(e -> e.getValue().equals(selfRefFkField.getName()))
                                 .map(Map.Entry::getKey)
                                 .findFirst().orElse(null);

                             if (sourceIdValue == null || sourceParentCol == null) continue; // Skip if no ID or no mapping for parent

                             Object sourceParentIdValue = sourceRow.get(sourceParentCol);

                             try {
                                 Object targetId = convertToFieldType(sourceIdValue, targetEntityClass, idFieldName);
                                 Object targetParentId = null; // Default to null

                                 // --- Handle "0 as NULL" for Parent FK in Pass 2 ---
                                 boolean treatParentAsNull = false;
                                 if (tableConfig.isTreatZeroIdAsNullForForeignKeys()
                                     && sourceParentIdValue instanceof Number
                                     && ((Number) sourceParentIdValue).longValue() == 0L)
                                 {
                                     log.trace("Treating source parent value 0 as NULL for FK update (Source Col: {}) for ID {}",
                                               sourceParentCol, targetId);
                                     treatParentAsNull = true;
                                 }
                                 // --- End Handle "0 as NULL" ---

                                 if (!treatParentAsNull && sourceParentIdValue != null) {
                                     // Convert non-null, non-zero parent ID
                                     targetParentId = convertToFieldType(sourceParentIdValue,
                                                                         selfRefFkType, // Target type (e.g., Long.class)
                                                                         null); // Don't look up field name
                                 }

                                 // Only add update to batch if parent ID is not null (after potential 0-as-null handling)
                                 if (targetParentId != null) {
                                     log.trace("Adding update batch: SET {} = {} WHERE {} = {}", selfRefDbColumn, targetParentId, idColumnName, targetId);
                                     setPreparedStatementParameters(updateStmt, List.of(targetParentId, targetId));
                                     updateStmt.addBatch();
                                     currentBatchSize++;
                                     batchUpdateCounts[0]++;

                                     if (currentBatchSize >= UPDATE_BATCH_SIZE) { /* ... execute batch ... */
                                          log.debug("Executing update batch (size: {})", currentBatchSize);
                                          updateStmt.executeBatch(); updateStmt.clearBatch(); currentBatchSize = 0;
                                     }
                                 } else {
                                     log.trace("Skipping update for ID {} because target parent ID is null (Source Col: {}, Source Value: {})",
                                               targetId, sourceParentCol, sourceParentIdValue);
                                 }
                             } catch (Exception e) {
                                 log.error("Error preparing update for table {} (Target ID: {}): {}",
                                           tableName, sourceIdValue, e.getMessage(), e);
                             }
                         } // End loop for update batching

                         if (currentBatchSize > 0) { /* ... execute final batch ... */
                              log.debug("Executing final update batch (size: {})", currentBatchSize); updateStmt.executeBatch();
                         }

                     } catch (SQLException e) { /* ... handle batch update failure ... */ throw new SQLException("Batch update failed: " + e.getMessage(), e); }
                 }); // End doWork
                 updatedFkCount = batchUpdateCounts[0];
                 log.info("Finished Pass 2 for {}. Updated FKs: {}", tableName, updatedFkCount);

            } catch (RuntimeException e) { /* ... handle doWork exceptions ... */ if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause(); else throw e; }
        } // End if isSelfReferencing

        long duration = System.currentTimeMillis() - startTime;
        log.info("Finished migrating table {}. Processed: {}, Inserted: {}, Skipped: {}, Updated FKs: {}. Duration: {} ms",
                 tableName, processedCount, insertedCount, skippedExistingCount, updatedFkCount, duration);
    }


    // ========================================================================
    // Internal Helper Methods (Including new FK Inference)
    // ========================================================================

    /**
     * Infers foreign key information from JPA annotations.
     * @param entityClass The target entity class.
     * @return Map where Key is the target *field name* of the FK ID field (e.g., "parentSubdivisionId"),
     *         and Value is ForeignKeyInfo containing details.
     */
    private Map<String, ForeignKeyInfo> inferForeignKeyInfo(Class<?> entityClass) {
        Map<String, ForeignKeyInfo> fkMap = new HashMap<>();
        List<Field> allFields = getAllFields(entityClass);

        for (Field field : allFields) {
            field.setAccessible(true);
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
            OneToOne oneToOne = field.getAnnotation(OneToOne.class); // Also consider OneToOne

            Field relationshipField = null; // The field holding the actual entity relationship (e.g., parentSubdivision)
            Field foreignKeyIdField = null; // The field holding the FK value (e.g., parentSubdivisionId)

            // Scenario 1: Field has @JoinColumn and is a relationship type (@ManyToOne/@OneToOne)
            if (joinColumn != null && (manyToOne != null || oneToOne != null)) {
                relationshipField = field;
                // Need to find the corresponding ID field if it exists (e.g., parentSubdivisionId for parentSubdivision)
                // This is tricky and relies on conventions or explicit mapping often not present.
                // For simplicity, we'll focus on finding the field that *directly* uses the @JoinColumn name if available,
                // or assume the @JoinColumn *is* on the ID field itself if the type matches.
                // Let's find a potential matching ID field first.
                String potentialIdFieldName = derivePotentialIdFieldName(field.getName()); // e.g., "parentSubdivision" -> "parentSubdivisionId"
                foreignKeyIdField = findField(entityClass, potentialIdFieldName);
                // If we didn't find a separate ID field, maybe the @JoinColumn is on the ID field itself?
                if (foreignKeyIdField == null && (field.getType() == Long.class || field.getType() == long.class || field.getType() == Integer.class || field.getType() == int.class /* add other ID types */)) {
                     foreignKeyIdField = field; // Assume this field holds the ID and the @JoinColumn
                     relationshipField = null; // No separate relationship field in this case
                }

            // Scenario 2: Field is likely the FK ID field itself (e.g., Long parentSubdivisionId)
            // It might have @Column(name="...") matching a @JoinColumn elsewhere, or just the @JoinColumn
            } else if (joinColumn != null && !(manyToOne != null || oneToOne != null)) {
                 foreignKeyIdField = field; // Assume this field holds the ID and has the @JoinColumn
                 // Try to find the corresponding relationship field (e.g., parentSubdivision for parentSubdivisionId)
                 String potentialRelationshipFieldName = derivePotentialRelationshipFieldName(field.getName()); // e.g., "parentSubdivisionId" -> "parentSubdivision"
                 relationshipField = findField(entityClass, potentialRelationshipFieldName);

            // Scenario 3: Field might be the FK ID field without @JoinColumn, relying on relationship field's @JoinColumn
            } else if (joinColumn == null && !(manyToOne != null || oneToOne != null)) {
                 // Check if this field's name matches a potential ID field derived from a relationship field found elsewhere
                 for(Field otherField : allFields) {
                      if (otherField.isAnnotationPresent(JoinColumn.class) && (otherField.isAnnotationPresent(ManyToOne.class) || otherField.isAnnotationPresent(OneToOne.class))) {
                           String potentialIdFieldName = derivePotentialIdFieldName(otherField.getName());
                           if (field.getName().equals(potentialIdFieldName)) {
                                foreignKeyIdField = field;
                                relationshipField = otherField;
                                joinColumn = otherField.getAnnotation(JoinColumn.class); // Get JoinColumn from relationship field
                                break;
                           }
                      }
                 }
            }


            // If we identified an FK ID field and its JoinColumn details
            if (foreignKeyIdField != null && joinColumn != null) {
                String dbColumnName = joinColumn.name();
                if (dbColumnName == null || dbColumnName.isEmpty()) {
                    // If name is missing in @JoinColumn, JPA might infer it, but we need it explicitly here.
                    // Let's try getting it from @Column on the ID field if present, or default to field name.
                    dbColumnName = getColumnNameForField(foreignKeyIdField);
                    log.warn("Using inferred/default column name '{}' for FK field '{}'. Explicit name in @JoinColumn is recommended.",
                             dbColumnName, foreignKeyIdField.getName());
                }

                Class<?> fkFieldType = foreignKeyIdField.getType();
                // Determine target entity type for self-reference check
                Class<?> targetEntityType = null;
                if (relationshipField != null) {
                     targetEntityType = relationshipField.getType(); // Type of the @ManyToOne/OneToOne field
                } else if (manyToOne != null) { // If @JoinColumn and @ManyToOne are on the same field
                     targetEntityType = field.getType();
                } else if (oneToOne != null) { // If @JoinColumn and @OneToOne are on the same field
                     targetEntityType = field.getType();
                }
                 // Add more logic here if targetEntity is needed but not found

                boolean isSelfRef = targetEntityType != null && targetEntityType == entityClass;

                ForeignKeyInfo info = new ForeignKeyInfo(foreignKeyIdField, dbColumnName, fkFieldType, isSelfRef, relationshipField);
                fkMap.put(foreignKeyIdField.getName(), info); // Map by the FK ID field name
                log.trace("Inferred FK: Field='{}', Column='{}', Type='{}', SelfRef={}, RelField='{}'",
                          foreignKeyIdField.getName(), dbColumnName, fkFieldType.getSimpleName(), isSelfRef,
                          relationshipField != null ? relationshipField.getName() : "N/A");
            }
        }
        return fkMap;
    }

    // Helper to guess potential ID field name from relationship field name
    private String derivePotentialIdFieldName(String relationshipFieldName) {
        // Simple convention: append "Id"
        if (relationshipFieldName != null && !relationshipFieldName.isEmpty()) {
            return relationshipFieldName + "Id";
        }
        return null;
    }

    // Helper to guess potential relationship field name from ID field name
     private String derivePotentialRelationshipFieldName(String idFieldName) {
        // Simple convention: remove trailing "Id" if present
        if (idFieldName != null && idFieldName.toLowerCase().endsWith("id") && idFieldName.length() > 2) {
            return idFieldName.substring(0, idFieldName.length() - 2);
        }
        return null;
    }


    /** Finds the first self-referencing FK from the map */
    private ForeignKeyInfo findSelfReference(Map<String, ForeignKeyInfo> fkMap, Class<?> entityClass) {
        return fkMap.values().stream()
                .filter(ForeignKeyInfo::isSelfReference)
                .findFirst()
                .orElse(null);
    }


    /**
     * Saves an entity, forcing the provided ID. If selfRefForeignKeyColumnName is provided,
     * its value will be forced to NULL during native SQL INSERT.
     */
    private <T> void saveEntityWithForcedIdInternal(T entity, Field idField, Object idValue, boolean isGeneratedId, String tableName, String selfRefForeignKeyColumnNameToNull) throws Exception {
        Class<?> entityClass = entity.getClass();
        if (idValue == null) throw new IllegalArgumentException("ID value must be set");

        if (isGeneratedId) {
            // --- Native SQL Insert Logic (Handles selfRefForeignKeyColumnNameToNull) ---
            log.trace("Entity {} has @GeneratedValue, using native SQL INSERT for ID: {}", entityClass.getSimpleName(), idValue);
            StringBuilder columns = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            List<Object> values = new ArrayList<>();
            List<Field> allFields = getAllFields(entityClass);
            Set<String> processedColumnNames = new HashSet<>();

            for (Field field : allFields) {
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                    field.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                    continue;
                }

                String columnName = getColumnNameForField(field);
                String columnNameKey = columnName.toLowerCase();

                if (processedColumnNames.contains(columnNameKey)) { continue; }

                Object value = field.get(entity);

                // Force self-ref FK column to NULL in Pass 1 native insert
                if (columnName.equalsIgnoreCase(selfRefForeignKeyColumnNameToNull)) {
                    log.trace("Forcing column '{}' to NULL for Pass 1 native insert.", columnName);
                    value = null;
                }
                // Handle extracting FK value if it's a JoinColumn (but not the self-ref one being nulled)
                else if (field.isAnnotationPresent(JoinColumn.class) && value != null && !field.getType().isPrimitive() && !field.getType().getPackage().getName().startsWith("java.")) {
                     // Check if value is an entity before trying to get ID
                     Field relatedIdField = findIdField(value.getClass());
                     if (relatedIdField != null) {
                         relatedIdField.setAccessible(true);
                         value = relatedIdField.get(value); // Get the actual foreign key value
                     } else {
                         log.warn("Could not find @Id field on related entity type {} for field '{}'. Using null for column '{}'.", value.getClass().getSimpleName(), field.getName(), columnName);
                         value = null;
                     }
                }

                if (columns.length() > 0) { columns.append(", "); placeholders.append(", "); }
                columns.append("\"").append(columnName).append("\"");
                placeholders.append("?");
                values.add(value);
                processedColumnNames.add(columnNameKey);
            }

            if (columns.length() == 0) { return; }

            String sql = "INSERT INTO \"" + tableName + "\" (" + columns + ") VALUES (" + placeholders + ")";
            log.trace("Executing native SQL: {}", sql);
            Session session = entityManager.unwrap(Session.class);
            session.doWork(connection -> {
                 try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    setPreparedStatementParameters(stmt, values);
                    stmt.executeUpdate();
                } catch (SQLException e) { throw new SQLException("Native SQL insert failed: " + e.getMessage(), e); }
            });

        } else {
            // --- Merge Logic (Handles selfRefForeignKeyColumnNameToNull) ---
            log.trace("Entity {} does not have @GeneratedValue, using entityManager.merge() for ID: {}", entityClass.getSimpleName(), idValue);
             if (selfRefForeignKeyColumnNameToNull != null) {
                  Field fkField = findFieldByColumnName(entityClass, selfRefForeignKeyColumnNameToNull);
                  if (fkField != null) {
                      fkField.setAccessible(true);
                      if (!fkField.getType().isPrimitive()) {
                           log.trace("Setting field '{}' to null before merge for Pass 1.", fkField.getName());
                           fkField.set(entity, null); // Set field on object to null
                      } else { log.warn("Cannot set primitive field '{}' to null before merge.", fkField.getName()); }
                  } else { log.warn("Could not find field for FK column '{}' to set null before merge.", selfRefForeignKeyColumnNameToNull); }
             }
            entityManager.merge(entity); // Merge the potentially modified entity
        }
    }


    // --- Other existing helpers: checkEntityExistsInternal, setPreparedStatementParameters, findIdField, getTableName, getIdColumnName, getAllFields, getColumnNameForField, findField, findFieldByColumnName, convertToFieldType, setProperty ---
    // (Ensure implementations are present and correct)
    private String getColumnNameForField(Field field) { /* ... implementation ... */
        if (field.isAnnotationPresent(jakarta.persistence.Column.class)) { jakarta.persistence.Column columnAnn = field.getAnnotation(jakarta.persistence.Column.class); if (columnAnn != null && !columnAnn.name().isEmpty()) return columnAnn.name(); }
        if (field.isAnnotationPresent(jakarta.persistence.JoinColumn.class)) { jakarta.persistence.JoinColumn joinColumnAnn = field.getAnnotation(jakarta.persistence.JoinColumn.class); if (joinColumnAnn != null && !joinColumnAnn.name().isEmpty()) return joinColumnAnn.name(); }
        return field.getName();
    }
    private Field findField(Class<?> entityClass, String fieldName) { /* ... implementation ... */
         Class<?> currentClass = entityClass;
         while (currentClass != null && currentClass != Object.class) {
             try { return currentClass.getDeclaredField(fieldName); } catch (NoSuchFieldException e) { /* ignore */ }
             currentClass = currentClass.getSuperclass();
         } return null;
    }
     private Field findFieldByColumnName(Class<?> entityClass, String columnName) { /* ... implementation ... */
         List<Field> allFields = getAllFields(entityClass);
         for (Field field : allFields) { if (getColumnNameForField(field).equalsIgnoreCase(columnName)) { return field; } }
         return null;
     }
     private boolean checkEntityExistsInternal(String tableName, String idColumnName, Object idValue) throws SQLException { /* ... implementation ... */
        if (idValue == null) return false;
        String sql = "SELECT COUNT(*) FROM \"" + tableName + "\" WHERE \"" + idColumnName + "\" = ?";
        Session session = entityManager.unwrap(Session.class);
        final boolean[] exists = {false};
        try {
            session.doWork(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    setPreparedStatementParameters(stmt, List.of(idValue));
                    try (ResultSet rs = stmt.executeQuery()) { if (rs.next()) { exists[0] = rs.getLong(1) > 0; } }
                } catch (SQLException e) { throw new SQLException("Existence check failed: " + e.getMessage(), e); }
            });
        } catch (RuntimeException e) { if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause(); else throw e; }
        return exists[0];
     }
     private void setPreparedStatementParameters(PreparedStatement stmt, List<Object> values) throws SQLException { /* ... implementation ... */
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i); int paramIndex = i + 1;
            if (value == null) { stmt.setNull(paramIndex, java.sql.Types.NULL); }
            else if (value instanceof String) { stmt.setString(paramIndex, (String) value); }
            else if (value instanceof Integer) { stmt.setInt(paramIndex, (Integer) value); }
            else if (value instanceof Long) { stmt.setLong(paramIndex, (Long) value); }
            else if (value instanceof Double) { stmt.setDouble(paramIndex, (Double) value); }
            else if (value instanceof Float) { stmt.setFloat(paramIndex, (Float) value); }
            else if (value instanceof Boolean) { stmt.setBoolean(paramIndex, (Boolean) value); }
            else if (value instanceof java.math.BigDecimal) { stmt.setBigDecimal(paramIndex, (java.math.BigDecimal) value); }
            else if (value instanceof java.math.BigInteger) { stmt.setBigDecimal(paramIndex, new java.math.BigDecimal((java.math.BigInteger) value)); }
            else if (value instanceof java.sql.Date) { stmt.setDate(paramIndex, (java.sql.Date) value); }
            else if (value instanceof java.sql.Timestamp) { stmt.setTimestamp(paramIndex, (java.sql.Timestamp) value); }
            else if (value instanceof java.sql.Time) { stmt.setTime(paramIndex, (java.sql.Time) value); }
            else if (value instanceof java.time.LocalDate) { stmt.setDate(paramIndex, java.sql.Date.valueOf((java.time.LocalDate) value)); }
            else if (value instanceof java.time.LocalDateTime) { stmt.setTimestamp(paramIndex, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value)); }
            else if (value instanceof java.time.LocalTime) { stmt.setTime(paramIndex, java.sql.Time.valueOf((java.time.LocalTime) value)); }
            else if (value instanceof java.time.OffsetDateTime) { stmt.setObject(paramIndex, value); }
            else if (value instanceof java.util.Date) { stmt.setTimestamp(paramIndex, new java.sql.Timestamp(((java.util.Date) value).getTime())); }
            else if (value instanceof byte[]) { stmt.setBytes(paramIndex, (byte[]) value); }
            else if (value instanceof java.util.UUID) { stmt.setObject(paramIndex, value); }
            else if (value instanceof Enum) { stmt.setString(paramIndex, ((Enum<?>) value).name()); }
            else { log.warn("Setting parameter {} using setObject for unknown type: {}", paramIndex, value.getClass().getName()); stmt.setObject(paramIndex, value); }
        }
     }
     private Field findIdField(Class<?> entityClass) { /* ... implementation ... */
        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) { for (Field field : currentClass.getDeclaredFields()) { if (field.isAnnotationPresent(Id.class)) { return field; } } currentClass = currentClass.getSuperclass(); } return null;
     }
     private String getTableName(Class<?> entityClass) { /* ... implementation ... */
        if (entityClass.isAnnotationPresent(Table.class)) { Table tableAnnotation = entityClass.getAnnotation(Table.class); if (tableAnnotation != null && !tableAnnotation.name().isEmpty()) { return tableAnnotation.name(); } } return entityClass.getSimpleName();
     }
     private String getIdColumnName(Field idField) { /* ... implementation ... */
        if (idField.isAnnotationPresent(jakarta.persistence.Column.class)) { jakarta.persistence.Column columnAnn = idField.getAnnotation(jakarta.persistence.Column.class); if (columnAnn != null && !columnAnn.name().isEmpty()) { return columnAnn.name(); } } if (idField.isAnnotationPresent(jakarta.persistence.JoinColumn.class)) { jakarta.persistence.JoinColumn joinColumnAnn = idField.getAnnotation(jakarta.persistence.JoinColumn.class); if (joinColumnAnn != null && !joinColumnAnn.name().isEmpty()) { return joinColumnAnn.name(); } } return idField.getName();
     }
     private List<Field> getAllFields(Class<?> clazz) { /* ... implementation ... */
        List<Field> fields = new ArrayList<>(); Class<?> currentClass = clazz; while (currentClass != null && currentClass != Object.class) { for (Field field : currentClass.getDeclaredFields()) { fields.add(field); } currentClass = currentClass.getSuperclass(); } return fields;
     }
     private Object convertToFieldType(Object sourceValue, Class<?> targetTypeOrEntityClass, String targetFieldNameOrNull) throws Exception { /* ... implementation ... */
        if (sourceValue == null) return null; Class<?> targetType; if (targetFieldNameOrNull != null) { Field field = findField(targetTypeOrEntityClass, targetFieldNameOrNull); if (field == null) throw new NoSuchFieldException(targetFieldNameOrNull); targetType = field.getType(); } else { targetType = targetTypeOrEntityClass; }
        Class<?> sourceValueType = sourceValue.getClass(); if (targetType.isAssignableFrom(sourceValueType)) { return sourceValue; }
        try {
            if (Number.class.isAssignableFrom(targetType) || targetType.isPrimitive() && (targetType != boolean.class && targetType != char.class)) { if (sourceValue instanceof Number) { Number sn = (Number) sourceValue; if (targetType == Long.class || targetType == long.class) return sn.longValue(); if (targetType == Integer.class || targetType == int.class) return sn.intValue(); if (targetType == Double.class || targetType == double.class) return sn.doubleValue(); if (targetType == Float.class || targetType == float.class) return sn.floatValue(); if (targetType == Short.class || targetType == short.class) return sn.shortValue(); if (targetType == Byte.class || targetType == byte.class) return sn.byteValue(); if (targetType == java.math.BigDecimal.class) { if (sn instanceof Double || sn instanceof Float) { return new java.math.BigDecimal(sn.toString()); } else { return new java.math.BigDecimal(sn.toString()); } } if (targetType == java.math.BigInteger.class) { if (sn instanceof java.math.BigDecimal) { return ((java.math.BigDecimal)sn).toBigInteger(); } return new java.math.BigInteger(sn.toString()); } } else { String s = sourceValue.toString().trim(); if (targetType == Long.class || targetType == long.class) return Long.parseLong(s); if (targetType == Integer.class || targetType == int.class) return Integer.parseInt(s); if (targetType == Double.class || targetType == double.class) return Double.parseDouble(s); if (targetType == Float.class || targetType == float.class) return Float.parseFloat(s); if (targetType == Short.class || targetType == short.class) return Short.parseShort(s); if (targetType == Byte.class || targetType == byte.class) return Byte.parseByte(s); if (targetType == java.math.BigDecimal.class) return new java.math.BigDecimal(s); if (targetType == java.math.BigInteger.class) return new java.math.BigInteger(s); } }
            else if (targetType == String.class) { return sourceValue.toString(); }
            else if (targetType == Boolean.class || targetType == boolean.class) { if (sourceValue instanceof Boolean) { return sourceValue; } else if (sourceValue instanceof Number) { if (sourceValue instanceof Double || sourceValue instanceof Float || sourceValue instanceof java.math.BigDecimal) { return ((Number) sourceValue).doubleValue() != 0.0; } else { return ((Number) sourceValue).longValue() != 0L; } } else { String s = sourceValue.toString().trim().toLowerCase(); return "true".equals(s) || "1".equals(s) || "t".equals(s) || "y".equals(s); } }
            else if (targetType == java.time.LocalDate.class) { if (sourceValue instanceof java.sql.Date) { return ((java.sql.Date) sourceValue).toLocalDate(); } else if (sourceValue instanceof java.sql.Timestamp) { return ((java.sql.Timestamp) sourceValue).toLocalDateTime().toLocalDate(); } else if (sourceValue instanceof java.time.LocalDateTime) { return ((java.time.LocalDateTime) sourceValue).toLocalDate(); } else if (sourceValue instanceof java.time.LocalDate) { return sourceValue; } }
            else if (targetType == java.time.LocalDateTime.class) { if (sourceValue instanceof java.sql.Timestamp) { return ((java.sql.Timestamp) sourceValue).toLocalDateTime(); } else if (sourceValue instanceof java.sql.Date) { return ((java.sql.Date) sourceValue).toLocalDate().atStartOfDay(); } else if (sourceValue instanceof java.time.LocalDate) { return ((java.time.LocalDate) sourceValue).atStartOfDay(); } else if (sourceValue instanceof java.time.LocalDateTime) { return sourceValue; } }
            else if (targetType == java.time.LocalTime.class) { if (sourceValue instanceof java.sql.Time) { return ((java.sql.Time) sourceValue).toLocalTime(); } else if (sourceValue instanceof java.sql.Timestamp) { return ((java.sql.Timestamp) sourceValue).toLocalDateTime().toLocalTime(); } else if (sourceValue instanceof java.time.LocalDateTime) { return ((java.time.LocalDateTime) sourceValue).toLocalTime(); } else if (sourceValue instanceof java.time.LocalTime) { return sourceValue; } }
            else if (targetType == java.time.OffsetDateTime.class) { if (sourceValue instanceof java.sql.Timestamp) { return ((java.sql.Timestamp) sourceValue).toInstant().atOffset(java.time.ZoneOffset.UTC); } else if (sourceValue instanceof java.time.OffsetDateTime) { return sourceValue; } }
            else if (targetType == java.util.UUID.class && sourceValue instanceof String) { return java.util.UUID.fromString((String)sourceValue); }
            else if (targetType == byte[].class && sourceValue instanceof byte[]) { return sourceValue; }
        } catch (Exception e) { throw new RuntimeException("Cannot convert value for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : targetType.getSimpleName()) + ": " + e.getMessage(), e); }
        throw new IllegalArgumentException("Unsupported type conversion for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : targetType.getSimpleName()) + ": from " + sourceValueType.getName() + " to " + targetType.getName());
     }
     private void setProperty(Object bean, String fieldName, Object value) throws Exception { /* ... implementation ... */
        try{ PropertyUtils.setProperty(bean, fieldName, value); } catch (Exception e){ log.error("Failed to set property '{}' on bean {} with value '{}' (type {}): {}", fieldName, bean.getClass().getSimpleName(), value, (value != null ? value.getClass().getName() : "null"), e.getMessage()); throw e; }
     }

} // End of class