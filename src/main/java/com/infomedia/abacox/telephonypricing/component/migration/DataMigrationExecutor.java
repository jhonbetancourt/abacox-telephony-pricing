// File: com/infomedia/abacox/telephonypricing/component/migration/DataMigrationExecutor.java
package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.function.BiConsumer;

@Component
@Log4j2
@RequiredArgsConstructor // Injects final fields
public class DataMigrationExecutor {

    private final TableMigrationExecutor tableExecutor;

    /**
     * Runs the migration for a list of tables, invoking a callback after each table.
     * Stops the entire process if any table migration fails.
     *
     * @param request          The migration parameters including source DB config and table list.
     * @param progressCallback A BiConsumer that accepts the TableMigrationConfig and an Exception (null if successful).
     *                         This callback is invoked after each table attempt.
     */
    public void runMigration(MigrationParams request, BiConsumer<TableMigrationConfig, Exception> progressCallback) {
        log.info("Starting data migration process with progress reporting...");
        int totalTables = request.getTablesToMigrate().size();
        int currentTableIndex = 0;

        for (TableMigrationConfig tableConfig : request.getTablesToMigrate()) {
            currentTableIndex++;
            log.info("---------------------------------------------------------");
            log.info("Attempting migration for table {}/{} : {} -> {}",
                    currentTableIndex, totalTables, tableConfig.getSourceTableName(), tableConfig.getTargetEntityClassName());
            Exception tableException = null;
            try {
                // Call the method on the executor bean (this goes through the proxy)
                tableExecutor.executeTableMigration(tableConfig, request.getSourceDbConfig());
                log.info("Successfully migrated table {}/{}: {}", currentTableIndex, totalTables, tableConfig.getSourceTableName());

                if (tableConfig.getPostMigrationSuccessAction() != null) {
                    log.info("Executing post-migration success action for table '{}'...", tableConfig.getSourceTableName());
                    try {
                        tableConfig.getPostMigrationSuccessAction().run();
                        log.info("Successfully executed post-migration action for table '{}'.", tableConfig.getSourceTableName());
                    } catch (Exception postActionEx) {
                        // Log this as a severe warning, but DO NOT fail the entire migration.
                        // The core data migration was successful. A failing post-action should not roll it back.
                        log.error("!!! Post-migration action for table '{}' FAILED. The data migration for this table was successful, but the subsequent action threw an exception. Please investigate manually. !!!",
                                  tableConfig.getSourceTableName(), postActionEx);
                        // We do not set tableException here, as the main migration succeeded.
                    }
                }

            } catch (Exception e) {
                // Log the specific table that failed
                tableException = e; // Store exception to pass to callback
                log.error("!!! CRITICAL ERROR migrating table {}/{}: {}. Stopping migration. !!!",
                        currentTableIndex, totalTables, tableConfig.getSourceTableName(), e.getMessage(), e);

            } finally {
                // Always report progress, passing null exception if successful
                 if (progressCallback != null) {
                     try {
                         progressCallback.accept(tableConfig, tableException);
                     } catch (Exception cbEx) {
                         log.error("Error executing progress callback for table {}", tableConfig.getSourceTableName(), cbEx);
                     }
                 }
            }

            // If an exception occurred in the try block, re-throw it now to stop the loop
            if (tableException != null) {
                 throw new RuntimeException("Migration failed for table " + tableConfig.getSourceTableName(), tableException);
            }
            log.info("---------------------------------------------------------");
        }

        log.info("Data migration process finished executing table loop.");
    }

     // Overload for backward compatibility or calls without progress reporting
     public void runMigration(MigrationParams request) {
         runMigration(request, null); // Call the main method with a null callback
     }
}