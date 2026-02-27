package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class JobPositionDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("funcargo")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.JobPosition")
                .sourceIdColumnName("FUNCARGO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FUNCARGO_ID", "id"),
                        entry("FUNCARGO_NOMBRE", "name"),
                        entry("FUNCARGO_ACTIVO", "active"),
                        entry("FUNCARGO_FCREACION", "createdDate"),
                        entry("FUNCARGO_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
