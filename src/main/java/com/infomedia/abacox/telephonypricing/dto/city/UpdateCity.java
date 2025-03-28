package com.infomedia.abacox.telephonypricing.dto.city;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.City}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateCity {
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> department = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> classification = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> municipality = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> municipalCapital = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 15)
    private JsonNullable<String> latitude = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 15)
    private JsonNullable<String> longitude = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> altitude = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> northCoordinate = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> eastCoordinate = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> origin = JsonNullable.undefined();
}