package com.infomedia.abacox.telephonypricing.dto.contact;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String contactType;
    private String employeeId;
    private String companyId;
    private String phoneNumber;
    private String name;
    private String description;
    private String indicatorId;
}