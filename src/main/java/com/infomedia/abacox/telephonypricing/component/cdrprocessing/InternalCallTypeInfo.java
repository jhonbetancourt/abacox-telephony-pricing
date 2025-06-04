package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.Data;

@Data
public class InternalCallTypeInfo {
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private Long destinationIndicatorId; // Indicator of the destination employee
    private Long originIndicatorId;    // Indicator of the origin employee
    private String additionalInfo;
    private boolean ignoreCall = false;
    private boolean effectivelyIncoming = false; // If the call should be treated as incoming despite initial parsing
    private Employee originEmployee;
    private Employee destinationEmployee;
}