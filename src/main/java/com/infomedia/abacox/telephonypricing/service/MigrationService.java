// File: com/infomedia/abacox/telephonypricing/service/MigrationService.java
package com.infomedia.abacox.telephonypricing.service; // Use your actual package

import com.infomedia.abacox.telephonypricing.component.migration.DataMigrationExecutor;
import com.infomedia.abacox.telephonypricing.component.migration.MigrationParams;
import com.infomedia.abacox.telephonypricing.component.migration.SourceDbConfig;
import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigKey;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigService;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStatus;
import com.infomedia.abacox.telephonypricing.exception.MigrationAlreadyInProgressException;
import com.infomedia.abacox.telephonypricing.db.repository.EmployeeRepository;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;

@Service
@Log4j2
@RequiredArgsConstructor
public class MigrationService {

        private final DataMigrationExecutor dataMigrationExecutor;
        private final EmployeeRepository employeeRepository;
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
                                        start(runRequest);
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
                // isMigrationRunning is set by startAsync, or should be set if this is called
                // directly (not recommended for async)
                if (!isMigrationRunning.get()) { // Ensure it's marked as running if called directly
                        isMigrationRunning.set(true);
                        resetMigrationState(); // Reset if called directly without async wrapper
                }
                log.info("<<<<<<<<<< Starting Full Data Migration Execution >>>>>>>>>>");
                currentState.set(MigrationState.RUNNING);
                List<TableMigrationConfig> tablesToMigrate; // Initialize

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
                                        + ";packetSize=32767;";

                        SourceDbConfig sourceDbConfig = SourceDbConfig.builder()
                                        .url(url)
                                        .username(runRequest.getUsername())
                                        .password(runRequest.getPassword())
                                        .build();

                        tablesToMigrate = defineMigrationOrderAndMappings(runRequest);
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
                        log.info("<<<<<<<<<< Full Data Migration Finished Successfully >>>>>>>>>>");

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

