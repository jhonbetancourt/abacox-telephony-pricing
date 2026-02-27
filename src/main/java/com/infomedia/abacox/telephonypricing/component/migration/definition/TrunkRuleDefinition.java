package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class TrunkRuleDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("reglatroncal")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TrunkRule")
                .sourceIdColumnName("REGLATRONCAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("REGLATRONCAL_ID", "id"),
                        entry("REGLATRONCAL_VALOR", "rateValue"),
                        entry("REGLATRONCAL_IVAINC", "includesVat"),
                        entry("REGLATRONCAL_TIPOTELE_ID", "telephonyTypeId"),
                        entry("REGLATRONCAL_INDICATIVO_ID", "indicatorIds"),
                        entry("REGLATRONCAL_TRONCAL_ID", "trunkId"),
                        entry("REGLATRONCAL_OPERADOR_NUEVO", "newOperatorId"),
                        entry("REGLATRONCAL_TIPOTELE_NUEVO", "newTelephonyTypeId"),
                        entry("REGLATRONCAL_SEGUNDOS", "seconds"),
                        entry("REGLATRONCAL_INDICAORIGEN_ID", "originIndicatorId"),
                        entry("REGLATRONCAL_ACTIVO", "active"),
                        entry("REGLATRONCAL_FCREACION", "createdDate"),
                        entry("REGLATRONCAL_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
