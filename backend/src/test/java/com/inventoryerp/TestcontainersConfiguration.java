package com.inventoryerp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres 16 matching production (max_locks_per_transaction raised for the 298-table schema).
 *
 * <p>Without Docker, set {@code IT_EXTERNAL_DB=true} plus {@code JDBC_DATABASE_URL} / {@code DB_USER} /
 * {@code DB_PASSWORD} to run the integration suites against an already running, empty Postgres 16 started with
 * {@code -c max_locks_per_transaction=1024} (see docs/TESTING.md); the container is then not created.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @ConditionalOnExpression("'${IT_EXTERNAL_DB:false}' != 'true'")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withUrlParam("stringtype", "unspecified")
                .withCommand("postgres", "-c", "max_locks_per_transaction=1024")
                .withReuse(true);
    }
}
