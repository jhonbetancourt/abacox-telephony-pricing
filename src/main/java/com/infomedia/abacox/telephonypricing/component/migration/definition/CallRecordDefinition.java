package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import lombok.extern.log4j.Log4j2;
import java.util.Map;
import static java.util.Map.entry;

@Log4j2
public class CallRecordDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("ACUMTOTAL")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.CallRecord")
                .sourceIdColumnName("ACUMTOTAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("ACUMTOTAL_ID", "id"),
                        entry("ACUMTOTAL_DIAL", "dial"),
                        entry("ACUMTOTAL_COMUBICACION_ID", "commLocationId"),
                        entry("ACUMTOTAL_FECHA_SERVICIO", "serviceDate"),
                        entry("ACUMTOTAL_OPERADOR_ID", "operatorId"),
                        entry("ACUMTOTAL_FUN_EXTENSION", "employeeExtension"),
                        entry("ACUMTOTAL_FUN_CLAVE", "employeeAuthCode"),
                        entry("ACUMTOTAL_INDICATIVO_ID", "indicatorId"),
                        entry("ACUMTOTAL_TELEFONO_DESTINO", "destinationPhone"),
                        entry("ACUMTOTAL_TIEMPO", "duration"),
                        entry("ACUMTOTAL_REPIQUE", "ringCount"),
                        entry("ACUMTOTAL_TIPOTELE_ID", "telephonyTypeId"),
                        entry("ACUMTOTAL_VALOR_FACTURADO", "billedAmount"),
                        entry("ACUMTOTAL_PRECIOMINUTO", "pricePerMinute"),
                        entry("ACUMTOTAL_PRECIOINICIAL", "initialPrice"),
                        entry("ACUMTOTAL_IO", "isIncoming"),
                        entry("ACUMTOTAL_TRONCAL", "trunk"),
                        entry("ACUMTOTAL_TRONCALINI", "initialTrunk"),
                        entry("ACUMTOTAL_FUNCIONARIO_ID", "employeeId"),
                        entry("ACUMTOTAL_FUN_TRANSFER", "employeeTransfer"),
                        entry("ACUMTOTAL_CAUSA_TRANSFER", "transferCause"),
                        entry("ACUMTOTAL_CAUSA_ASIGNA", "assignmentCause"),
                        entry("ACUMTOTAL_FUNDESTINO_ID", "destinationEmployeeId"),
                        entry("ACUMTOTAL_FILEINFO_ID", "fileInfoId"),
                        entry("ACUMTOTAL_FCREACION", "createdDate"),
                        entry("ACUMTOTAL_FMODIFICADO", "lastModifiedDate")))
                .maxEntriesToMigrate(context.getRunRequest().getMaxCallRecordEntries())
                .orderByClause("ACUMTOTAL_FECHA_SERVICIO DESC")
                .specificValueReplacements(Map.of("telephonyTypeId", context.getTelephonyTypeReplacements()))
                .onBatchSuccess(batch -> {
                    for (Map<String, Object> row : batch) {
                        Object fileInfoId = row.get("ACUMTOTAL_FILEINFO_ID");
                        if (fileInfoId instanceof Number) {
                            context.getMigratedFileInfoIds().add(((Number) fileInfoId).longValue());
                        }
                    }
                })
                .rowModifier(row -> mutateAcumtotalRow(row, context))
                .build();
    }

    private void mutateAcumtotalRow(Map<String, Object> row, MigrationContext context) {
        Object commLocIdRaw = row.get("ACUMTOTAL_COMUBICACION_ID");
        if (commLocIdRaw != null) {
            boolean validCommLoc = false;
            if (commLocIdRaw instanceof Number) {
                validCommLoc = ((Number) commLocIdRaw).longValue() >= 1;
            } else if (commLocIdRaw instanceof String) {
                String val = ((String) commLocIdRaw).trim();
                if (!val.isEmpty()) {
                    try {
                        validCommLoc = Long.parseLong(val) >= 1;
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }
            if (!validCommLoc) {
                row.put("ACUMTOTAL_COMUBICACION_ID", null);
            }
        }
        mutateEmployeeIdInRow(row, "ACUMTOTAL_FUNCIONARIO_ID", context);
        mutateEmployeeIdInRow(row, "ACUMTOTAL_FUNDESTINO_ID", context);
    }

    private void mutateEmployeeIdInRow(Map<String, Object> row, String columnName,
            MigrationContext context) {
        Object empIdRaw = row.get(columnName);
        if (empIdRaw == null)
            return;

        long empId = -1;
        if (empIdRaw instanceof Number) {
            empId = ((Number) empIdRaw).longValue();
        } else if (empIdRaw instanceof String) {
            String val = ((String) empIdRaw).trim();
            if (!val.isEmpty()) {
                try {
                    empId = Long.parseLong(val);
                } catch (NumberFormatException e) {
                }
            }
        }

        if (empId != -1) {
            if (!context.getMigratedEmployeeIds().contains(empId)) {
                row.put(columnName, null);
            }
        } else {
            row.put(columnName, null);
        }
    }
}
