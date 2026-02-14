// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CiscoCm60CdrProcessor.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component("ciscoCm60Processor")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60CdrProcessor implements CdrProcessor {

    // Now a list to support multiple identifiers
    public static final List<Long> PLANT_TYPE_IDENTIFIERS = List.of(26L, 56L);
    private static final String INTERNAL_CDR_RECORD_TYPE_HEADER_KEY = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b";

    // Stateless mapping for key normalization
    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();

    // REMOVED stateful field 'currentHeaderPositions'

    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;

    private static final List<String> IGNORED_AUTH_CODES = List.of("Invalid Authorization Code",
            "Invalid Authorization Level");
    private final CdrConfigService cdrConfigService;

    // --- List of fields that indicate a CMR file, not a CDR file ---
    private static final List<String> CMR_SPECIFIC_FIELDS = List.of(
            "numberPacketsSent",
            "numberOctetsSent",
            "jitter",
            "latency",
            "varVQMetrics");

    @Override
    public CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation, ExtensionLimits extensionLimits,
            Map<String, Integer> headerPositions) {
        // log.trace("Evaluating Cisco CM 6.0 CDR line: {}", cdrLine); // Reduce log
        // noise

        // 1. Validate Header Context
        if (headerPositions == null || headerPositions.isEmpty()
                || !headerPositions.containsKey("_max_mapped_header_index_")) {
            log.debug("Cisco CM 6.0 Headers not provided in context. Cannot process line: {}", cdrLine);
            CdrData errorData = new CdrData();
            errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true);
            errorData.setQuarantineReason("Header map missing in processing context (reprocessing error?)");
            errorData.setQuarantineStep(QuarantineErrorType.MISSING_HEADER.name());
            return errorData;
        }

        List<String> fields = CdrUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        String firstField = fields.isEmpty() ? "" : fields.get(0);
        if (INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(firstField)) {
            return null; // Skip header in data stream
        }
        if ("INTEGER".equalsIgnoreCase(firstField)) {
            return null; // Skip definition line
        }

        // cdrRecordType=1 is a CDR, cdrRecordType=2 is a CMR. We skip CMRs.
        if ("2".equals(firstField)) {
            return null;
        }

        // Determine min expected fields from the provided map
        int minExpectedFieldsForValidCdr = headerPositions.get("_max_mapped_header_index_") + 1;

        if (fields.size() < minExpectedFieldsForValidCdr) {
            log.debug("Cisco CM 6.0 CDR line has insufficient fields ({}). Expected at least {}. Line: {}",
                    fields.size(), minExpectedFieldsForValidCdr, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(
                    "Insufficient fields. Found " + fields.size() + ", expected " + minExpectedFieldsForValidCdr);
            cdrData.setQuarantineStep(QuarantineErrorType.PARSER_ERROR.name());
            return cdrData;
        }

        // --- Start of field extraction using headerPositions ---
        cdrData.setDateTimeOrigination(
                parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeOrigination", headerPositions)));
        LocalDateTime dateTimeConnect = parseEpochToLocalDateTime(
                getFieldValue(fields, "dateTimeConnect", headerPositions));
        LocalDateTime dateTimeDisconnect = parseEpochToLocalDateTime(
                getFieldValue(fields, "dateTimeDisconnect", headerPositions));
        cdrData.setDurationSeconds(parseIntField(getFieldValue(fields, "durationSeconds", headerPositions)));

        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect)
                    .getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect)
                    .getSeconds();
            if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() > 0)
                cdrData.setDurationSeconds(0);
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime));
        if (cdrData.getDurationSeconds() == null)
            cdrData.setDurationSeconds(0);

        // --- Apply _NN_VALIDA logic during extraction ---
        String callingNumber = getFieldValue(fields, "callingPartyNumber", headerPositions);
        String callingPartition = getFieldValue(fields, "callingPartyNumberPartition", headerPositions).toUpperCase();
        if (callingPartition.isEmpty() && extensionLimits != null
                && CdrUtil.isPossibleExtension(callingNumber, extensionLimits)) {
            callingPartition = cdrConfigService.getNoPartitionPlaceholder();
        }
        cdrData.setCallingPartyNumber(callingNumber);
        cdrData.setCallingPartyNumberPartition(callingPartition);

        String finalCalledNumber = getFieldValue(fields, "finalCalledPartyNumber", headerPositions);
        String finalCalledPartition = getFieldValue(fields, "finalCalledPartyNumberPartition", headerPositions)
                .toUpperCase();
        if (finalCalledPartition.isEmpty() && extensionLimits != null
                && CdrUtil.isPossibleExtension(finalCalledNumber, extensionLimits)) {
            finalCalledPartition = cdrConfigService.getNoPartitionPlaceholder();
        }
        cdrData.setFinalCalledPartyNumber(finalCalledNumber);
        cdrData.setFinalCalledPartyNumberPartition(finalCalledPartition);

        String lastRedirectNumber = getFieldValue(fields, "lastRedirectDn", headerPositions);
        String lastRedirectPartition = getFieldValue(fields, "lastRedirectDnPartition", headerPositions).toUpperCase();
        if (lastRedirectPartition.isEmpty() && CdrUtil.isPossibleExtension(lastRedirectNumber, extensionLimits)) {
            lastRedirectPartition = cdrConfigService.getNoPartitionPlaceholder();
        }
        cdrData.setLastRedirectDn(lastRedirectNumber);
        cdrData.setLastRedirectDnPartition(lastRedirectPartition);

        // --- Continue with other fields ---
        cdrData.setOriginalCalledPartyNumber(getFieldValue(fields, "originalCalledPartyNumber", headerPositions));
        cdrData.setOriginalCalledPartyNumberPartition(
                getFieldValue(fields, "originalCalledPartyNumberPartition", headerPositions).toUpperCase());
        cdrData.setDestMobileDeviceName(getFieldValue(fields, "destMobileDeviceName", headerPositions).toUpperCase());
        cdrData.setFinalMobileCalledPartyNumber(getFieldValue(fields, "finalMobileCalledPartyNumber", headerPositions));

        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setOriginalLastRedirectDn(cdrData.getLastRedirectDn());

        cdrData.setAuthCodeDescription(getFieldValue(fields, "authCodeDescription", headerPositions));
        cdrData.setLastRedirectRedirectReason(
                parseIntField(getFieldValue(fields, "lastRedirectRedirectReason", headerPositions)));
        cdrData.setOrigDeviceName(getFieldValue(fields, "origDeviceName", headerPositions));
        cdrData.setDestDeviceName(getFieldValue(fields, "destDeviceName", headerPositions));
        cdrData.setOrigVideoCodec(getFieldValue(fields, "origVideoCodec", headerPositions));
        cdrData.setOrigVideoBandwidth(parseIntField(getFieldValue(fields, "origVideoBandwidth", headerPositions)));
        cdrData.setOrigVideoResolution(getFieldValue(fields, "origVideoResolution", headerPositions));
        cdrData.setDestVideoCodec(getFieldValue(fields, "destVideoCodec", headerPositions));
        cdrData.setDestVideoBandwidth(parseIntField(getFieldValue(fields, "destVideoBandwidth", headerPositions)));
        cdrData.setDestVideoResolution(getFieldValue(fields, "destVideoResolution", headerPositions));
        cdrData.setJoinOnBehalfOf(parseIntField(getFieldValue(fields, "joinOnBehalfOf", headerPositions)));
        cdrData.setDestCallTerminationOnBehalfOf(
                parseIntField(getFieldValue(fields, "destCallTerminationOnBehalfOf", headerPositions)));
        cdrData.setDestConversationId(parseLongField(getFieldValue(fields, "destConversationId", headerPositions)));
        cdrData.setGlobalCallIDCallId(parseLongField(getFieldValue(fields, "globalCallIDCallId", headerPositions)));

        // log.trace("Initial parsed Cisco CM 6.0 fields: {}", cdrData);

        // --- Start of Logic Block (PHP: CM_FormatoCDR after field extraction) ---

        // Handle empty finalCalledPartyNumber
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        }
        // Handle case where finalCalled differs from originalCalled (potential
        // redirect)
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceIdentifier(cdrData.getLastRedirectDn())) {
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean invertTrunksForConference = true;

        if (isConferenceByFinalCalled) {
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7)
                    ? TransferCause.CONFERENCE_NOW
                    : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);
            cdrData.setConferenceIdentifierUsed(cdrData.getFinalCalledPartyNumber());

            // *** LEGACY ALIGNMENT ***
            // Legacy system format:
            // - Participant = Ext (callingPartyNumber) -> Matches FUN_EXTENSION
            // - Bridge = Transfer (lastRedirectDn) -> Matches FUN_TRANSFER (key)
            // - Controller = Dial (finalCalledPartyNumber) -> Matches TELEFONO_DESTINO

            // 1. Store Bridge ID (current finalCalled) in Transfer
            String bridgeId = cdrData.getFinalCalledPartyNumber();
            String controllerDn = cdrData.getLastRedirectDn();
            String controllerPartition = cdrData.getLastRedirectDnPartition();

            cdrData.setLastRedirectDn(bridgeId);
            cdrData.setLastRedirectDnPartition(cdrData.getFinalCalledPartyNumberPartition());

            // 2. Move Controller (original Redirect) to Dial (FinalCalled)
            cdrData.setFinalCalledPartyNumber(controllerDn);
            cdrData.setFinalCalledPartyNumberPartition(controllerPartition);

            // 3. Keep Calling (Extension) as Participant - NO SWAP
            // This ensures employee lookup finds the participant directly.

        } else {
            if (isConferenceIdentifier(cdrData.getLastRedirectDn())) {
                if (setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END)) {
                    cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    invertTrunksForConference = false; // PHP: Do not swap trunks for conference end leg
                }
            }
        }

        // Determine call direction after potential conference swaps
        if (isConferenceByFinalCalled) {
            boolean isConferenceEffectivelyIncoming = (!isPartitionPresent(
                    cdrData.getFinalCalledPartyNumberPartition())) &&
                    (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                            !CdrUtil.isPossibleExtension(cdrData.getCallingPartyNumber(), extensionLimits));
            if (isConferenceEffectivelyIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            }
            // Conditionally swap trunks for outgoing conferences
            else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                    CdrUtil.swapTrunks(cdrData);
                }
            }
        } else {
            // Non-conference incoming detection
            boolean isCallingPartyEffectivelyExternal = !isPartitionPresent(cdrData.getCallingPartyNumberPartition()) ||
                    !CdrUtil.isPossibleExtension(cdrData.getCallingPartyNumber(), extensionLimits);
            boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())
                    &&
                    CdrUtil.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), extensionLimits);
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                    CdrUtil.isPossibleExtension(cdrData.getLastRedirectDn(), extensionLimits);

            if (isCallingPartyEffectivelyExternal
                    && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                cdrData.setCallDirection(CallDirection.INCOMING);
                // PHP: _invertir($info_arr['dial_number'], $info_arr['ext']); (No trunk swap)
                CdrUtil.swapPartyNumbersOnly(cdrData);
                // log.trace("Non-conference incoming detected. Swapped calling/called numbers
                // ONLY.");
            }
        }

        // Determine if internal
        boolean isCallingPartyInternal = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                CdrUtil.isPossibleExtension(cdrData.getCallingPartyNumber(), extensionLimits);
        boolean isFinalCalledPartyInternal = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                CdrUtil.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), extensionLimits);

        if (isCallingPartyInternal && isFinalCalledPartyInternal) {
            cdrData.setInternalCall(true);
        } else {
            cdrData.setInternalCall(false);
        }

        // Final transfer cause assignment
        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING
                    && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING
                    && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            }
        }

        if (numberChangedByRedirect) {
            if (cdrData.getTransferCause() == TransferCause.NONE) {
                Integer lastRedirectReason = cdrData.getLastRedirectRedirectReason();
                if (lastRedirectReason != null && lastRedirectReason > 0 && lastRedirectReason <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    TransferCause autoTransferCause = (cdrData.getDestCallTerminationOnBehalfOf() != null
                            && cdrData.getDestCallTerminationOnBehalfOf() == 7) ? TransferCause.PRE_CONFERENCE_NOW
                                    : TransferCause.AUTO;
                    cdrData.setTransferCause(autoTransferCause);
                }
            }
        } else if (cdrData.getFinalMobileCalledPartyNumber() != null
                && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING) {
                if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                    if (cdrData.isInternalCall()
                            && !CdrUtil.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), extensionLimits)) {
                        cdrData.setInternalCall(false);
                    }
                }
            } else {
                if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                    cdrData.setInternalCall(false);
                }
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        if (isConferenceByFinalCalled &&
                cdrData.getCallingPartyNumber() != null &&
                Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference call where caller and callee are the same after all processing. Discarding CDR: {}",
                    cdrLine);
            return null;
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());

        return cdrData;
    }

    @PostConstruct
    public void initDefaultHeaderMappings() {
        conceptualToActualHeaderMap.put("callingPartyNumberPartition", "callingPartyNumberPartition".toLowerCase());
        conceptualToActualHeaderMap.put("callingPartyNumber", "callingPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("finalCalledPartyNumberPartition",
                "finalCalledPartyNumberPartition".toLowerCase());
        conceptualToActualHeaderMap.put("finalCalledPartyNumber", "finalCalledPartyNumber".toLowerCase());
        conceptualToActualHeaderMap.put("originalCalledPartyNumberPartition",
                "originalCalledPartyNumberPartition".toLowerCase());
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
        if (line == null || line.isEmpty())
            return false;
        List<String> fields = CdrUtil.parseCsvLine(line, CDR_SEPARATOR);
        return !fields.isEmpty() && INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(fields.get(0));
    }

    @Override
    public Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> map = new HashMap<>();
        List<String> headers = CdrUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            String actualHeaderFromFile = headers.get(i).toLowerCase();
            map.put(actualHeaderFromFile, i);
            if (conceptualToActualHeaderMap.containsValue(actualHeaderFromFile)) {
                if (i > maxIndex)
                    maxIndex = i;
            }
        }
        map.put("_max_mapped_header_index_", maxIndex);
        log.debug("Parsed Cisco CM 6.0 headers. Mapped positions count: {}. Min expected fields: {}", map.size(),
                maxIndex + 1);
        return map;
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName,
            Map<String, Integer> headerPositions) {
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName,
                conceptualFieldName.toLowerCase());
        Integer position = headerPositions.get(actualHeaderName);
        if (position == null)
            position = headerPositions.get(conceptualFieldName.toLowerCase());

        if (position != null && position >= 0 && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null)
                return "";
            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    if (!rawValue.isEmpty() && !rawValue.equals("0") && !rawValue.equals("-1")) {
                        return CdrUtil.decimalToIp(Long.parseLong(rawValue));
                    }
                    return rawValue;
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse IP address from decimal: {} for header {}", rawValue, actualHeaderName);
                    return rawValue;
                }
            }
            return rawValue;
        }
        return "";
    }

    private LocalDateTime parseEpochToLocalDateTime(String epochSecondsStr) {
        if (epochSecondsStr == null || epochSecondsStr.isEmpty() || "0".equals(epochSecondsStr))
            return null;
        try {
            long epochSeconds = Long.parseLong(epochSecondsStr);
            return epochSeconds > 0 ? DateTimeUtil.epochSecondsToLocalDateTime(epochSeconds) : null;
        } catch (NumberFormatException e) {
            log.debug("Failed to parse epoch seconds: {}", epochSecondsStr, e);
            return null;
        }
    }

    private Integer parseIntField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty())
            return 0;
        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse integer: {}", valueStr);
            return 0;
        }
    }

    private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty())
            return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse long: {}", valueStr);
            return 0L;
        }
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
    public List<Long> getPlantTypeIdentifiers() {
        return PLANT_TYPE_IDENTIFIERS;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || conferenceIdentifierActual == null
                || conferenceIdentifierActual.isEmpty()) {
            return false;
        }
        String prefix = conferenceIdentifierActual;
        String numUpper = number.toUpperCase();
        if (numUpper.startsWith(prefix)) {
            String rest = numUpper.substring(prefix.length());
            return !rest.isEmpty() && rest.matches("\\d+");
        }
        return false;
    }

    @Override
    public List<String> getIgnoredAuthCodeDescriptions() {
        return IGNORED_AUTH_CODES;
    }

    @Override
    public boolean probe(List<String> initialLines) {
        if (initialLines == null || initialLines.isEmpty()) {
            return false;
        }
        for (String line : initialLines) {
            if (isHeaderLine(line)) {
                List<String> headers = CdrUtil.parseCsvLine(line, CDR_SEPARATOR);

                // Reject if it contains CMR-specific fields
                boolean isCmr = headers.stream()
                        .anyMatch(h -> CMR_SPECIFIC_FIELDS.stream().anyMatch(cmrField -> cmrField.equalsIgnoreCase(h)));
                if (isCmr) {
                    log.warn(
                            "Detected CMR format based on presence of CMR-specific fields. This file will be rejected by this processor.");
                    return false; // This is a CMR file, reject it.
                }
                // If it's a header and not a CMR, it's a valid CDR file for this processor.
                return true;
            }
        }
        // No header line was found in the initial lines, so we cannot validate the
        // format.
        return false;
    }
}