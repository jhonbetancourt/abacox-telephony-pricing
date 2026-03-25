package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryUserTypeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("invetipousuario")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventoryUserType")
                .sourceIdColumnName("INVETIPOUSUARIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVETIPOUSUARIO_ID", "id"),
                        entry("INVETIPOUSUARIO_NOMBRE", "name"),
                        entry("INVETIPOUSUARIO_FCREACION", "createdDate"),
                        entry("INVETIPOUSUARIO_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
