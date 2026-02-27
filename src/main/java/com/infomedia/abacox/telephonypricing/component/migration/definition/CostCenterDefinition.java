package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class CostCenterDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("CENTROCOSTOS")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CostCenter")
                .sourceIdColumnName("CENTROCOSTOS_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("CENTROCOSTOS_ID", "id"),
                        entry("CENTROCOSTOS_CENTRO_COSTO", "name"),
                        entry("CENTROCOSTOS_OT", "workOrder"),
                        entry("CENTROCOSTOS_PERTENECE", "parentCostCenterId"),
                        entry("CENTROCOSTOS_MPORIGEN_ID", "originCountryId"),
                        entry("CENTROCOSTOS_ACTIVO", "active"),
                        entry("CENTROCOSTOS_FCREACION", "createdDate"),
                        entry("CENTROCOSTOS_FMODIFICADO", "lastModifiedDate")))
                .selfReferencing(true)
                .selfReferenceTargetForeignKeyFieldName("parentCostCenterId")
                .build();
    }
}
