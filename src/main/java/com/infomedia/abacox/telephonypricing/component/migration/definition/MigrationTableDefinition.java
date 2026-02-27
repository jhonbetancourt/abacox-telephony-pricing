package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

public interface MigrationTableDefinition {
    TableMigrationConfig getTableMigrationConfig(MigrationContext context);
}
