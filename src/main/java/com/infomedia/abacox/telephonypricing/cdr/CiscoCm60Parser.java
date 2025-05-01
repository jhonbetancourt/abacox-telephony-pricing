package com.infomedia.abacox.telephonypricing.cdr;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.apache.logging.log4j.util.Strings;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component("ciscoCm60Parser")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60Parser implements CdrParser {

    // --- Constants based on CM_TipoPlanta ---
    // Header names should be lowercase for consistent map lookup
    private static final Map<String, String> HEADER_MAPPING = Map.ofEntries(
            Map.entry("callingpartynumberpartition", "callingPartyNumberPartition"),
            Map.entry("callingpartynumber", "callingPartyNumber"),
            Map.entry("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartynumber", "finalCalledPartyNumber"),
            Map.entry("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition"),
            Map.entry("originalcalledpartynumber", "originalCalledPartyNumber"),
            Map.entry("lastredirectdnpartition", "lastRedirectDnPartition"),
            Map.entry("lastredirectdn", "lastRedirectDn"),
            Map.entry("destmobiledevicename", "destMobileDeviceName"), // Added partition for mobile
            Map.entry("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber"), // Added mobile number
            Map.entry("lastredirectredirectreason", "lastRedirectRedirectReason"),
            Map.entry("datetimeorigination", "dateTimeOrigination"),
            Map.entry("datetimeconnect", "dateTimeConnect"),
            Map.entry("datetimedisconnect", "dateTimeDisconnect"),
            Map.entry("origdevicename", "origDeviceName"),
            Map.entry("destdevicename", "destDeviceName"),
            Map.entry("origvideocap_codec", "origVideoCap_Codec"),
            Map.entry("origvideocap_bandwidth", "origVideoCap_Bandwidth"),
            Map.entry("origvideocap_resolution", "origVideoCap_Resolution"),
            Map.entry("destvideocap_codec", "destVideoCap_Codec"),
            Map.entry("destvideocap_bandwidth", "destVideoCap_Bandwidth"),
            Map.entry("destvideocap_resolution", "destVideoCap_Resolution"),
            Map.entry("joinonbehalfof", "joinOnBehalfOf"),
            Map.entry("destcallterminationonbehalfof", "destCallTerminationOnBehalfOf"),
            Map.entry("destconversationid", "destConversationId"),
            Map.entry("globalcallid_callid", "globalCallID_callId"),
            Map.entry("duration", "duration"),
            Map.entry("authcodedescription", "authCodeDescription")
            // Ensure all keys used in getField are lowercase here
    );

    private static final String DELIMITER = ",";
    private static final String HEADER_START_TOKEN = "cdrRecordType";
    private static final int MIN_EXPECTED_FIELDS = 50; // Keep as an estimate
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    private static final String CONFERENCE_PREFIX = "b"; // Default conference prefix from PHP logic

    @Override
    public Optional<RawCdrDto> parseLine(String line, Map<String, Integer> headerMap) {
        if (line == null || line.trim().isEmpty() || isHeaderLine(line) || line.startsWith(";")) {
             log.trace("Skipping empty, header, or comment line: {}", line);
            return Optional.empty();
        }

        if (headerMap == null || headerMap.isEmpty()) {
             log.warn("Header map is missing or empty, cannot parse line accurately: {}", line);
             return Optional.empty(); // Cannot parse without headers
        }

        if (!line.contains(DELIMITER)) {
            log.warn("Line does not contain delimiter '{}': {}", DELIMITER, line);
            return Optional.empty();
        }

        // Split using the delimiter, preserving trailing empty fields
        String[] fields = line.split(DELIMITER, -1);

        // Clean fields (remove quotes, trim whitespace)
        for (int i = 0; i < fields.length; i++) {
            fields[i] = cleanCsvField(fields[i]);
        }

        try {
            RawCdrDto.RawCdrDtoBuilder builder = RawCdrDto.builder();
            builder.cdrLine(line); // Store original line

            // --- Extract Core Fields using headerMap ---
            String globalCallId = getField(fields, headerMap, "globalcallid_callid");
            LocalDateTime dateTimeOrigination = parseTimestamp(getField(fields, headerMap, "datetimeorigination"));
            LocalDateTime dateTimeConnect = parseTimestamp(getField(fields, headerMap, "datetimeconnect"));
            LocalDateTime dateTimeDisconnect = parseTimestamp(getField(fields, headerMap, "datetimedisconnect"));
            String callingPartyNumber = getField(fields, headerMap, "callingpartynumber");
            String callingPartyNumberPartition = getField(fields, headerMap, "callingpartynumberpartition");
            String finalCalledPartyNumber = getField(fields, headerMap, "finalcalledpartynumber");
            String finalCalledPartyNumberPartition = getField(fields, headerMap, "finalcalledpartynumberpartition");
            String originalCalledPartyNumber = getField(fields, headerMap, "originalcalledpartynumber");
            String originalCalledPartyNumberPartition = getField(fields, headerMap, "originalcalledpartynumberpartition");
            String lastRedirectDn = getField(fields, headerMap, "lastredirectdn");
            String lastRedirectDnPartition = getField(fields, headerMap, "lastredirectdnpartition");
            String destMobileDeviceName = getField(fields, headerMap, "destmobiledevicename");
            String finalMobileCalledPartyNumber = getField(fields, headerMap, "finalmobilecalledpartynumber");
            int duration = parseInteger(getField(fields, headerMap, "duration"));
            String authCodeDescription = getField(fields, headerMap, "authcodedescription");
            String origDeviceName = getField(fields, headerMap, "origdevicename");
            String destDeviceName = getField(fields, headerMap, "destdevicename");
            int lastRedirectRedirectReason = parseInteger(getField(fields, headerMap, "lastredirectredirectreason"));
            Integer joinOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "joinonbehalfof"));
            Integer destCallTerminationOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "destcallterminationonbehalfof"));
            Integer destConversationId = parseOptionalInteger(getField(fields, headerMap, "destconversationid"));

            // --- Apply Logic from PHP (CM_FormatoCDR and helpers) ---

            // 1. Handle empty finalCalledPartyNumber (fallback to original)
            if (Strings.isBlank(finalCalledPartyNumber)) {
                finalCalledPartyNumber = originalCalledPartyNumber;
                finalCalledPartyNumberPartition = originalCalledPartyNumberPartition;
                 log.trace("Using originalCalledPartyNumber ({}) as finalCalledPartyNumber for {}", finalCalledPartyNumber, globalCallId);
            }

            // 2. Calculate Ring Duration
            int ringDuration = 0;
            if (dateTimeConnect != null && dateTimeOrigination != null && dateTimeConnect.isAfter(dateTimeOrigination)) {
                ringDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeConnect).getSeconds();
            } else if (dateTimeDisconnect != null && dateTimeOrigination != null && dateTimeDisconnect.isAfter(dateTimeOrigination)) {
                // If call never connected, ring duration is the time until disconnect
                ringDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeDisconnect).getSeconds();
                duration = 0; // Ensure duration is 0 if call never connected
            }
            builder.ringDuration(Math.max(0, ringDuration)); // Ensure non-negative

            // 3. Determine Basic Incoming Status (Refined during enrichment)
            // Basic check: No partition and caller number looks external or is blank
            boolean isCallingExtLikely = isLikelyExtensionBasic(callingPartyNumber);
            boolean incoming = (Strings.isBlank(callingPartyNumberPartition) && (Strings.isBlank(callingPartyNumber) || !isCallingExtLikely));
            builder.incoming(incoming);

            // 4. Conference Prefix 'b' handling is deferred to enrichment service.

            // --- Build DTO ---
            builder.globalCallId(globalCallId);
            builder.dateTimeOrigination(dateTimeOrigination);
            builder.dateTimeConnect(dateTimeConnect);
            builder.dateTimeDisconnect(dateTimeDisconnect);
            builder.callingPartyNumber(callingPartyNumber);
            builder.callingPartyNumberPartition(callingPartyNumberPartition);
            builder.finalCalledPartyNumber(finalCalledPartyNumber);
            builder.finalCalledPartyNumberPartition(finalCalledPartyNumberPartition);
            builder.originalCalledPartyNumber(originalCalledPartyNumber);
            builder.originalCalledPartyNumberPartition(originalCalledPartyNumberPartition);
            builder.lastRedirectDn(lastRedirectDn);
            builder.lastRedirectDnPartition(lastRedirectDnPartition);
            builder.destMobileDeviceName(destMobileDeviceName);
            builder.finalMobileCalledPartyNumber(finalMobileCalledPartyNumber);
            builder.duration(duration);
            builder.authCodeDescription(authCodeDescription);
            builder.origDeviceName(origDeviceName);
            builder.destDeviceName(destDeviceName);
            builder.lastRedirectRedirectReason(lastRedirectRedirectReason);
            builder.joinOnBehalfOf(joinOnBehalfOf);
            builder.destCallTerminationOnBehalfOf(destCallTerminationOnBehalfOf);
            builder.destConversationId(destConversationId);

            // Video fields
            builder.origVideoCapCodec(getField(fields, headerMap, "origvideocap_codec"));
            builder.origVideoCapBandwidth(parseOptionalInteger(getField(fields, headerMap, "origvideocap_bandwidth")));
            builder.origVideoCapResolution(getField(fields, headerMap, "origvideocap_resolution"));
            builder.destVideoCapCodec(getField(fields, headerMap, "destvideocap_codec"));
            builder.destVideoCapBandwidth(parseOptionalInteger(getField(fields, headerMap, "destvideocap_bandwidth")));
            builder.destVideoCapResolution(getField(fields, headerMap, "destvideocap_resolution"));

            RawCdrDto dto = builder.build();
            log.trace("Successfully parsed CDR line into DTO: {}", dto.getGlobalCallId());
            return Optional.of(dto);

        } catch (Exception e) {
            log.error("Failed to parse CDR line: {} - Error: {}", line, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public boolean isHeaderLine(String line) {
        // Cisco CDR headers start with "cdrRecordType" (case-insensitive)
        return line != null && line.toLowerCase().trim().startsWith(HEADER_START_TOKEN.toLowerCase());
    }

     @Override
    public Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> headerMap = new HashMap<>();
        if (headerLine == null || !isHeaderLine(headerLine)) {
            log.warn("Provided line is not a valid header line: {}", headerLine);
            return headerMap; // Return empty map if not a valid header
        }
        String[] headers = headerLine.split(DELIMITER, -1);
        for (int i = 0; i < headers.length; i++) {
            String cleanedHeader = cleanCsvField(headers[i].trim()).toLowerCase();
            if (!cleanedHeader.isEmpty()) {
                // Use the standard key from HEADER_MAPPING if available, otherwise use the cleaned header
                // Ensure the key stored in the map is the standard one used by getField
                String standardKey = findStandardKey(cleanedHeader);
                headerMap.put(standardKey, i);
            }
        }
        log.debug("Parsed header map: {}", headerMap);
        return headerMap;
    }

    // Helper to find the standard key (value in HEADER_MAPPING) for a given cleaned header
    private String findStandardKey(String cleanedHeader) {
        // Iterate through the mapping to find the standard key associated with the cleaned header
        for (Map.Entry<String, String> entry : HEADER_MAPPING.entrySet()) {
            if (entry.getKey().equals(cleanedHeader)) {
                return entry.getValue().toLowerCase(); // Return the standard key (value from map), lowercased
            }
        }
        // If no mapping found, return the cleaned header itself (lowercased)
        return cleanedHeader;
    }


    @Override
    public int getExpectedMinFields() {
        // This is an estimate, Cisco CDRs can vary.
        // The actual check should be based on the presence of required fields via headerMap.
        return MIN_EXPECTED_FIELDS;
    }

     @Override
    public String getDelimiter() {
        return DELIMITER;
    }

    // --- Helper Methods ---

    private String getField(String[] fields, Map<String, Integer> headerMap, String standardHeaderName) {
        // Lookup using the standard, lowercase key defined in HEADER_MAPPING constants
        Integer index = headerMap.get(standardHeaderName.toLowerCase());
        if (index != null && index >= 0 && index < fields.length) {
            String value = fields[index];
             // Return empty string if the field is null, otherwise return the value
             return value != null ? value : "";
        }
        log.trace("Header key '{}' not found in header map or index out of bounds.", standardHeaderName);
        return ""; // Return empty string if header not found or index invalid
    }


    private LocalDateTime parseTimestamp(String timestampStr) {
        if (Strings.isBlank(timestampStr) || "0".equals(timestampStr)) {
            return null; // Represents cases where the timestamp is explicitly zero or empty
        }
        try {
            long epochSeconds = Long.parseLong(timestampStr);
            // Handle potential zero timestamp after parsing, although already checked
            if (epochSeconds == 0) return null;
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            log.warn("Could not parse timestamp: '{}'. Returning null.", timestampStr);
            return null;
        } catch (Exception e) { // Catch other potential exceptions like DateTimeException
            log.error("Error converting epoch seconds {} to LocalDateTime: {}", timestampStr, e.getMessage());
            return null;
        }
    }

    private int parseInteger(String intStr) {
        if (Strings.isBlank(intStr)) {
           return 0;
        }
        try {
            // Use regex for stricter validation before parsing
            if (!intStr.matches("-?\\d+")) {
                 log.warn("Non-numeric value encountered where integer expected: '{}'. Returning 0.", intStr);
                 return 0;
            }
            return Integer.parseInt(intStr);
        } catch (NumberFormatException e) {
            log.warn("Could not parse integer: '{}'. Returning 0.", intStr);
            return 0;
        }
    }
     private Integer parseOptionalInteger(String intStr) {
        if (Strings.isBlank(intStr) || "0".equals(intStr)) {
           return null; // Return null for blank or zero, indicating absence or zero value
        }
        try {
             // Use regex for stricter validation before parsing
             if (!intStr.matches("-?\\d+")) {
                 log.warn("Non-numeric value encountered where optional integer expected: '{}'. Returning null.", intStr);
                 return null;
            }
            return Integer.parseInt(intStr);
        } catch (NumberFormatException e) {
            log.warn("Could not parse optional integer: '{}'. Returning null.", intStr);
            return null;
        }
    }


    private String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        // Handle quoted fields correctly
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            // Replace escaped double quotes ("") with a single double quote (")
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

     // Basic check used during parsing before full context is available.
     // Full check using DB config happens in EnrichmentService.
     private boolean isLikelyExtensionBasic(String number) {
        if (!StringUtils.hasText(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;
        // Allow digits, hash, asterisk as per PHP logic
        if (!effectiveNumber.matches("[\\d#*]+")) return false;
        // Use default plausible lengths here, enrichment service will use DB config
        // These defaults are less critical now as the main check is in enrichment.
        int minExtLength = 2; // Default plausible min length
        int maxExtLength = 7; // Default plausible max length
        int numLength = effectiveNumber.length();
        return (numLength >= minExtLength && numLength <= maxExtLength);
    }

    // Helper to check for conference prefix 'b' (EVAL-CONFERENCIA)
    private boolean isConferenceCallIndicator(String number) {
        if (number == null || number.length() <= CONFERENCE_PREFIX.length()) {
            return false;
        }
        String prefix = number.substring(0, CONFERENCE_PREFIX.length());
        String rest = number.substring(CONFERENCE_PREFIX.length());
        // Check if prefix matches (case-insensitive) and the rest is numeric
        return CONFERENCE_PREFIX.equalsIgnoreCase(prefix) && rest.matches("\\d+");
    }
}