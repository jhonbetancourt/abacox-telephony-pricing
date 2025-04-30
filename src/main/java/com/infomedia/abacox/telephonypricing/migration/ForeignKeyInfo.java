package com.infomedia.abacox.telephonypricing.migration; // Use your actual package

import lombok.Data;
import lombok.RequiredArgsConstructor;
import java.lang.reflect.Field;


@Data
@RequiredArgsConstructor
public class ForeignKeyInfo {
    private final Field foreignKeyField; // The field holding the FK value (e.g., parentSubdivisionId)
    private final String dbColumnName;   // The actual DB column name (e.g., parent_subdivision_id)
    private final Class<?> targetTypeId; // The type of the FK field (e.g., Long.class)
    private final boolean isSelfReference; // Is this FK pointing back to the same entity type?
    private final Field relationshipField; // The field holding the actual entity relationship (e.g., parentSubdivision), can be null
}