        private List<TableMigrationConfig> defineMigrationOrderAndMappings(MigrationStart runRequest) {
                List<TableMigrationConfig> configs = new ArrayList<>();

                // Level 0: No FK dependencies among these
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("MPORIGEN")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.OriginCountry")
                                .sourceIdColumnName("MPORIGEN_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("MPORIGEN_ID", "id"),
                                                entry("MPORIGEN_SIMBOLO", "currencySymbol"),
                                                entry("MPORIGEN_PAIS", "name"),
                                                entry("MPORIGEN_CCODE", "code"),
                                                entry("MPORIGEN_ACTIVO", "active"),
                                                entry("MPORIGEN_FCREACION", "createdDate"),
                                                entry("MPORIGEN_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("CIUDADES")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.City")
                                .sourceIdColumnName("CIUDADES_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("CIUDADES_ID", "id"),
                                                entry("CIUDADES_DEPARTAMENTO", "department"),
                                                entry("CIUDADES_CLASIFICACION", "classification"),
                                                entry("CIUDADES_MUNICIPIO", "municipality"),
                                                entry("CIUDADES_CABMUNICIPAL", "municipalCapital"),
                                                entry("CIUDADES_LATITUD", "latitude"),
                                                entry("CIUDADES_LONGITUD", "longitude"),
                                                entry("CIUDADES_ALTITUD", "altitude"),
                                                entry("CIUDADES_NORTE", "northCoordinate"),
                                                entry("CIUDADES_ESTE", "eastCoordinate"),
                                                entry("CIUDADES_ORIGEN", "origin"),
                                                entry("CIUDADES_ACTIVO", "active"),
                                                entry("CIUDADES_FCREACION", "createdDate"),
                                                entry("CIUDADES_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("tipoplanta")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.PlantType")
                                .sourceIdColumnName("TIPOPLANTA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("TIPOPLANTA_ID", "id"),
                                                entry("TIPOPLANTA_NOMBRE", "name"),
                                                entry("TIPOPLANTA_ACTIVO", "active"),
                                                entry("TIPOPLANTA_FCREACION", "createdDate"),
                                                entry("TIPOPLANTA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("funcargo")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.JobPosition")
                                .sourceIdColumnName("FUNCARGO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("FUNCARGO_ID", "id"),
                                                entry("FUNCARGO_NOMBRE", "name"),
                                                entry("FUNCARGO_ACTIVO", "active"),
                                                entry("FUNCARGO_FCREACION", "createdDate"),
                                                entry("FUNCARGO_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("CLASELLAMA")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CallCategory")
                                .sourceIdColumnName("CLASELLAMA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("CLASELLAMA_ID", "id"),
                                                entry("CLASELLAMA_NOMBRE", "name"),
                                                entry("CLASELLAMA_ACTIVO", "active"),
                                                entry("CLASELLAMA_FCREACION", "createdDate"),
                                                entry("CLASELLAMA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 1: Depend on Level 0
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("OPERADOR")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Operator")
                                .sourceIdColumnName("OPERADOR_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("OPERADOR_ID", "id"),
                                                entry("OPERADOR_NOMBRE", "name"),
                                                entry("OPERADOR_MPORIGEN_ID", "originCountryId"),
                                                entry("OPERADOR_ACTIVO", "active"),
                                                entry("OPERADOR_FCREACION", "createdDate"),
                                                entry("OPERADOR_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("TIPOTELE")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TelephonyType")
                                .sourceIdColumnName("TIPOTELE_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("TIPOTELE_ID", "id"),
                                                entry("TIPOTELE_NOMBRE", "name"),
                                                entry("TIPOTELE_CLASELLAMA_ID", "callCategoryId"),
                                                entry("TIPOTELE_TRONCALES", "usesTrunks"),
                                                entry("TIPOTELE_ACTIVO", "active"),
                                                entry("TIPOTELE_FCREACION", "createdDate"),
                                                entry("TIPOTELE_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 2: Depend on Level 1 or 0
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("INDICATIVO")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Indicator")
                                .sourceIdColumnName("INDICATIVO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("INDICATIVO_ID", "id"),
                                                entry("INDICATIVO_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("INDICATIVO_DPTO_PAIS", "departmentCountry"),
                                                entry("INDICATIVO_CIUDAD", "cityName"),
                                                entry("INDICATIVO_OPERADOR_ID", "operatorId"),
                                                entry("INDICATIVO_MPORIGEN_ID", "originCountryId"),
                                                entry("INDICATIVO_ENUSO", "active"), // Assuming ENUSO maps to active
                                                entry("INDICATIVO_FCREACION", "createdDate"),
                                                entry("INDICATIVO_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("PREFIJO")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Prefix")
                                .sourceIdColumnName("PREFIJO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("PREFIJO_ID", "id"),
                                                entry("PREFIJO_OPERADOR_ID", "operatorId"),
                                                entry("PREFIJO_TIPOTELE_ID", "telephonyTypeId"), // Renamed in entity
                                                entry("PREFIJO_PREFIJO", "code"),
                                                entry("PREFIJO_VALORBASE", "baseValue"),
                                                entry("PREFIJO_BANDAOK", "bandOk"),
                                                entry("PREFIJO_IVAINC", "vatIncluded"),
                                                entry("PREFIJO_IVA", "vatValue"),
                                                entry("PREFIJO_ACTIVO", "active"),
                                                entry("PREFIJO_FCREACION", "createdDate"),
                                                entry("PREFIJO_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("TIPOTELECFG")
                                .targetEntityClassName(
                                                "com.infomedia.abacox.telephonypricing.db.entity.TelephonyTypeConfig")
                                .sourceIdColumnName("TIPOTELECFG_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("TIPOTELECFG_ID", "id"),
                                                entry("TIPOTELECFG_MIN", "minValue"),
                                                entry("TIPOTELECFG_MAX", "maxValue"),
                                                entry("TIPOTELECFG_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("TIPOTELECFG_MPORIGEN_ID", "originCountryId"),
                                                // Assuming TIPOTELECFG doesn't have its own _ACTIVO, relies on parent
                                                // entities
                                                // If it has, add: entry("TIPOTELECFG_ACTIVO", "active"),
                                                entry("TIPOTELECFG_FCREACION", "createdDate"),
                                                entry("TIPOTELECFG_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 3: Depend on Level 2 or lower
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("BANDA")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Band")
                                .sourceIdColumnName("BANDA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("BANDA_ID", "id"),
                                                entry("BANDA_PREFIJO_ID", "prefixId"),
                                                entry("BANDA_NOMBRE", "name"),
                                                entry("BANDA_VALOR", "value"),
                                                entry("BANDA_INDICAORIGEN_ID", "originIndicatorId"),
                                                entry("BANDA_IVAINC", "vatIncluded"),
                                                entry("BANDA_REF", "reference"),
                                                entry("BANDA_ACTIVO", "active"),
                                                entry("BANDA_FCREACION", "createdDate"),
                                                entry("BANDA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("EMPRESA")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Company")
                                .sourceIdColumnName("EMPRESA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("EMPRESA_ID", "id"),
                                                entry("EMPRESA_ADICIONAL", "additionalInfo"),
                                                entry("EMPRESA_DIRECCION", "address"),
                                                entry("EMPRESA_EMPRESA", "name"),
                                                entry("EMPRESA_NIT", "taxId"),
                                                entry("EMPRESA_RSOCIAL", "legalName"),
                                                entry("EMPRESA_URL", "website"),
                                                entry("EMPRESA_INDICATIVO_ID", "indicatorId"),
                                                entry("EMPRESA_ACTIVO", "active"),
                                                entry("EMPRESA_FCREACION", "createdDate"),
                                                entry("EMPRESA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("COMUBICACION")
                                .targetEntityClassName(
                                                "com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation")
                                .sourceIdColumnName("COMUBICACION_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("COMUBICACION_ID", "id"),
                                                entry("COMUBICACION_DIRECTORIO", "directory"),
                                                entry("COMUBICACION_TIPOPLANTA_ID", "plantTypeId"),
                                                entry("COMUBICACION_SERIAL", "serial"),
                                                entry("COMUBICACION_INDICATIVO_ID", "indicatorId"),
                                                entry("COMUBICACION_PREFIJOPBX", "pbxPrefix"),
                                                entry("COMUBICACION_ACTIVO", "active"),
                                                entry("COMUBICACION_FCREACION", "createdDate"),
                                                entry("COMUBICACION_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("SERIE")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Series")
                                .sourceIdColumnName("SERIE_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("SERIE_ID", "id"),
                                                entry("SERIE_INDICATIVO_ID", "indicatorId"),
                                                entry("SERIE_NDC", "ndc"),
                                                entry("SERIE_INICIAL", "initialNumber"),
                                                entry("SERIE_FINAL", "finalNumber"),
                                                entry("SERIE_EMPRESA", "company"),
                                                entry("SERIE_ACTIVO", "active"),
                                                entry("SERIE_FCREACION", "createdDate"),
                                                entry("SERIE_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("servespecial")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.SpecialService")
                                .sourceIdColumnName("SERVESPECIAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("SERVESPECIAL_ID", "id"),
                                                entry("SERVESPECIAL_INDICATIVO_ID", "indicatorId"),
                                                entry("SERVESPECIAL_NUMERO", "phoneNumber"),
                                                entry("SERVESPECIAL_VALOR", "value"),
                                                entry("SERVESPECIAL_IVA", "vatAmount"),
                                                entry("SERVESPECIAL_IVAINC", "vatIncluded"),
                                                entry("SERVESPECIAL_DESCRIPCION", "description"),
                                                entry("SERVESPECIAL_MPORIGEN_ID", "originCountryId"),
                                                entry("SERVESPECIAL_ACTIVO", "active"),
                                                entry("SERVESPECIAL_FCREACION", "createdDate"),
                                                entry("SERVESPECIAL_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 4: Depend on Level 3 or lower (Self-referencing tables placed here)
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("SUBDIRECCION")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Subdivision")
                                .sourceIdColumnName("SUBDIRECCION_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("SUBDIRECCION_ID", "id"),
                                                entry("SUBDIRECCION_PERTENECE", "parentSubdivisionId"),
                                                entry("SUBDIRECCION_NOMBRE", "name"),
                                                entry("SUBDIRECCION_ACTIVO", "active"),
                                                entry("SUBDIREccion_FCREACION", "createdDate"),
                                                entry("SUBDIRECCION_FMODIFICADO", "lastModifiedDate")))
                                .selfReferencing(true)
                                .selfReferenceTargetForeignKeyFieldName("parentSubdivisionId")
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("CENTROCOSTOS")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CostCenter")
                                .sourceIdColumnName("CENTROCOSTOS_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("CENTROCOSTOS_ID", "id"),
                                                entry("CENTROCOSTOS_CENTRO_COSTO", "name"),
                                                entry("CENTROCOSTOS_OT", "workOrder"),
                                                entry("CENTROCOSTOS_PERTENECE", "parentCostCenterId"),
                                                entry("CENTROCOSTOS_MPORIGEN_ID", "originCountryId"),
                                                entry("CENTROCOSTOS_ACTIVO", "active"),
                                                entry("CENTROCOSTOS_FCREACION", "createdDate"),
                                                entry("CENTROCOSTOS_FMODIFICADO", "lastModifiedDate")))
                                .selfReferencing(true)
                                .selfReferenceTargetForeignKeyFieldName("parentCostCenterId")
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("BANDAINDICA")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.BandIndicator")
                                .sourceIdColumnName("BANDAINDICA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("BANDAINDICA_ID", "id"),
                                                entry("BANDAINDICA_BANDA_ID", "bandId"),
                                                entry("BANDAINDICA_INDICATIVO_ID", "indicatorId"),
                                                // BANDAINDICA doesn't have _ACTIVO, relies on Band and Indicator
                                                entry("BANDAINDICA_FCREACION", "createdDate"),
                                                entry("BANDAINDICA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("valorespecial")
                                .targetEntityClassName(
                                                "com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue")
                                .sourceIdColumnName("VALORESPECIAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("VALORESPECIAL_ID", "id"),
                                                entry("VALORESPECIAL_NOMBRE", "name"),
                                                entry("VALORESPECIAL_VALOR", "rateValue"),
                                                entry("VALORESPECIAL_IVAINC", "includesVat"),
                                                entry("VALORESPECIAL_DOMINGO", "sundayEnabled"),
                                                entry("VALORESPECIAL_LUNES", "mondayEnabled"),
                                                entry("VALORESPECIAL_MARTES", "tuesdayEnabled"),
                                                entry("VALORESPECIAL_MIERCOLES", "wednesdayEnabled"),
                                                entry("VALORESPECIAL_JUEVES", "thursdayEnabled"),
                                                entry("VALORESPECIAL_VIERNES", "fridayEnabled"),
                                                entry("VALORESPECIAL_SABADO", "saturdayEnabled"),
                                                entry("VALORESPECIAL_FESTIVO", "holidayEnabled"),
                                                entry("VALORESPECIAL_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("VALORESPECIAL_OPERADOR_ID", "operatorId"),
                                                entry("VALORESPECIAL_BANDA_ID", "bandId"),
                                                entry("VALORESPECIAL_DESDE", "validFrom"),
                                                entry("VALORESPECIAL_HASTA", "validTo"),
                                                entry("VALORESPECIAL_INDICAORIGEN_ID", "originIndicatorId"),
                                                entry("VALORESPECIAL_HORAS", "hoursSpecification"),
                                                entry("VALORESPECIAL_TIPOVALOR", "valueType"),
                                                entry("VALORESPECIAL_ACTIVO", "active"),
                                                entry("VALORESPECIAL_FCREACION", "createdDate"),
                                                entry("VALORESPECIAL_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("celulink")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Trunk")
                                .sourceIdColumnName("CELULINK_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("CELULINK_ID", "id"),
                                                entry("CELULINK_COMUBICACION_ID", "commLocationId"),
                                                entry("CELULINK_DESC", "description"),
                                                entry("CELULINK_TRONCAL", "name"),
                                                entry("CELULINK_OPERADOR_ID", "operatorId"),
                                                entry("CELULINK_NOPREFIJOPBX", "noPbxPrefix"),
                                                entry("CELULINK_CANALES", "channels"),
                                                entry("CELULINK_ACTIVO", "active"),
                                                entry("CELULINK_FCREACION", "createdDate"),
                                                entry("CELULINK_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 5: Depend on Level 4 or lower
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("FUNCIONARIO")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Employee")
                                .sourceIdColumnName("FUNCIONARIO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("FUNCIONARIO_ID", "id"),
                                                entry("FUNCIONARIO_NOMBRE", "name"),
                                                entry("FUNCIONARIO_SUBDIRECCION_ID", "subdivisionId"),
                                                entry("FUNCIONARIO_CENTROCOSTOS_ID", "costCenterId"),
                                                entry("FUNCIONARIO_CLAVE", "authCode"),
                                                entry("FUNCIONARIO_EXTENSION", "extension"),
                                                entry("FUNCIONARIO_COMUBICACION_ID", "communicationLocationId"),
                                                entry("FUNCIONARIO_FUNCARGO_ID", "jobPositionId"),
                                                entry("FUNCIONARIO_CORREO", "email"),
                                                entry("FUNCIONARIO_TELEFONO", "phone"),
                                                entry("FUNCIONARIO_DIRECCION", "address"),
                                                entry("FUNCIONARIO_NUMEROID", "idNumber"),
                                                entry("FUNCIONARIO_ACTIVO", "active"), // This will be overridden by
                                                                                       // Pass 3 if enabled
                                                entry("FUNCIONARIO_FCREACION", "createdDate"),
                                                entry("FUNCIONARIO_FMODIFICADO", "lastModifiedDate")))
                                .processHistoricalActiveness(true) // Enable Pass 3
                                .sourceHistoricalControlIdColumn("FUNCIONARIO_HISTORICTL_ID")
                                .sourceValidFromDateColumn("FUNCIONARIO_HISTODESDE")
                                .postMigrationSuccessAction(() -> {
                                        int n = employeeRepository.deactivateDuplicateActiveEmployees();
                                        log.info("Post-migration action: Deactivated {} duplicate active employees.",
                                                        n);
                                })
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("rangoext")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange")
                                .sourceIdColumnName("RANGOEXT_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("RANGOEXT_ID", "id"),
                                                entry("RANGOEXT_COMUBICACION_ID", "commLocationId"),
                                                entry("RANGOEXT_SUBDIRECCION_ID", "subdivisionId"),
                                                entry("RANGOEXT_PREFIJO", "prefix"),
                                                entry("RANGOEXT_DESDE", "rangeStart"),
                                                entry("RANGOEXT_HASTA", "rangeEnd"),
                                                entry("RANGOEXT_CENTROCOSTOS_ID", "costCenterId"),
                                                entry("RANGOEXT_FCREACION", "createdDate"),
                                                entry("RANGOEXT_FMODIFICADO", "lastModifiedDate")))
                                .processHistoricalActiveness(true) // Enable Pass 3
                                .sourceHistoricalControlIdColumn("RANGOEXT_HISTORICTL_ID")
                                .sourceValidFromDateColumn("RANGOEXT_HISTODESDE")
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("pbxespecial")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.PbxSpecialRule")
                                .sourceIdColumnName("PBXESPECIAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("PBXESPECIAL_ID", "id"),
                                                entry("PBXESPECIAL_NOMBRE", "name"),
                                                entry("PBXESPECIAL_BUSCAR", "searchPattern"),
                                                entry("PBXESPECIAL_IGNORAR", "ignorePattern"),
                                                entry("PBXESPECIAL_REMPLAZO", "replacement"),
                                                entry("PBXESPECIAL_COMUBICACION_ID", "commLocationId"),
                                                entry("PBXESPECIAL_MINLEN", "minLength"),
                                                entry("PBXESPECIAL_IO", "direction"),
                                                entry("PBXESPECIAL_ACTIVO", "active"),
                                                entry("PBXESPECIAL_FCREACION", "createdDate"),
                                                entry("PBXESPECIAL_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("tarifatroncal")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TrunkRate")
                                .sourceIdColumnName("TARIFATRONCAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("TARIFATRONCAL_ID", "id"),
                                                entry("TARIFATRONCAL_TRONCAL_ID", "trunkId"),
                                                entry("TARIFATRONCAL_VALOR", "rateValue"),
                                                entry("TARIFATRONCAL_IVAINC", "includesVat"),
                                                entry("TARIFATRONCAL_OPERADOR_ID", "operatorId"),
                                                entry("TARIFATRONCAL_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("TARIFATRONCAL_NOPREFIJOPBX", "noPbxPrefix"),
                                                entry("TARIFATRONCAL_NOPREFIJO", "noPrefix"),
                                                entry("TARIFATRONCAL_SEGUNDOS", "seconds"),
                                                entry("TARIFATRONCAL_ACTIVO", "active"), // Assuming TARIFATRONCAL has
                                                                                         // _ACTIVO
                                                entry("TARIFATRONCAL_FCREACION", "createdDate"),
                                                entry("TARIFATRONCAL_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // ADDED: OfficeDetails (DATOSOFICINA) - Depends on Subdivision (L4) and
                // Indicator (L2)
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("DATOSOFICINA")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails")
                                .sourceIdColumnName("DATOSOFICINA_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("DATOSOFICINA_ID", "id"),
                                                entry("DATOSOFICINA_SUBDIRECCION_ID", "subdivisionId"),
                                                entry("DATOSOFICINA_DIRECCION", "address"),
                                                entry("DATOSOFICINA_TELEFONO", "phone"),
                                                entry("DATOSOFICINA_INDICATIVO_ID", "indicatorId"),
                                                entry("DATOSOFICINA_ACTIVO", "active"),
                                                entry("DATOSOFICINA_FCREACION", "createdDate"),
                                                entry("DATOSOFICINA_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 6: Depend on Level 5 or lower
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("DIRECTORIO")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Contact")
                                .sourceIdColumnName("DIRECTORIO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("DIRECTORIO_ID", "id"),
                                                entry("DIRECTORIO_TIPO", "contactType"),
                                                entry("DIRECTORIO_FUNCIONARIO_ID", "employeeId"),
                                                entry("DIRECTORIO_EMPRESA_ID", "companyId"),
                                                entry("DIRECTORIO_TELEFONO", "phoneNumber"),
                                                entry("DIRECTORIO_NOMBRE", "name"),
                                                entry("DIRECTORIO_DESCRIPCION", "description"),
                                                entry("DIRECTORIO_INDICATIVO_ID", "indicatorId"),
                                                entry("DIRECTORIO_ACTIVO", "active"),
                                                entry("DIRECTORIO_FCREACION", "createdDate"),
                                                entry("DIRECTORIO_FMODIFICADO", "lastModifiedDate")))
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("reglatroncal")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TrunkRule")
                                .sourceIdColumnName("REGLATRONCAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("REGLATRONCAL_ID", "id"),
                                                entry("REGLATRONCAL_VALOR", "rateValue"),
                                                entry("REGLATRONCAL_IVAINC", "includesVat"),
                                                entry("REGLATRONCAL_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("REGLATRONCAL_INDICATIVO_ID", "indicatorIds"),
                                                entry("REGLATRONCAL_TRONCAL_ID", "trunkId"),
                                                entry("REGLATRONCAL_OPERADOR_NUEVO", "newOperatorId"),
                                                entry("REGLATRONCAL_TIPOTELE_NUEVO", "newTelephonyTypeId"),
                                                entry("REGLATRONCAL_SEGUNDOS", "seconds"),
                                                entry("REGLATRONCAL_INDICAORIGEN_ID", "originIndicatorId"),
                                                entry("REGLATRONCAL_ACTIVO", "active"),
                                                entry("REGLATRONCAL_FCREACION", "createdDate"),
                                                entry("REGLATRONCAL_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // ADDED: SubdivisionManager (JEFESUBDIR) - Depends on Subdivision (L4) and
                // Employee (L5)
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("JEFESUBDIR")
                                .targetEntityClassName(
                                                "com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager")
                                .sourceIdColumnName("JEFESUBDIR_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("JEFESUBDIR_ID", "id"),
                                                entry("JEFESUBDIR_SUBDIRECCION_ID", "subdivisionId"),
                                                entry("JEFESUBDIR_JEFE", "managerId"),
                                                entry("JEFESUBDIR_ACTIVO", "active"),
                                                entry("JEFESUBDIR_FCREACION", "createdDate"),
                                                entry("JEFESUBDIR_FMODIFICADO", "lastModifiedDate")))
                                .build());

                // Level 7: fileinfo (if needed, often populated by application logic, not
                // direct migration)
                // Assuming fileinfo is populated by the CDR processing logic itself, not a
                // direct table migration from source.
                // If it IS a direct migration:
                /*
                 * configs.add(TableMigrationConfig.builder()
                 * .sourceTableName("fileinfo") // Assuming source table name
                 * .targetEntityClassName(
                 * "com.infomedia.abacox.telephonypricing.db.entity.FileInfo")
                 * .sourceIdColumnName("FILEINFO_ID")
                 * .targetIdFieldName("id")
                 * .columnMapping(Map.ofEntries(
                 * entry("FILEINFO_ID", "id"),
                 * entry("FILEINFO_ARCHIVO", "filename"),
                 * entry("FILEINFO_PERTENECE", "parentId"),
                 * entry("FILEINFO_TAMANO", "size"),
                 * entry("FILEINFO_FECHA", "date"),
                 * entry("FILEINFO_CTL", "checksum"),
                 * entry("FILEINFO_REF_ID", "referenceId"),
                 * entry("FILEINFO_DIRECTORIO", "directory"),
                 * entry("FILEINFO_TIPO", "type")
                 * // FILEINFO typically doesn't have _FCREACION/_FMODIFICADO in the old system
                 * ))
                 * .build());
                 */

                // Level 8: ACUMTOTAL (CallRecord) - This is the final target, usually populated
                // by CDR processing,
                // but if you are migrating existing ACUMTOTAL data:
                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("ACUMTOTAL")
                                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CallRecord")
                                .sourceIdColumnName("ACUMTOTAL_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("ACUMTOTAL_ID", "id"),
                                                entry("ACUMTOTAL_DIAL", "dial"),
                                                entry("ACUMTOTAL_COMUBICACION_ID", "commLocationId"),
                                                entry("ACUMTOTAL_FECHA_SERVICIO", "serviceDate"),
                                                entry("ACUMTOTAL_OPERADOR_ID", "operatorId"),
                                                entry("ACUMTOTAL_FUN_EXTENSION", "employeeExtension"),
                                                entry("ACUMTOTAL_FUN_CLAVE", "employeeAuthCode"),
                                                entry("ACUMTOTAL_INDICATIVO_ID", "indicatorId"),
                                                entry("ACUMTOTAL_TELEFONO_DESTINO", "destinationPhone"),
                                                entry("ACUMTOTAL_TIEMPO", "duration"),
                                                entry("ACUMTOTAL_REPIQUE", "ringCount"),
                                                entry("ACUMTOTAL_TIPOTELE_ID", "telephonyTypeId"),
                                                entry("ACUMTOTAL_VALOR_FACTURADO", "billedAmount"),
                                                entry("ACUMTOTAL_PRECIOMINUTO", "pricePerMinute"),
                                                entry("ACUMTOTAL_PRECIOINICIAL", "initialPrice"),
                                                entry("ACUMTOTAL_IO", "isIncoming"),
                                                entry("ACUMTOTAL_TRONCAL", "trunk"),
                                                entry("ACUMTOTAL_TRONCALINI", "initialTrunk"),
                                                entry("ACUMTOTAL_FUNCIONARIO_ID", "employeeId"),
                                                entry("ACUMTOTAL_FUN_TRANSFER", "employeeTransfer"),
                                                entry("ACUMTOTAL_CAUSA_TRANSFER", "transferCause"),
                                                entry("ACUMTOTAL_CAUSA_ASIGNA", "assignmentCause"),
                                                entry("ACUMTOTAL_FUNDESTINO_ID", "destinationEmployeeId"),
                                                entry("ACUMTOTAL_FCREACION", "createdDate"),
                                                entry("ACUMTOTAL_FMODIFICADO", "lastModifiedDate")))
                                .maxEntriesToMigrate(runRequest.getMaxCallRecordEntries())
                                .orderByClause("ACUMTOTAL_ID DESC")
                                .build());

                configs.add(TableMigrationConfig.builder()
                                .sourceTableName("acumfallido")
                                .targetEntityClassName(
                                                "com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord")
                                .sourceIdColumnName("ACUMFALLIDO_ID")
                                .targetIdFieldName("id")
                                .columnMapping(Map.ofEntries(
                                                entry("ACUMFALLIDO_ID", "id"),
                                                entry("ACUMFALLIDO_EXTENSION", "employeeExtension"),
                                                entry("ACUMFALLIDO_TIPO", "errorType"), // Note: smallint to String
                                                                                        // conversion will be attempted
                                                                                        // by the migrator
                                                entry("ACUMFALLIDO_MENSAJE", "errorMessage"),
                                                entry("ACUMFALLIDO_ACUMTOTAL_ID", "originalCallRecordId"),
                                                entry("ACUMFALLIDO_COMUBICACION_ID", "commLocationId"),
                                                // AuditedEntity fields
                                                entry("ACUMFALLIDO_FCREACION", "createdDate"),
                                                entry("ACUMFALLIDO_FMODIFICADO", "lastModifiedDate")))
                                .maxEntriesToMigrate(runRequest.getMaxFailedCallRecordEntries()) // Limit for
                                                                                                 // performance, similar
                                                                                                 // to CallRecord
                                .orderByClause("ACUMFALLIDO_ID DESC") // Get the most recent failures first
                                .build());

                return configs;
        }

        public enum MigrationState {
                IDLE, STARTING, RUNNING, COMPLETED, FAILED
        }
}