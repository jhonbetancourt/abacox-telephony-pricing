package com.infomedia.abacox.telephonypricing.component.permissions;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers this module's permissions by scanning every Spring MVC handler
 * method for the {@link RequiresPermission} annotation. The annotation is the
 * single source of truth - if an endpoint declares it, the corresponding
 * {@code resource:action} permission is emitted.
 * <p>
 * Developers write short, module-local keys on the annotation
 * (e.g. {@code pricing:read}). The scanner automatically prepends
 * {@code abacox.permission.prefix} so the registered key is the fully
 * qualified form ({@code tp.pricing:read}). This guarantees no
 * collisions between modules that happen to use the same resource name.
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

        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> collect(handlerMethod, byKey));

        return new ArrayList<>(byKey.values());
    }

    private void collect(HandlerMethod handlerMethod, Map<String, Map<String, String>> byKey) {
        Method method = handlerMethod.getMethod();
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        if (annotation == null) {
            return;
        }

        String raw = annotation.value();
        if (raw == null || raw.isBlank()) {
            log.warn("Empty @RequiresPermission on {}#{}", method.getDeclaringClass().getSimpleName(), method.getName());
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
        String qualifiedResource = permissionPrefix + "." + localResource;
        String key = qualifiedResource + ":" + action;

        byKey.computeIfAbsent(key, k -> {
            Map<String, String> def = new HashMap<>();
            def.put("resource", qualifiedResource);
            def.put("action", action);
            def.put("description", qualifiedResource + " " + action);
            return def;
        });
    }
}
