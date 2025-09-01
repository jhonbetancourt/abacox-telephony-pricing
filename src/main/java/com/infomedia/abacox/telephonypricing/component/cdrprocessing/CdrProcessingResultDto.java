package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CdrProcessingResultDto {
    private Long fileInfoId;
    private String status; // e.g., "COMPLETED", "FAILED"
    private String message;
    private long linesRead;
    private int successfulRecords;
    private int quarantinedRecords;
    private int skippedLines;
}