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
public class CiscoCm60CdrProcessor implements CdrTypeProcessor {

    public static final Long PLANT_TYPE_IDENTIFIER = 26L; // CM_6_0
    private static final String INTERNAL_CDR_RECORD_TYPE_HEADER_KEY = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b";

    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;
    private int minExpectedFieldsForValidCdr = 0;

    private final EmployeeLookupService employeeLookupService;
    private final CallTypeDeterminationService callTypeDeterminationService;


    @PostConstruct
    public void initDefaultHeaderMappings() {
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
        this.conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();
    }

    @Override
    public boolean isHeaderLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        List<String> fields = CdrUtil.parseCsvLine(line, CDR_SEPARATOR);
        if (!fields.isEmpty()) {
            return INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(fields.get(0));
        }
        return false;
    }

    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            String actualHeaderFromFile = headers.get(i).toLowerCase();
            currentHeaderPositions.put(actualHeaderFromFile, i);
            for (Map.Entry<String, String> entry : conceptualToActualHeaderMap.entrySet()) {
                if (entry.getValue().equals(actualHeaderFromFile)) {
                    if (i > maxIndex) {
                        maxIndex = i;
                    }
                    break;
                }
            }
        }
        this.minExpectedFieldsForValidCdr = maxIndex + 1;
        currentHeaderPositions.put("_max_mapped_header_index_", maxIndex);
        log.debug("Parsed Cisco CM 6.0 headers. Mapped positions: {}. Min expected fields: {}", currentHeaderPositions, minExpectedFieldsForValidCdr);
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

            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    if (!rawValue.isEmpty() && !rawValue.equals("0") && !rawValue.equals("-1")) {
                        return CdrUtil.decimalToIp(Long.parseLong(rawValue));
                    }
                    return rawValue;
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse IP address from decimal: {} for header {}", rawValue, actualHeaderName);
                    return rawValue;
                }
            }
            return rawValue;
        }
        log.trace("Field for conceptual name '{}' (actual header: '{}') not found or out of bounds.", conceptualFieldName, actualHeaderName);
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
        log.debug("Evaluating Cisco CM 6.0 CDR line: {}", cdrLine);
        if (currentHeaderPositions.isEmpty() || !currentHeaderPositions.containsKey("_max_mapped_header_index_")) {
            log.error("Cisco CM 6.0 Headers not parsed. Cannot process line: {}", cdrLine);
            CdrData errorData = new CdrData(); errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true); errorData.setQuarantineReason("Header not parsed prior to CDR line processing.");
            errorData.setQuarantineStep(QuarantineErrorType.MISSING_HEADER.name()); return errorData;
        }

        List<String> fields = CdrUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        String firstField = fields.isEmpty() ? "" : fields.get(0);
        if (INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(firstField)) {
            log.debug("Skipping header line found mid-stream.");
            return null;
        }
        if ("INTEGER".equalsIgnoreCase(firstField)) {
            log.debug("Skipping 'INTEGER' type definition line.");
            return null;
        }

        if (fields.size() < this.minExpectedFieldsForValidCdr) {
            log.warn("Cisco CM 6.0 CDR line has insufficient fields ({}). Expected at least {}. Line: {}", fields.size(), this.minExpectedFieldsForValidCdr, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Insufficient fields. Found " + fields.size() + ", expected " + this.minExpectedFieldsForValidCdr);
            cdrData.setQuarantineStep(QuarantineErrorType.PARSER_ERROR.name());
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
            if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() > 0) {
                cdrData.setDurationSeconds(0);
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

        log.debug("Initial parsed Cisco CM 6.0 fields: {}", cdrData);

        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());

        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
            log.debug("FinalCalledPartyNumber was empty, used OriginalCalledPartyNumber: {}", cdrData.getFinalCalledPartyNumber());
        }
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
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

            if (!isConferenceByLastRedirectDn) {
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();

                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());
                cdrData.setLastRedirectDn(tempDialNumber);
                cdrData.setLastRedirectDnPartition(tempDialPartition);
                log.debug("Conference: Swapped finalCalledParty with lastRedirectDn. New finalCalled: {}, New lastRedirectDn: {}",
                        cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn());

                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    String originalLastRedirectDnValue = cdrData.getOriginalLastRedirectDn();
                    if (originalLastRedirectDnValue != null && originalLastRedirectDnValue.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(originalLastRedirectDnValue);
                        cdrData.setConferenceIdentifierUsed(originalLastRedirectDnValue);
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                        cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    }
                }
            }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrUtil.swapPartyInfo(cdrData);
            }
        } else {
            if (isConferenceByLastRedirectDn) {
                if (setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END)) {
                    cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    invertTrunksForConference = false;
                }
            }
        }

        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(commLocation);

        if (isConferenceByFinalCalled) {
            boolean isConferenceEffectivelyIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                    (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceEffectivelyIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                 if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) { // Only swap trunks if parties were also swapped
                    CdrUtil.swapTrunks(cdrData);
                }
            }
        } else {
            boolean isCallingPartyEffectivelyExternal = !isPartitionPresent(cdrData.getCallingPartyNumberPartition()) ||
                    !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
            boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                    employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                    employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);

            if (isCallingPartyEffectivelyExternal && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                cdrData.setCallDirection(CallDirection.INCOMING);
                CdrUtil.swapPartyInfo(cdrData);
                log.debug("Non-conference incoming detected. Swapped calling/called numbers. Calling: {}, Called: {}",
                        cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
            }
        }

        boolean isCallingPartyInternal = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternal = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isCallingPartyInternal && isFinalCalledPartyInternal) {
            cdrData.setInternalCall(true);
        } else {
            cdrData.setInternalCall(false);
        }

        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                // For incoming, after swap, callingPartyNumber is our extension. If lastRedirectDn is different, it's a redirect.
                numberChangedByRedirect = true;
            }
        }

        if (numberChangedByRedirect) {
            if (cdrData.getTransferCause() == TransferCause.NONE) { // Only set if not already set by conference logic
                Integer lastRedirectReason = cdrData.getLastRedirectRedirectReason();
                if (lastRedirectReason != null && lastRedirectReason > 0 && lastRedirectReason <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    TransferCause autoTransferCause = (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) ?
                            TransferCause.PRE_CONFERENCE_NOW : TransferCause.AUTO;
                    cdrData.setTransferCause(autoTransferCause);
                }
            }
        }
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING) {
                if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                    if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                        cdrData.setInternalCall(false);
                    }
                }
            } else { // INCOMING
                // After swap, finalCalledPartyNumber is the external number, callingPartyNumber is our extension.
                // If our extension (callingPartyNumber) is different from finalMobileCalledPartyNumber, it's a redirect to mobile.
                if (!Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    // The call was to our extension (callingPartyNumber), but redirected to finalMobileCalledPartyNumber.
                    // The "final destination" of this leg from the PBX's perspective is the mobile number.
                    // So, the external party (finalCalledPartyNumber) called our extension (callingPartyNumber) which forwarded to mobile.
                    // The effective "callee" for this CDR segment becomes the mobile number.
                    cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber()); // Our "extension" is now the mobile
                    cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName()); // Partition of the mobile
                    cdrData.setInternalCall(false); // It's no longer an internal call to our original extension
                }
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        if (isConferenceByFinalCalled &&
                cdrData.getCallingPartyNumber() != null &&
                Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.info("Conference call where caller and callee are the same after all processing. Discarding CDR: {}", cdrLine);
            return null;
        }

        log.debug("Final evaluated Cisco CM 6.0 CDR data: {}", cdrData);
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
        return cdrData.getTransferCause() == cause;
    }

    @Override
    public Long getPlantTypeIdentifier() {
        return PLANT_TYPE_IDENTIFIER;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || conferenceIdentifierActual == null || conferenceIdentifierActual.isEmpty()) {
            return false;
        }
        String prefixToMatch = conferenceIdentifierActual.toUpperCase();
        String numUpper = number.toUpperCase();

        if (numUpper.startsWith(prefixToMatch)) {
            String rest = numUpper.substring(prefixToMatch.length());
            return !rest.isEmpty() && rest.matches("\\d+");
        }
        return false;
    }
}