package com.infomedia.abacox.telephonypricing.dto.officedetails;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.OfficeDetails}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateOfficeDetails {
    @NotNull
    private Long subdivisionId;
    
    @NotBlank
    @Size(max = 100)
    private String address;
    
    @NotBlank
    @Size(max = 100)
    private String phone;
    
    @NotNull
    private Long indicatorId;
}