// src/main/java/com/infomedia/abacox/telephonypricing/db/projection/SubdivisionUsageByTypeReport.java
package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native report that breaks down subdivision (department)
 * usage by telephony type.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface SubdivisionUsageByTypeReport {

    Long getSubdivisionId();
    String getSubdivisionName();
    String getTelephonyTypeName();
    BigDecimal getTotalBilledAmount();
    Long getTotalDuration();
}