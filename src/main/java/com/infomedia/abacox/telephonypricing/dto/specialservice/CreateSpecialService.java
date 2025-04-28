package com.infomedia.abacox.telephonypricing.dto.specialservice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSpecialService {

    @NotNull
    private Long indicatorId;

    @NotBlank
    @Size(max = 50)
    private String phoneNumber;

    @NotNull
    private BigDecimal value;

    @NotNull
    private BigDecimal vatAmount;

    @NotNull
    private Boolean vatIncluded;

    @NotBlank
    @Size(max = 80)
    private String description;

    @NotNull
    private Long originCountryId;
}