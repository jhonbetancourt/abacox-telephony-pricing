package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class CdrLoadControlDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName(context.getControlDatabase() + ".dbo.cargactl")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl")
                .sourceIdColumnName("CARGACTL_ID")
                .targetIdFieldName("id")
                .whereClause(
                        context.getSourceClientId() != null ? "CARGACTL_CLIENTE_ID = " + context.getSourceClientId()
                                : null)
                .columnMapping(Map.ofEntries(
                        entry("CARGACTL_ID", "id"),
                        entry("CARGACTL_DIRECTORIO", "name"),
                        entry("CARGACTL_TIPOPLANTA_ID", "plantTypeId"),
                        entry("CARGACTL_ACTIVO", "active"),
                        entry("CARGACTL_FCREACION", "createdDate"),
                        entry("CARGACTL_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
