package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;

import java.util.Map;
import static java.util.Map.entry;

public class InventoryDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("inventario")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.Inventory")
                .sourceIdColumnName("INVENTARIO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("INVENTARIO_ID", "id"),
                        entry("INVENTARIO_SERIE", "serialNumber"),
                        entry("INVENTARIO_MAC", "mac"),
                        entry("INVENTARIO_PLACA", "plate"),
                        entry("INVENTARIO_FUNCIONARIO_ID", "employeeId"),
                        entry("INVENTARIO_INVEQUIPOS_ID", "inventoryEquipmentId"),
                        entry("INVENTARIO_SERVICIO", "service"),
                        entry("INVENTARIO_UBICACION", "locationId"),
                        entry("INVENTARIO_SUBDIRECCION_ID", "subdivisionId"),
                        entry("INVENTARIO_CENTROCOSTOS_ID", "costCenterId"),
                        entry("INVENTARIO_USRED", "networkUser"),
                        entry("INVENTARIO_TIPOEQUIPOS_ID", "equipmentTypeId"),
                        entry("INVENTARIO_DESCRIPCION", "description"),
                        entry("INVENTARIO_COMENTARIOS", "comments"),
                        entry("INVENTARIO_HISTODESDE", "historySince"),
                        entry("INVENTARIO_HISTOCAMBIO", "historyChange"),
                        entry("INVENTARIO_HISTORICTL_ID", "historyControlId"),
                        entry("INVENTARIO_CUENTA", "account"),
                        entry("INVENTARIO_MOTIVOCAMBIO", "changeReason"),
                        entry("INVENTARIO_INVEDS_ID", "inventorySupplierId"),
                        entry("INVENTARIO_FECHAINSTALACION", "installationDate"),
                        entry("INVENTARIO_INVEOT_ID", "inventoryWorkOrderTypeId"),
                        entry("INVENTARIO_INVETIPOUSUARIO_ID", "inventoryUserTypeId"),
                        entry("INVENTARIO_NUMACTACASO", "caseActNumber"),
                        entry("INVENTARIO_ACTACASO", "caseAct"),
                        entry("INVENTARIO_INVEPROPIETARIO_ID", "inventoryOwnerId"),
                        entry("INVENTARIO_INVESERVICIOSADC_ID", "inventoryAdditionalServiceId"),
                        entry("INVENTARIO_VIGENCIAPERMISOS", "permissionsExpiry"),
                        entry("INVENTARIO_PERMISOSEXT_ID", "permissionsExtId"),
                        entry("INVENTARIO_ESTADO", "status"),
                        entry("INVENTARIO_FCREACION", "createdDate"),
                        entry("INVENTARIO_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
