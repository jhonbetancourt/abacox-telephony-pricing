package com.infomedia.abacox.telephonypricing.multitenancy;

import org.slf4j.MDC;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    
    // Matches the <key>tenant</key> in logback-spring.xml
    private static final String MDC_KEY = "tenant"; 

    public static void setTenant(String tenant) {
        CURRENT_TENANT.set(tenant);
        
        if (tenant != null) {
            MDC.put(MDC_KEY, tenant);
        } else {
            MDC.remove(MDC_KEY);
        }
    }

    public static String getTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        MDC.remove(MDC_KEY); // Automatically cleans up logging context
    }
}