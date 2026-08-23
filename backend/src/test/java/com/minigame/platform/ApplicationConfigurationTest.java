package com.minigame.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTest {
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
}
