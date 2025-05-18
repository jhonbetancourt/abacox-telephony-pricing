package com.infomedia.abacox.telephonypricing.cdr;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("ciscoCm60Processor")
@Log4j2
public class CiscoCm60CdrProcessor implements ICdrTypeProcessor {

    private static final String PLANT_TYPE_ID = "CISCO_CM_6_0"; // Corresponds to CM_6_0 in PHP
    public static final String CDR_RECORD_TYPE_HEADER = "cdrrecordtype";
    private static final String CDR_SEPARATOR = ",";
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // From $cm_config['cdr_conferencia']

    // Mappings from internal CdrData field conceptual names to potential CSV header names
    // This mimics $cm_config['cabeceras'] and $cm_cdr in PHP's CM_TipoPlanta
    private final Map<String, String> headerToFieldMap = new HashMap<>();
    private Map<String, Integer> currentHeaderPositions = new HashMap<>();
    private int expectedMinFields = 0; // Will be set after header parsing

    public CiscoCm60CdrProcessor() {
        initializeDefaultHeaderMappings();
    }

    private void initializeDefaultHeaderMappings() {
        // From PHP $cm_cdr array in CM_TipoPlanta
        headerToFieldMap.put("callingpartynumberpartition", "callingPartyNumberPartition");
        headerToFieldMap.put("callingpartynumber", "callingPartyNumber");
        headerToFieldMap.put("finalcalledpartynumberpartition", "finalCalledPartyNumberPartition");
        headerToFieldMap.put("finalcalledpartynumber", "finalCalledPartyNumber");
        headerToFieldMap.put("originalcalledpartynumberpartition", "originalCalledPartyNumberPartition");
        headerToFieldMap.put("originalcalledpartynumber", "originalCalledPartyNumber");
        headerToFieldMap.put("lastredirectdnpartition", "lastRedirectDnPartition");
        headerToFieldMap.put("lastredirectdn", "lastRedirectDn");
        headerToFieldMap.put("destmobiledevicename", "destMobileDeviceName");
        headerToFieldMap.put("finalmobilecalledpartynumber", "finalMobileCalledPartyNumber");
        headerToFieldMap.put("lastredirectredirectreason", "lastRedirectRedirectReason");
        headerToFieldMap.put("datetimeorigination", "dateTimeOrigination");
        headerToFieldMap.put("datetimeconnect", "dateTimeConnect");
        headerToFieldMap.put("datetimedisconnect", "dateTimeDisconnect");
        headerToFieldMap.put("origdevicename", "origDeviceName");
        headerToFieldMap.put("destdevicename", "destDeviceName");
        headerToFieldMap.put("origvideocap_codec", "origVideoCodec");
        headerToFieldMap.put("origvideocap_bandwidth", "origVideoBandwidth");
        headerToFieldMap.put("origvideocap_resolution", "origVideoResolution");
        headerToFieldMap.put("destvideocap_codec", "destVideoCodec");
        headerToFieldMap.put("destvideocap_bandwidth", "destVideoBandwidth");
        headerToFieldMap.put("destvideocap_resolution", "destVideoResolution");
        headerToFieldMap.put("joinonbehalfof", "joinOnBehalfOf");
        headerToFieldMap.put("destcallterminationonbehalfof", "destCallTerminationOnBehalfOf");
        headerToFieldMap.put("destconversationid", "destConversationId");
        headerToFieldMap.put("globalcallid_callid", "globalCallIDCallId");
        headerToFieldMap.put("duration", "durationSeconds");
        headerToFieldMap.put("authcodedescription", "authCodeDescription");
        // Add all other mappings from $cm_cdr
    }


    @Override
    public void parseHeader(String headerLine) {
        currentHeaderPositions.clear();
        List<String> headers = CdrParserUtil.parseCsvLine(headerLine, CDR_SEPARATOR);
        for (int i = 0; i < headers.size(); i++) {
            String cleanedHeader = CdrParserUtil.cleanCsvField(headers.get(i)).toLowerCase();
            currentHeaderPositions.put(cleanedHeader, i);
        }
        expectedMinFields = headers.size(); // Or a fixed number if known
        log.info("Parsed Cisco CM 6.0 header. Field count: {}. Mappings: {}", expectedMinFields, currentHeaderPositions);
    }

