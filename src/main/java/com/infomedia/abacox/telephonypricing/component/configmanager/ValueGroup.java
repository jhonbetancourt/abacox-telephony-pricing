package com.infomedia.abacox.telephonypricing.component.configmanager;

import lombok.Getter;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a collection of configuration values for a specific group.
 * This class provides a convenient, type-safe API to access configuration values
 * for a given group, falling back to defaults if a value is not set in the database.
 */
@Getter
public class ValueGroup {

    private final String group;
    private final Map<String, Value> values;

    /**
     * Constructs a ValueGroup from a map of raw key-value pairs.
     * It enriches this data by creating full Value objects, including their group, key, and value.
     *
     * @param group The configuration group this set of values belongs to.
     * @param rawValues A map of configuration keys to their object values.
     */
    public ValueGroup(String group, Map<String, Object> rawValues) {
        this.group = Objects.requireNonNull(group, "Group cannot be null");
        this.values = Objects.requireNonNull(rawValues, "Raw values map cannot be null")
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new Value(
                                group,
                                entry.getKey(),
                                entry.getValue() == null ? null : entry.getValue().toString()
                        )
                ));
    }

    /**
     * Retrieves a configuration value by its key.
     *
     * @param configKey The ConfigKey enum constant.
     * @return The Value object, guaranteed to be non-null.
     * @throws IllegalArgumentException if the provided ConfigKey does not belong to this group.
     */
    public Value getValue(ConfigKey configKey) {
        if (!configKey.getGroup().name().equals(group)) {
            throw new IllegalArgumentException(
                    String.format("ConfigKey '%s' does not belong to the '%s' group.", configKey.name(), this.group)
            );
        }
        return values.get(configKey.getKey());
    }
}