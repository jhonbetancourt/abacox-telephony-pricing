package com.infomedia.abacox.telephonypricing.dto.specialextension;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a {@link com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension}.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSpecialExtension {

    @NotBlank(message = "Extension number cannot be blank.")
    @Size(max = 255, message = "Extension number cannot exceed 255 characters.")
    private String extension;

    @NotNull(message = "Type cannot be null.")
    private Integer type;

    @NotNull(message = "LDAP enabled flag cannot be null.")
    private Boolean ldapEnabled;

    // Description is optional and can be null
    private String description;
}