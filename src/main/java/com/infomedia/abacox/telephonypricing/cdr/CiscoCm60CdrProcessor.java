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
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;

    private final EmployeeLookupService employeeLookupService;
    private final CallTypeDeterminationService callTypeDeterminationService;


    @PostConstruct
    public void initDefaultHeaderMappings() {
        // Conceptual CdrData field -> expected lowercase header name from Cisco CDR
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

        // In a real system, this would be loaded from dynamic config (e.g., PlantType specific settings)
        // For now, using the static one. PHP: $cm_config['cdr_conferencia']
        this.conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();

        log.info("Cisco CM 6.0 CDR Processor initialized. Conference prefix: {}", this.conferenceIdentifierActual);
    }

    /**
     * PHP: CM_ValidarCab
     */
    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
            if (conceptualToActualHeaderMap.containsValue(cleanedHeader) || conceptualToActualHeaderMap.containsKey(cleanedHeader)) {
                 if (i > maxIndex) maxIndex = i;
            }
        }
        currentHeaderPositions.put("_max_mapped_header_index_", maxIndex);
        log.info("Parsed Cisco CM 6.0 header. Field count: {}. Positions mapped: {}. Max mapped index: {}", headers.size(), currentHeaderPositions.size() -1, maxIndex);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName, conceptualFieldName.toLowerCase());
        Integer position = currentHeaderPositions.get(actualHeaderName);

        if (position == null) {
            position = currentHeaderPositions.get(conceptualFieldName.toLowerCase());
        }

        if (position != null && position >= 0 && position < fields.size()) {
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
        if (currentHeaderPositions.isEmpty() || !currentHeaderPositions.containsKey("_max_mapped_header_index_")) {
            log.error("Header not parsed for Cisco CM 6.0. Cannot process CDR line: {}", cdrLine);
            CdrData errorData = new CdrData(); errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true); errorData.setQuarantineReason("Header not parsed");
            errorData.setQuarantineStep("evaluateFormat_PreCheck"); return errorData;
        }

        List<String> fields = CdrParserUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        int maxMappedIndex = currentHeaderPositions.getOrDefault("_max_mapped_header_index_", -1);
        if (fields.size() <= maxMappedIndex && maxMappedIndex != -1) {
            log.warn("CDR line has fewer fields ({}) than expected based on max mapped header index ({}): {}", fields.size(), maxMappedIndex, cdrLine);
            cdrData.setMarkedForQuarantine(true); cdrData.setQuarantineReason("Insufficient fields for mapped headers");
            cdrData.setQuarantineStep("evaluateFormat_FieldCountCheck"); return cdrData;
        }

        String firstField = fields.isEmpty() ? "" : CdrParserUtil.cleanCsvField(fields.get(0));
        if (CDR_RECORD_TYPE_HEADER.equalsIgnoreCase(firstField)) {
            log.warn("Header line encountered again in evaluateFormat: {}", cdrLine);
            return null;
        }
        if ("INTEGER".equalsIgnoreCase(firstField)) {
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

        // Store original final called party info before potential modifications
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());


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

        // --- PHP: Logic from CM_FormatoCDR for handling empty dial_number, conferences, and redirects ---
        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());

        // PHP: if ($info_arr['dial_number'] == "")
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        }
        // PHP: elseif ($info_arr['dial_number'] != $destino_original && $destino_original != '')
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            // PHP: if (!$esconf_redir)
            if (!isConferenceByLastRedirectDn) {
                cdrData.storeOriginalValue("lastRedirectDn", cdrData.getLastRedirectDn()); // Store before overwriting
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean invertTrunksForConference = true; // PHP: $invertir_troncales

        if (isConferenceByFinalCalled) {
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                                              TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);
            cdrData.setConferenceIdentifierUsed(cdrData.getFinalCalledPartyNumber());

            // PHP: if (!$esconf_redir)
            if (!isConferenceByLastRedirectDn) {
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();

                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());
                cdrData.setLastRedirectDn(tempDialNumber); // This will be the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);

                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    String extRedirCc = (String) cdrData.getOriginalValue("lastRedirectDn");
                    if (extRedirCc != null && extRedirCc.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(extRedirCc);
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                        cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir(...) }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrParserUtil.swapPartyInfo(cdrData);
            }
        } else { // Not a conference by finalCalledPartyNumber
            if (isConferenceByLastRedirectDn) {
                setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END);
                cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                invertTrunksForConference = false;
            }
        }

        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits();
        boolean isCallingPartyInternalFormat = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                               employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                   employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isConferenceByFinalCalled) {
            // PHP: $es_entrante = (trim($info_arr['partdestino']) == '' && ($ndial === '' || !ExtensionPosible($ndial)));
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                // PHP: if ($invertir_troncales) { _invertir($info_arr['troncal'], $info_arr['troncal-ini']); }
                // This inversion happens if it's a conference and not determined as incoming.
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) { // Only if parties were swapped
                    CdrParserUtil.swapTrunks(cdrData);
                }
            }
        } else { // Not a conference by finalCalledPartyNumber
            // PHP: if ($interna_ext && $interna_des) { $info_arr['interna'] = 1; }
            if (isCallingPartyInternalFormat && isFinalCalledPartyInternalFormat) {
                cdrData.setInternalCall(true);
            }
            // PHP: elseif (!$interna_ext) { ... if ($interna_des || $interna_redir) { $info_arr['incoming'] = 1; _invertir(...); } }
            else if (!isCallingPartyInternalFormat) {
                boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                        employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);
                if (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat) {
                     cdrData.setCallDirection(CallDirection.INCOMING);
                     CdrParserUtil.swapPartyInfo(cdrData); // Swaps ext and dial_number
                }
            }
        }

        // Transfer handling (PHP: if ($info_arr['ext-redir'] != '' && ...))
        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                // For incoming, callingPartyNumber is our extension after potential swaps
                numberChangedByRedirect = true;
            }
        }

        if (numberChangedByRedirect) {
            if (cdrData.getLastRedirectRedirectReason() != null && cdrData.getLastRedirectRedirectReason() > 0 && cdrData.getLastRedirectRedirectReason() <= 16) {
                setTransferCauseIfUnset(cdrData, TransferCause.NORMAL);
            } else {
                TransferCause autoTransferCause = (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) ?
                                                 TransferCause.PRE_CONFERENCE_NOW : TransferCause.AUTO;
                setTransferCauseIfUnset(cdrData, autoTransferCause);
            }
        }
        // PHP: elseif ($info_arr['ext-movil'] != '' && ...)
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName()); // Use mobile device name as partition
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                // After inversion for incoming, finalCalledPartyNumber is our extension.
                // If this is different from finalMobileCalledPartyNumber, it means our extension forwarded to mobile.
                numberChangedByMobileRedirect = true;
                // PHP: $info_arr['ext'] = $info_arr['ext-movil']; $info_arr['partorigen'] = $info_arr['partdestino'];
                // This means the "destination" of the incoming call (our side) becomes the mobile number.
                // Since our extension is in finalCalledPartyNumber after inversion:
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        // PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].'') { $info_arr = array(); }
        if (isConferenceByFinalCalled && Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference self-call detected, discarding: {}", cdrLine);
            return null; // Return empty to signify discard
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
        // PHP: CM_ValidarConferencia
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