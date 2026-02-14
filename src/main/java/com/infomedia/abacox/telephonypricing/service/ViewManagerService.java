package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class ViewManagerService implements TenantInitializer {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    @Override
    public void onTenantInit(String tenantId) {
        if (tenantId == null || "public".equals(tenantId)) {
            log.debug("Skipping tenant-specific view initialization for context: {}", tenantId);
            return;
        }

        log.info("Starting database views update/initialization for tenant '{}'...", tenantId);

        try {
            // Set the search path for the current transaction
            jdbcTemplate.execute("SELECT set_config('search_path', ?, false)", (PreparedStatement ps) -> {
                ps.setString(1, tenantId);
                ps.execute();
                return null;
            });

            List<String> requiredViews = List.of(
                    "v_corporate_report");

            // Changed method reference to the new logic
            requiredViews.forEach(this::createOrReplaceView);

            log.info("Database views initialization tasks complete for tenant '{}'.", tenantId);
        } catch (Exception e) {
            log.error("A critical error occurred during view initialization for tenant '{}'.", tenantId, e);
            throw new RuntimeException("View initialization failed for tenant: " + tenantId, e);
        }
    }

    public void createOrReplaceView(String viewName) {
        log.info("Processing view '{}' for current tenant schema.", viewName);

        try {
            // 1. Drop the view if it exists.
            // We use CASCADE to ensure that if a view has been modified in a way
            // that breaks dependencies, the old version is cleared out entirely.
            // Note: Ensure your 'requiredViews' list is ordered by dependency (independent
            // views first).
            String dropSql = "DROP VIEW IF EXISTS " + viewName + " CASCADE";
            jdbcTemplate.execute(dropSql);
            log.debug("Dropped view '{}' (if it existed).", viewName);

            // 2. Load the SQL definition
            String sqlFilePath = "db/views/" + viewName + ".sql";
            Resource resource = new ClassPathResource(sqlFilePath);

            if (!resource.exists()) {
                log.error("SQL file not found for view '{}'. Expected at: {}", viewName, sqlFilePath);
                return;
            }

            // 3. Create the view
            String createSql = resourceAsString(resource);
            jdbcTemplate.execute(createSql);
            log.info("Successfully created/updated view '{}' in current tenant schema.", viewName);

        } catch (Exception e) {
            throw new RuntimeException("Failed to update view '" + viewName + "'", e);
        }
    }

    // The 'viewExists' method was removed as it is no longer needed
    // because we are using 'DROP VIEW IF EXISTS'.

    private String resourceAsString(Resource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}