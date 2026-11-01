package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Inventory reads over real movements: batch receipts with expiry, issues, layered (FIFO) adjustments up and down,
 * then the ledger entry detail of each movement, item detail, stock levels, registers and the reports with filters.
 */
class InventoryReadsIT extends ApiTestSupport {

    String adminId;
    String category;
    String uom;
    String loc;
    String fifo;
    String avco;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        loc = ok(201, "POST", "/inventory/locations", Map.of("code", "RD-" + sfx(), "name", "Reads " + sfx(),
                "type", "warehouse")).get("id").asString();
        fifo = product("FIFO", true);
        avco = product("AVCO", false);
        String expiry = LocalDate.now().plusDays(10).toString();
        stockIn(List.of(line(fifo, 10, 3, "B-1", expiry), line(fifo, 5, 5, "B-2", LocalDate.now().plusDays(90)
                .toString()), line(avco, 20, 2, null, null)));
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(fifo, 4, null, null, null), line(avco, 6, null, null,
                        null))));
        for (Map<String, Object> item : List.of(Map.<String, Object>of("productId", fifo, "qtyActual", 13),
                Map.<String, Object>of("productId", avco, "qtyActual", 12))) {
            String adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById",
                    adminId, "approvedById", adminId, "items", List.of(item))).get("id").asString();
            assertThat(ok(200, "POST", "/inventory/adjustments/" + adj + "/approve", Map.of()).get("status")
                    .asString()).isEqualTo("completed");
        }
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String product(String costing, boolean batch) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"categoryId\", \"uomId\", \"batchTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", 4, ?, ?, ?, now())")
                .params(id, demoOrg, "RD-" + sfx(), "Read item " + sfx(), costing, category, uom, batch).update();
        return id;
    }

    static Map<String, Object> line(String productId, Object qty, Object unitCost, String batch, String expiry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        if (batch != null) {
            m.put("batchNumber", batch);
            m.put("expiryDate", expiry);
        }
        return m;
    }

    void stockIn(List<Map<String, Object>> items) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", items));
    }

    @Test
    @DisplayName("stock after the movements: FIFO adjusted up, AVCO adjusted up from 14")
    void onHand() {
        assertThat(jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(fifo, loc).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("13");
        assertThat(jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(avco, loc).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("every ledger entry of the product resolves its source document")
    void ledgerDetail() {
        for (String product : List.of(fifo, avco)) {
            JsonNode page = ok(200, "GET", "/inventory/ledger?productId=" + product + "&pageSize=50", null);
            JsonNode rows = page.has("data") ? page.get("data") : page;
            assertThat(rows.size()).isGreaterThanOrEqualTo(3);
            for (JsonNode row : rows) {
                JsonNode detail = ok(200, "GET", "/inventory/ledger/" + row.get("id").asString(), null);
                assertThat(detail.toString()).contains(product);
            }
        }
    }

    @Test
    @DisplayName("item detail, stock levels and registers")
    void itemsLevelsRegisters() {
        assertThat(ok(200, "GET", "/inventory/items/" + fifo, null).toString()).contains("B-1");
        for (String q : List.of("", "?locationId=" + loc, "?search=Read%20item", "?page=2&pageSize=2")) {
            ok(200, "GET", "/inventory/product-stock-levels" + q, null);
        }
        String range = "?start=" + LocalDate.now().minusDays(1) + "&end=" + LocalDate.now();
        for (String kind : List.of("stock_in", "stock_out", "damages", "adjustments", "transfers", "all")) {
            JsonNode r = ok(200, "GET", "/inventory/reports/register/" + kind + range + "&locationId=" + loc, null);
            assertThat(r.isObject()).isTrue();
        }
        assertThat(ok(200, "GET", "/inventory/reports/register/all" + range + "&categoryId=" + category, null)
                .isObject()).isTrue();
    }

    @Test
    @DisplayName("reports with filters: expiring, reorder, movements, item movements, analysis, reconciliation")
    void reports() {
        assertThat(ok(200, "GET", "/inventory/reports/expiring?days=30&locationId=" + loc, null).toString())
                .contains("B-1");
        ok(200, "GET", "/inventory/reports/reorder?locationId=" + loc, null);
        String range = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now().plusDays(1);
        ok(200, "GET", "/inventory/reports/movements?" + range + "&locationId=" + loc, null);
        for (String product : List.of(fifo, avco)) {
            ok(200, "GET", "/inventory/reports/item-movements?" + range + "&productId=" + product + "&locationId="
                    + loc, null);
        }
        ok(200, "GET", "/inventory/reports/movement-analysis?" + range + "&locationId=" + loc + "&categoryId="
                + category, null);
        ok(200, "GET", "/inventory/reports/reconciliation?locationId=" + loc, null);
    }
}
