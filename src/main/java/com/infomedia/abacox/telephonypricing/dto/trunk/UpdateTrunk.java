package com.infomedia.abacox.telephonypricing.dto.trunk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTrunk {

    private JsonNullable<Long> commLocationId = JsonNullable.undefined();

    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> description = JsonNullable.undefined();

    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> trunk = JsonNullable.undefined();

    private JsonNullable<Long> operatorId = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Boolean> noPbxPrefix = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Integer> channels = JsonNullable.undefined();
}