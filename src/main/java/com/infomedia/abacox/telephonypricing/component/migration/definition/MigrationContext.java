package com.infomedia.abacox.telephonypricing.component.migration.definition;

import com.infomedia.abacox.telephonypricing.dto.migration.MigrationStart;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Getter
@Builder
public class MigrationContext {
    private final MigrationStart runRequest;
    private final Integer sourceClientId;
    private final Map<Object, Object> telephonyTypeReplacements;
    private final AtomicReference<LongOpenHashSet> validEmployeeIdsCache;
    private final Supplier<LongOpenHashSet> employeeIdLoader;
    private final Set<Long> migratedFileInfoIds;
    private final Map<String, Integer> directorioToPlantCache;
    private final String controlDatabase;
}
