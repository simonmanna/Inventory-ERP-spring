package com.inventoryerp.kernel.migrate;

import java.util.Arrays;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Finance readiness audit B4 (P2-11): run the Flyway migrations and exit, so production can migrate as the schema
 * owner in a separate step and start the API without the owner's login ({@code SPRING_FLYWAY_ENABLED=false}, no
 * {@code FLYWAY_USER}).
 *
 * <p>{@code java -jar app.jar --migrate-only} (or {@code APP_MIGRATE_ONLY=true}) starts a minimal context holding only
 * the DataSource and Flyway auto-configurations: it reads the same {@code application.yml} (locations, placeholders,
 * baseline) as the API, but starts no web server, job, listener or seed. Exit code 0 when the schema is current.
 *
 * <p>Deliberately not a {@code @Configuration}: the API's component scan must not pick it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class MigrateOnly {

    static final String FLAG = "--migrate-only";

    /** Whether this launch only migrates. */
    public static boolean requested(String[] args) {
        return Arrays.asList(args).contains(FLAG) || "true".equalsIgnoreCase(System.getenv("APP_MIGRATE_ONLY"));
    }

    /** Runs the migrations (Flyway runs while the context starts) and returns the process exit code. */
    public static int run(String[] args) {
        String[] rest = Arrays.stream(args).filter(a -> !FLAG.equals(a)).toArray(String[]::new);
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MigrateOnly.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties("spring.flyway.enabled=true")
                .run(rest)) {
            System.out.println("Migrations applied; schema is current.");
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Migration failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
