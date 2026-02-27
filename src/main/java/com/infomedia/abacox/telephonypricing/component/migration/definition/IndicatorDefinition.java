package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class IndicatorDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
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
                        entry("INDICATIVO_ENUSO", "active"),
                        entry("INDICATIVO_FCREACION", "createdDate"),
                        entry("INDICATIVO_FMODIFICADO", "lastModifiedDate")))
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .build();
    }
}
