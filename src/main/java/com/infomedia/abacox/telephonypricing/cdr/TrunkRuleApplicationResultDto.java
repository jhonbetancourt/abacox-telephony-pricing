
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.entity.TrunkRule;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TrunkRuleApplicationResultDto {
    private BigDecimal newPricePerUnitExVat; // Price after rule, EXCLUDING VAT
    private BigDecimal newVatRate; // VAT rate to apply to this new price
    private Integer newBillingUnitInSeconds;
    private TelephonyType newTelephonyType; // Can be null if not changed by rule
    private Operator newOperator;           // Can be null if not changed by rule
    private Indicator newOriginIndicator;   // Can be null if not changed by rule
    private TrunkRule appliedRule;
    private boolean ruleWasApplied;
}