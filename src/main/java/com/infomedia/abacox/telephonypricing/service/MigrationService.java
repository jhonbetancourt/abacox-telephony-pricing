package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.migration.DataMigrationExecutor;
import com.infomedia.abacox.telephonypricing.component.migration.MigrationParams;
import com.infomedia.abacox.telephonypricing.component.migration.SourceDbConfig;
import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigKey;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigService;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStatus;
import com.infomedia.abacox.telephonypricing.exception.MigrationAlreadyInProgressException;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import com.infomedia.abacox.telephonypricing.component.migration.definition.*;

@Service
@Log4j2
@RequiredArgsConstructor
public class MigrationService {

        private final DataMigrationExecutor dataMigrationExecutor;
        private final EntityManager entityManager;
        private final PlatformTransactionManager transactionManager;
        private final ConfigService configService;
        private final ExecutorService migrationExecutorService = Executors.newSingleThreadExecutor();

        // --- State Tracking ---
        private final AtomicBoolean isMigrationRunning = new AtomicBoolean(false);
        private final AtomicReference<MigrationState> currentState = new AtomicReference<>(MigrationState.IDLE);
        private final AtomicReference<LocalDateTime> startTime = new AtomicReference<>();
        private final AtomicReference<LocalDateTime> endTime = new AtomicReference<>();
        private final AtomicReference<String> errorMessage = new AtomicReference<>();
        private final AtomicInteger totalTables = new AtomicInteger(0);
        private final AtomicInteger migratedTables = new AtomicInteger(0);
        private final AtomicReference<String> currentStep = new AtomicReference<>("");
        private final AtomicReference<Future<?>> migrationTaskFuture = new AtomicReference<>(null);
        // --- End State Tracking ---

        public void startAsync(MigrationStart runRequest) {
                Integer clientId = validateAndGetClientId(runRequest);
                submitMigrationTask(() -> start(runRequest, clientId));
        }

        private void submitMigrationTask(Runnable migrationTask) {
                if (!isMigrationRunning.compareAndSet(false, true)) {
                        throw new MigrationAlreadyInProgressException("A data migration is already in progress.");
                }

                // 1. Capture the tenant from the current HTTP thread
                String currentTenant = TenantContext.getTenant();

                log.info("Submitting migration task to executor service for tenant: {}", currentTenant);
                try {
                        resetMigrationState();
                        Future<?> future = migrationExecutorService.submit(() -> {
                                // 2. Set the tenant in the NEW thread
                                try {
                                        TenantContext.setTenant(currentTenant);
                                        migrationTask.run();
                                } finally {
                                        // 3. Cleanup
                                        TenantContext.clear();
                                }
                        });
                        migrationTaskFuture.set(future);
                } catch (Exception e) {
                        log.error("Failed to submit migration task to executor service", e);
                        currentState.set(MigrationState.FAILED);
                        errorMessage.set("Failed to start migration task: " + e.getMessage());
                        endTime.set(LocalDateTime.now());
                        isMigrationRunning.set(false);
                        migrationTaskFuture.set(null);
                }
        }

        private void resetMigrationState() {
                currentState.set(MigrationState.STARTING);
                startTime.set(LocalDateTime.now());
                endTime.set(null);
                errorMessage.set(null);
                migratedTables.set(0);
                totalTables.set(0);
                currentStep.set("Initializing...");
                migrationTaskFuture.set(null);
                log.debug("Migration state reset.");
        }

        public void start(MigrationStart runRequest) {
                start(runRequest, validateAndGetClientId(runRequest));
        }

        public void start(MigrationStart runRequest, Integer sourceClientId) {
                ensureRunning();
                log.info("<<<<<<<<<< Starting Full Data Migration Execution >>>>>>>>>>");
                executeMigration(runRequest, defineMigrationOrderAndMappings(runRequest, sourceClientId));
        }

        private Integer validateAndGetClientId(MigrationStart runRequest) {
                Integer clientId = fetchClientId(runRequest);
                if (clientId == null) {
                        String dbName = runRequest.getDatabase().trim();
                        String lookupName = dbName.startsWith("abacox_") ? dbName.substring(7) : dbName;
                        String error = String.format(
                                        "Could not find CLIENTE_ID for database '%s' (lookup: '%s') in control database '%s'. Migration aborted.",
                                        dbName, lookupName, runRequest.getControlDatabase());
                        log.error(error);
                        throw new RuntimeException(error);
                }
                return clientId;
        }

        private void ensureRunning() {
                if (!isMigrationRunning.get()) {
                        isMigrationRunning.set(true);
                        resetMigrationState();
                }
        }

