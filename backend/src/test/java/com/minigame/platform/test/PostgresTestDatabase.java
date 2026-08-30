package com.minigame.platform.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

public final class PostgresTestDatabase {
    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            validateExternalDatabase(EXTERNAL_URL, System.getenv("TEST_DB_ALLOW_DESTRUCTIVE"));
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> environment("TEST_DB_USER", "minigame"));
            registry.add("spring.datasource.password", () -> environment("TEST_DB_PASSWORD", "local-minigame"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    static void validateExternalDatabase(String url, String destructiveOptIn) {
        if (!"true".equalsIgnoreCase(destructiveOptIn)) {
            throw new IllegalStateException(
                    "External test database requires TEST_DB_ALLOW_DESTRUCTIVE=true"
            );
        }
        try {
            if (!url.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("scheme");
            }
            var path = URI.create(url.substring("jdbc:".length())).getPath();
            var database = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            if (!database.endsWith("_test")) {
                throw new IllegalStateException("External database name must end with _test");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("TEST_DB_URL must be a PostgreSQL JDBC URL for a *_test database",
                    exception);
        }
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
