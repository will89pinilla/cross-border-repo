package com.cobre.accounts.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * An empty database inside the shared PostgreSQL 18 container, migrated by Flyway as
 * {@code accounts_owner} with the same grants as the real one (spec §9), for tests that need exact
 * totals (AC12) that other test classes would otherwise disturb. No Spring context is started.
 */
public final class FreshDatabase {

    private final String url;

    private FreshDatabase(String url) {
        this.url = url;
    }

    public static FreshDatabase create() {
        var postgres = PostgresIntegrationTest.POSTGRES;
        String name = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        // Same database setup as docker/postgres/init/01-roles.sh, for a new database.
        try (Connection admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + name + " OWNER " + PostgresIntegrationTest.OWNER_ROLE);
            sql.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
            sql.execute("GRANT CONNECT ON DATABASE " + name + " TO " + PostgresIntegrationTest.APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database " + name, e);
        }
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        Flyway.configure()
                .dataSource(url, PostgresIntegrationTest.OWNER_ROLE, PostgresIntegrationTest.OWNER_PASSWORD)
                .load()
                .migrate();
        return new FreshDatabase(url);
    }

    public String url() {
        return url;
    }

    /** Connected as {@code accounts_app}, like the application. */
    public DataSource appDataSource() {
        return new DriverManagerDataSource(url, PostgresIntegrationTest.APP_ROLE, PostgresIntegrationTest.APP_PASSWORD);
    }
}
