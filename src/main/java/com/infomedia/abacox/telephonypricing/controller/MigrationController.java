package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStatus;
import com.infomedia.abacox.telephonypricing.service.MigrationService;
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
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@Log4j2
@RequestMapping("/api/migration")
public class MigrationController {

    private final MigrationService migrationService;

    @PostMapping(value = "/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse startMigration(@Valid @RequestBody MigrationStart runRequest) {
        migrationService.startAsync(runRequest);
        return new MessageResponse("Migration process initiated successfully. Check status endpoint for progress.");
    }


    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public MigrationStatus getMigrationStatus() {
        return migrationService.getStatus();
    }
}