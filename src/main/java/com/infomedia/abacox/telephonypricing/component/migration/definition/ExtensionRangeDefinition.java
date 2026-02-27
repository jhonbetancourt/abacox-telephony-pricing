package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class ExtensionRangeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("rangoext")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange")
                .sourceIdColumnName("RANGOEXT_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("RANGOEXT_ID", "id"),
                        entry("RANGOEXT_COMUBICACION_ID", "commLocationId"),
                        entry("RANGOEXT_SUBDIRECCION_ID", "subdivisionId"),
                        entry("RANGOEXT_PREFIJO", "prefix"),
                        entry("RANGOEXT_DESDE", "rangeStart"),
                        entry("RANGOEXT_HASTA", "rangeEnd"),
                        entry("RANGOEXT_CENTROCOSTOS_ID", "costCenterId"),
                        entry("RANGOEXT_FCREACION", "createdDate"),
                        entry("RANGOEXT_FMODIFICADO", "lastModifiedDate"),
                        entry("RANGOEXT_HISTORICTL_ID", "historyControlId"),
                        entry("RANGOEXT_HISTODESDE", "historySince"),
                        entry("RANGOEXT_HISTOCAMBIO", "historyChange")))
                .build();
    }
}
