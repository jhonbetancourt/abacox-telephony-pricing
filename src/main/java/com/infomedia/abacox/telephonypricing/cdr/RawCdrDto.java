package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

// Represents the raw data extracted from a CDR line by a parser
@Data
@Builder
public class RawCdrDto {
    // Common fields needed for initial processing
    private String cdrLine; // Original line for reference/quarantine
    private String globalCallId; // Unique ID for the call segment (e.g., globalCallID_callId)
    private LocalDateTime dateTimeOrigination;
    private LocalDateTime dateTimeConnect;
    private LocalDateTime dateTimeDisconnect;
    private String callingPartyNumber; // ext
    private String callingPartyNumberPartition; // partorigen
    private String finalCalledPartyNumber; // dial_number
    private String finalCalledPartyNumberPartition; // partdestino
    private String originalCalledPartyNumber; // For fallback if finalCalledPartyNumber is empty
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDn; // ext-redir
    private String lastRedirectDnPartition; // partredir
    private String destMobileDeviceName; // partmovil
    private String finalMobileCalledPartyNumber; // ext-movil
    private int duration; // duration_seg
    private String authCodeDescription; // acc_code
    private String origDeviceName; // troncal-ini
    private String destDeviceName; // troncal
    private int lastRedirectRedirectReason; // code_transfer
    private int disconnectCause; // Example: origCause_value / destCause_value (choose one or combine)
    private int ringDuration; // Calculated ring duration

    // Video fields (optional, based on CM version/config)
    private String origVideoCapCodec;
    private Integer origVideoCapBandwidth;
    private String origVideoCapResolution;
    private String destVideoCapCodec;
    private Integer destVideoCapBandwidth;
    private String destVideoCapResolution;

    // Conference/Join fields
    private Integer joinOnBehalfOf;
    private Integer destCallTerminationOnBehalfOf;
    private Integer destConversationId;

    // Calculated fields
    private boolean incoming; // Determined during parsing/initial processing

    // Metadata
    private String sourceIp; // If available from source (e.g., syslog)
    private Long fileInfoId; // If processed from a file with metadata
}