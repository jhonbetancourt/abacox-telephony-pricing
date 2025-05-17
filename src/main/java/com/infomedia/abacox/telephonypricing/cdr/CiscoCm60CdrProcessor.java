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
    // These are the "logical" field names we expect. The establishColumnMapping will map these
    // to actual column names found in the CDR header.
    public static final String HDR_CDR_RECORD_TYPE = "cdrrecordtype";
    public static final String HDR_GLOBAL_CALL_ID_CALL_MANAGER_ID = "globalcallid_callmanagerid";
    public static final String HDR_GLOBAL_CALL_ID_CALL_ID = "globalcallid_callid";
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
    public static final String HDR_DEST_CONVERSATION_ID = "destconversationid";
    public static final String HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER = "finalmobilecalledpartynumber";
    public static final String HDR_DEST_MOBILE_DEVICE_NAME = "destmobiledevicename";

    public static final String HDR_ORIG_VIDEO_CAP_CODEC = "origvideocap_codec";
    public static final String HDR_ORIG_VIDEO_CAP_BANDWIDTH = "origvideocap_bandwidth";
    public static final String HDR_ORIG_VIDEO_CAP_RESOLUTION = "origvideocap_resolution";
    public static final String HDR_DEST_VIDEO_CAP_CODEC = "destvideocap_codec";
    public static final String HDR_DEST_VIDEO_CAP_BANDWIDTH = "destvideocap_bandwidth";
    public static final String HDR_DEST_VIDEO_CAP_RESOLUTION = "destvideocap_resolution";

    private static final String CDR_DELIMITER = ",";
    // PHP: $cm_config['cdr_conferencia'] = strtoupper($cm_config['cdr_conferencia']); -> default 'b'
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
            columnMapping.put(CdrHelper.cleanString(headers[i]).toLowerCase(), i);
        }

        // Check for essential headers
        if (!columnMapping.containsKey(HDR_CALLING_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_FINAL_CALLED_PARTY_NUMBER) ||
            !columnMapping.containsKey(HDR_DATETIME_ORIGINATION) ||
            !columnMapping.containsKey(HDR_DURATION)) {
            log.error("Essential Cisco CDR header columns are missing. Parsed Headers: {}", columnMapping.keySet());
            throw new CdrProcessingException("Essential Cisco CDR header columns are missing.");
        }
        log.info("Cisco CDR Column Mapping Established: {}", columnMapping);
        return columnMapping;
    }

    @Override
    public RawCdrData parseCdrLine(String cdrLine, CommunicationLocation commLocation, Map<String, Integer> columnMapping) {
        String[] columns = cdrLine.split(CDR_DELIMITER, -1);

        int maxExpectedIndex = columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0);
        if (columns.length <= maxExpectedIndex) {
            throw new CdrProcessingException("CDR line column count (" + columns.length + ") mismatch with header (expected at least " + (maxExpectedIndex + 1) + "). Line: " + cdrLine);
        }

        RawCdrData rawData = new RawCdrData();
        rawData.setOriginalLine(cdrLine);

        // --- Step 1: Parse all relevant raw fields and store originals ---
        Long dateTimeOriginationEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_ORIGINATION);
        Long dateTimeConnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_CONNECT);
        Long dateTimeDisconnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_DISCONNECT);

        rawData.setDateTimeOrigination(CdrHelper.epochToLocalDateTime(dateTimeOriginationEpoch));
        rawData.setDateTimeConnect(CdrHelper.epochToLocalDateTime(dateTimeConnectEpoch));
        rawData.setDateTimeDisconnect(CdrHelper.epochToLocalDateTime(dateTimeDisconnectEpoch));

        // Store pristine original values from CDR
        rawData.setOriginal_callingPartyNumber(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER));
        rawData.setOriginal_callingPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER_PARTITION).toUpperCase());
        rawData.setOriginal_finalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER));
        rawData.setOriginal_finalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase());
        rawData.setOriginal_originalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER));
        rawData.setOriginal_originalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase());
        rawData.setOriginal_lastRedirectDn(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN));
        rawData.setOriginal_lastRedirectDnPartition(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION).toUpperCase());
        rawData.setOriginal_finalMobileCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER));
        rawData.setOriginal_destMobileDeviceName(getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME).toUpperCase());

        // Initialize current working fields with originals
        rawData.setCallingPartyNumber(rawData.getOriginal_callingPartyNumber());
        rawData.setCallingPartyNumberPartition(rawData.getOriginal_callingPartyNumberPartition());
        rawData.setFinalCalledPartyNumber(rawData.getOriginal_finalCalledPartyNumber());
        rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_finalCalledPartyNumberPartition());
        rawData.setLastRedirectDn(rawData.getOriginal_lastRedirectDn());
        rawData.setLastRedirectDnPartition(rawData.getOriginal_lastRedirectDnPartition());
        rawData.setFinalMobileCalledPartyNumber(rawData.getOriginal_finalMobileCalledPartyNumber());
        rawData.setDestMobileDeviceName(rawData.getOriginal_destMobileDeviceName());

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

        // --- Step 2: Calculate ring time, adjust duration if call never connected ---
        if (dateTimeConnectEpoch != null && dateTimeConnectEpoch > 0 && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeConnectEpoch - dateTimeOriginationEpoch));
        } else if (dateTimeDisconnectEpoch != null && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeDisconnectEpoch - dateTimeOriginationEpoch));
            rawData.setDuration(0); // Call never connected, duration is 0
        }
        if (rawData.getRingTime() != null && rawData.getRingTime() < 0) rawData.setRingTime(0);

        // --- Step 3: If current finalCalledPartyNumber is empty, use original_originalCalledPartyNumber ---
        if (rawData.getFinalCalledPartyNumber().isEmpty()) {
            rawData.setFinalCalledPartyNumber(rawData.getOriginal_originalCalledPartyNumber());
            rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_originalCalledPartyNumberPartition());
        }
        // --- Step 4: If current finalCalledPartyNumber differs from original_originalCalledPartyNumber,
        // and original_lastRedirectDn was not a conference ID, update current lastRedirectDn to original_originalCalledPartyNumber
        else if (!rawData.getOriginal_originalCalledPartyNumber().isEmpty() &&
                 !rawData.getFinalCalledPartyNumber().equals(rawData.getOriginal_originalCalledPartyNumber())) {
            // Use original_lastRedirectDn for this check as per PHP's $esconf_redir
            if (!isConferenceIdentifier(rawData.getOriginal_lastRedirectDn())) {
                rawData.setLastRedirectDn(rawData.getOriginal_originalCalledPartyNumber());
                rawData.setLastRedirectDnPartition(rawData.getOriginal_originalCalledPartyNumberPartition());
            }
        }

        // --- Step 5 & 6: Conference and Redirect Logic ---
        boolean isCurrentFinalCalledPartyConference = isConferenceIdentifier(rawData.getFinalCalledPartyNumber());
        boolean isOriginalLastRedirectDnConference = isConferenceIdentifier(rawData.getOriginal_lastRedirectDn());
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true;

        if (isCurrentFinalCalledPartyConference) {
            ImdexTransferCause confCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                                           ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.CONFERENCE;
            updateImdexTransferCause(rawData, confCause);

            if (!isOriginalLastRedirectDnConference) {
                String tempConfId = rawData.getFinalCalledPartyNumber();
                String tempConfPart = rawData.getFinalCalledPartyNumberPartition();

                rawData.setFinalCalledPartyNumber(rawData.getLastRedirectDn());
                rawData.setFinalCalledPartyNumberPartition(rawData.getLastRedirectDnPartition());

                rawData.setLastRedirectDn(tempConfId);
                rawData.setLastRedirectDnPartition(tempConfPart);

                if (confCause == ImdexTransferCause.PRE_CONFERENCE_NOW && rawData.getDestConversationId() != null && rawData.getDestConversationId() > 0) {
                    rawData.setLastRedirectDn("i" + rawData.getDestConversationId());
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) { // If not PRE_CONFERENCE_NOW
                swapCallingAndFinalCalled(rawData);
            }
        } else {
            boolean isCurrentWorkingLastRedirectDnConference = isConferenceIdentifier(rawData.getLastRedirectDn());
            if (isCurrentWorkingLastRedirectDnConference) {
                if (updateImdexTransferCause(rawData, ImdexTransferCause.CONFERENCE_END)) {
                    invertTrunksForConference = false;
                }
            }
        }

        // --- Step 7: Self-Call Discard (after conference processing might have swapped numbers) ---
        if (Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            log.warn("Self-call detected after conference processing. CDR: {}", cdrLine);
            throw new CdrProcessingException("Self-call detected: " + cdrLine);
        }

        // --- Step 8: Incoming Call Detection and Trunk Swapping ---
        rawData.setIncomingCall(false); // Default to outgoing
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        boolean isConferenceRelated = rawData.getImdexTransferCause() == ImdexTransferCause.CONFERENCE ||
                                      rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW ||
                                      rawData.getImdexTransferCause() == ImdexTransferCause.CONFERENCE_END;

        if (isConferenceRelated) {
            boolean isEffectivelyIncomingForConference =
                (rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                (rawData.getFinalCalledPartyNumber().isEmpty() || !CdrHelper.isPotentialExtension(rawData.getFinalCalledPartyNumber(), limits));

            if (isEffectivelyIncomingForConference) {
                rawData.setIncomingCall(true);
            } else if (invertTrunksForConference) {
                swapTrunks(rawData);
            }
        } else {
            boolean isOriginInternal = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
            boolean isDestinationInternal = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), limits);
            boolean isRedirectInternal = isInternalParty(rawData.getLastRedirectDn(), rawData.getLastRedirectDnPartition(), limits);

            if (!isOriginInternal && (isDestinationInternal || isRedirectInternal)) {
                rawData.setIncomingCall(true);
                swapCallingAndFinalCalled(rawData);
            }
        }

        // --- Step 9: Final Transfer Cause (for non-conference redirects) ---
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
                String effectiveDestForTransferCheck = rawData.isIncomingCall() ?
                                                       rawData.getCallingPartyNumber() :
                                                       rawData.getFinalCalledPartyNumber();

                if (!Objects.equals(effectiveDestForTransferCheck, rawData.getLastRedirectDn())) {
                    Integer redirectReason = rawData.getLastRedirectRedirectReason();
                    if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                        updateImdexTransferCause(rawData, ImdexTransferCause.NORMAL);
                    } else {
                        Integer destTerminationCode = rawData.getDestCallTerminationOnBehalfOf();
                        ImdexTransferCause autoCause = (destTerminationCode != null && destTerminationCode == 7) ?
                                                       ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.AUTO_TRANSFER;
                        updateImdexTransferCause(rawData, autoCause);
                    }
                }
            }
        }

        // --- Step 10: Mobile Redirect Override ---
        // Logic from tp_cisco_cm_60.php for finalMobileCalledPartyNumber
        // If a call is redirected to a mobile number, this CDR leg represents the call from the forwarding party to the mobile.
        if (rawData.getOriginal_finalMobileCalledPartyNumber() != null &&
            !rawData.getOriginal_finalMobileCalledPartyNumber().isEmpty() &&
            !rawData.getOriginal_finalMobileCalledPartyNumber().equals(rawData.getFinalCalledPartyNumber())) {

            log.debug("Mobile redirect detected. Original finalCalled: {}/{}, Mobile Dest: {}/{}",
                rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(),
                rawData.getOriginal_finalMobileCalledPartyNumber(), rawData.getOriginal_destMobileDeviceName());

            // The 'callingParty' for this leg is the party that was originally called and is now forwarding.
            // At this stage of parsing, rawData.getFinalCalledPartyNumber() holds the number of the party that is forwarding.
            rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
            rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());

            // The 'finalCalledParty' for this leg is the mobile number.
            rawData.setFinalCalledPartyNumber(rawData.getOriginal_finalMobileCalledPartyNumber());
            rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_destMobileDeviceName()); // Use mobile's "partition" (device name)

            rawData.setIncomingCall(false); // This leg (to the mobile) is outgoing from the PBX.
            // The 'interna' flag is determined by EnrichmentService based on these updated numbers.
            updateImdexTransferCause(rawData, ImdexTransferCause.AUTO_TRANSFER);

            log.debug("After mobile redirect logic. New calling: {}/{}, New final: {}/{}",
                rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(),
                rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition());
        }


        // --- Step 11: Set Final Effective Numbers in rawData ---
        // These are the numbers that the EnrichmentService will use.
        rawData.setEffectiveOriginatingNumber(rawData.getCallingPartyNumber());
        rawData.setEffectiveOriginatingPartition(rawData.getCallingPartyNumberPartition());
        rawData.setEffectiveDestinationNumber(rawData.getFinalCalledPartyNumber());
        rawData.setEffectiveDestinationPartition(rawData.getFinalCalledPartyNumberPartition());

        return rawData;
    }

    private String getColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        Integer index = columnMapping.get(headerKey.toLowerCase());
        if (index != null && index >= 0 && index < columns.length) {
            return CdrHelper.cleanString(columns[index]);
        }
        return "";
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
            try {
                String numericPart = val.replaceAll("[^0-9-]", "");
                if (!numericPart.isEmpty() && !"-".equals(numericPart)) return Long.parseLong(numericPart);
            } catch (NumberFormatException e) {
                log.warn("NFE for long key '{}': val '{}', original value from CDR '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
            }
        }
        return null;
    }

    private Integer getIntColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
             try {
                String numericPart = val.replaceAll("[^0-9-]", "");
                if (!numericPart.isEmpty() && !"-".equals(numericPart)) return Integer.parseInt(numericPart);
            } catch (NumberFormatException e) {
                log.warn("NFE for int key '{}': val '{}', original value from CDR '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
            }
        }
        return null;
    }

    private boolean isConferenceIdentifier(String number) {
        if (number == null || number.isEmpty()) return false;
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
        if (rawData.getImdexTransferCause() == newCause ||
            (newCause == ImdexTransferCause.PRE_CONFERENCE_NOW && rawData.getImdexTransferCause() == ImdexTransferCause.CONFERENCE) ||
            (newCause == ImdexTransferCause.CONFERENCE && rawData.getImdexTransferCause() == ImdexTransferCause.PRE_CONFERENCE_NOW) ) {
            rawData.setImdexTransferCause(newCause);
            return true;
        }
        return false;
    }

    private void swapCallingAndFinalCalled(RawCdrData rawData) {
        String tempNum = rawData.getCallingPartyNumber();
        String tempPart = rawData.getCallingPartyNumberPartition();
        rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
        rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
        rawData.setFinalCalledPartyNumber(tempNum);
        rawData.setFinalCalledPartyNumberPartition(tempPart);
    }

    private void swapTrunks(RawCdrData rawData) {
        String tempTrunk = rawData.getOrigDeviceName();
        rawData.setOrigDeviceName(rawData.getDestDeviceName());
        rawData.setDestDeviceName(tempTrunk);
    }
}