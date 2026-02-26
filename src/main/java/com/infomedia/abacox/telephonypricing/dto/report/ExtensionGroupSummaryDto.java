package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Summary (TOTALES) row for the Extension Group report.
 * Matches the legacy "TOTALES" footer row in grupo_valores.php.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionGroupSummaryDto {

    /** Always "TOTALES" */
    private String label;

    private Integer totalIncoming;
    private Integer totalOutgoing;
    private Integer totalVoicemail;
    private Integer total;
    private BigDecimal totalPercent;
}
