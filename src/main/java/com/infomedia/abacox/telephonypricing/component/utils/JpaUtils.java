package com.infomedia.abacox.telephonypricing.component.utils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for JPA/database operations that extend beyond standard JPA functionality.
 */
public class JpaUtils {

    private static final Logger log = LoggerFactory.getLogger(JpaUtils.class);

    /**
     * Private constructor to prevent instantiation of utility class
     */
    private JpaUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Helper method to get all fields from a class and its superclasses
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                fields.add(field);
            }
            currentClass = currentClass.getSuperclass();
        }
        
        return fields;
    }

    /**
     * Saves an entity to the database preserving its current ID value, even if the entity uses auto-generated IDs.
     * This bypasses any ID generation strategies defined on the entity.
     * 
     * @param entity The entity to save with its ID already set
     * @param entityManager JPA EntityManager
     * @param <T> The entity type
     * @return The saved entity
     * @throws IllegalArgumentException If entity has no ID field or the ID is not set
     * @throws RuntimeException If an error occurs during saving
     */
    public static <T> T saveEntityWithForcedId(T entity, EntityManager entityManager) {
        Class<?> entityClass = entity.getClass();
        
        try {
            // Find the ID field and its current value
            Field idField = null;
            Object idValue = null;
            
            // Look for ID field in the class hierarchy
            Class<?> currentClass = entityClass;
            while (currentClass != null && idField == null) {
                for (Field field : currentClass.getDeclaredFields()) {
                    field.setAccessible(true);
                    if (field.isAnnotationPresent(Id.class)) {
                        idField = field;
                        idValue = field.get(entity);
                        break;
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            
            if (idField == null) {
                throw new IllegalArgumentException("Entity class does not have an @Id field");
            }
            
            if (idValue == null) {
                throw new IllegalArgumentException("ID value must be set on the entity before saving");
            }
            
            log.debug("Saving entity of type {} with forced ID value: {}", entityClass.getSimpleName(), idValue);
            
            // Check if the ID field has @GeneratedValue
            boolean hasAutoGeneratedId = idField.isAnnotationPresent(GeneratedValue.class);
            
            // For entities with auto-generated IDs, use native SQL
            if (hasAutoGeneratedId) {
                log.debug("Entity has auto-generated ID, using direct SQL to preserve ID value");
                
                // Get the table name
                String tableName = entityClass.getSimpleName();
                if (entityClass.isAnnotationPresent(Table.class)) {
                    Table tableAnnotation = entityClass.getAnnotation(Table.class);
                    if (!tableAnnotation.name().isEmpty()) {
                        tableName = tableAnnotation.name();
                    }
                }
                
                // Build column list and values for SQL insert
                StringBuilder columns = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                List<Object> values = new ArrayList<>();
                
                // Process all fields including inherited ones
                List<Field> allFields = getAllFields(entityClass);
                for (Field field : allFields) {
                    field.setAccessible(true);
                    
                    // Skip static or transient fields
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                        java.lang.reflect.Modifier.isTransient(field.getModifiers()) ||
                        field.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                        continue;
                    }
                    
                    // Get column name from annotation or use field name
                    String columnName = field.getName();
                    if (field.isAnnotationPresent(jakarta.persistence.Column.class)) {
                        jakarta.persistence.Column columnAnn = field.getAnnotation(jakarta.persistence.Column.class);
                        if (!columnAnn.name().isEmpty()) {
                            columnName = columnAnn.name();
                        }
                    } else if (field.equals(idField)) {
                        // Handle ID column name differently if needed
                        if (field.isAnnotationPresent(jakarta.persistence.JoinColumn.class)) {
                            jakarta.persistence.JoinColumn joinColumn = field.getAnnotation(jakarta.persistence.JoinColumn.class);
                            if (!joinColumn.name().isEmpty()) {
                                columnName = joinColumn.name();
                            }
                        }
                    }
                    
                    Object value = field.get(entity);
                    
                    // Add column and value if not null or if it's the ID field
                    if (value != null || field.equals(idField)) {
                        if (columns.length() > 0) {
                            columns.append(", ");
                            placeholders.append(", ");
                        }
                        
                        columns.append(columnName);
                        placeholders.append("?");
                        values.add(value);
                    }
                }
                
                // Construct SQL statement
                String sql = "INSERT INTO " + tableName + " (" + columns + ") VALUES (" + placeholders + ")";
                log.debug("Executing SQL: {}", sql);
                
                // Get native connection and execute SQL
                Session session = entityManager.unwrap(Session.class);
                session.doWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        // Set parameters
                        for (int i = 0; i < values.size(); i++) {
                            Object value = values.get(i);
                            if (value == null) {
                                stmt.setNull(i + 1, java.sql.Types.NULL);
                            } else if (value instanceof String) {
                                stmt.setString(i + 1, (String) value);
                            } else if (value instanceof Integer) {
                                stmt.setInt(i + 1, (Integer) value);
                            } else if (value instanceof Long) {
                                stmt.setLong(i + 1, (Long) value);
                            } else if (value instanceof Double) {
                                stmt.setDouble(i + 1, (Double) value);
                            } else if (value instanceof Float) {
                                stmt.setFloat(i + 1, (Float) value);
                            } else if (value instanceof Boolean) {
                                stmt.setBoolean(i + 1, (Boolean) value);
                            } else if (value instanceof java.math.BigDecimal) {
                                stmt.setBigDecimal(i + 1, (java.math.BigDecimal) value);
                            } else if (value instanceof java.math.BigInteger) {
                                stmt.setBigDecimal(i + 1, new java.math.BigDecimal((java.math.BigInteger) value));
                            } else if (value instanceof java.util.Date) {
                                stmt.setDate(i + 1, new java.sql.Date(((java.util.Date) value).getTime()));
                            } else if (value instanceof java.time.LocalDate) {
                                stmt.setDate(i + 1, java.sql.Date.valueOf((java.time.LocalDate) value));
                            } else if (value instanceof java.time.LocalDateTime) {
                                stmt.setTimestamp(i + 1, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value));
                            } else if (value instanceof Enum) {
                                stmt.setString(i + 1, ((Enum<?>) value).name());
                            } else {
                                // Default to string representation
                                stmt.setString(i + 1, value.toString());
                            }
                        }
                        
                        stmt.executeUpdate();
                    }
                });
                
                // Return the entity with the preserved ID
                return entity;
            } else {
                // For entities without auto-generated IDs, we can simply use EntityManager
                log.debug("Entity does not have auto-generated ID, using standard JPA persist/merge");
                return entityManager.merge(entity);
            }
        } catch (Exception e) {
            log.error("Error saving entity with forced ID: {}", e.getMessage(), e);
            throw new RuntimeException("Error saving entity with forced ID", e);
        }
    }
    
    /**
     * Batch saves entities to the database preserving their current ID values.
     * 
     * @param entities List of entities to save
     * @param entityManager JPA EntityManager 
     * @param <T> The entity type
     * @return List of saved entities
     */
    public static <T> List<T> batchSaveEntitiesWithForcedIds(List<T> entities, EntityManager entityManager) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<T> savedEntities = new ArrayList<>(entities.size());
        for (T entity : entities) {
            savedEntities.add(saveEntityWithForcedId(entity, entityManager));
        }
        
        return savedEntities;
    }
}