    private String getFieldValue(List<String> fields, String conceptualFieldName) {
        String headerName = headerToFieldMap.entrySet().stream()
                .filter(entry -> conceptualFieldName.equalsIgnoreCase(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(conceptualFieldName.toLowerCase()); // Fallback to direct name if not in map

        Integer position = currentHeaderPositions.get(headerName);
        if (position != null && position < fields.size()) {
            String rawValue = fields.get(position);
            // Cisco CDRs sometimes have IPs as decimal, dates as epoch seconds
            if (headerName.contains("ipaddr") || headerName.contains("address_ip")) {
                try {
                    return CdrParserUtil.decimalToIp(Long.parseLong(rawValue));
                } catch (NumberFormatException e) {
                    log.warn("Could not parse IP from decimal: {}", rawValue);
                    return rawValue; // return original if not a number
                }
            }
            // Dates are handled separately after extraction
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
            log.warn("Invalid epoch seconds format: {}", epochSecondsStr);
            return null;
        }
    }


    @Override
    public CdrData evaluateFormat(String cdrLine) {
        if (currentHeaderPositions.isEmpty()) {
            log.error("Header not parsed for Cisco CM 6.0. Cannot process CDR line.");
            // Potentially throw an exception or return a CdrData marked for quarantine
            CdrData errorData = new CdrData();
            errorData.setRawCdrLine(cdrLine);
            errorData.setMarkedForQuarantine(true);
            errorData.setQuarantineReason("Header not parsed");
            errorData.setQuarantineStep("evaluateFormat_PreCheck");
            return errorData;
        }

        List<String> fields = CdrParserUtil.parseCsvLine(cdrLine, CDR_SEPARATOR);
        CdrData cdrData = new CdrData();
        cdrData.setRawCdrLine(cdrLine);

        if (fields.size() < expectedMinFields) {
            log.warn("CDR line has fewer fields ({}) than expected ({}): {}", fields.size(), expectedMinFields, cdrLine);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Insufficient fields");
            cdrData.setQuarantineStep("evaluateFormat_FieldCountCheck");
            return cdrData;
        }

        // Check if it's an "INTEGER" type line (metadata, not actual CDR)
        if ("INTEGER".equalsIgnoreCase(fields.get(0))) {
            log.debug("Skipping INTEGER type line: {}", cdrLine);
            return null; // Or a special marker DTO
        }


        // Extract core fields using the mapped positions
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


        String durationStr = getFieldValue(fields, "durationSeconds");
        cdrData.setDurationSeconds(durationStr.isEmpty() ? 0 : Integer.parseInt(durationStr));

        cdrData.setAuthCodeDescription(getFieldValue(fields, "authCodeDescription"));

        String lastRedirectReasonStr = getFieldValue(fields, "lastRedirectRedirectReason");
        cdrData.setLastRedirectRedirectReason(lastRedirectReasonStr.isEmpty() ? 0 : Integer.parseInt(lastRedirectReasonStr));

        cdrData.setOrigDeviceName(getFieldValue(fields, "origDeviceName"));
        cdrData.setDestDeviceName(getFieldValue(fields, "destDeviceName"));

        // Video fields
        cdrData.setOrigVideoCodec(getFieldValue(fields, "origVideoCodec"));
        String origVideoBandwidthStr = getFieldValue(fields, "origVideoBandwidth");
        cdrData.setOrigVideoBandwidth(origVideoBandwidthStr.isEmpty() ? 0 : Integer.parseInt(origVideoBandwidthStr));
        cdrData.setOrigVideoResolution(getFieldValue(fields, "origVideoResolution"));
        cdrData.setDestVideoCodec(getFieldValue(fields, "destVideoCodec"));
        String destVideoBandwidthStr = getFieldValue(fields, "destVideoBandwidth");
        cdrData.setDestVideoBandwidth(destVideoBandwidthStr.isEmpty() ? 0 : Integer.parseInt(destVideoBandwidthStr));
        cdrData.setDestVideoResolution(getFieldValue(fields, "destVideoResolution"));

        // Conference/Join fields
        String joinOnBehalfOfStr = getFieldValue(fields, "joinOnBehalfOf");
        cdrData.setJoinOnBehalfOf(joinOnBehalfOfStr.isEmpty() ? 0 : Integer.parseInt(joinOnBehalfOfStr));
        String destCallTerminationOnBehalfOfStr = getFieldValue(fields, "destCallTerminationOnBehalfOf");
        cdrData.setDestCallTerminationOnBehalfOf(destCallTerminationOnBehalfOfStr.isEmpty() ? 0 : Integer.parseInt(destCallTerminationOnBehalfOfStr));
        String destConversationIdStr = getFieldValue(fields, "destConversationId");
        cdrData.setDestConversationId(destConversationIdStr.isEmpty() ? 0L : Long.parseLong(destConversationIdStr));
        String globalCallIdStr = getFieldValue(fields, "globalCallIDCallId");
        cdrData.setGlobalCallIDCallId(globalCallIdStr.isEmpty() ? 0L : Long.parseLong(globalCallIdStr));


        // Calculate ringing time
        int ringingTime = 0;
        if (dateTimeConnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeConnect).getSeconds();
        } else if (dateTimeDisconnect != null && cdrData.getDateTimeOrigination() != null) {
            ringingTime = (int) java.time.Duration.between(cdrData.getDateTimeOrigination(), dateTimeDisconnect).getSeconds();
            cdrData.setDurationSeconds(0); // Ensure duration is 0 if call never connected
        }
        cdrData.setRingingTimeSeconds(Math.max(0, ringingTime)); // Ringing time cannot be negative


        // Logic from PHP's CM_FormatoCDR for handling empty finalCalledPartyNumber
        // and conference calls
        boolean isConferenceCall = isConferenceIdentifier(cdrData.getFinalCalledPartyNumber());
        boolean isRedirectConference = isConferenceIdentifier(cdrData.getLastRedirectDn());

        String lastRedirectDnOriginalForConference = cdrData.getLastRedirectDn(); // Store before potential modification

        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            cdrData.setFinalCalledPartyNumber(cdrData.getOriginalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumberPartition(cdrData.getOriginalCalledPartyNumberPartition());
        } else if (!cdrData.getFinalCalledPartyNumber().equals(cdrData.getOriginalCalledPartyNumber()) &&
                   cdrData.getOriginalCalledPartyNumber() != null && !cdrData.getOriginalCalledPartyNumber().isEmpty()) {
            if (!isRedirectConference) { // If lastRedirectDn is not a conference
                // Store the original lastRedirectDn before overwriting, if needed for specific logic later
                // cdrData.getAdditionalData().put("originalLastRedirectDn", cdrData.getLastRedirectDn());
                cdrData.setLastRedirectDn(cdrData.getOriginalCalledPartyNumber());
                cdrData.setLastRedirectDnPartition(cdrData.getOriginalCalledPartyNumberPartition());
            }
        }

        if (isConferenceCall) {
            cdrData.setTransferCause(
                (cdrData.getJoinOnBehalfOf() != null && cdrData.getJoinOnBehalfOf() == 7) ?
                TransferCause.CONFERENCE_NOW : TransferCause.CONFERENCE
            );
        }

        if (isConferenceCall) {
            if (!isRedirectConference) {
                String tempDialNumber = cdrData.getFinalCalledPartyNumber();
                String tempDialPartition = cdrData.getFinalCalledPartyNumberPartition();

                cdrData.setFinalCalledPartyNumber(cdrData.getLastRedirectDn());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getLastRedirectDnPartition());

                cdrData.setLastRedirectDn(tempDialNumber); // This is the 'bXXXX' conference ID
                cdrData.setLastRedirectDnPartition(tempDialPartition);

                if (cdrData.getTransferCause() == TransferCause.CONFERENCE_NOW) {
                     // In PHP: $info_arr['ext-redir']   = 'i'.$info_arr['indice-conferencia'];
                     // The PHP code also checks ext-redir-cc if it starts with 'c'.
                     // This logic might need more context from the original data if `ext-redir-cc` was populated.
                     // For now, using the indice-conferencia.
                    if (cdrData.getDestConversationId() != null && cdrData.getDestConversationId() > 0) {
                         cdrData.setLastRedirectDn("i" + cdrData.getDestConversationId());
                    }
                }
            }

            if (cdrData.getJoinOnBehalfOf() == null || cdrData.getJoinOnBehalfOf() != 7) { // Not a "Join on Behalf Of" type conference initiation
                // Swap caller/callee for standard conference legs
                String tempExt = cdrData.getCallingPartyNumber();
                String tempExtPart = cdrData.getCallingPartyNumberPartition();
                cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
                cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
                cdrData.setFinalCalledPartyNumber(tempExt);
                cdrData.setFinalCalledPartyNumberPartition(tempExtPart);
                // Note: Trunk inversion happens later based on call direction
            }
        } else { // Not a conference initiated by finalCalledPartyNumber
            if (isRedirectConference) { // Call was redirected FROM a conference (conference ended, two parties remain)
                cdrData.setTransferCause(TransferCause.CONFERENCE_END);
                // PHP logic for finding original conference leg might be complex and involve DB lookups,
                // which are omitted for now as per "no reprocessing/historical".
                // The PHP code sets ext-redir to the original conference initiator.
                // Here, we just mark it. The actual lastRedirectDn is already the conference ID.
            }
        }

        // Determine if internal (simplified, more robust logic in EnrichmentService)
        // This initial flag helps guide EnrichmentService
        boolean isCallingPartyInternal = cdrData.getCallingPartyNumberPartition() != null && !cdrData.getCallingPartyNumberPartition().isEmpty() &&
                                         CdrParserUtil.isExtension(cdrData.getCallingPartyNumber());
        boolean isFinalCalledPartyInternal = cdrData.getFinalCalledPartyNumberPartition() != null && !cdrData.getFinalCalledPartyNumberPartition().isEmpty() &&
                                             CdrParserUtil.isExtension(cdrData.getFinalCalledPartyNumber());

        if (isCallingPartyInternal && isFinalCalledPartyInternal) {
            cdrData.setInternalCall(true);
        }

        // Determine call direction (initial assessment, refined in EnrichmentService)
        // PHP: if ($info_arr['partorigen'] == '') $info_arr['incoming'] = 1;
        if ((cdrData.getCallingPartyNumberPartition() == null || cdrData.getCallingPartyNumberPartition().isEmpty()) &&
            (isFinalCalledPartyInternal || (cdrData.getLastRedirectDnPartition() != null && !cdrData.getLastRedirectDnPartition().isEmpty() && CdrParserUtil.isExtension(cdrData.getLastRedirectDn())))) {
            cdrData.setCallDirection(CallDirection.INCOMING);
            // If incoming, PHP swaps ext and dial_number. This is complex and depends on enrichment.
            // For now, just set direction. Enrichment service will handle swaps.
        }


        // Handle transfers not related to conference initiation
        if (cdrData.getTransferCause() == TransferCause.NONE && // Not already marked as conference
            cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            boolean numberChanged = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !cdrData.getFinalCalledPartyNumber().equals(cdrData.getLastRedirectDn())) {
                numberChanged = true;
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
                       !cdrData.getCallingPartyNumber().equals(cdrData.getLastRedirectDn())) { // After potential swap
                numberChanged = true;
            }

            if (numberChanged) {
                if (cdrData.getLastRedirectRedirectReason() != null &&
                    cdrData.getLastRedirectRedirectReason() > 0 &&
                    cdrData.getLastRedirectRedirectReason() <= 16) {
                    cdrData.setTransferCause(TransferCause.NORMAL);
                } else {
                     // Check for finaliza-union == 7 for PRE_CONFERENCE_NOW
                    if (cdrData.getDestCallTerminationOnBehalfOf() != null && cdrData.getDestCallTerminationOnBehalfOf() == 7) {
                        cdrData.setTransferCause(TransferCause.PRE_CONFERENCE_NOW);
                    } else {
                        cdrData.setTransferCause(TransferCause.AUTO);
                    }
                }
            }
        }

