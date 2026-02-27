package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class EmployeeDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("FUNCIONARIO")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Employee")
                .sourceIdColumnName("FUNCIONARIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FUNCIONARIO_ID", "id"),
                        entry("FUNCIONARIO_NOMBRE", "name"),
                        entry("FUNCIONARIO_SUBDIRECCION_ID", "subdivisionId"),
                        entry("FUNCIONARIO_CENTROCOSTOS_ID", "costCenterId"),
                        entry("FUNCIONARIO_CLAVE", "authCode"),
                        entry("FUNCIONARIO_EXTENSION", "extension"),
                        entry("FUNCIONARIO_COMUBICACION_ID", "communicationLocationId"),
                        entry("FUNCIONARIO_FUNCARGO_ID", "jobPositionId"),
                        entry("FUNCIONARIO_CORREO", "email"),
                        entry("FUNCIONARIO_TELEFONO", "phone"),
                        entry("FUNCIONARIO_DIRECCION", "address"),
                        entry("FUNCIONARIO_NUMEROID", "idNumber"),
                        entry("FUNCIONARIO_ACTIVO", "active"),
                        entry("FUNCIONARIO_FCREACION", "createdDate"),
                        entry("FUNCIONARIO_FMODIFICADO", "lastModifiedDate"),
                        entry("FUNCIONARIO_HISTORICTL_ID", "historyControlId"),
                        entry("FUNCIONARIO_HISTODESDE", "historySince"),
                        entry("FUNCIONARIO_HISTOCAMBIO", "historyChange")))
                .build();
    }
}
