
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

    // Pricing related fields derived from prefix/band
    private BigDecimal pricePerUnitExVat; // Price per minute or per billing_unit_seconds, excluding VAT
    private boolean vatIncludedInPrefixOrBand; // Was VAT included in the source (Prefix/Band)?
    private BigDecimal vatRate; // VAT Percentage (e.g., 16.0 for 16%) from Prefix

    private boolean billedInSeconds; // If true, pricePerUnit is actually pricePerSecond (from TelephonyType or TrunkRule)
    private Integer billingUnitInSeconds; // e.g., 60 for minute, 1 for second

    private boolean fromTrunk; // Was this evaluation path initiated due to a trunk call?
    private boolean bandUsed;
    private Long bandId;
    private String bandName;

    // Flag to indicate if this result is an "assumed" one (e.g. fallback to local)
    private boolean assumed;
}