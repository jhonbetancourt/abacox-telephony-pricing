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

@Data
@NoArgsConstructor
public class CdrData {
    // Fields from PHP's $info_arr after parsing
    private String rawCdrLine;
    private LocalDateTime dateTimeOrigination; // date + time combined
    private String callingPartyNumber; // ext
    private String finalCalledPartyNumber; // dial_number
    private Integer durationSeconds; // duration_seg
    private String authCodeDescription; // acc_code
    private CallDirection callDirection = CallDirection.OUTGOING; // incoming (0=saliente, 1=entrante)
    private Integer ringingTimeSeconds; // ring

    // Cisco specific fields that might be used in enrichment
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumberPartition;
    private String originalCalledPartyNumber;
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDn; // ext-redir
    private String lastRedirectDnPartition; // partredir
    private String destMobileDeviceName; // partmovil
    private String finalMobileCalledPartyNumber; // ext-movil

    private Integer lastRedirectRedirectReason; // code_transfer
    private String origDeviceName; // troncal-ini
    private String destDeviceName; // troncal
    private String disconnectCauseOrig; // coderrororigen (Not directly used in PHP logic for acumtotal, but parsed)
    private String disconnectCauseDest; // coderrordestino (Not directly used in PHP logic for acumtotal, but parsed)

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
    private BigDecimal initialPricePerMinute = BigDecimal.ZERO; // For special rates (PHP: ACUMTOTAL_PRECIOINICIAL)
    private boolean priceIncludesVat = false;
    private boolean initialPriceIncludesVat = false; // For special rates
    private BigDecimal vatRate = BigDecimal.ZERO;
    private boolean chargeBySecond = false;

    private boolean isInternalCall = false; // Flag if call is internal
    private String pbxSpecialRuleAppliedInfo; // Info if a PBX rule changed the number

    // For CallRecord entity
    private FileInfo fileInfo;
    private Long commLocationId; // Set from the context of processing

    // Helper for additional, non-standard fields or temporary data
    private Map<String, Object> additionalData = new HashMap<>();

    // Fields for potential quarantine/failure
    private boolean markedForQuarantine = false;
    private String quarantineReason;
    private String quarantineStep;

    // Helper to get original timestamp for unique hash
    public long getDateTimeOriginationEpochSeconds() {
        // Assuming dateTimeOrigination is UTC as per Cisco docs
        return dateTimeOrigination != null ? dateTimeOrigination.toEpochSecond(java.time.ZoneOffset.UTC) : 0;
    }

    // Helper to store original values if they are changed by transformations
    public void storeOriginalValue(String key, Object value) {
        additionalData.put("original_" + key, value);
    }

    public Object getOriginalValue(String key) {
        return additionalData.get("original_" + key);
    }
}