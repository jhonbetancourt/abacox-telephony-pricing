package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.db.entity.ConfigValue;
import com.infomedia.abacox.telephonypricing.repository.ConfigValueRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manages application configuration values stored in the database.
 *
 * This service implements a "read-through/write-through" caching strategy to minimize database access.
 * - On startup, all configuration values are loaded into an in-memory cache.
 * - Read operations (getValue, findValue) are served directly from the cache.
 * - Write operations (setValue, updateConfiguration) update both the database and the cache to ensure consistency.
 *
 * The service also provides a thread-safe callback mechanism for components that need to react to configuration changes.
 */
@Service
@Log4j2
public class ConfigManagerService extends CrudService<ConfigValue, Long, ConfigValueRepository> {

    // Thread-safe cache for configuration values. Key: ConfigKey, Value: String
    private final Map<String, String> configCache = new ConcurrentHashMap<>();

    // Thread-safe map for update callbacks. The list of callbacks is also thread-safe.
    private final Map<String, List<UpdateCallback>> updateCallbacks = new ConcurrentHashMap<>();

    public ConfigManagerService(ConfigValueRepository repository) {
        super(repository);
    }

    /**
     * Loads all configuration values from the database into the cache on application startup.
     */
    @PostConstruct
    public void initializeCache() {
        log.info("Initializing configuration cache...");
        Map<String, ConfigValue> dbValues = getRepository().findAll().stream()
                .collect(Collectors.toMap(ConfigValue::getKey, Function.identity()));
        configCache.putAll(dbValues.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getValue())));
        log.info("Configuration cache initialized with {} entries.", configCache.size());
    }

    public String getValue(String configKey, String defaultValue) {
        return configCache.getOrDefault(configKey, defaultValue);
    }

    @Transactional
    public void setValue(String configKey, String value) {
        setByKey(configKey, value);
    }

    protected void setByKey(String configKey, String newValue) {
        String oldValue = configCache.get(configKey);

        // Only proceed if the value has actually changed
        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        // Update or create the value in the database
        ConfigValue configValue = getRepository().findByKey(configKey)
                .orElseGet(() -> ConfigValue.builder().key(configKey).build());

        configValue.setValue(newValue);
        getRepository().save(configValue);

        // Update the cache (Write-through)
        configCache.put(configKey, newValue);
        log.info("Updated config key '{}' to new value.", configKey);

        // Trigger callbacks asynchronously if the value changed
        onUpdateValue(configKey, newValue);
    }

    public Map<String, Object> getConfiguration() {
        return new HashMap<>(configCache);
    }

    public Map<String, Object> getConfigurationByKeys(List<String> keys) {
        return keys.stream()
                .filter(configCache::containsKey)
                .collect(Collectors.toMap(Function.identity(), configCache::get));
    }

    public Map<String, Object> getConfigurationByKeys(Map<String, String> keysAndDefaults) {
        return keysAndDefaults.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> configCache.getOrDefault(entry.getKey(), entry.getValue())
                ));
    }

    @Transactional
    public void updateConfiguration(Map<String, String> configMap) {
        configMap.forEach(this::setByKey);
    }

    // --- Callback Mechanism ---

    public interface UpdateCallback {
        <T> void onUpdate(T value);
    }

    /**
     * Registers a callback to be executed when a specific configuration value changes.
     *
     * @param configKey The key to monitor.
     * @param callback  The callback to execute.
     */
    public void registerUpdateCallback(String configKey, UpdateCallback callback) {
        updateCallbacks.computeIfAbsent(configKey, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * Unregisters a previously registered callback.
     *
     * @param configKey The key the callback was registered for.
     * @param callback  The callback to remove.
     */
    public void unregisterUpdateCallback(String configKey, UpdateCallback callback) {
        List<UpdateCallback> callbacks = updateCallbacks.get(configKey);
        if (callbacks != null) {
            callbacks.remove(callback);
        }
    }

    private <T> void onUpdateValue(String configKey, T value) {
        List<UpdateCallback> callbacks = updateCallbacks.get(configKey);
        if (callbacks != null && !callbacks.isEmpty()) {
            callbacks.forEach(callback ->
                    new Thread(() -> {
                        try {
                            callback.onUpdate(value);
                        } catch (Exception e) {
                            log.error("Error executing update callback for key '{}'", configKey, e);
                        }
                    }).start());
        }
    }
}