package com.infomedia.abacox.telephonypricing.dto.employee;

import com.infomedia.abacox.telephonypricing.dto.comlocation.CommunicationLocationDto;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterDto;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

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
    private SubdivisionDto subdivision;
    private CostCenterDto costCenter;
    private CommunicationLocationDto communicationLocation;
    private JobPositionDto jobPosition;
}