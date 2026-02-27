package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class PrefixDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("PREFIJO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Prefix")
                .sourceIdColumnName("PREFIJO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("PREFIJO_ID", "id"),
                        entry("PREFIJO_OPERADOR_ID", "operatorId"),
                        entry("PREFIJO_TIPOTELE_ID", "telephonyTypeId"),
                        entry("PREFIJO_PREFIJO", "code"),
                        entry("PREFIJO_VALORBASE", "baseValue"),
                        entry("PREFIJO_BANDAOK", "bandOk"),
                        entry("PREFIJO_IVAINC", "vatIncluded"),
                        entry("PREFIJO_IVA", "vatValue"),
                        entry("PREFIJO_ACTIVO", "active"),
                        entry("PREFIJO_FCREACION", "createdDate"),
                        entry("PREFIJO_FMODIFICADO", "lastModifiedDate")))
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .build();
    }
}
