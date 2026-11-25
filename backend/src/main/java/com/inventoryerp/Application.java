package com.inventoryerp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {

    static {
        // Prisma stores DateTime as UTC in timestamp-without-time-zone columns; pin the JVM (and with it the
        // PgJDBC session TimeZone) to UTC so JDBC reads/writes and now() agree with Nest.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        if (com.inventoryerp.kernel.migrate.MigrateOnly.requested(args)) {
            // B4: a separate migration step (as the schema owner); the API then starts without the owner's login
            System.exit(com.inventoryerp.kernel.migrate.MigrateOnly.run(args));
        }
        SpringApplication.run(Application.class, args);
    }
}
