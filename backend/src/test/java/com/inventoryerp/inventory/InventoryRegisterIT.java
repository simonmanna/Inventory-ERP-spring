package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.InventoryRegisterService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Movement registers computed in SQL: one product with a receipt, a sample stock-out, a waste record, a damaged
 * adjustment and a transfer; summary, breakdowns, facets, filters, row order and the SQL row cap.
 */
class InventoryRegisterIT extends ApiTestSupport {

    @Autowired
    InventoryRegisterService registers;

    String adminId;
    String loc;
    String dest;
    String destName;
    String product;
    String range;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        loc = ok(201, "POST", "/inventory/locations", Map.of("code", "RG-" + sfx(), "name", "Register src " + sfx(),
                "type", "warehouse")).get("id").asString();
        destName = "Register dest " + sfx();
        dest = ok(201, "POST", "/inventory/locations", Map.of("code", "RG-" + sfx(), "name", destName,
                "type", "warehouse")).get("id").asString();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"uomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                        + " 'AVCO'::\"CostingMethod\", 2, ?, now())")
                .params(product, demoOrg, "RG-" + sfx(), "Register item " + sfx(), uom).update();

        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", 20,
                        "unitCost", 2))));
        JsonNode so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", product,
                        "qty", 3))));
        ok(200, "POST", "/inventory/stock-outs/" + so.get("id").asString() + "/approve", null);
        JsonNode waste = ok(200, "POST", "/inventory/waste", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "category", "spoiled", "items", List.of(Map.of("productId", product,
                        "qty", 2))));
        ok(200, "POST", "/inventory/waste/" + waste.get("id").asString() + "/approve", null);
        String adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "reason", "damaged", "items", List.of(Map.of("productId", product,
                        "qtyActual", 13)))).get("id").asString();
        assertThat(ok(200, "POST", "/inventory/adjustments/" + adj + "/approve", Map.of()).get("status").asString())
                .isEqualTo("completed");
        JsonNode trf = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", loc, "toLocationId", dest,
                "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of("productId", product,
                        "qtyRequested", 4))));
        assertThat(ok(200, "POST", "/inventory/transfers/" + trf.get("id").asString() + "/approve", null)
                .get("status").asString()).isEqualTo("completed");
        range = "?start=" + LocalDate.now().minusDays(1) + "&end=" + LocalDate.now().plusDays(1) + "&productId="
                + product;
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    JsonNode register(String kind, String extra) {
        return ok(200, "GET", "/inventory/reports/register/" + kind + range + extra, null);
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asString() : n.get(field).asString()));
        return out;
    }

    @Test
    @DisplayName("all: signed totals, gains / losses, facets over every line, newest row first")
    void all() {
        JsonNode r = register("all", "");
        JsonNode s = r.get("summary");
        assertThat(s.get("lines").asInt()).isEqualTo(6);
        // +20 −3 −2 −2 −4 +4 at 2 each
        assertThat(s.get("qty").asDouble()).isEqualTo(13);
        assertThat(s.get("value").asDouble()).isEqualTo(26);
        assertThat(s.get("gainQty").asDouble()).isEqualTo(24);
        assertThat(s.get("lossQty").asDouble()).isEqualTo(11);
        assertThat(s.get("grossValue").asDouble()).isEqualTo(70);
        assertThat(s.get("items").asInt()).isEqualTo(1);
        assertThat(s.get("locations").asInt()).isEqualTo(2);
        assertThat(r.get("truncated").asBoolean()).isFalse();
        assertThat(r.get("rows")).hasSize(6);
        assertThat(r.get("rows").get(5).get("type").asString()).isEqualTo("receipt");
        assertThat(r.get("rows").get(5).get("sourceLabel").asString()).isEqualTo("Direct stock in");
        assertThat(texts(r.get("facets").get("sources"), "value"))
                .contains("direct_stock_in", "stock_out", "waste", "stock_transfer");
        assertThat(texts(r.get("facets").get("reasons"), null)).contains("Damaged", "Spoiled", "Sample", "Transfer");
        assertThat(r.get("byProduct")).hasSize(1);
        assertThat(r.get("byProduct").get(0).get("lines").asInt()).isEqualTo(6);
        assertThat(r.get("byProduct").get(0).get("netValue").asDouble()).isEqualTo(26);
        assertThat(r.get("byHour")).hasSize(24);
        assertThat(r.get("byWeekday")).hasSize(7);
        assertThat(r.get("trend").get("bucket").asString()).isEqualTo("day");
        int trendLines = 0;
        for (JsonNode p : r.get("trend").get("points")) {
            trendLines += p.get("lines").asInt();
        }
        assertThat(trendLines).isEqualTo(6);

        // Filters narrow the totals; facets still describe every line of the window.
        JsonNode losses = register("all", "&direction=loss");
        assertThat(losses.get("summary").get("lines").asInt()).isEqualTo(4);
        assertThat(losses.get("summary").get("value").asDouble()).isEqualTo(-22);
        assertThat(texts(losses.get("facets").get("sources"), "value")).contains("direct_stock_in");
        assertThat(register("all", "&source=waste").get("summary").get("lines").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("stock_out, damages, adjustments and transfers classify by source document")
    void kinds() {
        JsonNode out = register("stock_out", "");
        assertThat(out.get("summary").get("lines").asInt()).isEqualTo(1);
        assertThat(out.get("rows").get(0).get("reason").asString()).isEqualTo("Sample");
        assertThat(out.get("summary").get("value").asDouble()).isEqualTo(6);

        JsonNode damages = register("damages", "");
        assertThat(damages.get("summary").get("lines").asInt()).isEqualTo(2);
        assertThat(texts(damages.get("byReason"), "key")).containsExactlyInAnyOrder("Spoiled", "Damaged");
        JsonNode adjRow = null;
        for (JsonNode row : damages.get("rows")) {
            if ("adjustment_out".equals(row.get("type").asString())) {
                adjRow = row;
            }
        }
        assertThat(adjRow).isNotNull();
        assertThat(adjRow.get("docRef").asString()).startsWith("ADJ-");

        JsonNode adjustments = register("adjustments", "");
        assertThat(adjustments.get("summary").get("qty").asDouble()).isEqualTo(-2);
        assertThat(adjustments.get("summary").get("lossValue").asDouble()).isEqualTo(4);

        JsonNode transfers = register("transfers", "&locationId=" + dest);
        assertThat(transfers.get("summary").get("lines").asInt()).isEqualTo(1);
        JsonNode t = transfers.get("rows").get(0);
        assertThat(t.get("type").asString()).isEqualTo("transfer_out");
        assertThat(t.get("toLocationId").asString()).isEqualTo(dest);
        assertThat(t.get("toLocation").asString()).isEqualTo(destName);
        assertThat(transfers.get("byLocation").get(0).get("key").asString()).endsWith(" → " + destName);
    }

    @Test
    @DisplayName("the row cap is applied in SQL: newest rows, truncated, summary still counts every line")
    @SuppressWarnings("unchecked")
    void rowCap() {
        Map<String, String> q = new HashMap<>();
        q.put("start", LocalDate.now().minusDays(1).toString());
        q.put("end", LocalDate.now().plusDays(1).toString());
        q.put("productId", product);
        Map<String, Object> capped = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> registers.register("all", q, 2));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) capped.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(capped.get("truncated")).isEqualTo(true);
        assertThat(((Number) ((Map<String, Object>) capped.get("summary")).get("lines")).intValue()).isEqualTo(6);
        Instant first = (Instant) rows.get(0).get("date");
        Instant second = (Instant) rows.get(1).get("date");
        assertThat(first).isAfterOrEqualTo(second);
        Instant newest = jdbc.sql("SELECT max(\"createdAt\") FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ?").params(demoOrg, product)
                .query((rs, n) -> rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)).single();
        assertThat(first).isEqualTo(newest);
        Map<String, Object> full = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> registers.register("all", q, InventoryRegisterService.ROW_CAP));
        assertThat((List<?>) full.get("rows")).hasSize(6);
        assertThat(full.get("truncated")).isEqualTo(false);
    }
}
