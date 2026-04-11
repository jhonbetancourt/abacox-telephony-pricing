package com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateCdrLoadControl {
    @NotBlank
    @Size(max = 64)
    private JsonNullable<String> name = JsonNullable.undefined();

    private JsonNullable<Integer> plantTypeId = JsonNullable.undefined();
}
