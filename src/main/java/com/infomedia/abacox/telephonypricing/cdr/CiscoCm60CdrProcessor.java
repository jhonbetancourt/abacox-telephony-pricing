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

    // Header constants (as defined before)
    public static final String HDR_CDR_RECORD_TYPE = "cdrRecordType";
    public static final String HDR_GLOBAL_CALL_ID_CALL_MANAGER_ID = "globalCallID_CallManagerId";
    public static final String HDR_GLOBAL_CALL_ID_CALL_ID = "globalCallID_callId";
    public static final String HDR_ORIG_LEG_CALL_IDENTIFIER = "origLegCallIdentifier";
    public static final String HDR_DATETIME_ORIGINATION = "dateTimeOrigination";
    public static final String HDR_CALLING_PARTY_NUMBER = "callingPartyNumber";
    public static final String HDR_CALLING_PARTY_NUMBER_PARTITION = "callingPartyNumberPartition";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER = "originalCalledPartyNumber";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION = "originalCalledPartyNumberPartition";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER = "finalCalledPartyNumber";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION = "finalCalledPartyNumberPartition";
    public static final String HDR_LAST_REDIRECT_DN = "lastRedirectDn";
    public static final String HDR_LAST_REDIRECT_DN_PARTITION = "lastRedirectDnPartition";
    public static final String HDR_DATETIME_CONNECT = "dateTimeConnect";
    public static final String HDR_DATETIME_DISCONNECT = "dateTimeDisconnect";
    public static final String HDR_LAST_REDIRECT_REDIRECT_REASON = "lastRedirectRedirectReason";
    public static final String HDR_AUTH_CODE_DESCRIPTION = "authCodeDescription";
    public static final String HDR_DURATION = "duration";
    public static final String HDR_ORIG_DEVICE_NAME = "origDeviceName";
    public static final String HDR_DEST_DEVICE_NAME = "destDeviceName";
    public static final String HDR_JOIN_ON_BEHALF_OF = "joinOnBehalfOf";
    public static final String HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF = "destCallTerminationOnBehalfOf";
    public static final String HDR_DEST_CONVERSATION_ID = "destConversationId";
    public static final String HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER = "finalMobileCalledPartyNumber";
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destMobileDeviceName";

    public static final String HDR_ORIG_VIDEO_CAP_CODEC = "origVideoCap_Codec";
    public static final String HDR_ORIG_VIDEO_CAP_BANDWIDTH = "origVideoCap_Bandwidth";
    public static final String HDR_ORIG_VIDEO_CAP_RESOLUTION = "origVideoCap_Resolution";
    public static final String HDR_DEST_VIDEO_CAP_CODEC = "destVideoCap_Codec";
    public static final String HDR_DEST_VIDEO_CAP_BANDWIDTH = "destVideoCap_Bandwidth";
    public static final String HDR_DEST_VIDEO_CAP_RESOLUTION = "destVideoCap_Resolution";

    private static final String CDR_DELIMITER = ",";
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b";

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
            String headerName = CdrHelper.cleanString(headers[i]).toLowerCase();
            // Using a simple switch for known headers. A more dynamic mapping could be used.
            switch (headerName) {
                case "cdrrecordtype": columnMapping.put(HDR_CDR_RECORD_TYPE, i); break;
                case "globalcallid_callmanagerid": columnMapping.put(HDR_GLOBAL_CALL_ID_CALL_MANAGER_ID, i); break;
                case "globalcallid_callid": columnMapping.put(HDR_GLOBAL_CALL_ID_CALL_ID, i); break;
                case "origlegcallidentifier": columnMapping.put(HDR_ORIG_LEG_CALL_IDENTIFIER, i); break;
                case "datetimeorigination": columnMapping.put(HDR_DATETIME_ORIGINATION, i); break;
                case "callingpartynumber": columnMapping.put(HDR_CALLING_PARTY_NUMBER, i); break;
                case "callingpartynumberpartition": columnMapping.put(HDR_CALLING_PARTY_NUMBER_PARTITION, i); break;
                case "originalcalledpartynumber": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER, i); break;
                case "originalcalledpartynumberpartition": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "finalcalledpartynumber": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER, i); break;
                case "finalcalledpartynumberpartition": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "lastredirectdn": columnMapping.put(HDR_LAST_REDIRECT_DN, i); break;
                case "lastredirectdnpartition": columnMapping.put(HDR_LAST_REDIRECT_DN_PARTITION, i); break;
                case "datetimeconnect": columnMapping.put(HDR_DATETIME_CONNECT, i); break;
                case "datetimedisconnect": columnMapping.put(HDR_DATETIME_DISCONNECT, i); break;
                case "lastredirectredirectreason": columnMapping.put(HDR_LAST_REDIRECT_REDIRECT_REASON, i); break;
                case "authcodedescription": columnMapping.put(HDR_AUTH_CODE_DESCRIPTION, i); break;
                case "duration": columnMapping.put(HDR_DURATION, i); break;
                case "origdevicename": columnMapping.put(HDR_ORIG_DEVICE_NAME, i); break;
                case "destdevicename": columnMapping.put(HDR_DEST_DEVICE_NAME, i); break;
                case "joinonbehalfof": columnMapping.put(HDR_JOIN_ON_BEHALF_OF, i); break;
                case "destcallterminationonbehalfof": columnMapping.put(HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF, i); break;
                case "destconversationid": columnMapping.put(HDR_DEST_CONVERSATION_ID, i); break;
                case "finalmobilecalledpartynumber": columnMapping.put(HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER, i); break;
                case "destmobiledevicename": columnMapping.put(HDR_DEST_MOBILE_DEVICE_NAME_PARTITION, i); break;

                case "origvideocap_codec": columnMapping.put(HDR_ORIG_VIDEO_CAP_CODEC, i); break;
                case "origvideocap_bandwidth": columnMapping.put(HDR_ORIG_VIDEO_CAP_BANDWIDTH, i); break;
                case "origvideocap_resolution": columnMapping.put(HDR_ORIG_VIDEO_CAP_RESOLUTION, i); break;
                case "destvideocap_codec": columnMapping.put(HDR_DEST_VIDEO_CAP_CODEC, i); break;
                case "destvideocap_bandwidth": columnMapping.put(HDR_DEST_VIDEO_CAP_BANDWIDTH, i); break;
                case "destvideocap_resolution": columnMapping.put(HDR_DEST_VIDEO_CAP_RESOLUTION, i); break;
                // Add other mappings as needed
            }
        }
        // Validate essential columns
        if (!columnMapping.containsKey(HDR_CALLING_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_FINAL_CALLED_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_DATETIME_ORIGINATION) ||
            !columnMapping.containsKey(HDR_DURATION)) {
            log.error("Essential Cisco CDR header columns are missing. Mapping: {}", columnMapping);
            throw new CdrProcessingException("Essential Cisco CDR header columns are missing.");
        }
        log.info("Cisco CDR Column Mapping Established: {}", columnMapping);
        return columnMapping;
    }

    @Override
    public RawCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping) {
        String[] columns = cdrLine.split(CDR_DELIMITER, -1); // -1 to keep trailing empty strings

        if (columns.length < columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0) + 1) {
            log.warn("CDR line has fewer columns ({}) than expected based on header. Line: {}", columns.length, cdrLine);
            throw new CdrProcessingException("CDR line format mismatch with header. Line: " + cdrLine);
        }

        RawCdrData rawData = new RawCdrData();
        rawData.setOriginalLine(cdrLine);

        // Extract base fields
        Long dateTimeOriginationEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_ORIGINATION);
        Long dateTimeConnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_CONNECT);
        Long dateTimeDisconnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_DISCONNECT);

        rawData.setDateTimeOrigination(CdrHelper.epochToLocalDateTime(dateTimeOriginationEpoch));
        rawData.setDateTimeConnect(CdrHelper.epochToLocalDateTime(dateTimeConnectEpoch));
        rawData.setDateTimeDisconnect(CdrHelper.epochToLocalDateTime(dateTimeDisconnectEpoch));

        rawData.setCallingPartyNumber(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER));
        rawData.setCallingPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER_PARTITION));
        
        String finalCalledPartyNum = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER);
        String finalCalledPartyPart = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION);
        String origCalledPartyNum = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER);
        String origCalledPartyPart = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION);
        String lastRedirectDn = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN);
        String lastRedirectDnPart = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION);
        
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
        rawData.setDestMobileDeviceNamePartition(getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME_PARTITION));
        
        // Video fields
        rawData.setOrigVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_CODEC));
        rawData.setOrigVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_BANDWIDTH));
        rawData.setOrigVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_RESOLUTION));
        rawData.setDestVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_CODEC));
        rawData.setDestVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_BANDWIDTH));
        rawData.setDestVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_RESOLUTION));


        // Calculate Ring Time
        if (dateTimeConnectEpoch != null && dateTimeConnectEpoch > 0 && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeConnectEpoch - dateTimeOriginationEpoch));
        } else if (dateTimeDisconnectEpoch != null && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeDisconnectEpoch - dateTimeOriginationEpoch));
        }
        if (rawData.getRingTime() != null && rawData.getRingTime() < 0) rawData.setRingTime(0);

        // Ensure duration is 0 if call never connected
        if (dateTimeConnectEpoch == null || dateTimeConnectEpoch == 0) {
            rawData.setDuration(0);
        }

        // --- Start of complex PHP logic port for conference/redirects ---
        String extRedirCcPreserved = lastRedirectDn; // Preserve original lastRedirectDn for specific conference case

        // PHP: if ($info_arr['dial_number'] == "")
        if (finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = origCalledPartyNum;
            finalCalledPartyPart = origCalledPartyPart;
        }
        // PHP: elseif ($info_arr['dial_number'] != $destino_original && $destino_original != '')
        else if (!origCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(origCalledPartyNum)) {
            // PHP: if (!$esconf_redir)
            if (!isConferenceIdentifier(lastRedirectDn)) {
                lastRedirectDn = origCalledPartyNum;
                lastRedirectDnPart = origCalledPartyPart;
            }
        }

        boolean isFinalCalledPartyConference = isConferenceIdentifier(finalCalledPartyNum);
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true; // Default, can be overridden

        if (isFinalCalledPartyConference) {
            ImdexTransferCause confCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                                           ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.CONFERENCE;
            setImdexTransferCauseIfNotSet(rawData, confCause);

            if (!isConferenceIdentifier(lastRedirectDn)) {
                String originalFinalCalledNumForConf = finalCalledPartyNum;
                String originalFinalCalledPartForConf = finalCalledPartyPart;

                finalCalledPartyNum = lastRedirectDn;
                finalCalledPartyPart = lastRedirectDnPart;

                lastRedirectDn = originalFinalCalledNumForConf; // This is the 'bXXXX' conference ID
                lastRedirectDnPart = originalFinalCalledPartForConf;

                if (confCause == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    if (extRedirCcPreserved != null && extRedirCcPreserved.toLowerCase().startsWith("c")) {
                        lastRedirectDn = extRedirCcPreserved; // Use original if it was 'cXXXX'
                    } else if (rawData.getDestConversationId() != null) {
                        lastRedirectDn = "i" + rawData.getDestConversationId(); // Construct 'iXXXX'
                    }
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) { // PHP: if ($cdr_motivo_union != "7")
                // Invert calling and called parties
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(finalCalledPartyNum);
                rawData.setCallingPartyNumberPartition(finalCalledPartyPart);
                finalCalledPartyNum = tempNum;
                finalCalledPartyPart = tempPart;
            }
        } else {
            // Not a conference by finalCalledPartyNumber, check if lastRedirectDn is a conference
            if (isConferenceIdentifier(lastRedirectDn)) {
                if (setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.CONFERENCE_END)) {
                    invertTrunksForConference = false;
                }
            }
        }

        // Determine incoming status
        rawData.setIncomingCall(false); // Default to outgoing
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        if (isFinalCalledPartyConference) {
            // PHP: $es_entrante = (trim($info_arr['partdestino']) == '' && ($ndial === '' || !ExtensionPosible($ndial)));
            boolean isConferenceEffectivelyIncoming =
                (finalCalledPartyPart == null || finalCalledPartyPart.isEmpty()) &&
                (finalCalledPartyNum.isEmpty() || !CdrHelper.isPotentialExtension(finalCalledPartyNum, limits));

            if (isConferenceEffectivelyIncoming) {
                rawData.setIncomingCall(true);
            } else if (invertTrunksForConference) {
                // If it's a conference and determined to be outgoing, invert trunks
                String tempTrunk = rawData.getOrigDeviceName();
                rawData.setOrigDeviceName(rawData.getDestDeviceName());
                rawData.setDestDeviceName(tempTrunk);
            }
        } else {
            // General incoming logic (not a conference identified by finalCalledPartyNum)
            boolean isOriginInternal = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
            boolean isDestinationInternal = isInternalParty(finalCalledPartyNum, finalCalledPartyPart, limits);
            boolean isRedirectInternal = isInternalParty(lastRedirectDn, lastRedirectDnPart, limits);

            if (!isOriginInternal) { // If origin is not clearly internal
                if (isDestinationInternal || isRedirectInternal) { // And destination or redirect IS internal
                    rawData.setIncomingCall(true);
                    // Invert calling and final called parties
                    String tempNum = rawData.getCallingPartyNumber();
                    String tempPart = rawData.getCallingPartyNumberPartition();
                    rawData.setCallingPartyNumber(finalCalledPartyNum);
                    rawData.setCallingPartyNumberPartition(finalCalledPartyPart);
                    finalCalledPartyNum = tempNum;
                    finalCalledPartyPart = tempPart;
                }
            }
        }

        // Set transfer cause for non-conference redirects
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (lastRedirectDn != null && !lastRedirectDn.isEmpty()) {
                boolean isRedirectDifferentFromEffectiveDest;
                if (rawData.isIncomingCall()) { // After potential inversion
                    isRedirectDifferentFromEffectiveDest = !Objects.equals(rawData.getCallingPartyNumber(), lastRedirectDn);
                } else {
                    isRedirectDifferentFromEffectiveDest = !Objects.equals(finalCalledPartyNum, lastRedirectDn);
                }

                if (isRedirectDifferentFromEffectiveDest) {
                    Integer redirectReason = rawData.getLastRedirectRedirectReason();
                    if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                        setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.NORMAL);
                    } else {
                        ImdexTransferCause autoCause = (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                                                       ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.AUTO_TRANSFER;
                        setImdexTransferCauseIfNotSet(rawData, autoCause);
                    }
                }
            }
        }
        
        // Apply final mobile redirect if applicable and no other transfer took precedence
        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) { // Or only if NO_TRANSFER or AUTO_TRANSFER? PHP logic is subtle.
            
            boolean isMobileRedirectDifferent;
            String currentEffectiveDest = rawData.isIncomingCall() ? rawData.getCallingPartyNumber() : finalCalledPartyNum;
            isMobileRedirectDifferent = !Objects.equals(currentEffectiveDest, rawData.getFinalMobileCalledPartyNumber());

            if(isMobileRedirectDifferent) {
                // This call is now effectively to the mobile number
                if (rawData.isIncomingCall()) {
                    rawData.setCallingPartyNumber(rawData.getFinalMobileCalledPartyNumber()); // The "caller" is now the mobile number
                    rawData.setCallingPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
                } else {
                    finalCalledPartyNum = rawData.getFinalMobileCalledPartyNumber();
                    finalCalledPartyPart = rawData.getDestMobileDeviceNamePartition();
                }
                setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.AUTO_TRANSFER); // Or a new specific mobile redirect cause
            }
        }

        // Set final effective numbers
        rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
        rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());
        rawData.setEffectiveDestinationNumber(finalCalledPartyNum);
        rawData.setEffectiveDestinationPartition(finalCalledPartyPart);
        
        // Final check from PHP: if conference and origin == destination, discard.
        if (isFinalCalledPartyConference && // Or any conference related transfer cause
            Objects.equals(rawData.getEffectiveOriginatingNumber(), rawData.getEffectiveDestinationNumber()) &&
            Objects.equals(rawData.getEffectiveOriginatingPartition(), rawData.getEffectiveDestinationPartition())) {
            log.warn("Conference call resulted in self-call after processing. CDR: {}", cdrLine);
            throw new CdrProcessingException("Conference resulted in self-call: " + cdrLine);
        }
        
        // Store the (potentially modified) lastRedirectDn and finalCalledPartyNum back into rawData
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);

        return rawData;
    }

    private String getColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        Integer index = columnMapping.get(key);
        if (index != null && index >= 0 && index < columns.length) {
            return CdrHelper.cleanString(columns[index]);
        }
        return "";
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        String val = getColumnValue(columns, columnMapping, key);
        if (!val.isEmpty() && CdrHelper.isNumeric(val)) {
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
        if (!val.isEmpty() && CdrHelper.isNumeric(val)) {
             try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                log.warn("Could not parse integer value for key '{}': {}", key, val);
            }
        }
        return null;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        return number.toLowerCase().startsWith(CONFERENCE_IDENTIFIER_PREFIX) &&
               number.length() > CONFERENCE_IDENTIFIER_PREFIX.length() &&
               CdrHelper.isNumeric(number.substring(CONFERENCE_IDENTIFIER_PREFIX.length()));
    }

    private boolean isInternalParty(String number, String partition, InternalExtensionLimitsDto limits) {
        return (partition != null && !partition.isEmpty()) && CdrHelper.isPotentialExtension(number, limits);
    }

    private boolean setImdexTransferCauseIfNotSet(RawCdrData rawData, ImdexTransferCause cause) {
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            rawData.setImdexTransferCause(cause);
            return true;
        }
        // Allow overwriting AUTO_TRANSFER with a more specific conference-related cause if it's PRE_CONFERENCE_NOW
        if (rawData.getImdexTransferCause() == ImdexTransferCause.AUTO_TRANSFER && cause == ImdexTransferCause.PRE_CONFERENCE_NOW) {
            rawData.setImdexTransferCause(cause);
            return true;
        }
        return false; // Cause already set to something other than NO_TRANSFER (or AUTO_TRANSFER if new cause is not PRE_CONFERENCE_NOW)
    }
}