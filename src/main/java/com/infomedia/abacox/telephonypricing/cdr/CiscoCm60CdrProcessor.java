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

    // Constants for expected Cisco CDR fields (keys for columnMapping)
    // ... (same as previous response)
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
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
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
                case "destmobiledevicename": columnMapping.put(HDR_DEST_MOBILE_DEVICE_NAME_PARTITION, i); break; // This is likely the partition for finalMobileCalledPartyNumber
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
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        Integer index = columnMapping.get(key);
        if (index != null && index >= 0 && index < columns.length) {
            return CdrHelper.cleanString(columns[index]);
        }
        return "";
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String key) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
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
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
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
        String[] columns = cdrLine.split(CDR_DELIMITER, -1); // Use -1 to keep trailing empty strings

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
        
        // Initial population of final/original/redirected numbers and partitions
        String finalCalledPartyNum = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER);
        String finalCalledPartyPart = getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION);
        String origCalledPartyNum = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER);
        String origCalledPartyPart = getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION);
        String lastRedirectDn = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN);
        String lastRedirectDnPart = getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION);
        String finalMobileCalledPartyNum = getColumnValue(columns, columnMapping, HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER);
        String destMobileDeviceNamePart = getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME_PARTITION);

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

        // Ring time calculation
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

        // --- Logic from PHP's CM_FormatoCDR for Cisco CallManager specific field interpretation ---
        String extRedirCcPreserved = lastRedirectDn; // Preserve original lastRedirectDn for specific conference logic

        boolean isLastRedirectDnConference = isConferenceIdentifier(lastRedirectDn);

        if (finalCalledPartyNum.isEmpty()) {
            finalCalledPartyNum = origCalledPartyNum;
            finalCalledPartyPart = origCalledPartyPart;
        } else if (!origCalledPartyNum.isEmpty() && !finalCalledPartyNum.equals(origCalledPartyNum)) {
            if (!isLastRedirectDnConference) {
                // extRedirCcPreserved is already set
                lastRedirectDn = origCalledPartyNum;
                lastRedirectDnPart = origCalledPartyPart;
            }
        }

        boolean isFinalCalledPartyConference = isConferenceIdentifier(finalCalledPartyNum);
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true; // Corresponds to PHP's $invertir_troncales

        if (isFinalCalledPartyConference) {
            rawData.setImdexTransferCause(
                (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                ImdexTransferCause.PRE_CONFERENCE_NOW :
                ImdexTransferCause.CONFERENCE
            );

            if (!isConferenceIdentifier(lastRedirectDn)) { // If lastRedirectDn is not itself a conference ID
                String originalFinalCalledNumForConference = finalCalledPartyNum;
                String originalFinalCalledPartForConference = finalCalledPartyPart;

                finalCalledPartyNum = lastRedirectDn;
                finalCalledPartyPart = lastRedirectDnPart;

                lastRedirectDn = originalFinalCalledNumForConference; // This is now the 'bXXXX' conference ID
                lastRedirectDnPart = originalFinalCalledPartForConference;

                if (rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    if (extRedirCcPreserved != null && extRedirCcPreserved.toLowerCase().startsWith("c")) {
                         lastRedirectDn = extRedirCcPreserved;
                    } else if (rawData.getDestConversationId() != null) {
                         lastRedirectDn = "i" + rawData.getDestConversationId();
                    }
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) { // If not "Join On Behalf Of" type 7
                // Swap calling party with (new) final called party
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(finalCalledPartyNum);
                rawData.setCallingPartyNumberPartition(finalCalledPartyPart);
                finalCalledPartyNum = tempNum;
                finalCalledPartyPart = tempPart;
            }
        } else if (isConferenceIdentifier(lastRedirectDn)) { // End of conference leg
            if (setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.CONFERENCE_END)) {
                 invertTrunksForConference = false;
            }
        }
        
        // Update rawData with potentially modified numbers/partitions from conference logic
        rawData.setFinalCalledPartyNumber(finalCalledPartyNum);
        rawData.setFinalCalledPartyNumberPartition(finalCalledPartyPart);
        rawData.setLastRedirectDn(lastRedirectDn);
        rawData.setLastRedirectDnPartition(lastRedirectDnPart);
        rawData.setFinalMobileCalledPartyNumber(finalMobileCalledPartyNum);
        rawData.setDestMobileDeviceNamePartition(destMobileDeviceNamePart);


        // Determine incoming/outgoing based on PHP logic
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        boolean isOriginInternalParty = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
        boolean isDestinationInternalParty = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), limits);
        
        rawData.setIncomingCall(false); // Default to outgoing

        if (isFinalCalledPartyConference) {
            // Incoming if effective final destination (after conference logic swaps) is external-like
            // and its partition is empty.
            rawData.setIncomingCall(
                (rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                !CdrHelper.isPotentialExtension(rawData.getFinalCalledPartyNumber(), limits)
            );
        } else if (!isOriginInternalParty) { // Origin is not an internal party
            boolean isRedirectedDestInternal = isInternalParty(rawData.getLastRedirectDn(), rawData.getLastRedirectDnPartition(), limits);
            if (isDestinationInternalParty || isRedirectedDestInternal) {
                rawData.setIncomingCall(true);
                // PHP: InvertirLlamada - swap callingParty and finalCalledParty
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
                rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
                rawData.setFinalCalledPartyNumber(tempNum);
                rawData.setFinalCalledPartyNumberPartition(tempPart);
            }
        }
        // If call is still considered outgoing, and origin was internal but destination was not, it remains outgoing.
        // If both origin and destination are internal, it's an internal-to-internal call (treated as outgoing by default here, EnrichmentService refines).

        // Trunk inversion for conferences
        if (isFinalCalledPartyConference && !rawData.isIncomingCall() && invertTrunksForConference) {
            String tempTrunk = rawData.getOrigDeviceName();
            rawData.setOrigDeviceName(rawData.getDestDeviceName());
            rawData.setDestDeviceName(tempTrunk);
        }

        // Set ImdexTransferCause based on lastRedirectRedirectReason and other conditions
        Integer redirectReason = rawData.getLastRedirectRedirectReason();
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) { // Only if not already set by conference logic
            if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) { // Cisco defined redirect reasons
                setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.NORMAL);
            } else if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
                // Check if the redirect destination is different from the effective final destination
                boolean isRedirectDifferent;
                if (rawData.isIncomingCall()) { // For incoming, compare effective origin (now in callingParty) to lastRedirectDn
                    isRedirectDifferent = !Objects.equals(rawData.getCallingPartyNumber(), rawData.getLastRedirectDn());
                } else { // For outgoing, compare effective destination (finalCalled) to lastRedirectDn
                    isRedirectDifferent = !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getLastRedirectDn());
                }

                if (isRedirectDifferent) {
                     setImdexTransferCauseIfNotSet(rawData,
                        (rawData.getDestCallTerminationOnBehalfOf() != null && rawData.getDestCallTerminationOnBehalfOf() == 7) ?
                        ImdexTransferCause.PRE_CONFERENCE_NOW : // This might be specific to joinOnBehalfOf, check PHP
                        ImdexTransferCause.AUTO_TRANSFER
                    );
                }
            }
        }

        // Mobile redirect logic (fn:CFMobil, etc. - not explicitly in fields, but logic implies if finalMobile fields are populated)
        if (rawData.getFinalMobileCalledPartyNumber() != null && !rawData.getFinalMobileCalledPartyNumber().isEmpty() &&
            rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) { // Only if not already a transfer/conference
            
            boolean isMobileRedirectDifferent;
            if (rawData.isIncomingCall()) {
                isMobileRedirectDifferent = !Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalMobileCalledPartyNumber());
            } else {
                isMobileRedirectDifferent = !Objects.equals(rawData.getFinalCalledPartyNumber(), rawData.getFinalMobileCalledPartyNumber());
            }

            if(isMobileRedirectDifferent) {
                // The number that ultimately handled the call is the mobile number.
                // For pricing, this mobile number becomes the key destination.
                // PHP's InvertirLlamada logic is complex here.
                // If incoming, callingParty was external, finalCalled was internal. If finalCalled forwarded to mobile,
                // the "true" destination for pricing is mobile.
                // If outgoing, callingParty was internal, finalCalled was external. If finalCalled forwarded to mobile,
                // the "true" destination is mobile.
                // Essentially, finalMobileCalledPartyNumber becomes the new effective destination.
                rawData.setEffectiveDestinationNumber(rawData.getFinalMobileCalledPartyNumber());
                rawData.setEffectiveDestinationPartition(rawData.getDestMobileDeviceNamePartition()); // Assuming this is its partition
                setImdexTransferCauseIfNotSet(rawData, ImdexTransferCause.AUTO_TRANSFER); // Or a specific "MOBILE_REDIRECT"
            }
        }


        // Set effective numbers for enrichment if not already overridden by mobile redirect
        if (rawData.getEffectiveDestinationNumber() == null) {
            rawData.setEffectiveDestinationNumber(rawData.getFinalCalledPartyNumber());
            rawData.setEffectiveDestinationPartition(rawData.getFinalCalledPartyNumberPartition());
        }
        rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
        rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());

        // Final check for conference self-call (PHP logic)
        if (isFinalCalledPartyConference && // Only if it was initially a conference type
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
        return number.toLowerCase().startsWith(CONFERENCE_IDENTIFIER_PREFIX) &&
               number.length() > CONFERENCE_IDENTIFIER_PREFIX.length() &&
               CdrHelper.isNumeric(number.substring(CONFERENCE_IDENTIFIER_PREFIX.length()));
    }

    private boolean isInternalParty(String number, String partition, InternalExtensionLimitsDto limits) {
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