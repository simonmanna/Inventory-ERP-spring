package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.StockMove;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Engine and count remediations of the Nest audits: P1-02 skip_expired lots, P1-03 serials on base quantity, P2-03
 * count modes and P2-04 stock health ({@code inv-audit3-remediation.spec.ts}); INV-008 attribution and N5 count resume
 * ({@code inv-p1-integrity.spec.ts}); P1-1 FIFO valuation ({@code inv-readiness-remediation.spec.ts}).
 */
class InventoryAuditRemediationIT extends InventoryAuditSupport {

    static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    long exceptions(String productId, String kind) {
        return count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + (kind == null ? "" : " AND kind = '" + kind + "'"), org, productId);
    }

    /** A FIFO lot-tracked product under skip_expired holding one expired lot of 5 @ 40. */
    String expiredBatchProduct(String tag, Map<String, Object> extra) {
        Map<String, Object> cols = new java.util.LinkedHashMap<>(Map.of("batchTracking", true, "costingMethod", "FIFO",
                "costPrice", 100));
        cols.putAll(extra);
        String p = product(tag, cols);
        productSetting(p, "inventory.expiredStockPolicy", "\"skip_expired\"");
        receive(p, main, 5, 40, m -> m.batch("EXP-" + uid()).expiry(Instant.now().minus(3, ChronoUnit.DAYS).toString()));
        return p;
    }

    // ------------------------------------------------------------------ P1-02 skip_expired

    @Test
    @DisplayName("P1-02 permissive: consumes the expired lot, conserves lots and raises an exception")
    void skipExpiredPermissive() {
        String p = expiredBatchProduct("EXP-PERM", Map.of());
        issue(p, main, 1, null);
        assertThat(onHand(p, main)).isEqualByComparingTo("4");
        assertThat(lotSum(p, main)).isEqualByComparingTo("4");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("4");
        BigDecimal qty = jdbc.sql("SELECT quantity FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND kind = 'expired_lot_consumed'").params(org, p).query(BigDecimal.class).single();
        assertThat(qty).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("P1-02: prefers a valid lot over an expired one")
    void skipExpiredPrefersValid() {
        String p = expiredBatchProduct("EXP-MIX", Map.of());
        String valid = "OK-" + uid();
        receive(p, main, 3, 50, m -> m.batch(valid).expiry(Instant.now().plus(30, ChronoUnit.DAYS).toString()));
        issue(p, main, 2, null);
        assertThat(jdbc.sql("SELECT quantity FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"batchNumber\" = ?").params(org, p, valid).query(BigDecimal.class).single())
                .isEqualByComparingTo("1");
        assertThat(onHand(p, main)).isEqualByComparingTo("6");
        assertThat(lotSum(p, main)).isEqualByComparingTo("6");
        assertThat(exceptions(p, null)).isZero();
    }

    @Test
    @DisplayName("P1-02 strict (allowNegativeStock=false + block) and requireAvailable refuse without touching stock")
    void skipExpiredStrict() {
        String p = expiredBatchProduct("EXP-STRICT", Map.of("stockPolicy", "block"));
        productSetting(p, "inventory.allowNegativeStock", "false");
        assertThatThrownBy(() -> issue(p, main, 1, null)).hasMessageContaining("Insufficient stock");
        String q = expiredBatchProduct("EXP-REQ", Map.of());
        assertThatThrownBy(() -> issue(q, main, 1, m -> m.requireAvailable(true)))
                .hasMessageContaining("Insufficient stock");
        for (String id : List.of(p, q)) {
            assertThat(onHand(id, main)).isEqualByComparingTo("5");
            assertThat(lotSum(id, main)).isEqualByComparingTo("5");
        }
    }

    @Test
    @DisplayName("P1-02: beyond all lots, the unlayered overflow is the only negative (quant = Σ lots − overflow)")
    void skipExpiredOverflow() {
        String p = expiredBatchProduct("EXP-NEG", Map.of());
        issue(p, main, 7, null);
        assertThat(lotSum(p, main)).isEqualByComparingTo("0");
        assertThat(onHand(p, main)).isEqualByComparingTo("-2");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("-2");
    }

    // ------------------------------------------------------------------ P1-03 serials

    /** Piece (reference) and Case of 12 in a fresh category; returns {piece, case}. */
    String[] uomPair(String tag) {
        String cat = insert("UomCategory", Map.of("organizationId", org, "name", "A3-" + tag + "-" + uid(),
                "updatedAt", now()));
        String piece = insert("UnitOfMeasure", Map.of("organizationId", org, "categoryId", cat, "code", "A3-" + tag
                + "-P-" + uid(), "name", "Piece", "factor", 1, "isBase", true, "updatedAt", now()));
        String box = insert("UnitOfMeasure", Map.of("organizationId", org, "categoryId", cat, "code", "A3-" + tag
                + "-C-" + uid(), "name", "Case of 12", "factor", 12, "ratio", 12, "uomType", "bigger",
                "updatedAt", now()));
        jdbc.sql("UPDATE \"UomCategory\" SET \"referenceUomId\" = ? WHERE id = ?").params(piece, cat).update();
        return new String[] {piece, box};
    }

    long inStockSerials(String productId) {
        return count("SELECT count(*) FROM \"InventorySerial\" WHERE \"productId\" = ? AND status = 'in_stock'",
                productId);
    }

    @Test
    @DisplayName("P1-03: a case of 12 needs 12 serials to receive and to sell, and restocks by serial")
    void serialsOnBaseQuantity() {
        String[] uom = uomPair("SER");
        String p = product("SER-CASE", Map.of("serialTracking", true, "uomId", uom[0], "purchaseUomId", uom[1],
                "costPrice", 100));
        productSetting(p, "inventory.serialPolicy", "\"required\"");
        List<String> serials = IntStream.range(0, 12).mapToObj(i -> "A3SN-" + uid() + "-" + i).toList();
        assertThatThrownBy(() -> receive(p, main, 1, 1200, m -> m.uom(uom[1]).serials(List.of(serials.getFirst()))))
                .hasMessageContaining("needs exactly 12 serial");
        receive(p, main, 1, 1200, m -> m.uom(uom[1]).serials(serials));
        assertThat(onHand(p, main)).isEqualByComparingTo("12");
        assertThat(inStockSerials(p)).isEqualTo(12);

        assertThatThrownBy(() -> issue(p, main, 1, m -> m.uom(uom[1]).serials(List.of(serials.getFirst()))))
                .hasMessageContaining("needs 12 serial");
        issue(p, main, 1, m -> m.uom(uom[1]).serials(serials));
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        List<Object> outRows = jdbc.sql("SELECT \"serialId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"quantityChange\" < 0").params(org, p).query((rs, n) -> rs.getObject(1))
                .list();
        assertThat(outRows).hasSize(12).doesNotContainNull();

        asOrgRun(() -> stock.receiveReturn(StockMove.of(p, main, new BigDecimal("12")).serials(serials)
                .source("audit_return", uid())));
        assertThat(inStockSerials(p)).isEqualTo(12);
        assertThat(onHand(p, main)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("P1-03: fractional serialised quantities fail at the engine boundary (any policy)")
    void fractionalSerialQuantities() {
        String p = product("SER-FRAC", Map.of("serialTracking", true));
        receive(p, main, 2, 10, m -> m.serials(List.of("F-" + uid(), "F-" + uid())));
        assertThatThrownBy(() -> issue(p, main, "0.5", null)).hasMessageContaining("whole number");
        assertThatThrownBy(() -> receive(p, main, "1.5", 10, null)).hasMessageContaining("whole number");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("P1-03: the required policy never writes an un-serialised overflow row")
    void requiredPolicyNoOverflow() {
        String p = product("SER-SHORT", Map.of("serialTracking", true));
        productSetting(p, "inventory.serialPolicy", "\"required\"");
        String sn = "S-" + uid();
        receive(p, main, 1, 10, m -> m.serials(List.of(sn)));
        assertThatThrownBy(() -> issue(p, main, 2, m -> m.serials(List.of(sn, sn))))
                .hasMessageContaining("Duplicate serial");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"serialId\" IS NULL AND"
                + " \"quantityChange\" < 0", p)).isZero();
    }

    // ------------------------------------------------------------------ P2-03 count modes

    @Test
    @DisplayName("P2-03: a blind spot count hides system figures until reviewed, and posts only its scope")
    void blindSpotCount() {
        String loc = location("A3-CNT-" + uid(), "Blind count", "warehouse");
        String a = product("CNT-A", Map.of("costPrice", 100));
        String b = product("CNT-B", Map.of("costPrice", 100));
        receive(a, loc, 10, 100, null);
        receive(b, loc, 7, 100, null);
        Res noScope = api("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot"));
        assertThat(noScope.body().get("message").asString()).contains("at least one product");
        JsonNode session = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "blind", true, "scopeProductIds", List.of(a)));
        String id = session.get("id").asString();
        assertThat(session.get("lines")).hasSize(1);
        assertThat(session.get("systemHidden").asBoolean()).isTrue();
        assertThat(session.get("lines").get(0).get("systemQty").isNull()).isTrue();
        JsonNode saved = okA(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId",
                session.get("lines").get(0).get("id").asString(), "countedQty", 9, "reason", "breakage"))));
        assertThat(saved.get("lines").get(0).get("variance").isNull()).isTrue();
        JsonNode review = okA(200, "GET", "/inventory/counts/" + id + "/review", null);
        assertThat(num(review.get("lines").get(0).get("systemQty"))).isEqualByComparingTo("10");
        assertThat(num(review.get("lines").get(0).get("variance"))).isEqualByComparingTo("-1");
        okA(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        assertThat(onHand(a, loc)).isEqualByComparingTo("9");
        assertThat(onHand(b, loc)).isEqualByComparingTo("7");
        assertThat(okA(200, "GET", "/inventory/counts/" + id, null).get("systemHidden").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("P2-03: a cycle count by category scopes the sheet")
    void cycleCountByCategory() {
        String loc = location("A3-CYC-" + uid(), "Cycle count", "warehouse");
        String cat = insert("ProductCategory", Map.of("organizationId", org, "name", "A3 Dairy " + uid(),
                "updatedAt", now()));
        String inCat = product("CYC-IN", Map.of("categoryId", cat));
        product("CYC-OUT");
        Res noScope = api("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "cycle"));
        assertThat(noScope.body().get("message").asString()).contains("needs a scope");
        JsonNode s = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "cycle",
                "scopeCategoryIds", List.of(cat)));
        List<String> productIds = new ArrayList<>();
        s.get("lines").forEach(l -> productIds.add(l.get("productId").asString()));
        assertThat(productIds).as(s.toString()).containsExactly(inCat);
        okA(201, "POST", "/inventory/counts/" + s.get("id").asString() + "/cancel", null);
    }

    // ------------------------------------------------------------------ P2-04 stock health

    @Test
    @DisplayName("P2-04: stock health classifies active vs dead stock and ages the on-hand layers")
    void stockHealth() {
        String loc = location("A3-HL-" + uid(), "Health store", "warehouse");
        String active = product("HL-ACTIVE", Map.of("costPrice", 100));
        String dead = product("HL-DEAD", Map.of("costPrice", 100));
        receive(active, loc, 10, 100, null);
        receive(dead, loc, 4, 100, null);
        issue(active, loc, 5, null);
        JsonNode res = okA(200, "GET", "/inventory/reports/stock-health?locationId=" + loc + "&slowDays=30&deadDays=60",
                null);
        JsonNode rowA = row(res, active);
        JsonNode rowD = row(res, dead);
        assertThat(rowA.get("status").asString()).isEqualTo("active");
        assertThat(num(rowA.get("onHand"))).isEqualByComparingTo("5");
        assertThat(num(rowA.get("aging").get("d0_30"))).isEqualByComparingTo("5");
        assertThat(num(rowA.get("consumedQty"))).isEqualByComparingTo("5");
        assertThat(num(rowA.get("turnover")).signum()).isPositive();
        // Hardening R5a (inventory deviation 34): never consumed is aged from its receipt, so stock received today is
        // active; dead / slow ageing of old receipts is covered by InventoryReportsHardeningIT.stockHealthAge.
        assertThat(rowD.get("status").asString()).isEqualTo("active");
        assertThat(num(rowD.get("value"))).isEqualByComparingTo("400");
        assertThat(num(rowD.get("consumedQty"))).isZero();
        JsonNode onlyActive = okA(200, "GET", "/inventory/reports/stock-health?locationId=" + loc + "&status=active",
                null);
        assertThat(onlyActive.get("rows")).hasSize(2);
        JsonNode onlyDead = okA(200, "GET", "/inventory/reports/stock-health?locationId=" + loc + "&status=dead", null);
        assertThat(onlyDead.get("rows")).isEmpty();
    }

    static JsonNode row(JsonNode res, String productId) {
        for (JsonNode r : res.get("rows")) {
            if (productId.equals(r.get("productId").asString())) {
                return r;
            }
        }
        throw new AssertionError("no stock health row for " + productId);
    }

    // ------------------------------------------------------------------ inv-p1-integrity.spec.ts

    @Test
    @DisplayName("INV-008: attribution to an unknown user is refused")
    void unknownAttribution() {
        String p = product("P1-ATTR");
        Res r = api("POST", "/inventory/stock-outs", Map.of("locationId", main, "responsibleById", "not-a-user",
                "approvedById", auditUserId, "items", List.of(Map.of("productId", p, "qty", 1))));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("active user");
    }

    @Test
    @DisplayName("N5: starting a count resumes a draft that already has counts; restart opens a new one")
    void countResume() {
        String loc = location("P1-CNT-" + uid(), "Resume count", "warehouse");
        String p = product("P1-COUNT", Map.of("costPrice", 10));
        receive(p, loc, 4, 10, null);
        JsonNode first = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "closing"));
        String lineId = null;
        for (JsonNode l : first.get("lines")) {
            if (p.equals(l.get("productId").asString())) {
                lineId = l.get("id").asString();
            }
        }
        okA(200, "PATCH", "/inventory/counts/" + first.get("id").asString() + "/draft", Map.of("lines",
                List.of(Map.of("lineId", lineId, "countedQty", 4))));
        Res again = api("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "closing"));
        assertThat(again.status()).isIn(200, 201);
        assertThat(again.body().get("id").asString()).isEqualTo(first.get("id").asString());
        JsonNode fresh = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "closing",
                "restart", true));
        assertThat(fresh.get("id").asString()).isNotEqualTo(first.get("id").asString());
    }

    // ------------------------------------------------------------------ inv-readiness-remediation.spec.ts

    @Test
    @DisplayName("P1-1: FIFO valuation is the remaining lot cost, not quant × running average")
    void fifoValuationByLot() {
        String p = product("RDY-FIFO", Map.of("costingMethod", "FIFO", "batchTracking", true, "pickingStrategy", "FIFO",
                "costPrice", 5));
        receive(p, main, 10, 5, m -> m.batch("RDY-LOT-A-" + uid()));
        receive(p, main, 10, 9, m -> m.batch("RDY-LOT-B-" + uid()));
        issue(p, main, 12, m -> m.strategy("FIFO"));
        JsonNode report = okA(200, "GET", "/reports/inventory/valuation", null);
        JsonNode item = null;
        for (JsonNode i : report.get("items")) {
            if (p.equals(i.get("productId").asString())) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(num(item.get("onHandQty"))).isEqualByComparingTo("8");
        assertThat(num(item.get("totalValue"))).isEqualByComparingTo("72");
    }
}
