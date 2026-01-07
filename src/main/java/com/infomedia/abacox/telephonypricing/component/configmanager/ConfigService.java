package com.infomedia.abacox.telephonypricing.component.configmanager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigValueService configValueService;

    /**
     * Retrieves all configuration values for a specific group for the CURRENT tenant.
     *
     * @param group The ConfigGroup to retrieve values for.
     * @return A ValueGroup object containing all key-value pairs for that group.
     */
    public ValueGroup getConfiguration(ConfigGroup group) {
        // 1. Get defaults defined in the Enum
        Map<String, String> keysAndDefaults = Arrays.stream(ConfigKey.values())
                .filter(key -> key.getGroup() == group)
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));

        // 2. Fetch the values (Lazy-loads tenant cache if needed)
        Map<String, Object> rawValues = configValueService.getConfigurationByGroup(group.name(), keysAndDefaults);

        // 3. Wrap result
        return new ValueGroup(group.name(), rawValues);
    }

    public Map<String, Object> getConfigurationMap(ConfigGroup group) {
        Map<String, String> keysAndDefaults = Arrays.stream(ConfigKey.values())
                .filter(key -> key.getGroup() == group)
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));

        return configValueService.getConfigurationByGroup(group.name(), keysAndDefaults);
    }

    public void updateConfiguration(ConfigGroup group, Map<String, Object> newConfig) {
        configValueService.updateConfiguration(group.name(), newConfig);
    }

    public void updateValue(ConfigKey configKey, String newValue) {
        configValueService.setValue(configKey.getGroup().name(), configKey.getKey(), newValue);
    }

    public void updateValue(ConfigKey configKey, Object newValue) {
        String valueAsString = (newValue == null) ? null : newValue.toString();
        configValueService.setValue(configKey.getGroup().name(), configKey.getKey(), valueAsString);
    }

    public Value getValue(ConfigKey configKey) {
        return configValueService.getValue(configKey.getGroup().name(), configKey.getKey(), configKey.getDefaultValue());
    }

    public void registerUpdateCallback(ConfigKey configKey, Consumer<Value> callback) {
        configValueService.registerUpdateCallback(configKey.getGroup().name(), configKey.getKey(), callback);
    }

    public void unregisterUpdateCallback(ConfigKey configKey, Consumer<Value> callback) {
        configValueService.unregisterUpdateCallback(configKey.getGroup().name(), configKey.getKey(), callback);
    }

    public void registerGroupUpdateCallback(ConfigGroup group, Consumer<ValueGroup> callback) {
        Map<String, String> keysAndDefaults = Arrays.stream(ConfigKey.values())
                .filter(key -> key.getGroup() == group)
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));

        configValueService.registerGroupUpdateCallback(group.name(), keysAndDefaults, callback);
    }

    public void unregisterGroupUpdateCallback(ConfigGroup group, Consumer<ValueGroup> callback) {
        configValueService.unregisterGroupUpdateCallback(group.name(), callback);
    }
}