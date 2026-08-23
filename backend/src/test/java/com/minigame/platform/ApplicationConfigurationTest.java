package com.minigame.platform;

import com.minigame.platform.auth.application.SessionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ApplicationConfigurationTest {
    private final WebApplicationContextRunner application = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MinigameApplication.class)
            .withPropertyValues(
                    "spring.autoconfigure.exclude="
                            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                            + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            );

    @Test
    void configuresServerAndManagementAtTheRootLevel() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(properties.getProperty("server.port")).isEqualTo("${PORT:8080}");
        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,metrics");
        assertThat(properties.getProperty("spring.server.port")).isNull();
        assertThat(properties.getProperty("spring.management.endpoints.web.exposure.include")).isNull();
    }

    @Test
    void nonLocalStartupFailsWithoutApplicationSessionSecret() {
        application.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("Could not resolve placeholder 'APP_SESSION_SECRET'");
        });
    }

    @Test
    void localProfileProvidesAnExplicitDevelopmentSessionSecret() {
        application.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasSingleBean(SessionTokenService.class));
    }

    @Test
    void packagesTheCloudSqlPostgresSocketFactoryForRuntimeConnections() {
        assertThatCode(() -> Class.forName("com.google.cloud.sql.postgres.SocketFactory"))
                .doesNotThrowAnyException();
    }
}
