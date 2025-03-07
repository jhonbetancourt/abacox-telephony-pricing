package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Operator}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OperatorDto extends AuditedDto {
    private Long id;
    private String name;
    private Long originCountryId;
}