package com.cobre.accounts.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests against a real PostgreSQL 18. Roles are created by the same init script that
 * Docker Compose uses ({@code docker/postgres/init/01-roles.sh}); Flyway migrates as
 * {@code accounts_owner} and the application connects as {@code accounts_app} (spec §9).
 *
 * <p>One container for the whole test JVM, started once: Spring caches the application context
 * across test classes, so a per-class container would leave cached contexts pointing at a
 * stopped database. Testcontainers' Ryuk removes it when the JVM exits.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    protected static final String OWNER_ROLE = "accounts_owner";
    protected static final String APP_ROLE = "accounts_app";
    static final String OWNER_PASSWORD = "owner_test_pw";
    static final String APP_PASSWORD = "app_test_pw";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("accounts")
            .withEnv("ACCOUNTS_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("ACCOUNTS_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres/init/01-roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }
}
