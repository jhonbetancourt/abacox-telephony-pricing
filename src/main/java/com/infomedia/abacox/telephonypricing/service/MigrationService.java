package com.infomedia.abacox.telephonypricing.service; // Use your actual package

import com.infomedia.abacox.telephonypricing.component.migration.DataMigrationExecutor;
import com.infomedia.abacox.telephonypricing.component.migration.MigrationParams;
import com.infomedia.abacox.telephonypricing.component.migration.SourceDbConfig;
import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStatus;
import com.infomedia.abacox.telephonypricing.exception.MigrationAlreadyInProgressException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService; // Added
import java.util.concurrent.Executors;
import java.util.concurrent.Future; // Added
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;

@Service
@Log4j2
@RequiredArgsConstructor
public class MigrationService {

    private final DataMigrationExecutor dataMigrationExecutor;
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
    private final AtomicReference<Future<?>> migrationTaskFuture = new AtomicReference<>(null); // To potentially cancel
    // --- End State Tracking ---

    /**
     * Initiates the data migration asynchronously using a dedicated ExecutorService.
     * Throws MigrationAlreadyInProgressException if a migration is already running.
     *
     * @param runRequest The parameters for the migration.
     */
    public void startAsync(MigrationStart runRequest) {
        // Use compareAndSet for atomicity, although isMigrationRunning.get() check is likely sufficient
        // because the actual execution is serialized by the single-thread executor.
        // However, compareAndSet is robust against potential (though unlikely) races here.
        if (!isMigrationRunning.compareAndSet(false, true)) {
             throw new MigrationAlreadyInProgressException("A data migration is already in progress.");
        }

        log.info("Submitting migration task to executor service.");
        try {
            // Reset state *before* submitting the task
            resetMigrationState();

            // Submit the actual migration logic to run asynchronously
            Future<?> future = migrationExecutorService.submit(() -> {
                start(runRequest); // Call the private method containing the logic
            });
            migrationTaskFuture.set(future); // Store the future if cancellation is needed

        } catch (Exception e) {
            // Handle potential submission errors (e.g., RejectedExecutionException if executor is shutting down)
            log.error("Failed to submit migration task to executor service", e);
            currentState.set(MigrationState.FAILED);
            errorMessage.set("Failed to start migration task: " + e.getMessage());
            endTime.set(LocalDateTime.now());
            isMigrationRunning.set(false); // Ensure state is reset if submission fails
            migrationTaskFuture.set(null);
            // Rethrow or handle as appropriate for your application
            // throw new RuntimeException("Failed to submit migration task", e);
        }
    }

    /**
     * Resets the internal state variables before starting a new migration.
     */
    private void resetMigrationState() {
        currentState.set(MigrationState.STARTING); // Indicate preparing to run
        startTime.set(LocalDateTime.now());
        endTime.set(null);
        errorMessage.set(null);
        migratedTables.set(0);
        totalTables.set(0);
        currentStep.set("Initializing...");
        migrationTaskFuture.set(null); // Clear previous future
        log.debug("Migration state reset.");
    }


