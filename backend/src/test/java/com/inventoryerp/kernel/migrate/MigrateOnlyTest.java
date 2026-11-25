package com.inventoryerp.kernel.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Finance readiness audit B4: the migrate step migrates even when the shared env file disables Flyway for the API. */
class MigrateOnlyTest {

    @Test
    @DisplayName("B4: --migrate-only forces Flyway on as a command-line argument, which outranks SPRING_FLYWAY_ENABLED=false")
    void flywayForcedOnOverTheEnvironment() {
        String[] args = MigrateOnly.arguments(new String[] {"--migrate-only", "--spring.main.lazy-initialization=false"});
        assertThat(args).doesNotContain("--migrate-only").contains("--spring.flyway.enabled=true");

        // Spring Boot's order: command-line arguments first, then the OS environment (the production env file)
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("os-env", Map.of("spring.flyway.enabled", "false")));
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
    }
}
