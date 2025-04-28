package com.infomedia.abacox.telephonypricing.dto.extensionrange;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.ExtensionRange}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateExtensionRange {
    private JsonNullable<Long> commLocationId = JsonNullable.undefined();
    
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 250)
    private JsonNullable<String> prefix = JsonNullable.undefined();
    
    private JsonNullable<Long> rangeStart = JsonNullable.undefined();
    
    private JsonNullable<Long> rangeEnd = JsonNullable.undefined();
    
    private JsonNullable<Long> costCenterId = JsonNullable.undefined();
}