        private void executeMigration(MigrationStart runRequest, List<TableMigrationConfig> tablesToMigrate) {
                currentState.set(MigrationState.RUNNING);

                // Snapshot the CDR processing flag so we can restore it after migration
                boolean cdrWasEnabled = configService.getValue(ConfigKey.CDR_PROCESSING_ENABLED).asBoolean();
                if (cdrWasEnabled) {
                        log.info("Disabling CDR processing for the duration of the migration.");
                        configService.updateValue(ConfigKey.CDR_PROCESSING_ENABLED, false);
                }

                try {
                        String url = "jdbc:sqlserver://" + runRequest.getHost() + ":" + runRequest.getPort()
                                        + ";databaseName="
                                        + runRequest.getDatabase()
                                        + ";encrypt=" + runRequest.getEncryption()
                                        + ";trustServerCertificate=" + runRequest.getTrustServerCertificate()
                                        + ";packetSize=32767"
                                        + ";selectMethod=cursor";

                        SourceDbConfig sourceDbConfig = SourceDbConfig.builder()
                                        .url(url)
                                        .username(runRequest.getUsername())
                                        .password(runRequest.getPassword())
                                        .build();

                        tablesToMigrate.forEach(
                                        tableConfig -> tableConfig.setAssumeTargetIsEmpty(runRequest.getCleanup()));
                        int totalTableCount = tablesToMigrate.size();
                        totalTables.set(totalTableCount);

                        MigrationParams params = new MigrationParams(sourceDbConfig, tablesToMigrate);

                        log.info("Constructed migration params with {} tables. Starting execution.", totalTableCount);

                        // Cleanup target tables (in reverse order) if requested
                        if (Boolean.TRUE.equals(runRequest.getCleanup())) {
                                cleanupTargetTables(tablesToMigrate);
                                // FileInfo is not in the migration list (populated by CDR processing),
                                // but CallRecord and FailedCallRecord reference it via FK.
                                // After those are deleted, clean up FileInfo as well.
                                cleanupFileInfo();
                        } else {
                                log.info("Skipping target table cleanup as per request.");
                        }

                        currentStep.set(String.format("Starting migration of %d tables...", totalTableCount));

                        dataMigrationExecutor.runMigration(params, this::reportProgress);

                        currentState.set(MigrationState.COMPLETED);
                        currentStep.set(String.format("Finished: Successfully migrated %d/%d tables.",
                                        migratedTables.get(), totalTables.get()));
                        log.info("<<<<<<<<<< Data Migration Finished Successfully >>>>>>>>>>");

                } catch (Exception e) {
                        log.error("<<<<<<<<<< Full Data Migration FAILED during execution >>>>>>>>>>", e);
                        currentState.set(MigrationState.FAILED);
                        String failureMsg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
                        errorMessage.set("Migration failed: " + failureMsg);
                        if (!currentStep.get().toLowerCase().contains("failed")) {
                                currentStep.set(String.format(
                                                "Failed during migration (%d/%d tables completed). Error: %s",
                                                migratedTables.get(), totalTables.get(), failureMsg));
                        }
                        log.error("Final migration status: FAILED. Error: {}", errorMessage.get());
                } finally {
                        // Restore CDR processing to whatever it was before migration
                        if (cdrWasEnabled) {
                                log.info("Restoring CDR processing enabled state after migration.");
                                configService.updateValue(ConfigKey.CDR_PROCESSING_ENABLED, true);
                        }
                        endTime.set(LocalDateTime.now());
                        isMigrationRunning.set(false);
                        migrationTaskFuture.set(null);
                        log.info("Migration process ended. State: {}, Duration: {}", currentState.get(),
                                        startTime.get() != null && endTime.get() != null
                                                        ? java.time.Duration.between(startTime.get(), endTime.get())
                                                        : "N/A");
                }
        }

        public MigrationStatus getStatus() {
                return MigrationStatus.builder()
                                .state(currentState.get())
                                .startTime(startTime.get())
                                .endTime(endTime.get())
                                .errorMessage(errorMessage.get())
                                .tablesToMigrate(totalTables.get())
                                .tablesMigrated(migratedTables.get())
                                .currentStep(currentStep.get())
                                .build();
        }

        private void reportProgress(TableMigrationConfig config, Exception error) {
                int currentTotal = totalTables.get();
                if (error == null) {
                        int completedCount = migratedTables.incrementAndGet();
                        String stepMessage = String.format("Migrated table %d/%d: %s",
                                        completedCount, currentTotal, config.getSourceTableName());
                        currentStep.set(stepMessage);
                        log.debug(stepMessage);
                } else {
                        int completedCount = migratedTables.get();
                        String stepMessage = String.format("Failed on table %d/%d: %s. Error: %s",
                                        completedCount + 1,
                                        currentTotal,
                                        config.getSourceTableName(),
                                        error.getMessage());
                        currentStep.set(stepMessage);
                        errorMessage.set(error.getMessage());
                        log.warn("Progress update: Failure on table {}. Error: {}", config.getSourceTableName(),
                                        error.getMessage());
                }
        }

