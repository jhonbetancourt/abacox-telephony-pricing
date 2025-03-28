package com.infomedia.abacox.telephonypricing.dto.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.Company}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateCompany {
    private JsonNullable<String> additionalInfo = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> address = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> taxId = JsonNullable.undefined();
    
    private JsonNullable<String> legalName = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> website = JsonNullable.undefined();
    
    private JsonNullable<Integer> indicatorId = JsonNullable.undefined();
}