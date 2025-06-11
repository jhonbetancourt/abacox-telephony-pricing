package com.infomedia.abacox.telephonypricing.dto.specialratevalue;

import com.infomedia.abacox.telephonypricing.dto.band.BandDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecialRateValueDto extends ActivableDto {
    private Long id;
    private String name;
    private BigDecimal rateValue;
    private Boolean includesVat;
    private Boolean sundayEnabled;
    private Boolean mondayEnabled;
    private Boolean tuesdayEnabled;
    private Boolean wednesdayEnabled;
    private Boolean thursdayEnabled;
    private Boolean fridayEnabled;
    private Boolean saturdayEnabled;
    private Boolean holidayEnabled;
    private Long telephonyTypeId;
    private Long operatorId;
    private Long bandId;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private Long originIndicatorId;
    private String hoursSpecification;
    private Integer valueType;
    private TelephonyTypeDto telephonyType;
    private OperatorDto operator;
    private BandDto band;
    private IndicatorDto originIndicator;
}