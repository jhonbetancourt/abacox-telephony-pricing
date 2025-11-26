// File: com/infomedia/abacox/telephonypricing/cdr/CdrProcessor.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;

import java.util.List;
import java.util.Map;

public interface CdrProcessor {
    /**
     * Probes the initial lines of a file to validate if this processor can handle the format.
     */
    boolean probe(List<String> initialLines);

    /**
     * Checks if the given line is a header line for this CDR type.
     */
    boolean isHeaderLine(String line);

    /**
     * Parses the header line and returns the column mapping.
     * Use this map in subsequent calls to evaluateFormat.
     *
     * @param headerLine The identified header line.
     * @return A map of Column Name -> Index
     */
    Map<String, Integer> parseHeader(String headerLine);

    /**
     * Parses a data line into a CdrData object.
     *
     * @param cdrLine The data line to parse.
     * @param commLocation The communication location context.
     * @param extensionLimits Limits for determining if a number is internal.
     * @param headerPositions The column mapping derived from the file header.
     * @return CdrData object, or null if the line should be skipped.
     */
    CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation, ExtensionLimits extensionLimits, Map<String, Integer> headerPositions);

    /**
     * Gets the unique identifiers for the plant types this processor handles.
     */
    List<Long> getPlantTypeIdentifiers();

    /**
     * Gets a list of authorization code descriptions that should be ignored.
     */
    List<String> getIgnoredAuthCodeDescriptions();
}