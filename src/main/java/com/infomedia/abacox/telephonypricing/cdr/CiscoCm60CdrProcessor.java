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

    // From PHP: La planta 6.0 es el tipoplanta 26 (CM_6_0)
    public static final String PLANT_TYPE_IDENTIFIER = "26";
    public static final String CDR_RECORD_TYPE_HEADER = "cdrrecordtype"; // PHP: $cm_config['llave']
    private static final String CDR_SEPARATOR = ","; // PHP: $cm_config['cdr_separador']
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

    // Maps conceptual field names (from CdrData) to the actual header names found in the CDR file
    private final Map<String, String> conceptualToActualHeaderMap = new HashMap<>();
    // Maps actual (lowercase, cleaned) header names from CDR file to their column index
    private final Map<String, Integer> currentHeaderPositions = new HashMap<>();

    private final EmployeeLookupService employeeLookupService;
    private final CallTypeDeterminationService callTypeDeterminationService;


    @PostConstruct
    public void init() {
        // These are the *conceptual* fields we expect to map.
        // The actual header names might vary slightly (case, exact wording).
        // This map helps bridge CdrData fields to potential header names.
        // PHP: $cm_config['cabeceras'][$cab] = $mllave;
        // $mllave is like "cdr_callingPartyNumber", $cab is the actual header string.
        // Here, key is conceptual, value is the *default/expected* actual header.
        // parseHeader will then map the *actual* headers found to these conceptual ones if they match.
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

        log.info("Cisco CM 6.0 CDR Processor initialized with default header mappings.");
    }

    /**
     * PHP equivalent: CM_ValidarCab (called when header line is detected)
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
        // PHP's CM_ValidarCab also updates $cm_config[$llave] = $pos_actual;
        // and $cm_config['cdr_campos'] = $max_campos;
        // Here, currentHeaderPositions serves the purpose of $cm_config[$llave]
        // and max_campos is implicitly the size or max index.
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        // Find the actual header name that maps to this conceptual field from our default list
        String defaultActualHeader = conceptualToActualHeaderMap.get(conceptualFieldName);
        if (defaultActualHeader == null) {
            // If the conceptual field itself is a direct header name (e.g. unmapped custom field)
            defaultActualHeader = conceptualFieldName.toLowerCase();
        }

        Integer position = currentHeaderPositions.get(defaultActualHeader);

        // Fallback: if the conceptual name itself is a header (e.g. from a slightly different CDR version)
        if (position == null) {
            position = currentHeaderPositions.get(conceptualFieldName.toLowerCase());
        }

        if (position != null && position < fields.size()) {
            String rawValue = fields.get(position);
            if (rawValue == null) return "";

            String cleanedValue = CdrParserUtil.cleanCsvField(rawValue); // Ensure fields are cleaned

            // PHP: if (strpos($cab, 'ipaddr') !== false || strpos($cab, 'address_ip') !== false)
            if (defaultActualHeader.contains("ipaddr") || defaultActualHeader.contains("address_ip")) {
                try {
                    if (!cleanedValue.isEmpty() && !cleanedValue.equals("0") && !cleanedValue.equals("-1")) { // -1 can be an IP in Cisco
                        return CdrParserUtil.decimalToIp(Long.parseLong(cleanedValue));
                    }
                    return cleanedValue; // Return "0" or "-1" as is
                } catch (NumberFormatException e) {
                    log.warn("Could not parse IP from decimal: {} for field {}", cleanedValue, defaultActualHeader);
                    return cleanedValue; // Return original if parsing fails
                }
            }
            return cleanedValue;
        }
        log.trace("Field '{}' (maps to actual header '{}') not found or out of bounds.", conceptualFieldName, defaultActualHeader);
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
            log.warn("Could not parse integer from: '{}'", valueStr);
            return 0;
        }
    }
     private Long parseLongField(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0L;
        try {
            return Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            log.warn("Could not parse long from: '{}'", valueStr);
            return 0L;
        }
    }

    /**
     * PHP equivalent: CM_FormatoCDR (core logic part)
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

        // PHP: if (strtolower($primer_campo) == strtolower($_cm_config['llave'])) // Linea con cabecera
        // This check is done by CdrFileProcessorService before calling evaluateFormat
        // PHP: elseif (count($arreglo_string) < $_cm_config['cdr_campos'])
        // We use maxMappedIndex to check if enough fields are present for mapped headers.
        int maxMappedIndex = currentHeaderPositions.values().stream().filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(-1);
        if (fields.size() <= maxMappedIndex && maxMappedIndex != -1) { // Check only if maxMappedIndex is valid
            log.warn("CDR line has fewer fields ({}) than expected based on max mapped header index ({}): {}", fields.size(), maxMappedIndex, cdrLine);
            cdrData.setMarkedForQuarantine(true); cdrData.setQuarantineReason("Insufficient fields for mapped headers");
            cdrData.setQuarantineStep("evaluateFormat_FieldCountCheck"); return cdrData;
        }
        // PHP: elseif (strtoupper($campos[0]) != 'INTEGER' ) // Ignora linea indicando los tipos
        // This is handled by CdrFileProcessorService or specific line_Archivo in PHP
        if ("INTEGER".equalsIgnoreCase(fields.get(0))) {
            log.debug("Skipping INTEGER type line: {}", cdrLine);
            return null;
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

        // PHP: $tiempo_repique logic
        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect).getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            // Call never connected, ringing time is until disconnect
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect).getSeconds();
            cdrData.setDurationSeconds(0); // Ensure duration is 0 if call never connected
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime)); // Ringing time cannot be negative

        // PHP: if ($info_arr['dial_number'] == "") ...
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        }
        // PHP: elseif ($info_arr['dial_number'] != $destino_original && $destino_original != '') ...
        else if (!Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            // PHP: if (!$esconf_redir) ...
            if (!isConferenceIdentifier(cdrData.getLastRedirectDn())) {
                cdrData.storeOriginalValue("lastRedirectDn", cdrData.getLastRedirectDn()); // Save original lastRedirectDn
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        boolean isConferenceByFinalCalled = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean invertTrunksForConference = true; // PHP: $invertir_troncales

        if (isConferenceByFinalCalled) {
            cdrData.setInternalCall(false); // Conference calls are usually not treated as simple internal calls for initial typing
            TransferCause confTransferCause = (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                                              TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE;
            setTransferCauseIfUnset(cdrData, confTransferCause);

            // PHP: if (!$esconf_redir)
            if (!isConferenceIdentifier(cdrData.getLastRedirectDn())) {
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
                        // PHP: $info_arr['ext-redir']   = 'i'.$info_arr['indice-conferencia'];
                        cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir(...); }
            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) {
                CdrParserUtil.swapPartyInfo(cdrData);
                CdrParserUtil.swapTrunks(cdrData); // Trunks also swapped here in PHP's logic for this case
            }
        } else {
            // PHP: elseif ($info_arr['info_transfer'] == IMDEX_TRANSFER_CONFERE_FIN)
            // This is a bit circular. If it's a conference end, it's because lastRedirectDn was a conference ID.
            if (isConferenceIdentifier(cdrData.getLastRedirectDn())) {
                setTransferCauseIfUnset(cdrData, TransferCause.CONFERENCE_END);
                invertTrunksForConference = false;
            }
        }

        // Initial call direction and internal call flag determination (PHP: $info_arr['interna'] and $info_arr['incoming'])
        // This is a simplified initial pass; CallTypeDeterminationService will refine it.
        ExtensionLimits limits = callTypeDeterminationService.getExtensionLimits(); // Get current limits
        boolean isCallingPartyInternalFormat = isPartitionPresent(cdrData.getCallingPartyNumberPartition()) &&
                                               employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits);
        boolean isFinalCalledPartyInternalFormat = isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition()) &&
                                                   employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits);

        if (isCallingPartyInternalFormat && isFinalCalledPartyInternalFormat) {
            cdrData.setInternalCall(true);
        }

        if (isConferenceByFinalCalled) { // Re-check after potential party swap
            // PHP: $es_entrante = (trim($info_arr['partdestino']) == '' && ($ndial === '' || !ExtensionPosible($ndial)));
            boolean isConferenceIncoming = (!isPartitionPresent(cdrData.getFinalCalledPartyNumberPartition())) &&
                                           (cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty() ||
                                            !employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits));
            if (isConferenceIncoming) {
                cdrData.setCallDirection(CallDirection.INCOMING);
            } else if (invertTrunksForConference && cdrData.getCallDirection() != CallDirection.INCOMING) { // Only if not already set to incoming
                // If it was a conference origination and parties were swapped, trunks were also swapped.
                // If it was a conference join (joinOnBehalfOf=7), parties were NOT swapped, so trunks might need swapping now.
                if (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) {
                     CdrParserUtil.swapTrunks(cdrData);
                }
            }
        } else { // Not a call *to* a conference bridge
            boolean isRedirectPartyInternalFormat = isPartitionPresent(cdrData.getLastRedirectDnPartition()) &&
                                                    employeeLookupService.isPossibleExtension(cdrData.getLastRedirectDn(), limits);
            // PHP: if ($interna_des || $interna_redir) { $info_arr['incoming'] = 1; _invertir(...); }
            if (!isCallingPartyInternalFormat && (isFinalCalledPartyInternalFormat || isRedirectPartyInternalFormat)) {
                 cdrData.setCallDirection(CallDirection.INCOMING);
                 CdrParserUtil.swapPartyInfo(cdrData);
                 // Trunks are NOT typically swapped in this specific PHP incoming detection case
            }
        }

        // Transfer handling (non-conference initiation)
        // PHP: if ($info_arr['ext-redir'] != '' && (($info_arr['incoming'] == 0 && $info_arr['dial_number'] != $info_arr['ext-redir']) || ...))
        if (cdrData.getTransferCause() == TransferCause.NONE && // Not already set by conference logic
            cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            boolean numberChangedByRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getLastRedirectDn())) {
                numberChangedByRedirect = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
                       !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getLastRedirectDn())) { // After potential swap, callingParty is our ext
                numberChangedByRedirect = true;
            }

            if (numberChangedByRedirect) {
                if (cdrData.getLastRedirectRedirectReason() != null &&
                    cdrData.getLastRedirectRedirectReason() > 0 &&
                    cdrData.getLastRedirectRedirectReason() <= 16) { // Standard Cisco transfer reasons
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                    // PHP: $motivotrans = ($info_arr['finaliza-union'] == 7) ? IMDEX_TRANSFER_PRECONFENOW : IMDEX_TRANSFER_AUTO;
                    if (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) {
                        cdrData.setTransferCause(TransferCause.PRE_CONFERENCE_NOW);
                    } else {
                        cdrData.setTransferCause(TransferCause.AUTO);
                    }
                }
            }
        }
        // Mobile Redirection (PHP: elseif ($info_arr['ext-movil'] != '' ...))
        else if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedByMobileRedirect = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !Objects.equals(cdrData.getFinalCalledPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName());
                if (cdrData.isInternalCall() && !employeeLookupService.isPossibleExtension(cdrData.getFinalCalledPartyNumber(), limits)) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
                       !Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedByMobileRedirect = true;
                // PHP: $info_arr['ext'] = $info_arr['ext-movil']; $info_arr['partorigen'] = $info_arr['partdestino'];
                // After initial inversion for incoming, 'ext' is our employee, 'partorigen' is their partition.
                // If this is now redirected to mobile, our employee's number effectively becomes the mobile number.
                cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setCallingPartyNumberPartition(cdrData.getDestMobileDeviceName()); // Partition of the mobile device
            }

            if (numberChangedByMobileRedirect) {
                setTransferCauseIfUnset(cdrData, TransferCause.AUTO);
            }
        }

        // PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].'') { $info_arr = array(); }
        if (isConferenceByFinalCalled && // Check original conference flag before any swaps
            Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference self-call detected after all processing, discarding: {}", cdrLine);
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

    /**
     * PHP equivalent: CM_ValidarConferencia
     */
    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty() || CONFERENCE_IDENTIFIER_PREFIX == null || CONFERENCE_IDENTIFIER_PREFIX.isEmpty()) {
            return false;
        }
        String prefix = CONFERENCE_IDENTIFIER_PREFIX.toLowerCase();
        String numLower = number.toLowerCase();
        if (numLower.startsWith(prefix)) {
            String rest = numLower.substring(prefix.length());
            // PHP: strlen($resto_dial) > 0 && is_numeric($resto_dial)
            return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
        }
        return false;
    }
}