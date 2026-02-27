package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class TrunkDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
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
                .build();
    }
}
