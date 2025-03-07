package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.config.ConfigKey;
import com.infomedia.abacox.telephonypricing.entity.Configuration;
import com.infomedia.abacox.telephonypricing.repository.ConfigurationRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import com.infomedia.abacox.telephonypricing.service.remote.ControlService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ConfigurationService extends CrudService<Configuration, Long, ConfigurationRepository> {

    private final ControlService controlService;

    public ConfigurationService(ConfigurationRepository repository, ControlService controlService) {
        super(repository);
        this.controlService = controlService;
    }

    public Optional<String> getByKey(ConfigKey configKey) {
        return getRepository().findByKey(configKey.name()).map(Configuration::getValue);
    }

    public Optional<String> getAsString(ConfigKey configKey) {
        return getByKey(configKey);
    }

    public Optional<Boolean> getAsBoolean(ConfigKey configKey) {
        return getByKey(configKey).map(Boolean::parseBoolean);
    }

    public Optional<Integer> getAsInteger(ConfigKey configKey) {
        return getByKey(configKey).map(Integer::parseInt);
    }

    public Optional<Long> getAsLong(ConfigKey configKey) {
        return getByKey(configKey).map(Long::parseLong);
    }

    public Optional<Double> getAsDouble(ConfigKey configKey) {
        return getByKey(configKey).map(Double::parseDouble);
    }

    public Optional<Float> getAsFloat(ConfigKey configKey) {
        return getByKey(configKey).map(Float::parseFloat);
    }

    public void setString(ConfigKey configKey, String value) {
        setByKey(configKey, value);
    }

    public void setBoolean(ConfigKey configKey, boolean value) {
        setByKey(configKey, String.valueOf(value));
    }

    public void setInteger(ConfigKey configKey, int value) {
        setByKey(configKey, String.valueOf(value));
    }

    public void setLong(ConfigKey configKey, long value) {
        setByKey(configKey, String.valueOf(value));
    }

    public void setDouble(ConfigKey configKey, double value) {
        setByKey(configKey, String.valueOf(value));
    }

    public void setFloat(ConfigKey configKey, float value) {
        setByKey(configKey, String.valueOf(value));
    }

    private final Map<ConfigKey, List<UpdateCallback>> updateCallbacks = new HashMap<>();

    public void setByKey(ConfigKey configKey, String value) {
        Optional<Configuration> configuration = getRepository().findByKey(configKey.name());
        if (configuration.isPresent()) {
            configuration.get().setValue(value);
            getRepository().save(configuration.get());
        } else {
            Configuration newConfiguration = Configuration.builder()
                    .key(configKey.name())
                    .value(value)
                    .build();
            getRepository().save(newConfiguration);
        }
    }

    public <T> void onUpdateValue(ConfigKey configKey, T value) {
        updateCallbacks.get(configKey).forEach(callback -> new Thread(() -> callback.onUpdate(value)).start());
    }

    public interface UpdateCallback {
        <T> void onUpdate(T value);
    }

    public void registerUpdateCallback(ConfigKey configKey, UpdateCallback callback) {
        if (!updateCallbacks.containsKey(configKey)) {
            updateCallbacks.put(configKey, new ArrayList<>());
        }
        List<UpdateCallback> callbacks = updateCallbacks.get(configKey);
        if(!callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    public void unregisterUpdateCallback(ConfigKey configKey, UpdateCallback callback) {
        if (updateCallbacks.containsKey(configKey)) {
            List<UpdateCallback> callbacks = updateCallbacks.get(configKey);
            callbacks.remove(callback);
        }
    }

    private String cachedUsersUrl = null;

    public String getUsersUrl() {
        if (cachedUsersUrl == null) {
            cachedUsersUrl = controlService.getInfoByPrefix("users").getUrl();
        }
        return cachedUsersUrl;
    }

    public void clearCachedConfig() {
        cachedUsersUrl = null;
    }

   /* private final ConfigurationDto configDefaults = ConfigurationDto.builder()
            .singleSession(false)
            .sessionMaxAge(86400)
            .build();

    @Transactional
    public ConfigurationDto getConfiguration() {
        return ConfigurationDto.builder()
                .singleSession(getAsBoolean(ConfigKey.SINGLE_SESSION).orElse(configDefaults.getSingleSession()))
                .sessionMaxAge(getAsInteger(ConfigKey.SESSION_MAX_AGE).orElse(configDefaults.getSessionMaxAge()))
                .build();
    }

    @Transactional
    public ConfigurationDto updateConfiguration(UpdateConfiguration uDto){
        Map<ConfigKey, Object> configMap = new HashMap<>();
        uDto.getSingleSession().ifPresent(value -> {
            setBoolean(ConfigKey.SINGLE_SESSION, value);
            configMap.put(ConfigKey.SINGLE_SESSION, value);
        });
        uDto.getSessionMaxAge().ifPresent(value -> {
            setInteger(ConfigKey.SESSION_MAX_AGE, value);
            configMap.put(ConfigKey.SESSION_MAX_AGE, value);
        });

        ConfigurationDto configuration = getConfiguration();

        configMap.forEach(this::onUpdateValue);

        return configuration;
    }*/
}
