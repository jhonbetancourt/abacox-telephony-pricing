package com.infomedia.abacox.telephonypricing.component.csv;

import com.opencsv.CSVReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Spring component that handles loading CSV data into database entities.
 * All required dependencies are auto-injected.
 */
@Component
public class CsvToDatabaseLoader {

    private static final Logger log = LoggerFactory.getLogger(CsvToDatabaseLoader.class);
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    /**
     * Constructor with required dependencies injected by Spring
     */
    @Autowired
    public CsvToDatabaseLoader(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Loads data from a CSV file into a database table mapped to the entity class
     * 
     * @param csvFilePath Path to the CSV file
     * @param entityClass The entity class that maps to the database table
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromCsvFile(String csvFilePath, Class<T> entityClass) throws Exception {
        log.info("Loading CSV data from file: {} into entity: {}", csvFilePath, entityClass.getName());
        try (FileReader reader = new FileReader(csvFilePath)) {
            loadFromReader(reader, entityClass, false, null);
        }
    }
    
    /**
     * Loads data from a CSV file into a database table mapped to the entity class with custom column mappings
     * 
     * @param csvFilePath Path to the CSV file
     * @param entityClass The entity class that maps to the database table
     * @param columnToFieldMapping Map of CSV column names to entity field names
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromCsvFile(String csvFilePath, Class<T> entityClass, Map<String, String> columnToFieldMapping) throws Exception {
        log.info("Loading CSV data from file: {} into entity: {} with custom mapping", csvFilePath, entityClass.getName());
        try (FileReader reader = new FileReader(csvFilePath)) {
            loadFromReader(reader, entityClass, false, columnToFieldMapping);
        }
    }
    
    /**
     * Loads data from a CSV input stream into a database table mapped to the entity class
     * 
     * @param inputStream InputStream containing CSV data
     * @param entityClass The entity class that maps to the database table
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromInputStream(InputStream inputStream, Class<T> entityClass) throws Exception {
        log.info("Loading CSV data from input stream into entity: {}", entityClass.getName());
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            loadFromReader(reader, entityClass, false, null);
        }
    }
    
    /**
     * Loads data from a CSV input stream into a database table mapped to the entity class with custom column mappings
     * 
     * @param inputStream InputStream containing CSV data
     * @param entityClass The entity class that maps to the database table
     * @param columnToFieldMapping Map of CSV column names to entity field names
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromInputStream(InputStream inputStream, Class<T> entityClass, Map<String, String> columnToFieldMapping) throws Exception {
        log.info("Loading CSV data from input stream into entity: {} with custom mapping", entityClass.getName());
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            loadFromReader(reader, entityClass, false, columnToFieldMapping);
        }
    }
    
    /**
     * Loads data from a CSV input stream into a database table mapped to the entity class,
     * forcing the use of IDs from the CSV even if the entity has auto-generated IDs.
     * 
     * @param inputStream InputStream containing CSV data
     * @param entityClass The entity class that maps to the database table
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromInputStreamForceIds(InputStream inputStream, Class<T> entityClass) throws Exception {
        log.info("Loading CSV data from input stream into entity: {} (Forcing IDs from CSV)", entityClass.getName());
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            loadFromReader(reader, entityClass, true, null);
        }
    }
    
    /**
     * Loads data from a CSV input stream into a database table mapped to the entity class,
     * forcing the use of IDs from the CSV even if the entity has auto-generated IDs.
     * 
     * @param inputStream InputStream containing CSV data
     * @param entityClass The entity class that maps to the database table
     * @param columnToFieldMapping Map of CSV column names to entity field names
     * @throws Exception If any error occurs during processing
     */
    public <T> void loadFromInputStreamForceIds(InputStream inputStream, Class<T> entityClass, Map<String, String> columnToFieldMapping) throws Exception {
        log.info("Loading CSV data from input stream into entity: {} (Forcing IDs from CSV) with custom mapping", entityClass.getName());
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            loadFromReader(reader, entityClass, true, columnToFieldMapping);
        }
    }
    
