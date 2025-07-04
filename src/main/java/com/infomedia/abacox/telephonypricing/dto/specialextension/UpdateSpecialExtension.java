package com.infomedia.abacox.telephonypricing.dto.specialextension;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating a {@link com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension}.
 * Uses JsonNullable to support PATCH semantics.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSpecialExtension {

    @NotBlank(message = "Extension number cannot be blank.")
    @Size(max = 255, message = "Extension number cannot exceed 255 characters.")
    private JsonNullable<String> extension = JsonNullable.undefined();

    @NotNull(message = "Type cannot be null.")
    private JsonNullable<Integer> type = JsonNullable.undefined();

    @NotNull(message = "LDAP enabled flag cannot be null.")
    private JsonNullable<Boolean> ldapEnabled = JsonNullable.undefined();

    private JsonNullable<String> description = JsonNullable.undefined();
}