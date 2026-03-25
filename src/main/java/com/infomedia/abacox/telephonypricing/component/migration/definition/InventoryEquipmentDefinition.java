package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryEquipmentDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("invequipos")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment")
                .sourceIdColumnName("INVEQUIPOS_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVEQUIPOS_ID", "id"),
                        entry("INVEQUIPOS_EQUIPO", "name"),
                        entry("INVEQUIPOS_PERTENECE", "parentEquipmentId"),
                        entry("INVEQUIPOS_VALORTT", "valueTt"),
                        entry("INVEQUIPOS_VALORINFOMEDIA", "valueInfomedia"),
                        entry("INVEQUIPOS_FCREACION", "createdDate"),
                        entry("INVEQUIPOS_FMODIFICADO", "lastModifiedDate")))
                .selfReferencing(true)
                .selfReferenceTargetForeignKeyFieldName("parentEquipmentId")
                .build();
    }
}
