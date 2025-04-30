package com.infomedia.abacox.telephonypricing.migration; // Use your actual package

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

@Component
@Log4j2
@RequiredArgsConstructor
public class MigrationRunner {

    private final DataMigrationExecutor dataMigrationExecutor;

    public void run(SourceDbConfig sourceDbConfig) {
        log.info("<<<<<<<<<< STARTING Full Data Migration >>>>>>>>>>");
        try {
            List<TableMigrationConfig> tablesToMigrate = defineMigrationOrderAndMappings();
            MigrationRequest request = new MigrationRequest(sourceDbConfig, tablesToMigrate);
            log.info("Constructed migration request with {} tables.", tablesToMigrate.size());
            dataMigrationExecutor.runMigration(request);
            log.info("<<<<<<<<<< Full Data Migration Finished Successfully >>>>>>>>>>");
        } catch (Exception e) {
            log.error("<<<<<<<<<< Full Data Migration FAILED >>>>>>>>>>", e);
            // throw e; // Optional: Stop application startup on failure
        }
    }

    /**
     * Defines the migration configuration using Builder and Map.ofEntries.
     * Order is crucial for dependencies.
     * Excludes audit columns.
     * treatZeroIdAsNullForForeignKeys defaults to true via @Builder.Default.
     */
    private List<TableMigrationConfig> defineMigrationOrderAndMappings() {
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
        configs.add(TableMigrationConfig.builder()
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
                .build());

        return configs;
    }
}