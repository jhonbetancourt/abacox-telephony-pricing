package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class SubdivisionManagerDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("JEFESUBDIR")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager")
                .sourceIdColumnName("JEFESUBDIR_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("JEFESUBDIR_ID", "id"),
                        entry("JEFESUBDIR_SUBDIRECCION_ID", "subdivisionId"),
                        entry("JEFESUBDIR_JEFE", "managerId"),
                        entry("JEFESUBDIR_ACTIVO", "active"),
                        entry("JEFESUBDIR_FCREACION", "createdDate"),
                        entry("JEFESUBDIR_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
