// File: com/infomedia/abacox/telephonypricing/cdr/CdrData.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Data
@NoArgsConstructor
public class CdrData {
    // Fields from PHP's $info_arr after parsing
    private String rawCdrLine;
    private LocalDateTime dateTimeOrigination; // date + time combined
    private String callingPartyNumber; // ext
    private String finalCalledPartyNumber; // dial_number (can be modified)
    private String originalFinalCalledPartyNumber; // Stores the initial value of finalCalledPartyNumber before any modifications
    private String originalFinalCalledPartyNumberPartition; // Stores the initial value of finalCalledPartyNumberPartition

    private Integer durationSeconds; // duration_seg
    private String authCodeDescription; // acc_code
    private CallDirection callDirection = CallDirection.OUTGOING; // incoming (0=saliente, 1=entrante)
    private Integer ringingTimeSeconds; // ring

    // Cisco specific fields that might be used in enrichment
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumberPartition; // Can be modified
    private String originalCalledPartyNumber;
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDn; // ext-redir
    private String lastRedirectDnPartition; // partredir
    private String destMobileDeviceName; // partmovil
    private String finalMobileCalledPartyNumber; // ext-movil

    private Integer lastRedirectRedirectReason; // code_transfer
    private String origDeviceName; // troncal-ini
    private String destDeviceName; // troncal
    private String disconnectCauseOrig;
    private String disconnectCauseDest;

    // Video related fields
    private String origVideoCodec;
    private Integer origVideoBandwidth;
    private String origVideoResolution;
    private String destVideoCodec;
    private Integer destVideoBandwidth;
    private String destVideoResolution;

    // Conference/Join related
    private Integer joinOnBehalfOf;
    private Integer destCallTerminationOnBehalfOf;
    private Long destConversationId;
    private Long globalCallIDCallId;
    private String conferenceIdentifierUsed; // e.g., "b001..." or "i123..."


    // Fields populated during enrichment (mimicking $infovalor and other additions)
    private Long employeeId;
    private Employee employee; // For convenience
    private Long destinationEmployeeId;
    private Employee destinationEmployee; // For convenience
    private AssignmentCause assignmentCause = AssignmentCause.NOT_ASSIGNED;
    private TransferCause transferCause = TransferCause.NONE;
    private String employeeTransferExtension; // Populated from lastRedirectDn if transferCause is set

    private Long telephonyTypeId;
    private String telephonyTypeName; // For display/logging
    private Long operatorId;
    private String operatorName; // For display/logging
    private Long indicatorId; // Destination indicator for outgoing, Source indicator for incoming
    private String destinationCityName; // For display/logging (destination for outgoing, source for incoming)
    private String effectiveDestinationNumber; // The number used for tariffing after cleaning/PBX rules

    private BigDecimal billedAmount = BigDecimal.ZERO;
    private BigDecimal pricePerMinute = BigDecimal.ZERO;
    private BigDecimal initialPricePerMinute = BigDecimal.ZERO;
    private boolean priceIncludesVat = false;
    private boolean initialPriceIncludesVat = false;
    private BigDecimal vatRate = BigDecimal.ZERO;
    private boolean chargeBySecond = false;

    private boolean isInternalCall = false; // Flag if call is internal
    private String pbxSpecialRuleAppliedInfo;

    private FileInfo fileInfo;
    private Long commLocationId;

    private Map<String, Object> additionalData = new HashMap<>();

    private boolean markedForQuarantine = false;
    private String quarantineReason;
    private String quarantineStep;

    public long getDateTimeOriginationEpochSeconds() {
        return dateTimeOrigination != null ? dateTimeOrigination.toEpochSecond(java.time.ZoneOffset.UTC) : 0;
    }

    public void storeOriginalValue(String key, Object value) {
        if (!additionalData.containsKey("original_" + key)) { // Store only the first time
            additionalData.put("original_" + key, value);
        }
    }

    public Object getOriginalValue(String key) {
        return additionalData.get("original_" + key);
    }

    // Helper to ensure effectiveDestinationNumber is initialized
    public String getEffectiveDestinationNumber() {
        return Objects.requireNonNullElse(effectiveDestinationNumber, finalCalledPartyNumber);
    }
}