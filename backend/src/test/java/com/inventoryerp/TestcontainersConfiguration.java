package com.inventoryerp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres 16 matching production (max_locks_per_transaction raised for the 298-table schema).
 *
 * <p>Without Docker, set {@code IT_EXTERNAL_DB=true} plus {@code JDBC_DATABASE_URL} / {@code DB_USER} /
 * {@code DB_PASSWORD} to run the integration suites against an already running, empty Postgres 16 started with
 * {@code -c max_locks_per_transaction=1024} (see docs/TESTING.md); the container is then not created.
 *
 * <p>Like production (docs/DATABASE.md section 6), Flyway runs as the container's superuser and the application as
 * {@code erp}, a role that owns nothing, so every integration test also runs under the tenant row-level security
 * policies. {@code db/callback/afterMigrate.sql} creates and grants the role; backups use the Flyway owner.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** The defaults of DB_USER / DB_PASSWORD, which the afterMigrate callback creates the role from. */
    static final String APP_ROLE = "erp";
    static final String APP_PASSWORD = "erp";

    @Bean
    @ConditionalOnExpression("'${IT_EXTERNAL_DB:false}' != 'true'")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withUrlParam("stringtype", "unspecified")
                .withCommand("postgres", "-c", "max_locks_per_transaction=1024")
                .withReuse(true);
    }

    /** The application's connection: the non-owner role, subject to row-level security. */
    @Bean
    @ConditionalOnExpression("'${IT_EXTERNAL_DB:false}' != 'true'")
    JdbcConnectionDetails appConnection(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return APP_ROLE;
            }

            @Override
            public String getPassword() {
                return APP_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    /** Flyway's connection: the container superuser owns the schema and creates the application role. */
    @Bean
    @ConditionalOnExpression("'${IT_EXTERNAL_DB:false}' != 'true'")
    FlywayConnectionDetails ownerConnection(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }
}
