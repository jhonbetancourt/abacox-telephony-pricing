package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.Data;

@Data
public class IncomingCallOriginInfo {
    private String effectiveNumber; // Number after transformations by buscarOrigen
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private Long operatorId;
    private String operatorName;
    private Long indicatorId; // Source indicator for incoming call
    private String destinationDescription; // Description of the source
}