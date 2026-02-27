package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class SeriesDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("SERIE")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Series")
                .sourceIdColumnName("SERIE_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SERIE_ID", "id"),
                        entry("SERIE_INDICATIVO_ID", "indicatorId"),
                        entry("SERIE_NDC", "ndc"),
                        entry("SERIE_INICIAL", "initialNumber"),
                        entry("SERIE_FINAL", "finalNumber"),
                        entry("SERIE_EMPRESA", "company"),
                        entry("SERIE_ACTIVO", "active"),
                        entry("SERIE_FCREACION", "createdDate"),
                        entry("SERIE_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
