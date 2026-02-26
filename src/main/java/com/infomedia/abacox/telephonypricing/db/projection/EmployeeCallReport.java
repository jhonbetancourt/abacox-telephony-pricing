package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

public interface EmployeeCallReport {
    Long getEmployeeId();

    String getEmployeeName();

    String getExtension();

    String getCityName();

    Long getOutgoingCalls();

    Long getIncomingCalls();

    BigDecimal getTotalCost();
}