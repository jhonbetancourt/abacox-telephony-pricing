package com.infomedia.abacox.telephonypricing.migration;

// Remove EntityManager and batchSize imports/fields if no longer needed here
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
// Remove Transactional imports if no methods use it here anymore

@Service
@Log4j2
@RequiredArgsConstructor // Injects final fields
public class DataMigrationService {

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

    // Remove the old migrateTable method entirely from this class
    // protected void migrateTable(...) { ... }

    // Remove helper methods (setProperty, convertToFieldType) if they moved entirely
    // private void setProperty(...) { ... }
    // private Object convertToFieldType(...) { ... }
}