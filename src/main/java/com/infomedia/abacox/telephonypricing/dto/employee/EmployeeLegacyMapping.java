package com.infomedia.abacox.telephonypricing.dto.employee;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmployeeLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String name;
    private String subdivisionId;
    private String costCenterId;
    private String extension;
    private String communicationLocationId;
    private String jobPositionId;
    private String email;
    private String phone;
    private String address;
    private String idNumber;
}