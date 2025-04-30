package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceDbConfig {
    private String url; // e.g., jdbc:sqlserver://<server_name>:<port>;databaseName=<db_name>;encrypt=true;trustServerCertificate=true;
    private String username;
    private String password;
    @Builder.Default
    private String driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver"; // Default for SQL Server
}