// File: com/infomedia/abacox/telephonypricing/cdr/ICdrTypeProcessor.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;

import java.util.List;

public interface CdrProcessor {
    /**
     * Probes the initial lines of a file to determine if this processor can handle the format.
     * This is the key method for automatic format detection.
     *
     * @param initialLines A list of the first few lines of the file.
     * @return true if the processor recognizes the format, false otherwise.
     */
    boolean probe(List<String> initialLines);

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
    CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation, ExtensionLimits extensionLimits);

    /**
     * Gets a unique identifier for the plant type this processor handles.
     *
     * @return String identifier.
     */
    Long getPlantTypeIdentifier();

    /**
     * Gets a list of authorization code descriptions that should be ignored
     * during employee lookup for this specific plant type. These are typically
     * error messages from the PBX that appear in the auth code field.
     *
     * @return A list of strings to ignore. Can be an empty list if none.
     */
    List<String> getIgnoredAuthCodeDescriptions();
}