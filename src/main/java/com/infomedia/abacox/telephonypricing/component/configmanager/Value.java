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

    private String group; // Added group for context

    private String key;

    private String value;

    // Constructor for backward compatibility or when group is not needed
    public Value(String key, String value) {
        this.key = key;
        this.value = value;
    }

    /**
     * Helper to create a consistent and informative error message.
     */
    private String getErrorMessage(String targetType) {
        return String.format("Configuration value '%s' for key '%s' in group '%s' cannot be converted to %s.", value, key, group, targetType);
    }

    // --- All other as...() methods remain exactly the same ---
    // (No changes needed for the conversion logic)

    public Integer asInteger() {
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

    public Boolean asBoolean() {
        return Boolean.parseBoolean(value);
    }

    public byte[] asBinary() {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 encoding is not supported, which should be impossible.", e);
        }
    }

    public BigDecimal asBigDecimal() {
        try {
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

    public List<String> asStringList() {
        return asStringList(",");
    }



    public List<String> asStringList(String delimiter) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(delimiter))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    public UUID asUUID() {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(getErrorMessage("UUID"), e);
        }
    }

    public Duration asDuration() {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(getErrorMessage("Duration (ISO-8601 format)"), e);
        }
    }

    public Instant asInstant() {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(getErrorMessage("Instant (ISO-8601 format)"), e);
        }
    }
}