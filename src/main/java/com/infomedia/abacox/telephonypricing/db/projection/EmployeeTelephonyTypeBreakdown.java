package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

public interface EmployeeTelephonyTypeBreakdown {
    Long getEmployeeId();

    String getTelephonyTypeName();

    BigDecimal getTotalCost();
}
