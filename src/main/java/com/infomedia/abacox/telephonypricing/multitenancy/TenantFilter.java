package com.infomedia.abacox.telephonypricing.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String tenantId = request.getHeader(TENANT_HEADER);

        // Optional: Add logic here to validate if tenant exists in DB if you want strict checking
        // Optional: Extract from Subdomain instead of Header if preferred

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenant(tenantId);
        } else {
            // Explicitly set to null so Resolver defaults to "public"
            TenantContext.clear(); 
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // CRITICAL: Prevent memory leaks in ThreadLocal (Tomcat threads are pooled)
            TenantContext.clear();
        }
    }
}