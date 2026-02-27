package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class SubdivisionDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("SUBDIRECCION")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Subdivision")
                .sourceIdColumnName("SUBDIRECCION_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SUBDIRECCION_ID", "id"),
                        entry("SUBDIRECCION_PERTENECE", "parentSubdivisionId"),
                        entry("SUBDIRECCION_NOMBRE", "name"),
                        entry("SUBDIRECCION_ACTIVO", "active"),
                        entry("SUBDIREccion_FCREACION", "createdDate"),
                        entry("SUBDIRECCION_FMODIFICADO", "lastModifiedDate")))
                .selfReferencing(true)
                .selfReferenceTargetForeignKeyFieldName("parentSubdivisionId")
                .build();
    }
}
