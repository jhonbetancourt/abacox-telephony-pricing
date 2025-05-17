package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalExtensionLimitsDto {
    private int minLength;
    private int maxLength; // Max length of numeric part for typical extensions
    private long minNumericValue; // Derived from minLength
    private long maxNumericValue; // Derived from maxLength
    private List<String> specialExtensions; // For non-numeric or very long extensions that don't fit numeric range

    public static InternalExtensionLimitsDto defaultLimits() {
        // Fallback defaults if DB queries yield no specific limits
        return new InternalExtensionLimitsDto(1, 7, 0L, 9999999L, Collections.emptyList());
    }
}