package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for the "Unassigned Calls" report.
 * <p>
 * Represents aggregated call data for extensions that are not assigned to a specific
 * employee or are assigned for a special reason (cause #5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnassignedCallReportDto {

    private String employeeExtension;

    private Long employeeId;

    private Integer assignmentCause;

    private String commLocationDirectory;

    private Long commLocationId;

    private String plantTypeName;

    private BigDecimal totalCost;

    private Long totalDuration;

    private Long callCount;

    private LocalDateTime lastCallDate;
}