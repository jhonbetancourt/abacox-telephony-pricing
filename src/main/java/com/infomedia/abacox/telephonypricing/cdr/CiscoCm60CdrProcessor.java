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
    private static final String DEFAULT_CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private String conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX;

    private final EmployeeLookupService employeeLookupService; // For ExtensionPosible
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
        this.conferenceIdentifierActual = DEFAULT_CONFERENCE_IDENTIFIER_PREFIX.toUpperCase();
        // In a full system, this.conferenceIdentifierActual could be loaded from CdrConfigService
        // which in turn could load it from a DB (PHP: $datosini = CDR_cargarBDD(...))
    }

    /**
     * PHP equivalent: CM_ValidarCab
     */
    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        int maxIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
            // Check if this header is one we conceptually map
            if (conceptualToActualHeaderMap.containsValue(cleanedHeader)) {
                 if (i > maxIndex) maxIndex = i;
            }
        }
        // Store the maximum index of a *mapped* header to check if CDR lines have enough fields
        currentHeaderPositions.put("_max_mapped_header_index_", maxIndex);
        log.debug("Parsed CDR headers. Mapped positions: {}", currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        // PHP: CM_ArregloData
        String actualHeaderName = conceptualToActualHeaderMap.getOrDefault(conceptualFieldName, conceptualFieldName.toLowerCase());
        Integer position = currentHeaderPositions.get(actualHeaderName);

        // Fallback if the direct conceptual name was used as a header (e.g. from a custom config)
        if (position == null) {
            position = currentHeaderPositions.get(conceptualFieldName.toLowerCase());
        }

        if (position != null && position >= 0 && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null) return "";

            // PHP: if (strpos($cab, 'ipaddr') !== false || strpos($cab, 'address_ip') !== false) { $arreglo_string[ $cm_config[$llave] ] = dec2ip($valor); }
            // Note: PHP's CM_FormatoCDR does this transformation *after* initial parsing. Here, we do it on retrieval.
            String cleanedValue = CdrParserUtil.cleanCsvField(rawValue);
            if (actualHeaderName.contains("ipaddr") || actualHeaderName.contains("address_ip")) {
                try {
                    if (!cleanedValue.isEmpty() && !cleanedValue.equals("0") && !cleanedValue.equals("-1")) { // Cisco uses 0 or -1 for unknown IPs
                        return CdrParserUtil.decimalToIp(Long.parseLong(cleanedValue));
                    }
                    return cleanedValue; // Return "0" or "-1" as is
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse IP address from decimal: {} for header {}", cleanedValue, actualHeaderName);
                    return cleanedValue; // Return original if parsing fails
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
            // PHP: if ($valor > 0) { $arreglo_string[ $cm_config[$llave] ] = date("Y-m-d H:i:s", $valor); }
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

    /**
     * PHP equivalent: CM_FormatoCDR
     */
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

        // PHP: if (strtolower($primer_campo) == strtolower($_cm_config['llave'])) { return $info_arr; }
        String firstField = fields.isEmpty() ? "" : CdrParserUtil.cleanCsvField(fields.get(0));
        if (CDR_RECORD_TYPE_HEADER.equalsIgnoreCase(firstField)) {
            log.debug("Skipping header line found mid-stream.");
            return null; // Indicates a header line, not a data line
        }
        // PHP: elseif (strtoupper($campos[0]) != 'INTEGER' )
        if ("INTEGER".equalsIgnoreCase(firstField)) {
            log.debug("Skipping 'INTEGER' type definition line.");
            return null;
        }

        // PHP: elseif (count($arreglo_string) < $_cm_config['cdr_campos']) { return $info_arr; }
        int maxMappedIndex = currentHeaderPositions.getOrDefault("_max_mapped_header_index_", -1);
        if (maxMappedIndex != -1 && fields.size() <= maxMappedIndex) {
            log.warn("CDR line has insufficient fields ({}). Expected at least {} for mapped headers. Line: {}", fields.size(), maxMappedIndex + 1, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Insufficient fields for mapped headers. Found " + fields.size() + ", expected more than " + maxMappedIndex);
            cdrData.setQuarantineStep("evaluateFormat_FieldCount");
            return cdrData;
        }
        // PHP: CM_ReportarNoCabs (already handled by initial check)

        cdrData.setDateTimeOrigination(parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeOrigination")));
        LocalDateTime dateTimeConnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeConnect"));
        LocalDateTime dateTimeDisconnect = parseEpochToLocalDateTime(getFieldValue(fields, "dateTimeDisconnect"));
        cdrData.setDurationSeconds(parseIntField(getFieldValue(fields, "durationSeconds")));

        // PHP: if ($cdr_fecha_con > 0) { $tiempo_repique = Fecha_Segundos($cdr_fecha_con) - Fecha_Segundos($cdr_fecha); }
        // PHP: else { $tiempo_repique = Fecha_Segundos($cdr_fecha_fin) - Fecha_Segundos($cdr_fecha); $duracion = 0; }
        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect).getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            // Call never connected or connected for less than 1s (PHP: $duracion = 0)
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect).getSeconds();
            cdrData.setDurationSeconds(0); // Ensure duration is 0 if not connected
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime)); // Ringing time cannot be negative

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

        // Store original final called party info before modifications
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setOriginalLastRedirectDn(cdrData.getLastRedirectDn()); // Store original redirect for later logic

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

        // PHP: if ($info_arr['dial_number'] == "") { ... }
        boolean isConferenceByLastRedirectDn = isConferenceIdentifier(cdrData.getLastRedirectDn());
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
            log.debug("FinalCalledPartyNumber was empty, used OriginalCalledPartyNumber: {}", cdrData.getFinalCalledPartyNumber());
        }
        // PHP: elseif ($info_arr['dial_number'] != $destino_original && $destino_original != '') { if (!$esconf_redir) { ... } }
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isConferenceByLastRedirectDn) {
                log.debug("FinalCalledPartyNumber differs from Original; LastRedirectDn is not conference. Using Original for LastRedirectDn.");
                // PHP: $info_arr['ext-redir-cc'] = $info_arr['ext-redir']; // Preserva original
                // This is implicitly handled by storing originalLastRedirectDn earlier.
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

            if (!isConferenceByLastRedirectDn) {
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
                    // $info_arr['ext-redir-cc'] was $info_arr['ext-redir'] before the swap, which is originalLastRedirectDn
                    if (cdrData.getOriginalLastRedirectDn() != null && cdrData.getOriginalLastRedirectDn().toLowerCase().startsWith("c")) {
                        cdrData.setLastRedirectDn(cdrData.getOriginalLastRedirectDn());
                        cdrData.setConferenceIdentifierUsed(cdrData.getOriginalLastRedirectDn());
                        log.debug("CONFERENCE_NOW: LastRedirectDn updated to original 'cxxxx' value: {}", cdrData.getLastRedirectDn());
                    } else if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                        cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                        log.debug("CONFERENCE_NOW: LastRedirectDn updated to 'ixxxx' from destConversationId: {}", cdrData.getLastRedirectDn());
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir($info_arr['dial_number'], $info_arr['ext']); ... }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrParserUtil.swapPartyInfo(cdrData);
                log.debug("Conference (not joinOnBehalfOf=7): Swapped calling/called party info.");
            }
        } else { // Not a conference by finalCalledPartyNumber
            // PHP: $esconferencia = CM_ValidarConferencia($cm_config, $info_arr['ext-redir']);
            if (isConferenceByLastRedirectDn) {
                // PHP: if (_cm_CausaTransfer($info_arr, IMDEX_TRANSFER_CONFERE_FIN)) { $invertir_troncales = false; }
                if (setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END)) {
                    cdrData.setConferenceIdentifierUsed(cdrData.getLastRedirectDn());
                    invertTrunksForConference = false;
                    log.debug("Call identified as CONFERENCE_END by lastRedirectDn.");
                }
            }
        }

        // Determine call direction based on partitions and extension format
        // PHP: if ($esconferencia) { ... $es_entrante = (trim($info_arr['partdestino']) == '' && ($ndial === '' || !ExtensionPosible($ndial))); ... }
        if (isConferenceByFinalCalled) { // Only apply this specific incoming logic if it's a conference identified by finalCalled
            ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(commLocation);
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
                log.debug("Conference call determined as INCOMING based on partitions and calling number format.");
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) {
                 // PHP: if ($cdr_motivo_union != "7") { CdrParserUtil.swapTrunks(cdrData); }
                 // The swapTrunks was conditional on joinOnBehalfOf != 7 for party info,
                 // but for trunks, it seems to apply if not incoming and invertTrunksForConference is true.
                 // However, the party info swap already happened if joinOnBehalfOf != 7.
                 // If joinOnBehalfOf == 7, party info was NOT swapped.
                 // So, if joinOnBehalfOf != 7 (parties swapped), trunks should also be swapped.
                 // If joinOnBehalfOf == 7 (parties NOT swapped), trunks should NOT be swapped.
                 // This means trunk swap follows party swap logic for conferences.
                if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                    CdrParserUtil.swapTrunks(cdrData);
                    log.debug("Conference call (not joinOnBehalfOf=7), trunks swapped.");
                }
            }
        } else { // Not a conference identified by finalCalledPartyNumber
            ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(commLocation);
            boolean isCallingPartyInternalFormat = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                                   employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
            boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                       employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

            // PHP: if (!$interna_ext) { ... if ($interna_des || $interna_redir) { $info_arr['incoming'] = 1; _invertir(...); } }
            if (!isCallingPartyInternalFormat) {
                boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                        employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);
                if (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat) {
                     cdrData.setCallDirection(CallDirection.INCOMING);
                     CdrParserUtil.swapPartyInfo(cdrData); // This also swaps partitions
                     log.debug("Non-conference call determined as INCOMING based on party/redirect format, swapped parties.");
                }
            }
        }

        // Finalize Transfer Info
        // PHP: if ($info_arr['ext-redir'] != '' && (($info_arr['incoming'] == 0 && $info_arr['dial_number'] != $info_arr['ext-redir']) || ($info_arr['incoming'] == 1 && $info_arr['ext'] != $info_arr['ext-redir'])))
        boolean numberChangedByRedirect = false;
        if (cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) {
                // After potential inversion, callingPartyNumber is our internal extension for incoming calls
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
            log.debug("Transfer detected. Cause: {}", cdrData.getTransferCause());
        }
        // PHP: elseif ($info_arr['ext-movil'] != '' && ...)
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(commLocation);
            if (cdrData.getCallDirection() == CallDirection.OUTGOING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING && !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                // For incoming, finalCalledPartyNumber is our internal extension after potential swaps
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber()); // This becomes our extension
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
            }
            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO); // Mobile redirect is a form of auto transfer
                log.debug("Mobile redirect detected. Final called number updated to: {}. TransferCause: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getTransferCause());
            }
        }

        // PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].'') { $info_arr = array(); }
        if (isConferenceByFinalCalled && // Use the original check for this specific condition
            Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.info("Conference call where caller and callee are the same after processing. Discarding CDR: {}", cdrLine);
            return null; // Discard this CDR
        }

        log.debug("Final evaluated CDR data: {}", cdrData);
        return cdrData;
    }

    private boolean isPartitionPresent(String partition) {
        return partition != null && !partition.isEmpty();
    }

    private boolean setTransferCauseIfUnset(CdrData cdrData, TransferCause cause) {
        // PHP: _cm_CausaTransfer
        if (cdrData.getTransferCause() == null || cdrData.getTransferCause() == TransferCause.NONE) {
            cdrData.setTransferCause(cause);
            return true;
        }
        // If already set, only return true if it's the same cause (idempotency)
        return cdrData.getTransferCause() == cause;
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
            // PHP: strlen($resto_dial) > 0 && is_numeric($resto_dial)
            return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
        }
        return false;
    }
}