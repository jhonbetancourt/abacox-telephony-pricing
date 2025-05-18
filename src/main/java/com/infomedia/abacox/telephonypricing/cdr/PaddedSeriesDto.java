
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaddedSeriesDto {
    private String paddedInitialNumber; // Full number including NDC + padded series_initial
    private String paddedFinalNumber;   // Full number including NDC + padded series_final
}
