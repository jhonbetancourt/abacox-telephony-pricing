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
    public static final String HDR_DEST_MOBILE_DEVICE_NAME_PARTITION = "destmobiledevicename";

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
        String[] columns = cdrLine.split(CDR_DELIMITER, -1);

        int maxExpectedIndex = columnMapping.values().stream().filter(Objects::nonNull).mapToInt(v -> v).max().orElse(0);
        if (columns.length <= maxExpectedIndex) {
            throw new CdrProcessingException("CDR line column count (" + columns.length + ") mismatch with header (expected at least " + (maxExpectedIndex + 1) + "). Line: " + cdrLine);
        }

        RawCdrData rawData = new RawCdrData();
        rawData.setOriginalLine(cdrLine);

        // --- Step 1: Parse all relevant raw fields ---
        Long dateTimeOriginationEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_ORIGINATION);
        Long dateTimeConnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_CONNECT);
        Long dateTimeDisconnectEpoch = getLongColumnValue(columns, columnMapping, HDR_DATETIME_DISCONNECT);

        rawData.setDateTimeOrigination(CdrHelper.epochToLocalDateTime(dateTimeOriginationEpoch));
        rawData.setDateTimeConnect(CdrHelper.epochToLocalDateTime(dateTimeConnectEpoch));
        rawData.setDateTimeDisconnect(CdrHelper.epochToLocalDateTime(dateTimeDisconnectEpoch));

        rawData.setCallingPartyNumber(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER));
        rawData.setCallingPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_CALLING_PARTY_NUMBER_PARTITION).toUpperCase());

        rawData.setOriginal_finalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER));
        rawData.setOriginal_finalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_FINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase());
        rawData.setOriginal_originalCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER));
        rawData.setOriginal_originalCalledPartyNumberPartition(getColumnValue(columns, columnMapping, HDR_ORIGINAL_CALLED_PARTY_NUMBER_PARTITION).toUpperCase());
        rawData.setOriginal_lastRedirectDn(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN));
        rawData.setOriginal_lastRedirectDnPartition(getColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_DN_PARTITION).toUpperCase());

        rawData.setFinalCalledPartyNumber(rawData.getOriginal_finalCalledPartyNumber());
        rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_finalCalledPartyNumberPartition());
        rawData.setLastRedirectDn(rawData.getOriginal_lastRedirectDn());
        rawData.setLastRedirectDnPartition(rawData.getOriginal_lastRedirectDnPartition());
        // Preserve the original lastRedirectDn value *before* it might be overwritten by originalCalledParty logic
        rawData.setPreservedOriginalLastRedirectDnForConferenceLogic(rawData.getOriginal_lastRedirectDn());


        rawData.setDuration(getIntColumnValue(columns, columnMapping, HDR_DURATION));
        rawData.setAuthCodeDescription(getColumnValue(columns, columnMapping, HDR_AUTH_CODE_DESCRIPTION));
        rawData.setLastRedirectRedirectReason(getIntColumnValue(columns, columnMapping, HDR_LAST_REDIRECT_REDIRECT_REASON));
        rawData.setOrigDeviceName(getColumnValue(columns, columnMapping, HDR_ORIG_DEVICE_NAME));
        rawData.setDestDeviceName(getColumnValue(columns, columnMapping, HDR_DEST_DEVICE_NAME));
        rawData.setJoinOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_JOIN_ON_BEHALF_OF));
        rawData.setDestCallTerminationOnBehalfOf(getIntColumnValue(columns, columnMapping, HDR_DEST_CALL_TERMINATION_ON_BEHALF_OF));
        rawData.setDestConversationId(getLongColumnValue(columns, columnMapping, HDR_DEST_CONVERSATION_ID));
        rawData.setGlobalCallIDCallId(getLongColumnValue(columns, columnMapping, HDR_GLOBAL_CALL_ID_CALL_ID));

        // Parse mobile redirect fields, but their usage for overriding destination is handled later if needed
        rawData.setFinalMobileCalledPartyNumber(getColumnValue(columns, columnMapping, HDR_FINAL_MOBILE_CALLED_PARTY_NUMBER));
        rawData.setDestMobileDeviceNamePartition(getColumnValue(columns, columnMapping, HDR_DEST_MOBILE_DEVICE_NAME_PARTITION).toUpperCase());

        rawData.setOrigVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_CODEC));
        rawData.setOrigVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_BANDWIDTH));
        rawData.setOrigVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_ORIG_VIDEO_CAP_RESOLUTION));
        rawData.setDestVideoCapCodec(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_CODEC));
        rawData.setDestVideoCapBandwidth(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_BANDWIDTH));
        rawData.setDestVideoCapResolution(getIntColumnValue(columns, columnMapping, HDR_DEST_VIDEO_CAP_RESOLUTION));

        // --- Step 2: Calculate ring time, adjust duration ---
        if (dateTimeConnectEpoch != null && dateTimeConnectEpoch > 0 && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeConnectEpoch - dateTimeOriginationEpoch));
        } else if (dateTimeDisconnectEpoch != null && dateTimeOriginationEpoch != null) {
            rawData.setRingTime((int) (dateTimeDisconnectEpoch - dateTimeOriginationEpoch));
            rawData.setDuration(0);
        }
        if (rawData.getRingTime() != null && rawData.getRingTime() < 0) rawData.setRingTime(0);

        // --- Step 3: Handle empty finalCalledPartyNumber (use originalCalledPartyNumber) ---
        if (rawData.getFinalCalledPartyNumber().isEmpty()) {
            rawData.setFinalCalledPartyNumber(rawData.getOriginal_originalCalledPartyNumber());
            rawData.setFinalCalledPartyNumberPartition(rawData.getOriginal_originalCalledPartyNumberPartition());
        }
        // --- Step 4: Handle finalCalledPartyNumber differing from originalCalledPartyNumber ---
        else if (!rawData.getOriginal_originalCalledPartyNumber().isEmpty() &&
                 !rawData.getFinalCalledPartyNumber().equals(rawData.getOriginal_originalCalledPartyNumber())) {
            // If lastRedirectDn (original value) was NOT a conference ID, update it
            if (!isConferenceIdentifier(rawData.getPreservedOriginalLastRedirectDnForConferenceLogic())) {
                rawData.setLastRedirectDn(rawData.getOriginal_originalCalledPartyNumber());
                rawData.setLastRedirectDnPartition(rawData.getOriginal_originalCalledPartyNumberPartition());
            }
        }

        // --- Step 5 & 6: Conference and Redirect Logic ---
        boolean isCurrentFinalCalledPartyConference = isConferenceIdentifier(rawData.getFinalCalledPartyNumber());
        boolean isCurrentLastRedirectDnConference = isConferenceIdentifier(rawData.getLastRedirectDn()); // Uses potentially updated lastRedirectDn
        Integer joinOnBehalfOf = rawData.getJoinOnBehalfOf();
        boolean invertTrunksForConference = true;

        if (isCurrentFinalCalledPartyConference) {
            ImdexTransferCause confCause = (joinOnBehalfOf != null && joinOnBehalfOf == 7) ?
                                           ImdexTransferCause.PRE_CONFERENCE_NOW : ImdexTransferCause.CONFERENCE;
            updateImdexTransferCause(rawData, confCause);

            if (!isCurrentLastRedirectDnConference) {
                String tempConfId = rawData.getFinalCalledPartyNumber();
                String tempConfPart = rawData.getFinalCalledPartyNumberPartition();

                rawData.setFinalCalledPartyNumber(rawData.getLastRedirectDn());
                rawData.setFinalCalledPartyNumberPartition(rawData.getLastRedirectDnPartition());

                rawData.setLastRedirectDn(tempConfId);
                rawData.setLastRedirectDnPartition(tempConfPart);

                if (confCause == ImdexTransferCause.PRE_CONFERENCE_NOW) {
                     if (rawData.getDestConversationId() != null) {
                         rawData.setLastRedirectDn("i" + rawData.getDestConversationId());
                    }
                }
            }

            if (joinOnBehalfOf == null || joinOnBehalfOf != 7) {
                String tempNum = rawData.getCallingPartyNumber();
                String tempPart = rawData.getCallingPartyNumberPartition();
                rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber());
                rawData.setCallingPartyNumberPartition(rawData.getFinalCalledPartyNumberPartition());
                rawData.setFinalCalledPartyNumber(tempNum);
                rawData.setFinalCalledPartyNumberPartition(tempPart);
            }
        } else {
            if (isCurrentLastRedirectDnConference) { // Check current lastRedirectDn
                if (updateImdexTransferCause(rawData, ImdexTransferCause.CONFERENCE_END)) {
                    invertTrunksForConference = false;
                }
            }
        }

        // --- Step 7: Self-Call Discard (PHP: $ignorar = ($cdr_extension_fin == $cdr_extension_ori && $cdr_particion_fin == $cdr_particion_ori);) ---
        // This check uses the state of numbers/partitions *after* the conference logic above, but *before* incoming call detection swaps.
        if (Objects.equals(rawData.getCallingPartyNumber(), rawData.getFinalCalledPartyNumber()) &&
            Objects.equals(rawData.getCallingPartyNumberPartition(), rawData.getFinalCalledPartyNumberPartition())) {
            log.warn("Self-call detected after conference processing. CDR: {}", cdrLine);
            throw new CdrProcessingException("Self-call detected: " + cdrLine);
        }

        // --- Step 8: Incoming Call Detection ---
        rawData.setIncomingCall(false); // Default
        InternalExtensionLimitsDto limits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        // Use the state of numbers/partitions *after* the self-call check
        String currentCallingNum = rawData.getCallingPartyNumber();
        String currentCallingPart = rawData.getCallingPartyNumberPartition();
        String currentFinalCalledNum = rawData.getFinalCalledPartyNumber();
        String currentFinalCalledPart = rawData.getFinalCalledPartyNumberPartition();
        String currentLastRedirectNum = rawData.getLastRedirectDn(); // This is the one that might be 'bXXXX'
        String currentLastRedirectPart = rawData.getLastRedirectDnPartition();

        ImdexTransferCause currentTransferCause = rawData.getImdexTransferCause();
        if (currentTransferCause == ImdexTransferCause.CONFERENCE ||
            currentTransferCause == ImdexTransferCause.PRE_CONFERENCE_NOW ||
            currentTransferCause == ImdexTransferCause.CONFERENCE_END) {

            boolean isEffectivelyIncomingForConference =
                (currentFinalCalledPart == null || currentFinalCalledPart.isEmpty()) &&
                (currentFinalCalledNum.isEmpty() || !CdrHelper.isPotentialExtension(currentFinalCalledNum, limits));

            if (isEffectivelyIncomingForConference) {
                rawData.setIncomingCall(true);
            } else if (invertTrunksForConference) {
                String tempTrunk = rawData.getOrigDeviceName();
                rawData.setOrigDeviceName(rawData.getDestDeviceName());
                rawData.setDestDeviceName(tempTrunk);
            }
        } else {
            boolean isOriginInternal = isInternalParty(currentCallingNum, currentCallingPart, limits);
            boolean isDestinationInternal = isInternalParty(currentFinalCalledNum, currentFinalCalledPart, limits);
            // For incoming, PHP also checks redirect. If destination is not internal but redirect is, it's still incoming.
            boolean isRedirectInternal = isInternalParty(currentLastRedirectNum, currentLastRedirectPart, limits);

            if (!isOriginInternal && (isDestinationInternal || isRedirectInternal)) {
                rawData.setIncomingCall(true);
                // PHP: _invertir($info_arr['dial_number'], $info_arr['ext']); // Partitions NOT swapped here
                String tempNum = rawData.getCallingPartyNumber(); // This is the external number
                rawData.setCallingPartyNumber(rawData.getFinalCalledPartyNumber()); // This becomes the internal extension
                // rawData.setCallingPartyNumberPartition(...); // Partition remains that of finalCalledParty
                rawData.setFinalCalledPartyNumber(tempNum); // This becomes the external number
                // rawData.setFinalCalledPartyNumberPartition(...); // Partition remains that of callingParty
            }
        }

        // --- Step 9: Final Transfer Cause (non-conference redirects) ---
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            if (currentLastRedirectNum != null && !currentLastRedirectNum.isEmpty()) {
                String effectiveDestForTransferCheck = rawData.isIncomingCall() ? rawData.getCallingPartyNumber() : rawData.getFinalCalledPartyNumber();
                if (!Objects.equals(effectiveDestForTransferCheck, currentLastRedirectNum)) {
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

        // --- Step 10: Set Final Effective Numbers in rawData ---
        // These are the numbers after all parsing logic, to be used by EnrichmentService
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
        log.trace("Header key '{}' not found or index out of bounds. Returning empty string.", headerKey);
        return "";
    }

    private Long getLongColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
            try {
                String numericPart = val.replaceAll("[^0-9]", "");
                if (!numericPart.isEmpty()) return Long.parseLong(numericPart);
            } catch (NumberFormatException e) {
                 log.warn("NFE for long key '{}': val '{}', original column value '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
            }
        }
        return null;
    }

    private Integer getIntColumnValue(String[] columns, Map<String, Integer> columnMapping, String headerKey) {
        String val = getColumnValue(columns, columnMapping, headerKey);
        if (!val.isEmpty()) {
             try {
                String numericPart = val.replaceAll("[^0-9-]", ""); // Allow negative for some codes
                if (!numericPart.isEmpty() && !"-".equals(numericPart)) return Integer.parseInt(numericPart);
            } catch (NumberFormatException e) {
                log.warn("NFE for int key '{}': val '{}', original column value '{}'", headerKey, val, columns[columnMapping.get(headerKey.toLowerCase())]);
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
        // PHP: ($info_arr['partorigen'] != '' && ExtensionPosible($info_arr['ext']))
        return (partition != null && !partition.isEmpty()) && CdrHelper.isPotentialExtension(number, limits);
    }

    private boolean updateImdexTransferCause(RawCdrData rawData, ImdexTransferCause newCause) {
        // PHP: if ($info_arr['info_transfer'] == 0 || $info_arr['info_transfer'] == $causa)
        if (rawData.getImdexTransferCause() == ImdexTransferCause.NO_TRANSFER) {
            rawData.setImdexTransferCause(newCause);
            return true;
        }
        // If already set, only update if it's the same cause (idempotency) or perhaps a more specific one.
        // For now, simple "set if not set" or "set if same".
        if (rawData.getImdexTransferCause() == newCause) {
            return true;
        }
        // If a cause is already set and the new one is different, PHP logic implies it doesn't overwrite.
        return false;
    }
}