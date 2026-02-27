package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.constants.RefTable;
import java.util.Map;
import static java.util.Map.entry;

public class HistoryControlDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("historictl")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.HistoryControl")
                .sourceIdColumnName("HISTORICTL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("HISTORICTL_ID", "id"),
                        entry("HISTORICTL_REF_TABLA", "refTable"),
                        entry("HISTORICTL_REF_ID", "refId"),
                        entry("HISTORICTL_HISTODESDE", "historySince")))
                .rowFilter(row -> {
                    Object refTableObj = row.get("HISTORICTL_REF_TABLA");
                    if (refTableObj instanceof Number) {
                        int refTableId = ((Number) refTableObj).intValue();
                        return RefTable.isValidId(refTableId);
                    }
                    return false;
                })
                .build();
    }
}
