package com.infomedia.abacox.telephonypricing.service.dashboard;

import com.infomedia.abacox.telephonypricing.dto.dashboard.DashboardOverviewDto;

import java.time.LocalDateTime;

public interface DashboardService {
    DashboardOverviewDto getDashboardOverview(LocalDateTime startDate, LocalDateTime endDate);
}
