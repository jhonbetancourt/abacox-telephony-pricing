package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * Projection for the "Consolidado por Área" breakdown query.
 * Returns one row per (display subdivision, telephony type) with aggregated cost.
 * Used to populate the telephonyTypeCosts list on SubdivisionUsageByTypeReportDto.
 */
public interface SubdivisionTelephonyTypeBreakdown {

    Long getSubdivisionId();
    String getTelephonyTypeName();
    BigDecimal getTotalBilledAmount();
}
