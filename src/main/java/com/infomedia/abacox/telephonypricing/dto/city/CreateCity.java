package com.infomedia.abacox.telephonypricing.dto.city;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.City}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateCity {
    @NotBlank
    @Size(max = 80)
    private String department;

    @NotBlank
    @Size(max = 80)
    private String classification;

    @NotBlank
    @Size(max = 80)
    private String municipality;

    @NotBlank
    @Size(max = 80)
    private String municipalCapital;

    @NotBlank
    @Size(max = 15)
    private String latitude;

    @NotBlank
    @Size(max = 15)
    private String longitude;

    @NotNull
    private Integer altitude;

    @NotNull
    private Integer northCoordinate;

    @NotNull
    private Integer eastCoordinate;

    @NotBlank
    @Size(max = 50)
    private String origin;
}