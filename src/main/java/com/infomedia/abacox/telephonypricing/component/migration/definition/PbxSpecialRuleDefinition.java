package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class PbxSpecialRuleDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
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
                .build();
    }
}
