// FILE: com/infomedia/abacox/telephonypricing/cdr/RawCdrData.java
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Data;

import java.time.LocalDateTime;

// Holds data parsed directly from a Cisco CDR row before full enrichment
@Data
public class RawCdrData {
    private String originalLine; // The raw CDR line for hashing and error reporting

    // Fields from Cisco CDR (names match internal logic, not necessarily raw CDR column names)
    private LocalDateTime dateTimeOrigination;
    private LocalDateTime dateTimeConnect;
    private LocalDateTime dateTimeDisconnect;

    // These fields will be manipulated during parsing based on conference/redirect logic
    private String callingPartyNumber;
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumber;
    private String finalCalledPartyNumberPartition;
    private String lastRedirectDn; // Stores the last redirect destination number
    private String lastRedirectDnPartition; // Stores the partition for lastRedirectDn

    // Store original values for specific logic steps
    private String original_finalCalledPartyNumber;
    private String original_finalCalledPartyNumberPartition;
    private String original_originalCalledPartyNumber;
    private String original_originalCalledPartyNumberPartition;
    private String original_lastRedirectDn; // Preserves initial value of lastRedirectDn before modifications
    private String original_lastRedirectDnPartition;

    private String finalMobileCalledPartyNumber;
    private String destMobileDeviceNamePartition; // Partition for finalMobileCalledPartyNumber

    private Integer duration;
    private String authCodeDescription;
    private Integer lastRedirectRedirectReason;
    private String origDeviceName;
    private String destDeviceName;
    private Integer joinOnBehalfOf;
    private Integer destCallTerminationOnBehalfOf;
    private Long destConversationId;
    private Long globalCallIDCallId;

    // Video related fields (can be null if not present)
    private Integer origVideoCapCodec;
    private Integer origVideoCapBandwidth;
    private Integer origVideoCapResolution;
    private Integer destVideoCapCodec;
    private Integer destVideoCapBandwidth;
    private Integer destVideoCapResolution;

    // Calculated or intermediate fields
    private Integer ringTime;
    private boolean incomingCall; // Final determination of call direction
    private ImdexTransferCause imdexTransferCause = ImdexTransferCause.NO_TRANSFER;

    // Final "effective" numbers and partitions to be used by EnrichmentService
    private String effectiveOriginatingNumber;
    private String effectiveOriginatingPartition;
    private String effectiveDestinationNumber;
    private String effectiveDestinationPartition;

    // Helper to preserve original lastRedirectDn if it was modified due to originalCalledParty logic
    private String preservedOriginalLastRedirectDnForConferenceLogic;
}