package com.infomedia.abacox.telephonypricing.cdr;

public enum TelephonyTypeEnum {
    UNKNOWN(0L), // Default or placeholder
    CELUFIJO(1L),
    CELLULAR(2L),
    LOCAL(3L),
    NATIONAL(4L),
    INTERNATIONAL(5L),
    VARIOUS_SERVICES(6L),
    INTERNAL_IP(7L), // PHP: _TIPOTELE_INTERNAL_IP
    LOCAL_IP(8L),    // PHP: _TIPOTELE_LOCAL_IP
    NATIONAL_IP(9L), // PHP: _TIPOTELE_NACIONAL_IP
    SATELLITE(10L),
    SPECIAL_SERVICES(11L), // PHP: _TIPOTELE_ESPECIALES
    LOCAL_EXTENDED(12L),
    REVERTED_PAYMENT(13L),
    INTERNAL_INTERNATIONAL_IP(14L), // Assuming this maps to PHP's _TIPOTELE_INTERNAL_IP when countries differ
    // ID 15 is missing in CSV
    NO_CONSUMPTION(16L), // PHP: _TIPOTELE_SINCONSUMO (was 98, now 16 based on CSV)
    ERRORS(97L), // PHP: _TIPOTELE_ERRORES
    INTERNAL_SIMPLE(100L); // A general internal type if more specific IP types don't apply

    private final long value;

    TelephonyTypeEnum(long value) {
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    public static TelephonyTypeEnum fromId(Long id) {
        if (id == null) return UNKNOWN;
        for (TelephonyTypeEnum type : values()) {
            if (type.value == id) {
                return type;
            }
        }
        // Fallback for PHP's original _TIPOTELE_SINCONSUMO if data hasn't migrated
        if (id == 98L) return NO_CONSUMPTION;
        return UNKNOWN;
    }

    public static boolean isInternalIpType(Long telephonyTypeId) {
        if (telephonyTypeId == null) return false;
        return telephonyTypeId == INTERNAL_IP.getValue() ||
               telephonyTypeId == LOCAL_IP.getValue() ||
               telephonyTypeId == NATIONAL_IP.getValue() ||
               telephonyTypeId == INTERNAL_INTERNATIONAL_IP.getValue() ||
               telephonyTypeId == INTERNAL_SIMPLE.getValue();
    }
}