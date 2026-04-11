package com.infomedia.abacox.telephonypricing.security.method;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import lombok.extern.log4j.Log4j2;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Spring Security {@link AuthorizationManager} that enforces
 * {@link RequiresPermission} on controller methods. The annotation value
 * is the local short form (e.g. {@code dashboard:read}); this manager
 * prepends {@code abacox.permission.prefix} so it matches the
 * fully-qualified keys ({@code tp.dashboard:read}) loaded into
 * {@link Authentication#getAuthorities()} by the security filter.
 */
@Component
@Log4j2
public class PermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    @Value("${abacox.permission.prefix}")
    private String permissionPrefix;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, MethodInvocation invocation) {
        Method method = invocation.getMethod();
        RequiresPermission requires = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
        if (requires == null) {
            return new AuthorizationDecision(true);
        }

        String required = permissionPrefix + "." + requires.value();
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) {
            log.debug("Denying call to {}#{} - not authenticated",
                    method.getDeclaringClass().getSimpleName(), method.getName());
            return new AuthorizationDecision(false);
        }

        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (required.equals(authority.getAuthority())) {
                return new AuthorizationDecision(true);
            }
        }

        log.debug("Denying call to {}#{} - missing permission '{}' for principal '{}'",
                method.getDeclaringClass().getSimpleName(), method.getName(), required, auth.getName());
        return new AuthorizationDecision(false);
    }
}
