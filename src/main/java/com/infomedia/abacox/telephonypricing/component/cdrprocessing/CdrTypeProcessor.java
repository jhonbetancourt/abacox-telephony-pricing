// File: com/infomedia/abacox/telephonypricing/cdr/ICdrTypeProcessor.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;

public interface CdrTypeProcessor {
    /**
     * Checks if the given line is a header line for this CDR type.
     * The line passed here should be the raw (or trimmed) line from the file.
     * The implementation is responsible for any necessary cleaning (like removing quotes)
     * before performing the check.
     *
     * @param line The raw or trimmed line from the CDR file.
     * @return true if the line is identified as a header, false otherwise.
     */
    boolean isHeaderLine(String line);

    /**
     * Parses the header line to configure the processor for subsequent data lines.
     *
     * @param headerLine The identified header line.
     */
    void parseHeader(String headerLine);

    /**
     * Parses a data line into a CdrData object.
     *
     * @param cdrLine The data line to parse.
     * @param commLocation The communication location context.
     * @return CdrData object, or null if the line should be skipped (e.g., comment, type definition).
     */
    CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation);

    /**
     * Gets a unique identifier for the plant type this processor handles.
     *
     * @return String identifier.
     */
    Long getPlantTypeIdentifier();
}