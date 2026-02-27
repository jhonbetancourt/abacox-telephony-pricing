package com.infomedia.abacox.telephonypricing.dto.migration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MigrationStart {
    @NotBlank
    @Schema(description = "Server hostname or IP", example = "172.16.4.254")
    private String host;

    @NotBlank
    @Schema(description = "Server port", example = "1433")
    private String port;

    @NotBlank
    @Schema(description = "Primary database name", example = "abacox_infomedia")
    private String database;

    @Builder.Default
    @Schema(description = "Database name for control tables", example = "abacox_control3_beta")
    private String controlDatabase = "abacox_control3_beta";

    @NotBlank
    @Schema(description = "Database username", example = "sa")
    private String username;

    @NotBlank
    @Schema(description = "Database password")
    private String password;

    @NotNull
    @Schema(description = "Use SSL/Encryption", example = "false")
    private Boolean encryption;

    @NotNull
    @Schema(description = "Trust server certificate", example = "true")
    private Boolean trustServerCertificate;

    @NotNull
    @Schema(description = "Max call records to migrate (0 for all)", example = "1000")
    private Integer maxCallRecordEntries;

    @NotNull
    @Schema(description = "Max failed call records to migrate (0 for all)", example = "1000")
    private Integer maxFailedCallRecordEntries;

    @Builder.Default
    @Schema(description = "Cleanup target tables before migration", example = "true")
    private Boolean cleanup = true;
}
