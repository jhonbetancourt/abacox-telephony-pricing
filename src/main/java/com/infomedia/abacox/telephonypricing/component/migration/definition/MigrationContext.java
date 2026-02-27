package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

@Getter
@Builder
public class MigrationContext {
    private final MigrationStart runRequest;
    private final Integer sourceClientId;
    private final Map<Object, Object> telephonyTypeReplacements;
    private final Set<Long> migratedEmployeeIds;
    private final Set<Long> migratedFileInfoIds;
    private final Map<String, Integer> directorioToPlantCache;
    private final String controlDatabase;
}