    /**
     * Core method that loads data from a CSV reader into a database using TransactionTemplate
     * 
     * @param reader Reader providing CSV data
     * @param entityClass The entity class that maps to the database table
     * @param forceIdsFromCsv If true, forces the use of IDs from the CSV, even for auto-generated IDs
     * @param customColumnMapping Optional map of CSV column names to entity field names
     * @throws Exception If any error occurs during processing
     */
    private <T> void loadFromReader(Reader reader, Class<T> entityClass, boolean forceIdsFromCsv, Map<String, String> customColumnMapping) throws Exception {
        try (CSVReader csvReader = new CSVReader(reader)) {
            // Read header row to get column names
            final String[] headers = csvReader.readNext();
            if (headers == null) {
                throw new IllegalArgumentException("CSV file is empty or has no headers");
            }
            
            log.debug("CSV headers: {}", String.join(", ", headers));
            
            // Get table name for native SQL
            String tableName = entityClass.getSimpleName();
            if (entityClass.isAnnotationPresent(Table.class)) {
                Table tableAnnotation = entityClass.getAnnotation(Table.class);
                if (!tableAnnotation.name().isEmpty()) {
                    tableName = tableAnnotation.name();
                }
            }
            
            // Create a map of column names to field names
            final Map<String, Field> columnToFieldMap = new HashMap<>();
            
            // Get all fields including inherited fields
            List<Field> allFields = getAllFields(entityClass);
            
            // Find the ID field and check if it has @GeneratedValue
            Optional<Field> idField = Optional.empty();
            boolean hasAutoGeneratedId = false;
            
            for (Field field : allFields) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(Id.class)) {
                    idField = Optional.of(field);
                    if (field.isAnnotationPresent(GeneratedValue.class)) {
                        hasAutoGeneratedId = true;
                        log.info("Entity {} has auto-generated ID field: {}", 
                                entityClass.getSimpleName(), field.getName());
                    }
                    break;
                }
            }
            
            // Map fields to columns based on either custom mapping or field name matching
            Map<String, String> dbColumnNames = new HashMap<>();
            List<String> fieldNames = new ArrayList<>();
            
            Map<String, Field> fieldsByName = new HashMap<>();
            for (Field field : allFields) {
                field.setAccessible(true);
                fieldsByName.put(field.getName(), field);
            }
            
            for (String header : headers) {
                String normalizedHeader = header.trim();
                
                if (customColumnMapping != null && customColumnMapping.containsKey(normalizedHeader)) {
                    // Use custom mapping if provided
                    String fieldName = customColumnMapping.get(normalizedHeader);
                    if (fieldsByName.containsKey(fieldName)) {
                        Field field = fieldsByName.get(fieldName);
                        columnToFieldMap.put(header, field);
                        
                        // Get the database column name (needed for native SQL)
                        String dbColumnName = field.getName();
                        if (field.isAnnotationPresent(jakarta.persistence.Column.class)) {
                            jakarta.persistence.Column columnAnn = field.getAnnotation(jakarta.persistence.Column.class);
                            if (!columnAnn.name().isEmpty()) {
                                dbColumnName = columnAnn.name();
                            }
                        }
                        
                        dbColumnNames.put(header, dbColumnName);
                        fieldNames.add(field.getName());
                        
                        log.debug("Mapped CSV column '{}' to entity field '{}' using custom mapping (DB column: '{}')", 
                                 header, field.getName(), dbColumnName);
                    } else {
                        log.warn("Custom mapping for CSV column '{}' specified field '{}' which does not exist in entity",
                                normalizedHeader, fieldName);
                    }
                } else {
                    // Try direct matching when no custom mapping is provided
                    for (Field field : allFields) {
                        if (field.getName().equalsIgnoreCase(normalizedHeader)) {
                            columnToFieldMap.put(header, field);
                            
                            // Get the database column name (needed for native SQL)
                            String dbColumnName = field.getName();
                            if (field.isAnnotationPresent(jakarta.persistence.Column.class)) {
                                jakarta.persistence.Column columnAnn = field.getAnnotation(jakarta.persistence.Column.class);
                                if (!columnAnn.name().isEmpty()) {
                                    dbColumnName = columnAnn.name();
                                }
                            }
                            
                            dbColumnNames.put(header, dbColumnName);
                            fieldNames.add(field.getName());
                            
                            log.debug("Mapped CSV column '{}' to entity field '{}' (DB column: '{}')", 
                                     header, field.getName(), dbColumnName);
                            break;
                        }
                    }
                }
            }
            
            if (columnToFieldMap.isEmpty()) {
                throw new IllegalArgumentException("No matching fields found between CSV headers and entity class");
            }
            
            log.info("Found {} matching fields between CSV and entity", columnToFieldMap.size());
            
            final Field finalIdField = idField.orElse(null);
            final boolean finalHasAutoGeneratedId = hasAutoGeneratedId;
            final String finalTableName = tableName;
            
