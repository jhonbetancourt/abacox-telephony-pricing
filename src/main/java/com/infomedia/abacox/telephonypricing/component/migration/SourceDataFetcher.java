// File: com/infomedia/abacox/telephonypricing/component/migration/SourceDataFetcher.java
package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
@Log4j2
public class SourceDataFetcher {

    private static final String SQL_SERVER_PRODUCT_NAME = "Microsoft SQL Server";
    private static final String POSTGRESQL_PRODUCT_NAME = "PostgreSQL";
    private static final String MYSQL_PRODUCT_NAME = "MySQL";
    private static final String ORACLE_PRODUCT_NAME = "Oracle";

    private enum SqlDialect {
        NONE,
        SQL_SERVER,
        POSTGRESQL,
        MYSQL,
        ORACLE_12C,
        ORACLE_PRE12C
    }

    /**
     * Fetches full row data for a specific list of source IDs.
     * Uses sub-batching to safely avoid the SQL Server 2100 parameter limit.
     */
    public List<Map<String, Object>> fetchFullDataForIds(SourceDbConfig config, String tableName,
            Set<String> columnsToSelect, String sourceIdColumn, List<Object> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        String columnsSql = buildColumnsSql(columnsToSelect, SqlDialect.NONE);

        log.debug("Fetching full data for {} IDs from table {}", ids.size(), tableName);

        try (Connection connection = DriverManager.getConnection(config.getUrl(), config.getUsername(),
                config.getPassword())) {

            // SQL Server has a strict 2100 parameter limit per query.
            // We split the incoming IDs into safe chunks of 1000 for the IN (...) clause.
            int maxParamsPerQuery = 2000;

            for (int i = 0; i < ids.size(); i += maxParamsPerQuery) {
                List<Object> subBatchIds = ids.subList(i, Math.min(i + maxParamsPerQuery, ids.size()));

                String placeholders = String.join(",", Collections.nCopies(subBatchIds.size(), "?"));
                String sql = String.format("SELECT %s FROM %s WHERE %s IN (%s)", columnsSql, tableName, sourceIdColumn,
                        placeholders);

                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    MigrationUtils.setPreparedStatementParameters(ps, subBatchIds);

                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        Map<String, Integer> columnIndexMap = buildColumnIndexMap(metaData);
                        while (rs.next()) {
                            results.add(extractRow(rs, columnsToSelect, columnIndexMap, SqlDialect.NONE));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch full data for ID batch from table {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
        return results;
    }

    /**
     * Fetches data using a streaming cursor (chunking) instead of OFFSET/FETCH
     * pagination.
     * This mimics Python's fetchmany() and prevents massive DB performance
     * degradation on large tables.
     */
    public void fetchData(SourceDbConfig config, String tableName, Set<String> requestedColumns, String whereClause,
            String sourceIdColumn, String orderByClause, Integer maxEntriesToMigrate,
            int batchSize, Consumer<List<Map<String, Object>>> batchProcessor) throws SQLException {

        long totalRowsFetched = 0;

        log.debug("Loading JDBC driver: {}", config.getDriverClassName());
        try {
            Class.forName(config.getDriverClassName());
        } catch (ClassNotFoundException e) {
            log.error("Could not load JDBC driver: {}", config.getDriverClassName(), e);
            throw new SQLException("JDBC Driver not found: " + config.getDriverClassName(), e);
        }

        log.info("Connecting to source database: {}", config.getUrl());
        try (Connection connection = DriverManager.getConnection(config.getUrl(), config.getUsername(),
                config.getPassword())) {

            // 1. Get Actual Columns & Metadata
            DatabaseMetaData dbMetaData = connection.getMetaData();
            Set<String> actualColumns = getActualSourceColumns(connection, dbMetaData, tableName);
            if (actualColumns.isEmpty()) {
                log.warn("Could not retrieve column metadata or table '{}' has no columns/does not exist.", tableName);
                return;
            }

            // 2. Determine Columns to Select (Intersection, case-insensitive)
            Set<String> requestedUpper = requestedColumns.stream().map(String::toUpperCase).collect(Collectors.toSet());
            Set<String> columnsToSelect = actualColumns.stream()
                    .filter(actualCol -> requestedUpper.contains(actualCol.toUpperCase()))
                    .collect(Collectors.toSet());

            logSkippedColumns(requestedColumns, columnsToSelect, tableName);

            // 3. Ensure essential columns exist in the select list
            String actualSourceIdColumn = null;
            if (sourceIdColumn != null && !sourceIdColumn.trim().isEmpty()) {
                actualSourceIdColumn = validateAndGetActualIdColumn(actualColumns, sourceIdColumn, tableName);
                columnsToSelect.add(actualSourceIdColumn);
            }

            if (orderByClause != null && !orderByClause.trim().isEmpty()) {
                String[] orderByParts = orderByClause.split(",");
                for (String part : orderByParts) {
                    String cleanCandidate = part.trim().split("\\s+")[0].replaceAll("[\\[\\]`\"]", "");
                    if (!cleanCandidate.isEmpty()) {
                        actualColumns.stream().filter(c -> c.equalsIgnoreCase(cleanCandidate)).findFirst()
                                .ifPresent(columnsToSelect::add);
                    }
                }
            }

            if (columnsToSelect.isEmpty()) {
                log.warn("No requested columns found in source table '{}'. Skipping fetch.", tableName);
                return;
            }

            // 4. Determine Database Dialect
            SqlDialect dialect = determineDialect(dbMetaData);

            // 5. Build Final Order By Clause
            String finalOrderByClause = orderByClause;
            if ((finalOrderByClause == null || finalOrderByClause.trim().isEmpty()) && actualSourceIdColumn != null) {
                // Only default to sorting by ID if no other order was provided
                finalOrderByClause = quoteIdentifier(actualSourceIdColumn, dialect) + " ASC";
            }

            // 6. Build the Streaming SQL Query
            String columnsSql = buildColumnsSql(columnsToSelect, dialect);
            String sql = buildStreamingQuery(dialect, tableName, columnsSql, whereClause, finalOrderByClause,
                    maxEntriesToMigrate);

            log.info("Executing streaming query: {}", sql);

            // Some drivers (e.g. Postgres) require autoCommit = false to honor setFetchSize
            // as a cursor
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);

                // 7. Execute Query with Forward-Only Cursor settings
                try (PreparedStatement preparedStatement = connection.prepareStatement(sql,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                    preparedStatement.setFetchSize(batchSize); // Tells JDBC to fetch in chunks over network
                    if (maxEntriesToMigrate != null && maxEntriesToMigrate > 0) {
                        preparedStatement.setMaxRows(maxEntriesToMigrate); // Standard JDBC fallback limit
                    }

                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        ResultSetMetaData metaData = resultSet.getMetaData();
                        Map<String, Integer> columnIndexMap = buildColumnIndexMap(metaData);

                        List<Map<String, Object>> currentBatch = new ArrayList<>(batchSize);

                        // 8. Stream through the results
                        while (resultSet.next()) {
                            Map<String, Object> row = extractRow(resultSet, columnsToSelect, columnIndexMap, dialect);
                            currentBatch.add(row);
                            totalRowsFetched++;

                            // Dispatch batch to the processor
                            if (currentBatch.size() >= batchSize) {
                                log.debug("Processing streamed batch of size {}", currentBatch.size());
                                batchProcessor.accept(new ArrayList<>(currentBatch)); // Pass copy
                                currentBatch.clear();
                            }

                            if (totalRowsFetched % (batchSize * 10) == 0) {
                                log.info("Streamed {} total rows from source table {}...", totalRowsFetched, tableName);
                            }
                        }

                        // Dispatch any remaining rows in the final batch
                        if (!currentBatch.isEmpty()) {
                            log.debug("Processing final streamed batch of size {}", currentBatch.size());
                            batchProcessor.accept(new ArrayList<>(currentBatch));
                            currentBatch.clear();
                        }
                    }
                }
            } finally {
                // Restore original connection state
                connection.setAutoCommit(originalAutoCommit);
            }

            log.info("Finished streaming data for source table {}. Total rows fetched: {}", tableName,
                    totalRowsFetched);

        } catch (SQLException e) {
            log.error("Error during data fetch lifecycle for source table {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    private SqlDialect determineDialect(DatabaseMetaData dbMetaData) {
        try {
            String dbProductName = dbMetaData.getDatabaseProductName();
            int dbMajorVersion = dbMetaData.getDatabaseMajorVersion();

            if (SQL_SERVER_PRODUCT_NAME.equalsIgnoreCase(dbProductName)) {
                return SqlDialect.SQL_SERVER;
            } else if (POSTGRESQL_PRODUCT_NAME.equalsIgnoreCase(dbProductName)) {
                return SqlDialect.POSTGRESQL;
            } else if (MYSQL_PRODUCT_NAME.equalsIgnoreCase(dbProductName)) {
                return SqlDialect.MYSQL;
            } else if (ORACLE_PRODUCT_NAME.equalsIgnoreCase(dbProductName)) {
                if (dbMajorVersion >= 12)
                    return SqlDialect.ORACLE_12C;
                return SqlDialect.ORACLE_PRE12C;
            }
        } catch (SQLException e) {
            log.warn("Could not reliably determine database metadata. Falling back to NONE.");
        }
        return SqlDialect.NONE;
    }

    private String buildStreamingQuery(SqlDialect dialect, String tableName, String columnsSql, String whereClause,
            String orderByClause, Integer maxEntries) {
        String safeTableName = quoteIdentifier(tableName, dialect);
        StringBuilder sql = new StringBuilder("SELECT ");

        // For SQL Server: SELECT TOP (X)
        if (maxEntries != null && maxEntries > 0 && dialect == SqlDialect.SQL_SERVER) {
            sql.append("TOP (").append(maxEntries).append(") ");
        }

        sql.append(columnsSql).append(" FROM ").append(safeTableName);

        // Mimicking Python script optimization for SQL Server
        if (dialect == SqlDialect.SQL_SERVER) {
            sql.append(" WITH (NOLOCK)");
        }

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByClause != null && !orderByClause.trim().isEmpty()) {
            sql.append(" ORDER BY ").append(orderByClause);
        }

        // Limit appended at the end for Postgres/MySQL/Oracle 12c+
        if (maxEntries != null && maxEntries > 0) {
            if (dialect == SqlDialect.POSTGRESQL || dialect == SqlDialect.MYSQL) {
                sql.append(" LIMIT ").append(maxEntries);
            } else if (dialect == SqlDialect.ORACLE_12C) {
                sql.append(" FETCH FIRST ").append(maxEntries).append(" ROWS ONLY");
            }
        }

        return sql.toString();
    }

    private String buildColumnsSql(Set<String> columnsToSelect, SqlDialect dialect) {
        return columnsToSelect.stream()
                .map(col -> quoteIdentifier(col, dialect))
                .collect(Collectors.joining(", "));
    }

    private String quoteIdentifier(String identifier, SqlDialect dialect) {
        if (identifier == null || identifier.isEmpty())
            return identifier;

        final String quoteCharStart;
        final String quoteCharEnd;

        if (dialect == SqlDialect.SQL_SERVER) {
            quoteCharStart = "[";
            quoteCharEnd = "]";
        } else if (dialect == SqlDialect.MYSQL) {
            quoteCharStart = "`";
            quoteCharEnd = "`";
        } else {
            quoteCharStart = "\"";
            quoteCharEnd = "\"";
        }

        if (identifier.startsWith(quoteCharStart) && identifier.endsWith(quoteCharEnd)) {
            return identifier;
        }

        if (identifier.contains(".")) {
            return Arrays.stream(identifier.split("\\."))
                    .map(part -> part.startsWith(quoteCharStart) && part.endsWith(quoteCharEnd)
                            ? part
                            : quoteCharStart + part + quoteCharEnd)
                    .collect(Collectors.joining("."));
        } else {
            return quoteCharStart + identifier + quoteCharEnd;
        }
    }

    private void logSkippedColumns(Set<String> requestedColumns, Set<String> columnsToSelect, String tableName) {
        Set<String> selectedUpper = columnsToSelect.stream().map(String::toUpperCase).collect(Collectors.toSet());
        requestedColumns.forEach(requestedCol -> {
            if (!selectedUpper.contains(requestedCol.toUpperCase())) {
                log.warn("Requested column '{}' not found in source table '{}' and will be skipped.", requestedCol,
                        tableName);
            }
        });
    }

    private String validateAndGetActualIdColumn(Set<String> actualColumns, String requestedSourceIdColumn,
            String tableName) throws SQLException {
        final String requestedUpper = requestedSourceIdColumn.toUpperCase();
        return actualColumns.stream()
                .filter(col -> col.toUpperCase().equals(requestedUpper))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("CRITICAL: Source ID column '{}' not found in table '{}'.", requestedSourceIdColumn,
                            tableName);
                    return new SQLException("Source ID column '" + requestedSourceIdColumn + "' not found.");
                });
    }

    private Map<String, Integer> buildColumnIndexMap(ResultSetMetaData metaData) throws SQLException {
        Map<String, Integer> columnIndexMap = new HashMap<>();
        int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            if (label == null || label.isEmpty())
                label = metaData.getColumnName(i);
            columnIndexMap.put(label.toUpperCase(), i);
        }
        return columnIndexMap;
    }

