package com.infomedia.abacox.telephonypricing.component.migration;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationParams {
    private SourceDbConfig sourceDbConfig;
    private List<TableMigrationConfig> tablesToMigrate; // Ordered list
}