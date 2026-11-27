package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.InventoryTieOutMonitor;
import com.inventoryerp.inventory.application.InventoryFeatureUsageService;
import com.inventoryerp.inventory.application.InventoryIntegrityMonitor;
import com.inventoryerp.inventory.application.InventoryIntegrityService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 0 (tasks T04 / T05 / T07): the report-only integrity checks, the feature-usage map and the R1 ledger
 * guard. Each test runs in its own isolated organisation, builds the defect where today's API reaches it (negative
 * quant, F1 post + cancel, F3 put-back + credit note, period-shifted receipt) and otherwise inserts fixture rows (never
 * an UPDATE or DELETE of the ledger), and asserts that exactly the expected report-only check flags it while the
 * report's own {@code ok} and checks are untouched.
 */
class InventoryIntegrityP0IT extends InventoryAuditSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired
    InventoryIntegrityMonitor monitor;

    /** Point the inherited fixtures (org, token, warehouses) at {@code o}. */
    void use(AuditOrg o) {
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    AuditOrg fresh() {
        AuditOrg o = newOrg();
        use(o);
        return o;
    }

    static List<String> failingKeys(JsonNode report, String section) {
        List<String> keys = new ArrayList<>();
        report.get(section).forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                keys.add(c.get("key").asString());
            }
        });
        return keys;
    }

    /** Report-only failing keys other than feature_risk (which every org that keeps stock fails). */
    static List<String> reportOnlyFailing(JsonNode report) {
        return failingKeys(report, "reportOnlyChecks").stream().filter(k -> !"feature_risk".equals(k)).toList();
    }

    /** The report has exactly {@code key} failing among the report-only checks (feature_risk aside); returns it. */
    JsonNode onlyReportOnlyFailing(String key) {
        JsonNode r = integrityReport();
        assertThat(reportOnlyFailing(r)).as(r.toString()).containsExactly(key);
        JsonNode c = integrityCheck(r, key);
        assertThat(c.get("failures").asLong()).isPositive();
        assertThat(c.get("rows").size()).isPositive();
        return c;
    }

    Map<String, Object> ledgerFixture(String productId, String loc, String type, Object qty, String refType,
            String reverses, String serialId) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", org);
        cols.put("ledgerCode", "P0-" + UUID.randomUUID().toString().substring(0, 8));
        cols.put("productId", productId);
        cols.put("locationId", loc);
        cols.put("type", type);
        cols.put("qtyBefore", BigDecimal.ZERO);
        cols.put("quantityChange", dec(qty));
        cols.put("balanceAfter", dec(qty));
        cols.put("unitCost", BigDecimal.ZERO);
        cols.put("totalValue", BigDecimal.ZERO);
        cols.put("referenceType", refType);
        cols.put("referenceId", "P0-" + UUID.randomUUID());
        if (reverses != null) {
            cols.put("reversesLedgerId", reverses);
        }
        if (serialId != null) {
            cols.put("serialId", serialId);
        }
        cols.put("createdAt", now());
        return cols;
    }

    /** Insert a ledger fixture row in the audit org's tenant transaction (INSERT only). */
    String insertLedger(Map<String, Object> cols) {
        return asOrg(() -> insert("InventoryLedger", cols));
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, org, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, supplier, !supplier).update();
        return id;
    }

    Map<String, String> keyed() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, keyed());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String today() {
        return LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString();
    }

    /** A draft invoice of {@code qty} of a tracked product, handed to a delivery order from {@code main}. */
    record Delivered(String invoice, String deliveryOrder, String product) {}

    Delivered invoiceWithDeliveryOrder(int qty) {
        String customer = partner("Customer", false);
        String p = product("DLV");
        receive(p, 50, 100);
        String invoice = okA(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", p, "quantity", qty, "unitPrice", 200)))).get("id").asString();
        JsonNode dO = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", invoice,
                "locationId", main));
        assertThat(dO.get("stockMode").asString()).isEqualTo("dispatch");
        return new Delivered(invoice, dO.get("id").asString(), p);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("clean organisation: report-only checks listed separately and green (feature_risk aside); ok and"
            + " checks unchanged; GL ties out")
    void cleanScenario() {
        fresh();
        String plain = product("PLAIN");
        String lot = product("LOT", Map.of("batchTracking", true));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(plain, 10, 5))));
        Map<String, Object> lotLine = item(lot, 4, 3);
        lotLine.put("batchNumber", "L1");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(lotLine)));
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(plain, 3, null))));

        JsonNode r = integrityReport();
        List<String> keys = new ArrayList<>();
        r.get("checks").forEach(c -> keys.add(c.get("key").asString()));
        assertThat(keys).containsExactlyElementsOf(InventoryIntegrityService.CHECKS);
        List<String> ro = new ArrayList<>();
        r.get("reportOnlyChecks").forEach(c -> ro.add(c.get("key").asString()));
        assertThat(ro).containsExactlyElementsOf(InventoryIntegrityService.REPORT_ONLY_CHECKS);
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(failingKeys(r, "reportOnlyChecks")).as(r.get("reportOnlyChecks").toString())
                .containsExactly("feature_risk");
        assertThat(r.get("reportOnlyOk").asBoolean()).isFalse();
        assertThat(integrityCheck(r, "unlinked_restocks").get("rows")).isEmpty();
        assertThat(integrityCheck(r, "gl_tieout_by_account").get("skipped").asBoolean()).isFalse();
        assertIntegrityGreen();
        assertIntegrityGreen(true, "feature_risk");
        assertGlTieOut();

        // A location filter skips the organisation-level report-only checks.
        JsonNode scoped = okA(200, "GET", "/inventory/reports/integrity?locationId=" + main, null);
        for (String k : List.of("gl_tieout_by_account", "stock_posting_jobs", "feature_risk")) {
            assertThat(integrityCheck(scoped, k).get("skipped").asBoolean()).as(k).isTrue();
        }
        assertThat(reportOnlyFailing(scoped)).isEmpty();
        assertThat(count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?", org)).isZero();
    }

    @Test
    @DisplayName("negative quants: an issue past on-hand (negative stock allowed) is listed; core checks stay green")
    void negativeQuants() {
        fresh();
        String p = product("NEG");
        receive(p, 2, 10);
        issue(p, main, 5, null);
        JsonNode c = onlyReportOnlyFailing("negative_quants");
        assertThat(c.get("rows").get(0).get("productId").asString()).isEqualTo(p);
        assertThat(c.get("rows").get(0).get("quantity").asString()).isEqualTo("-3");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("issued serial located + over-returned: a reversal of a serial's issue row (fixture) and an issue"
            + " returned twice its quantity")
    void serialAndOverReturn() {
        fresh();
        String serial = product("SER", Map.of("serialTracking", true));
        asOrgRun(() -> stock.receiveForDocument(StockMove.of(serial, main, new BigDecimal("2"))
                .unitCost(new BigDecimal("5")).serials(List.of("S-1", "S-2")), new GlReceiptContext("audit_seed",
                        "seed-" + UUID.randomUUID(), Instant.now(), null)));
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(serial, 1, null))));
        Map<String, Object> issueRow = asOrg(() -> jdbc.sql("SELECT id, \"serialId\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"quantityChange\" < 0").params(org, serial)
                .query().singleRow());
        assertThat(reportOnlyFailing(integrityReport())).isEmpty();

        // The unit comes back against its issue row without its serial: the serial still says issued.
        insertLedger(ledgerFixture(serial, main, "return_in", 1, "sales_return", (String) issueRow.get("id"), null));
        JsonNode c = onlyReportOnlyFailing("issued_serial_located");
        assertThat(c.get("rows").get(0).get("serialId").asString()).isEqualTo(issueRow.get("serialId"));
        assertThat(c.get("rows").get(0).get("returnedQuantity").asString()).isEqualTo("1");

        // A second return of the same issue row: returned 2 of 1.
        String plain = product("RET");
        receive(plain, 5, 10);
        String issued = issue(plain, main, 3, null).ledgerCode();
        String issueId = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"ledgerCode\" = ? AND \"productId\" = ?").params(org, issued, plain).query(String.class).single());
        String back = insertLedger(ledgerFixture(plain, main, "return_in", 3, "credit_note", issueId, null));
        // Taken back by its cancel: net 0 for this row, so not yet over-returned.
        insertLedger(ledgerFixture(plain, main, "issue", -3, "credit_note_cancel", back, null));
        insertLedger(ledgerFixture(plain, main, "return_in", 2, "credit_note", issueId, null));
        assertThat(failingKeys(integrityReport(), "reportOnlyChecks")).doesNotContain("over_returned");
        insertLedger(ledgerFixture(plain, main, "return_in", 2, "sales_return", issueId, null));
        JsonNode over = integrityCheck(integrityReport(), "over_returned");
        assertThat(over.get("ok").asBoolean()).isFalse();
        assertThat(over.get("rows").get(0).get("ledgerId").asString()).isEqualTo(issueId);
        assertThat(over.get("rows").get(0).get("reversedQuantity").asString()).isEqualTo("4");
        assertThat(over.get("rows").get(0).get("excess").asString()).isEqualTo("1");
    }

    @Test
    @DisplayName("unlinked restocks (informational): restock rows without reversesLedgerId are counted per source type,"
            + " the check stays ok")
    void unlinkedRestocks() {
        fresh();
        String p = product("UNL");
        receive(p, 5, 10);
        insertLedger(ledgerFixture(p, main, "return_in", 1, "pos_refund", null, null));
        insertLedger(ledgerFixture(p, main, "return_in", 2, "pos_refund", null, null));
        insertLedger(ledgerFixture(p, main, "return_to_supplier", -1, "vendor_bill_cancel", null, null));
        // Not a restock source, and a linked restock: neither is counted.
        insertLedger(ledgerFixture(p, main, "adjustment_in", 1, "integrity_probe", null, null));
        JsonNode c = integrityCheck(integrityReport(), "unlinked_restocks");
        assertThat(c.get("ok").asBoolean()).isTrue();
        assertThat(c.get("failures").asLong()).isZero();
        Map<String, String> byType = new LinkedHashMap<>();
        c.get("rows").forEach(r -> byType.put(r.get("referenceType").asString() + "/" + r.get("direction").asString(),
                r.get("rows").asString() + "/" + r.get("quantity").asString()));
        assertThat(byType).containsExactly(Map.entry("pos_refund/inflow", "2/3"),
                Map.entry("vendor_bill_cancel/outflow", "1/1"));
        assertThat(c.get("note").asString()).startsWith("3 unlinked row(s)");
    }

    @Test
    @DisplayName("stock-posting jobs: failed and stale pending jobs are listed; fresh and abandoned ones are not")
    void stockPostingJobs() {
        fresh();
        Instant old = Instant.now().minus(InventoryIntegrityService.STOCK_JOB_MAX_AGE).minus(1, ChronoUnit.HOURS);
        String failed = job("failed", Instant.now());
        String stale = job("pending", old);
        job("pending", Instant.now());
        job("abandoned", old);
        JsonNode c = onlyReportOnlyFailing("stock_posting_jobs");
        List<String> ids = new ArrayList<>();
        c.get("rows").forEach(r -> ids.add(r.get("jobId").asString()));
        assertThat(ids).containsExactlyInAnyOrder(failed, stale);
    }

    String job(String status, Instant createdAt) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", org);
        cols.put("status", status);
        cols.put("idempotencyKey", "p0-" + UUID.randomUUID());
        cols.put("lastError", "status " + status);
        cols.put("createdAt", Timestamp.from(createdAt));
        return asOrg(() -> insert("StockPostingJob", cols));
    }

    @Test
    @DisplayName("transit age: a transit transfer dispatched more than 7 days ago is listed")
    void transitAge() {
        fresh();
        String p = product("TRN");
        receive(p, 8, 3);
        String id = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt, "mode",
                "transit", "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of(
                        "productId", p, "qtyRequested", 5)))).get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(reportOnlyFailing(integrityReport())).isEmpty();
        // Document fixture (not the ledger): the dispatch happened 8 days ago.
        asOrgRun(() -> jdbc.sql("UPDATE \"StockTransfer\" SET \"dispatchedAt\" = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)), id).update());
        JsonNode c = onlyReportOnlyFailing("transit_age");
        assertThat(c.get("rows").get(0).get("transferId").asString()).isEqualTo(id);
        assertThat(c.get("rows").get(0).get("ageDays").asInt()).isEqualTo(8);
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("F1 detector: posted invoice handed to a delivery order that is then cancelled — no stock issued")
    void invoiceUndelivered() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(5);
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        // The delivery order is still open: the invoice waits for its delivery, not a finding.
        assertThat(reportOnlyFailing(integrityReport())).isEmpty();
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/cancel", Map.of("reason",
                "Customer collects"));
        // IPR F1: the posted invoice goes back to awaiting delivery (listed until a new delivery or a collection).
        assertThat(jdbc.sql("SELECT data->>'stockAwaitingDelivery' FROM \"Document\" WHERE id = ?").param(d.invoice())
                .query(String.class).optional().orElse(null)).as("the posted invoice awaits delivery").isNotNull();
        JsonNode c = onlyReportOnlyFailing("invoice_undelivered");
        JsonNode row = c.get("rows").get(0);
        assertThat(row.get("invoiceId").asString()).isEqualTo(d.invoice());
        assertThat(row.get("productId").asString()).isEqualTo(d.product());
        assertThat(row.get("undeliveredQuantity").asString()).isEqualTo("5");
        assertThat(row.get("deliveredQuantity").asString()).isEqualTo("0");
        assertThat(row.get("deliveryOrderStatuses").asString()).isEqualTo("cancelled");
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("50");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("F3 detector: a put-back written before IPR F3 (no reversal link) plus a full credit-note restock"
            + " return more than the trip issued")
    void deliveryReturnOverlap() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String transporter = partner("Haulier", true);
        String trip = okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/plan-loads",
                Map.of("payloadT", 20)).get("createdTripIds").get(0).asString();
        String rate = okA(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name",
                "Run " + UUID.randomUUID().toString().substring(0, 6), "basis", "per_trip", "rate", 1000)).get("id")
                .asString();
        okA(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "transporterRateId", rate, "transporterPlate", "UAX 123B", "transporterDriverName", "Okello"));
        JsonNode dispatched = money("/fleet/trips/" + trip + "/dispatch", null);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("loadLineId", dispatched.get("lines").get(0).get("id").asString());
        line.put("deliveredQty", 10);
        line.put("returnedQty", 0);
        money("/fleet/trips/" + trip + "/proof-of-delivery", Map.of("receiverName", "Site agent", "latitude", 0.3137,
                "longitude", 32.5812, "signature", PNG, "lines", List.of(line)));
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        assertThat(reportOnlyFailing(integrityReport())).as("10 delivered: consistent").isEmpty();

        // The customer is credited the full 10: the restock returns 10 against the trip's issue.
        String cn = okA(200, "POST", "/credit-notes", Map.of("partnerId", jdbc.sql("SELECT \"partnerId\" FROM"
                + " \"Document\" WHERE id = ?").param(d.invoice()).query(String.class).single(), "issueDate", today(),
                "reversedDocumentId", d.invoice(), "lines", List.of(Map.of("productId", d.product(), "quantity", 10,
                        "unitPrice", 200)))).get("id").asString();
        okA(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("50");
        assertThat(reportOnlyFailing(integrityReport())).as("a linked restock alone is consistent").isEmpty();

        // A put-back of 2 as written before IPR F3: no reversal link (fixture row, INSERT only).
        Map<String, Object> legacy = ledgerFixture(d.product(), main, "return_in", 2, "fleet_trip_return", null, null);
        legacy.put("referenceId", trip);
        insertLedger(legacy);
        JsonNode c = onlyReportOnlyFailing("delivery_return_overlap");
        JsonNode row = c.get("rows").get(0);
        assertThat(row.get("deliveryGroup").asString()).isEqualTo("invoice:" + d.invoice());
        assertThat(row.get("issuedQuantity").asString()).isEqualTo("10");
        assertThat(row.get("putBackQuantity").asString()).isEqualTo("2");
        assertThat(row.get("linkedReturnQuantity").asString()).isEqualTo("10");
        assertThat(row.get("excess").asString()).isEqualTo("2");
    }

    @Test
    @DisplayName("quality hold: a quality lot on hold whose stock never moved to QC quarantine is listed")
    void qualityHoldUnquarantined() {
        fresh();
        String p = product("QH");
        receive(p, 6, 10);
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", org);
        cols.put("lotNumber", "QL-" + UUID.randomUUID().toString().substring(0, 8));
        cols.put("stage", "incoming");
        cols.put("productId", p);
        cols.put("quantity", new BigDecimal("6"));
        cols.put("locationId", main);
        cols.put("status", "hold");
        String held = asOrg(() -> insert("QualityInspectionLot", cols));
        cols.put("lotNumber", "QL-" + UUID.randomUUID().toString().substring(0, 8));
        cols.put("status", "open");
        asOrg(() -> insert("QualityInspectionLot", cols));
        JsonNode c = onlyReportOnlyFailing("quality_hold_unquarantined");
        assertThat(c.get("failures").asLong()).isEqualTo(1);
        assertThat(c.get("rows").get(0).get("lotId").asString()).isEqualTo(held);
        assertThat(c.get("rows").get(0).get("onHandAtLocation").asString()).isEqualTo("6");
    }

    @Test
    @DisplayName("GL tie-out by account: an inventory account holding a balance without stock is listed in base currency")
    void glTieOutByAccount() {
        fresh();
        String p = product("GLA");
        receive(p, 4, 25);
        assertGlTieOut();
        // A credit-balance account (the receipts' contra account) named as a product's inventory account: the GL side
        // of that account moves, its valuation does not.
        String contra = asOrg(() -> jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                + " GROUP BY 1 HAVING sum(\"baseDebit\" - \"baseCredit\") < -1 ORDER BY 1 LIMIT 1").param(org)
                .query(String.class).single());
        product("GLA-EMPTY", Map.of("inventoryAccountOverrideId", contra));
        JsonNode c = integrityCheck(integrityReport(), "gl_tieout_by_account");
        assertThat(c.get("ok").asBoolean()).as(c.toString()).isFalse();
        assertThat(c.get("rows").get(0).get("accountId").asString()).isEqualTo(contra);
        assertThat(c.get("rows").get(0).get("glBaseValue").asString()).isEqualTo("-100");
        assertThat(c.get("rows").get(0).get("difference").asString()).isEqualTo("100");
    }

    @Test
    @DisplayName("period shifted (informational): a receipt journal dated last year, recorded this year")
    void periodShifted() {
        fresh();
        String p = product("PS");
        String sid = "ps-" + UUID.randomUUID();
        Instant lastYear = Instant.now().minus(Duration.ofDays(400));
        asOrgRun(() -> stock.receiveForDocument(StockMove.of(p, main, new BigDecimal("3")).unitCost(BigDecimal.TEN)
                .source("audit_seed", sid), new GlReceiptContext("audit_seed", sid, lastYear, null)));
        JsonNode c = integrityCheck(integrityReport(), "period_shifted");
        assertThat(c.get("ok").asBoolean()).isTrue();
        int moved = Year.now().getValue();
        int posted = lastYear.atZone(com.inventoryerp.kernel.common.BusinessZone.get()).getYear();
        if (moved == posted) {
            assertThat(c.get("rows")).as("same fiscal year: not shifted").isEmpty();
            return;
        }
        assertThat(c.get("rows").size()).as(c.toString()).isEqualTo(1);
        JsonNode row = c.get("rows").get(0);
        assertThat(row.get("referenceType").asString()).isEqualTo("audit_seed");
        assertThat(row.get("movedPeriod").asString()).isEqualTo("FY" + moved);
        assertThat(row.get("postedPeriod").asString()).isEqualTo("FY" + posted);
        assertThat(row.get("rows").asString()).isEqualTo("1");
    }

    @Test
    @DisplayName("feature usage: GET /inventory/reports/feature-usage maps used features and the P2s they promote;"
            + " 403 without inventory_valuation:read")
    void featureUsage() {
        fresh();
        JsonNode empty = okA(200, "GET", "/inventory/reports/feature-usage", null);
        assertThat(empty.get("organizationId").asString()).isEqualTo(org);
        assertThat(empty.get("promotedP2s")).as("nothing used yet").isEmpty();
        Map<String, Boolean> before = inUse(empty);
        assertThat(before.keySet()).containsExactlyInAnyOrderElementsOf(InventoryFeatureUsageService.featureKeys());
        // The audit organisation starts with two warehouses (MAIN, ALT) and the seeded catalogue, without stock.
        assertThat(before.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList())
                .contains("multi_warehouse").isSubsetOf("multi_warehouse", "avco_products");

        String serial = product("FU-SER", Map.of("serialTracking", true));
        receive(serial, main, 1, 5, m -> m.serials(List.of("FU-1")));
        orgSetting("inventory.allowNegativeStock", "false");
        JsonNode used = okA(200, "GET", "/inventory/reports/feature-usage", null);
        Map<String, Boolean> after = inUse(used);
        assertThat(after).containsEntry("serial_products", true).containsEntry("inventory_in_use", true)
                .containsEntry("negative_stock_blocked", true).containsEntry("csv_exports", true)
                .containsEntry("lot_products", false).containsEntry("fleet_delivery_orders", false);
        List<String> p2 = new ArrayList<>();
        used.get("promotedP2s").forEach(x -> p2.add(x.get("id").asString()));
        assertThat(p2).containsExactly("C-4", "C-8", "C-9", "S13", "S-6", "S-8");

        // The report-only feature_risk check lists the same P2s.
        JsonNode risk = integrityCheck(integrityReport(), "feature_risk");
        List<String> rows = new ArrayList<>();
        risk.get("rows").forEach(x -> rows.add(x.get("p2").asString()));
        assertThat(rows).containsExactlyElementsOf(p2);

        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, permissions, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(role, org, "Reader " + role.substring(0, 6), new String[] {"inventory:read"})
                .update();
        String email = "fu-" + role.substring(0, 8) + "@audit.test";
        createUser(org, email, "Reader@12345", role);
        assertThat(call("GET", "/inventory/reports/feature-usage", null, login(orgCode, email, "Reader@12345"))
                .status()).isEqualTo(403);
        assertThat(call("GET", "/inventory/reports/feature-usage", null, (String) null).status()).isEqualTo(401);
    }

    static Map<String, Boolean> inUse(JsonNode usage) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        usage.get("features").forEach(f -> m.put(f.get("key").asString(), f.get("inUse").asBoolean()));
        return m;
    }

    @Test
    @DisplayName("P2 map: every feature a P2 names exists; every report-only kind stays out of the value kinds")
    void staticMaps() {
        for (InventoryFeatureUsageService.P2Rule rule : InventoryFeatureUsageService.P2_FEATURES) {
            rule.anyOf().forEach(g -> assertThat(InventoryFeatureUsageService.featureKeys()).as(rule.id())
                    .containsAll(g));
        }
        for (String k : InventoryIntegrityService.REPORT_ONLY_CHECKS) {
            assertThat(InventoryTieOutMonitor.INTEGRITY_VALUE_KINDS).doesNotContain(InventoryIntegrityMonitor.kind(k));
            assertThat(InventoryIntegrityService.CHECKS).doesNotContain(k);
        }
    }

    @Test
    @DisplayName("monitor: report-only findings are raised as integrity_<key> once, and never hold a period close")
    void monitorAndPeriodClose() {
        fresh();
        String p = product("MON");
        receive(p, 1, 10);
        issue(p, main, 2, null);
        TenantStore store = TenantStore.organization(org);
        InventoryIntegrityMonitor.Result first = TenantContext.run(store, monitor::check);
        assertThat(first.raised()).isEmpty();
        assertThat(first.reportOnlyRaised()).containsExactly("integrity_negative_quants", "integrity_feature_risk");
        assertThat(TenantContext.run(store, monitor::check).reportOnlyRaised()).isEmpty();
        assertThat(count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND status = 'open'",
                org)).isEqualTo(2);

        // Every report-only kind open in an old period: the checklist and the close ignore them.
        String period = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'P0 2008-07', '2008-07-01', '2008-07-31 23:59:59', 'open', now())")
                .params(period, org).update();
        for (String k : InventoryIntegrityService.REPORT_ONLY_CHECKS) {
            jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                    + " status, \"createdAt\") VALUES (?, ?, 'p0', 0, ?, 'p0', 'open', '2008-07-03 03:10:00')")
                    .params(UUID.randomUUID().toString(), org, InventoryIntegrityMonitor.kind(k)).update();
        }
        JsonNode checklist = okA(200, "GET", "/fiscal-periods/" + period + "/close-checklist", null);
        List<String> blocking = new ArrayList<>();
        checklist.get("checks").forEach(c -> {
            if (List.of("inventory_exceptions", "inventory_integrity_value").contains(c.get("key").asString())) {
                assertThat(c.get("ok").asBoolean()).as(c.toString()).isTrue();
                blocking.add(c.get("key").asString());
            }
        });
        assertThat(blocking).containsExactlyInAnyOrder("inventory_exceptions", "inventory_integrity_value");
        Res close = call("POST", "/fiscal-periods/" + period + "/close", null, keyed());
        assertThat(close.status()).as(close.raw().body()).isEqualTo(200);
        assertThat(call("POST", "/fiscal-periods/" + period + "/reopen", null, keyed()).status()).isEqualTo(200);
        assertThat(onHand(p, main)).as("the monitor corrected nothing").isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("R1: the append-only trigger on InventoryLedger exists and is enabled")
    void ledgerGuardEnabled() {
        List<String> state = jdbc.sql("SELECT t.tgenabled::text FROM pg_trigger t WHERE t.tgname ="
                + " 'inventory_ledger_append_only' AND t.tgrelid = '\"InventoryLedger\"'::regclass AND NOT t.tgisinternal")
                .query(String.class).list();
        // 'O' = fires in origin (normal) mode; 'D' would be disabled.
        assertThat(state).containsExactly("O");
        String fn = jdbc.sql("SELECT p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid WHERE t.tgname ="
                + " 'inventory_ledger_append_only'").query(String.class).single();
        assertThat(fn).isEqualTo("guard_inventory_ledger");
    }

    @Test
    @DisplayName("race helper: racers start together, every outcome is collected, a failing racer does not hide others")
    void raceHelper() throws Exception {
        fresh();
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("RACE", Map.of("stockPolicy", "block"));
        receive(p, 2, 10);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> issue(p, main, 1, null).ledgerCode());
        }
        List<Raced<String>> out = race(Duration.ofSeconds(60), tasks);
        assertThat(out).hasSize(4);
        assertThat(out.stream().filter(Raced::ok).count()).isEqualTo(2);
        assertThat(out.stream().filter(r -> !r.ok()).map(Raced::message).toList())
                .allMatch(m -> m.startsWith("Insufficient stock for "));
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        orgSetting("inventory.allowNegativeStock", "true");
        assertIntegrityGreen();
    }
}
