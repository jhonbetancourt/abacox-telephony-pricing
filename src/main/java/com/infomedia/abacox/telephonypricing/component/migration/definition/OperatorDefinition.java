package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class OperatorDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("OPERADOR")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Operator")
                .sourceIdColumnName("OPERADOR_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("OPERADOR_ID", "id"),
                        entry("OPERADOR_NOMBRE", "name"),
                        entry("OPERADOR_MPORIGEN_ID", "originCountryId"),
                        entry("OPERADOR_FCREACION", "createdDate"),
                        entry("OPERADOR_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
