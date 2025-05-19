// File: com/infomedia/abacox/telephonypricing/cdr/CiscoCm60CdrProcessor.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component("ciscoCm60Processor")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60CdrProcessor implements ICdrTypeProcessor {

    public static final String PLANT_TYPE_IDENTIFIER = "26"; // CM_6_0
    public static final String CDR_RECORD_TYPE_HEADER = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b";

    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;

    private final EmployeeLookupService employeeLookupService; // Needed for ExtensionLimits
    private final CallTypeDeterminationService callTypeDeterminationService; // Needed for ExtensionLimits


    @PostConstruct
    public void initDefaultHeaderMappings() {
        // PHP: $cm_cdr array in CM_TipoPlanta
        conceptualToActualHeaderMap.put("callingPartyNumberPartition", "callingPartyNumberPartition".toLowerCase());
        conceptualToActualHeaderMap.put("callingPartyNumber", "callingPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("finalCalledPartyNumberPartition", "finalCalledPartyNumberPartition".toLowerCase());
        conceptualToActualHeaderMap.put("finalCalledPartyNumber", "finalCalledPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("originalCalledPartyNumberPartition", "originalCalledPartyNumberPartition".toLowerCase());
        conceptualToActualHeaderMap.put("originalCalledPartyNumber", "originalCalledPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("lastRedirectDnPartition", "lastRedirectDnPartition".toLowerCase());
        conceptualToActualHeaderMap.put("lastRedirectDn", "lastRedirectDn".toLowerCase());
        conceptualToActualHeaderMap.put("destMobileDeviceName", "destMobileDeviceName".toLowerCase());
        conceptualToActualHeaderMap.put("finalMobileCalledPartyNumber", "finalMobileCalledPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("lastRedirectRedirectReason", "lastRedirectRedirectReason".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeOrigination", "dateTimeOrigination".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeConnect", "dateTimeConnect".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeDisconnect", "dateTimeDisconnect".toLowerCase());
        conceptualToActualHeaderMap.put("origDeviceName", "origDeviceName".toLowerCase());
        conceptualToActualHeaderMap.put("destDeviceName", "destDeviceName".toLowerCase());
        conceptualToActualHeaderMap.put("origVideoCodec", "origVideoCap_Codec".toLowerCase());
        conceptualToActualHeaderMap.put("origVideoBandwidth", "origVideoCap_Bandwidth".toLowerCase());
        conceptualToActualHeaderMap.put("origVideoResolution", "origVideoCap_Resolution".toLowerCase());
        conceptualToActualHeaderMap.put("destVideoCodec", "destVideoCap_Codec".toLowerCase());
        conceptualToActualHeaderMap.put("destVideoBandwidth", "destVideoCap_Bandwidth".toLowerCase());
        conceptualToActualHeaderMap.put("destVideoResolution", "destVideoCap_Resolution".toLowerCase());
        conceptualToActualHeaderMap.put("joinOnBehalfOf", "joinOnBehalfOf".toLowerCase());
        conceptualToActualHeaderMap.put("destCallTerminationOnBehalfOf", "destCallTerminationOnBehalfOf".toLowerCase());
        conceptualToActualHeaderMap.put("destConversationId", "destConversationId".toLowerCase());
        conceptualToActualHeaderMap.put("globalCallIDCallId", "globalCallID_callId".toLowerCase());
        conceptualToActualHeaderMap.put("durationSeconds", "duration".toLowerCase());
        conceptualToActualHeaderMap.put("authCodeDescription", "authCodeDescription".toLowerCase());
        // In a real system, load from CdrConfigService which might load from DB
        // this.conferenceIdentifierActual = cdrConfigService.getConferenceIdentifierPrefix().toUpperCase();
        this.conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();

    }

    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
            // Check if this cleanedHeader is one of the values in our conceptualToActualHeaderMap
            if (conceptualToActualHeaderMap.containsValue(cleanedHeader)) {
                 if (i > maxIndex) maxIndex = i;
            }
        }
        currentHeaderPositions.put("_max_mapped_header_index_", maxIndex);
        log.debug("Parsed CDR headers. Mapped positions: {}", currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName, conceptualFieldName.toLowerCase());
        Integer position = currentHeaderPositions.get(actualHeaderName);
        // Fallback if the conceptual name itself was the actual header (e.g. if not in conceptualToActualHeaderMap)
        if (position == null) {
            position = currentHeaderPositions.get(conceptualFieldName.toLowerCase());
        }

        if (position != null && position >= 0 && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null) return "";
            String cleanedValue = CdrParserUtil.cleanCsvField(rawValue);

            // IP conversion logic
            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    // Cisco uses 0 or -1 for unspecified IPs.
                    if (!cleanedValue.isEmpty() && !cleanedValue.equals("0") && !cleanedValue.equals("-1")) {
                        return CdrParserUtil.decimalToIp(Long.parseLong(cleanedValue));
                    }
                    return cleanedValue; // Return "0" or "-1" as is
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse IP address from decimal: {} for header {}", cleanedValue, actualHeaderName);
                    return cleanedValue; // Return original problematic value
                }
            }
            return cleanedValue;
        }
        log.trace("Field '{}' (actual header: '{}') not found or out of bounds.", conceptualFieldName, actualHeaderName);
        return "";
    }

    private LocalDateTime parseEpochToLocalDateTime(String epochSecondsStr) {
        if (epochSecondsStr == null || epochSecondsStr.isEmpty() || "0".equals(epochSecondsStr)) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(epochSecondsStr);
            return epochSeconds > 0 ? DateTimeUtil.epochSecondsToLocalDateTime(epochSeconds) : null;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse epoch seconds: {}", epochSecondsStr, e);
            return null;
        }
    }

     private Integer parseIntField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse integer: {}", valueStr);
            return 0;
        }
    }

    private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse long: {}", valueStr);
            return 0L;
        }
    }

    @Override
    public CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation) {
        log.debug("Evaluating CDR line: {}", cdrLine);
        if (currentHeaderPositions.isEmpty() || !currentHeaderPositions.containsKey("_max_mapped_header_index_")) {
            log.error("CDR Headers not parsed. Cannot process line: {}", cdrLine);
            CdrData errorData = new CdrData(); errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true); errorData.setQuarantineReason("Header not parsed prior to CDR line processing.");
            errorData.setQuarantineStep("evaluateFormat_HeaderMissing"); return errorData;
        }

        List<String> fields = CdrParserUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        String firstField = fields.isEmpty() ? "" : CdrParserUtil.cleanCsvField(fields.get(0));
        if (CDR_RECORD_TYPE_HEADER.equalsIgnoreCase(firstField)) {
            log.debug("Skipping header line found mid-stream.");
            return null;
        }
        if ("INTEGER".equalsIgnoreCase(firstField)) {
            log.debug("Skipping 'INTEGER' type definition line.");
            return null;
        }

        int maxMappedIndex = currentHeaderPositions.getOrDefault("_max_mapped_header_index_", -1);
        if (maxMappedIndex != -1 && fields.size() <= maxMappedIndex) {
            log.warn("CDR line has insufficient fields ({}). Expected at least {} for mapped headers. Line: {}", fields.size(), maxMappedIndex + 1, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Insufficient fields for mapped headers. Found " + fields.size() + ", expected more than " + maxMappedIndex);
            cdrData.setQuarantineStep("evaluateFormat_FieldCount");
            return cdrData;
        }

        cdrData.setDateTimeOrigination(parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeOrigination")));
        LocalDateTime dateTimeConnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeConnect"));
        LocalDateTime dateTimeDisconnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeDisconnect"));
        cdrData.setDurationSeconds(parseIntField(getFieldValue(fields, "durationSeconds")));

        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect).getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect).getSeconds();
            if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() > 0) { // If duration was parsed as >0 but connect time is null
                 cdrData.setDurationSeconds(0); // Ensure duration is 0 if call never connected
            }
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime));
        if (cdrData.getDurationSeconds() == null) cdrData.setDurationSeconds(0);


        cdrData.setCallingPartyNumber(getFieldValue(fields, "callingPartyNumber"));
        cdrData.setCallingPartyNumberPartition(getFieldValue(fields, "callingPartyNumberPartition").toUpperCase());
        cdrData.setFinalCalledPartyNumber(getFieldValue(fields, "finalCalledPartyNumber"));
        cdrData.setFinalCalledPartyNumberPartition(getFieldValue(fields, "finalCalledPartyNumberPartition").toUpperCase());
        cdrData.setOriginalCalledPartyNumber(getFieldValue(fields, "originalCalledPartyNumber"));
        cdrData.setOriginalCalledPartyNumberPartition(getFieldValue(fields, "originalCalledPartyNumberPartition").toUpperCase());
        cdrData.setLastRedirectDn(getFieldValue(fields, "lastRedirectDn"));
        cdrData.setLastRedirectDnPartition(getFieldValue(fields, "lastRedirectDnPartition").toUpperCase());
        cdrData.setDestMobileDeviceName(getFieldValue(fields, "destMobileDeviceName").toUpperCase());
        cdrData.setFinalMobileCalledPartyNumber(getFieldValue(fields, "finalMobileCalledPartyNumber"));

        // Store initial values before they are potentially modified by conference/redirect logic
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setOriginalLastRedirectDn(cdrData.getLastRedirectDn());


        cdrData.setAuthCodeDescription(getFieldValue(fields, "authCodeDescription"));
        cdrData.setLastRedirectRedirectReason(parseIntField(getFieldValue(fields, "lastRedirectRedirectReason")));
        cdrData.setOrigDeviceName(getFieldValue(fields, "origDeviceName"));
        cdrData.setDestDeviceName(getFieldValue(fields, "destDeviceName"));

        cdrData.setOrigVideoCodec(getFieldValue(fields, "origVideoCodec"));
        cdrData.setOrigVideoBandwidth(parseIntField(getFieldValue(fields, "origVideoBandwidth")));
        cdrData.setOrigVideoResolution(getFieldValue(fields, "origVideoResolution"));
        cdrData.setDestVideoCodec(getFieldValue(fields, "destVideoCodec"));
        cdrData.setDestVideoBandwidth(parseIntField(getFieldValue(fields, "destVideoBandwidth")));
        cdrData.setDestVideoResolution(getFieldValue(fields, "destVideoResolution"));

        cdrData.setJoinOnBehalfOf(parseIntField(getFieldValue(fields, "joinOnBehalfOf")));
        cdrData.setDestCallTerminationOnBehalfOf(parseIntField(getFieldValue(fields, "destCallTerminationOnBehalfOf")));
        cdrData.setDestConversationId(parseLongField(getFieldValue(fields, "destConversationId")));
        cdrData.setGlobalCallIDCallId(parseLongField(getFieldValue(fields, "globalCallIDCallId")));

        log.debug("Initial parsed CDR fields: {}", cdrData);

        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
            log.debug("FinalCalledPartyNumber was empty, used OriginalCalledPartyNumber: {}", cdrData.getFinalCalledPartyNumber());
        } else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByLastRedirectDn) {
                log.debug("FinalCalledPartyNumber differs from Original; LastRedirectDn is not conference. Using Original for LastRedirectDn.");
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean invertTrunksForConference = true;

        if (isConferenceByFinalCalled) {
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                                              TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);
            cdrData.setConferenceIdentifierUsed(cdrData.getFinalCalledPartyNumber());
            log.debug("Call identified as conference by finalCalledPartyNumber. TransferCause: {}", cdrData.getTransferCause());

            if (!isConferenceByLastRedirectDn) { // If lastRedirectDn is not also a conference ID
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();
                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());
                cdrData.setLastRedirectDn(tempDialNumber); // This is now the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);
                log.debug("Conference: Swapped finalCalledParty with lastRedirectDn. New finalCalled: {}, New lastRedirectDn: {}",
                        cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn());

                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    // PHP: if (isset($info_arr['ext-redir-cc']) && strtolower(substr($info_arr['ext-redir-cc'], 0, 1)) == 'c')
                    // $info_arr['ext-redir-cc'] was $info_arr['ext-redir'] before this block, which is now cdrData.getFinalCalledPartyNumber()
                    String originalLastRedirectDnValue = cdrData.getOriginalLastRedirectDn(); // Value of lastRedirectDn before this conference block
                    if (originalLastRedirectDnValue != null && originalLastRedirectDnValue.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(originalLastRedirectDnValue);
                        cdrData.setConferenceIdentifierUsed(originalLastRedirectDnValue);
                        log.debug("CONFERENCE_NOW: LastRedirectDn updated to original 'cxxxx' value: {}", cdrData.getLastRedirectDn());
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                        cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                        log.debug("CONFERENCE_NOW: LastRedirectDn updated to 'ixxxx' from destConversationId: {}", cdrData.getLastRedirectDn());
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7")
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrParserUtil.swapPartyInfo(cdrData); // Swaps callingParty <=> finalCalledParty and their partitions
                log.debug("Conference (not joinOnBehalfOf=7): Swapped calling/called party info.");
            }
        } else { // Not a conference by finalCalledPartyNumber
            if (isConferenceByLastRedirectDn) {
                if (setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END)) {
                    cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    invertTrunksForConference = false; // PHP: $invertir_troncales = false;
                    log.debug("Call identified as CONFERENCE_END by lastRedirectDn.");
                }
            }
        }

        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(commLocation);

        if (isConferenceByFinalCalled) { // If it was a conference call (by finalCalledPartyNumber initially)
            // After potential swaps, callingPartyNumber is our extension, finalCalledPartyNumber is the other party (or conference bridge if not swapped)
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
                log.debug("Conference call determined as INCOMING based on partitions and calling number format.");
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                 // If it's an outgoing conference call and trunks weren't flagged to be kept as is
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) { // And not a "join now" type
                    CdrParserUtil.swapTrunks(cdrData);
                    log.debug("Outgoing conference call (not joinOnBehalfOf=7), trunks swapped.");
                }
            }
        } else { // Not a conference by finalCalledPartyNumber
            // PHP: elseif (!$interna_ext)
            boolean isCallingPartyEffectivelyExternal = !isPartitionPresent(cdrData.getCallingPartyNumberPartition()) ||
                                                        !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);

            if (isCallingPartyEffectivelyExternal) {
                boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                           employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);
                boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                        employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);

                if (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat) {
                     cdrData.setCallDirection(CallDirection.INCOMING);
                     CdrParserUtil.swapPartyInfo(cdrData); // callingParty is now our extension, finalCalled is external
                     log.debug("Non-conference call determined as INCOMING based on party/redirect format, swapped parties.");
                }
            }
        }

        // Determine if it's an internal call (extension to extension)
        // This should happen *after* party swaps for incoming calls are done.
        boolean isCallingPartyInternal = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                         employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternal = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                             employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isCallingPartyInternal && isFinalCalledPartyInternal) {
            cdrData.setInternalCall(true);
            log.debug("Marked as internal call based on both parties having internal format and partitions after all swaps.");
        } else {
            cdrData.setInternalCall(false); // Explicitly set if not meeting internal criteria
        }


        // Transfer logic
        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                // For incoming, after swap, callingParty is our ext. If lastRedirect is different, it's a redirect from another of our exts.
                // PHP logic: ($info_arr['incoming'] == 1 && $info_arr['ext'] != $info_arr['ext-redir'])
                // Our 'ext' is callingPartyNumber, 'ext-redir' is lastRedirectDn
                numberChangedByRedirect = true;
            }
        }

        if (numberChangedByRedirect) {
            if (cdrData.getTransferCause() == TransferCause.NONE) { // Only if not already set by conference logic
                if (cdrData.getLastRedirectRedirectReason() != null && cdrData.getLastRedirectRedirectReason() > 0 && cdrData.getLastRedirectRedirectReason() <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    TransferCause autoTransferCause = (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) ?
                                                     TransferCause.PRE_CONFERENCE_NOW : TransferCause.AUTO;
                    cdrData.setTransferCause(autoTransferCause);
                }
                log.debug("Transfer detected. Cause: {}", cdrData.getTransferCause());
            }
        }
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            String originalNumberForMobileRedirectCheck = "";

            if (cdrData.getCallDirection() == CallDirection.OUTGOING) {
                originalNumberForMobileRedirectCheck = cdrData.getFinalCalledPartyNumber();
                if (!Objects.equals(originalNumberForMobileRedirectCheck, cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                    if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                        cdrData.setInternalCall(false); // No longer internal if redirected to non-extension mobile
                    }
                }
            } else { // INCOMING
                originalNumberForMobileRedirectCheck = cdrData.getCallingPartyNumber(); // Our extension
                // If our extension is being redirected to a mobile number (e.g. call forwarding from our ext to mobile)
                // The finalCalledPartyNumber (external caller) remains the same.
                // The callingPartyNumber (our ext) effectively becomes the mobile number for this leg.
                // This is a complex scenario. PHP's logic for mobile redirect is simpler:
                // It updates ext or dial_number to the mobile number.
                // For incoming, if ext (our ext) != ext-movil, ext becomes ext-movil.
                if (!Objects.equals(originalNumberForMobileRedirectCheck, cdrData.getFinalMobileCalledPartyNumber())) {
                     numberChangedByMobileRedirect = true;
                     // This means our extension (callingPartyNumber) was forwarded to finalMobileCalledPartyNumber
                     // The call record should reflect that the call terminated at the mobile number,
                     // but originated from the external party (finalCalledPartyNumber).
                     // This is effectively an outgoing call from our system to the mobile, initiated by an incoming call.
                     // This might need a more sophisticated "linked call" model or careful handling of party roles.
                     // For now, let's follow PHP: update the party that represents "our" side.
                     cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                     cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName());
                     // This call is now effectively an OUTGOING call from our system to the mobile.
                     // cdrData.setCallDirection(CallDirection.OUTGOING); // This could be debated.
                     cdrData.setInternalCall(false); // Definitely not internal anymore.
                }
            }

            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO); // Or a more specific mobile forward cause
                log.debug("Mobile redirect detected. Final party updated. TransferCause: {}", cdrData.getTransferCause());
            }
        }

        // Final check for conference self-call
        if (isConferenceByFinalCalled && // If it was initially a conference call
            cdrData.getCallingPartyNumber() != null &&
            Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.info("Conference call where caller and callee are the same after all processing. Discarding CDR: {}", cdrLine);
            return null;
        }

        log.debug("Final evaluated CDR data: {}", cdrData);
        return cdrData;
    }

    private boolean isPartitionPresent(String partition) {
        return partition != null && !partition.isEmpty();
    }

    private boolean setTransferCauseIfUnset(CdrData cdrData, TransferCause cause) {
        if (cdrData.getTransferCause() == null || cdrData.getTransferCause() == TransferCause.NONE) {
            cdrData.setTransferCause(cause);
            return true;
        }
        // If already set to the same cause, it's fine. If different, prefer existing.
        return cdrData.getTransferCause() == cause;
    }

    @Override
    public String getPlantTypeIdentifier() {
        return PLANT_TYPE_IDENTIFIER;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || conferenceIdentifierActual == null || conferenceIdentifierActual.isEmpty()) {
            return false;
        }
        String prefix = conferenceIdentifierActual; // e.g., "B"
        String numUpper = number.toUpperCase();
        if (numUpper.startsWith(prefix)) {
            String rest = numUpper.substring(prefix.length());
            // PHP: strlen($resto_dial) > 0 && is_numeric($resto_dial)
            return !rest.isEmpty() && rest.matches("\\d+");
        }
        return false;
    }
}