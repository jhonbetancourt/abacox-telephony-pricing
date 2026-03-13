package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * Projection for the "Consolidado por Área" report main query.
 * One row per display subdivision with aggregated totals.
 * Telephony type breakdown is fetched separately via SubdivisionTelephonyTypeBreakdown.
 */
public interface SubdivisionUsageByTypeReport {

    Long getSubdivisionId();
    String getSubdivisionName();
    BigDecimal getTotalBilledAmount();
    Long getTotalDuration();
}
