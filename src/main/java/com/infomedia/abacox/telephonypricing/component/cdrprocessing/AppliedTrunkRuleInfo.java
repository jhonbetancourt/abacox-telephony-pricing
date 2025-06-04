package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AppliedTrunkRuleInfo {
    public BigDecimal rateValue;
    public Boolean includesVat;
    public Integer seconds;
    public Long newTelephonyTypeId;
    public String newTelephonyTypeName;
    public Long newOperatorId;
    public String newOperatorName;
    public BigDecimal vatRate; // VAT from the context of the newTelephonyType and newOperator
}