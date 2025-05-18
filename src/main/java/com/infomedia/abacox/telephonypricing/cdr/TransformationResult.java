package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class TransformationResult {
    private String transformedNumber;
    private boolean transformed;
    private Long newTelephonyTypeId;
}