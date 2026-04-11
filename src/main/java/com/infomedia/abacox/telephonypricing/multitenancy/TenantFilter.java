package com.infomedia.abacox.telephonypricing.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link TenantContext} for each request so downstream Hibernate
 * queries target the right schema.
 * <p>
 * Tenant resolution order:
 * <ol>
 *   <li>{@code X-Tenant-ID} header — used by internal service-to-service
 *       calls that carry the key in a header.</li>
 *   <li>The request's context path — which Spring's {@code ForwardedHeaderFilter}
 *       (enabled via {@code server.forward-headers-strategy=framework}) populates
 *       from the {@code X-Forwarded-Prefix} header set by Traefik's
 *       {@code stripPrefixRegex} middleware. For a request that entered as
 *       {@code /service/colsanitas/telephony-pricing/api/...}, the context
 *       path will be {@code /service/colsanitas/telephony-pricing}. We
 *       extract the tenant segment from there so public endpoints (health,
 *       swagger, etc.) still get a sensible tenant context.</li>
 * </ol>
 * <p>
 * For JWT-authenticated requests the {@code tenant} claim is authoritative
 * and will overwrite whatever this filter set — see {@code SecurityConfig}.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = resolveTenant(request);

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenant(tenantId);
        } else {
            TenantContext.clear();
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // CRITICAL: Prevent memory leaks in ThreadLocal (Tomcat threads are pooled)
            TenantContext.clear();
        }
    }

    private String resolveTenant(HttpServletRequest request) {
        String headerTenant = request.getHeader(TENANT_HEADER);
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant;
        }
        return extractTenantFromForwardedPrefix(request.getContextPath());
    }

    /**
     * Parses the tenant segment out of a forwarded prefix of the form
     * {@code /service/<tenant>/<module>}. Returns {@code null} for any other
     * shape.
     */
    static String extractTenantFromForwardedPrefix(String forwardedPrefix) {
        if (forwardedPrefix == null || forwardedPrefix.isBlank()) {
            return null;
        }
        String trimmed = forwardedPrefix.startsWith("/") ? forwardedPrefix.substring(1) : forwardedPrefix;
        String[] parts = trimmed.split("/");
        if (parts.length >= 2 && "service".equals(parts[0])) {
            String tenant = parts[1];
            return tenant.isBlank() ? null : tenant;
        }
        return null;
    }
}
