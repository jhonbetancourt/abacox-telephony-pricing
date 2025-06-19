package com.infomedia.abacox.telephonypricing.db.projection;

import java.time.LocalDateTime;

/**
 * A Projection Interface for the native employee activity report query.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface EmployeeActivityReport {

    Long getEmployeeId();
    String getEmployeeName();
    String getExtension();
    String getCostCenterWorkOrder();
    String getSubdivisionName();
    String getOfficeLocation();
    Long getOutgoingCallCount();
    Long getIncomingCallCount();
    LocalDateTime getLastIncomingCallDate();
    LocalDateTime getLastOutgoingCallDate();
}