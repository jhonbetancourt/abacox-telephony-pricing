package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class EquipmentTypeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("tipoequipos")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.EquipmentType")
                .sourceIdColumnName("TIPOEQUIPOS_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOEQUIPOS_ID", "id"),
                        entry("TIPOEQUIPOS_NOMBRE", "name"),
                        entry("TIPOEQUIPOS_FCREACION", "createdDate"),
                        entry("TIPOEQUIPOS_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
