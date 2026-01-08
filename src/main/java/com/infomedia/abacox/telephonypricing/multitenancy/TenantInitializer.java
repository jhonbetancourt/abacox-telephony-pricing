package com.infomedia.abacox.telephonypricing.multitenancy;

public interface TenantInitializer {

    /**
     * Executes the initialization logic for the current tenant
     * context (as set in {@link TenantContext}).
     * Implementations should be idempotent if possible.
     */
    void onTenantInit(String tenantId);
}