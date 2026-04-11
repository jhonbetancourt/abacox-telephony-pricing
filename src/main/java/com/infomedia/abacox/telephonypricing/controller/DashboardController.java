package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import com.infomedia.abacox.telephonypricing.dto.dashboard.DashboardOverviewDto;
import com.infomedia.abacox.telephonypricing.dto.dashboard.EmployeeActivityDashboardDto;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@RestController
@Tag(name = "Dashboard", description = "Dashboard Aggregation API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @RequiresPermission(Permissions.DASHBOARD_READ)
    @Operation(summary = "Get dashboard overview")
    @GetMapping(value = "/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public DashboardOverviewDto getOverview(
            @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {

        return dashboardService.getDashboardOverview(startDate, endDate);
    }

    @RequiresPermission(Permissions.DASHBOARD_READ)
    @Operation(summary = "Get employee activity dashboard")
    @GetMapping(value = "/employeeActivity", produces = MediaType.APPLICATION_JSON_VALUE)
    public EmployeeActivityDashboardDto getEmployeeActivity(
            @RequestParam(required = false) String employeeName,
            @RequestParam(required = false) String employeeExtension,
            @RequestParam(required = false) Long subdivisionId,
            @RequestParam(required = false) Long costCenterId,
            @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(pattern = DateTimePattern.DATE_TIME) LocalDateTime endDate) {

        return dashboardService.getEmployeeActivityDashboard(employeeName, employeeExtension, subdivisionId,
                costCenterId, startDate, endDate);
    }
}
