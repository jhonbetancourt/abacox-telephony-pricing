package com.infomedia.abacox.telephonypricing.dto.specialextension;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a {@link com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension}.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecialExtensionDto extends ActivableDto {

    private Long id;
    private String extension;
    private Integer type;
    private boolean ldapEnabled;
    private String description;
}