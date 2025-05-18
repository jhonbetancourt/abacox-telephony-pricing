
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.SpecialRateValue;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SpecialRateApplicationResultDto {
    private BigDecimal newPricePerUnitExVat; // Price after special rate, EXCLUDING VAT
    private BigDecimal newVatRate; // VAT rate to apply to this new price
    private SpecialRateValue appliedRule;
    private boolean rateWasApplied;
}