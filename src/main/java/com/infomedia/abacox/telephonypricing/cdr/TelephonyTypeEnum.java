// src/main/java/com/infomedia/abacox/cdr/processing/enums/TelephonyTypeEnum.java
package com.infomedia.abacox.telephonypricing.cdr;

public enum TelephonyTypeEnum {
    UNKNOWN(0),
    CELUFIJO(1),
    CELLULAR(2),
    LOCAL(3),
    NATIONAL(4),
    INTERNATIONAL(5),
    VARIOUS_SERVICES(6),
    INTERNAL_IP(7),
    LOCAL_IP(8),
    NATIONAL_IP(9),
    SATELLITE(10), // Added SATELLITE (assuming value 10, adjust if different)
    SPECIAL_SERVICES(11),
    LOCAL_EXTENDED(12),
    REVERTED_PAYMENT(13),
    ERRORS(97),
    NO_CONSUMPTION(98),
    INTERNAL_SIMPLE(100);


    private final long value;
    TelephonyTypeEnum(long value) { this.value = value; }
    public long getValue() { return value; }

    public static TelephonyTypeEnum fromId(Long id) {
        if (id == null) return UNKNOWN;
        for (TelephonyTypeEnum type : values()) {
            if (type.value == id) {
                return type;
            }
        }
        return UNKNOWN;
    }
}