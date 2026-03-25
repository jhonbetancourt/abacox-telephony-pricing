package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryWorkOrderTypeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("inveot")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventoryWorkOrderType")
                .sourceIdColumnName("INVEOT_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVEOT_ID", "id"),
                        entry("INVEOT_NOMBRE", "name"),
                        entry("INVEOT_FCREACION", "createdDate"),
                        entry("INVEOT_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
