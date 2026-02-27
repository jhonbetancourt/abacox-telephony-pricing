package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class ExtensionListDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("listadoext")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.ExtensionList")
                .sourceIdColumnName("LISTADOEXT_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("LISTADOEXT_ID", "id"),
                        entry("LISTADOEXT_NOMBRE", "name"),
                        entry("LISTADOEXT_TIPO", "type"),
                        entry("LISTADOEXT_LISTADO", "extensionList"),
                        entry("LISTADOEXT_ACTIVO", "active"),
                        entry("LISTADOEXT_FCREACION", "createdDate"),
                        entry("LISTADOEXT_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
