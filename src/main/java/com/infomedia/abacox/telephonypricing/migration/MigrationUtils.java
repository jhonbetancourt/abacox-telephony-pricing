package com.infomedia.abacox.telephonypricing.migration;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.beanutils.PropertyUtils;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

@Log4j2
public class MigrationUtils {

    private MigrationUtils() {
        // Prevent instantiation
    }

    /**
     * Finds the field annotated with @Id in an entity class or its superclasses.
     */
    public static Field findIdField(Class<?> entityClass) {
        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return field;
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }

    /**
     * Gets the table name for an entity class, respecting @Table annotation.
     */
    public static String getTableName(Class<?> entityClass) {
        if (entityClass.isAnnotationPresent(Table.class)) {
            Table tableAnnotation = entityClass.getAnnotation(Table.class);
            if (tableAnnotation != null && !tableAnnotation.name().isEmpty()) {
                return tableAnnotation.name();
            }
        }
        // Fallback to simple class name (consider naming strategy if needed)
        return entityClass.getSimpleName();
    }

    /**
     * Gets the database column name for a given field, respecting @Column and @JoinColumn.
     */
    public static String getColumnNameForField(Field field) {
        if (field.isAnnotationPresent(jakarta.persistence.Column.class)) {
            jakarta.persistence.Column columnAnn = field.getAnnotation(jakarta.persistence.Column.class);
            if (columnAnn != null && !columnAnn.name().isEmpty()) return columnAnn.name();
        }
        if (field.isAnnotationPresent(jakarta.persistence.JoinColumn.class)) {
            jakarta.persistence.JoinColumn joinColumnAnn = field.getAnnotation(jakarta.persistence.JoinColumn.class);
            if (joinColumnAnn != null && !joinColumnAnn.name().isEmpty()) return joinColumnAnn.name();
        }
        // Fallback to field name (consider naming strategy if needed)
        return field.getName();
    }

    /**
     * Gets the database column name for the ID field.
     */
    public static String getIdColumnName(Field idField) {
        return getColumnNameForField(idField);
    }


    /**
     * Gets all declared fields from a class and its superclasses.
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }
        return fields;
    }

    /**
     * Finds a field by name in a class or its superclasses.
     */
     public static Field findField(Class<?> entityClass, String fieldName) {
         Class<?> currentClass = entityClass;
         while (currentClass != null && currentClass != Object.class) {
             try {
                 return currentClass.getDeclaredField(fieldName);
             } catch (NoSuchFieldException e) { /* ignore and check superclass */ }
             currentClass = currentClass.getSuperclass();
         }
         return null;
     }

    /**
     * Finds a field by its mapped database column name.
     */
     public static Field findFieldByColumnName(Class<?> entityClass, String columnName) {
         List<Field> allFields = getAllFields(entityClass);
         for (Field field : allFields) {
             if (getColumnNameForField(field).equalsIgnoreCase(columnName)) {
                 return field;
             }
         }
         return null;
     }

