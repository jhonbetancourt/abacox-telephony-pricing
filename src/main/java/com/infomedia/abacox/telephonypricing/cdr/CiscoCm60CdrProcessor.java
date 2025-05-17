// FILE: com/infomedia/abacox/telephonypricing/cdr/CiscoCm60CdrProcessor.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service("ciscoCm60CdrProcessor")
@Log4j2
@RequiredArgsConstructor
public class CiscoCm60CdrProcessor implements CdrProcessor {

    // Internal constants for expected Cisco CDR fields (keys for columnMapping)
    public static final String HDR_CDR_RECORD_TYPE = "cdrRecordType";
    public static final String HDR_CALLING_PARTY_NUMBER = "callingPartyNumber";
    public static final String HDR_CALLING_PARTY_NUMBER_PARTITION = "callingPartyNumberPartition";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER = "finalCalledPartyNumber";
    public static final String HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION = "finalCalledPartyNumberPartition";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER = "originalCalledPartyNumber";
    public static final String HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION = "originalCalledPartyNumberPartition";
    public static final String HDR_LAST_REDIRECT_DN = "lastRedirectDn";
    public static final String HDR_LAST_REDIRECT_DN_PARTITION = "lastRedirectDnPartition";
    public static final String HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER = "finalMobileCalledPartyNumber";
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destMobileDeviceName"; // Assuming this is the partition for finalMobileCalledPartyNumber

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
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia'] = strtoupper($cm_config['cdr_conferencia']);

    private final CdrConfigService cdrConfigService; // For limits

    @Override
    public String getCdrDelimiter() {
        return CDR_DELIMITER;
    }

    @Override
    public Map<String, Integer> establishColumnMapping(String headerLine) {
        Map<String, Integer> columnMapping = new HashMap<>();
        String[] headers = headerLine.split(CDR_DELIMITER);
        for (int i = 0; i < headers.length; i++) {
            String headerName = CdrHelper.cleanString(headers[i]);
            // This mapping should be comprehensive based on all fields used in CM_FormatoCDR
            // and CM_TipoPlanta in PHP.
            switch (headerName.toLowerCase()) { // Use toLowerCase for case-insensitive matching
                case "cdrrecordtype": columnMapping.put(HDR_CDR_RECORD_TYPE, i); break;
                case "callingpartynumber": columnMapping.put(HDR_CALLING_PARTY_NUMBER, i); break;
                case "callingpartynumberpartition": columnMapping.put(HDR_CALLING_PARTY_NUMBER_PARTITION, i); break;
                case "finalcalledpartynumber": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER, i); break;
                case "finalcalledpartynumberpartition": columnMapping.put(HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "originalcalledpartynumber": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER, i); break;
                case "originalcalledpartynumberpartition": columnMapping.put(HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION, i); break;
                case "lastredirectdn": columnMapping.put(HDR_LAST_REDIRECT_DN, i); break;
                case "lastredirectdnpartition": columnMapping.put(HDR_LAST_REDIRECT_DN_PARTITION, i); break;
                case "finalmobilecalledpartynumber": columnMapping.put(HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER, i); break;
                case "destmobiledevicename": columnMapping.put(HDR_DEST_MOBILE_DEVICE_NAME_PARTITION, i); break;
                case "datetimeorigination": columnMapping.put(HDR_DATETIME_ORIGINATION, i); break;
                case "datetimeconnect": columnMapping.put(HDR_DATETIME_CONNECT, i); break;
                case "datetimedisconnect": columnMapping.put(HDR_DATETIME_DISCONNECT, i); break;
                case "duration": columnMapping.put(HDR_DURATION, i); break;
                case "authcodedescription": columnMapping.put(HDR_AUTH_CODE_DESCRIPTION, i); break;
                case "lastredirectredirectreason": columnMapping.put(HDR_LAST_REDIRECT_REDIRECT_REASON, i); break;
                case "origdevicename": columnMapping.put(HDR_ORIG_DEVICE_NAME, i); break;
                case "destdevicename": columnMapping.put(HDR_DEST_DEVICE_NAME, i); break;
                case "joinonbehalfof": columnMapping.put(HDR_JOIN_ON_BEHALF_OF, i); break;
                case "destcallterminationonbehalfof": columnMapping.put(HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF, i); break;
                case "destconversationid": columnMapping.put(HDR_DEST_CONVERSATION_ID, i); break;
                case "globalcallid_callid": columnMapping.put(HDR_GLOBAL_CALL_ID_CALL_ID, i); break;
                case "origvideocap_codec": columnMapping.put(HDR_ORIG_VIDEO_CAP_CODEC, i); break;
                case "origvideocap_bandwidth": columnMapping.put(HDR_ORIG_VIDEO_CAP_BANDWIDTH, i); break;
                case "origvideocap_resolution": columnMapping.put(HDR_ORIG_VIDEO_CAP_RESOLUTION, i); break;
                case "destvideocap_codec": columnMapping.put(HDR_DEST_VIDEO_CAP_CODEC, i); break;
                case "destvideocap_bandwidth": columnMapping.put(HDR_DEST_VIDEO_CAP_BANDWIDTH, i); break;
                case "destvideocap_resolution": columnMapping.put(HDR_DEST_VIDEO_CAP_RESOLUTION, i); break;
                // Add all other mappings from PHP's CM_TipoPlanta and CM_ValidarCab
            }
        }
        // Ensure essential columns are present
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


