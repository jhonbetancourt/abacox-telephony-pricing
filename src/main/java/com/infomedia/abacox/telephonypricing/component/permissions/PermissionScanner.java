package com.infomedia.abacox.telephonypricing.component.permissions;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers this module's permissions by scanning every Spring MVC handler
 * method for the {@link RequiresPermission} annotation. The annotation is the
 * single source of truth for <em>which</em> permission gates <em>which</em>
 * endpoint; the {@link Permissions} catalog is the single source of truth for
 * the list of valid keys and their human-readable descriptions.
 * <p>
 * Developers write short, module-local keys on the annotation
 * (e.g. {@code pricing:read}). The scanner:
 * <ul>
 *     <li>Prepends {@code abacox.permission.prefix} so the registered key is
 *         the fully qualified form ({@code tp.pricing:read}).</li>
 *     <li>Looks up a human-readable description in {@link Permissions#DESCRIPTIONS}.</li>
 *     <li>Warns if an endpoint declares a key that is not in the catalog
 *         (missing description) or if the catalog has entries that no endpoint
 *         references (stale catalog entry).</li>
 * </ul>
 * Duplicates (several endpoints sharing the same permission) are collapsed.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PermissionScanner {

    private final @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Value("${abacox.permission.prefix}")
    private String permissionPrefix;

    /**
     * @return a list of unique permission tuples in the shape expected by
     *         {@code abacox-users}: {@code {resource, action, description}}.
     */
    public List<Map<String, String>> scan() {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        Set<String> seenLocalKeys = new HashSet<>();

        handlerMapping.getHandlerMethods().forEach(
                (info, handlerMethod) -> collect(handlerMethod, byKey, seenLocalKeys));

        for (String cataloged : Permissions.DESCRIPTIONS.keySet()) {
            if (!seenLocalKeys.contains(cataloged)) {
                log.warn("Permission '{}' is defined in Permissions catalog but no endpoint declares it",
                        cataloged);
            }
        }

        return new ArrayList<>(byKey.values());
    }

    private void collect(HandlerMethod handlerMethod,
                         Map<String, Map<String, String>> byKey,
                         Set<String> seenLocalKeys) {
        Method method = handlerMethod.getMethod();
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        if (annotation == null) {
            return;
        }

        String raw = annotation.value();
        if (raw == null || raw.isBlank()) {
            log.warn("Empty @RequiresPermission on {}#{}",
                    method.getDeclaringClass().getSimpleName(), method.getName());
            return;
        }

        int sep = raw.indexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            log.warn("Invalid @RequiresPermission value '{}' on {}#{} (expected 'resource:action')",
                    raw, method.getDeclaringClass().getSimpleName(), method.getName());
            return;
        }

        String localResource = raw.substring(0, sep).trim();
        String action = raw.substring(sep + 1).trim();
        String localKey = localResource + ":" + action;
        seenLocalKeys.add(localKey);

        String cataloged = Permissions.describe(localKey);
        if (cataloged == null) {
            log.warn("Permission '{}' on {}#{} is not in the Permissions catalog - add it with a description",
                    localKey, method.getDeclaringClass().getSimpleName(), method.getName());
        }
        final String description = cataloged != null ? cataloged : (localResource + " " + action);

        String qualifiedResource = permissionPrefix + "." + localResource;
        String key = qualifiedResource + ":" + action;

        byKey.computeIfAbsent(key, k -> {
            Map<String, String> def = new HashMap<>();
            def.put("resource", qualifiedResource);
            def.put("action", action);
            def.put("description", description);
            return def;
        });
    }
}
