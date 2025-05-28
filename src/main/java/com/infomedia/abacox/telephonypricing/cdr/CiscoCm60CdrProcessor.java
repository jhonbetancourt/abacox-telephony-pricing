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
public class CiscoCm60CdrProcessor implements CdrTypeProcessor {

    public static final Long PLANT_TYPE_IDENTIFIER = 26L; // CM_6_0
    private static final String INTERNAL_CDR_RECORD_TYPE_HEADER_KEY = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $_IDENTIFICADOR_CONFERENCIA

    // Conceptual field names (from PHP's $cm_cdr keys) to actual expected header names (lowercase)
    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    // Current actual header names (lowercase) to their column index
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;

    private final EmployeeLookupService employeeLookupService;
    private final CallTypeDeterminationService callTypeDeterminationService;


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
        conceptualToActualHeaderMap.put("destMobileDeviceName", "destMobileDeviceName".toLowerCase()); // New in CM6+
        conceptualToActualHeaderMap.put("finalMobileCalledPartyNumber", "finalMobileCalledPartyNumber".toLowerCase()); // New in CM6+
        conceptualToActualHeaderMap.put("lastRedirectRedirectReason", "lastRedirectRedirectReason".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeOrigination", "dateTimeOrigination".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeConnect", "dateTimeConnect".toLowerCase());
        conceptualToActualHeaderMap.put("dateTimeDisconnect", "dateTimeDisconnect".toLowerCase());
        conceptualToActualHeaderMap.put("origDeviceName", "origDeviceName".toLowerCase()); // PHP: troncal_ini
        conceptualToActualHeaderMap.put("destDeviceName", "destDeviceName".toLowerCase()); // PHP: troncal_fin
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
        // Set the actual conference identifier prefix (case-insensitive for comparison later)
        this.conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();
    }

    @Override
    public boolean isHeaderLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String cleanedFirstField = "";
        // Use CdrUtil.parseCsvLine to handle quoted fields correctly
        List<String> fields = CdrUtil.parseCsvLine(line, CDR_SEPARATOR);
        if (!fields.isEmpty()) {
            // CdrUtil.parseCsvLine already cleans and unquotes
            cleanedFirstField = fields.get(0).toLowerCase();
        }
        return INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(cleanedFirstField);
    }

    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        // Use CdrUtil.parseCsvLine to handle quoted fields correctly
        List<String> headers = CdrUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            // CdrUtil.parseCsvLine already cleans and unquotes
            String cleanedHeader = headers.get(i).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
            // Check if this cleaned header is one of the values in our conceptualToActualHeaderMap
            if (conceptualToActualHeaderMap.containsValue(cleanedHeader)) {
                 if (i > maxIndex) maxIndex = i;
            }
        }
        currentHeaderPositions.put("_max_mapped_header_index_", maxIndex); // Store the highest index we care about
        log.debug("Parsed CDR headers for Cisco CM 6.0. Mapped positions: {}", currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName, conceptualFieldName.toLowerCase());
        Integer position = currentHeaderPositions.get(actualHeaderName);

        if (position != null && position >= 0 && position < fields.size()) {
            String rawValue = fields.get(position); // Already cleaned by parseCsvLine
            if (rawValue == null) return "";

            // Specific transformations based on conceptual field name (mimicking PHP's CM_FormatoCDR logic)
            if (conceptualFieldName.toLowerCase().contains("ipaddr") || conceptualFieldName.toLowerCase().contains("address_ip")) {
                try {
                    if (!rawValue.isEmpty() && !rawValue.equals("0") && !rawValue.equals("-1")) { // -1 can appear for some IP fields
                        return CdrUtil.decimalToIp(Long.parseLong(rawValue));
                    }
                    return rawValue; // Return "0" or "-1" as is
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse IP address from decimal: {} for header {} (conceptual {})", rawValue, actualHeaderName, conceptualFieldName);
                    return rawValue; // Return original if parsing fails
                }
            }
            // Date/time fields are handled by parseEpochToLocalDateTime
            return rawValue;
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
        if (valueStr == null || valueStr.isEmpty()) return 0; // PHP often defaults to 0 for empty numeric fields
        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse integer: '{}', returning 0.", valueStr);
            return 0;
        }
    }

    private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse long: '{}', returning 0L.", valueStr);
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
            errorData.setQuarantineStep("evaluateFormat_HeaderMissing_CM60"); return errorData;
        }

        List<String> fields = CdrUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        String firstField = fields.isEmpty() ? "" : fields.get(0);
        if (INTERNAL_CDR_RECORD_TYPE_HEADER_KEY.equalsIgnoreCase(firstField)) {
            log.debug("Skipping header line found mid-stream.");
            return null; // Not a data record
        }
        if ("INTEGER".equalsIgnoreCase(firstField)) {
            log.debug("Skipping 'INTEGER' type definition line.");
            return null; // Not a data record
        }

        int maxMappedIndex = currentHeaderPositions.getOrDefault("_max_mapped_header_index_", -1);
        if (maxMappedIndex != -1 && fields.size() <= maxMappedIndex) {
            log.warn("Cisco CM 6.0 CDR line has insufficient fields ({}). Expected at least {} for mapped headers. Line: {}", fields.size(), maxMappedIndex + 1, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Insufficient fields for mapped headers. Found " + fields.size() + ", expected more than " + maxMappedIndex);
            cdrData.setQuarantineStep("evaluateFormat_FieldCount_CM60");
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
            if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() > 0) { // PHP: $duracion = 0;
                cdrData.setDurationSeconds(0);
            }
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime)); // Ensure non-negative
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

        // Store original values before modification
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

        // PHP: if ($info_arr['dial_number'] == "") { ... use originalCalled ... }
        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
            log.debug("FinalCalledPartyNumber was empty, used OriginalCalledPartyNumber: {}", cdrData.getFinalCalledPartyNumber());
        }
        // PHP: elseif ($info_arr['dial_number'] != $destino_original && $destino_original != '') { if (!$esconf_redir) { ... use original for redir ... } }
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByLastRedirectDn) {
                log.debug("FinalCalledPartyNumber differs from Original; LastRedirectDn is not conference. Using Original for LastRedirectDn.");
                // Store the current lastRedirectDn as it might be a conference ID itself (PHP: ext-redir-cc)
                if (isConferenceIdentifier(cdrData.getLastRedirectDn())) {
                     cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                }
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean invertTrunksForConference = true; // PHP: $invertir_troncales = true;

        if (isConferenceByFinalCalled) {
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                    TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);
            cdrData.setConferenceIdentifierUsed(cdrData.getFinalCalledPartyNumber());
            log.debug("Call identified as conference by finalCalledPartyNumber. TransferCause: {}", cdrData.getTransferCause());

            // PHP: if (!$esconf_redir) { ... swap finalCalled with lastRedirect ... }
            if (!isConferenceByLastRedirectDn) {
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();
                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());
                cdrData.setLastRedirectDn(tempDialNumber); // This is now the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);
                log.debug("Conference: Swapped finalCalledParty with lastRedirectDn. New finalCalled: {}, New lastRedirectDn: {}",
                        cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn());

                // PHP: if ($infotrans == IMDEX_TRANSFER_CONFENOW) { ... use originalLastRedirectDn if 'c' or 'i'+destConversationId ... }
                if (confTransferCause == TransferCause.CONFERENCE_NOW) {
                    String originalLastRedirectDnValue = cdrData.getOriginalLastRedirectDn(); // This was stored before the swap above
                    if (originalLastRedirectDnValue != null && originalLastRedirectDnValue.toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(originalLastRedirectDnValue); // Restore if it was a 'cXXXX'
                        cdrData.setConferenceIdentifierUsed(originalLastRedirectDnValue);
                         log.debug("CONFERENCE_NOW: Restored lastRedirectDn to original 'cXXXX' value: {}", originalLastRedirectDnValue);
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        String derivedConfId = "i" + cdrData.getDestConversationId();
                        cdrData.setLastRedirectDn(derivedConfId);
                        cdrData.setConferenceIdentifierUsed(derivedConfId);
                        log.debug("CONFERENCE_NOW: Set lastRedirectDn to derived 'iXXXX' value: {}", derivedConfId);
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir($info_arr['dial_number'], $info_arr['ext']); ... }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrUtil.swapPartyInfo(cdrData); // Swaps ext/dial and their partitions
            }
        } else { // Not conference by final called
            // PHP: elseif ($esconferencia) { ... $esconferencia = CM_ValidarConferencia($cm_config, $info_arr['ext-redir']); if ($esconferencia) { _cm_CausaTransfer($info_arr, IMDEX_TRANSFER_CONFERE_FIN); $invertir_troncales = false; } }
            if (isConferenceByLastRedirectDn) { // Check if lastRedirectDn is a conference ID
                if (setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END)) {
                    cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    invertTrunksForConference = false; // PHP: $invertir_troncales = false;
                    log.debug("Call identified as CONFERENCE_END by lastRedirectDn. No trunk inversion for this case.");
                }
            }
        }

        ExtensionLimits limits = commLocation != null ? callTypeDeterminationService.getExtensionLimits(commLocation) : new ExtensionLimits();

        if (isConferenceByFinalCalled) {
            // PHP: $es_entrante = (trim($info_arr['partdestino']) == '' && ($ndial === '' || !ExtensionPosible($ndial)));
            // After potential swapPartyInfo, partdestino is original partorigen, and dial_number is original ext.
            boolean isConferenceEffectivelyIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                    (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceEffectivelyIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                 // Only swap trunks if party info was also swapped (i.e., joinOnBehalfOf != 7)
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                    CdrUtil.swapTrunks(cdrData);
                }
            }
        } else { // Not conference by final called
            // PHP: elseif (!$interna_ext) { ... $es_entrante = ... if ($es_entrante) { $info_arr['incoming'] = 1; _invertir($info_arr['dial_number'], $info_arr['ext']); } }
            boolean isCallingPartyEffectivelyExternal = !isPartitionPresent(cdrData.getCallingPartyNumberPartition()) ||
                !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
            boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);

            if (isCallingPartyEffectivelyExternal && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                cdrData.setCallDirection(CallDirection.INCOMING);
                // PHP CM_FormatoCDR only swaps ext/dial_number here, NOT partitions or trunks.
                String tempExt = cdrData.getCallingPartyNumber();
                cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
                cdrData.setFinalCalledPartyNumber(tempExt);
                log.debug("Non-conference incoming detected. Swapped only calling/called numbers. Calling: {}, Called: {}",
                        cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
            }
        }

        // Determine if call is internal based on final state of parties
        boolean isCallingPartyInternal = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternal = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isCallingPartyInternal && isFinalCalledPartyInternal) {
            cdrData.setInternalCall(true);
        } else {
            cdrData.setInternalCall(false);
        }

        // Set effective destination number *after* all swaps and internal logic
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());


        // Handle transfers (PHP: if ($info_arr['ext-redir'] != '' && ...))
        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                // For incoming, the "our" party is callingPartyNumber after potential swaps
                numberChangedByRedirect = true;
            }
        }

        if (numberChangedByRedirect) {
            if (cdrData.getTransferCause() == TransferCause.NONE) { // Only if not already set (e.g., by conference logic)
                Integer lastRedirectReason = cdrData.getLastRedirectRedirectReason();
                if (lastRedirectReason != null && lastRedirectReason > 0 && lastRedirectReason <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else if (lastRedirectReason != null && lastRedirectReason == 130) { // Specific Cisco code for Busy
                    cdrData.setTransferCause(TransferCause.BUSY);
                } else {
                    // PHP: $motivotrans = ($info_arr['finaliza-union'] == 7) ? IMDEX_TRANSFER_PRECONFENOW : IMDEX_TRANSFER_AUTO;
                    TransferCause autoTransferCause = (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) ?
                            TransferCause.PRE_CONFERENCE_NOW : TransferCause.AUTO;
                    cdrData.setTransferCause(autoTransferCause);
                }
            }
        }
        // PHP: elseif ($info_arr['ext-movil'] != '' && ...)
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING) {
                if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName()); // Use mobile device name as partition
                    cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());
                    if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                        cdrData.setInternalCall(false);
                    }
                }
            } else { // INCOMING
                if (!Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                    numberChangedByMobileRedirect = true;
                    cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                    cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName());
                    cdrData.setInternalCall(false); // A call to/from an external mobile is not internal
                }
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO); // PHP: $info_arr['info_transfer'] = IMDEX_TRANSFER_AUTO;
            }
        }

        // PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].'') { $info_arr = array(); }
        if (isConferenceByFinalCalled && // Was originally a conference call (before any swaps)
                cdrData.getCallingPartyNumber() != null &&
                Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.info("Conference call where caller and callee are the same after all processing. Discarding CDR: {}", cdrLine);
            return null; // Return empty, indicating this CDR should be skipped
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
        return cdrData.getTransferCause() == cause; // Return true if it was already set to the same cause
    }

    @Override
    public Long getPlantTypeIdentifier() {
        return PLANT_TYPE_IDENTIFIER;
    }

    private boolean isConferenceIdentifier(String number) {
        // PHP: CM_ValidarConferencia
        if (number == null || number.isEmpty() || conferenceIdentifierActual == null || conferenceIdentifierActual.isEmpty()) {
            return false;
        }
        String prefix = conferenceIdentifierActual; // Already uppercase from init
        String numUpper = number.toUpperCase();
        if (numUpper.startsWith(prefix)) {
            String rest = numUpper.substring(prefix.length());
            // PHP: strlen($resto_dial) > 0 && is_numeric($resto_dial)
            return !rest.isEmpty() && rest.matches("\\d+");
        }
        return false;
    }
}