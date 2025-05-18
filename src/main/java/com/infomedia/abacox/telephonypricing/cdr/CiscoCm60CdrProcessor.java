
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
    private static final String CONFERENCE_IDENTIFIER_PREFIX = "b"; // PHP: $cm_config['cdr_conferencia']

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
        String[] columns = cdrLine.split(CDR_DELIMITER, -1); // -1 to keep trailing empty strings

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

        // --- Step 4: Pre-conference handling of lastRedirectDn (mimics PHP's ext-redir-cc logic) ---
        // This step preserves the *actual* lastRedirectDn from the CDR if the working lastRedirectDn
        // is about to be overwritten by originalCalledPartyNumber.
        String preservedActualCdrLastRedirectDn = rawData.getOriginal_lastRedirectDn();
        String preservedActualCdrLastRedirectDnPartition = rawData.getOriginal_lastRedirectDnPartition();

        if (!rawData.getOriginal_originalCalledPartyNumber().isEmpty() &&
            !rawData.getFinalCalledPartyNumber().equals(rawData.getOriginal_originalCalledPartyNumber())) {
            // If original_lastRedirectDn (from CDR) was NOT a conference ID
            if (!isConferenceIdentifier(rawData.getOriginal_lastRedirectDn())) {
                // current working lastRedirectDn becomes original_originalCalledPartyNumber
                rawData.setLastRedirectDn(rawData.getOriginal_originalCalledPartyNumber());
                rawData.setLastRedirectDnPartition(rawData.getOriginal_originalCalledPartyNumberPartition());
                // preservedActualCdrLastRedirectDn still holds the value from CDR's lastRedirectDn field
            }
        }

        // --- Step 5 & 6: Conference and Redirect Logic ---
        boolean isCurrentFinalCalledPartyConference = isConferenceIdentifier(rawData.getFinalCalledPartyNumber());
        // Use the *actual CDR value* of lastRedirectDn for this check, not the potentially modified working one
        boolean isActualCdrLastRedirectDnConference = isConferenceIdentifier(rawData.getOriginal_lastRedirectDn());

        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true; // Default for conference related swaps

        if (isCurrentFinalCalledPartyConference) {
            ImdexTransferCause confCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                                           ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.CONFERENCE;
            updateImdexTransferCause(rawData, confCause);

            if (!isActualCdrLastRedirectDnConference) { // If original_lastRedirectDn (from CDR) was NOT a conference
                String tempConfId = rawData.getFinalCalledPartyNumber(); // This is the 'bXXXX' conference ID
                String tempConfPart = rawData.getFinalCalledPartyNumberPartition();

                // finalCalledPartyNumber becomes the current working lastRedirectDn
                // (which might be original_originalCalledPartyNumber or original_lastRedirectDn from CDR)
                rawData.setFinalCalledPartyNumber(rawData.getLastRedirectDn());
                rawData.setFinalCalledPartyNumberPartition(rawData.getLastRedirectDnPartition());

                // lastRedirectDn (working) becomes the conference ID 'bXXXX'
                rawData.setLastRedirectDn(tempConfId);
                rawData.setLastRedirectDnPartition(tempConfPart);

                if (confCause == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                    // Use the preserved actual lastRedirectDn from CDR if it starts with 'c'
                    if (preservedActualCdrLastRedirectDn != null &&
                        preservedActualCdrLastRedirectDn.toLowerCase().startsWith("c")) {
                        rawData.setLastRedirectDn(preservedActualCdrLastRedirectDn);
                        rawData.setLastRedirectDnPartition(preservedActualCdrLastRedirectDnPartition);
                    } else if (rawData.getDestConversationId() != null && rawData.getDestConversationId() > 0) {
                        rawData.setLastRedirectDn("i" + rawData.getDestConversationId());
                        // Partition for "i" + destConversationId is not explicitly set in PHP,
                        // seems to remain the one from the conference ID (tempConfPart)
                    }
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) { // If not PRE_CONFERENCE_NOW
                swapCallingAndFinalCalled(rawData);
            }
        } else {
            // If finalCalledPartyNumber is NOT a conference, check if the current working lastRedirectDn is.
            // This handles the "end of conference" scenario.
            boolean isCurrentWorkingLastRedirectDnConference = isConferenceIdentifier(rawData.getLastRedirectDn());
            if (isCurrentWorkingLastRedirectDnConference) {
                if (updateImdexTransferCause(rawData, ImdexTransferCause.CONFERENCE_END)) {
                    invertTrunksForConference = false; // Do not invert trunks for this specific scenario
                }
            }
        }

        // --- Step 7: Self-Call Discard ---
        // This check is after conference logic might have swapped numbers.
        if (Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            // PHP: if ($esconferencia && $info_arr['ext'].'' == $info_arr['dial_number'].'') { $info_arr = array(); }
            // This implies discarding the CDR if, after conference logic, caller and callee are identical.
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
            // For conference-related calls, incoming is determined if the final destination (after swaps)
            // is not an internal extension or has no partition.
            boolean isEffectivelyIncomingForConference =
                (rawData.getFinalCalledPartyNumberPartition() == null || rawData.getFinalCalledPartyNumberPartition().isEmpty()) &&
                (rawData.getFinalCalledPartyNumber().isEmpty() || !CdrHelper.isPotentialExtension(rawData.getFinalCalledPartyNumber(), limits));

            if (isEffectivelyIncomingForConference) {
                rawData.setIncomingCall(true);
            } else if (invertTrunksForConference) { // If it's a conference-related outgoing call (or internal-to-internal conference)
                swapTrunks(rawData);
            }
        } else { // Not a conference-related call (or handled as such)
            boolean isOriginInternal = isInternalParty(rawData.getCallingPartyNumber(), rawData.getCallingPartyNumberPartition(), limits);
            boolean isDestinationInternal = isInternalParty(rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(), limits);
            // Use the *working* lastRedirectDn for this check
            boolean isRedirectInternal = isInternalParty(rawData.getLastRedirectDn(), rawData.getLastRedirectDnPartition(), limits);

            if (!isOriginInternal && (isDestinationInternal || isRedirectInternal)) {
                rawData.setIncomingCall(true);
                swapCallingAndFinalCalled(rawData); // For incoming, callingParty becomes the external number
            }
        }

        // --- Step 9: Final Transfer Cause (for non-conference redirects) ---
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
                // Determine the "effective" destination number against which lastRedirectDn should be compared
                String effectiveDestinationForTransferCheck = rawData.isIncomingCall() ?
                                                               rawData.getCallingPartyNumber() : // For incoming, callingParty is the external, finalCalled is internal
                                                               rawData.getFinalCalledPartyNumber();

                if (!Objects.equals(effectiveDestinationForTransferCheck, rawData.getLastRedirectDn())) {
                    Integer redirectReason = rawData.getLastRedirectRedirectReason();
                    if (redirectReason != null && redirectReason > 0 && redirectReason <= 16) {
                        updateImdexTransferCause(rawData, ImdexTransferCause.NORMAL);
                    } else {
                        // PHP's IMDEX_TRANSFER_AUTO or IMDEX_TRANSFER_PRECONFENOW (based on destCallTerminationOnBehalfOf)
                        Integer destTerminationCode = rawData.getDestCallTerminationOnBehalfOf();
                        ImdexTransferCause autoCause = (destTerminationCode != null && destTerminationCode == 7) ?
                                                       ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.AUTO_TRANSFER;
                        updateImdexTransferCause(rawData, autoCause);
                    }
                }
            }
        }

        // --- Step 10: Mobile Redirect Override (specific to tp_cisco_cm_60.php logic) ---
        // This logic applies *after* all previous manipulations of finalCalledPartyNumber.
        if (rawData.getOriginal_finalMobileCalledPartyNumber() != null &&
            !rawData.getOriginal_finalMobileCalledPartyNumber().isEmpty() &&
            !rawData.getOriginal_finalMobileCalledPartyNumber().equals(rawData.getFinalCalledPartyNumber())) { // Compare with current working finalCalledPartyNumber

            log.debug("Mobile redirect detected. Current finalCalled: {}/{}, Mobile Dest: {}/{}",
                rawData.getFinalCalledPartyNumber(), rawData.getFinalCalledPartyNumberPartition(),
                rawData.getOriginal_finalMobileCalledPartyNumber(), rawData.getOriginal_destMobileDeviceName());

            // The 'callingParty' for this leg is the party that was originally called and is now forwarding.
            // At this stage, rawData.getFinalCalledPartyNumber() holds the number of the party that is forwarding.
            rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
            rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());

            // The 'finalCalledParty' for this leg is the mobile number.
            rawData.setFinalCalledPartyNumber(rawData.getOriginal_finalMobileCalledPartyNumber());
            rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_destMobileDeviceName());

            rawData.setIncomingCall(false); // This leg (to the mobile) is outgoing from the PBX.
            updateImdexTransferCause(rawData, ImdexTransferCause.AUTO_TRANSFER); // Mark as an auto transfer

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
        // An internal party has a non-empty partition AND its number is a potential extension
        return (partition != null && !partition.isEmpty()) && CdrHelper.isPotentialExtension(number, limits);
    }

    /**
     * Updates the ImdexTransferCause if the current cause is NO_TRANSFER or the same as newCause.
     * Handles specific cases for PRE_CONFERENCE_NOW and CONFERENCE being interchangeable if one is already set.
     * @return true if the cause was updated or was already the newCause, false otherwise.
     */
    private boolean updateImdexTransferCause(RawCdrData rawData, ImdexTransferCause newCause) {
        ImdexTransferCause currentCause = rawData.getImdexTransferCause();
        if (currentCause == ImdexTransferCause.NO_TRANSFER || currentCause == newCause) {
            rawData.setImdexTransferCause(newCause);
            return true;
        }
        // Allow PRE_CONFERENCE_NOW and CONFERENCE to overwrite each other
        if ((currentCause == ImdexTransferCause.CONFERENCE && newCause == ImdexTransferCause.PRE_CONFERENCE_NOW) ||
            (currentCause == ImdexTransferCause.PRE_CONFERENCE_NOW && newCause == ImdexTransferCause.CONFERENCE)) {
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