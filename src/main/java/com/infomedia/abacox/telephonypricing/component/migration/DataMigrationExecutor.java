// File: com/infomedia/abacox/telephonypricing/component/migration/DataMigrationExecutor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.BiConsumer;

@Component
@Log4j2
@RequiredArgsConstructor // Injects final fields
public class DataMigrationExecutor {

    private final TableMigrationExecutor tableExecutor;

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
                try {
                    if (tableConfig.getBeforeMigrationAction() != null) {
                        log.debug("Executing before-migration action for table '{}'...",
                                tableConfig.getSourceTableName());
                        tableConfig.getBeforeMigrationAction().accept(tableConfig);
                    }

                    // Call the method on the executor bean (this goes through the proxy)
                    tableExecutor.executeTableMigration(tableConfig, request.getSourceDbConfig());
                    log.debug("Successfully migrated table {}/{}: {}", currentTableIndex, totalTables,
                            tableConfig.getSourceTableName());

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