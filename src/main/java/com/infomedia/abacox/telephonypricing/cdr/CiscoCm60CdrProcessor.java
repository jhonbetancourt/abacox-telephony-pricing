// FILE: com/infomedia/abacox/telephonypricing/cdr/CiscoCm60CdrProcessor.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
@Qualifier("ciscoCm60CdrProcessor")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60CdrProcessor implements CdrProcessor {

    // Standard Cisco CDR Header Names (lowercase for consistent map keys)
    public static final String HDR_CDR_RECORD_TYPE = "cdrrecordtype"; // PHP: cm_config['llave']
    public static final String HDR_GLOBAL_CALL_ID_CALL_MANAGER_ID = "globalcallid_callmanagerid";
    public static final String HDR_GLOBAL_CALL_ID_CALL_ID = "globalcallid_callid"; // Used for RawCdrData
    public static final String HDR_ORIG_LEG_CALL_IDENTIFIER = "origlegcallidentifier";
    public static final String HDR_DATETIME_ORIGINATION = "datetimeorigination";
    public static final String HDR_CALLING_PARTY_NUMBER = "callingpartynumber";
    public static final String HDR_CALLING_PARTY_NUMBER_PARTITION = "callingpartynumberpartition";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER = "originalcalledpartynumber";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION = "originalcalledpartynumberpartition";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER = "finalcalledpartynumber";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION = "finalcalledpartynumberpartition";
    public static final String HDR_LAST_REDIRECT_DN = "lastredirectdn";
    public static final String HDR_LAST_REDIRECT_DN_PARTITION = "lastredirectdnpartition";
    public static final String HDR_DATETIME_CONNECT = "datetimeconnect";
    public static final String HDR_DATETIME_DISCONNECT = "datetimedisconnect";
    public static final String HDR_LAST_REDIRECT_REDIRECT_REASON = "lastredirectredirectreason";
    public static final String HDR_AUTH_CODE_DESCRIPTION = "authcodedescription";
    public static final String HDR_DURATION = "duration";
    public static final String HDR_ORIG_DEVICE_NAME = "origdevicename";
    public static final String HDR_DEST_DEVICE_NAME = "destdevicename";
    public static final String HDR_JOIN_ON_BEHALF_OF = "joinonbehalfof";
    public static final String HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF = "destcallterminationonbehalfof";
    public static final String HDR_DEST_CONVERSATION_ID = "destconversationid"; // Used for RawCdrData
    public static final String HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER = "finalmobilecalledpartynumber";
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destmobiledevicename"; // Partition for mobile redirect

    public static final String HDR_ORIG_VIDEO_CAP_CODEC = "origvideocap_codec";
    public static final String HDR_ORIG_VIDEO_CAP_BANDWIDTH = "origvideocap_bandwidth";
    public static final String HDR_ORIG_VIDEO_CAP_RESOLUTION = "origvideocap_resolution";
    public static final String HDR_DEST_VIDEO_CAP_CODEC = "destvideocap_codec";
    public static final String HDR_DEST_VIDEO_CAP_BANDWIDTH = "destvideocap_bandwidth";
    public static final String HDR_DEST_VIDEO_CAP_RESOLUTION = "destvideocap_resolution";

    private static final String CDR_DELIMITER = ",";
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $_IDENTIFICADOR_CONFERENCIA

    private final CdrConfigService cdrConfigService;

    @Override
    public String getCdrDelimiter() {
        return CDR_DELIMITER;
    }

    @Override
    public Map<String, Integer> establishColumnMapping(String headerLine) {
        Map<String, Integer> columnMapping = new HashMap<>();
        String[] headers = headerLine.split(CDR_DELIMITER);
        for (int i = 0; i < headers.length; i++) {
            // Store lowercase, cleaned header name as key
            columnMapping.put(CdrHelper.cleanString(headers[i]).toLowerCase(), i);
        }

        // Validate essential columns by checking if their standard lowercase names are keys in the map
        if (!columnMapping.containsKey(HDR_CALLING_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_FINAL_CALLED_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_DATETIME_ORIGINATION) ||
            !columnMapping.containsKey(HDR_DURATION)) {
            log.error("Essential Cisco CDR header columns are missing. Parsed Headers: {}", columnMapping.keySet());
            throw new CdrProcessingException("Essential Cisco CDR header columns are missing based on standard names.");
        }
        log.info("Cisco CDR Column Mapping Established: {}", columnMapping);
        return columnMapping;
    }

    @Override
    public RawCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping) {
        String[] columns = cdrLine.split(CDR_DELIMITER, -1);

        int maxExpectedIndex = columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0);
        if (columns.length <= maxExpectedIndex) {
            log.warn("CDR line has {} columns, but expected at least {} based on header. Line: {}", columns.length, maxExpectedIndex + 1, cdrLine);
            throw new CdrProcessingException("CDR line column count mismatch with header. Line: " + cdrLine);
        }

        RawCdrData rawData = new RawCdrData();
        rawData.setOriginalLine(cdrLine);

        // 1. Parse all raw fields
        Long dateTimeOriginationEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_ORIGINATION);
        Long dateTimeConnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_CONNECT);
        Long dateTimeDisconnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_DISCONNECT);

        rawData.setDateTimeOrigination(CdrHelper.epochToLocalDateTime(dateTimeOriginationEpoch));
        rawData.setDateTimeConnect(CdrHelper.epochToLocalDateTime(dateTimeConnectEpoch));
        rawData.setDateTimeDisconnect(CdrHelper.epochToLocalDateTime(dateTimeDisconnectEpoch));

        rawData.setCallingPartyNumber(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER));
        rawData.setCallingPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER_PARTITION).toUpperCase());

        // These will be modified by conference/redirect logic
        String finalCalledPartyNum = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER);
        String finalCalledPartyPart = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase();
        String originalCalledPartyNum = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER);
        String originalCalledPartyPart = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase();
        String lastRedirectDn = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN);
        String lastRedirectDnPart = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION).toUpperCase();
        String originalLastRedirectDn = lastRedirectDn; // Preserve for specific conference case logic

        rawData.setDuration(getIntColumnValue(columns, columnMapping, HDR_DURATION));
        rawData.setAuthCodeDescription(getColumnValue(columns, columnMapping, HDR_AUTH_CODE_DESCRIPTION));
        rawData.setLastRedirectRedirectReason(getIntColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_REDIRECT_REASON));
        rawData.setOrigDeviceName(getColumnValue(columns, columnMapping, HDR_ORIG_DEVICE_NAME));
        rawData.setDestDeviceName(getColumnValue(columns, columnMapping, HDR_DEST_DEVICE_NAME));
        rawData.setJoinOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_JOIN_ON_BEHALF_OF));
        rawData.setDestCallTerminationOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF));
        rawData.setDestConversationId(getLongColumnValue(columns, columnMapping, HDR_DEST_CONVERSATION_ID));
        rawData.setGlobalCallIDCallId(getLongColumnValue(columns, columnMapping, HDR_GLOBAL_CALL_ID_CALL_ID));

        rawData.setFinalMobileCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER));
        rawData.setDestMobileDeviceNamePartition(getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME_PARTITION).toUpperCase());

        rawData.setOrigVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_CODEC));
        rawData.setOrigVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_BANDWIDTH));
        rawData.setOrigVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_RESOLUTION));
        rawData.setDestVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_CODEC));
        rawData.setDestVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_BANDWIDTH));
        rawData.setDestVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_RESOLUTION));

        // 2. Calculate ring time, adjust duration
        if (dateTimeConnectEpoch != null && dateTimeConnectEpoch > 0 && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeConnectEpoch - dateTimeOriginationEpoch));
        } else if (dateTimeDisconnectEpoch != null && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeDisconnectEpoch - dateTimeOriginationEpoch));
        }
        if (rawData.getRingTime() != null && rawData.getRingTime() < 0) rawData.setRingTime(0);

        if (dateTimeConnectEpoch == null || dateTimeConnectEpoch == 0) {
            rawData.setDuration(0);
        }

        // 3. Handle finalCalledPartyNum == "" (use original)
        if (finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = originalCalledPartyNum;
            finalCalledPartyPart = originalCalledPartyPart;
        }
        // 4. Handle finalCalledPartyNum != originalCalledPartyNum (update lastRedirectDn if not conference)
        else if (!originalCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(originalCalledPartyNum)) {
            if (!isConferenceIdentifier(lastRedirectDn)) {
                lastRedirectDn = originalCalledPartyNum;
                lastRedirectDnPart = originalCalledPartyPart;
            }
        }

        // 5. Conference/Redirect Logic
        boolean isFinalCalledPartyConference = isConferenceIdentifier(finalCalledPartyNum);
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true;

        // 6. If finalCalledPartyNum is a conference ID
        if (isFinalCalledPartyConference) {
            ImdexTransferCause confCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                                           ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.CONFERENCE;
            updateImdexTransferCause(rawData, confCause);

            if (!isConferenceIdentifier(lastRedirectDn)) {
                String tempConfId = finalCalledPartyNum;
                String tempConfPart = finalCalledPartyPart;

                finalCalledPartyNum = lastRedirectDn;
                finalCalledPartyPart = lastRedirectDnPart;

                lastRedirectDn = tempConfId; // This is the 'bXXXX' conference ID
                lastRedirectDnPart = tempConfPart;

                if (confCause == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    // PHP: $info_arr['ext-redir'] = 'i'.$info_arr['indice-conferencia'];
                    // originalLastRedirectDn was preserved. If it started with 'c', PHP doesn't show logic to prefer it.
                    // Sticking to 'i' + destConversationId.
                    if (rawData.getDestConversationId() != null) {
                         lastRedirectDn = "i" + rawData.getDestConversationId();
                    }
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) {
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(finalCalledPartyNum);
                rawData.setCallingPartyNumberPartition(finalCalledPartyPart);
                finalCalledPartyNum = tempNum;
                finalCalledPartyPart = tempPart;
            }
        }
        // 7. Else (not conference by finalCalledPartyNum), check if lastRedirectDn is conference
        else {
            if (isConferenceIdentifier(lastRedirectDn)) {
                if (updateImdexTransferCause(rawData, ImdexTransferCause.CONFERENCE_END)) {
                    invertTrunksForConference = false;
                }
            }
        }

        // 8. Incoming Call Detection
        rawData.setIncomingCall(false); // Default
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        if (isFinalCalledPartyConference) {
            boolean isConferenceEffectivelyIncoming =
                (finalCalledPartyPart == null || finalCalledPartyPart.isEmpty()) &&
                (finalCalledPartyNum.isEmpty() || !CdrHelper.isPotentialExtension(finalCalledPartyNum, limits));

            if (isConferenceEffectivelyIncoming) {
                rawData.setIncomingCall(true);
            } else if (invertTrunksForConference) {
                String tempTrunk = rawData.getOrigDeviceName();
                rawData.setOrigDeviceName(rawData.getDestDeviceName());
                rawData.setDestDeviceName(tempTrunk);
            }
        } else { // Not a conference identified by finalCalledPartyNum
            boolean isOriginInternal = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
            boolean isDestinationInternal = isInternalParty(finalCalledPartyNum, finalCalledPartyPart, limits);
            boolean isRedirectInternal = isInternalParty(lastRedirectDn, lastRedirectDnPart, limits);

            if (!isOriginInternal && (isDestinationInternal || isRedirectInternal)) {
                rawData.setIncomingCall(true);
                // Invert calling and final called parties (PHP does not invert partitions here)
                String tempNum = rawData.getCallingPartyNumber();
                // String tempPart = rawData.getCallingPartyNumberPartition(); // Keep original partition with new number
                rawData.setCallingPartyNumber(finalCalledPartyNum);
                // rawData.setCallingPartyNumberPartition(finalCalledPartyPart);
                finalCalledPartyNum = tempNum;
                // finalCalledPartyPart = tempPart;
            }
        }

        // 9. Set Transfer Cause (non-conference redirects)
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (lastRedirectDn != null && !lastRedirectDn.isEmpty()) {
                String effectiveDestForTransferCheck = rawData.isIncomingCall() ? rawData.getCallingPartyNumber() : finalCalledPartyNum;
                if (!Objects.equals(effectiveDestForTransferCheck, lastRedirectDn)) {
                    Integer redirectReason = rawData.getLastRedirectRedirectReason();
                    if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                        updateImdexTransferCause(rawData, ImdexTransferCause.NORMAL);
                    } else {
                        ImdexTransferCause autoCause = (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                                                       ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.AUTO_TRANSFER;
                        updateImdexTransferCause(rawData, autoCause);
                    }
                }
            }
        }

        // 10. Mobile Redirect
        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) { // PHP: only if no other transfer took precedence

            String currentEffectiveDestForMobileCheck = rawData.isIncomingCall() ? rawData.getCallingPartyNumber() : finalCalledPartyNum;
            if (!Objects.equals(currentEffectiveDestForMobileCheck, rawData.getFinalMobileCalledPartyNumber())) {
                if (rawData.isIncomingCall()) {
                    rawData.setCallingPartyNumber(rawData.getFinalMobileCalledPartyNumber());
                    rawData.setCallingPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
                } else {
                    finalCalledPartyNum = rawData.getFinalMobileCalledPartyNumber();
                    finalCalledPartyPart = rawData.getDestMobileDeviceNamePartition();
                }
                updateImdexTransferCause(rawData, ImdexTransferCause.AUTO_TRANSFER);
            }
        }

        // 11. Set Effective Numbers in rawData
        rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
        rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());
        rawData.setEffectiveDestinationNumber(finalCalledPartyNum);
        rawData.setEffectiveDestinationPartition(finalCalledPartyPart);

        // 12. Final Self-Call Discard (only if original finalCalledPartyNum was a conference ID)
        if (isFinalCalledPartyConference &&
            Objects.equals(rawData.getEffectiveOriginatingNumber(), rawData.getEffectiveDestinationNumber()) &&
            Objects.equals(rawData.getEffectiveOriginatingPartition(), rawData.getEffectiveDestinationPartition())) {
            log.warn("Conference call resulted in self-call after processing. CDR: {}", cdrLine);
            throw new CdrProcessingException("Conference resulted in self-call: " + cdrLine);
        }

        // 13. Store modified lastRedirectDn and finalCalledPartyNum back into rawData's main fields
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);

        return rawData;
    }

    private String getColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        Integer index = columnMapping.get(headerKey.toLowerCase()); // Header keys in map are lowercase
        if (index != null && index >= 0 && index < columns.length) {
            return CdrHelper.cleanString(columns[index]);
        }
        // log.trace("Header key '{}' not found in column mapping or index out of bounds.", headerKey);
        return "";
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
            try {
                // Cisco CDR epoch times are sometimes suffixed with non-numeric characters or have decimals
                // We need to ensure we parse only the numeric part representing seconds.
                String numericPart = val.replaceAll("[^0-9]", "");
                if (!numericPart.isEmpty()) {
                    return Long.parseLong(numericPart);
                }
            } catch (NumberFormatException e) {
                log.warn("Could not parse long value for key '{}': {} from original value '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
            }
        }
        return null;
    }

    private Integer getIntColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
             try {
                String numericPart = val.replaceAll("[^0-9-]", ""); // Allow negative for some fields if necessary
                if (!numericPart.isEmpty() && !"-".equals(numericPart)) { // Handle case where only "-" remains
                     return Integer.parseInt(numericPart);
                }
            } catch (NumberFormatException e) {
                log.warn("Could not parse integer value for key '{}': {} from original value '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
            }
        }
        return null;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        // Ensure CONFERENCE_IDENTIFIER_PREFIX is lowercase for comparison
        String prefixLower = CONFERENCE_IDENTIFIER_PREFIX.toLowerCase();
        if (number.toLowerCase().startsWith(prefixLower)) {
            String restOfNumber = number.substring(prefixLower.length());
            return !restOfNumber.isEmpty() && CdrHelper.isNumeric(restOfNumber);
        }
        return false;
    }

    private boolean isInternalParty(String number, String partition, InternalExtensionLimitsDto limits) {
        return (partition != null && !partition.isEmpty()) && CdrHelper.isPotentialExtension(number, limits);
    }

    private boolean updateImdexTransferCause(RawCdrData rawData, ImdexTransferCause newCause) {
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            rawData.setImdexTransferCause(newCause);
            return true;
        }
        return rawData.getImdexTransferCause() == newCause;
    }
}