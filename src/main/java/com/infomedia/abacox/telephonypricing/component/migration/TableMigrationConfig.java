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
    private String targetIdFieldName; // Name of the @Id field in the target entity
    private Map<String, String> columnMapping; // Map<SourceColumnName, TargetFieldName>

    @Builder.Default
    private boolean selfReferencing = false;
    private String selfReferenceSourceParentIdColumn;
    private String selfReferenceTargetForeignKeyFieldName;

    @Builder.Default
    private boolean treatZeroIdAsNullForForeignKeys = true;

    // --- New fields for Historical Activeness (Pass 3) ---
    @Builder.Default
    private boolean processHistoricalActiveness = false; // Flag to enable Pass 3
    private String sourceHistoricalControlIdColumn; // e.g., "FUNCIONARIO_HISTORICTL_ID"
    private String sourceValidFromDateColumn;       // e.g., "FUNCIONARIO_HISTODESDE"
    // targetHistoricalControlIdFieldName and targetValidFromDateFieldName are derived from columnMapping
    // if processHistoricalActiveness is true.
}