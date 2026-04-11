package com.infomedia.abacox.telephonypricing.security.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infomedia.abacox.telephonypricing.messaging.InternalMessage;
import com.infomedia.abacox.telephonypricing.messaging.MessagingService;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@code (tenant, rolename) -> Set<permissionKey>}.
 * <p>
 * Loaded lazily via a RabbitMQ {@code PERMISSION_EFFECTIVE_QUERY} call to
 * the users module. Invalidated by listening for the
 * {@code ROLE_PERMISSIONS_CHANGED} event broadcast by users when a role
 * mutates.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class RolePermissionCache {

    private static final long QUERY_TIMEOUT_MS = 5_000;

    private final @Lazy MessagingService messagingService;
    private final ObjectMapper objectMapper;

    @Value("${abacox.permission.role-cache.timeout-ms:5000}")
    private long queryTimeoutMs;

    private final ConcurrentHashMap<String, Set<String>> cache = new ConcurrentHashMap<>();

    public Set<String> get(String rolename) {
        if (rolename == null || rolename.isBlank()) {
            return Collections.emptySet();
        }
        String tenant = TenantContext.getTenant();
        String key = cacheKey(tenant, rolename);
        return cache.computeIfAbsent(key, k -> load(tenant, rolename));
    }

    public void invalidate(String tenant, String rolename) {
        if (rolename == null) {
            return;
        }
        cache.remove(cacheKey(tenant, rolename));
        log.debug("Invalidated role cache for tenant='{}', rolename='{}'", tenant, rolename);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private Set<String> load(String tenant, String rolename) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenant", tenant);
        payload.put("rolename", rolename);

        InternalMessage response = messagingService.sendQuery(
                "users", "PERMISSION_EFFECTIVE_QUERY", payload,
                queryTimeoutMs > 0 ? queryTimeoutMs : QUERY_TIMEOUT_MS);

        if (response == null || !response.isSuccess() || response.getPayload() == null) {
            log.warn("Failed to load permissions for tenant='{}' rolename='{}'", tenant, rolename);
            return Collections.emptySet();
        }

        try {
            Set<String> result = objectMapper.convertValue(
                    response.getPayload(), new TypeReference<Set<String>>() {});
            log.debug("Loaded {} permission(s) for tenant='{}' rolename='{}'",
                    result.size(), tenant, rolename);
            return Set.copyOf(result);
        } catch (Exception e) {
            log.error("Failed to deserialize permission response for rolename='{}': {}",
                    rolename, e.getMessage());
            return Collections.emptySet();
        }
    }

    private static String cacheKey(String tenant, String rolename) {
        return (tenant == null ? "_global_" : tenant) + ":" + rolename;
    }
}
