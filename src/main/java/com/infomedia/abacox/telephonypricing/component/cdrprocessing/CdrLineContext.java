package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A simple DTO to hold the context for processing a single CDR line.
 * This is used to pass information from the non-transactional routing service
 * to the transactional batch processing service.
 */
@Getter
@AllArgsConstructor
public class CdrLineContext {
    private final String cdrLine;
    private final Long fileInfoId;
    private final CommunicationLocation targetCommLocation;
    private final CdrTypeProcessor processor;
}