        // Handle mobile redirection
        if (cdrData.getFinalMobileCalledPartyNumber() != null && !cdrData.getFinalMobileCalledPartyNumber().isEmpty()) {
            boolean numberChangedDueToMobile = false;
            if (cdrData.getCallDirection() == CallDirection.OUTGOING &&
                !cdrData.getFinalCalledPartyNumber().equals(cdrData.getFinalMobileCalledPartyNumber())) {
                numberChangedDueToMobile = true;
                cdrData.setFinalCalledPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                cdrData.setFinalCalledPartyNumberPartition(cdrData.getDestMobileDeviceName()); // Using device name as partition
                 if (cdrData.isInternalCall() && !CdrParserUtil.isExtension(cdrData.getFinalCalledPartyNumber())) {
                    cdrData.setInternalCall(false);
                }
            } else if (cdrData.getCallDirection() == CallDirection.INCOMING &&
                       !cdrData.getCallingPartyNumber().equals(cdrData.getFinalMobileCalledPartyNumber())) { // After potential swap
                numberChangedDueToMobile = true;
                cdrData.setCallingPartyNumber(cdrData.getFinalMobileCalledPartyNumber());
                // Partition for incoming caller is tricky if it's a mobile redirect.
                // PHP sets partorigen = partdestino.
                cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
            }

            if (numberChangedDueToMobile) {
                cdrData.setTransferCause(TransferCause.AUTO); // Or a more specific mobile redirect cause
            }
        }


        // Final check for conference self-calls (PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].''))
        if (isConferenceCall &&
            cdrData.getCallingPartyNumber() != null &&
            cdrData.getCallingPartyNumber().equals(cdrData.getFinalCalledPartyNumber())) {
            log.debug("Conference self-call detected, discarding: {}", cdrLine);
            return null; // Discard this CDR
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


    @Override
    public String getPlantTypeIdentifier() {
        return PLANT_TYPE_ID;
    }
}
