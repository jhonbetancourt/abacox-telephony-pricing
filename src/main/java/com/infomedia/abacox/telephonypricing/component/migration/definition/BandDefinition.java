package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class BandDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("BANDA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Band")
                .sourceIdColumnName("BANDA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("BANDA_ID", "id"),
                        entry("BANDA_PREFIJO_ID", "prefixId"),
                        entry("BANDA_NOMBRE", "name"),
                        entry("BANDA_VALOR", "value"),
                        entry("BANDA_INDICAORIGEN_ID", "originIndicatorId"),
                        entry("BANDA_IVAINC", "vatIncluded"),
                        entry("BANDA_REF", "reference"),
                        entry("BANDA_FCREACION", "createdDate"),
                        entry("BANDA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
