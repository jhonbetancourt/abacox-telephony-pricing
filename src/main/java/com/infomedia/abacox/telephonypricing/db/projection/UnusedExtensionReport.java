package com.infomedia.abacox.telephonypricing.db.projection;

import java.time.LocalDateTime;

/**
 * Projection for the "Unused Extension Report".
 * Represents an employee whose extension has not been used for calls
 * within a specified period, and includes additional contextual information.
 */
public interface UnusedExtensionReport {
    Long getEmployeeId();

    String getEmployeeName();

    String getExtension();

    LocalDateTime getEmployeeHistoryStartDate();

    String getSubdivisionName();

    String getCostCenter();

    String getPlant();

    LocalDateTime getLastCallDate(); // The last time this extension was ever used

    Long getOutgoingCalls();

    Long getIncomingCalls();

    Long getTotalCalls();
}
