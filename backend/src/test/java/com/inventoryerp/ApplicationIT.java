package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Boots the full context against a fresh Postgres: Flyway applies V1..Vn and Hibernate validates
 * all 296 generated entities against the real schema (ddl-auto=validate). Also checks the public
 * health probe and the Nest-shaped 401 body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void migratesAndValidatesSchema() {
        Integer tables = jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public'")
                .query(Integer.class)
                .single();
        assertThat(tables).isGreaterThanOrEqualTo(298);
    }

    @Autowired
    tools.jackson.databind.json.JsonMapper mapper;

    @Test
    void jsonMatchesNestWireFormat() {
        var json = mapper.writeValueAsString(java.util.Map.of(
                "amount", new java.math.BigDecimal("12.500000"),
                "whole", new java.math.BigDecimal("1500.000000"),
                "at", java.time.Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(json)
                .contains("\"amount\":\"12.5\"")
                .contains("\"whole\":\"1500\"")
                .contains("\"at\":\"2026-01-01T00:00:00.000Z\"");
    }

    @Test
    void healthIsPublic() {
        String body = RestClient.create("http://localhost:" + port).get().uri("/api/v1/health").retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"ok\"");
    }

    @Test
    void unauthenticatedReturnsNestShape() {
        var response = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/api/v1/products")
                .exchange((req, res) -> new Object[] {res.getStatusCode().value(), res.bodyTo(String.class)}, true);
        assertThat(response[0]).isEqualTo(401);
        assertThat((String) response[1]).contains("\"statusCode\":401").contains("\"message\":\"Unauthorized\"");
    }
}
