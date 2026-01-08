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

        log.info("Starting database views initialization for tenant '{}'...", tenantId);

        try {
            // *** THE FINAL, CORRECT FIX ***
            // Use the flexible `execute` method with a PreparedStatementCallback.
            // This allows us to run a statement that returns a result set without processing it.
            jdbcTemplate.execute("SELECT set_config('search_path', ?, false)", (PreparedStatement ps) -> {
                ps.setString(1, tenantId);
                ps.execute(); // This just executes the statement.
                return null;  // We return null because we don't need any result from the callback.
            });

            List<String> requiredViews = List.of(
                    "v_corporate_report",
                    "v_conference_calls_report"
            );

            requiredViews.forEach(this::createViewIfNotExists);

            log.info("Database views initialization tasks complete for tenant '{}'.", tenantId);
        } catch (Exception e) {
            log.error("A critical error occurred during view initialization for tenant '{}'.", tenantId, e);
            throw new RuntimeException("View initialization failed for tenant: " + tenantId, e);
        }
    }

    public void createViewIfNotExists(String viewName) {
        if (viewExists(viewName)) {
            log.info("View '{}' already exists in current tenant schema. Skipping creation.", viewName);
            return;
        }

        log.info("View '{}' does not exist in current tenant schema. Attempting to create from SQL file.", viewName);
        try {
            String sqlFilePath = "db/views/" + viewName + ".sql";
            Resource resource = new ClassPathResource(sqlFilePath);

            if (!resource.exists()) {
                log.error("SQL file not found for view '{}'. Expected at: {}", viewName, sqlFilePath);
                return;
            }

            String sql = resourceAsString(resource);
            jdbcTemplate.execute(sql);
            log.info("Successfully created view '{}' in current tenant schema.", viewName);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create view '" + viewName + "'", e);
        }
    }
    
    private boolean viewExists(String viewName) {
        String sql = "SELECT EXISTS (SELECT FROM pg_views WHERE schemaname = current_schema() AND viewname = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, viewName);
        return exists != null && exists;
    }

    private String resourceAsString(Resource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}