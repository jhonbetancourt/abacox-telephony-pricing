package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ExtensionLimits {
    public int minLength = 100; // Default from PHP _LIM_INTERNAS
    public int maxLength = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
    public List<String> specialFullExtensions = Collections.emptyList();
}