    private Map<String, Object> extractRow(ResultSet resultSet, Set<String> columnsToSelect,
            Map<String, Integer> columnIndexMap, SqlDialect dialect) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        for (String selectedColumn : columnsToSelect) {
            Integer index = columnIndexMap.get(selectedColumn.toUpperCase());
            if (index != null) {
                Object value = resultSet.getObject(index);
                value = convertSqlTypes(value, dialect);
                row.put(selectedColumn, value);
            }
        }
        return row;
    }

    private Object convertSqlTypes(Object value, SqlDialect dialect) {
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        } else if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        } else if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime();
        }
        return value;
    }

    private Set<String> getActualSourceColumns(Connection connection, DatabaseMetaData metaData, String tableName)
            throws SQLException {
        Set<String> columnNames = new HashSet<>();
        String catalog = null;
        String schemaPattern = null;
        String actualTableName = tableName;

        try {
            catalog = connection.getCatalog();
        } catch (SQLException ignored) {
        }

        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 3) {
                catalog = parts[0];
                schemaPattern = parts[1];
                actualTableName = parts[2];
            } else if (parts.length == 2) {
                schemaPattern = parts[0];
                actualTableName = parts[1];
            }
        }

        try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, actualTableName, null)) {
            while (rs.next()) {
                columnNames.add(rs.getString("COLUMN_NAME"));
            }
        }

        if (columnNames.isEmpty() && schemaPattern != null) {
            try (ResultSet rs = metaData.getColumns(catalog, null, actualTableName, null)) {
                while (rs.next()) {
                    columnNames.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columnNames;
    }
}