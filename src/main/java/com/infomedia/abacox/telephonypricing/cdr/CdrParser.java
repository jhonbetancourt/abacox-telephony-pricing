package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Map;
import java.util.Optional;

public interface CdrParser {
    /**
     * Parses a single line from a CDR source.
     * @param line The raw CDR line.
     * @param headerMap Optional map of header names to column indices, if applicable.
     * @return Optional containing RawCdrDto if parsing is successful and valid, empty otherwise.
     */
    Optional<RawCdrDto> parseLine(String line, Map<String, Integer> headerMap);

    /**
     * Identifies if a line is a header line.
     * @param line The raw CDR line.
     * @return true if it's a header, false otherwise.
     */
    boolean isHeaderLine(String line);

     /**
     * Parses a header line to create a map of header names to column indices.
     * @param headerLine The raw header line.
     * @return Map of header names (lowercase) to their 0-based index.
     */
    Map<String, Integer> parseHeader(String headerLine);

    /**
     * Returns the expected minimum number of fields for this CDR type.
     * Can be used for basic validation before detailed parsing.
     * @return Minimum number of fields.
     */
    int getExpectedMinFields();

     /**
     * Returns the delimiter used by this parser.
     * @return The delimiter character or string.
     */
    String getDelimiter();
}