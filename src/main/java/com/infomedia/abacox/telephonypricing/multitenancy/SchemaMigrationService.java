package com.infomedia.abacox.telephonypricing.multitenancy;

import liquibase.Liquibase;
import liquibase.command.CommandScope;
import liquibase.command.core.helpers.DbUrlConnectionCommandStep;
import liquibase.command.core.helpers.DiffOutputControlCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.AbstractResourceAccessor;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.OpenOptions;
import liquibase.resource.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class SchemaMigrationService {

    private final DataSource dataSource;

    @Value("${abacox.multitenancy.entity-package}")
    private String entityPackage;

    @Value("${abacox.multitenancy.hibernate-dialect:org.hibernate.dialect.PostgreSQLDialect}")
    private String hibernateDialect;

    /**
     * 1. Creates Schema (if missing).
     * 2. Compares JPA Entities vs Schema.
     * 3. Generates XML Changelog.
     */
    public String previewMigration(String tenantId) throws Exception {
        // Construct the Liquibase Hibernate URL dynamically
        String hibernateUrl = "hibernate:spring:" + entityPackage + "?dialect=" + hibernateDialect;
        
        Path tempFile = Files.createTempFile("migration_" + tenantId + "_", ".xml");
        
        try (Connection connection = dataSource.getConnection()) {
            ensureSchemaExists(connection, tenantId);

            connection.createStatement().execute("SET search_path TO \"" + tenantId + "\"");
            
            Database targetDatabase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            targetDatabase.setDefaultSchemaName(tenantId);

            Database referenceDatabase = DatabaseFactory.getInstance()
                    .openDatabase(hibernateUrl, null, null, null, new ClassLoaderResourceAccessor());

            CommandScope diffCmd = new CommandScope("diffChangelog");
            diffCmd.addArgumentValue(DbUrlConnectionCommandStep.DATABASE_ARG, targetDatabase);
            diffCmd.addArgumentValue("referenceDatabase", referenceDatabase);
            diffCmd.addArgumentValue("changelogFile", tempFile.toAbsolutePath().toString());
            diffCmd.addArgumentValue(DiffOutputControlCommandStep.INCLUDE_CATALOG_ARG, false);
            diffCmd.addArgumentValue(DiffOutputControlCommandStep.INCLUDE_SCHEMA_ARG, false);
            diffCmd.addArgumentValue(DiffOutputControlCommandStep.INCLUDE_TABLESPACE_ARG, false);
            
            diffCmd.execute();

            return Files.readString(tempFile);

        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    /**
     * Used ONLY when creating a brand new tenant.
     * Generates the changelog and applies it immediately.
     */
    public void initializeNewTenantSchema(String tenantId) throws Exception {
        log.info("Initializing NEW schema for tenant: {}", tenantId);
        String changelog = previewMigration(tenantId);

        // FAIL-FAST LOGIC: For a new tenant, an empty changelog is an error.
        if (changelog == null || !changelog.contains("<changeSet")) {
            log.error("CRITICAL PROVISIONING FAILURE: Liquibase diff generated no changes for new tenant '{}'. This likely indicates a misconfiguration (e.g., wrong entity package path). Halting.", tenantId);
            throw new IllegalStateException("Failed to generate initial schema for new tenant '" + tenantId + "'. No database changes were detected.");
        }

        log.info("Applying generated changelog to initialize schema for tenant: {}", tenantId);
        applyMigration(tenantId, changelog);
        log.info("Schema initialized successfully for: {}", tenantId);
    }

    /**
     * Applies a raw XML changelog string to the tenant.
     */
    public void applyMigration(String tenantId, String changelogContent) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchemaExists(connection, tenantId);
            connection.createStatement().execute("SET search_path TO \"" + tenantId + "\"");

            Database targetDatabase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            targetDatabase.setDefaultSchemaName(tenantId);

            Liquibase liquibase = new Liquibase(
                    "dynamic-changelog.xml", 
                    new StringResourceAccessor("dynamic-changelog.xml", changelogContent),
                    targetDatabase
            );

            liquibase.update("");
            log.info("Migration applied to tenant: {}", tenantId);
        }
    }

    private void ensureSchemaExists(Connection connection, String tenantId) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + tenantId + "\"");
        }
    }

    public void syncPublicSchema() throws Exception {
        String schemaName = "public";
        log.info("Bootstrapping Public Schema: {}", schemaName);

        try (Connection connection = dataSource.getConnection()) {
            ensureSchemaExists(connection, schemaName);
            connection.createStatement().execute("SET search_path TO \"" + schemaName + "\"");

            Database targetDatabase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            targetDatabase.setDefaultSchemaName(schemaName);

            Liquibase liquibase = new Liquibase(
                    "db/migration/public/V1__init_public.sql",
                    new ClassLoaderResourceAccessor(),
                    targetDatabase
            );

            liquibase.update("");
            log.info("Public schema bootstrapped successfully.");
        }
    }

    public void dropSchema(String tenantId) throws Exception {
        if ("public".equalsIgnoreCase(tenantId) || "information_schema".equalsIgnoreCase(tenantId)) {
            throw new IllegalArgumentException("Cannot drop system or public schemas.");
        }

        log.warn("DESTRUCTIVE ACTION: Dropping schema for tenant: {}", tenantId);

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {

            stmt.execute("DROP SCHEMA IF EXISTS \"" + tenantId + "\" CASCADE");
            log.info("Schema dropped successfully: {}", tenantId);
        }
    }

    public static class StringResourceAccessor extends AbstractResourceAccessor {
        private final String filename;
        private final String content;

        public StringResourceAccessor(String filename, String content) {
            this.filename = filename;
            this.content = content;
        }

        @Override
        public List<Resource> getAll(String path) {
            if (path != null && path.endsWith(filename)) {
                return Collections.singletonList(createResource());
            }
            return Collections.emptyList();
        }

        @Override
        public List<Resource> search(String pathToSearch, boolean recursive) {
            if (pathToSearch != null && pathToSearch.endsWith(filename)) {
                return Collections.singletonList(createResource());
            }
            return Collections.emptyList();
        }

        @Override
        public void close() throws Exception { }

        @Override
        public List<String> describeLocations() {
            return Collections.singletonList("String Memory");
        }

        private Resource createResource() {
            return new Resource() {
                @Override
                public String getPath() { return filename; }
                @Override
                public URI getUri() { return URI.create("string://" + filename); }
                @Override
                public boolean exists() { return true; }
                @Override
                public Resource resolve(String other) { return null; }
                @Override
                public Resource resolveSibling(String other) { return null; }
                @Override
                public InputStream openInputStream() throws IOException {
                    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                }
                @Override
                public boolean isWritable() { return false; }
                @Override
                public OutputStream openOutputStream(OpenOptions openOptions) throws IOException {
                    throw new IOException("Read-only resource");
                }
            };
        }
    }
}