    /**
     * Contains the core logic for performing the data migration.
     * This method is executed asynchronously via the migrationExecutorService.
     * It should NOT be called directly from outside startAsync.
     *
     * @param runRequest The parameters for the migration.
     */
    public void start(MigrationStart runRequest) {
        isMigrationRunning.set(true);
        log.info("<<<<<<<<<< Starting Full Data Migration Execution >>>>>>>>>>");
        currentState.set(MigrationState.RUNNING); // Now actually running
        List<TableMigrationConfig> tablesToMigrate = List.of();
        try {
            String url = "jdbc:sqlserver://" + runRequest.getHost() + ":" + runRequest.getPort() + ";databaseName="
                    + runRequest.getDatabase() + ";encrypt=" + runRequest.getEncryption() + ";trustServerCertificate="
                    + runRequest.getTrustServerCertificate() + ";";

            SourceDbConfig sourceDbConfig = SourceDbConfig.builder()
                    .url(url)
                    .username(runRequest.getUsername())
                    .password(runRequest.getPassword())
                    .build();

            tablesToMigrate = defineMigrationOrderAndMappings();
            int totalTableCount = tablesToMigrate.size();
            totalTables.set(totalTableCount);

            MigrationParams params = new MigrationParams(sourceDbConfig, tablesToMigrate);

            log.info("Constructed migration params with {} tables. Starting execution.", totalTableCount);
            currentStep.set(String.format("Starting migration of %d tables...", totalTableCount));

            // Pass the progress reporting method reference
            dataMigrationExecutor.runMigration(params, this::reportProgress);

            // If runMigration completes without exception
            currentState.set(MigrationState.COMPLETED);
            currentStep.set(String.format("Finished: Successfully migrated %d/%d tables.", migratedTables.get(), totalTables.get()));
            log.info("<<<<<<<<<< Full Data Migration Finished Successfully >>>>>>>>>>");

        } catch (Exception e) {
            // Catch exceptions from runMigration or setup
            log.error("<<<<<<<<<< Full Data Migration FAILED during execution >>>>>>>>>>", e);
            currentState.set(MigrationState.FAILED);
            String failureMsg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
            errorMessage.set("Migration failed: " + failureMsg);
            // Ensure the step reflects failure if not already set by reportProgress
            if (!currentStep.get().toLowerCase().contains("failed")) {
                 currentStep.set(String.format("Failed during migration (%d/%d tables completed). Error: %s",
                         migratedTables.get(), totalTables.get(), failureMsg));
            }
             log.error("Final migration status: FAILED. Error: {}", errorMessage.get());
             // Optional: Rethrow if the executor's exception handling mechanism needs it,
             // but typically logging and setting state is sufficient for background tasks.
             // throw new RuntimeException("Migration failed", e);

        } finally {
            endTime.set(LocalDateTime.now());
            isMigrationRunning.set(false); // Release the lock
            migrationTaskFuture.set(null); // Clear the future on completion/failure
            log.info("Migration process ended. State: {}, Duration: {}", currentState.get(),
                    startTime.get() != null && endTime.get() != null ?
                            java.time.Duration.between(startTime.get(), endTime.get()) : "N/A");
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

    /**
     * Callback method invoked by DataMigrationExecutor after processing each table.
     * Updates the migration progress status.
     *
     * @param config The configuration of the table just processed.
     * @param error  The exception that occurred during processing, or null if successful.
     */
    private void reportProgress(TableMigrationConfig config, Exception error) {
        int currentTotal = totalTables.get(); // Get snapshot of total

        if (error == null) {
            // Success for this table
            int completedCount = migratedTables.incrementAndGet();
            String stepMessage = String.format("Migrated table %d/%d: %s",
                    completedCount, currentTotal, config.getSourceTableName());
            currentStep.set(stepMessage);
            log.debug(stepMessage); // Keep debug for successful steps
        } else {
            // Failure for this table
            int completedCount = migratedTables.get();
            String stepMessage = String.format("Failed on table %d/%d: %s. Error: %s",
                    completedCount + 1, // Show user which table number failed (e.g., 5/10)
                    currentTotal,
                    config.getSourceTableName(),
                    error.getMessage());
            currentStep.set(stepMessage);
            errorMessage.set(error.getMessage()); // Set the specific error message
            // The main performMigration() catch block will set the final FAILED state
            log.warn("Progress update: Failure on table {}. Error: {}", config.getSourceTableName(), error.getMessage());
        }
    }


    // --- defineMigrationOrderAndMappings() method remains the same ---
    private List<TableMigrationConfig> defineMigrationOrderAndMappings() {
       // ... (Keep the existing long list of table configurations)
        List<TableMigrationConfig> configs = new ArrayList<>();
        // --- Order based on dependencies ---

        // Level 0: No FK dependencies among these
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("MPORIGEN")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.OriginCountry")
                .sourceIdColumnName("MPORIGEN_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("MPORIGEN_ID", "id"),
                        entry("MPORIGEN_SIMBOLO", "currencySymbol"),
                        entry("MPORIGEN_PAIS", "name"),
                        entry("MPORIGEN_CCODE", "code"),
                        entry("MPORIGEN_ACTIVO", "active")
                ))
                // .treatZeroIdAsNullForForeignKeys(true) // Uses default
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("CIUDADES")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.City")
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
                        entry("CIUDADES_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("tipoplanta")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.PlantType")
                .sourceIdColumnName("TIPOPLANTA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOPLANTA_ID", "id"),
                        entry("TIPOPLANTA_NOMBRE", "name"),
                        entry("TIPOPLANTA_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("funcargo")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.JobPosition")
                .sourceIdColumnName("FUNCARGO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FUNCARGO_ID", "id"),
                        entry("FUNCARGO_NOMBRE", "name"),
                        entry("FUNCARGO_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("CLASELLAMA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.CallCategory")
                .sourceIdColumnName("CLASELLAMA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("CLASELLAMA_ID", "id"),
                        entry("CLASELLAMA_NOMBRE", "name"),
                        entry("CLASELLAMA_ACTIVO", "active")
                ))
                .build());

        // Level 1: Depend on Level 0
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("OPERADOR")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Operator")
                .sourceIdColumnName("OPERADOR_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("OPERADOR_ID", "id"),
                        entry("OPERADOR_NOMBRE", "name"),
                        entry("OPERADOR_MPORIGEN_ID", "originCountryId"), // FK
                        entry("OPERADOR_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("TIPOTELE")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.TelephonyType")
                .sourceIdColumnName("TIPOTELE_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOTELE_ID", "id"),
                        entry("TIPOTELE_NOMBRE", "name"),
                        entry("TIPOTELE_CLASELLAMA_ID", "callCategoryId"), // FK
                        entry("TIPOTELE_TRONCALES", "usesTrunks"),
                        entry("TIPOTELE_ACTIVO", "active")
                ))
                .build());

        // Level 2: Depend on Level 1 or 0
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("INDICATIVO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Indicator")
                .sourceIdColumnName("INDICATIVO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INDICATIVO_ID", "id"),
                        entry("INDICATIVO_TIPOTELE_ID", "telephonyTypeId"), // FK
                        entry("INDICATIVO_DPTO_PAIS", "departmentCountry"),
                        entry("INDICATIVO_CIUDAD", "cityName"),
                        entry("INDICATIVO_OPERADOR_ID", "operatorId"), // FK
                        entry("INDICATIVO_MPORIGEN_ID", "originCountryId"), // FK
                        entry("INDICATIVO_ENUSO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("PREFIJO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Prefix")
                .sourceIdColumnName("PREFIJO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("PREFIJO_ID", "id"),
                        entry("PREFIJO_OPERADOR_ID", "operatorId"), // FK
                        entry("PREFIJO_TIPOTELE_ID", "telephoneTypeId"), // FK
                        entry("PREFIJO_PREFIJO", "code"),
                        entry("PREFIJO_VALORBASE", "baseValue"),
                        entry("PREFIJO_BANDAOK", "bandOk"),
                        entry("PREFIJO_IVAINC", "vatIncluded"),
                        entry("PREFIJO_IVA", "vatValue"),
                        entry("PREFIJO_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("TIPOTELECFG")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig")
                .sourceIdColumnName("TIPOTELECFG_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOTELECFG_ID", "id"),
                        entry("TIPOTELECFG_MIN", "minValue"),
                        entry("TIPOTELECFG_MAX", "maxValue"),
                        entry("TIPOTELECFG_TIPOTELE_ID", "telephonyTypeId"), // FK
                        entry("TIPOTELECFG_MPORIGEN_ID", "originCountryId") // FK
                ))
                .build());

        // Level 3: Depend on Level 2 or lower
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("BANDA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Band")
                .sourceIdColumnName("BANDA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("BANDA_ID", "id"),
                        entry("BANDA_PREFIJO_ID", "prefixId"), // FK
                        entry("BANDA_NOMBRE", "name"),
                        entry("BANDA_VALOR", "value"),
                        entry("BANDA_INDICAORIGEN_ID", "originIndicatorId"), // FK
                        entry("BANDA_IVAINC", "vatIncluded"),
                        entry("BANDA_REF", "reference"),
                        entry("BANDA_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("EMPRESA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Company")
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
                        entry("EMPRESA_INDICATIVO_ID", "indicatorId"), // FK
                        entry("EMPRESA_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("COMUBICACION")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.CommunicationLocation")
                .sourceIdColumnName("COMUBICACION_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("COMUBICACION_ID", "id"),
                        entry("COMUBICACION_DIRECTORIO", "directory"),
                        entry("COMUBICACION_TIPOPLANTA_ID", "plantTypeId"), // FK
                        entry("COMUBICACION_SERIAL", "serial"),
                        entry("COMUBICACION_INDICATIVO_ID", "indicatorId"), // FK
                        entry("COMUBICACION_PREFIJOPBX", "pbxPrefix"),
                        entry("COMUBICACION_FECHACAPTURA", "captureDate"),
                        entry("COMUBICACION_CDRS", "cdrCount"),
                        entry("COMUBICACION_ARCHIVO", "fileName"),
                        entry("COMUBICACION_CABECERA_ID", "headerId"),
                        entry("COMUBICACION_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("SERIE")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Series")
                .sourceIdColumnName("SERIE_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SERIE_ID", "id"),
                        entry("SERIE_INDICATIVO_ID", "indicatorId"), // FK
                        entry("SERIE_NDC", "ndc"),
                        entry("SERIE_INICIAL", "initialNumber"),
                        entry("SERIE_FINAL", "finalNumber"),
                        entry("SERIE_EMPRESA", "company"),
                        entry("SERIE_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("servespecial")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.SpecialService")
                .sourceIdColumnName("SERVESPECIAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SERVESPECIAL_ID", "id"),
                        entry("SERVESPECIAL_INDICATIVO_ID", "indicatorId"), // FK
                        entry("SERVESPECIAL_NUMERO", "phoneNumber"),
                        entry("SERVESPECIAL_VALOR", "value"),
                        entry("SERVESPECIAL_IVA", "vatAmount"),
                        entry("SERVESPECIAL_IVAINC", "vatIncluded"),
                        entry("SERVESPECIAL_DESCRIPCION", "description"),
                        entry("SERVESPECIAL_MPORIGEN_ID", "originCountryId"), // FK
                        entry("SERVESPECIAL_ACTIVO", "active")
                ))
                .build());

        // Level 4: Depend on Level 3 or lower (Self-referencing tables placed here)
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("SUBDIRECCION")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Subdivision")
                .sourceIdColumnName("SUBDIRECCION_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SUBDIRECCION_ID", "id"),
                        entry("SUBDIRECCION_PERTENECE", "parentSubdivisionId"), // FK (Self-ref handled by executor)
                        entry("SUBDIRECCION_NOMBRE", "name"),
                        entry("SUBDIRECCION_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("CENTROCOSTOS")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.CostCenter")
                .sourceIdColumnName("CENTROCOSTOS_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("CENTROCOSTOS_ID", "id"),
                        entry("CENTROCOSTOS_CENTRO_COSTO", "name"),
                        entry("CENTROCOSTOS_OT", "workOrder"),
                        entry("CENTROCOSTOS_PERTENECE", "parentCostCenterId"), // FK (Self-ref handled by executor)
                        entry("CENTROCOSTOS_MPORIGEN_ID", "originCountryId"), // FK
                        entry("CENTROCOSTOS_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("BANDAINDICA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.BandIndicator")
                .sourceIdColumnName("BANDAINDICA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("BANDAINDICA_ID", "id"),
                        entry("BANDAINDICA_BANDA_ID", "bandId"), // FK
                        entry("BANDAINDICA_INDICATIVO_ID", "indicatorId") // FK
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("valorespecial")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.SpecialRateValue")
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
                        entry("VALORESPECIAL_TIPOTELE_ID", "telephonyTypeId"), // FK
                        entry("VALORESPECIAL_OPERADOR_ID", "operatorId"), // FK
                        entry("VALORESPECIAL_BANDA_ID", "bandId"), // FK
                        entry("VALORESPECIAL_DESDE", "validFrom"),
                        entry("VALORESPECIAL_HASTA", "validTo"),
                        entry("VALORESPECIAL_INDICAORIGEN_ID", "originIndicatorId"), // FK
                        entry("VALORESPECIAL_HORAS", "hoursSpecification"),
                        entry("VALORESPECIAL_TIPOVALOR", "valueType"),
                        entry("VALORESPECIAL_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("celulink") // Source table for Trunk
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Trunk")
                .sourceIdColumnName("CELULINK_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("CELULINK_ID", "id"),
                        entry("CELULINK_COMUBICACION_ID", "commLocationId"), // FK
                        entry("CELULINK_DESC", "description"),
                        entry("CELULINK_TRONCAL", "name"),
                        entry("CELULINK_OPERADOR_ID", "operatorId"), // FK
                        entry("CELULINK_NOPREFIJOPBX", "noPbxPrefix"),
                        entry("CELULINK_CANALES", "channels"),
                        entry("CELULINK_ACTIVO", "active")
                ))
                .build());

        // Level 5: Depend on Level 4 or lower
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("FUNCIONARIO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Employee")
                .sourceIdColumnName("FUNCIONARIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FUNCIONARIO_ID", "id"),
                        entry("FUNCIONARIO_NOMBRE", "name"),
                        entry("FUNCIONARIO_SUBDIRECCION_ID", "subdivisionId"), // FK
                        entry("FUNCIONARIO_CENTROCOSTOS_ID", "costCenterId"), // FK
                        entry("FUNCIONARIO_CLAVE", "authCode"),
                        entry("FUNCIONARIO_EXTENSION", "extension"),
                        entry("FUNCIONARIO_COMUBICACION_ID", "communicationLocationId"), // FK
                        entry("FUNCIONARIO_FUNCARGO_ID", "jobPositionId"), // FK
                        entry("FUNCIONARIO_CORREO", "email"),
                        entry("FUNCIONARIO_TELEFONO", "phone"),
                        entry("FUNCIONARIO_DIRECCION", "address"),
                        entry("FUNCIONARIO_NUMEROID", "idNumber"),
                        entry("FUNCIONARIO_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("rangoext") // Source table for ExtensionRange
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.ExtensionRange")
                .sourceIdColumnName("RANGOEXT_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("RANGOEXT_ID", "id"),
                        entry("RANGOEXT_COMUBICACION_ID", "commLocationId"), // FK
                        entry("RANGOEXT_SUBDIRECCION_ID", "subdivisionId"), // FK
                        entry("RANGOEXT_PREFIJO", "prefix"),
                        entry("RANGOEXT_DESDE", "rangeStart"),
                        entry("RANGOEXT_HASTA", "rangeEnd"),
                        entry("RANGOEXT_CENTROCOSTOS_ID", "costCenterId"), // FK
                        entry("RANGOEXT_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("pbxespecial") // Source table for PbxSpecialRule
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule")
                .sourceIdColumnName("PBXESPECIAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("PBXESPECIAL_ID", "id"),
                        entry("PBXESPECIAL_NOMBRE", "name"),
                        entry("PBXESPECIAL_BUSCAR", "searchPattern"),
                        entry("PBXESPECIAL_IGNORAR", "ignorePattern"),
                        entry("PBXESPECIAL_REMPLAZO", "replacement"),
                        entry("PBXESPECIAL_COMUBICACION_ID", "commLocationId"), // FK
                        entry("PBXESPECIAL_MINLEN", "minLength"),
                        entry("PBXESPECIAL_IO", "direction"),
                        entry("PBXESPECIAL_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("tarifatroncal") // Source table for TrunkRate
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.TrunkRate")
                .sourceIdColumnName("TARIFATRONCAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TARIFATRONCAL_ID", "id"),
                        entry("TARIFATRONCAL_TRONCAL_ID", "trunkId"), // FK
                        entry("TARIFATRONCAL_VALOR", "rateValue"),
                        entry("TARIFATRONCAL_IVAINC", "includesVat"),
                        entry("TARIFATRONCAL_OPERADOR_ID", "operatorId"), // FK
                        entry("TARIFATRONCAL_TIPOTELE_ID", "telephonyTypeId"), // FK
                        entry("TARIFATRONCAL_NOPREFIJOPBX", "noPbxPrefix"),
                        entry("TARIFATRONCAL_NOPREFIJO", "noPrefix"),
                        entry("TARIFATRONCAL_SEGUNDOS", "seconds"
                        )))
                .build());

        // Level 6: Depend on Level 5 or lower
        configs.add(TableMigrationConfig.builder()
                .sourceTableName("DIRECTORIO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.Contact")
                .sourceIdColumnName("DIRECTORIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("DIRECTORIO_ID", "id"),
                        entry("DIRECTORIO_TIPO", "contactType"),
                        entry("DIRECTORIO_FUNCIONARIO_ID", "employeeId"), // FK
                        entry("DIRECTORIO_EMPRESA_ID", "companyId"), // FK
                        entry("DIRECTORIO_TELEFONO", "phoneNumber"),
                        entry("DIRECTORIO_NOMBRE", "name"),
                        entry("DIRECTORIO_DESCRIPCION", "description"),
                        entry("DIRECTORIO_INDICATIVO_ID", "indicatorId"), // FK
                        entry("DIRECTORIO_ACTIVO", "active")
                ))
                .build());

        configs.add(TableMigrationConfig.builder()
                .sourceTableName("reglatroncal") // Source table for TrunkRule
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.TrunkRule")
                .sourceIdColumnName("REGLATRONCAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("REGLATRONCAL_ID", "id"),
                        entry("REGLATRONCAL_VALOR", "rateValue"),
                        entry("REGLATRONCAL_IVAINC", "includesVat"),
                        entry("REGLATRONCAL_TIPOTELE_ID", "telephonyTypeId"), // FK
                        entry("REGLATRONCAL_INDICATIVO_ID", "indicatorIds"),
                        entry("REGLATRONCAL_TRONCAL_ID", "trunkId"), // FK
                        entry("REGLATRONCAL_OPERADOR_NUEVO", "newOperatorId"), // FK
                        entry("REGLATRONCAL_TIPOTELE_NUEVO", "newTelephonyTypeId"), // FK
                        entry("REGLATRONCAL_SEGUNDOS", "seconds"),
                        entry("REGLATRONCAL_INDICAORIGEN_ID", "originIndicatorId"), // FK
                        entry("REGLATRONCAL_ACTIVO", "active")
                ))
                .build());

        // Level 7: Independent or depends on lower levels (FileInfo)
       /* configs.add(TableMigrationConfig.builder()
                .sourceTableName("fileinfo")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.entity.FileInfo")
                .sourceIdColumnName("FILEINFO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FILEINFO_ID", "id"),
                        entry("FILEINFO_ARCHIVO", "filename"),
                        entry("FILEINFO_PERTENECE", "parentId"),
                        entry("FILEINFO_TAMANO", "size"),
                        entry("FILEINFO_FECHA", "date"),
                        entry("FILEINFO_CTL", "checksum"),
                        entry("FILEINFO_REF_ID", "referenceId"),
                        entry("FILEINFO_DIRECTORIO", "directory"),
                        entry("FILEINFO_TIPO", "type")
                ))
                .build());*/
        return configs;
    }

    // Added STARTING state
    public enum MigrationState {
        IDLE,        // No migration running, none run yet or last one finished
        STARTING,    // Submitted to executor, preparing to run
        RUNNING,     // Migration is currently executing
        COMPLETED,   // Last migration finished successfully
        FAILED       // Last migration failed
    }
}