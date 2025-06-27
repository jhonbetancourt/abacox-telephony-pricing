package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for the Processing Failure diagnostic report.
 * <p>
 * Represents aggregated data on failed call records, grouped by error type and the
 * communication location where the failure occurred.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingFailureReportDto {

    private String errorType;

    private Long commLocationId;

    private String commLocationDirectory;

    private Long plantTypeId;

    private String plantTypeName;

    private Long failureCount;
}