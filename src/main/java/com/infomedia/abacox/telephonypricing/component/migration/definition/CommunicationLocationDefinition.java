package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class CommunicationLocationDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("COMUBICACION")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation")
                .sourceIdColumnName("COMUBICACION_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("COMUBICACION_ID", "id"),
                        entry("COMUBICACION_DIRECTORIO", "directory"),
                        entry("COMUBICACION_TIPOPLANTA_ID", "plantTypeId"),
                        entry("COMUBICACION_SERIAL", "serial"),
                        entry("COMUBICACION_INDICATIVO_ID", "indicatorId"),
                        entry("COMUBICACION_PREFIJOPBX", "pbxPrefix"),
                        entry("COMUBICACION_ACTIVO", "active"),
                        entry("COMUBICACION_FCREACION", "createdDate"),
                        entry("COMUBICACION_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
