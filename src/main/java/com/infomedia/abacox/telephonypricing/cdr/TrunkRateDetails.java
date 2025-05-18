package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class TrunkRateDetails {
    public Long operatorId; // Operator for this specific rate
    public Long telephonyTypeId;
    public BigDecimal rateValue;
    public Boolean includesVat;
    public Integer seconds; // TARIFATRONCAL_SEGUNDOS
    public Boolean noPbxPrefix; // TARIFATRONCAL_NOPREFIJOPBX
    public Boolean noPrefix;    // TARIFATRONCAL_NOPREFIJO
}