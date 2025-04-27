package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallDestinationInfo {
    private String telephone;
    private Long operatorId;
    private String operatorName;
    private Long indicatorId;
    private String indicatorCode;
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private String destination;
    private Boolean useTrunk;
    private BigDecimal billedAmount;
    private BigDecimal pricePerMinute;
    private Boolean pricePerMinuteIncludesVat;
    private BigDecimal vatAmount;
    private Boolean inSeconds;
    private BigDecimal initialPrice;
    private Boolean initialPriceIncludesVat;
    private Boolean useBands;
    private Long bandId;
    private String bandName;
    private BigDecimal discountPercentage;
}