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

    // --- Pristine Original Values from CDR ---
    private String original_callingPartyNumber;
    private String original_callingPartyNumberPartition;
    private String original_finalCalledPartyNumber;
    private String original_finalCalledPartyNumberPartition;
    private String original_originalCalledPartyNumber; // originalCalledPartyNumber from CDR
    private String original_originalCalledPartyNumberPartition; // originalCalledPartyNumberPartition from CDR
    private String original_lastRedirectDn; // lastRedirectDn from CDR
    private String original_lastRedirectDnPartition; // lastRedirectDnPartition from CDR
    private String original_finalMobileCalledPartyNumber;
    private String original_destMobileDeviceName; // Partition for mobile redirect

    // --- Current Working Values (manipulated during parsing) ---
    private String callingPartyNumber;
    private String callingPartyNumberPartition;
    private String finalCalledPartyNumber;
    private String finalCalledPartyNumberPartition;
    private String lastRedirectDn; // This is the "ext-redir" in PHP, can be updated
    private String lastRedirectDnPartition;
    private String finalMobileCalledPartyNumber; // Current working value for mobile number
    private String destMobileDeviceName; // Current working value for mobile partition/device name

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