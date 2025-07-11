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
    String getSubdivisionName();
    String getCostCenterName();
    String getCostCenterWorkOrder();
    String getCommLocationDirectory();
    String getIndicatorDepartmentCountry();
    String getIndicatorCityName();
    LocalDateTime getLastCallDate(); // The last time this extension was ever used
}