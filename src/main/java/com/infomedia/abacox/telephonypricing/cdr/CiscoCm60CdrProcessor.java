// File: com/infomedia/abacox/telephonypricing/cdr/CiscoCm60CdrProcessor.java
package com.infomedia.abacox.telephonypricing.cdr;

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
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // Default, can be overridden by config

    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = CONFERENCE_IDENTIFIER_PREFIX; // Can be updated from config

    private final EmployeeLookupService employeeLookupService; // For isPossibleExtension
    private final CallTypeDeterminationService callTypeDeterminationService; // For getExtensionLimits

    // PHP: $cm_config (loaded by CM_TipoPlanta)
    // This method effectively replaces CM_TipoPlanta's role in setting up mappings
    @PostConstruct
    public void initDefaultHeaderMappings() {
        // Default mappings (conceptual CdrData field -> expected lowercase header name)
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

        // PHP: $cm_config['cdr_conferencia'] = strtoupper($cm_config['cdr_conferencia']);
        // This would be loaded from a dynamic config (e.g., PlantType specific settings)
        // For now, using the static one.
        this.conferenceIdentifierActual = CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();

        log.info("Cisco CM 6.0 CDR Processor initialized with default header mappings. Conference prefix: {}", this.conferenceIdentifierActual);
    }

    /**
     * PHP: CM_ValidarCab
     */
    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
        }
        log.info("Parsed Cisco CM 6.0 header. Field count: {}. Positions mapped: {}", headers.size(), currentHeaderPositions.size());
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName, conceptualFieldName.toLowerCase());
        Integer position = currentHeaderPositions.get(actualHeaderName);

        if (position == null) { // Fallback if the conceptual name itself was the header
            position = currentHeaderPositions.get(conceptualFieldName.toLowerCase());
        }

        if (position != null && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null) return "";
            String cleanedValue = CdrParserUtil.cleanCsvField(rawValue);

            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    if (!cleanedValue.isEmpty() && !cleanedValue.equals("0") && !cleanedValue.equals("-1")) {
                        return CdrParserUtil.decimalToIp(Long.parseLong(cleanedValue));
                    }
                    return cleanedValue;
                } catch (NumberFormatException e) {
                    log.warn("Could not parse IP from decimal: {} for field {}", cleanedValue, actualHeaderName);
                    return cleanedValue;
                }
            }
            return cleanedValue;
        }
        log.trace("Field '{}' (maps to actual header '{}') not found or out of bounds.", conceptualFieldName, actualHeaderName);
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
            log.warn("Invalid epoch seconds format: {} for date/time field.", epochSecondsStr);
            return null;
        }
    }

    private Integer parseIntField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Could not parse integer from: '{}', returning 0", valueStr);
            return 0;
        }
    }

    private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Could not parse long from: '{}', returning 0L", valueStr);
            return 0L;
        }
    }

    /**
     * PHP: CM_FormatoCDR
     */
    @Override
    public CdrData evaluateFormat(String cdrLine) {
        if (currentHeaderPositions.isEmpty()) {
            log.error("Header not parsed for Cisco CM 6.0. Cannot process CDR line: {}", cdrLine);
            CdrData errorData = new CdrData(); errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true); errorData.setQuarantineReason("Header not parsed");
            errorData.setQuarantineStep("evaluateFormat_PreCheck"); return errorData;
        }

        List<String> fields = CdrParserUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        int maxMappedIndex = currentHeaderPositions.values().stream().filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(-1);
        if (fields.size() <= maxMappedIndex && maxMappedIndex != -1) {
            log.warn("CDR line has fewer fields ({}) than expected based on max mapped header index ({}): {}", fields.size(), maxMappedIndex, cdrLine);
            cdrData.setMarkedForQuarantine(true); cdrData.setQuarantineReason("Insufficient fields for mapped headers");
            cdrData.setQuarantineStep("evaluateFormat_FieldCountCheck"); return cdrData;
        }
        if ("INTEGER".equalsIgnoreCase(fields.get(0))) {
            log.debug("Skipping INTEGER type line: {}", cdrLine);
            return null;
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
            cdrData.setDurationSeconds(0);
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime));

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

        // Logic from PHP's CM_FormatoCDR for handling empty dial_number and conference calls
        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());

        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        } else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByLastRedirectDn) {
                cdrData.storeOriginalValue("lastRedirectDn", cdrData.getLastRedirectDn());
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

            if (!isConferenceByLastRedirectDn) {
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();

                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());
                cdrData.setLastRedirectDn(tempDialNumber); // This is the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);

                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    String extRedirCc = (String) cdrData.getOriginalValue("lastRedirectDn");
                    if (extRedirCc != null && extRedirCc.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(extRedirCc);
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                    }
                }
            }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrParserUtil.swapPartyInfo(cdrData);
                // Trunks are swapped here in PHP if it's a conference initiation and not a join
            }
        } else { // Not a call *to* a conference bridge
            if (isConferenceByLastRedirectDn) { // Call *from* or *ending* a conference
                setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END);
                invertTrunksForConference = false;
            }
        }

        // Determine initial 'internal' and 'incoming' flags
        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(); // Get current limits
        boolean isCallingPartyInternalFormat = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                               employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                   employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isCallingPartyInternalFormat && isFinalCalledPartyInternalFormat) {
            cdrData.setInternalCall(true);
        }

        if (isConferenceByFinalCalled) { // Re-check after potential party swap
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                // If it was a conference origination and parties were swapped, trunks should also be swapped.
                // If it was a conference join (joinOnBehalfOf=7), parties were NOT swapped.
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                    CdrParserUtil.swapTrunks(cdrData);
                }
            }
        } else { // Not a call *to* a conference bridge
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                    employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);
            if (!isCallingPartyInternalFormat && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                 cdrData.setCallDirection(CallDirection.INCOMING);
                 CdrParserUtil.swapPartyInfo(cdrData);
                 // Trunks are NOT typically swapped in this specific PHP incoming detection case
            }
        }

        // Final transfer cause setting
        if (cdrData.getTransferCause() == TransferCause.NONE &&
            cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            boolean numberChangedByRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            }

            if (numberChangedByRedirect) {
                if (cdrData.getLastRedirectRedirectReason() != null && cdrData.getLastRedirectRedirectReason() > 0 && cdrData.getLastRedirectRedirectReason() <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    cdrData.setTransferCause((cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) ?
                                             TransferCause.PRE_CONFERENCE_NOW : TransferCause.AUTO);
                }
            }
        }
        // Mobile Redirection
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName());
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        // Discard conference self-calls
        if (isConferenceByFinalCalled && Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference self-call detected, discarding: {}", cdrLine);
            return null;
        }

        return cdrData;
    }

    private boolean isPartitionPresent(String partition) {
        return partition != null && !partition.isEmpty();
    }

    private void setTransferCauseIfUnset(CdrData cdrData, TransferCause cause) {
        if (cdrData.getTransferCause() == null || cdrData.getTransferCause() == TransferCause.NONE) {
            cdrData.setTransferCause(cause);
        }
    }

    @Override
    public String getPlantTypeIdentifier() {
        return PLANT_TYPE_IDENTIFIER;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || conferenceIdentifierActual == null || conferenceIdentifierActual.isEmpty()) {
            return false;
        }
        String prefix = conferenceIdentifierActual; // Already uppercase
        String numUpper = number.toUpperCase();
        if (numUpper.startsWith(prefix)) {
            String rest = numUpper.substring(prefix.length());
            return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
        }
        return false;
    }
}