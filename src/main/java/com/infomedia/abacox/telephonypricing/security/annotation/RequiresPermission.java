package com.infomedia.abacox.telephonypricing.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission required to invoke a controller endpoint.
 * <p>
 * Permissions follow the standardized {@code resource:action} format
 * (e.g. {@code employee:read}, {@code pricing:update}) and are aligned
 * with the permission strings consumed by the frontend.
 * <p>
 * When present, the value is automatically appended to the endpoint's
 * OpenAPI/Swagger description by {@code PermissionOperationCustomizer}
 * so that API consumers can see which permission is required. The
 * annotation is also available at runtime so an authorization aspect
 * can enforce it against the caller's effective permissions.
 * <p>
 * Endpoints that only need an authenticated user, fully public
 * endpoints (health, module info), or endpoints that use a different
 * authentication scheme (internal API key, CDR upload key) should
 * simply omit this annotation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * The permission key in {@code resource:action} format.
     */
    String value();
}
