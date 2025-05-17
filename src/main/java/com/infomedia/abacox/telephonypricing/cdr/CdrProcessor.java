package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import java.util.Map;

public interface CdrProcessor {
    /**
     * Processes a single CDR line (or a block of lines if the CDR format is multi-line).
     *
     * @param cdrLine The raw CDR line(s).
     * @param commLocation The communication location providing context.
     * @param columnMapping Map of expected column names to their indices in the CDR.
     * @return A partially populated CallRecord DTO or Entity.
     * @throws CdrProcessingException if parsing fails.
     */
    RawCiscoCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping);

    /**
     * Establishes the column mapping from the header line of the CDR file.
     * @param headerLine The header line from the CDR file.
     * @return A map where keys are internal field names (e.g., "callingPartyNumber")
     *         and values are the 0-based column index in the CDR.
     * @throws CdrProcessingException if header is invalid.
     */
    Map<String, Integer> establishColumnMapping(String headerLine);
}