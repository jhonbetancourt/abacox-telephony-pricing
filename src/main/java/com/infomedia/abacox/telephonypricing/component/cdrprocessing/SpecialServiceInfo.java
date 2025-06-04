package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class SpecialServiceInfo {
    public String phoneNumber;
    public BigDecimal value;
    public BigDecimal vatRate;
    public boolean vatIncluded;
    public String description;
    public Long operatorId; // From PHP's operador_interno
    public String operatorName;
}
