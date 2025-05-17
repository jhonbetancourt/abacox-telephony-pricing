package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.SpecialRateValue;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SpecialRateApplicationResultDto {
    private BigDecimal newPricePerMinute; // Price after special rate, before VAT application
    private boolean newVatIncluded;
    private BigDecimal newVatRate; // This might be the same or updated if SRV implies different operator/TT
    private SpecialRateValue appliedRule;
    private boolean rateWasApplied;
}
