package com.infomedia.abacox.telephonypricing.component.permissions;

import com.infomedia.abacox.telephonypricing.messaging.MessagingService;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers this module's permissions with abacox-users on tenant
 * initialization. Permissions are discovered at runtime by
 * {@link PermissionScanner} from {@code @RequiresPermission} annotations on
 * controller methods, so the annotation is the single source of truth.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PermissionRegistrar implements TenantInitializer {

    private final MessagingService messagingService;
    private final PermissionScanner permissionScanner;

    @Value("${spring.application.prefix}")
    private String module;

    @Override
    public void onTenantInit(String tenantId) {
        try {
            List<Map<String, String>> permissionDefs = permissionScanner.scan();

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
}
