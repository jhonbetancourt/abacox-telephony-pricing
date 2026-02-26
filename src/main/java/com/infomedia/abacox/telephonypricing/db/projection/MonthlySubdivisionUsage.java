package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

public interface MonthlySubdivisionUsage {
    Long getSubdivisionId();

    String getSubdivisionName();

    Integer getYear();

    Integer getMonth();

    BigDecimal getTotalBilledAmount();
}
