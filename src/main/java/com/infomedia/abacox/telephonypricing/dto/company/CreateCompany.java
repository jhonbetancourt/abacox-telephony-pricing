package com.infomedia.abacox.telephonypricing.dto.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.Company}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateCompany {
    private String additionalInfo;
    
    @NotBlank
    @Size(max = 255)
    private String address;
    
    @NotBlank
    @Size(max = 100)
    private String name;
    
    @NotBlank
    @Size(max = 100)
    private String taxId;
    
    private String legalName;
    
    @NotBlank
    @Size(max = 100)
    private String website;
    
    private Integer indicatorId;
}