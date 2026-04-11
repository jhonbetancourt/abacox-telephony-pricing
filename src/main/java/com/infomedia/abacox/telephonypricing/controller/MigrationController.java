package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStatus;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.MigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "Migration", description = "Migration API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@Log4j2
@RequestMapping("/api/migration")
public class MigrationController {

    private final MigrationService migrationService;

    @RequiresPermission(Permissions.MIGRATION_EXECUTE)
    @Operation(summary = "Start data migration")
    @PostMapping(value = "/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse startMigration(@Valid @RequestBody MigrationStart runRequest) {
        migrationService.startAsync(runRequest);
        return new MessageResponse("Migration process initiated successfully. Check status endpoint for progress.");
    }

    @RequiresPermission(Permissions.MIGRATION_EXECUTE)
    @Operation(summary = "Start inventory migration")
    @PostMapping(value = "/startInventory", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse startInventoryMigration(@Valid @RequestBody MigrationStart runRequest) {
        migrationService.startInventoryAsync(runRequest);
        return new MessageResponse("Inventory migration initiated successfully. Check status endpoint for progress.");
    }

    @RequiresPermission(Permissions.MIGRATION_READ)
    @Operation(summary = "Get migration status")
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public MigrationStatus getMigrationStatus() {
        return migrationService.getStatus();
    }
}