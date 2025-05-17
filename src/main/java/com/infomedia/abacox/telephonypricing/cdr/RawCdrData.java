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
    private String callingPartyNumber;
    private String finalCalledPartyNumber;
    private String originalCalledPartyNumber;
    private String lastRedirectDn;
    private String originalLastRedirectDn; // Added to preserve initial value of lastRedirectDn
    private String finalMobileCalledPartyNumber;

    private String callingPartyNumberPartition;
    private String finalCalledPartyNumberPartition;
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDnPartition;
    private String destMobileDeviceNamePartition;


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
    private boolean incomingCall;
    private String effectiveDestinationNumber;
    private String effectiveDestinationPartition;
    private String effectiveOriginatingNumber;
    private String effectiveOriginatingPartition;

    private ImdexTransferCause imdexTransferCause = ImdexTransferCause.NO_TRANSFER;
}
