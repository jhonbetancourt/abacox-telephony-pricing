package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.config.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigManagerService configManagerService;

    public Map<String, Object> getPublicConfiguration() {
        Map<String, String> publicKeysAndDefaults =
                ConfigKey.getPublicKeys().stream()
                .collect(Collectors.toMap(ConfigKey::getKey, ConfigKey::getDefaultValue));
        return configManagerService.getConfigurationByKeys(publicKeysAndDefaults);
    }

    public Map<String, Object> updatePublicConfiguration(Map<String, Object> newConfig) {
        List<String> publicKeys = ConfigKey.getPublicKeys().stream().map(ConfigKey::getKey).toList();
        Map<String, String> filteredConfig = newConfig.entrySet().stream()
                .filter(entry -> publicKeys.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        configManagerService.updateConfiguration(filteredConfig);
        return getPublicConfiguration();
    }

    public void updateValue(ConfigKey configKey, String newValue) {
        configManagerService.setValue(configKey.getKey(), newValue);
    }

    public String getValue(ConfigKey configKey) {
        return configManagerService.getValue(configKey.getKey(), configKey.getDefaultValue());
    }
}
