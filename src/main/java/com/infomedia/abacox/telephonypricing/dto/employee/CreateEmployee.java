package com.infomedia.abacox.telephonypricing.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Employee}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateEmployee {
    @NotBlank
    @Size(max = 255)
    private String name;

    private Long subdivisionId;

    private Long costCenterId;

   /* @NotBlank
    @Size(max = 50)
    private String accessKey;*/

    @NotBlank
    @Size(max = 50)
    private String extension;

    private Long communicationLocationId;

    private Long jobPositionId;

    @NotBlank
    @Size(max = 100)
    private String email;

    @NotBlank
    @Size(max = 100)
    private String phone;

    @NotBlank
    @Size(max = 255)
    private String address;

    @NotBlank
    @Size(max = 20)
    private String idNumber;
}