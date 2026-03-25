package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryOwnerDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("invepropietario")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventoryOwner")
                .sourceIdColumnName("INVEPROPIETARIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVEPROPIETARIO_ID", "id"),
                        entry("INVEPROPIETARIO_NOMBRE", "name"),
                        entry("INVEPROPIETARIO_FCREACION", "createdDate"),
                        entry("INVEPROPIETARIO_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
