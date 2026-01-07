package com.infomedia.abacox.telephonypricing.component.configmanager;

import com.infomedia.abacox.telephonypricing.db.entity.ConfigValue;
import com.infomedia.abacox.telephonypricing.db.repository.ConfigValueRepository;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Log4j2
public class ConfigValueService extends CrudService<ConfigValue, Long, ConfigValueRepository> {

    private static final Object NULL_PLACEHOLDER = new Object();
    private static final String DEFAULT_GROUP = "default";
    private static final String FALLBACK_TENANT = "public";

    // Cache: Tenant -> Group -> Key -> Value
    private final Map<String, Map<String, Map<String, Object>>> tenantConfigCache = new ConcurrentHashMap<>();

    // Callbacks: Group:Key -> List of Consumers
    private final Map<String, List<Consumer<Value>>> updateCallbacks = new ConcurrentHashMap<>();

    private static final class GroupCallbackRegistration {
        final Consumer<ValueGroup> callback;
        final Map<String, String> keysAndDefaults;

        GroupCallbackRegistration(Consumer<ValueGroup> callback, Map<String, String> keysAndDefaults) {
            this.callback = callback;
            this.keysAndDefaults = keysAndDefaults;
        }
    }

    private final Map<String, List<GroupCallbackRegistration>> groupUpdateCallbacks = new ConcurrentHashMap<>();

    public ConfigValueService(ConfigValueRepository repository) {
        super(repository);
    }

    private Object encodeValue(String value) {
        return value == null ? NULL_PLACEHOLDER : value;
    }

    private String decodeValue(Object storedValue) {
        return storedValue == NULL_PLACEHOLDER ? null : (String) storedValue;
    }

    private String getCallbackKey(String group, String key) {
        return group + ":" + key;
    }

    /**
     * Safely resolves the current tenant.
     * If TenantContext is null (e.g. during startup), defaults to "public".
     */
    private String resolveCurrentTenant() {
        String tenant = TenantContext.getTenant();
        return (tenant != null) ? tenant : FALLBACK_TENANT;
    }

    /**
     * Ensures the cache for the CURRENT tenant is loaded.
     */
    private void ensureTenantCacheLoaded() {
        String currentTenant = resolveCurrentTenant();

        if (tenantConfigCache.containsKey(currentTenant)) {
            return;
        }

        log.debug("Loading configuration cache for tenant: {}", currentTenant);
        try {
            // Because we are inside a TenantContext (or defaulted to public), 
            // this repository call goes to the correct schema.
            Map<String, Map<String, Object>> tenantCache = new ConcurrentHashMap<>();
            
            getRepository().findAll().forEach(configValue -> {
                String group = Objects.requireNonNullElse(configValue.getGroup(), DEFAULT_GROUP);
                tenantCache
                    .computeIfAbsent(group, k -> new ConcurrentHashMap<>())
                    .put(configValue.getKey(), encodeValue(configValue.getValue()));
            });
            
            tenantConfigCache.put(currentTenant, tenantCache);
        } catch (Exception e) {
            // Fail gracefully if schema doesn't exist yet (e.g. bootstrapping)
            // Store an empty map so we don't retry DB calls endlessly on every get()
            log.warn("Could not load configs for tenant '{}'. Schema might be missing. Using empty cache.", currentTenant);
            tenantConfigCache.put(currentTenant, new ConcurrentHashMap<>());
        }
    }

    public void invalidateCurrentTenantCache() {
        tenantConfigCache.remove(resolveCurrentTenant());
    }

    public Value getValue(String group, String configKey, String defaultValue) {
        ensureTenantCacheLoaded();
        String currentTenant = resolveCurrentTenant();
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);

        // Safe get: tenantConfigCache key is guaranteed not null by resolveCurrentTenant()
        Map<String, Object> groupCache = tenantConfigCache
                .getOrDefault(currentTenant, Map.of())
                .get(effectiveGroup);

        if (groupCache == null) {
            return new Value(effectiveGroup, configKey, defaultValue);
        }

        Object storedValue = groupCache.get(configKey);
        if (storedValue == null) {
            return new Value(effectiveGroup, configKey, defaultValue);
        }