        private void cleanupTargetTables(List<TableMigrationConfig> configs) {
                log.info("Starting cleanup of target tables (reverse dependency order)...");
                currentStep.set("Cleaning up target tables...");

                List<TableMigrationConfig> reverseConfigs = new ArrayList<>(configs);
                Collections.reverse(reverseConfigs);

                TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
                transactionTemplate.execute(status -> {
                        for (TableMigrationConfig config : reverseConfigs) {
                                String entityClassName = config.getTargetEntityClassName();
                                String entityName = entityClassName.substring(entityClassName.lastIndexOf('.') + 1);

                                try {
                                        // If self-referencing, break the cycle first (needs JPQL for field names)
                                        if (config.isSelfReferencing()
                                                        && config.getSelfReferenceTargetForeignKeyFieldName() != null) {
                                                log.info("Breaking self-reference for table {} (Entity: {})...",
                                                                config.getSourceTableName(), entityName);
                                                String updateHql = String.format("UPDATE %s e SET e.%s = NULL",
                                                                entityName,
                                                                config.getSelfReferenceTargetForeignKeyFieldName());
                                                entityManager.createQuery(updateHql).executeUpdate();
                                        }

                                        String physicalTable = getPhysicalTableName(entityClassName);
                                        log.info("Truncating table [{}] (Entity: {})...", physicalTable, entityName);
                                        entityManager.createNativeQuery("TRUNCATE TABLE " + physicalTable + " CASCADE")
                                                        .executeUpdate();

                                } catch (Exception e) {
                                        log.error("Failed to cleanup table {} (Entity: {}): {}",
                                                        config.getSourceTableName(),
                                                        entityName, e.getMessage());
                                        throw new RuntimeException(
                                                        "Failed to cleanup table " + config.getSourceTableName(), e);
                                }
                        }
                        return null;
                });

                log.info("Target table cleanup completed.");
        }

        private void cleanupFileInfo() {
                log.info("Truncating file_info table (not in migration list, referenced by call records)...");
                currentStep.set("Cleaning up file_info table...");
                TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
                transactionTemplate.execute(status -> {
                        try {
                                entityManager.createNativeQuery("TRUNCATE TABLE file_info CASCADE").executeUpdate();
                                log.info("Truncated file_info table.");
                        } catch (Exception e) {
                                log.error("Failed to truncate file_info table: {}", e.getMessage());
                                throw new RuntimeException("Failed to truncate file_info table", e);
                        }
                        return null;
                });
        }

