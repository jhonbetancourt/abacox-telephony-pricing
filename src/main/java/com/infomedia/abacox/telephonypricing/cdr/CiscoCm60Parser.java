// FILE: com/infomedia/abacox/telephonypricing/cdr/CiscoCm60Parser.java
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.apache.logging.log4j.util.Strings; // Using Log4j's StringUtils

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component("ciscoCm60Parser")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60Parser implements CdrParser {

    private final CdrProcessingConfig configService; // Assuming this might be needed for future config values
    private final CdrEnrichmentHelper cdrEnrichmentHelper; // For isLikelyExtension if needed by parser logic

    // Mappings from potential CSV header names (lowercase) to standardized internal field names (canonical, case-sensitive)
    private static final Map<String, String> HEADER_MAPPING = Map.ofEntries(
            Map.entry("cdrrecordtype", "cdrRecordType"),
            Map.entry("globalcallid_callmanagerid", "globalCallID_callManagerId"),
            Map.entry("globalcallid_callid", "globalCallID_callId"),
            Map.entry("origlegcallidentifier", "origLegCallIdentifier"),
            Map.entry("datetimeorigination", "dateTimeOrigination"),
            Map.entry("origdevicename", "origDeviceName"),
            Map.entry("orignodename", "origNodeName"), // Corrected from origNodeId based on CSV
            Map.entry("origipaddr", "origIpAddr"),
            Map.entry("callingpartynumber", "callingPartyNumber"),
            Map.entry("callingpartyunicodecodingsystem", "callingPartyUnicodeLoginUserID"),
            Map.entry("callingpartyunicodeloginuserid", "callingPartyUnicodeLoginUserID"),
            Map.entry("callingpartyipaddr", "callingPartyIpAddr"), // Not in CSV, but in map
            Map.entry("originalcalledpartynumber", "originalCalledPartyNumber"),
            Map.entry("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartynumber", "finalCalledPartyNumber"),
            Map.entry("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartyunicodeloginuserid", "finalCalledPartyUnicodeLoginUserID"),
            Map.entry("destlegidentifier", "destLegIdentifier"), // Corrected from destLegIdentifier
            Map.entry("datetimeconnect", "dateTimeConnect"),
            Map.entry("datetimedisconnect", "dateTimeDisconnect"),
            Map.entry("lastredirectdn", "lastRedirectDn"),
            Map.entry("lastredirectdnpartition", "lastRedirectDnPartition"),
            Map.entry("lastredirectredirectreason", "lastRedirectRedirectReason"),
            Map.entry("destdevicename", "destDeviceName"),
            Map.entry("destnodename", "destNodeName"), // Corrected from destNodeId
            Map.entry("destipaddr", "destIpAddr"),
            Map.entry("duration", "duration"),
            Map.entry("origcalledpartyredirectonbehalfof", "origCalledPartyRedirectOnBehalfOf"),
            Map.entry("finalcalledpartyredirectonbehalfof", "finalCalledPartyRedirectOnBehalfOf"),
            Map.entry("destcallterminationonbehalfof", "destCallTerminationOnBehalfOf"),
            Map.entry("authcodedescription", "authCodeDescription"),
            Map.entry("authorizationlevel", "authorizationLevel"),
            Map.entry("clientmattercode", "clientMatterCode"), // Not in CSV, but in map
            Map.entry("origconversationid", "origConversationId"), // Not in CSV, but in map
            Map.entry("destconversationid", "destConversationId"),
            Map.entry("joinonbehalfof", "joinOnBehalfOf"),
            Map.entry("comment", "comment"),
            Map.entry("origmobileidentity", "origMobileIdentity"), // Not in CSV, but in map
            Map.entry("destmobileidentity", "destMobileIdentity"), // Not in CSV, but in map
            Map.entry("origdevicemobilityprecedence", "origDeviceMobilityPrecedence"), // Not in CSV
            Map.entry("destdevicemobilityprecedence", "destDeviceMobilityPrecedence"), // Not in CSV
            Map.entry("origmobilecalltype", "origMobileCallType"), // Not in CSV
            Map.entry("destmobilecalltype", "destMobileCallType"), // Not in CSV
            Map.entry("origcalledpartyipaddr", "origCalledPartyIpAddr"), // Not in CSV
            Map.entry("finalcalledpartyipaddr", "finalCalledPartyIpAddr"), // Not in CSV
            Map.entry("origvideocap_codec", "origVideoCap_Codec"),
            Map.entry("origvideocap_bandwidth", "origVideoCap_Bandwidth"),
            Map.entry("origvideocap_resolution", "origVideoCap_Resolution"),
            Map.entry("origvideotransportaddress_ip", "origVideoTransportAddress_IP"),
            Map.entry("origvideotransportaddress_port", "origVideoTransportAddress_Port"),
            Map.entry("origvideochannel_payloadtype", "origVideoChannel_PayloadType"), // Not in CSV
            Map.entry("origvideocap_maxframespersecond", "origVideoCap_MaxFramesPerSecond"), // Not in CSV
            Map.entry("destvideocap_codec", "destVideoCap_Codec"),
            Map.entry("destvideocap_bandwidth", "destVideoCap_Bandwidth"),
            Map.entry("destvideocap_resolution", "destVideoCap_Resolution"),
            Map.entry("destvideotransportaddress_ip", "destVideoTransportAddress_IP"),
            Map.entry("destvideotransportaddress_port", "destVideoTransportAddress_Port"),
            Map.entry("destvideochannel_payloadtype", "destVideoChannel_PayloadType"), // Not in CSV
            Map.entry("destvideocap_maxframespersecond", "destVideoCap_MaxFramesPerSecond"), // Not in CSV
            Map.entry("outpulsedcallingpartynumber", "outpulsedCallingPartyNumber"),
            Map.entry("outpulsedcalledpartynumber", "outpulsedCalledPartyNumber"),
            Map.entry("huntpilotdn", "huntPilotDN"),
            Map.entry("huntpilotpartition", "huntPilotPartition"),
            Map.entry("pkid", "pKID"),
            Map.entry("origcalledpartyredirectreason", "origCalledPartyRedirectReason"),
            Map.entry("finalcalledpartyredirectreason", "finalCalledPartyRedirectReason"), // Not in CSV
            Map.entry("origprecedencelevel", "origPrecedenceLevel"),
            Map.entry("destprecedencelevel", "destPrecedenceLevel"),
            Map.entry("releasemethod", "releaseMethod"), // Not in CSV
            Map.entry("transmitcodec", "transmitCodec"), // Not in CSV
            Map.entry("receivecodec", "receiveCodec"), // Not in CSV
            Map.entry("transmitpackets", "transmitPackets"),
            Map.entry("transmitbytes", "transmitBytes"),
            Map.entry("receivepackets", "receivePackets"),
            Map.entry("receivebytes", "receiveBytes"),
            Map.entry("origmediacapsignalingprotocol", "origMediaCapSignalingProtocol"), // Not in CSV
            Map.entry("destmediacapsignalingprotocol", "destMediaCapSignalingProtocol"), // Not in CSV
            Map.entry("origvideochannel_role_channel2", "origVideoChannel_Role_Channel2"), // Adjusted key
            Map.entry("destvideochannel_role_channel2", "destVideoChannel_Role_Channel2"), // Adjusted key
            Map.entry("incomingprotocolid", "incomingProtocolID"),
            Map.entry("outgoingprotocolid", "outgoingProtocolID"),
            Map.entry("currentroutingreason", "currentRoutingReason"),
            Map.entry("origroutingreason", "origRoutingReason"),
            Map.entry("lastredirectingroutingreason", "lastRedirectingRoutingReason"),
            Map.entry("callsecuresstatus", "callSecuresStatus"), // Typo in original? "callSecuredStatus"
            Map.entry("origdevicemobility", "origDeviceMobility"), // Not in CSV
            Map.entry("destdevicemobility", "destDeviceMobility"), // Not in CSV
            Map.entry("origdeviceipv4addr", "origDeviceIPv4Addr"), // Not in CSV
            Map.entry("destdeviceipv4addr", "destDeviceIPv4Addr"), // Not in CSV
            Map.entry("origdeviceipv6addr", "origDeviceIPv6Addr"), // Not in CSV
            Map.entry("destdeviceipv6addr", "destDeviceIPv6Addr"), // Not in CSV
            Map.entry("origdeviceport", "origDevicePort"), // Not in CSV
            Map.entry("destdeviceport", "destDevicePort"), // Not in CSV
            Map.entry("origdevicemacaddr", "origDeviceMacAddr"), // Not in CSV
            Map.entry("destdevicemacaddr", "destDeviceMacAddr"), // Not in CSV
            Map.entry("origcallsetupcause", "origCallSetupCause"), // Not in CSV
            Map.entry("destcallsetupcause", "destCallSetupCause"), // Not in CSV
            Map.entry("origdtmfmethod", "origDTMFMethod"),
            Map.entry("destdtmfmethod", "destDTMFMethod"),
            Map.entry("callidentifier", "callIdentifier"), // Not in CSV
            Map.entry("origspan", "origSpan"),
            Map.entry("destspan", "destSpan"),
            Map.entry("origvideotransportaddress_ipv6", "origVideoTransportAddress_IPv6"), // Not in CSV
            Map.entry("destvideotransportaddress_ipv6", "destVideoTransportAddress_IPv6"), // Not in CSV
            Map.entry("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber"),
            Map.entry("finalmobilecalledpartynumberpartition", "finalMobileCalledPartyNumberPartition"), // Not in CSV
            Map.entry("origmobiledevicename", "origMobileDeviceName")
    );


    private static final String DELIMITER = ",";
    private static final String HEADER_START_TOKEN = "cdrRecordType"; // Case-insensitive match done later
    private static final int MIN_EXPECTED_FIELDS = 50; // A reasonable minimum, adjust if necessary
    private static final String CONFERENCE_PREFIX = "b"; // Case-insensitive check done later

    // Helper class to pass string by reference for swapping
    private static class StringRef { String value; StringRef(String v) { this.value = v; } }


    @Override
    public Optional<StandardizedCallEventDto> parseLine(String line, Map<String, Integer> headerMap, CdrProcessingRequest metadata) {
        if (line == null || line.trim().isEmpty() || isHeaderLine(line) || line.startsWith(";")) {
            log.info("Skipping empty, header, or comment line: {}", line);
            return Optional.empty();
        }
        String trimmedUpperLine = line.trim().toUpperCase();
        if (trimmedUpperLine.startsWith("INTEGER,") || trimmedUpperLine.startsWith("VARCHAR(")) {
            log.info("Skipping data type definition line: {}", line.substring(0, Math.min(line.length(), 100)) + "...");
            return Optional.empty();
        }

        if (headerMap == null || headerMap.isEmpty()) {
            log.info("Header map is missing or empty, cannot parse line accurately: {}", line);
            // Potentially create a failure record here if strict parsing is required even without header
            return Optional.empty();
        }
         if (!line.contains(DELIMITER)) {
            log.info("Line does not contain delimiter '{}': {}", DELIMITER, line);
            return Optional.empty();
        }

        String cdrHash = HashUtil.sha256(line);
        if (cdrHash == null) {
            // This should ideally not happen if HashUtil throws an exception for critical errors
            log.info("CRITICAL: Failed to generate hash for line. Skipping.");
            return Optional.empty();
        }

        String[] fields = line.split(DELIMITER, -1); // -1 to keep trailing empty fields
        for (int i = 0; i < fields.length; i++) {
            fields[i] = cleanCsvField(fields[i]);
        }

        // Determine expected fields based on the maximum index in the headerMap
        // This is more robust than a fixed MIN_EXPECTED_FIELDS if headers can vary slightly
        int expectedFields = headerMap.values().stream().mapToInt(Integer::intValue).max().orElse(MIN_EXPECTED_FIELDS -1) + 1;
        if (fields.length < expectedFields && fields.length < MIN_EXPECTED_FIELDS) { // Also check against static min as a fallback
            log.info("Line has {} fields, expected at least {} (from header) or {} (static min). Line: {}", fields.length, expectedFields, MIN_EXPECTED_FIELDS, line);
            return Optional.empty();
        }


        try {
            // --- Raw field extraction ---
            String globalCallId_callId = getField(fields, headerMap, "globalCallID_callId");
            if (Strings.isBlank(globalCallId_callId)) {
                log.info("Mandatory field 'globalCallID_callId' is blank or missing in CDR line. Skipping. Line: {}", line);
                return Optional.empty();
            }

            String dateTimeOriginationStr = getField(fields, headerMap, "dateTimeOrigination");
            LocalDateTime dateTimeOrigination = parseTimestamp(dateTimeOriginationStr);
            if (dateTimeOrigination == null) {
                log.info("Mandatory field 'dateTimeOrigination' is blank, zero, or invalid ('{}') in CDR line. Skipping. Line: {}", dateTimeOriginationStr, line);
                return Optional.empty();
            }

            LocalDateTime dateTimeConnect = parseTimestamp(getField(fields, headerMap, "dateTimeConnect"));
            LocalDateTime dateTimeDisconnect = parseTimestamp(getField(fields, headerMap, "dateTimeDisconnect"));

            StringRef callingPartyNumber = new StringRef(getField(fields, headerMap, "callingPartyNumber"));
            StringRef callingPartyNumberPartition = new StringRef(getField(fields, headerMap, "callingPartyNumberPartition", true)); // toUpper
            StringRef finalCalledPartyNumber = new StringRef(getField(fields, headerMap, "finalCalledPartyNumber"));
            StringRef finalCalledPartyNumberPartition = new StringRef(getField(fields, headerMap, "finalCalledPartyNumberPartition", true)); // toUpper
            String originalCalledPartyNumber = getField(fields, headerMap, "originalCalledPartyNumber");
            String originalCalledPartyNumberPartition = getField(fields, headerMap, "originalCalledPartyNumberPartition", true); // toUpper
            StringRef lastRedirectDn = new StringRef(getField(fields, headerMap, "lastRedirectDn"));
            StringRef lastRedirectDnPartition = new StringRef(getField(fields, headerMap, "lastRedirectDnPartition", true)); // toUpper

            // int duration = parseInteger(getField(fields, headerMap, "duration")); // This will be calculated
            String authCodeDescription = getField(fields, headerMap, "authCodeDescription");
            StringRef origDeviceName = new StringRef(getField(fields, headerMap, "origDeviceName"));
            StringRef destDeviceName = new StringRef(getField(fields, headerMap, "destDeviceName"));
            int lastRedirectRedirectReason = parseInteger(getField(fields, headerMap, "lastRedirectRedirectReason"));
            Integer joinOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "joinOnBehalfOf"));
            Integer destCallTerminationOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "destCallTerminationOnBehalfOf"));
            Integer destConversationId = parseOptionalInteger(getField(fields, headerMap, "destConversationId")); // Used for conference logic
            String finalMobileCalledPartyNumber = getField(fields, headerMap, "finalMobileCalledPartyNumber");


            // --- Corrected PHP Logic Emulation for duration and ring time ---
            int cdrFieldDuration = parseInteger(getField(fields, headerMap, "duration"));
            int calculatedDuration = cdrFieldDuration; // Start with CDR field value
            int calculatedRingDuration = 0;

            if (dateTimeConnect != null) { // Equivalent to PHP's $cdr_fecha_con > 0
                if (dateTimeOrigination != null && dateTimeConnect.isAfter(dateTimeOrigination)) {
                    calculatedRingDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeConnect).getSeconds();
                } else if (dateTimeOrigination != null && dateTimeConnect.equals(dateTimeOrigination)) {
                    calculatedRingDuration = 0;
                }
                // In this branch (dateTimeConnect is present), calculatedDuration REMAINS cdrFieldDuration.
            } else {
                // dateTimeConnect is null (or was 0 epoch), so call likely never connected
                if (dateTimeDisconnect != null && dateTimeOrigination != null && dateTimeDisconnect.isAfter(dateTimeOrigination)) {
                    calculatedRingDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeDisconnect).getSeconds();
                }
                calculatedDuration = 0; // PHP: $duracion = 0;
            }
            calculatedRingDuration = Math.max(0, calculatedRingDuration);
            // --- End of Corrected Duration/Ring Logic ---


            // --- Continue with other PHP Logic Emulation (CM_FormatoCDR steps) ---
            String originalLastRedirectDnForCC = lastRedirectDn.value; // Store before modification

            if (Strings.isBlank(finalCalledPartyNumber.value)) {
                finalCalledPartyNumber.value = originalCalledPartyNumber;
                finalCalledPartyNumberPartition.value = originalCalledPartyNumberPartition;
            } else if (!finalCalledPartyNumber.value.equals(originalCalledPartyNumber) && Strings.isNotBlank(originalCalledPartyNumber)) {
                if (!isConferenceCallIndicator(finalCalledPartyNumber.value)) {
                    lastRedirectDnPartition.value = originalCalledPartyNumberPartition;
                    lastRedirectDn.value = originalCalledPartyNumber;
                }
            }

            boolean isConference = isConferenceCallIndicator(finalCalledPartyNumber.value);
            CallTransferCause transferCause = CallTransferCause.NONE;
            boolean invertTrunksForConference = true;

            if (isConference) {
                transferCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ? CallTransferCause.CONFERENCE_NOW : CallTransferCause.CONFERENCE;
            } else {
                if (isConferenceCallIndicator(lastRedirectDn.value)) {
                    transferCause = CallTransferCause.CONFERENCE_END;
                    invertTrunksForConference = false;
                }
            }

            if (isConference) {
                if (!isConferenceCallIndicator(lastRedirectDn.value)) {
                    StringRef tempNum = new StringRef(finalCalledPartyNumber.value);
                    StringRef tempPart = new StringRef(finalCalledPartyNumberPartition.value);

                    finalCalledPartyNumber = lastRedirectDn;
                    finalCalledPartyNumberPartition = lastRedirectDnPartition;

                    lastRedirectDn = tempNum;
                    lastRedirectDnPartition = tempPart;

                    if (transferCause == CallTransferCause.CONFERENCE_NOW) {
                         if (originalLastRedirectDnForCC != null && originalLastRedirectDnForCC.toLowerCase().startsWith("c")) {
                            lastRedirectDn.value = originalLastRedirectDnForCC;
                        } else if (destConversationId != null && destConversationId > 0) {
                            lastRedirectDn.value = "i" + destConversationId;
                        }
                    }
                }
                if (transferCause != CallTransferCause.CONFERENCE_NOW) {
                    swapStrings(finalCalledPartyNumber, callingPartyNumber);
                    swapStrings(finalCalledPartyNumberPartition, callingPartyNumberPartition);
                }
            }

            boolean isIncoming = false;
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(metadata.getCommunicationLocationId());

            boolean isCallingPartyAnExtension = Strings.isNotBlank(callingPartyNumberPartition.value) &&
                                                cdrEnrichmentHelper.isLikelyExtension(callingPartyNumber.value, extConfig);

            if (!isCallingPartyAnExtension) {
                boolean isFinalCalledPartyAnExtension = Strings.isNotBlank(finalCalledPartyNumberPartition.value) &&
                                                        cdrEnrichmentHelper.isLikelyExtension(finalCalledPartyNumber.value, extConfig);
                boolean isLastRedirectDnAnExtension = Strings.isNotBlank(lastRedirectDnPartition.value) &&
                                                      cdrEnrichmentHelper.isLikelyExtension(lastRedirectDn.value, extConfig);
                if (isFinalCalledPartyAnExtension || isLastRedirectDnAnExtension) {
                    isIncoming = true;
                    swapStrings(finalCalledPartyNumber, callingPartyNumber);
                    // Partitions are NOT swapped here in PHP logic for this specific incoming detection.
                }
            }

            if (isConference && !isIncoming && invertTrunksForConference) {
                swapStrings(destDeviceName, origDeviceName);
            }

            if (isConference && callingPartyNumber.value.equals(finalCalledPartyNumber.value)) {
                log.info("Conference call leg has identical caller and called parties after processing ({}). Skipping.", callingPartyNumber.value);
                return Optional.empty();
            }

            if (Strings.isNotBlank(finalMobileCalledPartyNumber) && !finalMobileCalledPartyNumber.equals(finalCalledPartyNumber.value)) {
                log.info("Mobile redirection. Original final called: {}, Mobile final called: {}", finalCalledPartyNumber.value, finalMobileCalledPartyNumber);
                finalCalledPartyNumber.value = finalMobileCalledPartyNumber;
                if (transferCause == CallTransferCause.NONE) {
                    transferCause = CallTransferCause.AUTO;
                }
            }
            
            if (Strings.isNotBlank(lastRedirectDn.value) &&
                (lastRedirectDn.value.equals(callingPartyNumber.value) || lastRedirectDn.value.equals(finalCalledPartyNumber.value)) &&
                transferCause != CallTransferCause.CONFERENCE &&
                transferCause != CallTransferCause.CONFERENCE_NOW &&
                transferCause != CallTransferCause.CONFERENCE_END &&
                transferCause != CallTransferCause.PRE_CONFERENCE_NOW ) {
                log.info("Clearing redirecting party '{}' as it matches caller or called, and not a primary conference event.", lastRedirectDn.value);
                lastRedirectDn.value = "";
                if (transferCause != CallTransferCause.AUTO) { 
                     transferCause = CallTransferCause.NONE;
                }
            }

            if (transferCause == CallTransferCause.NONE && Strings.isNotBlank(lastRedirectDn.value)) {
                if (lastRedirectRedirectReason > 0 && lastRedirectRedirectReason <= 16) {
                    transferCause = CallTransferCause.NORMAL;
                } else if (joinOnBehalfOf != null && joinOnBehalfOf == 7 && destCallTerminationOnBehalfOf != null && destCallTerminationOnBehalfOf == 7) {
                    transferCause = CallTransferCause.PRE_CONFERENCE_NOW;
                } else if (lastRedirectRedirectReason != 0) {
                    transferCause = CallTransferCause.AUTO;
                }
            }

            StandardizedCallEventDto.StandardizedCallEventDtoBuilder builder = StandardizedCallEventDto.builder();
            builder.globalCallId(globalCallId_callId);
            builder.originalRawLine(line);
            builder.cdrHash(cdrHash);
            builder.callingPartyNumber(callingPartyNumber.value);
            builder.calledPartyNumber(finalCalledPartyNumber.value);
            builder.authCode(authCodeDescription);
            builder.callStartTime(dateTimeOrigination);
            builder.callConnectTime(dateTimeConnect);
            builder.callEndTime(dateTimeDisconnect);
            builder.durationSeconds(calculatedDuration); // Use corrected duration
            builder.ringDurationSeconds(calculatedRingDuration); // Use corrected ring duration
            builder.isIncoming(isIncoming);
            builder.isConference(isConference || transferCause == CallTransferCause.CONFERENCE_NOW || transferCause == CallTransferCause.PRE_CONFERENCE_NOW);
            builder.callTypeHint(isConference ? StandardizedCallEventDto.CallTypeHint.CONFERENCE : StandardizedCallEventDto.CallTypeHint.UNKNOWN);
            builder.sourceTrunkIdentifier(origDeviceName.value);
            builder.destinationTrunkIdentifier(destDeviceName.value);
            builder.redirectingPartyNumber(lastRedirectDn.value);
            builder.redirectReason(transferCause.getValue());
            builder.disconnectCause(destCallTerminationOnBehalfOf);
            builder.communicationLocationId(metadata.getCommunicationLocationId());
            builder.fileInfoId(metadata.getFileInfoId());
            builder.sourceDescription(metadata.getSourceDescription());

            StandardizedCallEventDto dto = builder.build();
            log.info("Successfully parsed and standardized CDR line into DTO: {}", dto.getGlobalCallId());
            return Optional.of(dto);

        } catch (Exception e) {
            log.info("Failed to parse/standardize CDR line: {} - Error: {}", line, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public boolean isHeaderLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        try {
            // Split by delimiter, then check the first field
            String[] fields = line.split(getDelimiter(), -1); // Use -1 to keep trailing empty fields
            if (fields.length > 0) {
                // Clean the field (remove quotes) before comparing
                String firstFieldCleaned = cleanCsvField(fields[0].trim());
                return HEADER_START_TOKEN.equalsIgnoreCase(firstFieldCleaned);
            }
        } catch (Exception e) {
            // Log if splitting or cleaning fails, but assume it's not a header
            log.info("Error checking if line is header: {}", line, e);
        }
        return false;
    }

    @Override
    public Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> headerMap = new HashMap<>();
        if (headerLine == null || !isHeaderLine(headerLine)) {
            log.info("Provided line is not a valid header line: {}", headerLine);
            return headerMap; // Return empty map
        }
        String[] headers = headerLine.split(DELIMITER, -1); // -1 to keep trailing empty fields
        for (int i = 0; i < headers.length; i++) {
            String cleanedCsvHeader = cleanCsvField(headers[i].trim()).toLowerCase();
            if (!cleanedCsvHeader.isEmpty()) {
                // Use the canonical internal key (value from HEADER_MAPPING, or the cleaned header itself if not mapped)
                // This key will be used by getField for lookup.
                String canonicalInternalKey = HEADER_MAPPING.getOrDefault(cleanedCsvHeader, cleanedCsvHeader);
                headerMap.put(canonicalInternalKey, i);
            }
        }
        log.info("Parsed header map: {}", headerMap);
        return headerMap;
    }


    @Override
    public int getExpectedMinFields() {
        return MIN_EXPECTED_FIELDS;
    }

    @Override
    public String getDelimiter() {
        return DELIMITER;
    }

    private String getField(String[] fields, Map<String, Integer> headerMap, String canonicalInternalFieldName) {
        return getField(fields, headerMap, canonicalInternalFieldName, false);
    }

    private String getField(String[] fields, Map<String, Integer> headerMap, String canonicalInternalFieldName, boolean toUpper) {
        Integer index = headerMap.get(canonicalInternalFieldName);
        if (index != null && index >= 0 && index < fields.length) {
            String value = fields[index];
            if (value != null) {
                String ipConvertedValue = value;
                // Check if the canonical name (which is what's in headerMap keys and used for lookup)
                // indicates an IP address field.
                if (canonicalInternalFieldName.toLowerCase().endsWith("ipaddr") || 
                    canonicalInternalFieldName.toLowerCase().endsWith("_ip")) { // Check for _IP suffix as well
                    // Attempt dec2ip conversion only if it looks like a decimal IP
                    if (value.matches("\\d+") && !value.contains(".")) { // Is a string of digits, no dots
                        ipConvertedValue = dec2ip(value);
                    } else {
                        // Already in dot-decimal or some other format, use as-is
                        ipConvertedValue = value;
                    }
                }
                return toUpper ? ipConvertedValue.toUpperCase() : ipConvertedValue;
            }
        }
        // Log if a mapped header is expected but not found in the fields array (e.g. line too short)
        // or if the canonicalInternalFieldName is not in headerMap (config issue or unexpected CDR format)
        if (index == null) {
            // This log can be noisy if many optional fields are not present.
            // Consider logging at a lower level (DEBUG) or only if the field is considered mandatory.
            // log.info("Header key '{}' not found in header map.", canonicalInternalFieldName);
        } else if (index >= fields.length) {
            log.info("Header key '{}' maps to index {}, which is out of bounds for current line with {} fields.", canonicalInternalFieldName, index, fields.length);
        }
        return ""; // Return empty string for missing or out-of-bounds fields
    }

    private LocalDateTime parseTimestamp(String timestampStr) {
        if (Strings.isBlank(timestampStr) || "0".equals(timestampStr)) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(timestampStr);
            if (epochSeconds == 0) return null; // Explicitly handle 0 epoch as null
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            log.info("Could not parse timestamp: '{}'. Returning null.", timestampStr);
            return null;
        } catch (Exception e) { // Catch other potential errors during conversion
            log.info("Error converting epoch seconds {} to LocalDateTime: {}", timestampStr, e.getMessage());
            return null;
        }
    }

    private int parseInteger(String intStr) {
        if (Strings.isBlank(intStr)) {
           return 0;
        }
        try {
            // Check if it's a valid integer representation before parsing
            if (!intStr.matches("-?\\d+")) { // Allows negative integers as well
                 log.info("Non-numeric value encountered where integer expected: '{}'. Returning 0.", intStr);
                 return 0;
            }
            return Integer.parseInt(intStr);
        } catch (NumberFormatException e) {
            log.info("Could not parse integer: '{}'. Returning 0.", intStr);
            return 0;
        }
    }

    private Integer parseOptionalInteger(String intStr) {
        if (Strings.isBlank(intStr) || "0".equals(intStr)) { // Cisco often uses "0" for undefined optional integers
           return null;
        }
        try {
             // Check if it's a valid integer representation
             if (!intStr.matches("-?\\d+")) {
                 log.info("Non-numeric value encountered where optional integer expected: '{}'. Returning null.", intStr);
                 return null;
            }
            return Integer.parseInt(intStr);
        } catch (NumberFormatException e) {
            log.info("Could not parse optional integer: '{}'. Returning null.", intStr);
            return null;
        }
    }

    /**
     * Cleans a field from a CSV line, primarily by removing surrounding double quotes
     * and un-escaping double quotes within the field.
     * @param field The raw field string.
     * @return The cleaned field string.
     */
    private String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim(); // Trim whitespace first
        // Check if the field is quoted
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            // Remove the outer quotes
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            // Un-escape doubled double quotes to a single double quote
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

    private boolean isConferenceCallIndicator(String number) {
        if (number == null || number.length() <= CONFERENCE_PREFIX.length()) {
            return false;
        }
        String prefix = number.substring(0, CONFERENCE_PREFIX.length());
        String rest = number.substring(CONFERENCE_PREFIX.length());
        // Ensure the rest is purely numeric
        return CONFERENCE_PREFIX.equalsIgnoreCase(prefix) && rest.matches("\\d+");
    }

    private void swapStrings(StringRef s1, StringRef s2) {
        String temp = s1.value;
        s1.value = s2.value;
        s2.value = temp;
    }

    /**
     * Converts a decimal IP address string (potentially from CDR) to dot-decimal format.
     * Mimics PHP's dec2ip.
     * @param decStr The decimal IP address as a string.
     * @return Dot-decimal IP string, or the original string if conversion fails or input is invalid.
     */
    private String dec2ip(String decStr) {
        if (decStr == null || decStr.isEmpty()) return "";
        try {
            long dec = Long.parseLong(decStr);
            if (dec == 0) return "0.0.0.0"; // Or "" if that's preferred for zero

            // Ensure it's treated as an unsigned 32-bit integer for IP conversion
            // Java's Long.toHexString handles negative longs differently than PHP's dechex for large unsigned ints
            String hex = String.format("%08x", dec & 0xFFFFFFFFL);


            // The PHP logic implies reversing the byte order after hex conversion.
            // Example: if hex is "AABBCCDD" (AA=MSB, DD=LSB from dechex perspective of a single large number)
            // PHP's array_reverse(str_split($hex, 2)) would be ["DD", "CC", "BB", "AA"]
            // Then hexdec is applied to each, resulting in IP: dec(DD).dec(CC).dec(BB).dec(AA)
            // This means the LSB of the original 32-bit number becomes the first octet of the IP.
            String[] hexParts = new String[4];
            hexParts[0] = hex.substring(0, 2); // MSB of the 32-bit hex
            hexParts[1] = hex.substring(2, 4);
            hexParts[2] = hex.substring(4, 6);
            hexParts[3] = hex.substring(6, 8); // LSB of the 32-bit hex

            // Construct IP with LSB first, as per PHP logic's reversal
            return Integer.parseInt(hexParts[3], 16) + "." +
                   Integer.parseInt(hexParts[2], 16) + "." +
                   Integer.parseInt(hexParts[1], 16) + "." +
                   Integer.parseInt(hexParts[0], 16);

        } catch (NumberFormatException e) {
            log.info("Could not parse value '{}' as long for dec2ip conversion. Returning original.", decStr);
            return decStr; // Return original if not a parseable long
        } catch (Exception e) { // Catch other potential errors like StringIndexOutOfBounds
            log.info("Error in dec2ip for input '{}': {}", decStr, e.getMessage(), e);
            return decStr; // Return original on any other error
        }
    }
}