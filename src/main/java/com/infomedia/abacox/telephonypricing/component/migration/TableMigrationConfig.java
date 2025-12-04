// File: com/infomedia/abacox/telephonypricing/component/migration/TableMigrationConfig.java
package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;
import java.util.function.Function;

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

    /**
     * Map of Target Field Name -> Transformation Function.
     * The function receives the raw Source Value (Object) and returns the Converted Target Value (Object).
     * This takes precedence over default MigrationUtils conversion.
     * 
     * Example: 
     * map.put("isActive", (val) -> "Y".equals(val) ? true : false);
     */
    private Map<String, Function<Object, Object>> customValueTransformers;

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

    /**
     * The column(s) to use for ordering the source data when fetching.
     * Should include direction, e.g., "creation_date DESC" or "id ASC".
     * If null, defaults to ordering by sourceIdColumnName ascending for stable paging.
     * For migrating most recent entries first, this should be set to a date/timestamp column with DESC.
     */
    private String orderByClause;

    /**
     * The maximum number of entries to migrate for this table.
     * If null or less than or equal to zero, all entries will be migrated.
     */
    private Integer maxEntriesToMigrate;
}