package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecialRateInfo {
    public BigDecimal rateValue;
    public boolean includesVat;
    public BigDecimal vatRate; // VAT rate from the prefix associated with the special rate's telephony type/operator
    public int valueType; // 0 = absolute, 1 = percentage
}