    /**
     * Sets parameters on a PreparedStatement based on their types.
     */
    public static void setPreparedStatementParameters(PreparedStatement stmt, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            int paramIndex = i + 1;
            if (value == null) {
                // Try to determine SQL type if possible, otherwise use generic NULL
                // For simplicity, using generic NULL here. Might need refinement based on DB.
                 stmt.setNull(paramIndex, java.sql.Types.OTHER); // Or Types.NULL / Types.VARCHAR
            } else if (value instanceof String) {
                stmt.setString(paramIndex, (String) value);
            } else if (value instanceof Integer) {
                stmt.setInt(paramIndex, (Integer) value);
            } else if (value instanceof Long) {
                stmt.setLong(paramIndex, (Long) value);
            } else if (value instanceof Double) {
                stmt.setDouble(paramIndex, (Double) value);
            } else if (value instanceof Float) {
                stmt.setFloat(paramIndex, (Float) value);
            } else if (value instanceof Boolean) {
                stmt.setBoolean(paramIndex, (Boolean) value);
            } else if (value instanceof java.math.BigDecimal) {
                stmt.setBigDecimal(paramIndex, (java.math.BigDecimal) value);
            } else if (value instanceof java.math.BigInteger) {
                stmt.setBigDecimal(paramIndex, new java.math.BigDecimal((java.math.BigInteger) value));
            } else if (value instanceof java.sql.Date) {
                stmt.setDate(paramIndex, (java.sql.Date) value);
            } else if (value instanceof java.sql.Timestamp) {
                stmt.setTimestamp(paramIndex, (java.sql.Timestamp) value);
            } else if (value instanceof java.sql.Time) {
                stmt.setTime(paramIndex, (java.sql.Time) value);
            } else if (value instanceof java.time.LocalDate) {
                stmt.setDate(paramIndex, java.sql.Date.valueOf((java.time.LocalDate) value));
            } else if (value instanceof java.time.LocalDateTime) {
                stmt.setTimestamp(paramIndex, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value));
            } else if (value instanceof java.time.LocalTime) {
                stmt.setTime(paramIndex, java.sql.Time.valueOf((java.time.LocalTime) value));
            } else if (value instanceof java.time.OffsetDateTime) {
                 // JDBC 4.2+ standard mapping
                stmt.setObject(paramIndex, value);
            } else if (value instanceof java.util.Date) { // Generic Date -> Timestamp
                stmt.setTimestamp(paramIndex, new java.sql.Timestamp(((java.util.Date) value).getTime()));
            } else if (value instanceof byte[]) {
                stmt.setBytes(paramIndex, (byte[]) value);
            } else if (value instanceof java.util.UUID) {
                stmt.setObject(paramIndex, value); // Standard mapping
            } else if (value instanceof Enum) {
                stmt.setString(paramIndex, ((Enum<?>) value).name()); // Store as string by default
            } else {
                log.warn("Setting parameter {} using setObject as default for unknown type: {}", paramIndex, value.getClass().getName());
                stmt.setObject(paramIndex, value);
            }
        }
    }

    /**
     * Converts a source value to the type required by a target field.
     */
    public static Object convertToFieldType(Object sourceValue, Class<?> targetTypeOrEntityClass, String targetFieldNameOrNull) throws Exception {
        if (sourceValue == null) return null;

        Class<?> targetType;
        if (targetFieldNameOrNull != null) {
            Field field = findField(targetTypeOrEntityClass, targetFieldNameOrNull);
            if (field == null) throw new NoSuchFieldException("Cannot find field '" + targetFieldNameOrNull + "' in class " + targetTypeOrEntityClass.getName());
            targetType = field.getType();
        } else {
            // If field name is null, assume targetTypeOrEntityClass *is* the target type
            targetType = targetTypeOrEntityClass;
        }

        Class<?> sourceValueType = sourceValue.getClass();

        // Direct assignment if compatible
        if (targetType.isAssignableFrom(sourceValueType)) {
            return sourceValue;
        }

        try {
            // --- Numeric Conversions ---
            if (Number.class.isAssignableFrom(targetType) || (targetType.isPrimitive() && targetType != boolean.class && targetType != char.class)) {
                if (sourceValue instanceof Number) {
                    Number sn = (Number) sourceValue;
                    if (targetType == Long.class || targetType == long.class) return sn.longValue();
                    if (targetType == Integer.class || targetType == int.class) return sn.intValue();
                    if (targetType == Double.class || targetType == double.class) return sn.doubleValue();
                    if (targetType == Float.class || targetType == float.class) return sn.floatValue();
                    if (targetType == Short.class || targetType == short.class) return sn.shortValue();
                    if (targetType == Byte.class || targetType == byte.class) return sn.byteValue();
                    if (targetType == java.math.BigDecimal.class) {
                        return new java.math.BigDecimal(sn.toString()); // Use string constructor for precision
                    }
                    if (targetType == java.math.BigInteger.class) {
                        if (sn instanceof java.math.BigDecimal) {
                            return ((java.math.BigDecimal) sn).toBigInteger();
                        } else {
                            return new java.math.BigInteger(String.valueOf(sn.longValue()));
                        }
                    }
                } else { // Try conversion from String if source wasn't a number
                    String s = sourceValue.toString().trim();
                    if (s.isEmpty()) return null; // Treat empty string as null for numeric types
                    if (targetType == Long.class || targetType == long.class) return Long.parseLong(s);
                    if (targetType == Integer.class || targetType == int.class) return Integer.parseInt(s);
                    if (targetType == Double.class || targetType == double.class) return Double.parseDouble(s);
                    if (targetType == Float.class || targetType == float.class) return Float.parseFloat(s);
                    if (targetType == Short.class || targetType == short.class) return Short.parseShort(s);
                    if (targetType == Byte.class || targetType == byte.class) return Byte.parseByte(s);
                    if (targetType == java.math.BigDecimal.class) return new java.math.BigDecimal(s);
                    if (targetType == java.math.BigInteger.class) return new java.math.BigInteger(s);
                }
            }
            // --- String Conversion ---
            else if (targetType == String.class) {
                return sourceValue.toString();
            }
            // --- Boolean Conversion ---
            else if (targetType == Boolean.class || targetType == boolean.class) {
                if (sourceValue instanceof Boolean) {
                    return sourceValue;
                } else if (sourceValue instanceof Number) {
                    // Treat 0 as false, non-zero as true
                    if (sourceValue instanceof Double || sourceValue instanceof Float || sourceValue instanceof java.math.BigDecimal) {
                        return ((Number) sourceValue).doubleValue() != 0.0;
                    } else {
                        return ((Number) sourceValue).longValue() != 0L;
                    }
                } else {
                    String s = sourceValue.toString().trim().toLowerCase();
                    return "true".equals(s) || "1".equals(s) || "t".equals(s) || "y".equals(s) || "on".equals(s);
                }
            }
            // --- Date/Time Conversions (Add more as needed) ---
            else if (targetType == java.time.LocalDate.class) {
                if (sourceValue instanceof java.sql.Date) {
                    return ((java.sql.Date) sourceValue).toLocalDate();
                } else if (sourceValue instanceof java.sql.Timestamp) {
                    return ((java.sql.Timestamp) sourceValue).toLocalDateTime().toLocalDate();
                } else if (sourceValue instanceof java.time.LocalDateTime) {
                    return ((java.time.LocalDateTime) sourceValue).toLocalDate();
                } // Add String parsing if necessary
            } else if (targetType == java.time.LocalDateTime.class) {
                if (sourceValue instanceof java.sql.Timestamp) {
                    return ((java.sql.Timestamp) sourceValue).toLocalDateTime();
                } else if (sourceValue instanceof java.sql.Date) {
                    return ((java.sql.Date) sourceValue).toLocalDate().atStartOfDay();
                } // Add String parsing if necessary
            }
            // --- UUID Conversion ---
             else if (targetType == java.util.UUID.class && sourceValue instanceof String) {
                 try {
                     return java.util.UUID.fromString((String) sourceValue);
                 } catch (IllegalArgumentException e) {
                     throw new RuntimeException("Invalid UUID format for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : targetType.getSimpleName()) + ": " + sourceValue, e);
                 }
             }
             // --- Enum Conversion (assuming source is String name) ---
              else if (targetType.isEnum() && sourceValue instanceof String) {
                 try {
                     @SuppressWarnings({"unchecked", "rawtypes"}) // Necessary for Enum.valueOf
                     Enum val = Enum.valueOf((Class<Enum>) targetType, (String) sourceValue);
                     return val;
                 } catch (IllegalArgumentException e) {
                     log.warn("Could not find enum constant '{}' in enum {} for field {}. Returning null.",
                              sourceValue, targetType.getSimpleName(), targetFieldNameOrNull);
                     return null; // Or throw error depending on requirements
                 }
              }
             // --- byte[] ---
              else if (targetType == byte[].class && sourceValue instanceof byte[]) {
                 return sourceValue;
             }

        } catch (NumberFormatException nfe) {
             throw new RuntimeException("Cannot convert value '" + sourceValue + "' to numeric type " + targetType.getSimpleName() + " for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : "") + ": " + nfe.getMessage(), nfe);
        } catch (Exception e) {
            // Catch-all for other conversion issues
            throw new RuntimeException("Cannot convert value for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : targetType.getSimpleName()) + ": " + e.getMessage(), e);
        }

        // If no conversion path found
        throw new IllegalArgumentException("Unsupported type conversion required for field " + (targetFieldNameOrNull != null ? targetFieldNameOrNull : targetType.getSimpleName()) + ": from " + sourceValueType.getName() + " to " + targetType.getName() + ". Value: '" + sourceValue + "'");
    }

    /**
     * Sets a property on a bean using Apache Commons BeanUtils.
     */
    public static void setProperty(Object bean, String fieldName, Object value) throws Exception {
        try {
            // PropertyUtils handles type conversion for standard types to some extent
            PropertyUtils.setProperty(bean, fieldName, value);
        } catch (Exception e) {
            log.error("Failed to set property '{}' on bean {} with value '{}' (type {}): {}",
                      fieldName, bean.getClass().getSimpleName(), value,
                      (value != null ? value.getClass().getName() : "null"), e.getMessage());
            // Rethrow to indicate failure
            throw e;
        }
    }


    // --- Foreign Key Inference Logic ---

    /**
     * Infers foreign key information from JPA annotations.
     */
    public static Map<String, ForeignKeyInfo> inferForeignKeyInfo(Class<?> entityClass) {
        Map<String, ForeignKeyInfo> fkMap = new HashMap<>();
        List<Field> allFields = getAllFields(entityClass); // Use own utility method

        for (Field field : allFields) {
            field.setAccessible(true);
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
            OneToOne oneToOne = field.getAnnotation(OneToOne.class);

            Field relationshipField = null; // The field holding the actual entity relationship (e.g., parentSubdivision)
            Field foreignKeyIdField = null; // The field holding the FK value (e.g., parentSubdivisionId)

            // Scenario 1: Field has @JoinColumn and is a relationship type (@ManyToOne/@OneToOne)
            if (joinColumn != null && (manyToOne != null || oneToOne != null)) {
                relationshipField = field;
                String potentialIdFieldName = derivePotentialIdFieldName(field.getName());
                foreignKeyIdField = findField(entityClass, potentialIdFieldName); // Use own utility
                if (foreignKeyIdField == null && (field.getType() == Long.class || field.getType() == long.class || field.getType() == Integer.class || field.getType() == int.class /* add other ID types */)) {
                   // Maybe JoinColumn is on a simple ID field that ALSO has @ManyToOne (less common)
                   // Or maybe the convention failed. For now, we prioritise finding a separate ID field.
                   log.trace("FK Inference: Relationship field '{}' has @JoinColumn but couldn't find separate ID field '{}'. Assuming FK ID is managed implicitly by JPA or mapped elsewhere.", field.getName(), potentialIdFieldName);
                   continue; // Skip this relationship field for explicit FK ID mapping
                }
                // If we found a separate ID field, proceed. If not, we don't have the FK ID field here.
            }
            // Scenario 2: Field is likely the FK ID field itself and has @JoinColumn
            else if (joinColumn != null && !(manyToOne != null || oneToOne != null)) {
                foreignKeyIdField = field;
                String potentialRelationshipFieldName = derivePotentialRelationshipFieldName(field.getName());
                relationshipField = findField(entityClass, potentialRelationshipFieldName); // Use own utility
            }
            // Scenario 3: Field might be the FK ID field without @JoinColumn, relying on relationship field's @JoinColumn
            else if (joinColumn == null && !(manyToOne != null || oneToOne != null)) {
                for (Field otherField : allFields) {
                    if (otherField != field && otherField.isAnnotationPresent(JoinColumn.class) && (otherField.isAnnotationPresent(ManyToOne.class) || otherField.isAnnotationPresent(OneToOne.class))) {
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
                    // If name is missing in @JoinColumn, try getting it from @Column on the ID field
                    dbColumnName = getColumnNameForField(foreignKeyIdField); // Use own utility
                    log.trace("Using inferred/default column name '{}' for FK field '{}'. Explicit name in @JoinColumn is recommended.",
                            dbColumnName, foreignKeyIdField.getName());
                }

                Class<?> fkFieldType = foreignKeyIdField.getType();
                Class<?> targetEntityType = null; // The entity type the FK points TO

                if (relationshipField != null) {
                    targetEntityType = relationshipField.getType(); // Type of the @ManyToOne/OneToOne field
                } else if (manyToOne != null && field == foreignKeyIdField) { // If @JoinColumn and @ManyToOne are on the same field
                    targetEntityType = field.getType(); // The type of the field itself must be the target entity
                } else if (oneToOne != null && field == foreignKeyIdField) { // If @JoinColumn and @OneToOne are on the same field
                     targetEntityType = field.getType();
                } else {
                     // Attempt to get targetEntity from @ManyToOne/@OneToOne even if relationshipField wasn't found above
                     if(manyToOne != null && manyToOne.targetEntity() != void.class) {
                         targetEntityType = manyToOne.targetEntity();
                     } else if (oneToOne != null && oneToOne.targetEntity() != void.class) {
                         targetEntityType = oneToOne.targetEntity();
                     } else {
                         log.warn("Could not determine target entity type for FK field '{}' (Column: {}). Self-reference check may be inaccurate.",
                                  foreignKeyIdField.getName(), dbColumnName);
                     }
                }


                boolean isSelfRef = targetEntityType != null && targetEntityType == entityClass;

                ForeignKeyInfo info = new ForeignKeyInfo(foreignKeyIdField, dbColumnName, fkFieldType, isSelfRef, relationshipField);
                fkMap.put(foreignKeyIdField.getName(), info); // Map by the FK *ID* field name
                log.trace("Inferred FK: Field='{}', Column='{}', Type='{}', SelfRef={}, RelField='{}', TargetEntity='{}'",
                        foreignKeyIdField.getName(), dbColumnName, fkFieldType.getSimpleName(), isSelfRef,
                        relationshipField != null ? relationshipField.getName() : "N/A",
                        targetEntityType != null ? targetEntityType.getSimpleName() : "UNKNOWN");
            }
        }
        return fkMap;
    }

    // Helper to guess potential ID field name from relationship field name
    private static String derivePotentialIdFieldName(String relationshipFieldName) {
        // Simple convention: append "Id"
        if (relationshipFieldName != null && !relationshipFieldName.isEmpty()) {
            // Handle common patterns like ending in "s" for collections (though less common for ToOne)
             if (relationshipFieldName.endsWith("s")) {
                // Crude plural removal - might need improvement
                // return relationshipFieldName.substring(0, relationshipFieldName.length() - 1) + "Id";
             }
            return relationshipFieldName + "Id";
        }
        return null;
    }

    // Helper to guess potential relationship field name from ID field name
    private static String derivePotentialRelationshipFieldName(String idFieldName) {
        // Simple convention: remove trailing "Id" if present
        if (idFieldName != null && idFieldName.toLowerCase().endsWith("id") && idFieldName.length() > 2) {
            return idFieldName.substring(0, idFieldName.length() - 2);
        }
        return null;
    }

    /**
     * Finds the first self-referencing FK from the map.
     */
    public static ForeignKeyInfo findSelfReference(Map<String, ForeignKeyInfo> fkMap, Class<?> entityClass) {
        return fkMap.values().stream()
                .filter(ForeignKeyInfo::isSelfReference)
                .findFirst()
                .orElse(null);
    }
}