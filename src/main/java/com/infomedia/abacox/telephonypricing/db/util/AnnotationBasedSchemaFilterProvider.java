package com.infomedia.abacox.telephonypricing.db.util;

import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2; // Import the annotation
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Log4j2 // Add the Lombok annotation here
public class AnnotationBasedSchemaFilterProvider implements SchemaFilterProvider {

    private static final Set<String> EXCLUDED_TABLES = scanForExcludedTables();

    private static Set<String> scanForExcludedTables() {
        log.info("Scanning classpath for @ExcludeFromDdl annotations...");

        final String basePackage = "com.infomedia.abacox.telephonypricing.db";
        
        final Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackages(basePackage)
                .setScanners(Scanners.TypesAnnotated));

        Set<Class<?>> excludedClasses = reflections.getTypesAnnotatedWith(ExcludeFromDdl.class);
        Set<String> excludedTableNames = new HashSet<>();

        for (Class<?> clazz : excludedClasses) {
            Table tableAnnotation = clazz.getAnnotation(Table.class);
            if (tableAnnotation != null && !tableAnnotation.name().isEmpty()) {
                String tableName = tableAnnotation.name();
                excludedTableNames.add(tableName.toLowerCase());
                // Use structured logging with placeholders {}
                log.info("Found @ExcludeFromDdl on entity [{}]. Excluding table: '{}'", clazz.getSimpleName(), tableName);
            } else {
                String tableName = clazz.getSimpleName();
                excludedTableNames.add(tableName.toLowerCase());
                log.warn("Found @ExcludeFromDdl on entity [{}], but it has no @Table(name=...) annotation. Excluding table based on class name: '{}'", clazz.getSimpleName(), tableName);
            }
        }
        return Collections.unmodifiableSet(excludedTableNames);
    }

    // ... (getCreateFilter, getDropFilter, etc. methods remain the same) ...
    @Override
    public SchemaFilter getCreateFilter() { return new AnnotationBasedSchemaFilter(); }
    @Override
    public SchemaFilter getDropFilter() { return new AnnotationBasedSchemaFilter(); }
    @Override
    public SchemaFilter getMigrateFilter() { return new AnnotationBasedSchemaFilter(); }
    @Override
    public SchemaFilter getValidateFilter() { return new AnnotationBasedSchemaFilter(); }
    @Override
    public SchemaFilter getTruncatorFilter() { return new AnnotationBasedSchemaFilter(); }


    public static class AnnotationBasedSchemaFilter implements SchemaFilter {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(org.hibernate.mapping.Table table) {
            boolean isExcluded = EXCLUDED_TABLES.contains(table.getName().toLowerCase());
            // This log is very useful for debugging which tables are being processed
            if (isExcluded) {
                log.debug("SCHEMA_FILTER: Excluding table [{}] from DDL generation.", table.getName());
            } else {
                log.debug("SCHEMA_FILTER: Including table [{}] in DDL generation.", table.getName());
            }
            return !isExcluded;
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    }
}