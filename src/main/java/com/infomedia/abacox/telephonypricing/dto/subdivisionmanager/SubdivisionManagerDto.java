package com.infomedia.abacox.telephonypricing.dto.subdivisionmanager;

import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.SubdivisionManager}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SubdivisionManagerDto extends ActivableDto {
    private Long id;
    private Long subdivisionId;
    private Long managerId;
    private SubdivisionDto subdivision;
    private EmployeeDto manager;
}