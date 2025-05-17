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
    private BigDecimal newPricePerMinute; // Price after rule, before VAT application
    private boolean newVatIncluded;
    private BigDecimal newVatRate;
    private Integer newBillingUnitInSeconds;
    private TelephonyType newTelephonyType;
    private Operator newOperator;
    private Indicator newOriginIndicator; // If rule changes origin context for pricing
    private TrunkRule appliedRule;
    private boolean ruleWasApplied;
}