    @Override
    public RawCiscoCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping) {
        String[] columns = cdrLine.split(CDR_DELIMITER);

        if (columns.length < columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0) + 1) {
            log.warn("CDR line has fewer columns ({}) than expected based on header. Line: {}", columns.length, cdrLine);
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

        rawData.setOrigVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_CODEC));
        rawData.setOrigVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_BANDWIDTH));
        rawData.setOrigVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_RESOLUTION));
        rawData.setDestVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_CODEC));
        rawData.setDestVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_BANDWIDTH));
        rawData.setDestVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_RESOLUTION));

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

        if (dateTimeConnectEpoch == null || dateTimeConnectEpoch == 0) {
            rawData.setDuration(0);
        }

        // --- Start of logic adapted from CM_FormatoCDR in include_cm.php ---
        // Preserve initial values for logic
        String finalCalledPartyNum = rawData.getFinalCalledPartyNumber();
        String finalCalledPartyPart = rawData.getFinalCalledPartyNumberPartition();
        String origCalledPartyNum = rawData.getOriginalCalledPartyNumber();
        String origCalledPartyPart = rawData.getOriginalCalledPartyNumberPartition();
        String lastRedirectDn = rawData.getLastRedirectDn();
        String lastRedirectDnPart = rawData.getLastRedirectDnPartition();
        String extRedirCc = ""; // PHP's ext-redir-cc for preserving original lastRedirectDn

        boolean isLastRedirectDnConference = isConferenceIdentifier(lastRedirectDn);

        if (finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = origCalledPartyNum;
            finalCalledPartyPart = origCalledPartyPart;
        } else if (!origCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(origCalledPartyNum)) {
            if (!isLastRedirectDnConference) {
                extRedirCc = lastRedirectDn;
                lastRedirectDn = origCalledPartyNum;
                lastRedirectDnPart = origCalledPartyPart;
            }
        }
        // Update rawData with potentially modified values before further logic
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);

        boolean isFinalCalledPartyConference = isConferenceIdentifier(finalCalledPartyNum);
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunks = true;

        if (isFinalCalledPartyConference) {
            rawData.setImdexTransferCause(
                (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                ImdexTransferCause.PRE_CONFERENCE_NOW :
                ImdexTransferCause.CONFERENCE
            );

            if (!isConferenceIdentifier(lastRedirectDn)) {
                String originalFinalCalledNumForConference = finalCalledPartyNum;
                String originalFinalCalledPartForConference = finalCalledPartyPart;

                finalCalledPartyNum = lastRedirectDn;
                finalCalledPartyPart = lastRedirectDnPart;

                lastRedirectDn = originalFinalCalledNumForConference;
                lastRedirectDnPart = originalFinalCalledPartForConference;

                if (rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    if (extRedirCc != null && extRedirCc.toLowerCase().startsWith("c")) {
                         lastRedirectDn = extRedirCc;
                    } else if (rawData.getDestConversationId() != null) {
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
        } else if (isConferenceIdentifier(lastRedirectDn)) {
            if (setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.CONFERENCE_END)) {
                 invertTrunks = false;
            }
        }
        // Update rawData again after conference logic
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);

        // Determine incoming/outgoing
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        boolean isOriginInternalParty = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
        boolean isDestinationInternalParty = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), limits);

        if (isFinalCalledPartyConference) {
            rawData.setIncomingCall(
                (rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                !CdrHelper.isPotentialExtension(rawData.getFinalCalledPartyNumber(), limits)
            );
        } else if (!isOriginInternalParty && (isDestinationInternalParty || isInternalParty(rawData.getLastRedirectDn(), rawData.getLastRedirectDnPartition(), limits))) {
            // If origin is external, and destination (or redirected dest) is internal, it's incoming
            rawData.setIncomingCall(true);
            // PHP inverts calling/called for incoming calls
            String tempNum = rawData.getCallingPartyNumber();
            String tempPart = rawData.getCallingPartyNumberPartition();
            rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
            rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
            rawData.setFinalCalledPartyNumber(tempNum);
            rawData.setFinalCalledPartyNumberPartition(tempPart);
        } else {
            rawData.setIncomingCall(false);
        }

        if (isFinalCalledPartyConference && !rawData.isIncomingCall() && invertTrunks) {
            String tempTrunk = rawData.getOrigDeviceName();
            rawData.setOrigDeviceName(rawData.getDestDeviceName());
            rawData.setDestDeviceName(tempTrunk);
        }

        Integer redirectReason = rawData.getLastRedirectRedirectReason();
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.NORMAL);
            } else if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
                boolean isRedirectDifferent = rawData.isIncomingCall() ?
                        !Objects.equals(rawData.getCallingPartyNumber(), rawData.getLastRedirectDn()) :
                        !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getLastRedirectDn());

                if (isRedirectDifferent) {
                     setImdexTransferCauseIfNotSet(rawData,
                        (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                        ImdexTransferCause.PRE_CONFERENCE_NOW :
                        ImdexTransferCause.AUTO_TRANSFER
                    );
                }
            }
        }

        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            
            boolean isMobileRedirectDifferent = rawData.isIncomingCall() ?
                !Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalMobileCalledPartyNumber()) :
                !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getFinalMobileCalledPartyNumber());

            if(isMobileRedirectDifferent) {
                if (rawData.isIncomingCall()) {
                    // If incoming, the call was to callingPartyNumber (internal), which then forwarded to mobile.
                    // The "final destination" from the system's perspective of who *received* the call is still the internal extension.
                    // The mobile number becomes a new "leg" or a different type of information.
                    // PHP logic seems to overwrite finalCalledPartyNumber. This needs careful thought.
                    // For now, let's assume the intent is to record the mobile number as the ultimate destination.
                    rawData.setEffectiveDestinationNumber(rawData.getFinalMobileCalledPartyNumber());
                    rawData.setEffectiveDestinationPartition(rawData.getDestMobileDeviceNamePartition());
                } else { // Outgoing call
                    rawData.setEffectiveDestinationNumber(rawData.getFinalMobileCalledPartyNumber());
                    rawData.setEffectiveDestinationPartition(rawData.getDestMobileDeviceNamePartition());
                }
                setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.AUTO_TRANSFER);
            }
        }

        // Set effective numbers if not already set by mobile redirect
        if (rawData.getEffectiveDestinationNumber() == null) {
            rawData.setEffectiveDestinationNumber(rawData.getFinalCalledPartyNumber());
            rawData.setEffectiveDestinationPartition(rawData.getFinalCalledPartyNumberPartition());
        }
        rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
        rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());


        if (isFinalCalledPartyConference &&
            Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            log.warn("Conference call resulted in self-call after processing. CDR: {}", cdrLine);
            throw new CdrProcessingException("Conference resulted in self-call: " + cdrLine);
        }
        return rawData;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        // PHP: strtoupper($cm_config['cdr_conferencia']) == $inicio_dial
        // Assuming CONFERENCE_IDENTIFIER_PREFIX is already uppercase if needed, or use .toLowerCase()
        return number.toLowerCase().startsWith(CONFERENCE_IDENTIFIER_PREFIX) &&
               number.length() > CONFERENCE_IDENTIFIER_PREFIX.length() &&
               CdrHelper.isNumeric(number.substring(CONFERENCE_IDENTIFIER_PREFIX.length()));
    }

    private boolean isInternalParty(String number, String partition, InternalExtensionLimitsDto limits) {
        // PHP: ($info_arr['partorigen'] != '' && ExtensionPosible($info_arr['ext']))
        return (partition != null && !partition.isEmpty()) && CdrHelper.isPotentialExtension(number, limits);
    }

    private boolean setImdexTransferCauseIfNotSet(RawCiscoCdrData rawData, ImdexTransferCause cause) {
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            rawData.setImdexTransferCause(cause);
            return true;
        }
        return false;
    }
}