package com.infomedia.abacox.telephonypricing.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Component
@Log4j2
@RequiredArgsConstructor // Injects final fields
public class DataMigrationExecutor {

    private final TableMigrationExecutor tableExecutor;

    // This method is NOT transactional
    public void runMigration(MigrationRequest request) {
        log.info("Starting data migration process...");
        // log.info("Using target batch size: {}", batchSize); // Logged in executor now

        for (TableMigrationConfig tableConfig : request.getTablesToMigrate()) {
            log.info("---------------------------------------------------------");
            log.info("Attempting migration for: {} -> {}", tableConfig.getSourceTableName(), tableConfig.getTargetEntityClassName());
            try {
                // Call the method on the executor bean (this goes through the proxy)
                tableExecutor.executeTableMigration(tableConfig, request.getSourceDbConfig());

            } catch (Exception e) {
                // Log the specific table that failed
                log.error("!!! CRITICAL ERROR migrating table {}: {}. Stopping migration. !!!",
                        tableConfig.getSourceTableName(), e.getMessage(), e);
                // Re-throw to stop the overall process
                throw new RuntimeException("Migration failed for table " + tableConfig.getSourceTableName(), e);
            }
            log.info("---------------------------------------------------------");
        }

        log.info("Data migration process finished.");
    }
}