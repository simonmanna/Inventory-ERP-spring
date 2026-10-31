package com.inventoryerp.biexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.biexport.web.BiExportController;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The reporting views as CSV: every view runs, one organization only, only whitelisted datasets. */
class BiExportIT extends ApiTestSupport {

    HttpResponse<String> csv(String token, String dataset) throws Exception {
        return get(token, dataset + "/export.csv");
    }

    HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bi/datasets/" + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("every dataset lists its columns without the organization and exports a header")
    void everyDataset() throws Exception {
        JsonNode datasets = ok(200, "GET", "/bi/datasets", null);
        assertThat(datasets.size()).isEqualTo(8);
        for (JsonNode d : datasets) {
            assertThat(d.get("columns").size()).isPositive();
            for (JsonNode c : d.get("columns")) {
                assertThat(c.asString()).isNotEqualTo("organizationId");
            }
            HttpResponse<String> file = csv(adminToken, d.get("key").asString());
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(file.body()).contains(d.get("columns").get(0).asString());
        }
    }

    @Test
    @DisplayName("a view shows the caller's organization only")
    void tenancy() throws Exception {
        String product = UUID.randomUUID().toString();
        String location = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? LIMIT 1")
                .param(demoOrg).query(String.class).single();
        String code = "BI-" + product.substring(0, 8);
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'BI gravel', 'stockable', 'AVCO'::\"CostingMethod\", now())")
                .params(product, demoOrg, code).update();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 12, now())")
                .params(UUID.randomUUID().toString(), demoOrg, product, location).update();
        assertThat(csv(adminToken, "stock-on-hand").body()).contains(code);
        assertThat(csv(otherToken, "stock-on-hand").body()).doesNotContain(code);
    }

    @Test
    @DisplayName("an unknown dataset is not found, a bad date is refused")
    void validation() throws Exception {
        assertThat(csv(adminToken, "pg_user").statusCode()).isEqualTo(404);
        assertThat(get(adminToken, "sales-lines/export.csv?from=soon").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(BiExportController.class)).isEqualTo(2);
    }
}
