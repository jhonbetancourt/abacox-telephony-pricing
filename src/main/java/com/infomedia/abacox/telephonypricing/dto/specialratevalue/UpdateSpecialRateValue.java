package com.infomedia.abacox.telephonypricing.dto.specialratevalue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSpecialRateValue {
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<BigDecimal> rateValue = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> includesVat = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> sundayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> mondayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> tuesdayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> wednesdayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> thursdayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> fridayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> saturdayEnabled = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> holidayEnabled = JsonNullable.undefined();
    
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    private JsonNullable<Long> operatorId = JsonNullable.undefined();
    
    private JsonNullable<Long> bandId = JsonNullable.undefined();
    
    private JsonNullable<LocalDateTime> validFrom = JsonNullable.undefined();
    
    private JsonNullable<LocalDateTime> validTo = JsonNullable.undefined();
    
    private JsonNullable<Long> originIndicatorId = JsonNullable.undefined();
    
    @Size(max = 80)
    private JsonNullable<String> hoursSpecification = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> valueType = JsonNullable.undefined();
}