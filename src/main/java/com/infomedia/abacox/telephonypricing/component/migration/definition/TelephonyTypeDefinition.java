package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class TelephonyTypeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("TIPOTELE")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.TelephonyType")
                .sourceIdColumnName("TIPOTELE_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("TIPOTELE_ID", "id"),
                        entry("TIPOTELE_NOMBRE", "name"),
                        entry("TIPOTELE_CLASELLAMA_ID", "callCategoryId"),
                        entry("TIPOTELE_TRONCALES", "usesTrunks"),
                        entry("TIPOTELE_ACTIVO", "active"),
                        entry("TIPOTELE_FCREACION", "createdDate"),
                        entry("TIPOTELE_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
