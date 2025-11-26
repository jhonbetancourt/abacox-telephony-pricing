package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.utils.XXHash64Util;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Data
@NoArgsConstructor
@Log4j2
public class CdrData {
    // Fields from PHP's $info_arr after parsing
    @ToString.Exclude
    private String rawCdrLine;
    
    // NEW FIELD: Stores the compressed byte array generated during processing
    @ToString.Exclude
    private byte[] preCompressedData;
    
    private Long ctlHash;
    private LocalDateTime dateTimeOrigination; 
    private String callingPartyNumber; 
    private String finalCalledPartyNumber; 
    private String originalFinalCalledPartyNumber; 
    private String originalFinalCalledPartyNumberPartition; 

    private Integer durationSeconds; 
    private String authCodeDescription; 
    private CallDirection callDirection = CallDirection.OUTGOING; 
    private Integer ringingTimeSeconds; 

    // Cisco specific fields
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumberPartition; 
    private String originalCalledPartyNumber;
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDn; 
    private String lastRedirectDnPartition; 
    private String originalLastRedirectDn;
    private String destMobileDeviceName; 
    private String finalMobileCalledPartyNumber; 

    private Integer lastRedirectRedirectReason; 
    private String origDeviceName; 
    private String destDeviceName; 
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
    private String conferenceIdentifierUsed; 

    // Fields populated during enrichment
    private Long employeeId;
    private Employee employee; 
    private Long destinationEmployeeId;
    private Employee destinationEmployee; 
    private AssignmentCause assignmentCause = AssignmentCause.NOT_ASSIGNED;
    private TransferCause transferCause = TransferCause.NONE;
    private String employeeTransferExtension; 

    private Long telephonyTypeId;
    private String telephonyTypeName; 
    private Long operatorId;
    private String operatorName; 
    private Long indicatorId; 
    private String destinationCityName; 
    private String effectiveDestinationNumber; 

    private BigDecimal billedAmount = BigDecimal.ZERO;
    private BigDecimal pricePerMinute = BigDecimal.ZERO;
    private BigDecimal initialPricePerMinute = BigDecimal.ZERO;
    private boolean priceIncludesVat = false;
    private boolean initialPriceIncludesVat = false;
    private BigDecimal vatRate = BigDecimal.ZERO;
    private boolean chargeBySecond = false;

    private boolean isInternalCall = false; 
    private String pbxSpecialRuleAppliedInfo;

    private FileInfo fileInfo;
    private Long commLocationId;

    private boolean markedForQuarantine = false;
    private String quarantineReason;
    private String quarantineStep;

    //Additional Processing Data
    private SpecialServiceInfo specialServiceTariff;
    private boolean normalizedTariffApplied = false;
    private BigDecimal specialRateDiscountPercentage;
    private String internalCheckPbxTransformedDest;
    private String originalCallerIdBeforePbxIncoming;
    private String originalCallerIdBeforeCMETransform;
    private Long hintedTelephonyTypeIdFromTransform;
    private String originalDialNumberBeforeCMETransform;
    private String originalDialNumberBeforePbxOutgoing;
    private String originalDialNumberBeforePbxIncoming;

    public long getDateTimeOriginationEpochSeconds() {
        return dateTimeOrigination != null ? dateTimeOrigination.toEpochSecond(ZoneOffset.UTC) : 0;
    }

    public void setRawCdrLine(String rawCdrLine) {
        this.rawCdrLine = rawCdrLine;
        this.ctlHash = XXHash64Util.hash(rawCdrLine.getBytes());
    }
}