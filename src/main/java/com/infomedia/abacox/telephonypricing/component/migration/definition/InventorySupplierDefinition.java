package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventorySupplierDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("inveds")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier")
                .sourceIdColumnName("INVEDS_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVEDS_ID", "id"),
                        entry("INVEDS_NOMBRE", "name"),
                        entry("INVEDS_INVEQUIPOS_ID", "inventoryEquipmentId"),
                        entry("INVEDS_EMPRESA", "company"),
                        entry("INVEDS_NIT", "nit"),
                        entry("INVEDS_SUBDIRECCION_ID", "subdivisionId"),
                        entry("INVEDS_DIRECCION", "address"),
                        entry("INVEDS_FCREACION", "createdDate"),
                        entry("INVEDS_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
