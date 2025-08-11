package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native "Top Called Numbers" report.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface DialedNumberUsageReport {
    String getPhoneNumber();
    String getCompanyName();
    String getContactName();
    String getDestinationCity();
    String getDestinationCountry();
    Long getCallCount();
    Long getTotalDuration();
    BigDecimal getTotalBilledAmount();
}