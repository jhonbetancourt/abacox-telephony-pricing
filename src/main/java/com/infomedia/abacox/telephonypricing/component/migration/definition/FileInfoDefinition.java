package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import lombok.extern.log4j.Log4j2;
import java.util.Map;
import static java.util.Map.entry;

@Log4j2
public class FileInfoDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("fileinfo")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.FileInfo")
                .sourceIdColumnName("FILEINFO_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("FILEINFO_ID", "id"),
                        entry("FILEINFO_ARCHIVO", "filename"),
                        entry("FILEINFO_TAMANO", "size"),
                        entry("FILEINFO_FECHA", "date"),
                        entry("DERIVED_PLANT_ID", "plantTypeId"),
                        entry("LITERAL_STATUS", "processingStatus")))
                .rowModifier(row -> {
                    Object directorio = row.get("FILEINFO_DIRECTORIO");
                    Integer plantId = context.getDirectorioToPlantCache().getOrDefault(String.valueOf(directorio), 0);
                    row.put("DERIVED_PLANT_ID", plantId);
                    row.put("LITERAL_STATUS", "COMPLETED");
                })
                .beforeMigrationAction(config -> {
                    if (context.getMigratedFileInfoIds().isEmpty()) {
                        log.info("No FileInfo IDs collected. FileInfo migration will be effectively skipped.");
                    }
                    // Filter logic will be handled by dynamic WHERE clause building in
                    // MigrationService
                    // or via a custom action here if we pass the config.
                })
                .build();
    }
}
