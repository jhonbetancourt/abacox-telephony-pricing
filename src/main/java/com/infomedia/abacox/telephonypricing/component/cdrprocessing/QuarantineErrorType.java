// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/QuarantineErrorType.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.Getter;

@Getter
public enum QuarantineErrorType {
    // From PHP's ReportarErrores $tipo
    INITIAL_VALIDATION_ERROR("CDRNOVAL", "Initial CDR data validation failed"), // PHP: CDRNOVAL
    INITIAL_VALIDATION_WARNING("CRNPREV", "Preventive quarantine due to validation warning"), // PHP: CRNPREV
    GLOBAL_EXTENSION_IGNORE("IGNORAGLOBAL", "Ignored due to global extension/plant routing rules"),
    DB_INSERT_FAILED("NOINSERTA", "Failed to insert into main call record table"),
    DUPLICATE_RECORD("REGDUPLICADO", "Duplicate record found during main table insertion"),
    CUSTOM_IGNORE("IGNORAPROPIOS", "Ignored by custom processing rules"),
    PENDING_ASSOCIATION("PENDIENTES", "CDR could not be associated with a target (e.g., in HACHA)"),
    INTERNAL_SELF_CALL("IGUALDESTINO", "Internal call where origin and destination are the same"),

    // Additional types for Java context
    INVALID_DATE("FECHANOVAL", "Origination date is missing, before minimum allowed, or too far in the future"),
    INVALID_DURATION("TIEMPONOVAL", "Call duration is negative, less than minimum, or exceeds maximum allowed"),
    INVALID_NUMBER_FORMAT("NUMNOVAL", "Calling or called party number contains invalid characters or spaces"),
    INTERNAL_POLICY_IGNORE("IGNORAINTERNA", "Ignored due to specific internal call routing/policy rules"),
    INSUFFICIENT_FIELDS("CAMPOSINSUF", "CDR line has fewer fields than the required minimum based on the header"),
    PARSER_ERROR("PARSER_ERROR", "Error during CDR line parsing"),
    ENRICHMENT_ERROR("ENRICHMENT_ERROR", "Error during CDR data enrichment"),
    ENRICHMENT_WARNING("ENRICHMENT_WARNING", "Warning during CDR data enrichment leading to quarantine"),
    UNHANDLED_EXCEPTION("UNHANDLED_EXCEPTION", "An unexpected error occurred during processing"),
    MISSING_HEADER("MISSING_HEADER", "CDR data encountered before header was processed"),
    IO_EXCEPTION("IO_EXCEPTION", "Error reading CDR input stream"),
    PARSER_QUARANTINE("PARSER_QUARANTINE", "Marked for quarantine by the CDR parser directly");

    private final String phpEquivalent;
    private final String description;

    QuarantineErrorType(String phpEquivalent, String description) {
        this.phpEquivalent = phpEquivalent;
        this.description = description;
    }

    public static QuarantineErrorType fromPhpType(String phpType) {
        for (QuarantineErrorType type : values()) {
            if (type.getPhpEquivalent().equalsIgnoreCase(phpType)) {
                return type;
            }
        }
        // Fallback or throw exception if not found
        switch (phpType.toUpperCase()) {
            case "CDRNOVAL": return INITIAL_VALIDATION_ERROR;
            case "CRNPREV": return INITIAL_VALIDATION_WARNING;
            case "IGNORAGLOBAL": return GLOBAL_EXTENSION_IGNORE;
            case "NOINSERTA": return DB_INSERT_FAILED;
            case "REGDUPLICADO": return DUPLICATE_RECORD;
            case "IGNORAPROPIOS": return CUSTOM_IGNORE;
            case "PENDIENTES": return PENDING_ASSOCIATION;
            case "IGUALDESTINO": return INTERNAL_SELF_CALL;
            case "FECHANOVAL": return INVALID_DATE;
            case "TIEMPONOVAL": return INVALID_DURATION;
            case "NUMNOVAL": return INVALID_NUMBER_FORMAT;
            case "IGNORAINTERNA": return INTERNAL_POLICY_IGNORE;
            case "CAMPOSINSUF": return INSUFFICIENT_FIELDS;
            default:
                return UNHANDLED_EXCEPTION; // Or a more generic "UNKNOWN_PHP_TYPE"
        }
    }
}