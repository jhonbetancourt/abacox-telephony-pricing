package com.infomedia.abacox.telephonypricing.multitenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class SchemaConnectionProvider implements MultiTenantConnectionProvider {

    private final DataSource dataSource;

    public SchemaConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(Object tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        try {
            String schema = (tenantIdentifier != null) ? tenantIdentifier.toString() : "public";
            // PostgreSQL syntax to switch schema
            connection.createStatement().execute("SET search_path TO \"" + schema + "\"");
        } catch (SQLException e) {
            throw new SQLException("Could not alter JDBC connection to specified schema [" + tenantIdentifier + "]", e);
        }
        return connection;
    }

    @Override
    public void releaseConnection(Object tenantIdentifier, Connection connection) throws SQLException {
        try {
            // Reset to public before returning to pool
            connection.createStatement().execute("SET search_path TO public");
        } catch (SQLException e) {
            // Log error, but don't throw, as the connection is closing anyway
        }
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    // --- Wrapped Interface Methods ---

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return unwrapType.cast(this);
        }
        throw new UnknownUnwrapTypeException(unwrapType);
    }
}