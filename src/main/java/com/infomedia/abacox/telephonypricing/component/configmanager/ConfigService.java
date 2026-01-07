package com.infomedia.abacox.telephonypricing.component.configmanager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigValueService configValueService;

    public Map<String, Object> getPublicConfiguration() {
        Map<String, String> publicKeysAndDefaults =
                ConfigKey.getPublicKeys().stream()
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));
        return configValueService.getConfigurationByKeys(publicKeysAndDefaults);
    }

    public void updatePublicConfiguration(Map<String, Object> newConfig) {
        List<String> publicKeys = ConfigKey.getPublicKeys().stream().map(ConfigKey::getKey).toList();

        Map<String, String> filteredConfig = newConfig.entrySet().stream()
                .filter(entry -> publicKeys.contains(entry.getKey()))
                .collect(
                        // 1. Supplier: How to create the map
                        HashMap::new,
                        // 2. Accumulator: How to add an entry to the map
                        (map, entry) -> map.put(
                                entry.getKey(),
                                entry.getValue() == null ? null : entry.getValue().toString()
                        ),
                        // 3. Combiner: How to merge two maps (for parallel streams)
                        HashMap::putAll
                );

        configValueService.updateConfiguration(filteredConfig);
    }

    public Map<String, Object> getPrivateConfiguration() {
        Map<String, String> privateKeysAndDefaults =
                ConfigKey.getPrivateKeys().stream()
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));
        return configValueService.getConfigurationByKeys(privateKeysAndDefaults);
    }

    public void updatePrivateConfiguration(Map<String, Object> newConfig) {
        List<String> privateKeys = ConfigKey.getPrivateKeys().stream().map(ConfigKey::getKey).toList();

        Map<String, String> filteredConfig = newConfig.entrySet().stream()
                .filter(entry -> privateKeys.contains(entry.getKey()))
                .collect(
                        // 1. Supplier: How to create the map
                        HashMap::new,
                        // 2. Accumulator: How to add an entry to the map
                        (map, entry) -> map.put(
                                entry.getKey(),
                                entry.getValue() == null ? null : entry.getValue().toString()
                        ),
                        // 3. Combiner: How to merge two maps (for parallel streams)
                        HashMap::putAll
                );

        configValueService.updateConfiguration(filteredConfig);
    }

    public void updateValue(ConfigKey configKey, String newValue) {
        configValueService.setValue(configKey.getKey(), newValue);
    }

    public void updateValue(ConfigKey configKey, Object newValue) {
        configValueService.setValue(configKey.getKey(), newValue.toString());
    }

    public Value getValue(ConfigKey configKey) {
        return configValueService.getValue(configKey.getKey(), configKey.getDefaultValue());
    }

    public void registerUpdateCallback(ConfigKey configKey, Consumer<Value> callback) {
        configValueService.registerUpdateCallback(configKey.getKey(), callback);
    }

    public void unregisterUpdateCallback(ConfigKey configKey, Consumer<Value> callback) {
        configValueService.unregisterUpdateCallback(configKey.getKey(), callback);
    }
}
