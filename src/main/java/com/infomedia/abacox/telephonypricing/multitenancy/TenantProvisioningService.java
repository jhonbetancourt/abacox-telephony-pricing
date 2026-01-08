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
            // 2. Create Schema & Tables.
            schemaMigrationService.initializeNewTenantSchema(tenantId);

            // 3. Populate Default Data. This is the step most likely to fail after schema creation.
            TenantContext.setTenant(tenantId);
            tenantInitService.init(tenantId);

            log.info("Tenant '{}' provisioned successfully.", tenantId);

        } catch (Exception e) {
            log.error("Failed to provision tenant '{}' due to an error. Starting rollback procedure.", tenantId, e);

            // --- ATTEMPT TO ROLL BACK / CLEAN UP ---
            try {
                log.warn("Attempting to drop the partially created schema for tenant '{}' to roll back.", tenantId);
                schemaMigrationService.dropSchema(tenantId);
                log.info("Successfully dropped schema for tenant '{}' as part of the rollback.", tenantId);
            } catch (Exception cleanupException) {
                // Log this as a critical secondary failure. The original exception 'e' is the root cause.
                log.error(
                        "CRITICAL: Rollback failed. Could not drop schema for tenant '{}' after a provisioning error. Manual database cleanup is required.",
                        tenantId,
                        cleanupException
                );
            }
            // --- END OF ROLLBACK LOGIC ---

            // Re-throw the original exception to ensure the caller knows the provisioning failed.
            throw new RuntimeException("Provisioning failed for tenant '" + tenantId + "' and has been rolled back.", e);
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
        } catch (Exception e) {
            log.error("Failed to deprovision tenant: " + tenantId, e);
            throw new RuntimeException("Deprovisioning failed", e);
        }
    }
}