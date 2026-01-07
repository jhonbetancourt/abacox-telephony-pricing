package com.infomedia.abacox.telephonypricing.component.configmanager;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Value {

    private String key;

    private String value;

    /**
     * Helper to create a consistent and informative error message.
     */
    private String getErrorMessage(String targetType) {
        return String.format("Configuration value '%s' for key '%s' cannot be converted to %s.", value, key, targetType);
    }

    public Integer asInt() {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getErrorMessage("Integer"), e);
        }
    }

    public Long asLong() {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getErrorMessage("Long"), e);
        }
    }

    public Double asDouble() {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getErrorMessage("Double"), e);
        }
    }

    /**
     * Note: Boolean.parseBoolean does not throw an exception.
     * It returns `true` if the string value is "true" (case-insensitive), and `false` otherwise.
     * This implementation maintains that standard behavior.
     */
    public Boolean asBoolean() {
        return Boolean.parseBoolean(value);
    }

    public byte[] asBinary() {
        try {
            // UTF-8 is a standard charset guaranteed to be available in any Java installation.
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // This exception should theoretically never be thrown.
            // Wrapping it in an IllegalStateException is a common pattern for "unreachable" checked exceptions.
            throw new IllegalStateException("UTF-8 encoding is not supported, which should be impossible.", e);
        }
    }

    public BigDecimal asBigDecimal() {
        try {
            // The constructor can handle very large numbers and scientific notation.
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getErrorMessage("BigDecimal"), e);
        }
    }

    public Float asFloat() {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getErrorMessage("Float"), e);
        }
    }

    public String asString() {
        return value;
    }

    /**
     * Parses the value as a comma-separated list of strings.
     * Trims whitespace from each element. Returns an empty list if the value is null or blank.
     *
     * @return A List of strings.
     */
    public List<String> asStringList() {
        return asStringList(",");
    }

    /**
     * Parses the value as a list of strings using a specified delimiter.
     * Trims whitespace from each element. Returns an empty list if the value is null or blank.
     *
     * @param delimiter The delimiter to split the string by (e.g., ",", ";", "|").
     * @return A List of strings.
     */
    public List<String> asStringList(String delimiter) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(delimiter))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    /**
     * Parses the value as a UUID.
     * @return The UUID object.
     */
    public UUID asUUID() {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(getErrorMessage("UUID"), e);
        }
    }

    /**
     * Parses the value as a Duration (e.g., "PT15M" for 15 minutes).
     * @return The Duration object.
     * @see java.time.Duration#parse(CharSequence)
     */
    public Duration asDuration() {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(getErrorMessage("Duration (ISO-8601 format)"), e);
        }
    }

    /**
     * Parses the value as an Instant (e.g., "2023-10-27T10:15:30.00Z").
     * @return The Instant object.
     * @see java.time.Instant#parse(CharSequence)
     */
    public Instant asInstant() {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(getErrorMessage("Instant (ISO-8601 format)"), e);
        }
    }
}