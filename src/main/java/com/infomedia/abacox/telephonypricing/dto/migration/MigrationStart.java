package com.infomedia.abacox.telephonypricing.dto.migration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MigrationStart {
    @NotBlank
    private String host;
    @NotBlank
    private String port;
    @NotBlank
    private String database;
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotNull
    private Boolean encryption;
    @NotNull
    private Boolean trustServerCertificate;
    @NotNull
    private Integer maxCallRecordEntries;
    @NotNull
    private Integer maxFailedCallRecordEntries;
}
