package com.infomedia.abacox.telephonypricing.cdr;

public enum FailedCallRecordErrorType {
    PARSING_ERROR("PARSING_ERROR"),
    ENRICHMENT_ERROR("ENRICHMENT_ERROR"),
    DB_ERROR("DB_ERROR"),
    VALIDATION_ERROR("VALIDATION_ERROR"),
    UNKNOWN_CDR_TYPE("UNKNOWN_CDR_TYPE"),
    CDR_ALREADY_PROCESSED("CDR_ALREADY_PROCESSED");


    private final String value;

    FailedCallRecordErrorType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}