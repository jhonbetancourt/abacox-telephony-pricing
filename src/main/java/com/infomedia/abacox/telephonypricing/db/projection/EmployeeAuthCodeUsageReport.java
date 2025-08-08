package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native audit report on calls made via authorization codes.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface EmployeeAuthCodeUsageReport {
    String getEmployeeName();
    String getAuthCode();
    String getOperatorName();
    String getTelephonyTypeName();
    Long getCallCount();
    Long getTotalDuration();
    BigDecimal getDurationPercentage();
    BigDecimal getTotalBilledAmount();
    BigDecimal getBilledAmountPercentage();
}