package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class TelephonyTypeConfigDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("TIPOTELECFG")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TelephonyTypeConfig")
                .sourceIdColumnName("TIPOTELECFG_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOTELECFG_ID", "id"),
                        entry("TIPOTELECFG_MIN", "minValue"),
                        entry("TIPOTELECFG_MAX", "maxValue"),
                        entry("TIPOTELECFG_TIPOTELE_ID", "telephonyTypeId"),
                        entry("TIPOTELECFG_MPORIGEN_ID", "originCountryId"),
                        entry("TIPOTELECFG_FCREACION", "createdDate"),
                        entry("TIPOTELECFG_FMODIFICADO", "lastModifiedDate")))
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .build();
    }
}
