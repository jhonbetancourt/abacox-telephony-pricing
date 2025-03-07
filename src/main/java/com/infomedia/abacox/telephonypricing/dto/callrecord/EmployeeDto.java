package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Employee}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmployeeDto extends ActivableDto {
    private Long id;
    private String name;
    private Long subdivisionId;
    private Long costCenterId;
    private String accessKey;
    private String extension;
    private Long communicationLocationId;
    private Long jobPositionId;
    private String email;
    private String telephone;
    private String address;
    private String cellPhone;
    private String idNumber;
}