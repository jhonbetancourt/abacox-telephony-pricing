package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class TrunkRateDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("tarifatroncal")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TrunkRate")
                .sourceIdColumnName("TARIFATRONCAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TARIFATRONCAL_ID", "id"),
                        entry("TARIFATRONCAL_TRONCAL_ID", "trunkId"),
                        entry("TARIFATRONCAL_VALOR", "rateValue"),
                        entry("TARIFATRONCAL_IVAINC", "includesVat"),
                        entry("TARIFATRONCAL_OPERADOR_ID", "operatorId"),
                        entry("TARIFATRONCAL_TIPOTELE_ID", "telephonyTypeId"),
                        entry("TARIFATRONCAL_NOPREFIJOPBX", "noPbxPrefix"),
                        entry("TARIFATRONCAL_NOPREFIJO", "noPrefix"),
                        entry("TARIFATRONCAL_SEGUNDOS", "seconds"),
                        entry("TARIFATRONCAL_ACTIVO", "active"),
                        entry("TARIFATRONCAL_FCREACION", "createdDate"),
                        entry("TARIFATRONCAL_FMODIFICADO", "lastModifiedDate")))
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .build();
    }
}
