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
    private String callingPartyNumber; // ext
    private String finalCalledPartyNumber; // dial_number
    private String originalCalledPartyNumber;
    private String lastRedirectDn; // ext-redir
    private String finalMobileCalledPartyNumber; // ext-movil

    private String callingPartyNumberPartition; // partorigen
    private String finalCalledPartyNumberPartition; // partdestino
    private String originalCalledPartyNumberPartition;
    private String lastRedirectDnPartition; // partredir
    private String destMobileDeviceNamePartition; // partmovil (assuming this is the partition for finalMobileCalledPartyNumber)


    private Integer duration;
    private String authCodeDescription; // acc_code
    private Integer lastRedirectRedirectReason; // code_transfer
    private String origDeviceName; // troncal-ini
    private String destDeviceName; // troncal
    private Integer joinOnBehalfOf;
    private Integer destCallTerminationOnBehalfOf; // finaliza-union
    private Long destConversationId; // indice-conferencia (assuming it's a long)
    private Long globalCallIDCallId; // indice-llamada

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
    private String effectiveDestinationNumber; // The number used for lookups after considering redirects
    private String effectiveDestinationPartition;
    private String effectiveOriginatingNumber;
    private String effectiveOriginatingPartition;

    // For transfer/conference logic
    private ImdexTransferCause imdexTransferCause = ImdexTransferCause.NO_TRANSFER;
}