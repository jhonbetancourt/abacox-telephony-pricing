package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service("ciscoCm60CdrProcessor")
@Log4j2
public class CiscoCm60CdrProcessor implements CdrProcessor {

    // Internal constants for expected Cisco CDR fields (keys for columnMapping)
    // These names are for internal use and will be mapped to actual CSV header names.
    public static final String HDR_CDR_RECORD_TYPE = "cdrRecordType"; // Key field to identify header
    public static final String HDR_CALLING_PARTY_NUMBER = "callingPartyNumber";
    public static final String HDR_CALLING_PARTY_NUMBER_PARTITION = "callingPartyNumberPartition";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER = "finalCalledPartyNumber";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION = "finalCalledPartyNumberPartition";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER = "originalCalledPartyNumber";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION = "originalCalledPartyNumberPartition";
    public static final String HDR_LAST_REDIRECT_DN = "lastRedirectDn";
    public static final String HDR_LAST_REDIRECT_DN_PARTITION = "lastRedirectDnPartition";
    public static final String HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER = "finalMobileCalledPartyNumber";
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destMobileDeviceName"; // Assuming this is the partition for mobile

    public static final String HDR_DATETIME_ORIGINATION = "dateTimeOrigination";
    public static final String HDR_DATETIME_CONNECT = "dateTimeConnect";
    public static final String HDR_DATETIME_DISCONNECT = "dateTimeDisconnect";
    public static final String HDR_DURATION = "duration";
    public static final String HDR_AUTH_CODE_DESCRIPTION = "authCodeDescription";
    public static final String HDR_LAST_REDIRECT_REDIRECT_REASON = "lastRedirectRedirectReason";
    public static final String HDR_ORIG_DEVICE_NAME = "origDeviceName";
    public static final String HDR_DEST_DEVICE_NAME = "destDeviceName";
    public static final String HDR_JOIN_ON_BEHALF_OF = "joinOnBehalfOf";
    public static final String HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF = "destCallTerminationOnBehalfOf";
    public static final String HDR_DEST_CONVERSATION_ID = "destConversationId";
    public static final String HDR_GLOBAL_CALL_ID_CALL_ID = "globalCallID_callId";

    public static final String HDR_ORIG_VIDEO_CAP_CODEC = "origVideoCap_Codec";
    public static final String HDR_ORIG_VIDEO_CAP_BANDWIDTH = "origVideoCap_Bandwidth";
    public static final String HDR_ORIG_VIDEO_CAP_RESOLUTION = "origVideoCap_Resolution";
    public static final String HDR_DEST_VIDEO_CAP_CODEC = "destVideoCap_Codec";
    public static final String HDR_DEST_VIDEO_CAP_BANDWIDTH = "destVideoCap_Bandwidth";
    public static final String HDR_DEST_VIDEO_CAP_RESOLUTION = "destVideoCap_Resolution";

    private static final String CDR_DELIMITER = ",";
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // From PHP: $_IDENTIFICADOR_CONFERENCIA

    @Override
    public String getCdrDelimiter() {
        return CDR_DELIMITER;
    }

