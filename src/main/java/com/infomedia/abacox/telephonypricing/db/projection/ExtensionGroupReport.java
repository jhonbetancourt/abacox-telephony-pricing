package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * Projection for the "Extension Group Report".
 * Provides a consolidated view of call statistics for a specific set of employees,
 * including counts for incoming, outgoing, and voicemail calls, along with their
 * overall contribution percentage.
 */
public interface ExtensionGroupReport {
    Long getEmployeeId();
    String getEmployeeName();
    String getExtension();
    String getSubdivisionName();
    String getIndicatorDepartmentCountry();
    String getIndicatorCityName();
    Integer getIncomingCount();
    Integer getOutgoingCount();
    Integer getVoicemailCount();
    Integer getTotal();
    BigDecimal getPercent();
}