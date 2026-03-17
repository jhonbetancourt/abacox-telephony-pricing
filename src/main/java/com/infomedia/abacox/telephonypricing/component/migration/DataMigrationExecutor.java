// File: com/infomedia/abacox/telephonypricing/component/migration/DataMigrationExecutor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

@Component
@Log4j2
@RequiredArgsConstructor // Injects final fields
public class DataMigrationExecutor {

    private final TableMigrationExecutor tableExecutor;
    private final DataSource dataSource;

    private static final String HIBERNATE_SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

    /**
     * Temporarily suppresses Hibernate's SqlExceptionHelper logging during
     * migration.
     * Returns the original level so it can be restored afterwards.
     */
    private Level suppressHibernateSqlExceptionHelper() {
        try {
            Logger hibernateLogger = (Logger) LoggerFactory.getLogger(HIBERNATE_SQL_EXCEPTION_HELPER);
            Level originalLevel = hibernateLogger.getLevel();
            hibernateLogger.setLevel(Level.OFF);
            log.debug("Suppressed SqlExceptionHelper logging for migration (was: {})", originalLevel);
            return originalLevel;
        } catch (Exception e) {
            log.debug("Could not suppress SqlExceptionHelper logging: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Restores Hibernate's SqlExceptionHelper logging to its original level.
     */
    private void restoreHibernateSqlExceptionHelper(Level originalLevel) {
        try {
            Logger hibernateLogger = (Logger) LoggerFactory.getLogger(HIBERNATE_SQL_EXCEPTION_HELPER);
            hibernateLogger.setLevel(originalLevel); // null restores to inherited level
            log.debug("Restored SqlExceptionHelper logging to: {}", originalLevel);
        } catch (Exception e) {
            log.debug("Could not restore SqlExceptionHelper logging: {}", e.getMessage());
        }
    }

    private Connection tenantConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        String tenant = TenantContext.getTenant();
        if (tenant != null && !tenant.isBlank()) {
            conn.createStatement().execute("SET search_path TO \"" + tenant + "\"");
        }
        return conn;
    }

    /**
     * Queries pg_indexes for all non-constraint indexes on the given table in the current schema.
     */
    private List<IndexInfo> fetchDroppableIndexes(String tableName) {
        String sql = """
                SELECT i.indexname, i.indexdef
                FROM pg_indexes i
                WHERE i.tablename = ?
                AND i.schemaname = current_schema()
                AND NOT EXISTS (
                    SELECT 1 FROM pg_constraint c
                    JOIN pg_class cl ON c.conrelid = cl.oid
                    WHERE cl.relname = i.tablename
                    AND c.conname = i.indexname
                )
                """;
        List<IndexInfo> result = new ArrayList<>();
        try (Connection conn = tenantConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new IndexInfo(rs.getString("indexname"), rs.getString("indexdef")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query indexes for table {}: {}", tableName, e.getMessage(), e);
        }
        return result;
    }

    private void dropIndexes(List<IndexInfo> indexes) {
        if (indexes.isEmpty()) return;
        try (Connection conn = tenantConnection();
             Statement stmt = conn.createStatement()) {
            for (IndexInfo idx : indexes) {
                log.info("Dropping index '{}' before migration", idx.name());
                stmt.execute("DROP INDEX IF EXISTS \"" + idx.name() + "\"");
            }
        } catch (SQLException e) {
            log.error("Failed to drop indexes: {}", e.getMessage(), e);
            throw new RuntimeException("Index drop failed", e);
        }
    }

    private void rebuildIndexes(List<IndexInfo> indexes) {
        if (indexes.isEmpty()) return;
        // CREATE INDEX CONCURRENTLY must run outside a transaction (autoCommit = true)
        try (Connection conn = tenantConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                for (IndexInfo idx : indexes) {
                    // Convert to CONCURRENTLY to avoid locking the table for reads
                    String concurrentDef = idx.definition().replace("CREATE INDEX", "CREATE INDEX CONCURRENTLY");
                    log.info("Rebuilding index '{}' after migration...", idx.name());
                    long start = System.currentTimeMillis();
                    stmt.execute(concurrentDef);
                    log.info("Rebuilt index '{}' in {}ms", idx.name(), System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to rebuild indexes: {}", e.getMessage(), e);
            throw new RuntimeException("Index rebuild failed", e);
        }
    }

    private record IndexInfo(String name, String definition) {}

    /**
     * Runs the migration for a list of tables, invoking a callback after each
     * table.
     * Stops the entire process if any table migration fails.
     *
     * @param request          The migration parameters including source DB config
     *                         and table list.
     * @param progressCallback A BiConsumer that accepts the TableMigrationConfig
     *                         and an Exception (null if successful).
     *                         This callback is invoked after each table attempt.
     */
    public void runMigration(MigrationParams request, BiConsumer<TableMigrationConfig, Exception> progressCallback) {
        log.debug("Starting data migration process with progress reporting...");

        // Suppress Hibernate SQL error logging during migration
        Level originalLevel = suppressHibernateSqlExceptionHelper();

        try {
            int totalTables = request.getTablesToMigrate().size();
            int currentTableIndex = 0;

            for (TableMigrationConfig tableConfig : request.getTablesToMigrate()) {
                currentTableIndex++;
                log.debug("---------------------------------------------------------");
                String targetClassName = tableConfig.getTargetEntityClassName();
                String simpleTargetName = targetClassName.contains(".")
                        ? targetClassName.substring(targetClassName.lastIndexOf('.') + 1)
                        : targetClassName;

                log.info("Attempting migration for table {}/{} : {} -> {}",
                        currentTableIndex, totalTables, tableConfig.getSourceTableName(),
                        simpleTargetName);
                Exception tableException = null;
                List<IndexInfo> droppedIndexes = Collections.emptyList();
                try {
                    if (tableConfig.getBeforeMigrationAction() != null) {
                        log.debug("Executing before-migration action for table '{}'...",
                                tableConfig.getSourceTableName());
                        tableConfig.getBeforeMigrationAction().accept(tableConfig);
                    }

                    if (tableConfig.isDropAndRebuildIndexes()) {
                        String targetTable = MigrationUtils.getTableName(
                                Class.forName(tableConfig.getTargetEntityClassName()));
                        droppedIndexes = fetchDroppableIndexes(targetTable);
                        log.info("Dropping {} indexes on '{}' before migration", droppedIndexes.size(), targetTable);
                        dropIndexes(droppedIndexes);
                    }

                    // Call the method on the executor bean (this goes through the proxy)
                    tableExecutor.executeTableMigration(tableConfig, request.getSourceDbConfig());
                    log.debug("Successfully migrated table {}/{}: {}", currentTableIndex, totalTables,
                            tableConfig.getSourceTableName());

                    if (tableConfig.isDropAndRebuildIndexes() && !droppedIndexes.isEmpty()) {
                        log.info("Rebuilding {} indexes after migration", droppedIndexes.size());
                        rebuildIndexes(droppedIndexes);
                    }

                    if (tableConfig.getPostMigrationSuccessAction() != null) {
                        log.debug("Executing post-migration success action for table '{}'...",
                                tableConfig.getSourceTableName());
                        try {
                            tableConfig.getPostMigrationSuccessAction().run();
                            log.debug("Successfully executed post-migration action for table '{}'.",
                                    tableConfig.getSourceTableName());
                        } catch (Exception postActionEx) {
                            log.error(
                                    "!!! Post-migration action for table '{}' FAILED. The data migration for this table was successful, but the subsequent action threw an exception. Please investigate manually. !!!",
                                    tableConfig.getSourceTableName(), postActionEx);
                        }
                    }

                } catch (Exception e) {
                    tableException = e;
                    log.error("!!! CRITICAL ERROR migrating table {}/{}: {}. Stopping migration. !!!",
                            currentTableIndex, totalTables, tableConfig.getSourceTableName(), e.getMessage(), e);

                    if (tableConfig.isDropAndRebuildIndexes() && !droppedIndexes.isEmpty()) {
                        log.warn("Migration failed — attempting to restore {} dropped indexes anyway...",
                                droppedIndexes.size());
                        try {
                            rebuildIndexes(droppedIndexes);
                        } catch (Exception rebuildEx) {
                            log.error("!!! Could not restore indexes after migration failure. Manual intervention required. !!!",
                                    rebuildEx);
                        }
                    }

                } finally {
                    if (progressCallback != null) {
                        try {
                            progressCallback.accept(tableConfig, tableException);
                        } catch (Exception cbEx) {
                            log.error("Error executing progress callback for table {}",
                                    tableConfig.getSourceTableName(), cbEx);
                        }
                    }
                }

                if (tableException != null) {
                    throw new RuntimeException("Migration failed for table " + tableConfig.getSourceTableName(),
                            tableException);
                }
                log.debug("---------------------------------------------------------");
            }

            log.debug("Data migration process finished executing table loop.");
        } finally {
            // Always restore the logger, even if migration fails
            restoreHibernateSqlExceptionHelper(originalLevel);
        }
    }

    // Overload for backward compatibility or calls without progress reporting
    public void runMigration(MigrationParams request) {
        runMigration(request, null); // Call the main method with a null callback
    }
}