        return new Value(effectiveGroup, configKey, decodeValue(storedValue));
    }

    @Transactional
    public void setValue(String group, String configKey, String value) {
        setByGroupAndKey(group, configKey, value);
    }

    protected void setByGroupAndKey(String group, String configKey, String newValue) {
        ensureTenantCacheLoaded();
        String currentTenant = resolveCurrentTenant();
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);

        // Check if changed
        Map<String, Map<String, Object>> tenantCache = tenantConfigCache.get(currentTenant);
        String oldValue = Optional.ofNullable(tenantCache.get(effectiveGroup))
                .map(g -> g.get(configKey))
                .map(this::decodeValue)
                .orElse(null);

        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        // DB Update
        ConfigValue configValue = getRepository().findByGroupAndKey(effectiveGroup, configKey)
                .orElseGet(() -> ConfigValue.builder().group(effectiveGroup).key(configKey).build());

        configValue.setValue(newValue);
        getRepository().save(configValue);

        // Cache Update
        tenantCache
                .computeIfAbsent(effectiveGroup, k -> new ConcurrentHashMap<>())
                .put(configKey, encodeValue(newValue));

        log.info("[Tenant: {}] Updated config '{}' in group '{}'.", currentTenant, configKey, effectiveGroup);

        onUpdateValue(effectiveGroup, configKey, newValue);
    }

    public Map<String, Object> getConfigurationByGroup(String group, Map<String, String> keysAndDefaults) {
        ensureTenantCacheLoaded();
        String currentTenant = resolveCurrentTenant();
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);

        Map<String, Object> groupCache = tenantConfigCache
                .getOrDefault(currentTenant, Map.of())
                .get(effectiveGroup);

        return keysAndDefaults.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (groupCache == null) return entry.getValue();
                            Object storedValue = groupCache.get(entry.getKey());
                            return storedValue == null ? entry.getValue() : decodeValue(storedValue);
                        }
                ));
    }

    public Map<String, Object> getConfigurationByGroup(String group) {
        ensureTenantCacheLoaded();
        String currentTenant = resolveCurrentTenant();
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);

        Map<String, Object> groupCache = tenantConfigCache
                .getOrDefault(currentTenant, Map.of())
                .get(effectiveGroup);

        if (groupCache == null) return Map.of();

        return groupCache.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> decodeValue(entry.getValue())
                ));
    }

    @Transactional
    public void updateConfiguration(String group, Map<String, Object> configMap) {
        configMap.forEach((key, value) ->
                setByGroupAndKey(group, key, value == null ? null : value.toString()));
    }

    // --- Callback Registration ---

    public void registerUpdateCallback(String group, String configKey, Consumer<Value> callback) {
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);
        String callbackKey = getCallbackKey(effectiveGroup, configKey);
        updateCallbacks.computeIfAbsent(callbackKey, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public void unregisterUpdateCallback(String group, String configKey, Consumer<Value> callback) {
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);
        String callbackKey = getCallbackKey(effectiveGroup, configKey);
        List<Consumer<Value>> callbacks = updateCallbacks.get(callbackKey);
        if (callbacks != null) {
            callbacks.remove(callback);
        }
    }

    public void registerGroupUpdateCallback(String group, Map<String, String> keysAndDefaults, Consumer<ValueGroup> callback) {
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);
        groupUpdateCallbacks
                .computeIfAbsent(effectiveGroup, k -> new CopyOnWriteArrayList<>())
                .add(new GroupCallbackRegistration(callback, keysAndDefaults));
    }

    public void unregisterGroupUpdateCallback(String group, Consumer<ValueGroup> callback) {
        String effectiveGroup = Objects.requireNonNullElse(group, DEFAULT_GROUP);
        List<GroupCallbackRegistration> regs = groupUpdateCallbacks.get(effectiveGroup);
        if (regs != null) {
            regs.removeIf(r -> r.callback == callback);
        }
    }

    private void onUpdateValue(String group, String configKey, String value) {
        Value changedValue = new Value(group, configKey, value);
        String callbackKey = getCallbackKey(group, configKey);
        
        List<Consumer<Value>> keyCallbacks = updateCallbacks.get(callbackKey);
        if (keyCallbacks != null) {
            keyCallbacks.forEach(cb -> safeRun(() -> cb.accept(changedValue)));
        }

        List<GroupCallbackRegistration> groupRegs = groupUpdateCallbacks.get(group);
        if (groupRegs != null) {
            groupRegs.forEach(reg -> safeRun(() -> {
                Map<String, Object> snapshot = (reg.keysAndDefaults == null)
                        ? getConfigurationByGroup(group)
                        : getConfigurationByGroup(group, reg.keysAndDefaults);
                reg.callback.accept(new ValueGroup(group, snapshot));
            }));
        }
    }

    private void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Error executing config update callback", e);
        }
    }

    public Map<String, Map<String, Object>> getConfiguration() {
        ensureTenantCacheLoaded();
        String currentTenant = resolveCurrentTenant();

        Map<String, Map<String, Object>> tenantCache = tenantConfigCache.get(currentTenant);

        if (tenantCache == null) {
            return Map.of();
        }

        // Return a decoded copy of the map
        return tenantCache.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        groupEntry -> groupEntry.getValue().entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        valueEntry -> decodeValue(valueEntry.getValue())
                                ))
                ));
    }
}