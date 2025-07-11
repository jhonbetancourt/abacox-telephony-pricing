package com.infomedia.abacox.telephonypricing.dto.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.Contact}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateContact {
    @NotNull
    private JsonNullable<Boolean> contactType = JsonNullable.undefined();
    
    private JsonNullable<Long> employeeId = JsonNullable.undefined();
    
    private JsonNullable<Long> companyId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> phoneNumber = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    private JsonNullable<String> description = JsonNullable.undefined();
    
    private JsonNullable<Long> indicatorId = JsonNullable.undefined();
}