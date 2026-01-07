package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TenantProvider {

    private final DataSource dataSource;

    /**
     * Returns a list of all schema names that represent tenants.
     * Filters out system schemas.
     */
    public List<String> getAllTenants() {
        List<String> tenants = new ArrayList<>();
        // Always include default/public if your logic requires it
        // tenants.add("public"); 

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // PostgreSQL query to find schemas excluding system ones
            ResultSet rs = statement.executeQuery(
                "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'public') " +
                "AND schema_name NOT LIKE 'pg_%'"
            );

            while (rs.next()) {
                tenants.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch tenant list", e);
        }
        return tenants;
    }
}