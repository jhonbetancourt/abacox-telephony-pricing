package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class SpecialRateValueDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("valorespecial")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue")
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
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .build();
    }
}
