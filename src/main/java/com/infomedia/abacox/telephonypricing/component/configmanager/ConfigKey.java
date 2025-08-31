package com.infomedia.abacox.telephonypricing.component.configmanager;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.TelephonyTypeEnum;
import lombok.Getter;

import java.util.List;
import java.util.stream.Stream;

/**
 * Enum representing all configurable keys stored in the database.
 * Each key holds its default value to ensure the application can always run.
 */
@Getter
public enum ConfigKey {

    // PUBLIC KEYS
    // --- General CDR Processing ---
    SERVICE_DATE_HOUR_OFFSET("-5", true),
    SPECIAL_VALUE_TARIFFING("true", true),
    MIN_CALL_DURATION_FOR_TARIFFING("0", true), // in seconds
    MAX_CALL_DURATION_MINUTES("2880", true), // 48 hours
    MIN_ALLOWED_CAPTURE_DATE("2000-01-01", true),
    MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE("90", true),
    CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE("false", true),
    DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID(TelephonyTypeEnum.NATIONAL_IP.getValue().toString(), true), // Corresponds to TelephonyTypeEnum.NATIONAL_IP
    DEFAULT_INTERNAL_CALL_TYPE_ID(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue().toString(), true), // Corresponds to TelephonyTypeEnum.INTERNAL_SIMPLE
    EXTENSIONS_GLOBAL("false", true),
    AUTH_CODES_GLOBAL("false", true),

    // --- Text Placeholders ---
    ASSUMED_TEXT("(ASUMIDO)", true),
    ORIGIN_TEXT("(ORIGEN)", true),
    PREFIX_TEXT("(PREFIJO)", true),
    EMPLOYEE_NAME_PREFIX_FROM_RANGE("Funcionario", true),
    NO_PARTITION_PLACEHOLDER("NN-VALIDA", true),
    CDR_UPLOAD_API_KEY("024dc8fe-1d0d-41b2-8f96-dcf3ad9e4141", true);

    private final String defaultValue;
    private final boolean isPublic;

    ConfigKey(String defaultValue, boolean isPublic) {
        this.defaultValue = defaultValue;
        this.isPublic = isPublic;
    }

    public static List<ConfigKey> getPublicKeys() {
        return Stream.of(values())
            .filter(ConfigKey::isPublic)
                .toList();
    }

    public static List<ConfigKey> getPrivateKeys() {
        return Stream.of(values())
            .filter(key -> !key.isPublic())
            .toList();
    }

    public static List<ConfigKey> getAllKeys() {
        return Stream.of(values())
            .toList();
    }

    /**
     * Converts the enum's name from UPPER_SNAKE_CASE to lowerCamelCase.
     * For example, PENDING_APPROVAL becomes pendingApproval.
     *
     * @return The lowerCamelCase representation of the enum name.
     */
    public String getKey() {
        // 1. Get the name and convert to lower case: "pending_approval"
        String lowerCaseName = this.name().toLowerCase();

        // 2. Split by underscore: ["pending", "approval"]
        String[] parts = lowerCaseName.split("_");

        // If there's only one part (e.g., "shipped"), return it directly.
        if (parts.length == 1) {
            return parts[0];
        }

        // 3. Build the camelCase string
        StringBuilder camelCaseString = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            // 4. Capitalize the first letter of subsequent parts
            camelCaseString.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1));
        }

        return camelCaseString.toString();
    }
}