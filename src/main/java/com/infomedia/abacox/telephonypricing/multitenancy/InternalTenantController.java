package com.infomedia.abacox.telephonypricing.multitenancy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/tenant")
@RequiredArgsConstructor
@Tag(name = "Tenant Management")
@SecurityRequirement(name = "Internal_Api_Key")
public class InternalTenantController {

    private final TenantProvisioningService provisioningService;
    private final SchemaMigrationService migrationService;

    @Operation(summary = "Provision a new tenant schema")
    @PostMapping("/{tenantId}")
    public ResponseEntity<String> provisionTenant(@PathVariable String tenantId) {
        provisioningService.provisionTenant(tenantId);
        return ResponseEntity.ok("Tenant " + tenantId + " provisioned successfully.");
    }

    @Operation(summary = "Deprovision (Delete) a tenant schema and all its data")
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<String> deprovisionTenant(@PathVariable String tenantId) {
        try {
            provisioningService.deprovisionTenant(tenantId);
            return ResponseEntity.ok("Tenant " + tenantId + " deprovisioned (schema dropped) successfully.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error removing tenant: " + e.getMessage());
        }
    }

    @Operation(summary = "Preview changes: Comparies Java Entities vs Tenant DB")
    @GetMapping(value = "/{tenantId}/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> previewChanges(@PathVariable String tenantId) {
        try {
            String changelog = migrationService.previewMigration(tenantId);
            return ResponseEntity.ok(changelog);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error generating diff: " + e.getMessage());
        }
    }

    @Operation(summary = "Apply changes: Executes the provided XML/YAML changelog against the tenant")
    @PostMapping(value = "/{tenantId}/apply", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> applyChanges(
            @PathVariable String tenantId,
            @RequestBody String changelogContent) {
        try {
            migrationService.applyMigration(tenantId, changelogContent);
            return ResponseEntity.ok("Migration applied successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error applying migration: " + e.getMessage());
        }
    }
}