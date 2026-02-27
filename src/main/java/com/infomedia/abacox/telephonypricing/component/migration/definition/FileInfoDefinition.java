package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;

import lombok.extern.log4j.Log4j2;
import java.util.Map;
import java.util.Set;
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
                .virtualColumns(Set.of("DERIVED_PLANT_ID", "LITERAL_STATUS"))
                .additionalColumnsToFetch(Set.of("FILEINFO_DIRECTORIO"))
                .rowModifier(row -> {
                    Object directorio = row.get("FILEINFO_DIRECTORIO");
                    Integer plantId = context.getDirectorioToPlantCache().getOrDefault(String.valueOf(directorio), 0);
                    row.put("DERIVED_PLANT_ID", plantId);
                    row.put("LITERAL_STATUS", FileInfo.ProcessingStatus.COMPLETED_MISSING.name());
                })
                .beforeMigrationAction(config -> {
                    if (context.getMigratedFileInfoIds().isEmpty()) {
                        log.info("No FileInfo IDs collected. FileInfo migration will be effectively skipped.");
                        config.setWhereClause("1=0");
                    } else {
                        config.setSourceIdFilter(new java.util.HashSet<>(context.getMigratedFileInfoIds()));
                        log.debug("Filtering FileInfo migration to {} collected IDs via sourceIdFilter.",
                                context.getMigratedFileInfoIds().size());
                    }
                })
                .build();
    }
}
