package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull; // Import NonNull for required fields

import java.time.LocalDateTime;

/**
 * Represents a standardized, format-agnostic view of a call event,
 * translated from a raw CDR line by a specific parser.
 * This DTO is used by the CdrEnrichmentService.
 */
@Data
@Builder
public class StandardizedCallEventDto {

    // Core Call Identifiers & Parties
    @NonNull // Global Call ID should always be present if parsing succeeded
    private String globalCallId;
    @NonNull // Original line is essential for hashing and quarantine
    private String originalRawLine;
    @NonNull // Hash is essential for duplicate checks
    private String cdrHash;
    private String callingPartyNumber; // Effective calling party after parser interpretation
    private String calledPartyNumber;  // Effective called party after parser interpretation
    private String authCode;

    // Timestamps & Duration
    private LocalDateTime callStartTime; // Typically dateTimeOrigination
    private LocalDateTime callConnectTime; // Optional: dateTimeConnect
    private LocalDateTime callEndTime; // Typically dateTimeDisconnect
    private int durationSeconds; // Calculated or direct from CDR
    private int ringDurationSeconds; // Calculated by parser

    // Call Characteristics (Interpreted by Parser)
    private boolean isIncoming;
    private boolean isConference; // Flag indicating if this leg is part of a conference setup/participation

    // Call Type Hint (Basic classification by Parser)
    public enum CallTypeHint {
        UNKNOWN, INTERNAL, LOCAL, NATIONAL, INTERNATIONAL, MOBILE, CONFERENCE, SPECIAL_SERVICE, OTHER
    }
    @Builder.Default // Default hint if parser cannot determine
    private CallTypeHint callTypeHint = CallTypeHint.UNKNOWN;

    // Routing & Device Info
    private String sourceTrunkIdentifier; // e.g., origDeviceName
    private String destinationTrunkIdentifier; // e.g., destDeviceName
    private String redirectingPartyNumber; // e.g., lastRedirectDn (after potential swaps by parser)
    private Integer redirectReason; // e.g., lastRedirectReason
    private Integer disconnectCause; // e.g., destTerminationCause

    // Source Metadata
    private Long communicationLocationId;
    private Long fileInfoId; // Optional: Link to source file info
    private String sourceDescription; // Optional: Description of the source (e.g., filename)

}