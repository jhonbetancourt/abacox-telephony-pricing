package com.infomedia.abacox.telephonypricing.component.configmanager;

import com.infomedia.abacox.telephonypricing.db.entity.ConfigValue;
import com.infomedia.abacox.telephonypricing.db.repository.ConfigValueRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 * This implementation uses a "Null Object Pattern" to store null values in the ConcurrentHashMap cache,
 * which does not natively support nulls.
 */
@Service
@Log4j2
public class ConfigValueService extends CrudService<ConfigValue, Long, ConfigValueRepository> {

    // A special, private object used to represent null values in the ConcurrentHashMap.
    private static final Object NULL_PLACEHOLDER = new Object();

    // Thread-safe cache. The value is Object to accommodate both Strings and the NULL_PLACEHOLDER.
    private final Map<String, Object> configCache = new ConcurrentHashMap<>();

    // Thread-safe map for update callbacks. The list of callbacks is also thread-safe.
    private final Map<String, List<Consumer<Value>>> updateCallbacks = new ConcurrentHashMap<>();

    public ConfigValueService(ConfigValueRepository repository) {
        super(repository);
    }

    /**
     * Encodes a String value for storage in the cache. Converts null to the placeholder.
     */
    private Object encodeValue(String value) {
        return value == null ? NULL_PLACEHOLDER : value;
    }

    /**
     * Decodes an Object from the cache. Converts the placeholder back to null.
     */
    private String decodeValue(Object storedValue) {
        return storedValue == NULL_PLACEHOLDER ? null : (String) storedValue;
    }

    /**
     * Loads all configuration values from the database into the cache on application startup.
     */
    @PostConstruct
    public void initializeCache() {
        log.info("Initializing configuration cache...");
        // We cannot use putAll directly; we must encode each value.
        getRepository().findAll().forEach(configValue -> {
            configCache.put(configValue.getKey(), encodeValue(configValue.getValue()));
        });
        log.info("Configuration cache initialized with {} entries.", configCache.size());
    }

    public Value getValue(String configKey, String defaultValue) {
        Object storedValue = configCache.get(configKey);
        // If the key doesn't exist in the cache, storedValue will be null.
        if (storedValue == null) {
            return new Value(configKey, defaultValue);
        }
        // If the key exists, decode its value (which might be null).
        return new Value(configKey, decodeValue(storedValue));
    }

    @Transactional
    public void setValue(String configKey, String value) {
        setByKey(configKey, value);
    }

    protected void setByKey(String configKey, String newValue) {
        // Decode the old value from the cache for comparison.
        String oldValue = decodeValue(configCache.get(configKey));

        // Only proceed if the value has actually changed
        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        // Update or create the value in the database
        ConfigValue configValue = getRepository().findByKey(configKey)
                .orElseGet(() -> ConfigValue.builder().key(configKey).build());

        configValue.setValue(newValue);
        getRepository().save(configValue);

        // Update the cache (Write-through), encoding the new value.
        configCache.put(configKey, encodeValue(newValue));
        log.info("Updated config key '{}' to new value.", configKey);

        // Trigger callbacks with the original (decoded) new value.
        onUpdateValue(configKey, newValue);
    }

    public Map<String, Object> getConfiguration() {
        // Decode all values before returning the map to the caller.
        return configCache.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> decodeValue(entry.getValue())
                ));
    }

    public Map<String, Object> getConfigurationByKeys(List<String> keys) {
        return keys.stream()
                .filter(configCache::containsKey)
                .collect(Collectors.toMap(
                        Function.identity(),
                        key -> decodeValue(configCache.get(key))
                ));
    }

    public Map<String, Object> getConfigurationByKeys(Map<String, String> keysAndDefaults) {
        return keysAndDefaults.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            Object storedValue = configCache.get(entry.getKey());
                            // If key is not in cache, use the provided default.
                            // Otherwise, decode the value from the cache.
                            return storedValue == null ? entry.getValue() : decodeValue(storedValue);
                        }
                ));
    }

    @Transactional
    public void updateConfiguration(Map<String, String> configMap) {
        // The map passed in can contain nulls, and setByKey handles them correctly.
        configMap.forEach(this::setByKey);
    }

    // --- Callback Mechanism (No changes needed here) ---

    public void registerUpdateCallback(String configKey, Consumer<Value> callback) {
        updateCallbacks.computeIfAbsent(configKey, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public void unregisterUpdateCallback(String configKey, Consumer<Value> callback) {
        List<Consumer<Value>> callbacks = updateCallbacks.get(configKey);
        if (callbacks != null) {
            callbacks.remove(callback);
        }
    }

    private void onUpdateValue(String configKey, String value) {
        List<Consumer<Value>> callbacks = updateCallbacks.get(configKey);
        if (callbacks != null && !callbacks.isEmpty()) {
            callbacks.forEach(callback ->
                    new Thread(() -> {
                        try {
                            // The callback receives the actual value (e.g., a null String), not the placeholder.
                            callback.accept(new Value(configKey, value));
                        } catch (Exception e) {
                            log.error("Error executing update callback for key '{}'", configKey, e);
                        }
                    }).start());
        }
    }
}