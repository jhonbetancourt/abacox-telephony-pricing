// FILE: com/infomedia/abacox/telephonypricing/cdr/dto/EvaluatedDestinationDto.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class EvaluatedDestinationDto {
    private String processedNumber; // Number after prefix stripping, etc.
    private TelephonyType telephonyType;
    private Operator operator;
    private Indicator indicator;
    private String destinationDescription; // e.g., city name or "Assumed Local"

    // Pricing related fields derived from prefix/band/special rate
    private BigDecimal pricePerMinute; // Final rate per minute (or per unit if seconds billing)
    private boolean vatIncludedInPrice;
    private BigDecimal vatRate; // Percentage (e.g., 16.0 for 16%)
    private BigDecimal initialPriceBeforeSpecialRates; // Price before special rates, ex-VAT
    private boolean initialPriceVatIncluded;

    private boolean billedInSeconds; // If true, pricePerMinute is actually pricePerSecond
    private Integer billingUnitInSeconds; // e.g., 60 for minute, 1 for second, N for N-second blocks from trunk rule

    private boolean fromTrunk;
    private boolean bandUsed;
    private Long bandId;
    private String bandName;

    // Flag to indicate if this result is an "assumed" one (e.g. fallback to local)
    private boolean assumed;
}