package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.*;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TariffValue {
    private BigDecimal rateValue;
    private boolean includesVat;
    private BigDecimal vatRate;
}

