package com.infomedia.abacox.telephonypricing.migration; // Use your actual package

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

    // --- New fields for self-reference ---
    @Builder.Default
    private boolean selfReferencing = false; // Flag to indicate self-reference
    private String selfReferenceSourceParentIdColumn; // e.g., "SUBDIRECCION_PERTENECE"
    private String selfReferenceTargetForeignKeyFieldName; // e.g., "parentSubdivisionId" (the Long field)
    // We can derive the DB column name from the target field name later if needed

    @Builder.Default
    private boolean treatZeroIdAsNullForForeignKeys = true; // Flag to treat 0 as null for foreign keys
}