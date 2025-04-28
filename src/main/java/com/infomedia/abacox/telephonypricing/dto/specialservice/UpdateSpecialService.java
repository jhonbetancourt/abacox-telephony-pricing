package com.infomedia.abacox.telephonypricing.dto.specialservice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSpecialService {

    private JsonNullable<Long> indicatorId = JsonNullable.undefined();

    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> phoneNumber = JsonNullable.undefined();

    @NotNull
    private JsonNullable<BigDecimal> value = JsonNullable.undefined();

    @NotNull
    private JsonNullable<BigDecimal> vatAmount = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Boolean> vatIncluded = JsonNullable.undefined();

    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> description = JsonNullable.undefined();

    private JsonNullable<Long> originCountryId = JsonNullable.undefined();
}