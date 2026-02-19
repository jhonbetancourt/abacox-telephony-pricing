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

    SERVICE_DATE_HOUR_OFFSET(ConfigGroup.CDR, "-5"),
    SPECIAL_VALUE_TARIFFING(ConfigGroup.CDR, "true"),
    MIN_CALL_DURATION_FOR_TARIFFING(ConfigGroup.CDR, "0"), // in seconds
    MAX_CALL_DURATION_MINUTES(ConfigGroup.CDR, "2880"), // 48 hours
    MIN_ALLOWED_CAPTURE_DATE(ConfigGroup.CDR, "2000-01-01"),
    MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE(ConfigGroup.CDR, "90"),
    CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE(ConfigGroup.CDR, "false"),
    DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID(ConfigGroup.CDR, TelephonyTypeEnum.NATIONAL_IP.getValue().toString()), // Corresponds
                                                                                                                    // to
                                                                                                                    // TelephonyTypeEnum.NATIONAL_IP
    EXTENSIONS_GLOBAL(ConfigGroup.CDR, "false"),
    AUTH_CODES_GLOBAL(ConfigGroup.CDR, "false"),

    // --- Text Placeholders ---
    ASSUMED_TEXT(ConfigGroup.CDR, "(ASUMIDO)"),
    ORIGIN_TEXT(ConfigGroup.CDR, "(ORIGEN)"),
    PREFIX_TEXT(ConfigGroup.CDR, "(PREFIJO)"),
    EMPLOYEE_NAME_PREFIX_FROM_RANGE(ConfigGroup.CDR, "Funcionario"),
    NO_PARTITION_PLACEHOLDER(ConfigGroup.CDR, "NN-VALIDA"),
    CDR_PROCESSING_ENABLED(ConfigGroup.CDR, "true"),
    CDR_UPLOAD_API_KEY(ConfigGroup.CDR, "024dc8fe-1d0d-41b2-8f96-dcf3ad9e4141");

    private final ConfigGroup group;
    private final String defaultValue;

    ConfigKey(ConfigGroup group, String defaultValue) {
        this.group = group;
        this.defaultValue = defaultValue;
    }

    public static List<ConfigKey> getKeys(ConfigGroup group) {
        return Stream.of(values())
                .filter(key -> key.getGroup().equals(group))
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