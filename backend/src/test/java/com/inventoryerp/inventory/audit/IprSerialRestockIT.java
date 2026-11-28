package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueBackReceipt;
import com.inventoryerp.inventory.application.StockReversalService.IssueSource;
import com.inventoryerp.inventory.application.StockReversalService.IssuedBack;
import com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnLine;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR 1c / 1d (engine stream E2): serial and lot integrity (C-1, C-2, C-7), the virtual-location guard (X-3), the
 * opt-in sales-order reservation check (E-1a), unknown move types, product setup changes under the costing lock (C-8),
 * the bill line on bill receipts (SW-5), the receipt take-back helper (F6 engine side), linked-restock detection
 * (unlinked_restocks) and the R2 races of the engine (same serial, last unit, reserve + issue).
 */
class IprSerialRestockIT extends InventoryAuditSupport {

    @Autowired
    StockReversalService reversals;

    @Autowired
    StockReservationService reservations;

    @Autowired
    com.inventoryerp.inventory.application.StockPostingService stockPosting;

    // ------------------------------------------------------------------ fixtures

    String serialLot(String code) {
        return product(code, Map.of("serialTracking", true, "batchTracking", true, "costPrice", 10));
    }

    String serialOnly(String code) {
        return product(code, Map.of("serialTracking", true, "costPrice", 10));
    }

    void receiveSerials(String p, String loc, String lot, List<String> serials, int cost) {
        receive(p, loc, serials.size(), cost, m -> {
            m.serials(serials);
            if (lot != null) {
                m.batch(lot);
            }
        });
    }

