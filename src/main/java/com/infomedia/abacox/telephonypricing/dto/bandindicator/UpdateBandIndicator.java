package com.infomedia.abacox.telephonypricing.dto.bandindicator;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.BandIndicator}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateBandIndicator {
    @NotNull
    private JsonNullable<Long> bandId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Long> indicatorId = JsonNullable.undefined();
}