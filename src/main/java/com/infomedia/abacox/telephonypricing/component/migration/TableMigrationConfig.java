// File: com/infomedia/abacox/telephonypricing/component/migration/TableMigrationConfig.java
package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableMigrationConfig {
    private String sourceTableName;
    private String targetEntityClassName;
    private String sourceIdColumnName;
    private String targetIdFieldName;
    private Map<String, String> columnMapping;

    @Builder.Default
    private boolean selfReferencing = false;
    private String selfReferenceSourceParentIdColumn;
    private String selfReferenceTargetForeignKeyFieldName;

    @Builder.Default
    private boolean treatZeroIdAsNullForForeignKeys = true;

    @Builder.Default
    private boolean processHistoricalActiveness = false;
    private String sourceHistoricalControlIdColumn;
    private String sourceValidFromDateColumn;

    /**
     * An optional action to run after this specific table has been migrated successfully.
     * This will not be executed if the migration for this table fails.
     */
    private Runnable postMigrationSuccessAction;
}