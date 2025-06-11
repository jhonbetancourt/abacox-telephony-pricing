package com.infomedia.abacox.telephonypricing.dto.commlocation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDateTime;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateCommLocation {
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> directory = JsonNullable.undefined();
    
    private JsonNullable<Long> plantTypeId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 20)
    private JsonNullable<String> serial = JsonNullable.undefined();
    
    private JsonNullable<Long> indicatorId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 32)
    private JsonNullable<String> pbxPrefix = JsonNullable.undefined();
    
    private JsonNullable<LocalDateTime> captureDate = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> cdrCount = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> fileName = JsonNullable.undefined();
    
    private JsonNullable<Long> headerId = JsonNullable.undefined();
}