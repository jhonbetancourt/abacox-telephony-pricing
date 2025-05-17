package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InternalCallTypeResultDto {
    private TelephonyType telephonyType;
    private Operator operator; // Usually a default "internal" operator
    private Indicator destinationIndicator; // Indicator of the destination employee or location
    private Employee destinationEmployee;
    private boolean ignoreCall; // If the call should be ignored (e.g., inter-plant global call not handled by this node)
    private boolean isIncomingInternal; // If the call, though internal, should be treated as incoming to this commLocation
}
