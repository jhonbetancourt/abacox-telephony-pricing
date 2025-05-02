// FILE: cdr/CiscoCm60Parser.java
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.apache.logging.log4j.util.Strings;

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

    // --- Constants ---
    // Header mapping remains the same as before
    private static final Map<String, String> HEADER_MAPPING = Map.ofEntries(
            Map.entry("callingpartynumberpartition", "callingPartyNumberPartition"),
            Map.entry("callingpartynumber", "callingPartyNumber"),
            Map.entry("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartynumber", "finalCalledPartyNumber"),
            Map.entry("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition"),
            Map.entry("originalcalledpartynumber", "originalCalledPartyNumber"),
            Map.entry("lastredirectdnpartition", "lastRedirectDnPartition"),
            Map.entry("lastredirectdn", "lastRedirectDn"),
            Map.entry("destmobiledevicename", "destMobileDeviceName"),
            Map.entry("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber"),
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
    );

    private static final String DELIMITER = ",";
    private static final String HEADER_START_TOKEN = "cdrRecordType";
    private static final int MIN_EXPECTED_FIELDS = 50;
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    private static final String CONFERENCE_PREFIX = "b"; // PHP: $_IDENTIFICADOR_CONFERENCIA

    @Override
    public Optional<StandardizedCallEventDto> parseLine(String line, Map<String, Integer> headerMap, CdrProcessingRequest metadata) {
        // Initial checks remain the same (null, empty, header, comment, data type lines)
        if (line == null || line.trim().isEmpty() || isHeaderLine(line) || line.startsWith(";")) {
            log.trace("Skipping empty, header, or comment line: {}", line);
            return Optional.empty();
        }
        String trimmedUpperLine = line.trim().toUpperCase();
        if (trimmedUpperLine.startsWith("INTEGER,") || trimmedUpperLine.startsWith("VARCHAR(")) {
            log.debug("Skipping data type definition line: {}", line.substring(0, Math.min(line.length(), 100)) + "...");
            return Optional.empty();
        }
        if (headerMap == null || headerMap.isEmpty()) {
            log.warn("Header map is missing or empty, cannot parse line accurately: {}", line);
            return Optional.empty();
        }
        if (!line.contains(DELIMITER)) {
            log.warn("Line does not contain delimiter '{}': {}", DELIMITER, line);
            return Optional.empty();
        }

        String cdrHash = HashUtil.sha256(line);
        if (cdrHash == null) {
            log.error("CRITICAL: Failed to generate hash for line. Skipping.");
            return Optional.empty();
        }

        String[] fields = line.split(DELIMITER, -1);
        for (int i = 0; i < fields.length; i++) {
            fields[i] = cleanCsvField(fields[i]);
        }

        try {
            // --- Extract Raw Fields (using helper) ---
            String globalCallId = getField(fields, headerMap, "globalcallid_callid");
            LocalDateTime dateTimeOrigination = parseTimestamp(getField(fields, headerMap, "datetimeorigination"));
            LocalDateTime dateTimeConnect = parseTimestamp(getField(fields, headerMap, "datetimeconnect"));
            LocalDateTime dateTimeDisconnect = parseTimestamp(getField(fields, headerMap, "datetimedisconnect"));
            String rawCallingPartyNumber = getField(fields, headerMap, "callingpartynumber");
            String rawCallingPartyNumberPartition = getField(fields, headerMap, "callingpartynumberpartition");
            String rawFinalCalledPartyNumber = getField(fields, headerMap, "finalcalledpartynumber");
            String rawFinalCalledPartyNumberPartition = getField(fields, headerMap, "finalcalledpartynumberpartition");
            String rawOriginalCalledPartyNumber = getField(fields, headerMap, "originalcalledpartynumber");
            String rawOriginalCalledPartyNumberPartition = getField(fields, headerMap, "originalcalledpartynumberpartition");
            String rawLastRedirectDn = getField(fields, headerMap, "lastredirectdn");
            String rawLastRedirectDnPartition = getField(fields, headerMap, "lastredirectdnpartition");
            String rawFinalMobileCalledPartyNumber = getField(fields, headerMap, "finalmobilecalledpartynumber"); // Mobile redirect number
            // String rawDestMobileDeviceName = getField(fields, headerMap, "destmobiledevicename"); // Mobile device name (partition equivalent?)
            int duration = parseInteger(getField(fields, headerMap, "duration"));
            String authCodeDescription = getField(fields, headerMap, "authcodedescription");
            String origDeviceName = getField(fields, headerMap, "origdevicename");
            String destDeviceName = getField(fields, headerMap, "destdevicename");
            int lastRedirectRedirectReason = parseInteger(getField(fields, headerMap, "lastredirectredirectreason"));
            Integer joinOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "joinonbehalfof"));
            Integer destCallTerminationOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "destcallterminationonbehalfof"));
            // Integer destConversationId = parseOptionalInteger(getField(fields, headerMap, "destconversationid")); // Used for more complex conference linking (omitted for now)

            // --- Cisco-Specific Interpretation (Refined based on PHP logic) ---

            // 1. Determine effective called number (fallback logic from CM_FormatoCDR)
            String effectiveCalledNumber = Strings.isBlank(rawFinalCalledPartyNumber) ? rawOriginalCalledPartyNumber : rawFinalCalledPartyNumber;
            // String effectiveCalledPartition = Strings.isBlank(rawFinalCalledPartyNumber) ? rawOriginalCalledPartyNumberPartition : rawFinalCalledPartyNumberPartition; // Partition not directly used in Standardized DTO

            // 2. Calculate Ring Duration (same as before)
            int ringDuration = calculateRingDuration(dateTimeOrigination, dateTimeConnect, dateTimeDisconnect);
            if (dateTimeConnect == null) duration = 0; // Ensure duration is 0 if call never connected

            // 3. Determine Basic Incoming Status (same as before)
            boolean isCallingExtLikely = isLikelyExtensionBasic(rawCallingPartyNumber);
            boolean isIncoming = (Strings.isBlank(rawCallingPartyNumberPartition) && (Strings.isBlank(rawCallingPartyNumber) || !isCallingExtLikely));

            // 4. Conference Check (same as before)
            boolean isConference = isConferenceCallIndicator(effectiveCalledNumber) || (joinOnBehalfOf != null && joinOnBehalfOf == 7);

            // 5. Determine Standardized Caller/Called/Redirect based on conference/transfer/mobile redirect status
            String finalCallingParty = rawCallingPartyNumber;
            String finalCalledParty = effectiveCalledNumber;
            String finalRedirectingParty = rawLastRedirectDn;
            CallTransferCause transferCause = CallTransferCause.NONE;

            // --- Refined Transfer/Conference/Mobile Logic ---
            boolean isMobileRedirect = Strings.isNotBlank(rawFinalMobileCalledPartyNumber) &&
                                       !rawFinalMobileCalledPartyNumber.equals(effectiveCalledNumber);

            if (isConference) {
                // Handle conference logic (swapping caller/redirect)
                if (Strings.isNotBlank(rawLastRedirectDn)) {
                    finalCallingParty = rawLastRedirectDn;
                    finalRedirectingParty = rawCallingPartyNumber;
                } else {
                    log.warn("Conference detected for CDR {} but LastRedirectDn is empty. Semantic caller/redirect might be inaccurate.", globalCallId);
                }
                finalCalledParty = effectiveCalledNumber; // Keep conference ID as called party
                isIncoming = false; // Treat as outgoing
                transferCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ? CallTransferCause.CONFERENCE_NOW : CallTransferCause.CONFERENCE;
            } else if (isMobileRedirect) {
                // Handle mobile redirect (final called party becomes the mobile number)
                log.debug("Mobile redirect detected for CDR {}: Original Called={}, Mobile Called={}", globalCallId, effectiveCalledNumber, rawFinalMobileCalledPartyNumber);
                finalCalledParty = rawFinalMobileCalledPartyNumber;
                // Redirecting party is the number *before* the mobile redirect
                finalRedirectingParty = effectiveCalledNumber;
                transferCause = CallTransferCause.AUTO; // Treat mobile redirect as an auto-transfer
                isIncoming = false; // Mobile redirects imply an outgoing leg
            } else if (Strings.isNotBlank(rawLastRedirectDn) && !rawLastRedirectDn.equals(effectiveCalledNumber)) {
                // Handle regular transfers/redirects
                // Check if the redirect DN itself indicates a conference end
                if (isConferenceCallIndicator(rawLastRedirectDn)) {
                    transferCause = CallTransferCause.CONFERENCE_END;
                    // In PHP, ext-redir was sometimes replaced with conference originator here, complex logic omitted for now
                } else if (lastRedirectRedirectReason > 0 && lastRedirectRedirectReason <= 16) {
                    // Map Cisco reason codes to standard causes if possible
                    transferCause = mapCiscoTransferReason(lastRedirectRedirectReason);
                } else {
                    transferCause = CallTransferCause.AUTO; // Default for other redirects
                }
                // Redirecting party is already set correctly
            }

            // --- Build Standardized DTO ---
            StandardizedCallEventDto.StandardizedCallEventDtoBuilder builder = StandardizedCallEventDto.builder();

            builder.globalCallId(globalCallId);
            builder.originalRawLine(line);
            builder.cdrHash(cdrHash);
            builder.callingPartyNumber(finalCallingParty);
            builder.calledPartyNumber(finalCalledParty);
            builder.authCode(authCodeDescription);

            builder.callStartTime(dateTimeOrigination);
            builder.callConnectTime(dateTimeConnect);
            builder.callEndTime(dateTimeDisconnect);
            builder.durationSeconds(duration);
            builder.ringDurationSeconds(ringDuration);

            builder.isIncoming(isIncoming);
            builder.isConference(isConference); // Keep flag for enrichment context
            builder.callTypeHint(determineCallTypeHint(isIncoming, isConference, finalCallingParty, finalCalledParty));

            builder.sourceTrunkIdentifier(origDeviceName);
            builder.destinationTrunkIdentifier(destDeviceName);
            builder.redirectingPartyNumber(finalRedirectingParty);
            builder.redirectReason(transferCause.getValue()); // Store standardized reason
            builder.disconnectCause(destCallTerminationOnBehalfOf);

            // Source Metadata
            builder.communicationLocationId(metadata.getCommunicationLocationId());
            builder.fileInfoId(metadata.getFileInfoId());
            builder.sourceDescription(metadata.getSourceDescription());

            StandardizedCallEventDto dto = builder.build();
            log.trace("Successfully parsed and standardized CDR line into DTO: {}", dto.getGlobalCallId());
            return Optional.of(dto);

        } catch (Exception e) {
            log.error("Failed to parse/standardize CDR line: {} - Error: {}", line, e.getMessage(), e);
            return Optional.empty();
        }
    }

    // --- Other CdrParser methods (isHeaderLine, parseHeader, etc.) ---
    // (Keep implementations from previous step)
    @Override
    public boolean isHeaderLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        try {
            String[] fields = line.split(getDelimiter(), -1);
            if (fields.length > 0) {
                String firstFieldCleaned = cleanCsvField(fields[0].trim());
                return HEADER_START_TOKEN.equalsIgnoreCase(firstFieldCleaned);
            }
        } catch (Exception e) {
            log.warn("Error checking if line is header: {}", line, e);
        }
        return false;
    }

     @Override
    public Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> headerMap = new HashMap<>();
        if (headerLine == null || !isHeaderLine(headerLine)) {
            log.warn("Provided line is not a valid header line: {}", headerLine);
            return headerMap;
        }
        String[] headers = headerLine.split(DELIMITER, -1);
        for (int i = 0; i < headers.length; i++) {
            String cleanedHeader = cleanCsvField(headers[i].trim()).toLowerCase();
            if (!cleanedHeader.isEmpty()) {
                String standardKey = findStandardKey(cleanedHeader);
                headerMap.put(standardKey, i);
            }
        }
        log.debug("Parsed header map: {}", headerMap);
        return headerMap;
    }

    private String findStandardKey(String cleanedHeader) {
        for (Map.Entry<String, String> entry : HEADER_MAPPING.entrySet()) {
            if (entry.getKey().equals(cleanedHeader)) {
                return entry.getValue().toLowerCase();
            }
        }
        log.trace("No explicit mapping found for header '{}', using it directly (lowercase).", cleanedHeader);
        return cleanedHeader;
    }

    @Override
    public int getExpectedMinFields() {
        return MIN_EXPECTED_FIELDS;
    }

     @Override
    public String getDelimiter() {
        return DELIMITER;
    }

    // --- Helper Methods ---

    private String getField(String[] fields, Map<String, Integer> headerMap, String standardFieldName) {
        Integer index = headerMap.get(standardFieldName.toLowerCase());
        if (index != null && index >= 0 && index < fields.length) {
            String value = fields[index];
             return value != null ? value : "";
        }
        log.trace("Header key '{}' not found in header map or index out of bounds.", standardFieldName);
        return "";
    }

    private LocalDateTime parseTimestamp(String timestampStr) {
        if (Strings.isBlank(timestampStr) || "0".equals(timestampStr)) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(timestampStr);
            if (epochSeconds == 0) return null;
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            log.warn("Could not parse timestamp: '{}'. Returning null.", timestampStr);
            return null;
        } catch (Exception e) {
            log.error("Error converting epoch seconds {} to LocalDateTime: {}", timestampStr, e.getMessage());
            return null;
        }
    }

    private int parseInteger(String intStr) {
        if (Strings.isBlank(intStr)) {
           return 0;
        }
        try {
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
           return null;
        }
        try {
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
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

     private boolean isLikelyExtensionBasic(String number) {
        if (Strings.isBlank(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;
        if (!effectiveNumber.matches("[\\d#*]+")) return false;
        int minExtLength = CdrProcessingConfig.DEFAULT_MIN_EXT_LENGTH;
        int maxExtLength = CdrProcessingConfig.DEFAULT_MAX_EXT_LENGTH;
        int numLength = effectiveNumber.length();
        return (numLength >= minExtLength && numLength <= maxExtLength);
    }

    private boolean isConferenceCallIndicator(String number) {
        if (number == null || number.length() <= CONFERENCE_PREFIX.length()) {
            return false;
        }
        String prefix = number.substring(0, CONFERENCE_PREFIX.length());
        String rest = number.substring(CONFERENCE_PREFIX.length());
        return CONFERENCE_PREFIX.equalsIgnoreCase(prefix) && rest.matches("\\d+");
    }

    private int calculateRingDuration(LocalDateTime start, LocalDateTime connect, LocalDateTime end) {
        int ringDuration = 0;
        if (connect != null && start != null && connect.isAfter(start)) {
            ringDuration = (int) java.time.Duration.between(start, connect).getSeconds();
        } else if (end != null && start != null && end.isAfter(start)) {
            // If no connect time, ring duration is until disconnect
            ringDuration = (int) java.time.Duration.between(start, end).getSeconds();
        }
        return Math.max(0, ringDuration); // Ensure non-negative
    }

    private CallTransferCause mapCiscoTransferReason(int ciscoReason) {
        // Map known Cisco redirectReason codes to CallTransferCause enum
        // Ref: https://www.cisco.com/c/en/us/td/docs/voice_ip_comm/cucm/service/11_0_1/cdrdef/CUCM_BK_C389827D_00_cucm-cdr-administration-guide-1101/CUCM_BK_C389827D_00_cucm-cdr-administration-guide-1101_chapter_01000.html#CUCM_RF_C069E43A_00
        return switch (ciscoReason) {
            case 1 -> CallTransferCause.NO_ANSWER; // No Answer
            case 2 -> CallTransferCause.BUSY; // Busy
            // case 3 -> CallTransferCause.UNKNOWN; // No Bandwidth (No direct mapping)
            // case 4 -> CallTransferCause.UNKNOWN; // Destination Out of Order (No direct mapping)
            case 10 -> CallTransferCause.NORMAL; // Call Forward All (Treat as normal transfer?)
            case 12 -> CallTransferCause.BUSY; // Call Forward Busy
            case 13 -> CallTransferCause.NO_ANSWER; // Call Forward No Answer
            // case 14 -> CallTransferCause.UNKNOWN; // Call Forward No Coverage (No direct mapping)
            // case 15 -> CallTransferCause.UNKNOWN; // Call Forward Unregistered (No direct mapping)
            case 16 -> CallTransferCause.NORMAL; // Call Pickup (Treat as normal transfer?)
            default -> CallTransferCause.AUTO; // Default to Auto for others
        };
    }

    private StandardizedCallEventDto.CallTypeHint determineCallTypeHint(boolean isIncoming, boolean isConference, String caller, String called) {
        if (isConference) return StandardizedCallEventDto.CallTypeHint.CONFERENCE;
        if (isIncoming) return StandardizedCallEventDto.CallTypeHint.UNKNOWN; // Enrichment determines incoming type
        if (isLikelyExtensionBasic(caller) && isLikelyExtensionBasic(called)) return StandardizedCallEventDto.CallTypeHint.INTERNAL;
        // Further hints (LOCAL, NATIONAL etc.) are better determined during enrichment
        return StandardizedCallEventDto.CallTypeHint.UNKNOWN;
    }
}