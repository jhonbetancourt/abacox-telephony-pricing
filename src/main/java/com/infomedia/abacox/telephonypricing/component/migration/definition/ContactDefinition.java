package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class ContactDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("DIRECTORIO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Contact")
                .sourceIdColumnName("DIRECTORIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("DIRECTORIO_ID", "id"),
                        entry("DIRECTORIO_TIPO", "contactType"),
                        entry("DIRECTORIO_FUNCIONARIO_ID", "employeeId"),
                        entry("DIRECTORIO_EMPRESA_ID", "companyId"),
                        entry("DIRECTORIO_TELEFONO", "phoneNumber"),
                        entry("DIRECTORIO_NOMBRE", "name"),
                        entry("DIRECTORIO_DESCRIPCION", "description"),
                        entry("DIRECTORIO_INDICATIVO_ID", "indicatorId"),
                        entry("DIRECTORIO_ACTIVO", "active"),
                        entry("DIRECTORIO_FCREACION", "createdDate"),
                        entry("DIRECTORIO_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
