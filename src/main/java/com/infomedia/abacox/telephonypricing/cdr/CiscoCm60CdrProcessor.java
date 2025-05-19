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
    public static final String CDR_RECORD_TYPE_HEADER = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

    private final Map<String, String> headerToConceptualFieldMap = new HashMap<>();
    private final Map<String, String> conceptualFieldToActualHeaderMap = new HashMap<>();
    private Map<String, Integer> currentHeaderPositions = new HashMap<>();

    private final EmployeeLookupService employeeLookupService; // For isPossibleExtension
    private final CallTypeDeterminationService callTypeDeterminationService; // For getExtensionLimits


    @PostConstruct
    public void init() {
        initializeDefaultHeaderMappings();
        log.info("Cisco CM 6.0 CDR Processor initialized.");
    }

    private void initializeDefaultHeaderMappings() {
        // From PHP's CM_TipoPlanta $cm_cdr array
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
            String conceptualField = headerToConceptualFieldMap.get(cleanedHeader);
            if (conceptualField != null) {
                conceptualFieldToActualHeaderMap.put(conceptualField, cleanedHeader);
            } else {
                // Store unmapped headers as well, using their own name as conceptual field
                conceptualFieldToActualHeaderMap.put(cleanedHeader, cleanedHeader);
            }
        }
        log.info("Parsed Cisco CM 6.0 header. Field count: {}. Positions: {}", headers.size(), currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualFieldToActualHeaderMap.get(conceptualFieldName);
        if (actualHeaderName == null) { // Fallback if conceptual name was used directly for unmapped field
            actualHeaderName = conceptualFieldName.toLowerCase();
        }
        Integer position = currentHeaderPositions.get(actualHeaderName);

        if (position != null && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null) return "";

            // PHP: if (strpos($cab, 'ipaddr') !== false || strpos($cab, 'address_ip') !== false)
            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    if (!rawValue.isEmpty() && !rawValue.equals("0")) return CdrParserUtil.decimalToIp(Long.parseLong(rawValue));
                } catch (NumberFormatException e) {
                    log.warn("Could not parse IP from decimal: {} for field {}", rawValue, actualHeaderName);
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

        int maxMappedIndex = currentHeaderPositions.values().stream().filter(Objects::nonNull).max(Integer::compareTo).orElse(-1);
        if (fields.size() <= maxMappedIndex) {
            log.warn("CDR line has fewer fields ({}) than expected based on max mapped index ({}): {}", fields.size(), maxMappedIndex, cdrLine);
            cdrData.setMarkedForQuarantine(true); cdrData.setQuarantineReason("Insufficient fields based on header map");
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
            cdrData.setDurationSeconds(0); // Ensure duration is 0 if call never connected
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime));


        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean isConferenceByRedirect = isConferenceIdentifier(cdrData.getLastRedirectDn());

        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        } else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByRedirect) {
                cdrData.getAdditionalData().put("ext-redir-cc", cdrData.getLastRedirectDn()); // Save original lastRedirectDn
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceRelated = false;
        boolean invertTrunksForConference = true;

        if (isConferenceByFinalCalled) {
            isConferenceRelated = true;
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                                              TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);

            if (!isConferenceByRedirect) {
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
                CdrParserUtil.swapPartyInfo(cdrData);
            }
        } else {
            if (isConferenceByRedirect) {
                isConferenceRelated = true;
                setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END);
                invertTrunksForConference = false;
            }
        }

        // Initial call direction and internal call flag determination
        // This is a simplified version of PHP's logic. More detailed logic is in CallTypeDeterminationService.
        boolean isCallingPartyInternalFormat = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                               isPossibleExtension(cdrData.getCallingPartyNumber());
        boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                   isPossibleExtension(cdrData.getFinalCalledPartyNumber());

        if (isCallingPartyInternalFormat && isFinalCalledPartyInternalFormat) {
            cdrData.setInternalCall(true);
        }

        if (isConferenceRelated) {
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !isPossibleExtension(cdrData.getCallingPartyNumber()));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference) {
                CdrParserUtil.swapTrunks(cdrData);
            }
        } else {
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                    isPossibleExtension(cdrData.getLastRedirectDn());
            if (!isCallingPartyInternalFormat && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                 cdrData.setCallDirection(CallDirection.INCOMING);
                 CdrParserUtil.swapPartyInfo(cdrData);
            }
        }

        // Transfer handling (non-conference initiation)
        if (cdrData.getTransferCause() == TransferCause.NONE &&
            cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            boolean numberChangedByRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
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
                if (cdrData.isInternalCall() && !isPossibleExtension(cdrData.getFinalCalledPartyNumber())) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
                       !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName());
            }

            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        if (isConferenceRelated &&
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

    @Override
    public String getPlantTypeIdentifier() {
        return PLANT_TYPE_ID_PHP;
    }

    private boolean isPossibleExtension(String number) {
        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits();
        if (limits == null) {
            log.warn("ExtensionLimits not initialized in CiscoCm60CdrProcessor.isPossibleExtension. Using default check.");
            return number != null && !number.isEmpty() && number.matches("\\d{3,7}");
        }
        return employeeLookupService.isPossibleExtension(number, limits);
    }

    private boolean isPartitionPresent(String partition) {
        return partition != null && !partition.isEmpty();
    }
}