package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueBackReceipt;
import com.inventoryerp.inventory.application.StockReversalService.IssuedBack;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR review 3 (stream R3): defects of the independent review of the inventory engine core — serial+lot transfers and
 * issues naming a lot (#1, #3), receipt take-backs of serial products (#2), blank receipt cost of a STANDARD product
 * (#4), an AVCO count gain on a negative quant without an average (#5), a late count correction that cannot post (#6)
 * and the count freeze exemption (#7). Every scenario ends with the integrity report and the GL tie-out.
 */
class IprReview3EngineIT extends InventoryAuditSupport {

    static final String LATE = "inventory_count_late_correction";

    @Autowired
    StockReversalService reversals;

    @Autowired
    StockPostingService stockPosting;

    /** A failed test must not leave a draft count that blocks the next one (one draft per location). */
    @AfterEach
    void cancelDrafts() {
        asOrgRun(() -> jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'cancelled' WHERE \"organizationId\" = ?"
                + " AND status = 'draft'").param(org).update());
    }

    // ------------------------------------------------------------------ fixtures

    String serialLot(String code) {
        return product(code, Map.of("serialTracking", true, "batchTracking", true, "costPrice", 10));
    }

    String serialOnly(String code) {
        return product(code, Map.of("serialTracking", true, "costPrice", 10));
    }

    /** {@code qty} units into lot {@code lot}, of which {@code serials} carry a serial (capture_optional). */
    void receiveLot(String p, String lot, int qty, List<String> serials, int cost) {
        receive(p, main, qty, cost, m -> {
            m.serials(serials);
            if (lot != null) {
                m.batch(lot);
            }
        });
    }

    /** A receipt under document {@code (type, id)}: {@code qty} units, {@code serials} of them serialised. */
    void receiveDoc(String p, String type, String id, int qty, List<String> serials, int cost) {
        asOrg(() -> stock.receiveForDocument(StockMove.of(p, main, BigDecimal.valueOf(qty)).unitCost(BigDecimal
                .valueOf(cost)).serials(serials), new GlReceiptContext(type, id, Instant.now(), null)));
    }

    Map<String, Object> serial(String p, String number) {
        return asOrg(() -> jdbc.sql("SELECT s.status::text AS status, s.\"locationId\", b.\"batchNumber\","
                + " b.\"locationId\" AS \"lotLocationId\" FROM \"InventorySerial\" s LEFT JOIN \"InventoryBatch\" b ON"
                + " b.id = s.\"batchId\" WHERE s.\"organizationId\" = ? AND s.\"productId\" = ? AND s.\"serialNumber\" = ?")
                .params(org, p, number).query().singleRow());
    }

    String status(String p, String number) {
        return (String) serial(p, number).get("status");
    }

    BigDecimal lotQty(String p, String loc, String lot) {
        return asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ? AND \"batchNumber\" = ?").params(org, p, loc, lot)
                .query(BigDecimal.class).single());
    }

    long inStockSerials(String p, String loc) {
        return asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND status = 'in_stock'").params(org, p, loc)
                .query(Long.class).single());
    }

    BigDecimal average(String p, String loc) {
        return asOrg(() -> jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"variantKey\" = '' AND \"locationId\" = ?").params(org, p, loc)
                .query(BigDecimal.class).single());
    }

    void green() {
        JsonNode report = integrityReport();
        List<String> failing = failingChecks(report, "checks");
        assertThat(failing).as("integrity checks " + failing).isEmpty();
        List<String> reportOnly = failingChecks(report, "reportOnlyChecks").stream()
                .filter(f -> !f.startsWith("feature_risk ") && !f.startsWith("negative_quants ")).toList();
        assertThat(reportOnly).as("report-only integrity checks " + reportOnly).isEmpty();
        assertGlTieOut();
    }

    JsonNode startCount(String loc, List<String> products, boolean freeze) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationId", loc);
        body.put("countType", "spot");
        body.put("scopeProductIds", products);
        if (freeze) {
            body.put("freezeLocation", true);
        }
        return okA(201, "POST", "/inventory/counts/start", body);
    }

    void countAndSubmit(JsonNode session, String product, int counted) {
        String lineId = null;
        for (JsonNode l : session.get("lines")) {
            // a lot-tracked product has a line per lot plus a lot-less line: count the lot that holds the stock
            boolean lot = l.get("batchId") != null && !l.get("batchId").isNull();
            if (product.equals(l.get("productId").asString()) && (lineId == null || lot)) {
                lineId = l.get("id").asString();
            }
        }
        String id = session.get("id").asString();
        okA(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines",
                List.of(Map.of("lineId", lineId, "countedQty", counted, "reason", "counted"))));
        okA(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
    }

    static void pause() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ #1

    @Test
    @DisplayName("#1: a serial+lot transfer naming a lot moves that lot's serial units and its units without a serial,"
            + " never another lot's serial unit")
    void transferNamedLotStaysInLot() {
        String p = serialLot("R3-TRF");
        receiveLot(p, "L1", 5, List.of("R3T-1", "R3T-2"), 10);
        receiveLot(p, "L2", 1, List.of("R3T-3"), 12);
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, BigDecimal.valueOf(3)).batch("L1")
                .source("audit_transfer", "t-" + UUID.randomUUID())));
        assertThat(serial(p, "R3T-3").get("locationId")).as("L2's serial unit stays at the source").isEqualTo(main);
        assertThat(serial(p, "R3T-1").get("locationId")).isEqualTo(alt);
        assertThat(serial(p, "R3T-2").get("lotLocationId")).as("its lot row moved with it").isEqualTo(alt);
        assertThat(lotQty(p, main, "L1")).isEqualByComparingTo("2");
        assertThat(lotQty(p, main, "L2")).isEqualByComparingTo("1");
        assertThat(lotQty(p, alt, "L1")).isEqualByComparingTo("3");
        // A serial number of another lot named together with the lot is refused.
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, BigDecimal.ONE)
                .batch("L1").serials(List.of("R3T-3")).source("audit_transfer", "t-" + UUID.randomUUID()))))
                .hasMessageContaining("not in batch L1");
        green();
    }

    // ------------------------------------------------------------------ #3

    @Test
    @DisplayName("#3: an issue naming a lot of a serial+lot product takes that lot's serial units, then its units"
            + " without a serial; other lots only for what the lot lacks")
    void issueNamedLotStaysInLot() {
        String p = serialLot("R3-ISS");
        receiveLot(p, "L1", 5, List.of("R3I-1", "R3I-2"), 10);
        receiveLot(p, "L2", 1, List.of("R3I-3"), 12);
        issue(p, main, 3, m -> m.strategy("MANUAL").batch("L1"));
        assertThat(status(p, "R3I-3")).as("L2's serial unit is not released").isEqualTo("in_stock");
        assertThat(status(p, "R3I-1")).isEqualTo("issued");
        assertThat(status(p, "R3I-2")).isEqualTo("issued");
        assertThat(lotQty(p, main, "L1")).isEqualByComparingTo("2");
        assertThat(lotQty(p, main, "L2")).isEqualByComparingTo("1");
        green();
        // The named lot is short (2 left): the rest comes from the other lot, and lots keep summing to on-hand.
        issue(p, main, 3, m -> m.strategy("MANUAL").batch("L1"));
        assertThat(lotQty(p, main, "L1")).isEqualByComparingTo("0");
        assertThat(status(p, "R3I-3")).isEqualTo("issued");
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        green();
    }

    // ------------------------------------------------------------------ #2

    @Test
    @DisplayName("#2a: reversing a receipt of 2 serial units + 3 without a serial takes back its own units, not other"
            + " receipts' serial units")
    void reversalTakesBackUnserialisedUnits() {
        String p = serialOnly("R3-REVA");
        receiveDoc(p, "audit_seed", "old-" + UUID.randomUUID(), 2, List.of("R3A-1", "R3A-2"), 10);
        String doc = "grn-" + UUID.randomUUID();
        receiveDoc(p, "audit_seed", doc, 5, List.of("R3A-3", "R3A-4"), 10);
        asOrg(() -> reversals.reverseLedgerSource(new LedgerSource("audit_seed", doc, "audit_reversal", "rev-" + doc,
                List.of("audit_seed"), "test reversal", null)));
        assertThat(status(p, "R3A-1")).as("another receipt's serial unit stays").isEqualTo("in_stock");
        assertThat(status(p, "R3A-2")).isEqualTo("in_stock");
        assertThat(status(p, "R3A-3")).isEqualTo("issued");
        assertThat(status(p, "R3A-4")).isEqualTo("issued");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        assertThat(inStockSerials(p, main)).isEqualTo(2);
        green();
    }

    @Test
    @DisplayName("#2a: issuing back a bill receipt of 1 serial unit + 3 without a serial leaves other receipts' serial"
            + " units in stock")
    void issueBackTakesUnserialisedUnits() {
        String p = serialOnly("R3-BACK");
        receiveDoc(p, "audit_seed", "old-" + UUID.randomUUID(), 2, List.of("R3B-1", "R3B-2"), 10);
        String bill = "bill-" + UUID.randomUUID();
        asOrg(() -> stock.receiveForDocument(StockMove.of(p, main, BigDecimal.valueOf(4)).unitCost(BigDecimal.TEN)
                .serials(List.of("R3B-3")), GlReceiptContext.grni("vendor_bill", bill, Instant.now())));
        List<IssuedBack> back = asOrg(() -> reversals.issueBackReceipt(new IssueBackReceipt("vendor_bill", bill,
                "vendor_bill_cancel", bill, null, null, "Bill cancelled", null, "Cannot cancel the bill", null)));
        assertThat(back).hasSize(1);
        asOrgRun(() -> stockPosting.postAdjustment(p, BigDecimal.ONE.negate(), back.getFirst().totalValue(),
                Instant.now(), "vendor_bill_cancel", bill, "test: bill cancel stock credit"));
        assertThat(status(p, "R3B-1")).as("another receipt's serial unit stays").isEqualTo("in_stock");
        assertThat(status(p, "R3B-2")).isEqualTo("in_stock");
        assertThat(status(p, "R3B-3")).isEqualTo("issued");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        assertThat(inStockSerials(p, main)).isEqualTo(2);
        green();
    }

    @Test
    @DisplayName("#2b: a strict reversal of a document with two rows of one serial product takes back each row's own"
            + " serial units, not the oldest in stock")
    void reversalOfTwoRowsPinsEachRow() {
        String p = serialOnly("R3-REVB");
        receiveDoc(p, "audit_seed", "old-" + UUID.randomUUID(), 2, List.of("R3V-1", "R3V-2"), 10);
        String doc = "grn-" + UUID.randomUUID();
        receiveDoc(p, "audit_seed", doc, 2, List.of("R3W-1", "R3W-2"), 10);
        receiveDoc(p, "audit_seed", doc, 2, List.of("R3W-3", "R3W-4"), 10);
        asOrg(() -> reversals.reverseLedgerSource(new LedgerSource("audit_seed", doc, "audit_reversal", "rev-" + doc,
                List.of("audit_seed"), "test reversal", null, "Cannot reverse the receipt", null)));
        assertThat(status(p, "R3V-1")).as("older receipt's serial unit stays").isEqualTo("in_stock");
        assertThat(status(p, "R3V-2")).isEqualTo("in_stock");
        for (String w : List.of("R3W-1", "R3W-2", "R3W-3", "R3W-4")) {
            assertThat(status(p, w)).as(w).isEqualTo("issued");
        }
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        green();
    }

    // ------------------------------------------------------------------ #4

    @Test
    @DisplayName("#4: a receipt without a cost of a STANDARD product enters at its governing standard, with no"
            + " purchase price variance (a quant left with an older average after the method change)")
    void standardBlankCostAtStandard() {
        // An AVCO product bought at 12 and sold out at the second store; then switched to STANDARD at 10 (allowed with
        // no stock on hand). The empty quant keeps its old average 12.
        String p = product("R3-STD", Map.of("costPrice", 12));
        receive(p, alt, 2, 12, null);
        issue(p, alt, 2, null);
        okA(200, "PATCH", "/products/" + p, Map.of("costingMethod", "STANDARD", "costPrice", 10));
        String grn = "grn-" + UUID.randomUUID();
        StockMove.Received r = asOrg(() -> stock.receiveForDocument(StockMove.of(p, alt, BigDecimal.valueOf(3)),
                GlReceiptContext.grni("audit_grn", grn, Instant.now())));
        assertThat(r.unitCost()).isEqualByComparingTo("10");
        assertThat(r.actualValue()).as("blank cost = the governing standard (GRNI at 30)").isEqualByComparingTo("30");
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"StandardCostVariance\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND amount <> 0", org, p))).as("no purchase price variance").isZero();
        green();
    }

    // ------------------------------------------------------------------ #5

    @Test
    @DisplayName("#5: an AVCO count gain on a negative quant without an average gives the quant the gain's cost and"
            + " squares the short units (no value stranded)")
    void avcoCountGainOnNegativeQuant() {
        String p = product("R3-NEG", Map.of("costPrice", 4));
        receive(p, main, 2, 0, null);
        issue(p, main, 4, null);
        assertThat(onHand(p, main)).isEqualByComparingTo("-2");
        asOrg(() -> {
            StockMove m = StockMove.of(p, main, null).source("audit_count", "cnt-" + UUID.randomUUID());
            m.countedQuantity = BigDecimal.valueOf(3);
            return stock.adjust(m);
        });
        assertThat(onHand(p, main)).isEqualByComparingTo("3");
        assertThat(average(p, main)).as("the quant takes the gain's cost").isEqualByComparingTo("4");
        green();
    }

    // ------------------------------------------------------------------ #6

    @Test
    @DisplayName("#6: a late count correction refused by the posting (a dimension rule) is flagged for review; the"
            + " late movement still commits and the correction's events do not go out")
    void lateCorrectionRefusedByPostingKeepsMovement() {
        String p = product("R3-LATE", Map.of("costPrice", 5, "batchTracking", true));
        receive(p, main, 10, 5, m -> m.batch("LT1"));
        Instant movedAt = Instant.now();
        pause();
        JsonNode session = startCount(main, List.of(p), false);
        countAndSubmit(session, p, 7);
        String dim = "R3DIM" + UUID.randomUUID().toString().substring(0, 4).replace("-", "");
        String income = account("stock_adjustment_income");
        asOrgRun(() -> {
            jdbc.sql("INSERT INTO \"AnalyticDimension\" (id, \"organizationId\", code, name) VALUES (?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org, dim, "Review 3 dimension").update();
            jdbc.sql("INSERT INTO \"AccountDimensionRule\" (id, \"organizationId\", \"accountId\", dimension, rule)"
                    + " VALUES (?, ?, ?, ?, 'required')").params(UUID.randomUUID().toString(), org, income, dim).update();
        });
        try {
            detachedWrites.awaitIdle(Duration.ofSeconds(10));
            long receivedEvents = asOrg(() -> count("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" ="
                    + " 'stock.received' AND payload->>'productId' = ?", p));
            // A late stock-out of 3 that happened before the count: its correction (+3, Cr adjustment income) is
            // refused inside the posting service, after the pre-check passed.
            asOrg(() -> {
                StockMove m = StockMove.of(p, main, BigDecimal.valueOf(3)).source("audit_sale", "late-" + UUID.randomUUID());
                m.effectiveAt = movedAt;
                return stock.issue(m);
            });
            assertThat(onHand(p, main)).as("the late movement applied, uncorrected: 7 − 3").isEqualByComparingTo("4");
            assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                    + " \"productId\" = ? AND \"referenceType\" = ?", org, p, LATE))).isZero();
            assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                    + " \"productId\" = ? AND kind = 'late_count_correction_failed' AND status = 'open'", org, p)))
                    .as("flagged for manual review").isEqualTo(1);
            detachedWrites.awaitIdle(Duration.ofSeconds(10));
            assertThat(asOrg(() -> count("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'stock.received'"
                    + " AND payload->>'productId' = ?", p))).as("the rolled-back correction announced nothing")
                    .isEqualTo(receivedEvents);
            JsonNode late = okA(200, "GET", "/inventory/counts/" + session.get("id").asString(), null)
                    .get("lateTransactions");
            assertThat(late.toString()).contains("uncorrected");
        } finally {
            asOrgRun(() -> {
                jdbc.sql("DELETE FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ? AND dimension = ?")
                        .params(org, dim).update();
                jdbc.sql("DELETE FROM \"AnalyticDimension\" WHERE \"organizationId\" = ? AND code = ?").params(org, dim)
                        .update();
            });
        }
        List<String> failing = failingChecks(integrityReport(), "checks");
        assertThat(failing).as("integrity checks " + failing).isEmpty();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ #7

    @Test
    @DisplayName("#7: a count freeze exempts only the session that holds it; another session's posting is refused")
    void freezeExemptsOnlyItsSession() {
        String q = product("R3-FRZ", Map.of("costPrice", 5));
        receive(q, main, 5, 5, null);
        receive(q, alt, 5, 5, null);
        String frozen = startCount(main, List.of(q), true).get("id").asString();
        String other = startCount(alt, List.of(q), false).get("id").asString();
        assertThatThrownBy(() -> asOrg(() -> {
            StockMove m = StockMove.of(q, main, null).source("stock_adjustment", "x-" + UUID.randomUUID());
            m.deltaQuantity = BigDecimal.ONE;
            m.countSessionId = other;
            m.skipLateCountCheck = true;
            return stock.adjust(m);
        })).hasMessageContaining("is frozen for count");
        asOrg(() -> {
            StockMove m = StockMove.of(q, main, null).source("stock_adjustment", "x-" + UUID.randomUUID());
            m.deltaQuantity = BigDecimal.ONE;
            m.countSessionId = frozen;
            m.skipLateCountCheck = true;
            return stock.adjust(m);
        });
        assertThat(onHand(q, main)).as("the freeze holder's own posting passes").isEqualByComparingTo("6");
        green();
    }
}
