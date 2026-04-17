package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.multitenancy.MultitenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

/**
 * Preloads the dashboard (overview + employee activity) for the current month
 * for every tenant. Runs once on startup (async, so boot isn't delayed) and
 * every day at 02:15 local time thereafter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardPreloadScheduler {

    private final MultitenantRunner multitenantRunner;
    private final DashboardService dashboardService;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        log.info("Starting dashboard cache warm-up on application ready");
        preloadCurrentMonth();
        log.info("Finished dashboard cache warm-up on application ready");
    }

    @Scheduled(cron = "0 15 2 * * *")
    public void dailyPreload() {
        log.info("Starting scheduled dashboard cache preload");
        preloadCurrentMonth();
        log.info("Finished scheduled dashboard cache preload");
    }

    private void preloadCurrentMonth() {
        YearMonth current = YearMonth.now();
        LocalDateTime start = current.atDay(1).atStartOfDay();
        LocalDateTime end   = current.atEndOfMonth().atTime(LocalTime.MAX);

        multitenantRunner.runForAllTenants(tenant -> {
            try {
                log.debug("Preloading dashboard overview for tenant={} range={}..{}", tenant, start, end);
                dashboardService.getDashboardOverview(start, end);

                log.debug("Preloading employee activity dashboard for tenant={}", tenant);
                dashboardService.getEmployeeActivityDashboard(null, null, null, null, start, end);
            } catch (Exception ex) {
                log.error("Dashboard preload failed for tenant={}", tenant, ex);
            }
        });
    }
}
