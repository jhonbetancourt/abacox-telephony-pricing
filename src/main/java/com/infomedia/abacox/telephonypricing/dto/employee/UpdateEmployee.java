package com.infomedia.abacox.telephonypricing.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.Employee}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateEmployee {
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    
    private JsonNullable<Long> costCenterId = JsonNullable.undefined();
    
   /* @NotBlank
    @Size(max = 50)
    private JsonNullable<String> accessKey = JsonNullable.undefined();*/
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> extension = JsonNullable.undefined();
    
    private JsonNullable<Long> communicationLocationId = JsonNullable.undefined();
    
    private JsonNullable<Long> jobPositionId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> email = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> phone = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> address = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 20)
    private JsonNullable<String> idNumber = JsonNullable.undefined();
}