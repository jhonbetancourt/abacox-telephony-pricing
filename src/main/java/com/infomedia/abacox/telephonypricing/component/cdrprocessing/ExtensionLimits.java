// File: com/infomedia/abacox/telephonypricing/cdr/ExtensionLimits.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ExtensionLimits {
    // PHP: $_LIM_INTERNAS['min'] and $_LIM_INTERNAS['max'] store the actual numeric min/max values
    // derived from lengths.
    private int minLength = 100; // Default from PHP _LIM_INTERNAS['min'] (numeric value)
    private int maxLength = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK; // PHP _LIM_INTERNAS['max']
    private List<String> specialFullExtensions = Collections.emptyList(); // PHP: $_LIM_INTERNAS['full']
}