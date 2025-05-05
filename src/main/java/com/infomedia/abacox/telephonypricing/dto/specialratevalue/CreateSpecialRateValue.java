package com.infomedia.abacox.telephonypricing.dto.specialratevalue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.SpecialRateValue}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSpecialRateValue {
    @NotBlank
    @Size(max = 100)
    private String name;
    
    @NotNull
    private BigDecimal rateValue;
    
    @NotNull
    private Boolean includesVat;
    
    @NotNull
    private Boolean sundayEnabled;
    
    @NotNull
    private Boolean mondayEnabled;
    
    @NotNull
    private Boolean tuesdayEnabled;
    
    @NotNull
    private Boolean wednesdayEnabled;
    
    @NotNull
    private Boolean thursdayEnabled;
    
    @NotNull
    private Boolean fridayEnabled;
    
    @NotNull
    private Boolean saturdayEnabled;
    
    @NotNull
    private Boolean holidayEnabled;
    
    private Long telephonyTypeId;
    
    private Long operatorId;
    
    private Long bandId;
    
    private LocalDateTime validFrom;
    
    private LocalDateTime validTo;
    
    private Long originIndicatorId;
    
    @Size(max = 80)
    private String hoursSpecification;
    
    @NotNull
    private Integer valueType;
}