package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class BandIndicatorDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("BANDAINDICA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.BandIndicator")
                .sourceIdColumnName("BANDAINDICA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("BANDAINDICA_ID", "id"),
                        entry("BANDAINDICA_BANDA_ID", "bandId"),
                        entry("BANDAINDICA_INDICATIVO_ID", "indicatorId"),
                        entry("BANDAINDICA_FCREACION", "createdDate"),
                        entry("BANDAINDICA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
