package com.infomedia.abacox.telephonypricing.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SourceDataFetcher {

    // Consider adding batching/paging here for large tables
    public List<Map<String, Object>> fetchData(SourceDbConfig config, String tableName, Set<String> requestedColumns, String sourceIdColumn) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();

        log.debug("Loading JDBC driver: {}", config.getDriverClassName());
        try {
            Class.forName(config.getDriverClassName()); // Ensure driver is loaded
        } catch (ClassNotFoundException e) {
            log.error("Could not load JDBC driver: {}", config.getDriverClassName(), e);
            throw new SQLException("JDBC Driver not found: " + config.getDriverClassName(), e);
        }

        log.info("Connecting to source database: {}", config.getUrl());
        // Use try-with-resources for automatic closing of the connection
        try (Connection connection = DriverManager.getConnection(config.getUrl(), config.getUsername(), config.getPassword())) {

            // 1. Get Actual Columns from Source Table Metadata
            Set<String> actualColumns = getActualSourceColumns(connection, tableName);
            if (actualColumns.isEmpty()) {
                log.warn("Could not retrieve column metadata or table '{}' has no columns/does not exist.", tableName);
                // Decide if this is an error or just means skip the table
                return results; // Return empty list if no columns found/table missing
            }

            // 2. Determine Columns to Actually Select (Intersection of requested and actual)
            // Convert both to the same case (e.g., uppercase) for reliable comparison
            Set<String> requestedUpper = requestedColumns.stream().map(String::toUpperCase).collect(Collectors.toSet());
            Set<String> actualUpper = actualColumns.stream().map(String::toUpperCase).collect(Collectors.toSet());

            Set<String> columnsToSelect = actualColumns.stream() // Iterate through actual columns
                    .filter(actualCol -> requestedUpper.contains(actualCol.toUpperCase())) // Check if requested (case-insensitive)
                    .collect(Collectors.toSet());

            // 3. Log skipped columns
            requestedColumns.forEach(requestedCol -> {
                if (!columnsToSelect.stream().anyMatch(col -> col.equalsIgnoreCase(requestedCol))) {
                    log.warn("Requested column '{}' not found in source table '{}' and will be skipped.", requestedCol, tableName);
                }
            });

            // 4. Validate Essential Columns (like the ID)
            final String sourceIdColumnUpper = sourceIdColumn.toUpperCase();
            boolean idColumnExists = columnsToSelect.stream()
                                        .anyMatch(col -> col.toUpperCase().equals(sourceIdColumnUpper));

            if (!idColumnExists) {
                 log.error("CRITICAL: Configured source ID column '{}' does not exist in source table '{}'. Cannot migrate this table.", sourceIdColumn, tableName);
                 throw new SQLException("Source ID column '" + sourceIdColumn + "' not found in table '" + tableName + "'.");
            }

            // 5. Proceed only if there are columns to select
            if (columnsToSelect.isEmpty()) {
                log.warn("No requested columns found in source table '{}'. Skipping fetch.", tableName);
                return results;
            }

            // 6. Build and Execute the Query
            // Quote column names if they might contain spaces or special characters (optional, depends on DB)
            // String columnsSql = columnsToSelect.stream().map(col -> "\"" + col + "\"").collect(Collectors.joining(", ")); // Example quoting
            String columnsSql = String.join(", ", columnsToSelect);
            String sql = String.format("SELECT %s FROM %s", columnsSql, tableName); // Be cautious about SQL injection if tableName is user-controlled outside config

            log.info("Executing query on source: {}", sql);
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql);
                 ResultSet resultSet = preparedStatement.executeQuery()) {

                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                // Create a map for faster lookup of column index by name (case-insensitive)
                Map<String, Integer> columnIndexMap = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    columnIndexMap.put(metaData.getColumnLabel(i).toUpperCase(), i);
                }

                while (resultSet.next()) {
                    Map<String, Object> row = new HashMap<>();
                    // Iterate through the columns WE ACTUALLY SELECTED, not the original requested ones
                    for (String selectedColumn : columnsToSelect) {
                        Integer index = columnIndexMap.get(selectedColumn.toUpperCase());
                        if (index != null) {
                             Object value = resultSet.getObject(index);
                             // Basic type handling example (can be expanded)
                             if (value instanceof java.sql.Timestamp) {
                                 value = ((java.sql.Timestamp) value).toLocalDateTime();
                             } else if (value instanceof java.sql.Date) {
                                 value = ((java.sql.Date) value).toLocalDate();
                             } else if (value instanceof java.sql.Time) {
                                  value = ((java.sql.Time) value).toLocalTime();
                             }
                             // Add more specific SQL Server type conversions if needed
                             row.put(selectedColumn, value); // Use the original case from columnsToSelect for the key
                        } else {
                            // This shouldn't happen if columnIndexMap was built correctly, but good failsafe
                            log.warn("Column '{}' was expected in ResultSet but not found by label.", selectedColumn);
                        }
                    }
                    results.add(row);
                }
                log.info("Fetched {} rows from source table {}", results.size(), tableName);

            } // End of statement/resultset try-with-resources

        } catch (SQLException e) {
            log.error("Error during data fetch lifecycle for source table {}: {}", tableName, e.getMessage(), e);
            throw e; // Re-throw to be handled by the migration service
        }
        return results;
    }

    /**
     * Retrieves the actual column names for a given table from the database metadata.
     * Handles potential schema patterns (like 'dbo' for SQL Server).
     *
     * @param connection Active database connection
     * @param tableName  The name of the table (potentially including schema like 'dbo.MyTable')
     * @return A Set of actual column names, or an empty set if error/not found.
     * @throws SQLException if metadata retrieval fails fundamentally
     */
    private Set<String> getActualSourceColumns(Connection connection, String tableName) throws SQLException {
        Set<String> columnNames = new HashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog(); // Get current database name
        String schemaPattern = null;
        String actualTableName = tableName;

        // Basic handling for schema-qualified table names (e.g., "dbo.Customers")
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            schemaPattern = parts[0];
            actualTableName = parts[1];
            log.debug("Detected schema pattern '{}' and table name '{}'", schemaPattern, actualTableName);
        } else {
             // If no schema provided, try common defaults or null depending on the DB behavior
             // For SQL Server, 'dbo' is common, but null might work if user default schema matches
             // schemaPattern = "dbo"; // Or leave as null to let JDBC driver decide
             log.debug("No schema specified for table '{}', using default/null schema pattern.", actualTableName);
        }


        // Use try-with-resources for the ResultSet from getColumns
        try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, actualTableName, null)) { // null for columnNamePattern means all columns
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                columnNames.add(columnName);
            }
            log.debug("Found columns for table '{}': {}", tableName, columnNames);
        } catch (SQLException e) {
            log.error("Could not retrieve column metadata for table '{}' (Catalog: {}, Schema: {}, Table: {}): {}",
                      tableName, catalog, schemaPattern, actualTableName, e.getMessage());
            // Depending on severity, you might re-throw or return empty set
            throw e; // Re-throwing is often safer
        }

        // Fallback: If no columns found with specific schema, try without schema pattern (might help in some cases)
        if (columnNames.isEmpty() && schemaPattern != null) {
             log.warn("No columns found with schema pattern '{}' for table '{}'. Retrying without schema pattern.", schemaPattern, actualTableName);
             try (ResultSet rs = metaData.getColumns(catalog, null, actualTableName, null)) {
                 while (rs.next()) {
                     String columnName = rs.getString("COLUMN_NAME");
                     columnNames.add(columnName);
                 }
                 log.debug("Found columns for table '{}' (without schema pattern): {}", tableName, columnNames);
             } catch (SQLException e) {
                 log.error("Retry without schema pattern failed for table '{}': {}", tableName, e.getMessage());
                 // Don't throw here, just return the (still empty) set from the first attempt
             }
        }


        return columnNames;
    }
}