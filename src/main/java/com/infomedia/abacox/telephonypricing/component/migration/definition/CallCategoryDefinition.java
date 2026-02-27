package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class CallCategoryDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("CLASELLAMA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CallCategory")
                .sourceIdColumnName("CLASELLAMA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("CLASELLAMA_ID", "id"),
                        entry("CLASELLAMA_NOMBRE", "name"),
                        entry("CLASELLAMA_ACTIVO", "active"),
                        entry("CLASELLAMA_FCREACION", "createdDate"),
                        entry("CLASELLAMA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
