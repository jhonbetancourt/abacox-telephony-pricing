package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryAdditionalServiceDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("inveserviciosadc")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventoryAdditionalService")
                .sourceIdColumnName("INVESERVICIOSADC_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVESERVICIOSADC_ID", "id"),
                        entry("INVESERVICIOSADC_NOMBRE", "name"),
                        entry("INVESERVICIOSADC_FCREACION", "createdDate"),
                        entry("INVESERVICIOSADC_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
