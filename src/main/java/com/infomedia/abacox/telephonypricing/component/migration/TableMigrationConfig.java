// File: com/infomedia/abacox/telephonypricing/component/migration/TableMigrationConfig.java
package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Consumer;

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
     * An optional callback executed after a batch of rows is successfully persisted
     * in the target database.
     */
    private Consumer<List<Map<String, Object>>> onBatchSuccess;

    /**
     * Map of Target Field Name -> Transformation Function.
     * The function receives the raw Source Value (Object) and returns the Converted
     * Target Value (Object).
     * This takes precedence over default MigrationUtils conversion.
     * 
     * Example:
     * map.put("isActive", (val) -> "Y".equals(val) ? true : false);
     */
    private Map<String, Function<Object, Object>> customValueTransformers;

    /**
     * Map of Target Field Name -> Map of Source Value -> Target Value.
     * This takes precedence over customValueTransformers and default conversion.
     * useful for fixing bad data references.
     *
     * Example:
     * Map<Object, Object> replacements = new HashMap<>();
     * replacements.put(99, 1);
     * map.put("telephonyType", replacements);
     */
    private Map<String, Map<Object, Object>> specificValueReplacements;

    /**
     * An optional filter to discard rows purely in memory before insertion.
     * Evaluates against the raw source row Map. If it returns false, the row is
     * discarded.
     */
    private Predicate<Map<String, Object>> rowFilter;

    /**
     * An optional modifier to mutate row values purely in memory before insertion
     * and entity mapping. This executes AFTER the rowFilter.
     * Evaluates against the raw source row Map, allowing direct modification.
     */
    private Consumer<Map<String, Object>> rowModifier;

    @Builder.Default
    private boolean selfReferencing = false;
    private String selfReferenceSourceParentIdColumn;
    private String selfReferenceTargetForeignKeyFieldName;

    @Builder.Default
    private boolean treatZeroIdAsNullForForeignKeys = true;

    /**
     * An optional action to run after this specific table has been migrated
     * successfully.
     * This will not be executed if the migration for this table fails.
     */
    private Runnable postMigrationSuccessAction;

    /**
     * The column(s) to use for ordering the source data when fetching.
     * Should include direction, e.g., "creation_date DESC" or "id ASC".
     * If null, defaults to ordering by sourceIdColumnName ascending for stable
     * paging.
     * For migrating most recent entries first, this should be set to a
     * date/timestamp column with DESC.
     */
    private String orderByClause;

    /**
     * An optional SQL WHERE clause to filter the source data (without the WHERE
     * keyword).
     * e.g., "CLIENT_ID = 5"
     */
    private String whereClause;

    /**
     * The maximum number of entries to migrate for this table.
     * If null or less than or equal to zero, all entries will be migrated.
     */
    private Integer maxEntriesToMigrate;

    @Builder.Default
    private boolean assumeTargetIsEmpty = false;
}