package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class OfficeDetailsDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("DATOSOFICINA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails")
                .sourceIdColumnName("DATOSOFICINA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("DATOSOFICINA_ID", "id"),
                        entry("DATOSOFICINA_SUBDIRECCION_ID", "subdivisionId"),
                        entry("DATOSOFICINA_DIRECCION", "address"),
                        entry("DATOSOFICINA_TELEFONO", "phone"),
                        entry("DATOSOFICINA_INDICATIVO_ID", "indicatorId"),
                        entry("DATOSOFICINA_FCREACION", "createdDate"),
                        entry("DATOSOFICINA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
