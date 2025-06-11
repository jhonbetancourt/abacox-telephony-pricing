package com.infomedia.abacox.telephonypricing.dto.contact;

import com.infomedia.abacox.telephonypricing.dto.company.CompanyDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.Contact}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactDto extends ActivableDto {
    private Long id;
    private Boolean contactType;
    private Long employeeId;
    private Long companyId;
    private String phoneNumber;
    private String name;
    private String description;
    private Long indicatorId;
    private EmployeeDto employee;
    private CompanyDto company;
    private IndicatorDto indicator;
}