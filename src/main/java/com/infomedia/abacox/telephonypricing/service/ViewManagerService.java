package com.infomedia.abacox.telephonypricing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class ViewManagerService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * This method is triggered once the application is fully started.
     * It initializes necessary database components, such as views.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Application ready. Starting database views initialization tasks...");

        // Define a list of all views that the application requires.
        // This makes it easy to add more views in the future.
        List<String> requiredViews = List.of(
                "v_corporate_report"
                //, "v_another_report_view" // <-- Add future views here
                //, "v_summary_view"
        );

        // Iterate and ensure each view exists.
        requiredViews.forEach(this::createViewIfNotExists);

        log.info("Database views initialization tasks complete.");
    }

    /**
     * Checks if a database view exists and creates it from a corresponding SQL file if it does not.
     * The SQL file is expected to be in 'src/main/resources/db/views/'.
     *
     * @param viewName The name of the database view to check and potentially create.
     */
    public void createViewIfNotExists(String viewName) {
        if (viewExists(viewName)) {
            log.info("View '{}' already exists. Skipping creation.", viewName);
            return;
        }

        log.info("View '{}' does not exist. Attempting to create from SQL file.", viewName);
        try {
            // Construct the path to the SQL file within the classpath resources.
            String sqlFilePath = "db/views/" + viewName + ".sql";
            Resource resource = new ClassPathResource(sqlFilePath);

            if (!resource.exists()) {
                log.error("SQL file not found for view '{}'. Expected at: {}", viewName, sqlFilePath);
                return;
            }

            // Read the SQL content from the file.
            String sql = resourceAsString(resource);

            // Execute the SQL to create the view.
            jdbcTemplate.execute(sql);
            log.info("Successfully created view '{}'.", viewName);

        } catch (Exception e) {
            log.error("Failed to create view '{}'. Error: {}", viewName, e.getMessage());
            // Depending on your application's needs, you might want to re-throw this as a runtime exception
            // to halt application startup if the view is critical.
            // throw new RuntimeException("Failed to create critical view: " + viewName, e);
        }
    }

    /**
     * Checks if a specific view exists in the database.
     * This query is specific to PostgreSQL.
     *
     * @param viewName The name of the view.
     * @return true if the view exists, false otherwise.
     */
    private boolean viewExists(String viewName) {
        // PostgreSQL-specific query to check for a view in the 'public' schema.
        String sql = "SELECT EXISTS (SELECT FROM pg_views WHERE schemaname = 'public' AND viewname = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, viewName);
        return exists != null && exists;
    }

    /**
     * A utility method to read a resource file into a String.
     *
     * @param resource The resource to read.
     * @return The content of the resource as a String.
     */
    private String resourceAsString(Resource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}