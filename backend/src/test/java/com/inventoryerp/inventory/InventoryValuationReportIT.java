package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The operational valuation report ({@code GET /inventory/reports/valuation}) values every quant by its costing method
 * exactly like the accounting valuation ({@code GET /reports/inventory/valuation}): FIFO lots at their own cost,
 * STANDARD at the standard (cost price), lot-tracked products by lot, AVCO at the running average.
 */
class InventoryValuationReportIT extends ApiTestSupport {

    String run;
    String adminId;
    String wh1;
    String wh2;
    String avco;
    String fifo;
    String standard;
    String lots;

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        run = "VR" + suffix();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        wh1 = location();
        wh2 = location();
        avco = product("AVCO", false, null);
        fifo = product("FIFO", true, null);
        standard = product("STANDARD", false, 50);
        lots = product("AVCO", true, null);

        receive(wh1, avco, 10, 100, null);
        receive(wh1, avco, 10, 200, null);
        receive(wh1, fifo, 10, 10, "A-" + suffix());
        receive(wh1, fifo, 10, 20, "B-" + suffix());
        receive(wh1, standard, 10, 60, null);
        receive(wh1, lots, 10, 100, "L1-" + suffix());
        receive(wh2, lots, 5, 30, "L2-" + suffix());

        // Issue part of the FIFO stock: the remaining lots no longer value at the quant's running average.
        JsonNode so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", wh1, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", fifo, "qty", 5))));
        ok(200, "POST", "/inventory/stock-outs/" + so.get("id").asString() + "/approve", null);
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "VR-" + suffix(), "name", "Valuation "
                + suffix(), "type", "warehouse")).get("id").asString();
    }

    String product(String method, boolean batch, Object costPrice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", run + "-" + suffix());
        body.put("name", run + " " + method + (batch ? " lots" : ""));
        body.put("productType", "stockable");
        body.put("trackInventory", true);
        body.put("costingMethod", method);
        body.put("batchTracking", batch);
        if (costPrice != null) {
            body.put("costPrice", costPrice);
        }
        return ok(201, "POST", "/products", body).get("id").asString();
    }

    void receive(String warehouse, String productId, Object qty, Object cost, String batch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "goods");
        line.put("quantity", qty);
        line.put("unitCost", cost);
        if (batch != null) {
            line.put("batchNumber", batch);
        }
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "receivedAt",
                Instant.now().toString(), "lines", List.of(line)));
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.toString().replace("\"", ""));
    }

    /** productId → row of the operational report, filtered to this run's products. */
    Map<String, JsonNode> report(String query) {
        JsonNode body = ok(200, "GET", "/inventory/reports/valuation?search=" + run + query, null);
        Map<String, JsonNode> out = new HashMap<>();
        body.get("data").forEach(r -> out.put(r.get("productId").asString(), r));
        return out;
    }

    /** productId → exact current value of the accounting valuation. */
    Map<String, BigDecimal> accounting() {
        JsonNode body = ok(200, "GET", "/reports/inventory/valuation", null);
        Map<String, BigDecimal> out = new HashMap<>();
        body.get("items").forEach(i -> out.put(i.get("productId").asString(), new BigDecimal(i.get("totalValue")
                .asString())));
        return out;
    }

    BigDecimal lotValue(String productId, String locationId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity * \"unitCost\"), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ?"
                        + " AND \"locationId\" = ? AND \"isActive\" = true AND quantity > 0")
                .params(productId, locationId).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("per-product value equals the accounting valuation for AVCO, FIFO, STANDARD and lot-tracked stock")
    void matchesAccountingValuation() {
        Map<String, JsonNode> rows = report("");
        Map<String, BigDecimal> gl = accounting();
        for (String p : List.of(avco, fifo, standard, lots)) {
            assertThat(rows).containsKey(p);
            assertThat(num(rows.get(p).get("value"))).as(rows.get(p).toString()).isEqualByComparingTo(gl.get(p));
        }
        assertThat(num(rows.get(avco).get("value"))).isEqualByComparingTo("3000");
        // FIFO: the remaining lots at their own cost (not 15 quantity × running average).
        assertThat(num(rows.get(fifo).get("value"))).isEqualByComparingTo(lotValue(fifo, wh1));
        assertThat(num(rows.get(fifo).get("quantity"))).isEqualByComparingTo("15");
        // STANDARD: quantity × standard (cost price), not the receipt cost.
        BigDecimal std = jdbc.sql("SELECT \"costPrice\" FROM \"Product\" WHERE id = ?").param(standard)
                .query(BigDecimal.class).single();
        assertThat(num(rows.get(standard).get("value"))).isEqualByComparingTo(std.multiply(BigDecimal.TEN));
        assertThat(num(rows.get(lots).get("value"))).isEqualByComparingTo("1150");
        JsonNode locations = rows.get(lots).get("locations");
        assertThat(locations).hasSize(2);
        BigDecimal byLocation = BigDecimal.ZERO;
        for (JsonNode l : locations) {
            byLocation = byLocation.add(num(l.get("value")));
        }
        assertThat(byLocation).isEqualByComparingTo("1150");
    }

    @Test
    @DisplayName("a location filter values only that location's quants")
    void locationFilter() {
        Map<String, JsonNode> rows = report("&locationId=" + wh2);
        assertThat(rows.keySet()).containsExactly(lots);
        JsonNode row = rows.get(lots);
        assertThat(num(row.get("quantity"))).isEqualByComparingTo("5");
        assertThat(num(row.get("value"))).isEqualByComparingTo(lotValue(lots, wh2)).isEqualByComparingTo("150");
        assertThat(row.get("locations")).hasSize(1);
        assertThat(row.get("locations").get(0).get("locationId").asString()).isEqualTo(wh2);

        Map<String, JsonNode> main = report("&locationId=" + wh1);
        assertThat(num(main.get(lots).get("value"))).isEqualByComparingTo("1000");
        assertThat(num(main.get(fifo).get("value"))).isEqualByComparingTo(lotValue(fifo, wh1));
    }

    @Test
    @DisplayName("stock items list resolves product, unit and location in batch")
    void itemsListIncludes() {
        JsonNode page = ok(200, "GET", "/inventory/items?pageSize=200&search=" + run, null);
        assertThat(page.get("data").size()).isGreaterThanOrEqualTo(5);
        for (JsonNode r : page.get("data")) {
            assertThat(r.get("product").get("name").asString()).startsWith(run);
            assertThat(r.get("product").has("uomId")).isFalse();
            assertThat(r.get("location").get("id").asString()).isEqualTo(r.get("locationId").asString());
            assertThat(r.get("location").get("name").asString()).startsWith("Valuation ");
        }
    }
}
