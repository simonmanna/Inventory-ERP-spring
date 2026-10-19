package com.inventoryerp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres 16 matching production (max_locks_per_transaction raised for the 298-table schema). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withUrlParam("stringtype", "unspecified")
                .withCommand("postgres", "-c", "max_locks_per_transaction=1024")
                .withReuse(true);
    }
}
