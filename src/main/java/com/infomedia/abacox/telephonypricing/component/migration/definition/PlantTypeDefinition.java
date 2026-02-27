package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class PlantTypeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("tipoplanta")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.PlantType")
                .sourceIdColumnName("TIPOPLANTA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOPLANTA_ID", "id"),
                        entry("TIPOPLANTA_NOMBRE", "name"),
                        entry("TIPOPLANTA_FCREACION", "createdDate"),
                        entry("TIPOPLANTA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
