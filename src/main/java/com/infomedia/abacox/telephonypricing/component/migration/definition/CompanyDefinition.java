package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class CompanyDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("EMPRESA")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Company")
                .sourceIdColumnName("EMPRESA_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("EMPRESA_ID", "id"),
                        entry("EMPRESA_ADICIONAL", "additionalInfo"),
                        entry("EMPRESA_DIRECCION", "address"),
                        entry("EMPRESA_EMPRESA", "name"),
                        entry("EMPRESA_NIT", "taxId"),
                        entry("EMPRESA_RSOCIAL", "legalName"),
                        entry("EMPRESA_URL", "website"),
                        entry("EMPRESA_INDICATIVO_ID", "indicatorId"),
                        entry("EMPRESA_FCREACION", "createdDate"),
                        entry("EMPRESA_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
