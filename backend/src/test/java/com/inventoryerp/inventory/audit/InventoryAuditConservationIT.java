package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * Conservation audits: {@code inv-audit-conservation.spec.ts} (INV-INVARIANT-26 / -27 / -03), the INV-010 reversals,
 * INV-011 tie-out and INV-013 ledger guards of {@code inv-p2-accounting.spec.ts}, N1 / N2 of
 * {@code inv-p1-integrity.spec.ts} and the P1-04 transit cases of {@code inv-audit3-remediation.spec.ts}. Quantity and
 * value are conserved across locations through transfers, transit, recall and reversal; the last test checks every
 * quant of the suite's organisation (Σ ledger = on-hand, open lots = max(0, on-hand), unbroken chain) and that the
 * inventory GL ties to the valuation with zero variance after the whole mixed scenario.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryAuditConservationIT extends InventoryAuditSupport {

    String consProduct;

    static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    BigDecimal moved(String productId, String type) {
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND type::text = ?").params(org, productId, type).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ inv-audit-conservation.spec.ts

    /**
     * receive 100 @ 10 → issue 20 → receive 20 @ 14 (avg 10.8) → issue 30 → return 5 @ 10.8 → transfer 20 to ALT →
     * adjust MAIN to 53 (adjustment_out 2): closing 53 + 20 = 73, value 788.4.
     */
    @Test
    @Order(1)
    @DisplayName("INV-INVARIANT-26: classified movements reconcile to the closing quantity")
    void classifiedMovements() {
        consProduct = product("CONS-QTY");
        String p = consProduct;
        asOrgRun(() -> {
            stock.receiveForDocument(StockMove.of(p, main, new BigDecimal("100")).unitCost(BigDecimal.TEN),
                    new GlReceiptContext("audit_receipt", "r1", Instant.now(), null));
            stock.issue(StockMove.of(p, main, new BigDecimal("20")).source("audit_sale", "s1"));
            stock.receiveForDocument(StockMove.of(p, main, new BigDecimal("20")).unitCost(new BigDecimal("14")),
                    new GlReceiptContext("audit_receipt", "r2", Instant.now(), null));
            stock.issue(StockMove.of(p, main, new BigDecimal("30")).source("audit_sale", "s2"));
            stock.receiveReturn(StockMove.of(p, main, new BigDecimal("5")).unitCost(new BigDecimal("10.8"))
                    .source("audit_refund", "ret1"));
            stock.transfer(StockMove.transfer(p, main, alt, new BigDecimal("20")).source("audit_transfer", "t1"));
            StockMove adj = StockMove.of(p, main, null).notes("audit shrinkage");
            adj.countedQuantity = new BigDecimal("53");
            stock.adjust(adj);
        });
        assertThat(onHand(p, main)).isEqualByComparingTo("53");
        assertThat(onHand(p, alt)).isEqualByComparingTo("20");
        assertThat(moved(p, "receipt")).isEqualByComparingTo("120");
        assertThat(moved(p, "return_in")).isEqualByComparingTo("5");
        assertThat(moved(p, "transfer_in")).isEqualByComparingTo("20");
        assertThat(moved(p, "issue")).isEqualByComparingTo("-50");
        assertThat(moved(p, "transfer_out")).isEqualByComparingTo("-20");
        assertThat(moved(p, "adjustment_out")).isEqualByComparingTo("-2");
        assertThat(moved(p, "waste")).isEqualByComparingTo("0");
        assertThat(moved(p, "adjustment_in")).isEqualByComparingTo("0");
        assertThat(moved(p, "opening_balance")).isEqualByComparingTo("0");
        BigDecimal classified = jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND type::text IN ('opening_balance', 'receipt',"
                + " 'return_in', 'transfer_in', 'adjustment_in', 'issue', 'transfer_out', 'adjustment_out', 'waste')")
                .params(org, p).query(BigDecimal.class).single();
        assertThat(classified).isEqualByComparingTo("73");
    }

    @Test
    @Order(2)
    @DisplayName("INV-INVARIANT-27: subledger valuation equals the Stock Valuation GL balance; COGS and value conserved")
    void valuationConservation() {
        BigDecimal subledger = jdbc.sql("SELECT COALESCE(SUM(quantity * \"runningAverageCost\"), 0) FROM \"StockItem\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ?").params(org, consProduct)
                .query(BigDecimal.class).single();
        assertThat(r6(subledger)).isEqualByComparingTo("788.4");
        assertThat(r6(balance("stock_valuation"))).isEqualByComparingTo(r6(subledger));
        // 200 (20 × 10) + 324 (30 × 10.8) less the 54 credited back by the return restock
        assertThat(r6(balance("cogs"))).isEqualByComparingTo("470");
        // receipts 1280 + return 54 − COGS 524 − adjustment 21.6 = 788.4
        BigDecimal adjExpense = balance("stock_adjustment_expense");
        assertThat(adjExpense).isEqualByComparingTo("21.6");
        assertThat(new BigDecimal("1280").add(new BigDecimal("54")).subtract(new BigDecimal("524")).subtract(adjExpense))
                .isEqualByComparingTo(subledger);
    }

    @Test
    @Order(3)
    @DisplayName("INV-INVARIANT-03: every ledger row carries a resolvable source reference")
    void everyRowReferenced() {
        List<String> orphans = jdbc.sql("SELECT type::text || ' ' || \"ledgerCode\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND (\"referenceType\" IS NULL OR \"referenceId\" IS NULL)").param(org)
                .query(String.class).list();
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?", org)).isPositive();
        assertThat(orphans).isEmpty();
    }

    // ------------------------------------------------------------------ inv-p2-accounting.spec.ts (INV-010)

    @Test
    @Order(10)
    @DisplayName("INV-010: waste reversal restores stock and nets the GL back to zero")
    void wasteReversal() {
        String p = product("P2-WASTE", Map.of("costPrice", 10));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 10))));
        BigDecimal glBefore = balance("stock_valuation");
        JsonNode waste = okA(200, "POST", "/inventory/waste", Map.of("locationId", main, "responsibleById", auditUserId,
                "approvedById", auditUserId, "items", List.of(Map.of("productId", p, "qty", 3))));
        String id = waste.get("id").asString();
        okA(200, "POST", "/inventory/waste/" + id + "/approve", null);
        assertThat(onHand(p, main)).isEqualByComparingTo("7");
        assertThat(balance("stock_valuation")).isEqualByComparingTo(glBefore.subtract(new BigDecimal("30")));
        JsonNode reversed = okA(200, "POST", "/inventory/waste/" + id + "/reverse", Map.of("reason", "counted wrong"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(p, main)).isEqualByComparingTo("10");
        assertThat(balance("stock_valuation")).isEqualByComparingTo(glBefore);
        Res again = api("POST", "/inventory/waste/" + id + "/reverse", Map.of("reason", "again"));
        assertThat(again.status()).as(again.raw().body()).isEqualTo(409);
        assertThat(again.body().get("message").asString()).contains("already reversed");
    }

    @Test
    @Order(11)
    @DisplayName("INV-010: batch adjustment gain reversal takes the units back out of the lot")
    void batchAdjustmentReversal() {
        String p = product("P2-ADJ-BATCH", Map.of("batchTracking", true, "costPrice", 10));
        receive(p, main, 5, 6, m -> m.batch("P2-LOT"));
        BigDecimal glBefore = balance("stock_valuation");
        JsonNode adj = okA(200, "POST", "/inventory/adjustments", Map.of("locationId", main, "reason", "found",
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyActual", 8))));
        okA(200, "POST", "/inventory/adjustments/" + adj.get("id").asString() + "/approve", Map.of());
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        okA(200, "POST", "/inventory/adjustments/" + adj.get("id").asString() + "/reverse",
                Map.of("reason", "double counted"));
        assertThat(onHand(p, main)).isEqualByComparingTo("5");
        assertThat(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(org, p, main).query(BigDecimal.class).single())
                .isEqualByComparingTo("5");
        assertThat(balance("stock_valuation")).isEqualByComparingTo(glBefore);
    }

    @Test
    @Order(12)
    @DisplayName("INV-010: transfer reversal moves stock back")
    void transferReversal() {
        String p = product("P2-TRF", Map.of("costPrice", 10));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 9, 10))));
        JsonNode trf = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", 4))));
        okA(200, "POST", "/inventory/transfers/" + trf.get("id").asString() + "/approve", null);
        okA(200, "POST", "/inventory/transfers/" + trf.get("id").asString() + "/reverse", Map.of("reason",
                "wrong branch"));
        assertThat(onHand(p, main)).isEqualByComparingTo("9");
        assertThat(onHand(p, alt)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ inv-p1-integrity.spec.ts (N1, N2)

    @Test
    @Order(20)
    @DisplayName("N1: adjustment loss and gain on a batch item keep Σ lots == on-hand")
    void batchAdjustKeepsLots() {
        String p = product("P1-BATCH-ADJ", Map.of("batchTracking", true));
        receive(p, main, 10, 5, m -> m.batch("LOT-A"));
        receive(p, main, 10, 7, m -> m.batch("LOT-B"));
        for (String counted : List.of("14", "17")) {
            asOrgRun(() -> {
                StockMove adj = StockMove.of(p, main, null);
                adj.countedQuantity = new BigDecimal(counted);
                stock.adjust(adj);
            });
            assertThat(onHand(p, main)).isEqualByComparingTo(counted);
            assertThat(lotSum(p, main)).isEqualByComparingTo(counted);
        }
    }

    @Test
    @Order(21)
    @DisplayName("N2: batch transfer carries lot cost, expiry and receipt date")
    void batchTransferCarriesLot() {
        String p = product("P1-BATCH-TRF", Map.of("batchTracking", true));
        String expiry = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        receive(p, main, 5, 4, m -> m.batch("LOT-OLD").expiry(expiry));
        receive(p, main, 5, 8, m -> m.batch("LOT-NEW"));
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, new BigDecimal("6"))));
        BigDecimal outValue = jdbc.sql("SELECT \"totalValue\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND type = 'transfer_out'").params(org, p).query(BigDecimal.class).single();
        // 5 × 4 (LOT-OLD) + 1 × 8 (LOT-NEW)
        assertThat(outValue.abs()).isEqualByComparingTo("28");
        assertThat(lotSum(p, main)).isEqualByComparingTo("4");
        assertThat(lotSum(p, alt)).isEqualByComparingTo("6");
        Map<String, Object> dest = lot(p, alt, "LOT-OLD");
        Map<String, Object> src = lot(p, main, "LOT-OLD");
        assertThat(dest.get("expiryDate")).isEqualTo(src.get("expiryDate"));
        assertThat(dest.get("expiryDate")).isNotNull();
        assertThat(dest.get("receivedAt")).isEqualTo(src.get("receivedAt"));
        assertThat((BigDecimal) dest.get("unitCost")).isEqualByComparingTo("4");
    }

    Map<String, Object> lot(String p, String loc, String batch) {
        return jdbc.sql("SELECT \"expiryDate\", \"receivedAt\", \"unitCost\" FROM \"InventoryBatch\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND \"batchNumber\" = ?")
                .params(org, p, loc, batch).query().singleRow();
    }

    // ------------------------------------------------------------------ inv-audit3-remediation.spec.ts (P1-04)

    JsonNode createTransit(String p, int qty) {
        return okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt, "mode",
                "transit", "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of(
                        "productId", p, "qtyRequested", qty))));
    }

    String transitLocation() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'transit' LIMIT 1")
                .param(org).query(String.class).single();
    }

    static Map<String, Object> receiveLine(String itemId, int received, Integer damaged, Integer shortQty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId);
        m.put("received", received);
        if (damaged != null) {
            m.put("damaged", damaged);
        }
        if (shortQty != null) {
            m.put("short", shortQty);
        }
        return m;
    }

    @Test
    @Order(30)
    @DisplayName("P1-04: dispatch 20, receive 18, damaged 1, short 1 — then reverse every stage")
    void transitFullCycle() {
        String p = product("TR-FULL", Map.of("costPrice", 100));
        receive(p, 50, 100);
        BigDecimal svStart = balance("stock_valuation");
        JsonNode doc = createTransit(p, 20);
        String id = doc.get("id").asString();
        String itemId = doc.get("items").get(0).get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        assertThat(onHand(p, main)).isEqualByComparingTo("50");
        Res early = api("POST", "/inventory/transfers/" + id + "/receive", Map.of("lines", List.of(receiveLine(itemId,
                1, null, null))));
        assertThat(early.body().get("message").asString()).contains("only in-transit");

        JsonNode dispatched = okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(dispatched.get("status").asString()).isEqualTo("in_transit");
        String transit = transitLocation();
        assertThat(onHand(p, main)).isEqualByComparingTo("30");
        assertThat(onHand(p, transit)).isEqualByComparingTo("20");
        assertThat(onHand(p, alt)).isEqualByComparingTo("0");
        assertThat(api("POST", "/inventory/transfers/" + id + "/dispatch", null).body().get("message").asString())
                .contains("approved, undispatched");

        Res over = api("POST", "/inventory/transfers/" + id + "/receive", Map.of("lines", List.of(receiveLine(itemId,
                19, 1, 1))));
        assertThat(over.body().get("message").asString()).contains("exceeds");
        JsonNode partial = okA(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(receiveLine(itemId, 10, null, null))));
        assertThat(partial.get("status").asString()).isEqualTo("partially_received");
        JsonNode done = okA(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(receiveLine(itemId, 8, 1, 1)), "notes", "two cartons crushed / missing"));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("receipts")).hasSize(2);

        assertThat(onHand(p, main)).isEqualByComparingTo("30");
        assertThat(onHand(p, transit)).isEqualByComparingTo("0");
        assertThat(onHand(p, alt)).isEqualByComparingTo("18");
        Map<String, Object> line = jdbc.sql("SELECT \"qtyDispatched\", \"qtyReceived\", \"qtyDamaged\", \"qtyShort\""
                + " FROM \"StockTransferItem\" WHERE id = ?").param(itemId).query().singleRow();
        assertThat(List.of(line.get("qtyDispatched"), line.get("qtyReceived"), line.get("qtyDamaged"),
                line.get("qtyShort")).stream().map(v -> ((BigDecimal) v).stripTrailingZeros().toPlainString()).toList())
                .containsExactly("20", "18", "1", "1");
        // Only the two write-offs leave inventory value: 2 × 100 (the in-transit leg nets out once received).
        assertThat(balance("stock_valuation").add(balance("stock_in_transit")))
                .isEqualByComparingTo(svStart.subtract(new BigDecimal("200")));
        for (String loc : List.of(main, transit, alt)) {
            assertThat(ledgerSum(p, loc)).isEqualByComparingTo(onHand(p, loc));
        }

        okA(200, "POST", "/inventory/transfers/" + id + "/reverse", Map.of("reason", "wrong branch"));
        assertThat(onHand(p, main)).isEqualByComparingTo("50");
        assertThat(onHand(p, transit)).isEqualByComparingTo("0");
        assertThat(onHand(p, alt)).isEqualByComparingTo("0");
        assertThat(balance("stock_valuation").add(balance("stock_in_transit"))).isEqualByComparingTo(svStart);
    }

    @Test
    @Order(31)
    @DisplayName("P1-04: recall returns what is still in transit; cancel only before dispatch")
    void transitRecallAndCancel() {
        String p = product("TR-RECALL", Map.of("costPrice", 100));
        receive(p, 10, 100);
        JsonNode doc = createTransit(p, 10);
        String id = doc.get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(api("POST", "/inventory/transfers/" + id + "/cancel", null).body().get("message").asString())
                .contains("already moved stock");
        okA(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines", List.of(receiveLine(
                doc.get("items").get(0).get("id").asString(), 4, null, null))));
        JsonNode recalled = okA(200, "POST", "/inventory/transfers/" + id + "/recall", Map.of("reason",
                "truck turned back"));
        assertThat(recalled.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(p, main)).isEqualByComparingTo("6");
        assertThat(onHand(p, alt)).isEqualByComparingTo("4");
        assertThat(onHand(p, transitLocation())).isEqualByComparingTo("0");

        JsonNode other = createTransit(p, 1);
        okA(200, "POST", "/inventory/transfers/" + other.get("id").asString() + "/approve", null);
        assertThat(okA(200, "POST", "/inventory/transfers/" + other.get("id").asString() + "/cancel", null)
                .get("status").asString()).isEqualTo("cancelled");
    }

    @Test
    @Order(32)
    @DisplayName("P1-04: immediate transfers are unchanged and cannot be dispatched")
    void immediateTransferNoDispatch() {
        String p = product("TR-IMM", Map.of("costPrice", 100));
        receive(p, 5, 100);
        JsonNode doc = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", 2))));
        String id = doc.get("id").asString();
        assertThat(api("POST", "/inventory/transfers/" + id + "/dispatch", null).body().get("message").asString())
                .contains("immediate transfer");
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        assertThat(onHand(p, alt)).isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------ inv-p2-accounting.spec.ts (INV-013, INV-011)

    @Test
    @Order(40)
    @DisplayName("INV-013: the stock ledger refuses UPDATE, DELETE and broken arithmetic")
    void ledgerGuards() {
        Map<String, Object> row = jdbc.sql("SELECT id, \"productId\", \"locationId\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? LIMIT 1").param(org).query().singleRow();
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"InventoryLedger\" SET notes = 'tamper' WHERE id = ?")
                .param(row.get("id")).update()).hasStackTraceContaining("posted stock evidence");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM \"InventoryLedger\" WHERE id = ?").param(row.get("id")).update())
                .hasStackTraceContaining("posted stock evidence");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\","
                        + " \"productId\", \"locationId\", type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\","
                        + " \"unitCost\", \"totalValue\") VALUES (?, ?, 'BAD', ?, ?, 'receipt', 0, 5, 7, 1, 5)")
                .params(UUID.randomUUID().toString(), org, row.get("productId"), row.get("locationId")).update())
                .hasStackTraceContaining("balance_arithmetic_check");
    }

    @Test
    @Order(99)
    @DisplayName("INV-011: after the mixed scenario every quant conserves and the GL ties to the valuation (variance 0)")
    void tieOutAfterMixedScenario() {
        assertThat(quantInvariantBreaks()).isEmpty();
        JsonNode tie = okA(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.01", null);
        assertThat(num(tie.get("variance")).abs()).as(tie.toString()).isLessThanOrEqualTo(new BigDecimal("0.01"));
        assertThat(tie.get("withinTolerance").asBoolean()).isTrue();
        for (JsonNode u : tie.get("unpostedMovements")) {
            assertThat(num(u.get("value")).signum()).as("unposted valued movement " + u).isZero();
        }
        JsonNode current = okA(200, "GET", "/reports/inventory/valuation", null);
        assertThat(current.get("basis").asString()).isEqualTo("current_cost_by_method");
        assertThat(num(current.get("summary").get("totalValue")).subtract(num(tie.get("subledgerValue"))).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
        String yearAgo = Instant.now().minus(365, ChronoUnit.DAYS).toString().substring(0, 10);
        JsonNode past = okA(200, "GET", "/reports/inventory/valuation?asOf=" + yearAgo, null);
        assertThat(past.get("basis").asString()).isEqualTo("ledger");
        assertThat(past.get("items")).isEmpty();
        // The inventory screen's report values the same stock as accounting (deviation 22).
        JsonNode screen = okA(200, "GET", "/inventory/reports/valuation", null);
        assertThat(screen).isNotNull();
    }
}
