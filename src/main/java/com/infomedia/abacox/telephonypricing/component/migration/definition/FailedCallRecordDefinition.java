package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.QuarantineErrorType;
import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.component.utils.XXHash128Util;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static java.util.Map.entry;

public class FailedCallRecordDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        Map<Object, Object> originalCallRecordIdReplacements = new HashMap<>();
        originalCallRecordIdReplacements.put(0, null);

        return TableMigrationConfig.builder()
                .sourceTableName("acumfallido")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.FailedCallRecord")
                .sourceIdColumnName("ACUMFALLIDO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("ACUMFALLIDO_ID", "id"),
                        entry("ACUMFALLIDO_EXTENSION", "employeeExtension"),
                        entry("ACUMFALLIDO_DATOS", "errorType"),
                        entry("ACUMFALLIDO_MENSAJE", "errorMessage"),
                        entry("ACUMFALLIDO_ACUMTOTAL_ID", "originalCallRecordId"),
                        entry("ACUMFALLIDO_COMUBICACION_ID", "commLocationId"),
                        entry("ACUMFALLIDO_FILEINFO_ID", "fileInfoId"),
                        entry("ACUMFALLIDO_CDR", "ctlHash"),
                        entry("ACUMFALLIDO_FCREACION", "createdDate"),
                        entry("ACUMFALLIDO_FMODIFICADO", "lastModifiedDate")))
                .customValueTransformers(Map.of(
                        "errorType",
                        val -> QuarantineErrorType.fromPhpType(String.valueOf(val)).name(),
                        "ctlHash",
                        val -> val != null
                                ? XXHash128Util.hash(String.valueOf(val).getBytes(StandardCharsets.UTF_8))
                                : null))
                .maxEntriesToMigrate(context.getRunRequest().getMaxFailedCallRecordEntries())
                .specificValueReplacements(Map.of("originalCallRecordId", originalCallRecordIdReplacements))
                .orderByClause("ACUMFALLIDO_ID DESC")
                .onBatchSuccess(batch -> {
                    for (Map<String, Object> row : batch) {
                        Object fileInfoId = row.get("ACUMFALLIDO_FILEINFO_ID");
                        if (fileInfoId instanceof Number) {
                            context.getMigratedFileInfoIds().add(((Number) fileInfoId).longValue());
                        }
                    }
                })
                .build();
    }
}
