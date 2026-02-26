package com.infomedia.abacox.telephonypricing.db.projection;

public interface MonthlySubdivisionUsage {
    Long getSubdivisionId();

    String getSubdivisionName();

    String getMonthlyCostsCsv();
}
