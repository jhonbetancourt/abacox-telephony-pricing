package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native "Highest Consumption Employee" report.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface HighestConsumptionEmployeeReport {
    String getEmployeeName();
    String getExtension();
    String getOriginCity();
    String getOriginDepartmentCountry();
    Long getCallCount();
    Long getTotalDuration();
    BigDecimal getTotalBilledAmount();
}