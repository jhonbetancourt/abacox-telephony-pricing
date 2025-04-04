package com.infomedia.abacox.telephonypricing.dto.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Contact}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateContact {
    @NotNull
    private Boolean contactType;
    
    private Long employeeId;
    
    private Long companyId;
    
    @NotBlank
    @Size(max = 255)
    private String phoneNumber;
    
    @NotBlank
    @Size(max = 255)
    private String name;
    
    private String description;
    
    private Long indicatorId;
}