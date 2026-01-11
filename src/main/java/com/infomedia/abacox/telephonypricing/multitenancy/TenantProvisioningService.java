package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Log4j2
public class TenantProvisioningService {

    private final SchemaMigrationService schemaMigrationService;
    private final TenantInitService tenantInitService;
    private final TenantProvider tenantProvider;

    private static final Set<String> RESERVED_WORDS = Set.of(
            "public", "postgres", "information_schema", "pg_catalog",
            "user", "authorization", "admin", "null", "system", "role", "group"
    );

    public void provisionTenant(String tenantId) {
        validateTenantId(tenantId);

        // 1. Idempotency Check
        if (tenantProvider.schemaExists(tenantId)) {
            log.info("Tenant schema '{}' already exists. Skipping provisioning.", tenantId);
            return;
        }

        // 2. Provisioning Flow (Atomic Attempt)
        log.info("Starting provisioning for new tenant '{}'", tenantId);
        try {
            // Step A: Create Schema Structure (Tables, Indexes)
            schemaMigrationService.initializeNewTenantSchema(tenantId);

            // Step B: Populate Default Data (Admin User, Roles, etc.)
            TenantContext.setTenant(tenantId);
            tenantInitService.init(tenantId);

            log.info("Tenant '{}' provisioned successfully.", tenantId);

        } catch (Exception e) {
            log.error("Provisioning failed for tenant '{}'. Initiating rollback (dropping schema).", tenantId, e);

            // --- ROLLBACK STRATEGY ---
            // Whether it failed at Step A (half-created schema) or Step B (bad data),
            // we Nuke the schema to ensure a clean slate.
            try {
                // This method uses 'DROP SCHEMA IF EXISTS ... CASCADE', so it is safe 
                // to call even if the schema was never created or only partially created.
                schemaMigrationService.dropSchema(tenantId);
                log.info("Rollback successful: Schema '{}' dropped.", tenantId);
            } catch (Exception cleanupException) {
                // This is a worst-case scenario (DB connection lost, etc.)
                log.error("CRITICAL: Failed to rollback schema for tenant '{}'. Manual database cleanup required.", tenantId, cleanupException);
            }

            // Propagate exception so the Orchestrator knows it failed
            throw new RuntimeException("Provisioning failed for tenant '" + tenantId + "'. See logs for details.", e);

        } finally {
            // Always clear the ThreadLocal context
            TenantContext.clear();
        }
    }

    public void applyMigrationTenant(String tenantId, String changelog) {
        if (changelog == null || !changelog.contains("<changeSet")) {
            throw new IllegalStateException("Invalid changelog for tenant '" + tenantId + "'. No database changes were detected.");
        }

        try {
            log.info("Applying changelog to migrate schema for tenant: {}", tenantId);
            schemaMigrationService.applyMigration(tenantId, changelog);
            TenantContext.setTenant(tenantId);
            tenantInitService.init(tenantId);
            log.info("Schema migrated successfully for: {}", tenantId);
        }catch (Exception e){
            throw new RuntimeException("Failed to apply migration for tenant '" + tenantId + "'.", e);
        } finally {
            TenantContext.clear();
        }
    }

    public String previewMigrationTenant(String tenantId){
        try {
            return schemaMigrationService.previewMigration(tenantId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to preview migration for tenant '" + tenantId + "'.", e);
        }
    }

    public void deprovisionTenant(String tenantId) {
        validateTenantId(tenantId);

        try {
            if (!tenantProvider.schemaExists(tenantId)) {
                log.warn("Attempted to deprovision non-existent tenant: {}", tenantId);
                return;
            }
            schemaMigrationService.dropSchema(tenantId);
        } catch (Exception e) {
            log.error("Failed to deprovision tenant: " + tenantId, e);
            throw new RuntimeException("Deprovisioning failed", e);
        }
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty.");
        }
        if (tenantId.length() > 63) {
            throw new IllegalArgumentException("Tenant ID exceeds maximum length of 63 characters.");
        }
        if (!tenantId.matches("^[a-z][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Tenant ID must start with a letter and contain only lowercase letters, numbers, and underscores.");
        }
        if (RESERVED_WORDS.contains(tenantId)) {
            throw new IllegalArgumentException("Tenant ID '" + tenantId + "' is a reserved word.");
        }
    }
}