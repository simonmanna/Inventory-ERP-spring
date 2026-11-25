package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PLM reports (ADR-S086 §8): change analytics, phase-out / obsolete stock, stock by revision. */
class PlmReportsIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    static Map<String, Object> line(String productId, int qty) {
        return Map.of("componentProductId", productId, "quantity", qty);
    }

    JsonNode activeBom(String output, List<Map<String, Object>> lines) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Recipe " + suffix(),
                "outputProductId", output, "outputQuantity", 1, "lines", lines));
        return ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
    }

    void approve(String ecoId) {
        String review = null;
        for (JsonNode s : ok(200, "GET", PLM + "/changes/" + ecoId, null).get("stages")) {
            if (s.get("requiresApproval").asBoolean()) {
                review = s.get("id").asString();
                break;
            }
        }
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
    }

    /** A BOM change on {@code bomId} doubling {@code component}, approved and applied; returns the new BOM id. */
    String appliedChange(String bomId, String component) {
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "stockDisposition",
                "none"));
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines",
                List.of(line(component, 2))));
        approve(eco.get("id").asString());
        ok(201, "POST", PLM + "/changes/" + eco.get("id").asString() + "/apply", null);
        return eco.get("newBomId").asString();
    }

    void makeOne(String bomId) {
        JsonNode order = started(bomId);
        ok(201, "POST", "/manufacturing/production-orders/" + order.get("id").asString() + "/complete",
                Map.of("qtyProduced", 1));
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("change analytics: totals for the period, groups, guards")
    void changeReport() {
        String flour = product("RFLOUR");
        String loaf = product("RLOAF");
        JsonNode v1 = activeBom(loaf, List.of(line(flour, 1)));
        register(loaf, "released");
        appliedChange(v1.get("id").asString(), flour);

        JsonNode r = ok(200, "GET", PLM + "/reports/changes", null);
        assertThat(r.get("groupBy").asString()).isEqualTo("month");
        assertThat(r.get("created").get("created").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("applied").get("applied").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("applied").get("avgCycleDays").isNull()).isFalse();
        assertThat(r.get("applied").get("firstPassRate").isNull()).isFalse();
        assertThat(r.get("byType").get("bom").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("costVisible").asBoolean()).isTrue();
        String month = r.get("to").asString().substring(0, 7);
        boolean found = false;
        for (JsonNode g : r.get("rows")) {
            found |= month.equals(g.get("key").asString()) && g.get("applied").asLong() >= 1;
        }
        assertThat(found).isTrue();
        assertThat(ok(200, "GET", PLM + "/reports/changes?groupBy=type", null).get("rows").size())
                .isGreaterThanOrEqualTo(1);
        assertThat(ok(200, "GET", PLM + "/reports/changes?groupBy=owner&from=2020-01-01", null).get("from")
                .asString()).isEqualTo("2020-01-01");

        assertThat(msg(as("GET", PLM + "/reports/changes?groupBy=colour", null)))
                .isEqualTo("groupBy must be one of month, type, stage, owner, priority.");
        assertThat(msg(as("GET", PLM + "/reports/changes?from=2026-02-01&to=2026-01-01", null)))
                .isEqualTo("from must not be after to.");
        assertThat(msg(as("GET", PLM + "/reports/changes?from=yesterday", null))).isEqualTo("Invalid date yesterday.");
    }

    @Test
    @DisplayName("obsolete stock: on hand and its value, purchase orders still open, BOMs still using a phase-out"
            + " product, replacement")
    void obsoleteStock() {
        String part = product("ROLD");
        String successor = product("RNEW");
        stockIn(warehouse, part, 10, 3);
        String supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "RS-" + suffix(), "Report supplier "
                        + suffix()).update();
        // Ordered while still in design, then retired.
        ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", part,
                        "description", "Old part", "quantity", 4, "unitPrice", 3))));
        String item = register(part, "design");
        ok(201, "POST", PLM + "/items/" + item + "/transition", Map.of("toState", "obsolete", "replacementProductId",
                successor));

        JsonNode r = ok(200, "GET", PLM + "/reports/obsolete-stock?state=obsolete", null);
        JsonNode row = null;
        for (JsonNode x : r.get("rows")) {
            if (part.equals(x.get("productId").asString())) {
                row = x;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("lifecycleState").asString()).isEqualTo("obsolete");
        assertThat(dec(row.get("onHand"))).isEqualByComparingTo("10");
        assertThat(dec(row.get("value"))).isEqualByComparingTo("30");
        assertThat(dec(row.get("openPurchaseQty"))).isEqualByComparingTo("4");
        assertThat(row.get("openPurchaseOrders").asLong()).isEqualTo(1);
        assertThat(row.get("usedInActiveBoms").asLong()).isZero(); // obsolete needs it out of active BOMs first
        assertThat(row.get("replacementProductId").asString()).isEqualTo(successor);
        assertThat(row.get("since").isNull()).isFalse();
        assertThat(r.get("totals").get("stillPurchased").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("byState").has("phase_out")).isFalse();

        // Being phased out, still a component of an active BOM.
        String fading = product("RFADE");
        activeBom(fading, List.of(line(successor, 1)));
        String assembly = product("RASM");
        activeBom(assembly, List.of(line(fading, 1)));
        String fadingItem = register(fading, "released");
        ok(201, "POST", PLM + "/items/" + fadingItem + "/transition", Map.of("toState", "phase_out"));
        JsonNode both = ok(200, "GET", PLM + "/reports/obsolete-stock", null);
        JsonNode fadingRow = null;
        for (JsonNode x : both.get("rows")) {
            if (fading.equals(x.get("productId").asString())) {
                fadingRow = x;
            }
        }
        assertThat(fadingRow).isNotNull();
        assertThat(fadingRow.get("usedInActiveBoms").asLong()).isEqualTo(1);
        assertThat(both.get("byState").get("phase_out").get("products").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(both.get("totals").get("usedInActiveBoms").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(msg(as("GET", PLM + "/reports/obsolete-stock?state=retired", null)))
                .isEqualTo("state must be phase_out or obsolete.");
    }

    @Test
    @DisplayName("stock by revision: what was built on A and on B, and stock of unknown revision")
    void stockByRevision() {
        String flour = product("VFLOUR");
        String bun = product("VBUN");
        stockIn(bakery, flour, 20, 2);
        JsonNode v1 = activeBom(bun, List.of(line(flour, 1)));
        register(bun, "released");
        makeOne(v1.get("id").asString());
        String v2 = appliedChange(v1.get("id").asString(), flour);
        makeOne(v2);
        stockIn(warehouse, bun, 3, 5);
        drainOutboxFully();

        JsonNode r = ok(200, "GET", PLM + "/reports/stock-by-revision?productId=" + bun, null);
        JsonNode row = r.get("rows").get(0);
        assertThat(row.get("currentRevision").asString()).isEqualTo("B");
        assertThat(dec(row.get("onHand"))).isEqualByComparingTo("5");
        assertThat(row.get("ordersBuilt").asLong()).isEqualTo(2);
        Map<String, BigDecimal> byRev = new java.util.HashMap<>();
        row.get("revisions").forEach(x -> byRev.put(x.get("revision").asString(), dec(x.get("quantity"))));
        assertThat(byRev.get("A")).isEqualByComparingTo("1");
        assertThat(byRev.get("B")).isEqualByComparingTo("1");
        assertThat(dec(row.get("unknown"))).isEqualByComparingTo("3");
        assertThat(ok(200, "GET", PLM + "/reports/stock-by-revision", null).get("rows").size())
                .isGreaterThanOrEqualTo(1);
        assertThat(as("GET", PLM + "/reports/stock-by-revision?productId=" + flour, null).status()).isEqualTo(404);
    }
}