    static List<String> sn(String prefix, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(prefix + "-" + i);
        }
        return out;
    }

    Map<String, Object> serial(String p, String number) {
        return asOrg(() -> jdbc.sql("SELECT s.status::text AS status, s.\"locationId\", b.\"batchNumber\","
                + " b.\"locationId\" AS \"lotLocationId\" FROM \"InventorySerial\" s LEFT JOIN \"InventoryBatch\" b ON"
                + " b.id = s.\"batchId\" WHERE s.\"organizationId\" = ? AND s.\"productId\" = ? AND s.\"serialNumber\" = ?")
                .params(org, p, number).query().singleRow());
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

    String message(Throwable e) {
        return e == null ? null : e.getMessage();
    }

    void green() {
        assertIntegrityGreen(true, "feature_risk", "negative_quants");
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ C-1

    @Test
    @DisplayName("C-1: a serial issue of a serial+lot product takes the unit off its lot; lots = on-hand")
    void serialIssueDecrementsLot() {
        String p = serialLot("C1-ISS");
        receiveSerials(p, main, "L1", sn("C1A", 1, 3), 10);
        issue(p, main, 1, m -> m.serials(List.of("C1A-2")));
        assertThat(lotQty(p, main, "L1")).isEqualByComparingTo("2");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        // Units without a serial leave the lot's unserialised part only: none here, the serials are picked (C-2).
        issue(p, main, 1, null);
        assertThat(lotQty(p, main, "L1")).isEqualByComparingTo("1");
        assertThat(inStockSerials(p, main)).isEqualTo(1);
        green();
    }

    @Test
    @DisplayName("C-1 / C-7: a transfer moves the named serials and their lots follow them")
    void transferLotsFollowSerials() {
        String p = serialLot("C1-TRF");
        receiveSerials(p, main, "LA", sn("C1T", 1, 2), 10);
        receiveSerials(p, main, "LB", sn("C1T", 3, 4), 12);
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, BigDecimal.ONE).serials(List.of("C1T-3"))
                .source("audit_transfer", "t-" + UUID.randomUUID())));
        Map<String, Object> s3 = serial(p, "C1T-3");
        assertThat(s3.get("locationId")).as("the named serial moved").isEqualTo(alt);
        assertThat(s3.get("batchNumber")).isEqualTo("LB");
        assertThat(s3.get("lotLocationId")).as("its lot row is at the destination").isEqualTo(alt);
        assertThat(lotQty(p, main, "LA")).as("the other lot did not move").isEqualByComparingTo("2");
        assertThat(lotQty(p, main, "LB")).isEqualByComparingTo("1");
        assertThat(lotQty(p, alt, "LB")).isEqualByComparingTo("1");
        green();
    }

    @Test
    @DisplayName("C-1: a linked return brings the serial back into its own lot")
    void returnAndReversalKeepLot() {
        String p = serialLot("C1-RET");
        receiveSerials(p, main, "LR", sn("C1R", 1, 2), 10);
        StockMove.Issued out = issue(p, main, 1, m -> m.serials(List.of("C1R-1")));
        asOrgRun(() -> reversals.returnFromSource(new ReturnFromSource(IssueSource.ledgerCodes(List.of(out.ledgerCode())),
                null, "audit_return", "r-" + UUID.randomUUID(), null, null, null, null, true, null)));
        assertThat(serial(p, "C1R-1").get("status")).isEqualTo("in_stock");
        assertThat(serial(p, "C1R-1").get("batchNumber")).isEqualTo("LR");
        assertThat(lotQty(p, main, "LR")).isEqualByComparingTo("2");
        green();
    }

    // ------------------------------------------------------------------ C-2

    @Test
    @DisplayName("C-2: more serial numbers than units is refused (400), whoever calls the engine")
    void moreSerialsThanQuantityRefused() {
        String p = serialOnly("C2-MORE");
        receiveSerials(p, main, null, sn("C2M", 1, 3), 10);
        assertThatThrownBy(() -> issue(p, main, 1, m -> m.serials(List.of("C2M-1", "C2M-2"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("at most one serial number per unit");
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, BigDecimal.ONE)
                .serials(List.of("C2M-1", "C2M-2")).source("audit_transfer", "t"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("at most one serial number per unit");
        assertThat(onHand(p, main)).isEqualByComparingTo("3");
        green();
    }

    @Test
    @DisplayName("C-2: fewer serial numbers than units — the rest are picked from the units in stock, oldest first")
    void fewerSerialsAutoPick() {
        String p = serialOnly("C2-FEW");
        receiveSerials(p, main, null, sn("C2F", 1, 3), 10);
        issue(p, main, 2, m -> m.serials(List.of("C2F-3")));
        assertThat(serial(p, "C2F-3").get("status")).isEqualTo("issued");
        assertThat(serial(p, "C2F-1").get("status")).as("oldest unit picked").isEqualTo("issued");
        assertThat(serial(p, "C2F-2").get("status")).isEqualTo("in_stock");
        assertThat(inStockSerials(p, main)).isEqualTo(1);
        green();
    }

    // ------------------------------------------------------------------ X-3

    @Test
    @DisplayName("X-3: manual documents cannot move stock into or out of quarantine / rental locations; owning flows can")
    void virtualLocationGuard() {
        String qc = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code ="
                + " 'QC-QUARANTINE'").param(org).query(String.class).optional().orElse(null));
        String quarantine = qc != null ? qc : location("QC-QUARANTINE", "QC Quarantine", "virtual");
        String rentOut = location("RENT-OUT", "Rental Out", "virtual");
        String p = product("X3-GUARD", Map.of("costPrice", 4));
        receive(p, main, 10, 4, null);

        // A manual direct stock-in into quarantine: refused (route level).
        Res in = api("POST", "/inventory/direct-stock/in", direct(quarantine, List.of(item(p, 1, 4))));
        assertThat(in.status()).as(in.raw().body()).isEqualTo(400);
        assertThat(in.raw().body()).contains("manual stock document");
        // A hold: a manual transfer into quarantine is allowed.
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, quarantine, BigDecimal.valueOf(3)).manual()
                .source("stock_transfer", "hold")));
        // Out of quarantine by a manual document, a sale or a consumption: refused.
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, quarantine, main, BigDecimal.ONE)
                .manual().source("stock_transfer", "out")))).hasMessageContaining("manual stock document");
        assertThatThrownBy(() -> issue(p, quarantine, 1, m -> m.source("pos_invoice", "sale")))
                .hasMessageContaining("on hold");
        assertThatThrownBy(() -> issue(p, quarantine, 1, m -> m.moveType("production_consume")
                .source("production_order", "mo"))).hasMessageContaining("on hold");
        // The owning flows: a QC release (transfer) and a quality rejection (issue) pass.
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, quarantine, main, BigDecimal.ONE)
                .source("quality_lot", "QL-1")));
        issue(p, quarantine, 1, m -> m.moveType("waste").source("quality_lot", "QL-1"));
        issue(p, quarantine, 1, m -> m.moveType("waste").source("audit", "x").quarantineFlow());
        assertThat(onHand(p, quarantine)).isEqualByComparingTo("0");

        // Rental custody: a manual transfer out of / into RENT-OUT is refused; the rental flow's transfer passes.
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, rentOut, BigDecimal.ONE)
                .source("rental_checkout", "RA-1")));
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, rentOut, main, BigDecimal.ONE)
                .manual().source("stock_transfer", "x")))).hasMessageContaining("rental");
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, rentOut, BigDecimal.ONE)
                .manual().source("stock_transfer", "x")))).hasMessageContaining("rental");
        asOrgRun(() -> stock.transfer(StockMove.transfer(p, rentOut, main, BigDecimal.ONE)
                .source("rental_return", "RA-1")));
        green();
    }

    // ------------------------------------------------------------------ unknown move types (stream C request)

    @Test
    @DisplayName("An issue posting its own GL with a move type that has no posting rule is refused (no COGS fallback)")
    void unknownMoveTypeRefused() {
        String p = product("MT-UNK", Map.of("costPrice", 3));
        receive(p, main, 5, 3, null);
        long journals = journalEntries();
        assertThatThrownBy(() -> issue(p, main, 1, m -> m.moveType("adjustment_out")))
                .isInstanceOf(ApiException.class).hasMessageContaining("has no stock posting rule");
        assertThat(journalEntries()).isEqualTo(journals);
        // Quantity-only moves (the caller posts the journal) keep their own move type.
        issue(p, main, 1, m -> m.moveType("adjustment_out").skipGl());
        for (String t : List.of("issue", "waste", "expiry_write_off", "return_to_supplier", "internal_use",
                "promo_sample", "production_consume")) {
            issue(p, main, 0.5, m -> m.moveType(t));
        }
        assertThat(onHand(p, main)).isEqualByComparingTo("0.5");
    }

    // ------------------------------------------------------------------ E-1a

    @Test
    @DisplayName("E-1a: an opted-in issue or transfer leaves stock reserved for sales orders alone; a POS drain only"
            + " where its stock policy blocks")
    void salesReservationsHonoured() {
        String p = product("E1A-RES", Map.of("costPrice", 2));
        receive(p, main, 5, 2, null);
        asOrgRun(() -> reservations.reserve(new StockReservationService.Reserve(p, null, main, BigDecimal.valueOf(4),
                "sales_order_line", "SOL-1", null)));
        assertThatThrownBy(() -> issue(p, main, 2, m -> m.honourReservations(null)))
                .hasMessageContaining("is reserved for sales orders");
        assertThatThrownBy(() -> asOrgRun(() -> stock.transfer(StockMove.transfer(p, main, alt, BigDecimal.valueOf(2))
                .honourReservations(null).source("stock_transfer", "x")))).hasMessageContaining("reserved for sales");
        Res out = api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 2, null))));
        assertThat(out.status()).as(out.raw().body()).isEqualTo(400);
        assertThat(out.raw().body()).contains("reserved for sales orders");
        // The holder itself, a caller that does not opt in, and a POS drain under the default stock policy pass.
        issue(p, main, 1, m -> m.honourReservations(List.of("SOL-1")));
        issue(p, main, 1, m -> m.source("pos_invoice", "inv-1").honourReservations(null));
        // A POS drain of a block-policy product is refused where its availability rule refuses.
        String b = product("E1A-BLK", Map.of("costPrice", 2, "stockPolicy", "block"));
        receive(b, main, 3, 2, null);
        asOrgRun(() -> reservations.reserve(new StockReservationService.Reserve(b, null, main, BigDecimal.valueOf(3),
                "sales_order_line", "SOL-2", null)));
        assertThatThrownBy(() -> issue(b, main, 1, m -> m.source("pos_invoice", "inv-2").honourReservations(null)))
                .hasMessageContaining("reserved for sales orders");
        asOrgRun(() -> jdbc.sql("UPDATE \"StockReservation\" SET status = 'released' WHERE \"organizationId\" = ? AND"
                + " \"sourceId\" IN ('SOL-1', 'SOL-2')").param(org).update());
        green();
    }

    // ------------------------------------------------------------------ C-8

    @Test
    @DisplayName("C-8: tracking / costing changes wait for open reservations and pending POS postings (costing lock)")
    void setupChangeBlockedByPendingWork() {
        String p = product("C8-RES", Map.of("costPrice", 2));
        asOrgRun(() -> reservations.reserve(new StockReservationService.Reserve(p, null, main, BigDecimal.ONE,
                "manual", "c8-" + UUID.randomUUID(), null)));
        Res r = api("PATCH", "/products/" + p, Map.of("batchTracking", true));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("is reserved");
        Res c = api("PATCH", "/products/" + p, Map.of("costingMethod", "FIFO", "batchTracking", true));
        assertThat(c.status()).as(c.raw().body()).isEqualTo(400);
        asOrgRun(() -> jdbc.sql("UPDATE \"StockReservation\" SET status = 'released' WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(org, p).update());

        String q = product("C8-POS", Map.of("costPrice", 2));
        String orderId = insert("Order", Map.of("organizationId", org, "orderNumber", "ORD-C8-" + UUID.randomUUID(),
                "updatedAt", now()));
        insert("OrderItem", Map.of("organizationId", org, "orderId", orderId, "description", "C8", "quantity", 1,
                "lineNumber", 1, "productId", q, "updatedAt", now()));
        insert("StockPostingJob", Map.of("organizationId", org, "orderId", orderId, "status", "pending",
                "idempotencyKey", "c8:" + orderId));
        Res j = api("PATCH", "/products/" + q, Map.of("serialTracking", true));
        assertThat(j.status()).as(j.raw().body()).isEqualTo(400);
        assertThat(j.raw().body()).contains("waiting for its stock posting");
        asOrgRun(() -> jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'abandoned' WHERE \"orderId\" = ?")
                .param(orderId).update());
        okA(200, "PATCH", "/products/" + q, Map.of("serialTracking", true));
    }

    // ------------------------------------------------------------------ SW-5 and the F6 engine helper

    @Test
    @DisplayName("SW-5: a bill receipt records the bill line it came from")
    void billReceiptKeepsSourceLine() {
        String p = product("SW5-BILL", Map.of("costPrice", 2));
        String line = "bill-line-" + UUID.randomUUID();
        StockMove.Received res = asOrg(() -> stock.receiveFromBill(StockMove.of(p, main, BigDecimal.valueOf(2))
                .unitCost(BigDecimal.valueOf(2)).sourceLine(line), "bill-" + UUID.randomUUID(), Instant.now()));
        assertThat(asOrg(() -> jdbc.sql("SELECT \"sourceLineId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"ledgerCode\" = ?").params(org, res.ledgerCode()).query(String.class).single())).isEqualTo(line);
    }

    @Test
    @DisplayName("F6 helper: a receipt is issued back exactly from its own lots, with an on-hand check, once")
    void issueBackReceipt() {
        String p = product("F6-BACK", Map.of("batchTracking", true, "costingMethod", "FIFO", "costPrice", 2));
        receive(p, main, 4, 3, m -> m.batch("OLD"));
        String bill = "bill-" + UUID.randomUUID();
        asOrg(() -> stock.receiveForDocument(StockMove.of(p, main, BigDecimal.valueOf(5)).unitCost(BigDecimal.TEN)
                .batch("NEW"), GlReceiptContext.grni("vendor_bill", bill, Instant.now())));
        issue(p, main, 2, m -> m.strategy("MANUAL").batch("NEW"));
        IssueBackReceipt req = new IssueBackReceipt("vendor_bill", bill, "vendor_bill_cancel", bill, null, null,
                "Bill cancelled", null, "Cannot cancel the bill", "Bring the goods back first.");
        assertThatThrownBy(() -> asOrg(() -> reversals.issueBackReceipt(req)))
                .hasMessageContaining("Cannot cancel the bill: only 3 of 5 units");
        // A second bill whose goods are all still there: issued back from its own lot row, once.
        String bill2 = "bill-" + UUID.randomUUID();
        asOrg(() -> stock.receiveForDocument(StockMove.of(p, main, BigDecimal.valueOf(4)).unitCost(BigDecimal.TEN)
                .batch("B2"), GlReceiptContext.grni("vendor_bill", bill2, Instant.now())));
        IssueBackReceipt req2 = new IssueBackReceipt("vendor_bill", bill2, "vendor_bill_cancel", bill2, null, null,
                "Bill cancelled", null, "Cannot cancel the bill", null);
        List<IssuedBack> back = asOrg(() -> reversals.issueBackReceipt(req2));
        assertThat(back).hasSize(1);
        assertThat(back.getFirst().quantity()).isEqualByComparingTo("4");
        assertThat(back.getFirst().totalValue()).isEqualByComparingTo("40");
        assertThat(asOrg(() -> reversals.issueBackReceipt(req2))).as("nothing left to issue back").isEmpty();
        // The caller posts the journal from the returned value (the bill cancel would credit stock against GRNI).
        asOrgRun(() -> stockPosting.postAdjustment(p, BigDecimal.ONE.negate(), back.getFirst().totalValue(),
                Instant.now(), "vendor_bill_cancel", bill2, "test: bill cancel stock credit"));
        assertThat(lotQty(p, main, "B2")).isEqualByComparingTo("0");
        assertThat(lotQty(p, main, "OLD")).as("other lots untouched").isEqualByComparingTo("4");
        green();
    }

    // ------------------------------------------------------------------ unlinked restocks

    @Test
    @DisplayName("unlinked_restocks fails for an unlinked restock written after its source type was linked")
    void unlinkedRestockAfterLinkFails() {
        AuditOrg o = newOrg();
        String saved = org;
        String savedToken = auditToken;
        String savedUser = auditUserId;
        String savedMain = main;
        org = o.id();
        auditToken = o.token();
        auditUserId = o.userId();
        main = o.main();
        try {
            String p = product(o, "UNL-NEW", Map.of("costPrice", 2));
            receive(p, main, 5, 2, null);
            StockMove.Issued out = issue(p, main, 2, m -> m.source("quarry_explosive_issue", "q1"));
            asOrgRun(() -> reversals.returnFromSource(new ReturnFromSource(IssueSource.ledgerCodes(List.of(
                    out.ledgerCode())), List.of(ReturnLine.of(p, null, BigDecimal.ONE)), "quarry_explosive_return",
                    "q1", null, null, null, null, false, "return_in")));
            JsonNode c = integrityCheck(integrityReport(), "unlinked_restocks");
            assertThat(c.get("ok").asBoolean()).as(c.toString()).isTrue();
            asOrg(() -> stock.receive(StockMove.of(p, main, BigDecimal.ONE).unitCost(BigDecimal.TWO)
                    .moveType("return_in").source("quarry_explosive_return", "q2")));
            c = integrityCheck(integrityReport(), "unlinked_restocks");
            assertThat(c.get("ok").asBoolean()).as(c.toString()).isFalse();
            assertThat(c.get("failures").asLong()).isEqualTo(1);
        } finally {
            org = saved;
            auditToken = savedToken;
            auditUserId = savedUser;
            main = savedMain;
        }
    }

    // ------------------------------------------------------------------ GL-7 (stream F request)

    BigDecimal signedValue(String p, String loc) {
        return asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(CASE WHEN \"quantityChange\" >= 0 THEN COALESCE(\"totalValue\","
                + " 0) ELSE -COALESCE(\"totalValue\", 0) END), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ? AND \"valuationComponent\" IS NULL")
                .params(org, p, loc).query(BigDecimal.class).single());
    }

    long sweeps(String p) {
        return asOrg(() -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND type = 'cost_adjustment' AND notes LIKE 'AVCO rounding residue%'", org, p));
    }

    @Test
    @DisplayName("GL-7: an issue or transfer whose caller posts the journal (skipGl) still sweeps the AVCO residue, once")
    void residueSweptWhenCallerPostsJournal() {
        String p = product("GL7-ISS", Map.of("costPrice", 1));
        receive(p, main, 1, 1, null);
        receive(p, main, 2, 2, null);
        BigDecimal posted = BigDecimal.ZERO;
        for (int q : new int[] {1, 2}) {
            StockMove.Issued out = issue(p, main, q, m -> m.moveType("internal_use").skipGl());
            // The caller's own journal (as fleet fuel, tyres, production do): the issue row's value only.
            asOrgRun(() -> stockPosting.postAdjustment(p, BigDecimal.ONE.negate(), out.totalValue(), Instant.now(),
                    "audit_caller", out.ledgerCode(), "test: caller's issue journal"));
            posted = posted.add(out.totalValue());
        }
        assertThat(posted).isEqualByComparingTo("5.000001");
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        assertThat(sweeps(p)).as("residue swept once").isEqualTo(1);
        assertThat(signedValue(p, main)).isEqualByComparingTo("0");

        String t = product("GL7-TRF", Map.of("costPrice", 1));
        receive(t, main, 1, 1, null);
        receive(t, main, 2, 2, null);
        asOrgRun(() -> stock.transfer(StockMove.transfer(t, main, alt, BigDecimal.valueOf(3)).skipGl()
                .source("audit_branch_transfer", "bt-" + UUID.randomUUID())));
        assertThat(sweeps(t)).as("source residue swept once").isEqualTo(1);
        assertThat(signedValue(t, main)).isEqualByComparingTo("0");
        green();
    }

    // ------------------------------------------------------------------ R2 races

    @Test
    @DisplayName("R2: parallel issues of the same serial — one wins, the other is refused; no 40P01")
    void parallelSameSerial() throws Exception {
        String p = serialLot("R2-SER");
        receiveSerials(p, main, "LS", sn("R2S", 1, 3), 10);
        List<Callable<StockMove.Issued>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> issue(p, main, 1, m -> m.serials(List.of("R2S-2"))));
        }
        List<Raced<StockMove.Issued>> out = race(Duration.ofSeconds(60), tasks);
        assertThat(out.stream().filter(Raced::ok).count()).as(out.toString()).isEqualTo(1);
        out.stream().filter(r -> !r.ok()).forEach(r -> assertThat(r.message())
                .containsAnyOf("not available at this location", "issued concurrently"));
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        assertThat(lotQty(p, main, "LS")).isEqualByComparingTo("2");
        green();
    }

    @Test
    @DisplayName("R2: parallel issues of the last unit with negative stock off — exactly one; no over-issue")
    void parallelLastUnit() throws Exception {
        orgSetting("inventory.allowNegativeStock", "false");
        try {
            String p = product("R2-LAST", Map.of("costPrice", 2));
            receive(p, main, 1, 2, null);
            List<Callable<StockMove.Issued>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> issue(p, main, 1, null));
            }
            List<Raced<StockMove.Issued>> out = race(Duration.ofSeconds(60), tasks);
            assertThat(out.stream().filter(Raced::ok).count()).as(out.toString()).isEqualTo(1);
            assertThat(onHand(p, main)).isEqualByComparingTo("0");
            green();
        } finally {
            orgSetting("inventory.allowNegativeStock", "true");
        }
    }

    @Test
    @DisplayName("R2: parallel reserve (ATP style) and opted-in issue — never more promised than on hand")
    void parallelReserveAndIssue() throws Exception {
        String p = product("R2-RSV", Map.of("costPrice", 2));
        receive(p, main, 3, 2, null);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String sol = "SOL-R2-" + i;
            tasks.add(() -> asOrg(() -> {
                reservations.lockCell(p, null, main);
                BigDecimal onHand = jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"locationId\" = ?").params(org, p, main).query(BigDecimal.class).single();
                BigDecimal reserved = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND status = 'active'")
                        .params(org, p, main).query(BigDecimal.class).single();
                if (onHand.subtract(reserved).compareTo(BigDecimal.ONE) < 0) {
                    throw ApiException.badRequest("nothing free to reserve");
                }
                reservations.reserve(new StockReservationService.Reserve(p, null, main, BigDecimal.ONE,
                        "sales_order_line", sol, null));
                return "reserved";
            }));
            tasks.add(() -> {
                issue(p, main, 1, m -> m.honourReservations(null));
                return "issued";
            });
        }
        List<Raced<String>> out = race(Duration.ofSeconds(60), tasks);
        long reserved = out.stream().filter(r -> "reserved".equals(r.value())).count();
        long issued = out.stream().filter(r -> "issued".equals(r.value())).count();
        assertThat(reserved + issued).as(out.toString()).isEqualTo(3);
        BigDecimal left = onHand(p, main);
        assertThat(left).isEqualByComparingTo(BigDecimal.valueOf(3 - issued));
        BigDecimal activeReserved = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND status = 'active'").params(org, p)
                .query(BigDecimal.class).single());
        assertThat(activeReserved).as("no double reservation").isLessThanOrEqualTo(left);
        asOrgRun(() -> jdbc.sql("UPDATE \"StockReservation\" SET status = 'released' WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(org, p).update());
        green();
    }
}
