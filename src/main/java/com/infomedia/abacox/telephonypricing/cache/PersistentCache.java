package com.infomedia.abacox.telephonypricing.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infomedia.abacox.telephonypricing.db.entity.CacheEntry;
import com.infomedia.abacox.telephonypricing.db.repository.CacheEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Two-tier cache: Caffeine in-memory (fast) with a Postgres-backed persistent
 * layer (survives restarts). On a Caffeine miss the DB is consulted and — if a
 * non-expired row is found — Caffeine is rehydrated.
 *
 * The persistent layer is written to the currently-bound tenant schema, so
 * multi-tenancy is handled implicitly by the existing Hibernate multi-tenant
 * routing. Keys must still be namespaced per tenant for the in-memory layer,
 * which is shared across tenants in the same JVM.
 */
@Slf4j
public class PersistentCache implements Cache {

    private final String name;
    private final Duration ttl;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> inMemory;
    private final ObjectProvider<CacheEntryRepository> repositoryProvider;
    private final ObjectMapper objectMapper;

    public PersistentCache(String name,
                           Duration ttl,
                           com.github.benmanes.caffeine.cache.Cache<Object, Object> inMemory,
                           ObjectProvider<CacheEntryRepository> repositoryProvider,
                           ObjectMapper objectMapper) {
        this.name = name;
        this.ttl = ttl;
        this.inMemory = inMemory;
        this.repositoryProvider = repositoryProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return inMemory;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        String skey = stringifyKey(key);

        Object memValue = inMemory.getIfPresent(skey);
        if (memValue != null) {
            return wrap(memValue);
        }

        Object dbValue = readFromDb(skey);
        if (dbValue != null) {
            inMemory.put(skey, dbValue);
            return wrap(dbValue);
        }
        return null;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, @Nullable Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (value == null) {
            log.debug("PersistentCache[{}] skipping null value for key={}", name, key);
            return;
        }
        String skey = stringifyKey(key);
        inMemory.put(skey, value);
        writeToDb(skey, value);
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) {
            return existing;
        }
        put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        String skey = stringifyKey(key);
        inMemory.invalidate(skey);
        try {
            repositoryProvider.getObject().deleteEntry(name, skey);
        } catch (Exception ex) {
            log.warn("PersistentCache[{}] DB evict failed for key={}: {}", name, skey, ex.getMessage());
        }
    }

    @Override
    public void clear() {
        inMemory.invalidateAll();
        try {
            repositoryProvider.getObject().deleteAllByCacheName(name);
        } catch (Exception ex) {
            log.warn("PersistentCache[{}] DB clear failed: {}", name, ex.getMessage());
        }
    }

    // --- internals ---

    @Nullable
    private Object readFromDb(String skey) {
        try {
            CacheEntryRepository repo = repositoryProvider.getObject();
            Optional<CacheEntry> row = repo.findByCacheNameAndCacheKey(name, skey);
            if (row.isEmpty()) {
                return null;
            }
            CacheEntry entry = row.get();
            if (entry.getExpiresAt() == null || entry.getExpiresAt().isBefore(LocalDateTime.now())) {
                return null;
            }
            return objectMapper.readValue(entry.getPayload(), Object.class);
        } catch (Exception ex) {
            log.warn("PersistentCache[{}] DB read failed for key={}: {}", name, skey, ex.getMessage());
            return null;
        }
    }

    private void writeToDb(String skey, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            LocalDateTime now = LocalDateTime.now();
            CacheEntryRepository repo = repositoryProvider.getObject();
            CacheEntry entry = repo.findByCacheNameAndCacheKey(name, skey)
                    .orElseGet(() -> CacheEntry.builder()
                            .cacheName(name)
                            .cacheKey(skey)
                            .build());
            entry.setPayload(json);
            entry.setTypeInfo(value.getClass().getName());
            entry.setComputedAt(now);
            entry.setExpiresAt(now.plus(ttl));
            repo.save(entry);
        } catch (Exception ex) {
            log.warn("PersistentCache[{}] DB write failed for key={}: {}", name, skey, ex.getMessage());
        }
    }

    private static String stringifyKey(Object key) {
        return key == null ? "null" : key.toString();
    }

    private static ValueWrapper wrap(Object value) {
        return () -> value;
    }
}