    @Override
    public Map<String, Integer> establishColumnMapping(String headerLine) {
        Map<String, Integer> columnMapping = new HashMap<>();
        String[] headers = headerLine.split(CDR_DELIMITER);
        for (int i = 0; i < headers.length; i++) {
            String headerName = headers[i].trim().replace("\"", ""); // Clean header name
            // Map known headers to our internal constants
            // This is a simplified mapping. A more robust solution might use a predefined map
            // of "known Cisco header" -> "internal constant".
            switch (headerName) {
                case "cdrRecordType": columnMapping.put(HDR_CDR_RECORD_TYPE, i); break;
                case "callingPartyNumber": columnMapping.put(HDR_CALLING_PARTY_NUMBER, i); break;
                case "callingPartyNumberPartition": columnMapping.put(HDR_CALLING_PARTY_NUMBER_PARTITION, i); break;
                case "finalCalledPartyNumber": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER, i); break;
                case "finalCalledPartyNumberPartition": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "originalCalledPartyNumber": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER, i); break;
                case "originalCalledPartyNumberPartition": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "lastRedirectDn": columnMapping.put(HDR_LAST_REDIRECT_DN, i); break;
                case "lastRedirectDnPartition": columnMapping.put(HDR_LAST_REDIRECT_DN_PARTITION, i); break;
                case "finalMobileCalledPartyNumber": columnMapping.put(HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER, i); break;
                case "destMobileDeviceName": columnMapping.put(HDR_DEST_MOBILE_DEVICE_NAME_PARTITION, i); break; // Assuming this is the partition for mobile
                case "dateTimeOrigination": columnMapping.put(HDR_DATETIME_ORIGINATION, i); break;
                case "dateTimeConnect": columnMapping.put(HDR_DATETIME_CONNECT, i); break;
                case "dateTimeDisconnect": columnMapping.put(HDR_DATETIME_DISCONNECT, i); break;
                case "duration": columnMapping.put(HDR_DURATION, i); break;
                case "authCodeDescription": columnMapping.put(HDR_AUTH_CODE_DESCRIPTION, i); break;
                case "lastRedirectRedirectReason": columnMapping.put(HDR_LAST_REDIRECT_REDIRECT_REASON, i); break;
                case "origDeviceName": columnMapping.put(HDR_ORIG_DEVICE_NAME, i); break;
                case "destDeviceName": columnMapping.put(HDR_DEST_DEVICE_NAME, i); break;
                case "joinOnBehalfOf": columnMapping.put(HDR_JOIN_ON_BEHALF_OF, i); break;
                case "destCallTerminationOnBehalfOf": columnMapping.put(HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF, i); break;
                case "destConversationId": columnMapping.put(HDR_DEST_CONVERSATION_ID, i); break;
                case "globalCallID_callId": columnMapping.put(HDR_GLOBAL_CALL_ID_CALL_ID, i); break;
                case "origVideoCap_Codec": columnMapping.put(HDR_ORIG_VIDEO_CAP_CODEC, i); break;
                case "origVideoCap_Bandwidth": columnMapping.put(HDR_ORIG_VIDEO_CAP_BANDWIDTH, i); break;
                case "origVideoCap_Resolution": columnMapping.put(HDR_ORIG_VIDEO_CAP_RESOLUTION, i); break;
                case "destVideoCap_Codec": columnMapping.put(HDR_DEST_VIDEO_CAP_CODEC, i); break;
                case "destVideoCap_Bandwidth": columnMapping.put(HDR_DEST_VIDEO_CAP_BANDWIDTH, i); break;
                case "destVideoCap_Resolution": columnMapping.put(HDR_DEST_VIDEO_CAP_RESOLUTION, i); break;
                // Add all other relevant mappings from cm_config['cabeceras'] in PHP
            }
        }
        // Validate essential columns are present
        if (!columnMapping.containsKey(HDR_CALLING_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_FINAL_CALLED_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_DATETIME_ORIGINATION) ||
            !columnMapping.containsKey(HDR_DURATION)) {
            log.error("Essential CDR header columns are missing. Mapping: {}", columnMapping);
            throw new CdrProcessingException("Essential CDR header columns are missing.");
        }
        log.info("CDR Column Mapping Established: {}", columnMapping);
        return columnMapping;
    }

    private String getColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        Integer index = columnMapping.get(key);
        if (index != null && index < columns.length) {
            return columns[index].trim().replace("\"", "");
        }
        return null;
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        String val = getColumnValue(columns, columnMapping, key);
        if (val != null && !val.isEmpty() && CdrHelper.isNumeric(val)) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                log.warn("Could not parse long value for key '{}': {}", key, val);
            }
        }
        return null;
    }

    private Integer getIntColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        String val = getColumnValue(columns, columnMapping, key);
        if (val != null && !val.isEmpty() && CdrHelper.isNumeric(val)) {
             try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                log.warn("Could not parse integer value for key '{}': {}", key, val);
            }
        }
        return null;
    }


    @Override
    public RawCiscoCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping) {
        String[] columns = cdrLine.split(CDR_DELIMITER);

        if (columns.length < columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0) + 1) {
            log.warn("CDR line has fewer columns than expected based on header. Line: {}", cdrLine);
            throw new CdrProcessingException("CDR line format mismatch with header. Line: " + cdrLine);
        }

        RawCiscoCdrData rawData = new RawCiscoCdrData();
        rawData.setOriginalLine(cdrLine);

        Long dateTimeOriginationEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_ORIGINATION);
        Long dateTimeConnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_CONNECT);
        Long dateTimeDisconnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_DISCONNECT);

        rawData.setDateTimeOrigination(CdrHelper.epochToLocalDateTime(dateTimeOriginationEpoch));
        rawData.setDateTimeConnect(CdrHelper.epochToLocalDateTime(dateTimeConnectEpoch));
        rawData.setDateTimeDisconnect(CdrHelper.epochToLocalDateTime(dateTimeDisconnectEpoch));

        rawData.setCallingPartyNumber(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER));
        rawData.setCallingPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER_PARTITION));
        rawData.setFinalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER));
        rawData.setFinalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION));
        rawData.setOriginalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER));
        rawData.setOriginalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION));
        rawData.setLastRedirectDn(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN));
        rawData.setLastRedirectDnPartition(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION));
        rawData.setFinalMobileCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER));
        rawData.setDestMobileDeviceNamePartition(getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME_PARTITION));


        rawData.setDuration(getIntColumnValue(columns, columnMapping, HDR_DURATION));
        rawData.setAuthCodeDescription(getColumnValue(columns, columnMapping, HDR_AUTH_CODE_DESCRIPTION));
        rawData.setLastRedirectRedirectReason(getIntColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_REDIRECT_REASON));
        rawData.setOrigDeviceName(getColumnValue(columns, columnMapping, HDR_ORIG_DEVICE_NAME));
        rawData.setDestDeviceName(getColumnValue(columns, columnMapping, HDR_DEST_DEVICE_NAME));

        rawData.setJoinOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_JOIN_ON_BEHALF_OF));
        rawData.setDestCallTerminationOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF));
        rawData.setDestConversationId(getLongColumnValue(columns, columnMapping, HDR_DEST_CONVERSATION_ID));
        rawData.setGlobalCallIDCallId(getLongColumnValue(columns, columnMapping, HDR_GLOBAL_CALL_ID_CALL_ID));

        // Video fields
        rawData.setOrigVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_CODEC));
        rawData.setOrigVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_BANDWIDTH));
        rawData.setOrigVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_RESOLUTION));
        rawData.setDestVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_CODEC));
        rawData.setDestVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_BANDWIDTH));
        rawData.setDestVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_RESOLUTION));


        // Calculate ring time (PHP logic: if connectTime > 0, ring = connect - origination, else ring = disconnect - origination)
        if (rawData.getDateTimeConnect() != null && dateTimeConnectEpoch != null && dateTimeConnectEpoch > 0) {
            if (rawData.getDateTimeOrigination() != null && dateTimeOriginationEpoch != null) {
                rawData.setRingTime((int) (dateTimeConnectEpoch - dateTimeOriginationEpoch));
            }
        } else if (rawData.getDateTimeDisconnect() != null && dateTimeDisconnectEpoch != null) {
            if (rawData.getDateTimeOrigination() != null && dateTimeOriginationEpoch != null) {
                rawData.setRingTime((int) (dateTimeDisconnectEpoch - dateTimeOriginationEpoch));
            }
        }
        if (rawData.getRingTime() != null && rawData.getRingTime() < 0) rawData.setRingTime(0);


        // If call never connected, duration should be 0
        if (dateTimeConnectEpoch == null || dateTimeConnectEpoch == 0) {
            rawData.setDuration(0);
        }

        // --- Start of logic adapted from CM_FormatoCDR in include_cm.php ---
        // This part is complex and involves interpreting partitions and redirect reasons
        // to determine the true nature of the call (incoming, outgoing, transfer, conference).

        String finalCalledPartyNum = rawData.getFinalCalledPartyNumber();
        String finalCalledPartyPart = rawData.getFinalCalledPartyNumberPartition();
        String origCalledPartyNum = rawData.getOriginalCalledPartyNumber();
        String origCalledPartyPart = rawData.getOriginalCalledPartyNumberPartition();
        String lastRedirectDn = rawData.getLastRedirectDn();
        String lastRedirectDnPart = rawData.getLastRedirectDnPartition();

        // Handle empty finalCalledPartyNumber (uses originalCalledPartyNumber)
        if (finalCalledPartyNum == null || finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = origCalledPartyNum;
            finalCalledPartyPart = origCalledPartyPart;
            rawData.setFinalCalledPartyNumber(finalCalledPartyNum); // Update rawData
            rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);
        }
        // Handle case where finalCalledPartyNumber is different from originalCalledPartyNumber
        // and lastRedirectDn is involved (potential transfer/forward)
        else if (origCalledPartyNum != null && !origCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(origCalledPartyNum)) {
            if (!isConferenceIdentifier(lastRedirectDn)) { // If lastRedirectDn is not a conference
                // The PHP logic implies that if lastRedirectDn is not a conference,
                // then originalCalledParty becomes the redirect target.
                rawData.setLastRedirectDn(origCalledPartyNum);
                rawData.setLastRedirectDnPartition(origCalledPartyPart);
                // The PHP code also sets `ext-redir-cc` if `ext-redir` was different,
                // but this seems to be for internal tracking within the PHP script.
            }
        }
        
        // Update effective numbers after initial adjustments
        lastRedirectDn = rawData.getLastRedirectDn(); // Re-fetch in case it was updated
        lastRedirectDnPart = rawData.getLastRedirectDnPartition();

        boolean isConferenceCall = isConferenceIdentifier(finalCalledPartyNum);
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();

        if (isConferenceCall) {
            rawData.setImdexTransferCause(
                (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                ImdexTransferCause.PRE_CONFERENCE_NOW :
                ImdexTransferCause.CONFERENCE
            );

            // If it's a conference, the "lastRedirectDn" often becomes the true originator or a key party.
            if (!isConferenceIdentifier(lastRedirectDn)) {
                rawData.setEffectiveDestinationNumber(lastRedirectDn);
                rawData.setEffectiveDestinationPartition(lastRedirectDnPart);
                rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber()); // Original caller
                rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());

                // The PHP code swaps dial_number/ext and partdestino/partorigen here.
                // This means the 'lastRedirectDn' becomes the 'dial_number' for processing.
                rawData.setFinalCalledPartyNumber(lastRedirectDn);
                rawData.setFinalCalledPartyNumberPartition(lastRedirectDnPart);
                // And the original callingPartyNumber becomes the 'ext'.
                // This is already set in rawData.callingPartyNumber.

                if (rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    // Special handling for CONFERENCENOW
                    // PHP: $info_arr['ext-redir'] = 'i'.$info_arr['indice-conferencia'];
                    // This implies lastRedirectDn should be an internal conference ID.
                    // For now, we keep lastRedirectDn as is, enrichment might handle specific ID generation.
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir(...) }
            // This inversion logic for conferences (where original caller becomes dialed number)
            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) {
                 // Swap callingPartyNumber with finalCalledPartyNumber (which was just set to lastRedirectDn)
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
                rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
                rawData.setFinalCalledPartyNumber(tempNum);
                rawData.setFinalCalledPartyNumberPartition(tempPart);
            }

        } else if (isConferenceIdentifier(lastRedirectDn)) {
            // Call was redirected FROM a conference (end of a conference leg)
            rawData.setImdexTransferCause(ImdexTransferCause.CONFERENCE_END);
            // The effective numbers are the main calling/called parties in this case.
            rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
            rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());
            rawData.setEffectiveDestinationNumber(rawData.getFinalCalledPartyNumber());
            rawData.setEffectiveDestinationPartition(rawData.getFinalCalledPartyNumberPartition());
        }


        // Determine incoming/outgoing based on partitions and extension format
        // This is a simplified interpretation of the PHP logic.
        // PHP: $interna_ext = ($info_arr['partorigen'] != '' && ExtensionPosible($info_arr['ext']));
        // PHP: $interna_des = ($info_arr['partdestino'] != '' && ExtensionPosible($info_arr['dial_number']));
        boolean isOriginInternal = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), commLocation);
        boolean isDestinationInternal = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), commLocation);

        if (isConferenceCall) {
            // For conferences, PHP logic: if partdestino is empty and dial_number is not an extension, it's incoming.
            // After the swaps, rawData.getFinalCalledPartyNumber() is the party that joined/left.
            // rawData.getCallingPartyNumber() is the conference bridge or other party.
            if ((rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                !isPotentialExtension(rawData.getFinalCalledPartyNumber())) {
                rawData.setIncomingCall(true);
            } else {
                rawData.setIncomingCall(false); // Default for conference, may need trunk inversion later
            }
        } else if (!isOriginInternal && isDestinationInternal) {
            rawData.setIncomingCall(true);
            // PHP inverts dial_number and ext for incoming calls.
            String tempNum = rawData.getCallingPartyNumber();
            String tempPart = rawData.getCallingPartyNumberPartition();
            rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
            rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
            rawData.setFinalCalledPartyNumber(tempNum);
            rawData.setFinalCalledPartyNumberPartition(tempPart);
        } else {
            rawData.setIncomingCall(false);
        }

        // Handle general transfers (not conference-related initially)
        Integer redirectReason = rawData.getLastRedirectRedirectReason();
        if (redirectReason != null && redirectReason > 0 && redirectReason <= 16 &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            // PHP: $info_arr['ext-redir'] is already set.
            // PHP: _cm_CausaTransfer($info_arr, IMDEX_TRANSFER_NORMAL);
            rawData.setImdexTransferCause(ImdexTransferCause.NORMAL);
        } else if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty() &&
                   !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getLastRedirectDn()) && /* Saliente */
                   !Objects.equals(rawData.getCallingPartyNumber(), rawData.getLastRedirectDn()) && /* Entrante */
                   rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            // This is an auto-transfer if not already a conference or normal transfer
            rawData.setImdexTransferCause(
                (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                ImdexTransferCause.PRE_CONFERENCE_NOW : // This might be a specific type of auto-transfer leading to conference
                ImdexTransferCause.AUTO_TRANSFER
            );
        }

        // Mobile redirect handling (simplified)
        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getFinalMobileCalledPartyNumber()) && // Saliente
            !Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalMobileCalledPartyNumber()) && // Entrante
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {

            if (rawData.isIncomingCall()) {
                rawData.setCallingPartyNumber(rawData.getFinalMobileCalledPartyNumber()); // The call effectively went to the mobile
                rawData.setCallingPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
            } else {
                rawData.setFinalCalledPartyNumber(rawData.getFinalMobileCalledPartyNumber()); // The call was redirected to the mobile
                rawData.setFinalCalledPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
            }
            rawData.setImdexTransferCause(ImdexTransferCause.AUTO_TRANSFER);
        }


        // If it's a conference and not incoming, PHP inverts trunks.
        // This logic is tricky because trunk assignment depends on final call direction.
        // For now, we keep origDeviceName and destDeviceName as parsed.
        // EnrichmentService will assign CallRecord.trunk and CallRecord.initialTrunk.

        // Final check for conference calls that might look like self-calls after processing
        if (isConferenceCall &&
            Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            log.warn("Conference call resulted in self-call after processing, might be an issue. CDR: {}", cdrLine);
            // Depending on strictness, could throw an exception or mark for review
        }

        return rawData;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        // PHP: $_IDENTIFICADOR_CONFERENCIA == $inicio_dial && strlen($resto_dial) > 0 && is_numeric($resto_dial)
        return number.toLowerCase().startsWith(CONFERENCE_IDENTIFIER_PREFIX) &&
               number.length() > CONFERENCE_IDENTIFIER_PREFIX.length() &&
               CdrHelper.isNumeric(number.substring(CONFERENCE_IDENTIFIER_PREFIX.length()));
    }

    private boolean isInternalParty(String number, String partition, CommunicationLocation commLocation) {
        // Simplified: PHP's ExtensionPosible is complex.
        // A basic check: partition is not empty and number looks like an extension.
        // Real implementation would use ConfigurationService.getInternalExtensionLimits()
        return (partition != null && !partition.isEmpty()) && isPotentialExtension(number);
    }

    private boolean isPotentialExtension(String number) {
        if (number == null || number.isEmpty()) return false;
        String cleanedNumber = CdrHelper.cleanPhoneNumber(number);
        // Basic check: numeric and within a typical extension length range
        return CdrHelper.isNumeric(cleanedNumber) &&
               cleanedNumber.length() >= 2 && // Arbitrary min length
               cleanedNumber.length() <= 7;   // Arbitrary max length (adjust based on actuals)
    }
}