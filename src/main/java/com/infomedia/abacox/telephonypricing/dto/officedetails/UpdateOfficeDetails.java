package com.infomedia.abacox.telephonypricing.dto.officedetails;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.OfficeDetails}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateOfficeDetails {
    @NotNull
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> address = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> phone = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Long> indicatorId = JsonNullable.undefined();
}