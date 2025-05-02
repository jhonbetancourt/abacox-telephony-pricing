package com.infomedia.abacox.telephonypricing.dto.migration; // Use your actual package

import com.infomedia.abacox.telephonypricing.service.MigrationService;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MigrationStatus {
    private MigrationService.MigrationState state;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage; // Populated only if state is FAILED
    private Integer tablesToMigrate;
    private Integer tablesMigrated; // Optional: More granular progress
    private String currentStep; // Optional: More granular progress
}