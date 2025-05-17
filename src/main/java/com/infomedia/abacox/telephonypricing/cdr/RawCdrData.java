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

    // These fields will be manipulated during parsing based on conference/redirect logic.
    // They represent the "current" state of these numbers/partitions during parsing.
    private String callingPartyNumber;
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumber;
    private String finalCalledPartyNumberPartition;
    private String lastRedirectDn;
    private String lastRedirectDnPartition;

    // Store original values for specific logic steps and fallbacks
    private String original_callingPartyNumber; // To restore if swaps happen
    private String original_callingPartyNumberPartition;
    private String original_finalCalledPartyNumber;
    private String original_finalCalledPartyNumberPartition;
    private String original_originalCalledPartyNumber;
    private String original_originalCalledPartyNumberPartition;
    private String original_lastRedirectDn; // Stores the value of lastRedirectDn as read from CDR
    private String original_lastRedirectDnPartition;

    // Mobile redirection fields
    private String finalMobileCalledPartyNumber;
    private String destMobileDeviceName; // This is the "partition" field for mobile number in PHP

    private Integer duration;
    private String authCodeDescription;
    private Integer lastRedirectRedirectReason; // Cisco's reason code for redirection
    private String origDeviceName; // Trunk/Device
    private String destDeviceName; // Trunk/Device
    private Integer joinOnBehalfOf; // Reason for joining a call (e.g., conference, park pickup)
    private Integer destCallTerminationOnBehalfOf; // Reason for call termination
    private Long destConversationId; // Conference ID
    private Long globalCallIDCallId; // Unique call ID

    // Video related fields
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
    // These are set at the end of the parsing logic.
    private String effectiveOriginatingNumber;
    private String effectiveOriginatingPartition;
    private String effectiveDestinationNumber;
    private String effectiveDestinationPartition;
}