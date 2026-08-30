package com.minigame.platform.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTestDatabaseTest {
    @Test
    void external_database_requires_an_explicit_destructive_test_opt_in() {
        assertThatThrownBy(() -> PostgresTestDatabase.validateExternalDatabase(
                "jdbc:postgresql://localhost:5432/minigame_test",
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_DB_ALLOW_DESTRUCTIVE");
    }

    @Test
    void external_database_rejects_a_non_test_database_even_with_opt_in() {
        assertThatThrownBy(() -> PostgresTestDatabase.validateExternalDatabase(
                "jdbc:postgresql://localhost:5432/minigame",
                "true"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_test");
    }

    @Test
    void external_database_accepts_a_dedicated_test_database_with_opt_in() {
        assertThatCode(() -> PostgresTestDatabase.validateExternalDatabase(
                "jdbc:postgresql://localhost:5432/minigame_test?sslmode=disable",
                "true"
        )).doesNotThrowAnyException();
    }
}