            // Process the CSV in a single transaction
            transactionTemplate.execute(status -> {
                try {
                    String[] line;
                    int count = 0;
                    
                    // If we need to force IDs from CSV and entity has auto-generated IDs, use direct SQL insert
                    boolean useNativeSql = forceIdsFromCsv && finalHasAutoGeneratedId;
                    
                    if (useNativeSql) {
                        log.info("Using native SQL to force IDs from CSV for entity with auto-generated IDs");
                        
                        // Build the SQL insert statement
                        StringBuilder sql = new StringBuilder();
                        sql.append("INSERT INTO ").append(finalTableName).append(" (");
                        
                        for (int i = 0; i < headers.length; i++) {
                            if (columnToFieldMap.containsKey(headers[i])) {
                                if (i > 0) {
                                    sql.append(", ");
                                }
                                sql.append(dbColumnNames.get(headers[i]));
                            }
                        }
                        
                        sql.append(") VALUES (");
                        
                        for (int i = 0, paramCount = 0; i < headers.length; i++) {
                            if (columnToFieldMap.containsKey(headers[i])) {
                                if (paramCount > 0) {
                                    sql.append(", ");
                                }
                                sql.append("?");
                                paramCount++;
                            }
                        }
                        
                        sql.append(")");
                        
                        // Get native connection from Hibernate
                        Session session = entityManager.unwrap(Session.class);
                        Connection connection = session.doReturningWork(conn -> conn);
                        
                        // Use native JDBC for inserts
                        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                            while ((line = csvReader.readNext()) != null) {
                                int paramIndex = 1;
                                
                                // Set parameters for the SQL statement
                                for (int i = 0; i < headers.length; i++) {
                                    if (i < line.length && columnToFieldMap.containsKey(headers[i])) {
                                        Field field = columnToFieldMap.get(headers[i]);
                                        String value = line[i];
                                        
                                        if (value != null) {
                                            // For String fields, use empty string for empty values
                                            // For other types, null for empty values
                                            if (value.trim().isEmpty() && String.class.equals(field.getType())) {
                                                stmt.setString(paramIndex++, "");
                                            } else if (!value.trim().isEmpty()) {
                                                // Convert and set the parameter
                                                Object convertedValue = convertStringToFieldType(value.trim(), field.getType());
                                                setStatementParameter(stmt, paramIndex++, convertedValue);
                                            } else {
                                                // Set null for empty values in non-string fields
                                                stmt.setNull(paramIndex++, java.sql.Types.NULL);
                                            }
                                        } else {
                                            // Handle null values from CSV
                                            if (String.class.equals(field.getType())) {
                                                stmt.setString(paramIndex++, "");
                                            } else {
                                                stmt.setNull(paramIndex++, java.sql.Types.NULL);
                                            }
                                        }
                                    }
                                }
                                
                                // Execute the insert
                                stmt.executeUpdate();
                                count++;
                                
                                if (count % DEFAULT_BATCH_SIZE == 0) {
                                    log.info("Processed {} records", count);
                                }
                            }
                        }
                    } else {
                        // Use standard JPA approach
                        while ((line = csvReader.readNext()) != null) {
                            T entity = entityClass.getDeclaredConstructor().newInstance();
                            
                            // Map each column to the corresponding field
                            for (int i = 0; i < headers.length; i++) {
                                if (i < line.length && columnToFieldMap.containsKey(headers[i])) {
                                    Field field = columnToFieldMap.get(headers[i]);
                                    String value = line[i];
                                    
                                    // For String fields, use empty string for empty values
                                    if (value != null) {
                                        if (value.trim().isEmpty() && String.class.equals(field.getType())) {
                                            field.set(entity, "");
                                        } else if (!value.trim().isEmpty()) {
                                            // Convert string value to the appropriate type
                                            Object convertedValue = convertStringToFieldType(value.trim(), field.getType());
                                            field.set(entity, convertedValue);
                                        }
                                    } else if (String.class.equals(field.getType())) {
                                        // For null values in String fields
                                        field.set(entity, "");
                                    }
                                }
                            }
                            
                            // Use merge which handles both insert and update
                            entityManager.merge(entity);
                            count++;
                            
                            if (count % DEFAULT_BATCH_SIZE == 0) {
                                entityManager.flush();
                                entityManager.clear();
                                log.info("Processed {} records", count);
                            }
                        }
                    }
                    
                    log.info("Total records processed: {}", count);
                    return null;
                } catch (Exception e) {
                    log.error("Error processing CSV data: {}", e.getMessage(), e);
                    status.setRollbackOnly();
                    throw new RuntimeException("Error processing CSV data", e);
                }
            });
        }
    }

    /**
     * Gets all fields from a class, including inherited fields from superclasses.
     *
     * @param type The class to get fields from
     * @return List of all fields
     */
    private List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        
        // Get fields from current class
        fields.addAll(List.of(type.getDeclaredFields()));
        
        // Get fields from all superclasses
        Class<?> superClass = type.getSuperclass();
        while (superClass != null && !superClass.equals(Object.class)) {
            fields.addAll(List.of(superClass.getDeclaredFields()));
            superClass = superClass.getSuperclass();
        }
        
        return fields;
    }

    /**
     * Sets a parameter on a PreparedStatement with the correct type
     *
     * @param stmt The PreparedStatement
     * @param paramIndex The parameter index
     * @param value The value to set
     * @throws Exception If any error occurs
     */
    private void setStatementParameter(PreparedStatement stmt, int paramIndex, Object value) throws Exception {
        if (value == null) {
            stmt.setNull(paramIndex, java.sql.Types.NULL);
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
        } else if (value instanceof java.util.Date) {
            stmt.setDate(paramIndex, new java.sql.Date(((java.util.Date) value).getTime()));
        } else if (value instanceof java.time.LocalDate) {
            stmt.setDate(paramIndex, java.sql.Date.valueOf((java.time.LocalDate) value));
        } else if (value instanceof java.time.LocalDateTime) {
            stmt.setTimestamp(paramIndex, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value));
        } else if (value instanceof Enum) {
            stmt.setString(paramIndex, ((Enum<?>) value).name());
        } else {
            // Default to string representation
            stmt.setString(paramIndex, value.toString());
        }
    }

    /**
     * Converts a string value to the appropriate type for a field
     *
     * @param value The string value from CSV
     * @param targetType The target field type
     * @return Converted value
     */
    private Object convertStringToFieldType(String value, Class<?> targetType) {
        try {
            if (value == null || value.isEmpty()) {
                // Return empty string for String type
                if (String.class.equals(targetType)) {
                    return "";
                }
                return null;
            }

            if (String.class.equals(targetType)) {
                return value;
            } else if (Integer.class.equals(targetType) || int.class.equals(targetType)) {
                return Integer.parseInt(value);
            } else if (Long.class.equals(targetType) || long.class.equals(targetType)) {
                return Long.parseLong(value);
            } else if (Double.class.equals(targetType) || double.class.equals(targetType)) {
                return Double.parseDouble(value);
            } else if (Float.class.equals(targetType) || float.class.equals(targetType)) {
                return Float.parseFloat(value);
            } else if (java.math.BigDecimal.class.equals(targetType)) {
                return new java.math.BigDecimal(value);
            } else if (java.math.BigInteger.class.equals(targetType)) {
                return new java.math.BigInteger(value);
            } else if (Boolean.class.equals(targetType) || boolean.class.equals(targetType)) {
                // Handle various boolean representations
                if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes") ||
                        value.equalsIgnoreCase("y")) {
                    return true;
                } else if (value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("no") ||
                        value.equalsIgnoreCase("n")) {
                    return false;
                }
                return Boolean.parseBoolean(value);
            } else if (java.util.Date.class.equals(targetType)) {
                try {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(value);
                } catch (Exception e) {
                    // Try alternative date formats
                    try {
                        return new java.text.SimpleDateFormat("MM/dd/yyyy").parse(value);
                    } catch (Exception e2) {
                        throw new IllegalArgumentException("Cannot convert value to Date: " + value, e);
                    }
                }
            } else if (java.time.LocalDate.class.equals(targetType)) {
                try {
                    return java.time.LocalDate.parse(value);
                } catch (Exception e) {
                    // Try with formatter
                    try {
                        return java.time.LocalDate.parse(value,
                                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"));
                    } catch (Exception e2) {
                        throw new IllegalArgumentException("Cannot convert to LocalDate: " + value, e);
                    }
                }
            } else if (java.time.LocalDateTime.class.equals(targetType)) {
                try {
                    return java.time.LocalDateTime.parse(value);
                } catch (Exception e) {
                    // Try with formatter
                    try {
                        return java.time.LocalDateTime.parse(value,
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    } catch (Exception e2) {
                        throw new IllegalArgumentException("Cannot convert to LocalDateTime: " + value, e);
                    }
                }
            } else if (Enum.class.isAssignableFrom(targetType)) {
                try {
                    return Enum.valueOf((Class<Enum>) targetType, value);
                } catch (IllegalArgumentException e) {
                    // Try case-insensitive match for enums
                    for (Object enumConstant : targetType.getEnumConstants()) {
                        if (((Enum<?>) enumConstant).name().equalsIgnoreCase(value)) {
                            return enumConstant;
                        }
                    }
                    throw new IllegalArgumentException("Cannot convert to enum: " + value +
                            " for type " + targetType.getName());
                }
            }

            // Add more type conversions as needed

            throw new IllegalArgumentException("Unsupported type conversion for type: " +
                    targetType.getName() + " with value: " + value);
        } catch (Exception e) {
            log.error("Error converting value '{}' to type {}: {}",
                    value, targetType.getName(), e.getMessage());
            throw e;
        }
    }
}