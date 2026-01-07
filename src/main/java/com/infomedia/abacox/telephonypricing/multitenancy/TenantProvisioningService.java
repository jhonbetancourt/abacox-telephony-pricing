package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class TenantProvisioningService {

    private final SchemaMigrationService schemaMigrationService;
    private final TenantInitService tenantInitService;

    public void provisionTenant(String tenantId) {
        // 1. Sanitize
        if (!tenantId.matches("^[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid tenant ID.");
        }

        try {
            // 2. Create Schema & Tables (Using Liquibase "Sync")
            // This replaces the old Flyway logic
            schemaMigrationService.syncTenant(tenantId);

            // 3. Populate Default Data (Admin, System User)
            TenantContext.setTenant(tenantId);
            tenantInitService.init();
            
        } catch (Exception e) {
            log.error("Failed to provision tenant: " + tenantId, e);
            throw new RuntimeException("Provisioning failed", e);
        } finally {
            TenantContext.clear();
        }
    }

    public void deprovisionTenant(String tenantId) {
        // 1. Sanitize Input (Security check)
        if (!tenantId.matches("^[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid tenant ID format.");
        }

        try {
            // 2. Drop the Schema
            schemaMigrationService.dropSchema(tenantId);

            // Optional: You might want to remove this tenant from a central directory table
            // if you have a "Tenants" table in the public schema.

        } catch (Exception e) {
            log.error("Failed to deprovision tenant: " + tenantId, e);
            throw new RuntimeException("Deprovisioning failed", e);
        }
    }
}