        /**
         * Resolves the physical database table name for a given JPA entity class name
         * by inspecting Hibernate's metamodel.
         */
        private String getPhysicalTableName(String entityClassName) {
                try {
                        Class<?> entityClass = Class.forName(entityClassName);
                        org.hibernate.metamodel.spi.MappingMetamodelImplementor metamodel = entityManager
                                        .getEntityManagerFactory()
                                        .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class)
                                        .getMappingMetamodel();
                        org.hibernate.persister.entity.AbstractEntityPersister persister = (org.hibernate.persister.entity.AbstractEntityPersister) metamodel
                                        .getEntityDescriptor(entityClass);
                        return persister.getTableName();
                } catch (ClassNotFoundException e) {
                        throw new RuntimeException("Could not find entity class: " + entityClassName, e);
                }
        }

        private List<TableMigrationConfig> defineMigrationOrderAndMappings(MigrationStart runRequest,
                        Integer sourceClientId) {

                log.info("Defining migration mappings. Using validated CLIENTE_ID: {}", sourceClientId);

                Map<Object, Object> telephonyTypeReplacements = new HashMap<>();
                telephonyTypeReplacements.put(99, null);

                MigrationContext context = MigrationContext.builder()
                                .runRequest(runRequest)
                                .sourceClientId(sourceClientId)
                                .telephonyTypeReplacements(telephonyTypeReplacements)
                                .migratedEmployeeIds(Collections.synchronizedSet(new HashSet<>()))
                                .migratedFileInfoIds(Collections.synchronizedSet(new HashSet<>()))
                                .directorioToPlantCache(fetchDirectorioToPlantCache(runRequest, sourceClientId))
                                .controlDatabase(runRequest.getControlDatabase())
                                .build();

                List<MigrationTableDefinition> definitions = List.of(
                                // Level 0
                                new OriginCountryDefinition(),
                                new HistoryControlDefinition(),
                                new PlantTypeDefinition(),
                                new JobPositionDefinition(),
                                new CallCategoryDefinition(),
                                new ExtensionListDefinition(),
                                // Level 1
                                new OperatorDefinition(),
                                new TelephonyTypeDefinition(),
                                // Level 2
                                new IndicatorDefinition(),
                                new PrefixDefinition(),
                                new TelephonyTypeConfigDefinition(),
                                // Level 3
                                new BandDefinition(),
                                new CompanyDefinition(),
                                new CommunicationLocationDefinition(),
                                new SeriesDefinition(),
                                new SpecialServiceDefinition(),
                                // Level 4
                                new SubdivisionDefinition(),
                                new CostCenterDefinition(),
                                new BandIndicatorDefinition(),
                                new SpecialRateValueDefinition(),
                                new TrunkDefinition(),
                                // Level 5
                                new EmployeeDefinition(),
                                new ExtensionRangeDefinition(),
                                new PbxSpecialRuleDefinition(),
                                new TrunkRateDefinition(),
                                // Level 6
                                new OfficeDetailsDefinition(),
                                new ContactDefinition(),
                                new TrunkRuleDefinition(),
                                new SubdivisionManagerDefinition(),
                                // Final
                                new CallRecordDefinition(),
                                new FailedCallRecordDefinition(),
                                new CdrLoadControlDefinition(),
                                new FileInfoDefinition());

                return definitions.stream()
                                .map(def -> def.getTableMigrationConfig(context))
                                .toList();
        }

        private Map<String, Integer> fetchDirectorioToPlantCache(MigrationStart runRequest, Integer sourceClientId) {
                if (sourceClientId == null)
                        return Map.of();

                log.info("Loading plant cache from control database for client {}...", sourceClientId);
                Map<String, Integer> cache = new HashMap<>();

                String url = String.format(
                                "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=%s;trustServerCertificate=%s;",
                                runRequest.getHost(), runRequest.getPort(), runRequest.getControlDatabase(),
                                runRequest.getEncryption(), runRequest.getTrustServerCertificate());

                String query = String.format(
                                "SELECT CARGACTL_DIRECTORIO, CARGACTL_TIPOPLANTA_ID FROM %s.dbo.cargactl WHERE CARGACTL_CLIENTE_ID = ?",
                                runRequest.getControlDatabase());

                try (Connection conn = DriverManager.getConnection(url, runRequest.getUsername(),
                                runRequest.getPassword());
                                PreparedStatement ps = conn.prepareStatement(query)) {

                        ps.setInt(1, sourceClientId);
                        try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                        cache.put(rs.getString("CARGACTL_DIRECTORIO"),
                                                        rs.getInt("CARGACTL_TIPOPLANTA_ID"));
                                }
                        }
                } catch (Exception e) {
                        log.error("Failed to load plant cache from control database: {}", e.getMessage());
                }
                log.info("Loaded {} plant types into cache.", cache.size());
                return cache;
        }

        private Integer fetchClientId(MigrationStart runRequest) {
                String url = String.format(
                                "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=%s;trustServerCertificate=%s;",
                                runRequest.getHost(), runRequest.getPort(), runRequest.getControlDatabase(),
                                runRequest.getEncryption(), runRequest.getTrustServerCertificate());

                log.debug("Connecting to control database '{}' via URL: {} (User: {})",
                                runRequest.getControlDatabase(), url, runRequest.getUsername());

                String query = String.format("SELECT CLIENTE_ID FROM %s.dbo.cliente WHERE TRIM(CLIENTE_BD) = ?",
                                runRequest.getControlDatabase());

                String dbName = runRequest.getDatabase().trim();
                String lookupName = dbName.startsWith("abacox_") ? dbName.substring(7) : dbName;

                log.debug("Looking for CLIENTE_ID in {}.dbo.cliente where CLIENTE_BD = '{}' (Original DB: '{}')",
                                runRequest.getControlDatabase(), lookupName, dbName);

                try (Connection conn = DriverManager.getConnection(url, runRequest.getUsername(),
                                runRequest.getPassword());
                                PreparedStatement ps = conn.prepareStatement(query)) {

                        ps.setString(1, lookupName);
                        try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                        int clientId = rs.getInt("CLIENTE_ID");
                                        log.info("Successfully found CLIENTE_ID {} for database '{}' (lookup: '{}')",
                                                        clientId, dbName, lookupName);
                                        return clientId;
                                }
                        }
                        log.warn("No record found in {}.dbo.cliente for CLIENTE_BD = '{}'",
                                        runRequest.getControlDatabase(), lookupName);
                } catch (Exception e) {
                        log.error("Failed to fetch CLIENTE_ID from control database: {} (Query: {})", e.getMessage(),
                                        query, e);
                }
                return null;
        }

        public enum MigrationState {
                IDLE, STARTING, RUNNING, COMPLETED, FAILED
        }
}