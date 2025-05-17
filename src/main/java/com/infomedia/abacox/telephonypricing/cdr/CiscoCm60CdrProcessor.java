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
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destMobileDeviceName";

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
            }
        }
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
        if (index != null && index >= 0 && index < columns.length) { // Ensure index is valid
            return CdrHelper.cleanString(columns[index]);
        }
        return ""; // Return empty string for missing optional fields, consistent with PHP's trim
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
        String finalCalledPartyNum = rawData.getFinalCalledPartyNumber();
        String finalCalledPartyPart = rawData.getFinalCalledPartyNumberPartition();
        String origCalledPartyNum = rawData.getOriginalCalledPartyNumber();
        String origCalledPartyPart = rawData.getOriginalCalledPartyNumberPartition();
        String lastRedirectDn = rawData.getLastRedirectDn(); // This will be reassigned
        String lastRedirectDnPart = rawData.getLastRedirectDnPartition(); // This will be reassigned
        String extRedirCc = ""; // PHP's ext-redir-cc for preserving original lastRedirectDn

        boolean isLastRedirectDnConference = isConferenceIdentifier(lastRedirectDn);

        if (finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = origCalledPartyNum;
            finalCalledPartyPart = origCalledPartyPart;
        } else if (!origCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(origCalledPartyNum)) {
            if (!isLastRedirectDnConference) {
                extRedirCc = lastRedirectDn; // Preserve original lastRedirectDn if it's not a conference ID
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
        boolean invertTrunks = true; // Flag from PHP logic

        if (isFinalCalledPartyConference) {
            rawData.setImdexTransferCause(
                (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                ImdexTransferCause.PRE_CONFERENCE_NOW :
                ImdexTransferCause.CONFERENCE
            );

            if (!isConferenceIdentifier(lastRedirectDn)) { // If lastRedirectDn is not a conference ID itself
                String originalFinalCalled = finalCalledPartyNum;
                String originalFinalCalledPart = finalCalledPartyPart;

                finalCalledPartyNum = lastRedirectDn; // lastRedirectDn becomes the new "final" number
                finalCalledPartyPart = lastRedirectDnPart;

                // Store the conference bridge ID (original finalCalledPartyNum) in lastRedirectDn for later reference
                lastRedirectDn = originalFinalCalled;
                lastRedirectDnPart = originalFinalCalledPart;


                if (rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    if (extRedirCc != null && extRedirCc.toLowerCase().startsWith("c")) {
                         lastRedirectDn = extRedirCc; // Use preserved if it starts with 'c'
                    } else if (rawData.getDestConversationId() != null) {
                         lastRedirectDn = "i" + rawData.getDestConversationId();
                    }
                }
            }
            // PHP: if ($cdr_motivo_union != "7") { _invertir(...) }
            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) {
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(finalCalledPartyNum); // which is old lastRedirectDn
                rawData.setCallingPartyNumberPartition(finalCalledPartyPart); // which is old lastRedirectDnPartition
                finalCalledPartyNum = tempNum;
                finalCalledPartyPart = tempPart;
            }
        } else if (isConferenceIdentifier(lastRedirectDn)) { // Redirected FROM a conference
            rawData.setImdexTransferCause(ImdexTransferCause.CONFERENCE_END);
            invertTrunks = false; // PHP: $invertir_troncales = false;
        }
        // Update rawData again after conference logic
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);


        // Determine incoming/outgoing (simplified from PHP's complex partition/ExtensionPosible checks)
        InternalExtensionLimitsDto limits = commLocation.getIndicator() != null ?
                                            cdrConfigService.getInternalExtensionLimits(commLocation.getIndicator().getOriginCountryId(), commLocation.getId()) :
                                            InternalExtensionLimitsDto.defaultLimits();

        boolean isOriginInternalParty = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
        boolean isDestinationInternalParty = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), limits);

        if (isFinalCalledPartyConference) {
            // If final called is conference, incoming if its partition is empty AND it's not an extension number
            rawData.setIncomingCall(
                (rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                !CdrHelper.isPotentialExtension(rawData.getFinalCalledPartyNumber(), limits)
            );
        } else if (!isOriginInternalParty && isDestinationInternalParty) {
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

        // If it was a conference and not determined as incoming, PHP inverts trunks
        if (isFinalCalledPartyConference && !rawData.isIncomingCall() && invertTrunks) {
            String tempTrunk = rawData.getOrigDeviceName();
            rawData.setOrigDeviceName(rawData.getDestDeviceName());
            rawData.setDestDeviceName(tempTrunk);
        }

        // General transfer logic (if not already handled by conference)
        Integer redirectReason = rawData.getLastRedirectRedirectReason();
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                rawData.setImdexTransferCause(ImdexTransferCause.NORMAL);
            } else if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
                // Check if lastRedirectDn is different from the effective final destination
                boolean isRedirectDifferent = rawData.isIncomingCall() ?
                        !Objects.equals(rawData.getCallingPartyNumber(), rawData.getLastRedirectDn()) :
                        !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getLastRedirectDn());

                if (isRedirectDifferent) {
                     rawData.setImdexTransferCause(
                        (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                        ImdexTransferCause.PRE_CONFERENCE_NOW :
                        ImdexTransferCause.AUTO_TRANSFER
                    );
                }
            }
        }

        // Mobile redirect handling
        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            
            boolean isMobileRedirectDifferent = rawData.isIncomingCall() ?
                !Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalMobileCalledPartyNumber()) :
                !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getFinalMobileCalledPartyNumber());

            if(isMobileRedirectDifferent) {
                // The call effectively went to/from the mobile number
                if (rawData.isIncomingCall()) {
                    // Original caller was mobile, final internal recipient is in callingPartyNumber after initial swap
                    // This case means the call *from* mobile was redirected *to* callingPartyNumber.
                    // So, the effective "caller" is finalMobileCalledPartyNumber.
                    // The PHP logic here is a bit dense. Re-evaluating:
                    // If incoming, callingPartyNumber is the internal extension, finalCalledPartyNumber is external.
                    // If this was then redirected to mobile, it means the internal extension (callingPartyNumber)
                    // forwarded the call to finalMobileCalledPartyNumber.
                    // So, the call effectively becomes an *outgoing* call from callingPartyNumber to finalMobileCalledPartyNumber.
                    // This is complex. For now, let's assume the PHP logic's intent was to update the *external* party.
                    rawData.setFinalCalledPartyNumber(rawData.getFinalMobileCalledPartyNumber());
                    rawData.setFinalCalledPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
                } else { // Outgoing call
                    // Original internal caller (callingPartyNumber) called an external number (finalCalledPartyNumber),
                    // which then redirected to finalMobileCalledPartyNumber.
                    rawData.setFinalCalledPartyNumber(rawData.getFinalMobileCalledPartyNumber());
                    rawData.setFinalCalledPartyNumberPartition(rawData.getDestMobileDeviceNamePartition());
                }
                rawData.setImdexTransferCause(ImdexTransferCause.AUTO_TRANSFER); // Or a specific mobile transfer type
            }
        }

        // Final check for conference calls that might look like self-calls
        if (isFinalCalledPartyConference &&
            Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            log.warn("Conference call resulted in self-call after processing. CDR: {}", cdrLine);
            // This might indicate an issue or a specific scenario not fully captured.
            // For now, we let it pass, but it could be marked for review or an error.
            throw new CdrProcessingException("Conference resulted in self-call: " + cdrLine);
        }
        return rawData;
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
}