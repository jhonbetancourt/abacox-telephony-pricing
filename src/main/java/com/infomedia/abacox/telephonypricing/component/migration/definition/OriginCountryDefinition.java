package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class OriginCountryDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("MPORIGEN")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.OriginCountry")
                .sourceIdColumnName("MPORIGEN_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("MPORIGEN_ID", "id"),
                        entry("MPORIGEN_SIMBOLO", "currencySymbol"),
                        entry("MPORIGEN_PAIS", "name"),
                        entry("MPORIGEN_CCODE", "code"),
                        entry("MPORIGEN_ACTIVO", "active"),
                        entry("MPORIGEN_FCREACION", "createdDate"),
                        entry("MPORIGEN_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
