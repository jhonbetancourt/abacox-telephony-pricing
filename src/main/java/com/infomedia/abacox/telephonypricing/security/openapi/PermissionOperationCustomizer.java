package com.infomedia.abacox.telephonypricing.security.openapi;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Appends the {@link RequiresPermission} value of a handler method to the
 * generated OpenAPI operation description so that the required permission is
 * visible from the Swagger UI.
 * <p>
 * Developers write short, module-local keys on the annotation
 * (e.g. {@code employee:read}). This customizer prepends
 * {@code abacox.permission.prefix} so what shows up in Swagger matches the
 * fully qualified permission key the backend registers and enforces.
 */
@Component
public class PermissionOperationCustomizer implements OperationCustomizer {

    private static final String PERMISSION_PREFIX = "**Required permission:** `";
    private static final String PERMISSION_SUFFIX = "`";

    @Value("${abacox.permission.prefix}")
    private String permissionPrefix;

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        RequiresPermission requires = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (requires == null) {
            return operation;
        }

        String qualified = permissionPrefix + "." + requires.value();
        String permissionLine = PERMISSION_PREFIX + qualified + PERMISSION_SUFFIX;
        String existing = operation.getDescription();
        if (existing == null || existing.isBlank()) {
            operation.setDescription(permissionLine);
        } else if (!existing.contains(permissionLine)) {
            operation.setDescription(existing + "\n\n" + permissionLine);
        }
        return operation;
    }
}
