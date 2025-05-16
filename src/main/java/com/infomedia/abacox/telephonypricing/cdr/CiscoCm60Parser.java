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

    private final CdrProcessingConfig configService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;

    // Mappings from potential CSV header names (lowercase) to standardized internal field names (canonical, case-sensitive)
    private static final Map<String, String> HEADER_MAPPING = Map.ofEntries(
            Map.entry("cdrrecordtype", "cdrRecordType"),
            Map.entry("globalcallid_callmanagerid", "globalCallID_callManagerId"),
            Map.entry("globalcallid_callid", "globalCallID_callId"),
            Map.entry("origlegcallidentifier", "origLegCallIdentifier"),
            Map.entry("datetimeorigination", "dateTimeOrigination"),
            Map.entry("origdevicename", "origDeviceName"),
            Map.entry("orignodename", "origNodeName"),
            Map.entry("origipaddr", "origIpAddr"),
            Map.entry("callingpartynumber", "callingPartyNumber"),
            Map.entry("callingpartyunicodecodingsystem", "callingPartyUnicodeLoginUserID"),
            Map.entry("callingpartyunicodeloginuserid", "callingPartyUnicodeLoginUserID"),
            Map.entry("callingpartyipaddr", "callingPartyIpAddr"),
            Map.entry("originalcalledpartynumber", "originalCalledPartyNumber"),
            Map.entry("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartynumber", "finalCalledPartyNumber"),
            Map.entry("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition"),
            Map.entry("finalcalledpartyunicodeloginuserid", "finalCalledPartyUnicodeLoginUserID"),
            Map.entry("destlegidentifier", "destLegIdentifier"),
            Map.entry("datetimeconnect", "dateTimeConnect"),
            Map.entry("datetimedisconnect", "dateTimeDisconnect"),
            Map.entry("lastredirectdn", "lastRedirectDn"),
            Map.entry("lastredirectdnpartition", "lastRedirectDnPartition"),
            Map.entry("lastredirectredirectreason", "lastRedirectRedirectReason"),
            Map.entry("destdevicename", "destDeviceName"),
            Map.entry("destnodename", "destNodeName"),
            Map.entry("destipaddr", "destIpAddr"),
            Map.entry("duration", "duration"),
            Map.entry("origcalledpartyredirectonbehalfof", "origCalledPartyRedirectOnBehalfOf"),
            Map.entry("finalcalledpartyredirectonbehalfof", "finalCalledPartyRedirectOnBehalfOf"),
            Map.entry("destcallterminationonbehalfof", "destCallTerminationOnBehalfOf"),
            Map.entry("authcodedescription", "authCodeDescription"),
            Map.entry("authorizationlevel", "authorizationLevel"),
            Map.entry("clientmattercode", "clientMatterCode"),
            Map.entry("origconversationid", "origConversationId"),
            Map.entry("destconversationid", "destConversationId"),
            Map.entry("joinonbehalfof", "joinOnBehalfOf"),
            Map.entry("comment", "comment"),
            Map.entry("origmobileidentity", "origMobileIdentity"),
            Map.entry("destmobileidentity", "destMobileIdentity"),
            Map.entry("origdevicemobilityprecedence", "origDeviceMobilityPrecedence"),
            Map.entry("destdevicemobilityprecedence", "destDeviceMobilityPrecedence"),
            Map.entry("origmobilecalltype", "origMobileCallType"),
            Map.entry("destmobilecalltype", "destMobileCallType"),
            Map.entry("origcalledpartyipaddr", "origCalledPartyIpAddr"),
            Map.entry("finalcalledpartyipaddr", "finalCalledPartyIpAddr"),
            Map.entry("origvideocap_codec", "origVideoCap_Codec"),
            Map.entry("origvideocap_bandwidth", "origVideoCap_Bandwidth"),
            Map.entry("origvideocap_resolution", "origVideoCap_Resolution"),
            Map.entry("origvideotransportaddress_ip", "origVideoTransportAddress_IP"),
            Map.entry("origvideotransportaddress_port", "origVideoTransportAddress_Port"),
            Map.entry("origvideochannel_payloadtype", "origVideoChannel_PayloadType"),
            Map.entry("origvideocap_maxframespersecond", "origVideoCap_MaxFramesPerSecond"),
            Map.entry("destvideocap_codec", "destVideoCap_Codec"),
            Map.entry("destvideocap_bandwidth", "destVideoCap_Bandwidth"),
            Map.entry("destvideocap_resolution", "destVideoCap_Resolution"),
            Map.entry("destvideotransportaddress_ip", "destVideoTransportAddress_IP"),
            Map.entry("destvideotransportaddress_port", "destVideoTransportAddress_Port"),
            Map.entry("destvideochannel_payloadtype", "destVideoChannel_PayloadType"),
            Map.entry("destvideocap_maxframespersecond", "destVideoCap_MaxFramesPerSecond"),
            Map.entry("outpulsedcallingpartynumber", "outpulsedCallingPartyNumber"),
            Map.entry("outpulsedcalledpartynumber", "outpulsedCalledPartyNumber"),
            Map.entry("huntpilotdn", "huntPilotDN"),
            Map.entry("huntpilotpartition", "huntPilotPartition"),
            Map.entry("pkid", "pKID"),
            Map.entry("origcalledpartyredirectreason", "origCalledPartyRedirectReason"),
            Map.entry("finalcalledpartyredirectreason", "finalCalledPartyRedirectReason"),
            Map.entry("origprecedencelevel", "origPrecedenceLevel"),
            Map.entry("destprecedencelevel", "destPrecedenceLevel"),
            Map.entry("releasemethod", "releaseMethod"),
            Map.entry("transmitcodec", "transmitCodec"),
            Map.entry("receivecodec", "receiveCodec"),
            Map.entry("transmitpackets", "transmitPackets"),
            Map.entry("transmitbytes", "transmitBytes"),
            Map.entry("receivepackets", "receivePackets"),
            Map.entry("receivebytes", "receiveBytes"),
            Map.entry("origmediacapsignalingprotocol", "origMediaCapSignalingProtocol"),
            Map.entry("destmediacapsignalingprotocol", "destMediaCapSignalingProtocol"),
            Map.entry("origvideochannel_role_channel2", "origVideoChannel_Role_Channel2"),
            Map.entry("destvideochannel_role_channel2", "destVideoChannel_Role_Channel2"),
            Map.entry("incomingprotocolid", "incomingProtocolID"),
            Map.entry("outgoingprotocolid", "outgoingProtocolID"),
            Map.entry("currentroutingreason", "currentRoutingReason"),
            Map.entry("origroutingreason", "origRoutingReason"),
            Map.entry("lastredirectingroutingreason", "lastRedirectingRoutingReason"),
            Map.entry("callsecuresstatus", "callSecuresStatus"), // Typo in original? "callSecuredStatus"
            Map.entry("origdevicemobility", "origDeviceMobility"),
            Map.entry("destdevicemobility", "destDeviceMobility"),
            Map.entry("origdeviceipv4addr", "origDeviceIPv4Addr"),
            Map.entry("destdeviceipv4addr", "destDeviceIPv4Addr"),
            Map.entry("origdeviceipv6addr", "origDeviceIPv6Addr"),
            Map.entry("destdeviceipv6addr", "destDeviceIPv6Addr"),
            Map.entry("origdeviceport", "origDevicePort"),
            Map.entry("destdeviceport", "destDevicePort"),
            Map.entry("origdevicemacaddr", "origDeviceMacAddr"),
            Map.entry("destdevicemacaddr", "destDeviceMacAddr"),
            Map.entry("origcallsetupcause", "origCallSetupCause"),
            Map.entry("destcallsetupcause", "destCallSetupCause"),
            Map.entry("origdtmfmethod", "origDTMFMethod"),
            Map.entry("destdtmfmethod", "destDTMFMethod"),
            Map.entry("callidentifier", "callIdentifier"),
            Map.entry("origspan", "origSpan"),
            Map.entry("destspan", "destSpan"),
            Map.entry("origvideotransportaddress_ipv6", "origVideoTransportAddress_IPv6"),
            Map.entry("destvideotransportaddress_ipv6", "destVideoTransportAddress_IPv6"),
            Map.entry("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber"),
            Map.entry("finalmobilecalledpartynumberpartition", "finalMobileCalledPartyNumberPartition"),
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
            log.debug("Skipping empty, header, or comment line: {}", line);
            return Optional.empty();
        }
        String trimmedUpperLine = line.trim().toUpperCase();
        if (trimmedUpperLine.startsWith("INTEGER,") || trimmedUpperLine.startsWith("VARCHAR(")) {
            log.debug("Skipping data type definition line {}: {}", metadata.getSourceDescription(), line.substring(0, Math.min(line.length(), 100)) + "...");
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

        String[] fields = line.split(DELIMITER, -1); // -1 to keep trailing empty fields
        for (int i = 0; i < fields.length; i++) {
            fields[i] = cleanCsvField(fields[i]);
        }

        int expectedFields = headerMap.values().stream().mapToInt(Integer::intValue).max().orElse(MIN_EXPECTED_FIELDS -1) + 1;
        if (fields.length < expectedFields && fields.length < MIN_EXPECTED_FIELDS) {
            log.warn("Line has {} fields, expected at least {} (from header) or {} (static min). Line: {}", fields.length, expectedFields, MIN_EXPECTED_FIELDS, line);
            return Optional.empty();
        }


        try {
            // --- Raw field extraction ---
            String globalCallId_callId = getField(fields, headerMap, "globalCallID_callId");
            if (Strings.isBlank(globalCallId_callId)) {
                log.warn("Mandatory field 'globalCallID_callId' is blank or missing in CDR line. Skipping. Line: {}", line);
                return Optional.empty();
            }

            String dateTimeOriginationStr = getField(fields, headerMap, "dateTimeOrigination");
            LocalDateTime dateTimeOrigination = parseTimestamp(dateTimeOriginationStr);
            if (dateTimeOrigination == null) {
                log.warn("Mandatory field 'dateTimeOrigination' is blank, zero, or invalid ('{}') in CDR line. Skipping. Line: {}", dateTimeOriginationStr, line);
                return Optional.empty();
            }

            LocalDateTime dateTimeConnect = parseTimestamp(getField(fields, headerMap, "dateTimeConnect"));
            LocalDateTime dateTimeDisconnect = parseTimestamp(getField(fields, headerMap, "dateTimeDisconnect"));

            StringRef callingPartyNumber = new StringRef(getField(fields, headerMap, "callingPartyNumber"));
            StringRef callingPartyNumberPartition = new StringRef(getField(fields, headerMap, "callingPartyNumberPartition", true));
            StringRef finalCalledPartyNumber = new StringRef(getField(fields, headerMap, "finalCalledPartyNumber"));
            StringRef finalCalledPartyNumberPartition = new StringRef(getField(fields, headerMap, "finalCalledPartyNumberPartition", true));
            String originalCalledPartyNumber = getField(fields, headerMap, "originalCalledPartyNumber");
            String originalCalledPartyNumberPartition = getField(fields, headerMap, "originalCalledPartyNumberPartition", true);
            StringRef lastRedirectDn = new StringRef(getField(fields, headerMap, "lastRedirectDn"));
            StringRef lastRedirectDnPartition = new StringRef(getField(fields, headerMap, "lastRedirectDnPartition", true));

            String authCodeDescription = getField(fields, headerMap, "authCodeDescription");
            StringRef origDeviceName = new StringRef(getField(fields, headerMap, "origDeviceName"));
            StringRef destDeviceName = new StringRef(getField(fields, headerMap, "destDeviceName"));
            int lastRedirectRedirectReason = parseInteger(getField(fields, headerMap, "lastRedirectRedirectReason"));
            Integer joinOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "joinOnBehalfOf"));
            Integer destCallTerminationOnBehalfOf = parseOptionalInteger(getField(fields, headerMap, "destCallTerminationOnBehalfOf"));
            Integer destConversationId = parseOptionalInteger(getField(fields, headerMap, "destConversationId"));
            String finalMobileCalledPartyNumber = getField(fields, headerMap, "finalMobileCalledPartyNumber");


            // --- Corrected PHP Logic Emulation for duration and ring time ---
            int cdrFieldDuration = parseInteger(getField(fields, headerMap, "duration"));
            int calculatedDuration = cdrFieldDuration;
            int calculatedRingDuration = 0;

            if (dateTimeConnect != null) {
                if (dateTimeOrigination != null && dateTimeConnect.isAfter(dateTimeOrigination)) {
                    calculatedRingDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeConnect).getSeconds();
                } else if (dateTimeOrigination != null && dateTimeConnect.equals(dateTimeOrigination)) {
                    calculatedRingDuration = 0;
                }
            } else {
                if (dateTimeDisconnect != null && dateTimeOrigination != null && dateTimeDisconnect.isAfter(dateTimeOrigination)) {
                    calculatedRingDuration = (int) java.time.Duration.between(dateTimeOrigination, dateTimeDisconnect).getSeconds();
                }
                calculatedDuration = 0;
            }
            calculatedRingDuration = Math.max(0, calculatedRingDuration);
            // --- End of Corrected Duration/Ring Logic ---


            // --- Continue with other PHP Logic Emulation (CM_FormatoCDR steps) ---
            String originalLastRedirectDnForCC = lastRedirectDn.value;

            if (Strings.isBlank(finalCalledPartyNumber.value)) {
                finalCalledPartyNumber.value = originalCalledPartyNumber;
                finalCalledPartyNumberPartition.value = originalCalledPartyNumberPartition;
            } else if (!finalCalledPartyNumber.value.equals(originalCalledPartyNumber) && Strings.isNotBlank(originalCalledPartyNumber)) {
                if (!isConferenceCallIndicator(finalCalledPartyNumber.value)) { // PHP: !$esconf_redir (esconf_redir was based on finalCalledPartyNumber)
                    lastRedirectDnPartition.value = originalCalledPartyNumberPartition;
                    lastRedirectDn.value = originalCalledPartyNumber;
                }
            }

            boolean isConferenceLeg = isConferenceCallIndicator(finalCalledPartyNumber.value);
            CallTransferCause transferCause = CallTransferCause.NONE;
            boolean invertTrunksForConference = true;

            if (isConferenceLeg) {
                transferCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ? CallTransferCause.CONFERENCE_NOW : CallTransferCause.CONFERENCE;
            } else {
                if (isConferenceCallIndicator(lastRedirectDn.value)) {
                    transferCause = CallTransferCause.CONFERENCE_END;
                    invertTrunksForConference = false;
                }
            }

            if (isConferenceLeg) {
                log.debug("CDR {} is a conference leg. Initial transferCause: {}", globalCallId_callId, transferCause);
                if (!isConferenceCallIndicator(lastRedirectDn.value)) { // PHP: !$esconf_redir (esconf_redir was based on lastRedirectDn)
                    log.debug("Swapping finalCalledParty with lastRedirectDn for conference leg {}", globalCallId_callId);
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
                        log.debug("CONFERENCE_NOW: lastRedirectDn set to {}", lastRedirectDn.value);
                    }
                }
                if (transferCause != CallTransferCause.CONFERENCE_NOW) { // PHP: $cdr_motivo_union != "7"
                    log.debug("Swapping callingParty with finalCalledParty for conference leg {} (not CONFERENCE_NOW)", globalCallId_callId);
                    swapStrings(finalCalledPartyNumber, callingPartyNumber);
                    swapStrings(finalCalledPartyNumberPartition, callingPartyNumberPartition);
                }
            }

            boolean isIncoming = false;
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(metadata.getCommunicationLocationId());

            // General incoming detection logic (can be overridden by conference-specific incoming logic)
            boolean isCallingPartyAnExtension = Strings.isNotBlank(callingPartyNumberPartition.value) &&
                                                cdrEnrichmentHelper.isLikelyExtension(callingPartyNumber.value, extConfig);
            if (!isConferenceLeg) { // PHP: if ($info_arr['incoming'] == 0 && $info_arr['partorigen'] != '' && $info_arr['partdestino'] != '' && $info_arr['interna'] == 0)
                if (!isCallingPartyAnExtension) {
                    boolean isFinalCalledPartyAnExtension = Strings.isNotBlank(finalCalledPartyNumberPartition.value) &&
                                                            cdrEnrichmentHelper.isLikelyExtension(finalCalledPartyNumber.value, extConfig);
                    boolean isLastRedirectDnAnExtension = Strings.isNotBlank(lastRedirectDnPartition.value) &&
                                                          cdrEnrichmentHelper.isLikelyExtension(lastRedirectDn.value, extConfig);

                    if (isFinalCalledPartyAnExtension || isLastRedirectDnAnExtension) {
                        isIncoming = true;
                        swapStrings(finalCalledPartyNumber, callingPartyNumber);
                        // Partitions are NOT swapped here in PHP logic for this specific incoming detection.
                        log.debug("Non-conference leg {} determined as INCOMING. callingParty: {}, finalCalled: {}", globalCallId_callId, callingPartyNumber.value, finalCalledPartyNumber.value);
                    }
                }
            } else { // Conference leg specific incoming detection
                if (Strings.isBlank(finalCalledPartyNumberPartition.value) &&
                    (Strings.isBlank(finalCalledPartyNumber.value) || !cdrEnrichmentHelper.isLikelyExtension(finalCalledPartyNumber.value, extConfig))) {
                    isIncoming = true;
                    log.debug("Conference leg {} determined as INCOMING based on final party.", globalCallId_callId);
                }
                if (!isIncoming && invertTrunksForConference) { // If conference and determined outgoing, and trunks should be inverted
                    log.debug("Inverting trunks for outgoing conference leg {}", globalCallId_callId);
                    swapStrings(destDeviceName, origDeviceName);
                }
            }


            if (Strings.isNotBlank(finalMobileCalledPartyNumber) && !finalMobileCalledPartyNumber.equals(finalCalledPartyNumber.value)) {
                log.debug("Mobile redirection for {}. Original final called: {}, Mobile final called: {}", globalCallId_callId, finalCalledPartyNumber.value, finalMobileCalledPartyNumber);
                finalCalledPartyNumber.value = finalMobileCalledPartyNumber;
                // If no specific transfer cause was set, mark as AUTO
                if (transferCause == CallTransferCause.NONE) {
                    transferCause = CallTransferCause.AUTO;
                }
            }
            
            // This logic must come AFTER mobile redirection might have changed finalCalledPartyNumber
            if (Strings.isNotBlank(lastRedirectDn.value) &&
                (lastRedirectDn.value.equals(callingPartyNumber.value) || lastRedirectDn.value.equals(finalCalledPartyNumber.value)) &&
                transferCause != CallTransferCause.CONFERENCE &&
                transferCause != CallTransferCause.CONFERENCE_NOW &&
                transferCause != CallTransferCause.CONFERENCE_END &&
                transferCause != CallTransferCause.PRE_CONFERENCE_NOW ) {
                log.debug("Clearing redirecting party '{}' for {} as it matches caller or called, and not a primary conference event.", lastRedirectDn.value, globalCallId_callId);
                lastRedirectDn.value = "";
                if (transferCause != CallTransferCause.AUTO) { // Don't override AUTO if it was set by mobile redirection
                     transferCause = CallTransferCause.NONE;
                }
            }

            // Set transfer cause if still NONE and lastRedirectDn is present
            if (transferCause == CallTransferCause.NONE && Strings.isNotBlank(lastRedirectDn.value)) {
                if (lastRedirectRedirectReason > 0 && lastRedirectRedirectReason <= 16) {
                    transferCause = CallTransferCause.NORMAL;
                } else if (joinOnBehalfOf != null && joinOnBehalfOf == 7 && destCallTerminationOnBehalfOf != null && destCallTerminationOnBehalfOf == 7) {
                     // This condition from PHP's `_cm_CausaTransfer` for PRE_CONFERENCE_NOW
                    transferCause = CallTransferCause.PRE_CONFERENCE_NOW;
                } else if (lastRedirectRedirectReason != 0) { // Any other non-zero reason
                    transferCause = CallTransferCause.AUTO;
                }
            }

            if (isConferenceLeg && callingPartyNumber.value.equals(finalCalledPartyNumber.value)) {
                log.warn("Conference call leg {} has identical effective caller and called parties after processing ({}). Skipping.", globalCallId_callId, callingPartyNumber.value);
                return Optional.empty();
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
            builder.durationSeconds(calculatedDuration);
            builder.ringDurationSeconds(calculatedRingDuration);
            builder.isIncoming(isIncoming);
            builder.isConference(isConferenceLeg || transferCause == CallTransferCause.CONFERENCE_NOW || transferCause == CallTransferCause.PRE_CONFERENCE_NOW);
            builder.callTypeHint( (isConferenceLeg || transferCause == CallTransferCause.CONFERENCE_NOW || transferCause == CallTransferCause.PRE_CONFERENCE_NOW) ? StandardizedCallEventDto.CallTypeHint.CONFERENCE : StandardizedCallEventDto.CallTypeHint.UNKNOWN);
            builder.sourceTrunkIdentifier(origDeviceName.value);
            builder.destinationTrunkIdentifier(destDeviceName.value);
            builder.redirectingPartyNumber(lastRedirectDn.value);
            builder.redirectReason(transferCause.getValue());
            builder.disconnectCause(destCallTerminationOnBehalfOf);
            builder.communicationLocationId(metadata.getCommunicationLocationId());
            builder.fileInfoId(metadata.getFileInfoId());
            builder.sourceDescription(metadata.getSourceDescription());

            StandardizedCallEventDto dto = builder.build();
            log.debug("Successfully parsed and standardized CDR line into DTO: {}", dto.getGlobalCallId());
            return Optional.of(dto);

        } catch (Exception e) {
            log.error("Failed to parse/standardize CDR line: {} - Error: {}", line, e.getMessage(), e);
            return Optional.empty();
        }
    }

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
            String cleanedCsvHeader = cleanCsvField(headers[i].trim()).toLowerCase();
            if (!cleanedCsvHeader.isEmpty()) {
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
                if (canonicalInternalFieldName.toLowerCase().endsWith("ipaddr") ||
                    canonicalInternalFieldName.toLowerCase().endsWith("_ip")) {
                    if (value.matches("\\d+") && !value.contains(".")) {
                        ipConvertedValue = dec2ip(value);
                    }
                }
                return toUpper ? ipConvertedValue.toUpperCase() : ipConvertedValue;
            }
        }
        if (index == null) {
             //log.debug("Header key '{}' not found in header map.", canonicalInternalFieldName);
        } else if (index >= fields.length) {
            log.warn("Header key '{}' maps to index {}, which is out of bounds for current line with {} fields.", canonicalInternalFieldName, index, fields.length);
        }
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
            log.warn("Error converting epoch seconds {} to LocalDateTime: {}", timestampStr, e.getMessage());
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

    private boolean isConferenceCallIndicator(String number) {
        if (number == null || number.length() <= CONFERENCE_PREFIX.length()) {
            return false;
        }
        String prefix = number.substring(0, CONFERENCE_PREFIX.length());
        String rest = number.substring(CONFERENCE_PREFIX.length());
        return CONFERENCE_PREFIX.equalsIgnoreCase(prefix) && rest.matches("\\d+");
    }

    private void swapStrings(StringRef s1, StringRef s2) {
        String temp = s1.value;
        s1.value = s2.value;
        s2.value = temp;
    }

    private String dec2ip(String decStr) {
        if (decStr == null || decStr.isEmpty()) return "";
        try {
            long dec = Long.parseLong(decStr);
            if (dec == 0) return "0.0.0.0";
            String hex = String.format("%08x", dec & 0xFFFFFFFFL);
            String[] hexParts = new String[4];
            hexParts[0] = hex.substring(0, 2);
            hexParts[1] = hex.substring(2, 4);
            hexParts[2] = hex.substring(4, 6);
            hexParts[3] = hex.substring(6, 8);
            return Integer.parseInt(hexParts[3], 16) + "." +
                   Integer.parseInt(hexParts[2], 16) + "." +
                   Integer.parseInt(hexParts[1], 16) + "." +
                   Integer.parseInt(hexParts[0], 16);
        } catch (NumberFormatException e) {
            log.warn("Could not parse value '{}' as long for dec2ip conversion. Returning original.", decStr);
            return decStr;
        } catch (Exception e) {
            log.warn("Error in dec2ip for input '{}': {}", decStr, e.getMessage());
            return decStr;
        }
    }
}