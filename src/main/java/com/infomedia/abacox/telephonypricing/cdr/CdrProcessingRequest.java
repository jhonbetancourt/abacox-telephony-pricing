package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Builder;
import lombok.Data;

// Input metadata for processing a CDR source
@Data
@Builder
public class CdrProcessingRequest {
    private Long communicationLocationId;
    private String sourceDescription; // e.g., filename, stream identifier
    private Long fileInfoId; // Optional reference to FileInfo entity
}