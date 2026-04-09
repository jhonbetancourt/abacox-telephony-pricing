package com.infomedia.abacox.telephonypricing.component.permissions;

import com.infomedia.abacox.telephonypricing.messaging.MessagingService;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers this module's permissions with abacox-users on tenant initialization.
 * Reads permission definitions from permissions.yml and sends them via RabbitMQ.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PermissionRegistrar implements TenantInitializer {

    private final MessagingService messagingService;

    @Override
    public void onTenantInit(String tenantId) {
        try {
            Map<String, Object> config = loadConfig();
            String module = (String) config.get("module");
            List<Map<String, String>> permissionDefs = parsePermissions(config);

            if (permissionDefs.isEmpty()) {
                log.info("No permissions to register for module: {}", module);
                return;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("module", module);
            payload.put("permissions", permissionDefs);
            payload.put("tenant", tenantId);

            messagingService.sendQuery("users", "PERMISSION_REGISTER", payload, 30_000);
            log.info("Registered {} permissions for module '{}' in tenant '{}'",
                    permissionDefs.size(), module, tenantId);
        } catch (Exception e) {
            log.warn("Failed to register permissions for tenant '{}': {}", tenantId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream is = new ClassPathResource("permissions.yml").getInputStream()) {
            return yaml.load(is);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parsePermissions(Map<String, Object> config) {
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) config.get("permissions");
        List<Map<String, String>> result = new ArrayList<>();

        for (Map<String, Object> perm : permissions) {
            String resource = (String) perm.get("resource");
            String description = (String) perm.get("description");
            List<String> actions = (List<String>) perm.get("actions");

            for (String action : actions) {
                Map<String, String> def = new HashMap<>();
                def.put("resource", resource);
                def.put("action", action);
                def.put("description", description + " - " + action);
                result.add(def);
            }
        }
        return result;
    }
}
