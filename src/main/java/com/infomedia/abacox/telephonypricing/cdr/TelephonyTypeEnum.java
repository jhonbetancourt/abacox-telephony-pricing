// File: com/infomedia/abacox/telephonypricing/cdr/TelephonyTypeEnum.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum TelephonyTypeEnum {
    UNKNOWN(0L, "Unknown"), // Default or placeholder
    CELUFIJO(1L, "Celufijo"),
    CELLULAR(2L, "Celular"),
    LOCAL(3L, "Local"),
    NATIONAL(4L, "Nacional"),
    INTERNATIONAL(5L, "Internacional"),
    VARIOUS_SERVICES(6L, "Servicios varios"),
    INTERNAL_SIMPLE(7L, "Interna"), // Mapped from CSV ID 7 "Interna"
    LOCAL_IP(8L, "Interna Local"),
    NATIONAL_IP(9L, "Interna Nacional"),
    SATELLITE(10L, "Satelital"),
    SPECIAL_SERVICES(11L, "Números especiales"),
    LOCAL_EXTENDED(12L, "Local extendida"),
    REVERTED_PAYMENT(13L, "Pago revertido"),
    INTERNAL_INTERNATIONAL_IP(14L, "Interna Internacional"),
    // ID 15 is missing in CSV
    NO_CONSUMPTION(16L, "Sin Consumo"),
    ERRORS(97L, "Errores (No Válido)"); // PHP: _TIPOTELE_ERRORES

    private final long value;
    private final String defaultName;

    TelephonyTypeEnum(long value, String defaultName) {
        this.value = value;
        this.defaultName = defaultName;
    }

    public long getValue() {
        return value;
    }

    public String getDefaultName() { return defaultName; }

    public static TelephonyTypeEnum fromId(Long id) {
        if (id == null) return UNKNOWN;
        for (TelephonyTypeEnum type : values()) {
            if (type.value == id) {
                return type;
            }
        }
        return UNKNOWN;
    }

    public static boolean isInternalIpType(Long telephonyTypeId) {
        if (telephonyTypeId == null) return false;
        return telephonyTypeId == INTERNAL_SIMPLE.getValue() || // "Interna" from CSV
               telephonyTypeId == LOCAL_IP.getValue() ||
               telephonyTypeId == NATIONAL_IP.getValue() ||
               telephonyTypeId == INTERNAL_INTERNATIONAL_IP.getValue();
    }

    public static List<Long> getInternalTypeIds() {
        return Arrays.asList(
                INTERNAL_SIMPLE.getValue(),
                LOCAL_IP.getValue(),
                NATIONAL_IP.getValue(),
                INTERNAL_INTERNATIONAL_IP.getValue()
        );
    }
}