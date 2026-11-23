package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.InventoryReportsService;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R5a (inventory review 2026-11-22): report date bounds, batched includes, the reconciliation tolerance, the
 * stock-health age rule, the item-movement closing value of a past period, cost fields behind
 * {@code inventory_valuation:read}, request size limits and the bounded stock-document lists. One isolated
 * organisation per class.
 */
class InventoryReportsHardeningIT extends InventoryAuditSupport {

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String today() {
        return asOrg(() -> InventoryReportsService.localIso(Instant.now()));
    }

    /** A quant inserted with history: one ledger row per {@code (type, qty, value, daysAgo)}, oldest first. */
    void history(String productId, String loc, Object[]... rows) {
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        for (Object[] r : rows) {
            BigDecimal qty = dec(r[1]);
            BigDecimal v = dec(r[2]);
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("organizationId", org);
            cols.put("ledgerCode", "R5A-" + sfx());
            cols.put("productId", productId);
            cols.put("locationId", loc);
            cols.put("type", r[0]);
            cols.put("qtyBefore", balance);
            cols.put("quantityChange", qty);
            cols.put("balanceAfter", balance.add(qty));
            cols.put("unitCost", qty.signum() == 0 ? BigDecimal.ZERO : v.divide(qty.abs(), 6,
                    java.math.RoundingMode.HALF_UP));
            cols.put("totalValue", v);
            cols.put("referenceType", "r5a_history");
            cols.put("createdAt", Timestamp.from(Instant.now().minus(Duration.ofDays(((Number) r[3]).longValue()))));
            insert("InventoryLedger", cols);
            balance = balance.add(qty);
            value = qty.signum() >= 0 ? value.add(v) : value.subtract(v);
        }
        BigDecimal avg = balance.signum() > 0 ? value.divide(balance, 6, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        insert("StockItem", Map.of("organizationId", org, "productId", productId, "variantKey", "", "locationId", loc,
                "quantity", balance, "runningAverageCost", avg, "updatedAt", now()));
    }

    String readerToken(List<String> permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, permissions, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(role, org, "Reader " + sfx(), permissions.toArray(String[]::new)).update();
        String email = "reader-" + sfx() + "@audit.test";
        createUser(org, email, "Reader@12345", role);
        return login(orgCode, email, "Reader@12345");
    }

    @Test
    @DisplayName("ledger and movements: a date-only end is the whole local day; an ISO instant is used as given")
    void dateOnlyEndIsInclusive() {
        String p = product("DAY");
        receive(p, 4, 5);
        String day = today();
        JsonNode ledger = okA(200, "GET", "/inventory/ledger?productId=" + p + "&dateFrom=" + day + "&dateTo=" + day,
                null);
        assertThat(ledger.get("meta").get("total").asLong()).isEqualTo(1);
        String earlier = Instant.now().minus(Duration.ofHours(1)).toString();
        assertThat(okA(200, "GET", "/inventory/ledger?productId=" + p + "&dateTo=" + earlier, null).get("meta")
                .get("total").asLong()).isZero();
        JsonNode moves = okA(200, "GET", "/inventory/reports/movements?start=" + day + "&end=" + day, null);
        assertThat(moves.toString()).contains(p);
        assertThat(okA(200, "GET", "/inventory/reports/movements?end=" + earlier, null).toString()).doesNotContain(p);
        assertThat(api("GET", "/inventory/ledger?dateTo=not-a-date", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("ledger and item detail: includes keep their shape (read with one query per relation)")
    void batchedIncludesKeepShape() {
        String lot = product("LOT", Map.of("batchTracking", true));
        Map<String, Object> line = item(lot, 6, 2);
        line.put("batchNumber", "B-" + sfx());
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(line)));
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(lot, 1, null))));

        JsonNode ledger = okA(200, "GET", "/inventory/ledger?productId=" + lot, null).get("data");
        assertThat(ledger).hasSize(2);
        for (JsonNode r : ledger) {
            assertThat(r.get("product").get("id").asString()).isEqualTo(lot);
            assertThat(r.get("product").has("code")).isTrue();
            assertThat(r.get("variant").isNull()).isTrue();
            assertThat(r.get("location").get("id").asString()).isEqualTo(main);
            assertThat(r.get("batch").get("batchNumber").asString()).startsWith("B-");
            assertThat(r.get("batch").has("expiryDate")).isTrue();
        }

