package com.infomedia.abacox.telephonypricing.dto.extensionrange;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateExtensionRange {
    @NotNull
    private Long commLocationId;

    @NotNull
    private Long subdivisionId;

    @NotBlank
    @Size(max = 250)
    private String prefix;

    @NotNull
    private Long rangeStart;

    @NotNull
    private Long rangeEnd;

    @NotNull
    private Long costCenterId;
}