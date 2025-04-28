package com.infomedia.abacox.telephonypricing.dto.trunk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTrunk {

    @NotNull
    private Long commLocationId;

    @NotBlank
    @Size(max = 50)
    private String description;

    @NotBlank
    @Size(max = 50)
    private String trunk;

    @NotNull
    private Long operatorId;

    @NotNull
    private Boolean noPbxPrefix;

    @NotNull
    private Integer channels;
}