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

    private static final String PLANT_TYPE_ID_PHP = "26"; // Corresponds to CM_6_0 in PHP's TIPOPLANTA table
    public static final String CDR_RECORD_TYPE_HEADER = "cdrrecordtype"; // PHP: $cm_config['llave']
    private static final String CDR_SEPARATOR = ","; // PHP: $cm_config['cdr_separador']
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

    // Mappings from internal CdrData field conceptual names to potential CSV header names
    private final Map<String, String> headerToConceptualFieldMap = new HashMap<>();
    // Mappings from conceptual field names to actual header names found in the file
    private final Map<String, String> conceptualFieldToActualHeaderMap = new HashMap<>();
    private Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private int expectedMinFields = 0;
    private final EmployeeLookupService employeeLookupService;
    private final CallTypeDeterminationService callTypeDeterminationService;

    @PostConstruct
    public void init() {
        initializeDefaultHeaderMappings();
        log.info("Cisco CM 6.0 CDR Processor initialized.");
    }

    private void initializeDefaultHeaderMappings() {
        // From PHP $cm_cdr array in CM_TipoPlanta
        // Key: CSV Header Name (lowercase), Value: Conceptual Field Name (used internally)
        headerToConceptualFieldMap.put("callingpartynumberpartition", "callingPartyNumberPartition");
        headerToConceptualFieldMap.put("callingpartynumber", "callingPartyNumber");
        headerToConceptualFieldMap.put("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition");
        headerToConceptualFieldMap.put("finalcalledpartynumber", "finalCalledPartyNumber");
        headerToConceptualFieldMap.put("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition");
        headerToConceptualFieldMap.put("originalcalledpartynumber", "originalCalledPartyNumber");
        headerToConceptualFieldMap.put("lastredirectdnpartition", "lastRedirectDnPartition");
        headerToConceptualFieldMap.put("lastredirectdn", "lastRedirectDn");
        headerToConceptualFieldMap.put("destmobiledevicename", "destMobileDeviceName");
        headerToConceptualFieldMap.put("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber");
        headerToConceptualFieldMap.put("lastredirectredirectreason", "lastRedirectRedirectReason");
        headerToConceptualFieldMap.put("datetimeorigination", "dateTimeOrigination");
        headerToConceptualFieldMap.put("datetimeconnect", "dateTimeConnect");
        headerToConceptualFieldMap.put("datetimedisconnect", "dateTimeDisconnect");
        headerToConceptualFieldMap.put("origdevicename", "origDeviceName");
        headerToConceptualFieldMap.put("destdevicename", "destDeviceName");
        headerToConceptualFieldMap.put("origvideocap_codec", "origVideoCodec");
        headerToConceptualFieldMap.put("origvideocap_bandwidth", "origVideoBandwidth");
        headerToConceptualFieldMap.put("origvideocap_resolution", "origVideoResolution");
        headerToConceptualFieldMap.put("destvideocap_codec", "destVideoCodec");
        headerToConceptualFieldMap.put("destvideocap_bandwidth", "destVideoBandwidth");
        headerToConceptualFieldMap.put("destvideocap_resolution", "destVideoResolution");
        headerToConceptualFieldMap.put("joinonbehalfof", "joinOnBehalfOf");
        headerToConceptualFieldMap.put("destcallterminationonbehalfof", "destCallTerminationOnBehalfOf");
        headerToConceptualFieldMap.put("destconversationid", "destConversationId");
        headerToConceptualFieldMap.put("globalcallid_callid", "globalCallIDCallId");
        headerToConceptualFieldMap.put("duration", "durationSeconds");
        headerToConceptualFieldMap.put("authcodedescription", "authCodeDescription");
    }

    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        conceptualFieldToActualHeaderMap.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
            // Map conceptual field name to the actual header name found
            String conceptualField = headerToConceptualFieldMap.get(cleanedHeader);
            if (conceptualField != null) {
                conceptualFieldToActualHeaderMap.put(conceptualField, cleanedHeader);
            } else {
                // If not in default map, use the header itself as conceptual (for unknown/new fields)
                conceptualFieldToActualHeaderMap.put(cleanedHeader, cleanedHeader);
            }
        }
        expectedMinFields = headers.size();
        log.info("Parsed Cisco CM 6.0 header. Field count: {}. Positions: {}", expectedMinFields, currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualFieldToActualHeaderMap.get(conceptualFieldName);
        if (actualHeaderName == null) {
             // Fallback for fields not in the initial map, try direct conceptual name
            actualHeaderName = conceptualFieldName.toLowerCase();
        }

        Integer position = currentHeaderPositions.get(actualHeaderName);
        if (position != null && position < fields.size()) {
            String rawValue = fields.get(position);
            // IP conversion is done by CM_ArregloData in PHP, which is essentially this getFieldValue
            if (conceptualFieldName.toLowerCase().contains("ipaddr") || conceptualFieldName.toLowerCase().contains("address_ip")) {
                try {
                    if (rawValue != null && !rawValue.isEmpty())
                        return CdrParserUtil.decimalToIp(Long.parseLong(rawValue));
                } catch (NumberFormatException e) {
                    log.warn("Could not parse IP from decimal: {} for field {}", rawValue, conceptualFieldName);
                }
            }
            return rawValue;
        }
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
            log.warn("Could not parse integer from: {}", valueStr);
            return 0;
        }
    }
    
    private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.warn("Could not parse long from: {}", valueStr);
            return 0L;
        }
    }


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

        if ("INTEGER".equalsIgnoreCase(fields.get(0))) {
            log.debug("Skipping INTEGER type line: {}", cdrLine);
            return null;
        }
        if (fields.size() < expectedMinFields) {
            log.warn("CDR line has fewer fields ({}) than expected ({}): {}", fields.size(), expectedMinFields, cdrLine);
            cdrData.setMarkedForQuarantine(true); cdrData.setQuarantineReason("Insufficient fields");
            cdrData.setQuarantineStep("evaluateFormat_FieldCountCheck"); return cdrData;
        }

        cdrData.setDateTimeOrigination(parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeOrigination")));
        LocalDateTime dateTimeConnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeConnect"));
        LocalDateTime dateTimeDisconnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeDisconnect"));

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

        cdrData.setDurationSeconds(parseIntField(getFieldValue(fields, "durationSeconds")));
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

        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect).getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect).getSeconds();
            cdrData.setDurationSeconds(0);
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime));

        // --- Start of complex logic from PHP's CM_FormatoCDR ---
        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean isConferenceByRedirect = isConferenceIdentifier(cdrData.getLastRedirectDn());
        String originalLastRedirectDnForConference = cdrData.getLastRedirectDn(); // Save before modification

        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        } else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByRedirect) {
                cdrData.getAdditionalData().put("ext-redir-cc", cdrData.getLastRedirectDn()); // PHP: $info_arr['ext-redir-cc']
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConference = false; // General flag for conference related processing
        boolean invertTrunksForConference = true; // PHP: $invertir_troncales

        if (isConferenceByFinalCalled) {
            isConference = true;
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                                              TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);

            if (!isConferenceByRedirect) { // If lastRedirectDn is not itself a conference ID
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();

                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());

                cdrData.setLastRedirectDn(tempDialNumber); // This is the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);

                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    String extRedirCc = (String) cdrData.getAdditionalData().get("ext-redir-cc");
                    if (extRedirCc != null && extRedirCc.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(extRedirCc);
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                    }
                }
            }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                swapPartyInfo(cdrData); // Invert caller/callee
            }
        } else { // Not a conference initiated by finalCalledPartyNumber
            if (isConferenceByRedirect) { // Call was redirected FROM a conference (conference ended)
                isConference = true;
                setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END);
                invertTrunksForConference = false; // PHP: $invertir_troncales = false;
                // PHP logic for setting lastRedirectDn from ACUMCONFERENCIA_INDCONF is omitted (no historical DB lookup)
            }
        }

        // Initial call direction and internal call flag (refined later by CallTypeDeterminationService)
        // PHP: $info_arr['interna'] = 0; if ($interna_ext && $interna_des) $info_arr['interna'] = 1;
        // This is a very basic check. More robust check in CallTypeDeterminationService.
        boolean isCallingPartyInternalFormat = cdrData.getCallingPartyNumberPartition() != null && !cdrData.getCallingPartyNumberPartition().isEmpty();
        boolean isFinalCalledPartyInternalFormat = cdrData.getFinalCalledPartyNumberPartition() != null && !cdrData.getFinalCalledPartyNumberPartition().isEmpty();

        if (isCallingPartyInternalFormat && isFinalCalledPartyInternalFormat) {
            cdrData.setInternalCall(true);
        }

        if (isConference) {
            boolean isConferenceIncoming = (cdrData.getFinalCalledPartyNumberPartition() == null || cdrData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !isPossibleExtension(cdrData.getCallingPartyNumber())); // Simplified isExtension
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference) {
                swapTrunks(cdrData);
            }
        } else { // Not a conference related to finalCalledPartyNumber or lastRedirectDn being 'bXXX'
            // PHP: elseif (!$interna_ext) { ... $info_arr['incoming'] = 1; _invertir(...) }
            // This means if calling party is not an internal format, assume incoming if destination IS internal format
            // OR if redirected party is internal format.
            boolean isRedirectPartyInternalFormat = cdrData.getLastRedirectDnPartition() != null && !cdrData.getLastRedirectDnPartition().isEmpty();

            if (!isCallingPartyInternalFormat && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                 cdrData.setCallDirection(CallDirection.INCOMING);
                 swapPartyInfo(cdrData); // PHP: _invertir($info_arr['dial_number'], $info_arr['ext']);
                 // PHP does not swap partitions here, but it seems logical if ext/dial are swapped.
                 // For now, stick to PHP: only swap ext/dial. Partitions remain.
            }
        }


        // Transfer handling (non-conference initiation)
        if (cdrData.getTransferCause() == TransferCause.NONE && // Not already a conference type
            cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {

            boolean numberChangedByRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && // After potential swap
                       !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            }

            if (numberChangedByRedirect) {
                if (cdrData.getLastRedirectRedirectReason() != null &&
                    cdrData.getLastRedirectRedirectReason() > 0 &&
                    cdrData.getLastRedirectRedirectReason() <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    if (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) {
                        cdrData.setTransferCause(TransferCause.PRE_CONFERENCE_NOW);
                    } else {
                        cdrData.setTransferCause(TransferCause.AUTO);
                    }
                }
            }
        }

        // Mobile Redirection
        if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), callTypeDeterminationService.getExtensionLimits())) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && // After potential swap
                       !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                // PHP: $info_arr['partorigen'] = $info_arr['partdestino'];
                // This implies if incoming and redirected to mobile, the "caller's partition" becomes the "callee's original partition"
                // This needs careful thought. For now, let's assume the mobile device name is the new partition context.
                cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName());
            }

            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO); // Or a specific mobile redirect cause
            }
        }

        // Discard conference self-calls
        if (isConference && // This was the original isConferenceByFinalCalled
            Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference self-call detected (caller equals callee after conference processing), discarding: {}", cdrLine);
            return null;
        }

        return cdrData;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || CONFERENCE_IDENTIFIER_PREFIX == null || CONFERENCE_IDENTIFIER_PREFIX.isEmpty()) {
            return false;
        }
        String prefix = CONFERENCE_IDENTIFIER_PREFIX.toLowerCase();
        String numLower = number.toLowerCase();
        if (numLower.startsWith(prefix)) {
            String rest = numLower.substring(prefix.length());
            return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    private void setTransferCauseIfUnset(CdrData cdrData, TransferCause cause) {
        if (cdrData.getTransferCause() == null || cdrData.getTransferCause() == TransferCause.NONE) {
            cdrData.setTransferCause(cause);
        }
    }

    private void swapPartyInfo(CdrData cdrData) {
        String tempExt = cdrData.getCallingPartyNumber();
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumber(tempExt);
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);
    }

    private void swapTrunks(CdrData cdrData) {
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
    }

    @Override
    public String getPlantTypeIdentifier() {
        return PLANT_TYPE_ID_PHP; // Using the numeric ID from PHP for consistency if needed elsewhere
    }

    private boolean isPossibleExtension(String number) {
        return employeeLookupService.isPossibleExtension(number, callTypeDeterminationService.getExtensionLimits());
    }
}