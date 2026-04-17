package com.infomedia.abacox.telephonypricing.cache;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infomedia.abacox.telephonypricing.db.repository.CacheEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring {@link CacheManager} producing {@link PersistentCache} instances.
 * Each cache name is pre-registered from a {@link PersistentCacheSpec} and gets
 * its own Caffeine front with the spec's TTL / size.
 */
@Slf4j
public class PersistentCacheManager implements CacheManager {

    private final Map<String, PersistentCacheSpec> specs = new LinkedHashMap<>();
    private final Map<String, PersistentCache> caches = new ConcurrentHashMap<>();
    private final ObjectProvider<CacheEntryRepository> repositoryProvider;
    private final ObjectMapper cacheObjectMapper;

    public PersistentCacheManager(List<PersistentCacheSpec> specs,
                                  ObjectProvider<CacheEntryRepository> repositoryProvider) {
        for (PersistentCacheSpec spec : specs) {
            this.specs.put(spec.name(), spec);
        }
        this.repositoryProvider = repositoryProvider;
        this.cacheObjectMapper = buildCacheObjectMapper();
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        PersistentCacheSpec spec = specs.get(name);
        if (spec == null) {
            log.warn("Requested unknown cache '{}'; no PersistentCacheSpec registered", name);
            return null;
        }
        return caches.computeIfAbsent(name, this::build);
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(specs.keySet());
    }

    private PersistentCache build(String name) {
        PersistentCacheSpec spec = specs.get(name);
        com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine = Caffeine.newBuilder()
                .expireAfterWrite(spec.ttl())
                .maximumSize(spec.maxSize())
                .build();
        return new PersistentCache(name, spec.ttl(), caffeine, repositoryProvider, cacheObjectMapper);
    }

    /**
     * Dedicated mapper with default typing so arbitrary DTOs (including nested
     * generics and collections) round-trip through JSON without the caller
     * having to supply a TypeReference.
     */
    private static ObjectMapper buildCacheObjectMapper() {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.findAndRegisterModules();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
