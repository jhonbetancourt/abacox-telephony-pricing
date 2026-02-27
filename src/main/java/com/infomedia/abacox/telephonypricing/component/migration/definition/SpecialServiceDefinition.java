package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.component.migration.TableMigrationConfig;
import java.util.Map;
import static java.util.Map.entry;

public class SpecialServiceDefinition implements MigrationTableDefinition {
    @Override
    public TableMigrationConfig getTableMigrationConfig(MigrationContext context) {
        return TableMigrationConfig.builder()
                .sourceTableName("servespecial")
                .targetEntityClassName("com.infomedia.abacox.telephonypricing.db.entity.SpecialService")
                .sourceIdColumnName("SERVESPECIAL_ID")
                .targetIdFieldName("id")
                .columnMapping(Map.ofEntries(
                        entry("SERVESPECIAL_ID", "id"),
                        entry("SERVESPECIAL_INDICATIVO_ID", "indicatorId"),
                        entry("SERVESPECIAL_NUMERO", "phoneNumber"),
                        entry("SERVESPECIAL_VALOR", "value"),
                        entry("SERVESPECIAL_IVA", "vatAmount"),
                        entry("SERVESPECIAL_IVAINC", "vatIncluded"),
                        entry("SERVESPECIAL_DESCRIPCION", "description"),
                        entry("SERVESPECIAL_MPORIGEN_ID", "originCountryId"),
                        entry("SERVESPECIAL_FCREACION", "createdDate"),
                        entry("SERVESPECIAL_FMODIFICADO", "lastModifiedDate")))
                .build();
    }
}
