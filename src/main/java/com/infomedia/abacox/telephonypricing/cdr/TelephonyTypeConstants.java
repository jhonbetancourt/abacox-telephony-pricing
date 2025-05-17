package com.infomedia.abacox.telephonypricing.cdr;

public class TelephonyTypeConstants {
    // These IDs are from your telephony_type_202504301518.csv
    // It's better to look these up by name from the DB in a real scenario or have them in config
    // But for direct PHP logic translation, hardcoding might be initially necessary if PHP uses IDs directly.
    public static final long CELUFIJO = 1L;
    public static final long CELULAR = 2L;
    public static final long LOCAL = 3L;
    public static final long NACIONAL = 4L;
    public static final long INTERNACIONAL = 5L;
    public static final long SERVICIOS_VARIOS = 6L;
    public static final long INTERNA = 7L;
    public static final long INTERNA_LOCAL = 8L;
    public static final long INTERNA_NACIONAL = 9L;
    public static final long SATELITAL = 10L;
    public static final long NUMEROS_ESPECIALES = 11L;
    public static final long LOCAL_EXTENDIDA = 12L;
    public static final long PAGO_REVERTIDO = 13L;
    public static final long INTERNA_INTERNACIONAL = 14L;
    public static final long SIN_CONSUMO = 16L;
    public static final long ERRORES = 97L; // Assuming 97 for errors as per PHP
    public static final long DEFAULT_INTERNAL_IP = 7L; // Example: TIPOTELE_INTERNA_IP
    public static final long DEFAULT_NATIONAL_IP = 9L; // Example: TIPOTELE_NACIONAL_IP
    public static final long DEFAULT_INTERNATIONAL_IP = 14L; // Example: TIPOTELE_INTERNAL_IP (International)
    public static final long DEFAULT_LOCAL_IP = 8L; // Example: TIPOTELE_LOCAL_IP
}