        JsonNode detail = okA(200, "GET", "/inventory/items/" + lot, null);
        assertThat(detail.get("items").get(0).get("location").get("type").asString()).isEqualTo("warehouse");
        JsonNode recent = detail.get("recentLedger").get(0);
        assertThat(recent.get("location").has("id")).isFalse();
        assertThat(recent.get("location").get("code").asString()).isEqualTo("MAIN");
        assertThat(recent.get("batch").has("id")).isFalse();
        assertThat(recent.get("batch").get("batchNumber").asString()).startsWith("B-");
        assertThat(detail.get("batches").get(0).get("location").get("code").asString()).isEqualTo("MAIN");
    }

    @Test
    @DisplayName("reconciliation: a non-numeric or negative tolerance is a 400, not a 500")
    void reconciliationTolerance() {
        for (String bad : List.of("abc", "-1", "Infinity")) {
            Res r = api("GET", "/inventory/reports/reconciliation?tolerance=" + bad, null);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.body().get("message").asString()).isEqualTo("tolerance must be a non-negative number");
        }
        assertThat(okA(200, "GET", "/inventory/reports/reconciliation?tolerance=0.5", null).get("summary")
                .get("tolerance").asString()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("stock health: never consumed = aged from the last receipt; a transfer in does not make old stock new")
    void stockHealthAge() {
        String fresh = product("FRESH");
        history(fresh, main, new Object[] {"receipt", 5, 25, 10});
        String old = product("OLD");
        history(old, main, new Object[] {"receipt", 5, 25, 200});
        String moved = product("MOVED");
        history(moved, main, new Object[] {"receipt", 5, 25, 200}, new Object[] {"transfer_in", 2, 10, 1});
        String transferred = product("TRANSFERRED");
        history(transferred, alt, new Object[] {"transfer_in", 3, 15, 10});

        Map<String, String> status = new LinkedHashMap<>();
        for (JsonNode r : okA(200, "GET", "/inventory/reports/stock-health", null).get("rows")) {
            status.put(r.get("productId").asString() + "@" + r.get("locationId").asString(), r.get("status").asString());
        }
        assertThat(status).containsEntry(fresh + "@" + main, "active").containsEntry(old + "@" + main, "dead")
                .containsEntry(moved + "@" + main, "dead").containsEntry(transferred + "@" + alt, "active");
    }

    @Test
    @DisplayName("item movements: the closing value of a past period comes from the ledger, not today's average cost")
    void closingValueFromLedger() {
        String p = product("CLOSE");
        history(p, main, new Object[] {"receipt", 10, 50, 10});
        receive(p, 10, 15); // average now 10 (was 5)
        String past = asOrg(() -> InventoryReportsService.localIso(Instant.now().minus(Duration.ofDays(5))));
        JsonNode row = rowOf(okA(200, "GET", "/inventory/reports/item-movements?productId=" + p + "&end=" + past,
                null), p);
        assertThat(num(row.get("closingQty"))).isEqualByComparingTo("10");
        assertThat(num(row.get("closingValue"))).isEqualByComparingTo("50");
        JsonNode now = rowOf(okA(200, "GET", "/inventory/reports/item-movements?productId=" + p, null), p);
        assertThat(num(now.get("closingValue"))).isEqualByComparingTo("200");
    }

    static JsonNode rowOf(JsonNode report, String productId) {
        for (JsonNode r : report.get("data")) {
            if (productId.equals(r.get("productId").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + productId + " in " + report);
    }

    @Test
    @DisplayName("valuation and stock health: unit costs and values need inventory_valuation:read (null otherwise)")
    void costFieldsNeedValuationPermission() {
        String p = product("COST");
        receive(p, 3, 7);
        String reader = readerToken(List.of(Permissions.INVENTORY__READ));
        Res valuation = call("GET", "/inventory/reports/valuation", null, reader);
        assertThat(valuation.status()).isEqualTo(200);
        JsonNode row = rowOf(valuation.body(), p);
        assertThat(num(row.get("quantity"))).isEqualByComparingTo("3");
        assertThat(row.get("avgCost").isNull()).isTrue();
        assertThat(row.get("value").isNull()).isTrue();
        assertThat(row.get("locations").get(0).get("value").isNull()).isTrue();
        assertThat(valuation.body().get("totals").get("value").isNull()).isTrue();
        assertThat(valuation.body().get("byCategory").get(0).get("value").isNull()).isTrue();

        Res health = call("GET", "/inventory/reports/stock-health", null, reader);
        assertThat(health.status()).isEqualTo(200);
        for (JsonNode r : health.body().get("rows")) {
            assertThat(r.get("unitCost").isNull()).isTrue();
            assertThat(r.get("value").isNull()).isTrue();
            assertThat(r.get("consumedValue").isNull()).isTrue();
        }
        assertThat(health.body().get("summary").get("totalValue").isNull()).isTrue();

        String costReader = readerToken(List.of(Permissions.INVENTORY__READ, Permissions.INVENTORY_VALUATION__READ));
        JsonNode full = rowOf(call("GET", "/inventory/reports/valuation", null, costReader).body(), p);
        assertThat(num(full.get("value"))).isEqualByComparingTo("21");
        assertThat(num(full.get("avgCost"))).isEqualByComparingTo("7");
        assertThat(call("GET", "/inventory/reports/stock-health", null, costReader).body().get("summary")
                .get("totalValue").isNull()).isFalse();
    }

    @Test
    @DisplayName("validation: stock-out quantity must be positive; line lists and free text have caps")
    void requestLimits() {
        String p = product("VAL");
        Map<String, Object> zero = new LinkedHashMap<>(Map.of("locationId", main, "responsibleById", auditUserId,
                "approvedById", auditUserId, "items", List.of(Map.of("productId", p, "qty", 0))));
        Res r = api("POST", "/inventory/stock-outs", zero);
        assertThat(r.status()).isEqualTo(400);
        assertThat(validationMessages(r)).anySatisfy(m -> assertThat(m).contains("must be a positive number"));

        Map<String, Object> longReason = new LinkedHashMap<>(zero);
        longReason.put("items", List.of(Map.of("productId", p, "qty", 1)));
        longReason.put("reason", "x".repeat(1001));
        assertThat(validationMessages(api("POST", "/inventory/stock-outs", longReason)))
                .anySatisfy(m -> assertThat(m).contains("reason must be shorter than or equal to 1000 characters"));

        List<Map<String, Object>> lines = new ArrayList<>(Collections.nCopies(1001, Map.of("productId", p,
                "qtyActual", 1)));
        Res many = api("POST", "/inventory/adjustments", Map.of("locationId", main, "responsibleById", auditUserId,
                "approvedById", auditUserId, "items", lines));
        assertThat(many.status()).isEqualTo(400);
        assertThat(validationMessages(many)).anySatisfy(m -> assertThat(m)
                .contains("items must contain no more than 1000 elements"));

        Res alerts = api("POST", "/inventory/replenishment/requisition", Map.of("alertIds",
                Collections.nCopies(501, "x")));
        assertThat(alerts.status()).isEqualTo(400);
        assertThat(validationMessages(alerts)).anySatisfy(m -> assertThat(m)
                .contains("alertIds must contain no more than 500 elements"));
        Res lead = api("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", main, "minQty", 1,
                "leadTimeDays", 99999));
        assertThat(validationMessages(lead)).anySatisfy(m -> assertThat(m)
                .contains("leadTimeDays must not be greater than 3650"));
    }

    @Test
    @DisplayName("stock-document lists: a plain array of at most `limit` (default 500) documents, with a date window")
    void boundedDocumentLists() {
        String p = product("LIST");
        receive(p, 10, 1);
        for (int i = 0; i < 3; i++) {
            okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                    "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                            "qtyRequested", 1))));
        }
        JsonNode all = okA(200, "GET", "/inventory/transfers", null);
        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isGreaterThanOrEqualTo(3);
        assertThat(okA(200, "GET", "/inventory/transfers?limit=2", null)).hasSize(2);
        String tomorrow = asOrg(() -> InventoryReportsService.localIso(Instant.now().plus(Duration.ofDays(1))));
        assertThat(okA(200, "GET", "/inventory/transfers?dateFrom=" + tomorrow, null)).isEmpty();
        assertThat(okA(200, "GET", "/inventory/transfers?dateTo=" + today(), null).size()).isGreaterThanOrEqualTo(3);
        assertThat(api("GET", "/inventory/transfers?limit=abc", null).status()).isEqualTo(400);
        assertThat(okA(200, "GET", "/inventory/stock-outs?limit=1", null).isArray()).isTrue();
        assertThat(okA(200, "GET", "/inventory/adjustments?limit=1", null).isArray()).isTrue();
    }
}
