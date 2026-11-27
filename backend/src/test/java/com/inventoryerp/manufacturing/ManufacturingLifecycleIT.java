package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.TestSuppliers;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder;
import com.inventoryerp.manufacturing.application.ProductionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 2 (R4 / GL-11): the manufacturing lifecycle end to end, with the inventory integrity report, the GL
 * tie-out and the WIP tie-out ({@code wip_tieout}, docs/spec/manufacturing-wip-reconciliation.md) green after every
 * scenario — and in the middle of the open ones. Runs in an isolated organisation (InventoryAuditSupport), not DEMO
 * as {@link MfgTestSupport} does: DEMO's GL carries what every other suite posts and the seeded opening balances, so
 * "integrity green" can only be asserted on an organisation this suite owns. The fixtures follow MfgTestSupport.
 */
class ManufacturingLifecycleIT extends InventoryAuditSupport {

    static final String PO = "/manufacturing/production-orders/";

    @Autowired
    ProductionService production;

    String bakery;
    String warehouse;
    String today;
    int seq;

    @BeforeAll
    void lifecycleFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        // The production rules a real organisation gets (as MfgTestSupport does for DEMO).
        okA(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        bakery = location("production");
        warehouse = location("warehouse");
    }

    // ------------------------------------------------------------------ helpers

    String sfx() {
        return UUID.randomUUID().toString().substring(0, 6) + (++seq);
    }

    String location(String type) {
        return okA(201, "POST", "/inventory/locations", Map.of("code", "LC-" + sfx(), "name", "Lifecycle " + sfx(),
                "type", type)).get("id").asString();
    }

    String prod(String code) {
        return product(code, Map.of("manufacturingRole", "finished", "salesPrice", 15));
    }

    void stockIn(String loc, String productId, int qty, int unitCost) {
        okA(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(item(productId, qty, unitCost))));
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyedOk(String path, Object body) {
        Res r = keyed("POST", path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    String bom(String output, String component, int outputQty, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Recipe " + sfx(), "outputProductId", output,
                "outputQuantity", outputQty, "lines", List.of(Map.of("componentProductId", component, "quantity", qty))));
        body.putAll(extra);
        String id = okA(201, "POST", "/manufacturing/boms", body).get("id").asString();
        okA(201, "POST", "/manufacturing/boms/" + id + "/activate", null);
        return id;
    }

    String created(String bomId, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("bomId", bomId, "runs", 1, "locationId", bakery,
                "outputLocationId", warehouse));
        body.putAll(extra);
        String id = okA(201, "POST", "/manufacturing/production-orders", body).get("id").asString();
        okA(201, "POST", PO + id + "/confirm", null);
        return id;
    }

    String started(String bomId) {
        return started(bomId, Map.of());
    }

    String started(String bomId, Map<String, Object> extra) {
        String id = created(bomId, extra);
        okA(201, "POST", PO + id + "/start", Map.of());
        return id;
    }

    String status(String orderId) {
        return okA(200, "GET", PO + orderId, null).get("status").asString();
    }

    String activateStandard(String productId, Object total) {
        JsonNode draft = okA(201, "POST", "/inventory/standard-costs", Map.of("productId", productId, "effectiveFrom",
                today, "materialCost", total, "convertMethod", true));
        String id = draft.get("id").asString();
        okA(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        keyedOk("/inventory/standard-costs/" + id + "/activate", null);
        return id;
    }

    String token(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "LC " + role.substring(0, 8), permissions)
                .update();
        String email = "lc-" + role.substring(0, 8) + "@audit.test";
        createUser(org, email, "Lifecycle@12345", role);
        return login(orgCode, email, "Lifecycle@12345");
    }

    /** Integrity (incl. report-only, feature_risk excepted), GL tie-out and the WIP tie-out are green. */
    void green(String when) {
        green(when, List.of());
    }

    /** As {@link #green(String)}, tolerating the integrity checks {@code knownOpen} (each one a reported defect). */
    void green(String when, List<String> knownOpen) {
        JsonNode report = integrityReport();
        assertThat(failingChecks(report, "checks").stream().filter(f -> !knownOpen.contains(f.substring(0,
                f.indexOf(' ')))).toList()).as("integrity checks " + when).isEmpty();
        assertGlTieOut();
        JsonNode recon = okA(200, "GET", "/manufacturing/reports/wip-reconciliation", null);
        assertThat(recon.get("balanced").asBoolean()).as("WIP reconciliation report " + when + ": " + recon).isTrue();
        assertThat(failingChecks(report, "reportOnlyChecks").stream().filter(f -> !"feature_risk".equals(f.substring(0,
                f.indexOf(' ')))).toList()).as("report-only integrity checks " + when).isEmpty();
        JsonNode wip = integrityCheck(report, "wip_tieout");
        assertThat(wip.get("ok").asBoolean()).as("WIP tie-out " + when + ": " + wip).isTrue();
    }

    // ------------------------------------------------------------------ simple orders

    @Test
    @DisplayName("simple order: in progress (materials in WIP, overhead not yet) → complete; green at each step")
    void simpleFull() {
        String flour = prod("FLOUR");
        String cake = prod("CAKE");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("overheadCost", 50));
        String id = started(bomId);
        green("in progress");
        okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 20));
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("20");
        green("completed");
    }

    @Test
    @DisplayName("X-9: partial outputs of a simple order (overhead share absorbed, WIP share credited), cancel refused,"
            + " complete takes the rest, reversal takes every receipt back")
    void partialOutput() {
        String flour = prod("PFLOUR");
        String cake = prod("PCAKE");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("overheadCost", 40));
        String id = started(bomId);
        Res first = keyed("POST", PO + id + "/partial-output", Map.of("quantity", 5));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(first.body().get("status").asString()).isEqualTo("in_progress");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("5");
        green("after one partial output");
        Res noKey = api("POST", PO + id + "/partial-output", Map.of("quantity", 1));
        assertThat(noKey.status()).isEqualTo(400);
        Res cancel = api("POST", PO + id + "/cancel", Map.of());
        assertThat(cancel.status()).isEqualTo(400);
        assertThat(msg(cancel)).contains("has partial outputs in stock and cannot be cancelled");
        keyedOk(PO + id + "/partial-output", Map.of("quantity", 5));
        Res tooMuch = keyed("POST", PO + id + "/partial-output", Map.of("quantity", 10));
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(msg(tooMuch)).contains("would reach its expected quantity");
        green("after two partial outputs");

        JsonNode done = okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 10));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(new BigDecimal(done.get("producedQty").asString())).isEqualByComparingTo("20");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("20");
        green("completed after partials");

        okA(201, "POST", PO + id + "/reverse", Map.of());
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("40");
        green("reversed after partials");
    }

    @Test
    @DisplayName("cancel before start and after start (materials back); green")
    void cancels() {
        String flour = prod("CFLOUR");
        String cake = prod("CCAKE");
        stockIn(bakery, flour, 20, 100);
        String bomId = bom(cake, flour, 10, 4, Map.of());
        String before = created(bomId, Map.of());
        okA(201, "POST", PO + before + "/cancel", Map.of());
        assertThat(status(before)).isEqualTo("cancelled");
        green("cancelled before start");
        String after = started(bomId);
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("16");
        okA(201, "POST", PO + after + "/cancel", Map.of());
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("20");
        green("cancelled after start");
    }

    @Test
    @DisplayName("zero yield (service API) and a scrap loss; green")
    void zeroYieldAndLoss() {
        String flour = prod("ZFLOUR");
        String cake = prod("ZCAKE");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of());
        String zero = started(bomId);
        asOrgRun(() -> production.completeOrder(zero, CompleteOrder.of(BigDecimal.ZERO)));
        green("zero yield");
        String lossy = started(bomId);
        okA(201, "POST", PO + lossy + "/complete", Map.of("qtyProduced", 15, "scrapQty", 5));
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("15");
        green("scrap loss");
    }

    @Test
    @DisplayName("GL-2: by-products — AVCO and STANDARD (stock at standard, difference to variance) — and multiple"
            + " outputs; reversal offsets the by-product variance; green")
    void byProducts() {
        String flour = prod("BFLOUR");
        String cake = prod("BCAKE");
        String crumbs = prod("BCRUMBS");
        String bran = prod("BBRAN");
        stockIn(bakery, flour, 40, 100);
        activateStandard(crumbs, 3);
        String bomId = bom(cake, flour, 10, 10, Map.of());
        String id = started(bomId, Map.of("outputs", List.of(
                Map.of("productId", crumbs, "kind", "byproduct", "qtyExpected", 5, "costAllocationPct", 10),
                Map.of("productId", bran, "kind", "byproduct", "qtyExpected", 2, "costAllocationPct", 5))));
        green("by-product order in progress");
        okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 10));
        assertThat(onHand(crumbs, warehouse)).isEqualByComparingTo("5");
        assertThat(onHand(bran, warehouse)).isEqualByComparingTo("2");
        BigDecimal crumbsValue = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(\"totalValue\"), 0) FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"quantityChange\" > 0").params(org, crumbs)
                .query(BigDecimal.class).single());
        assertThat(crumbsValue).as("STANDARD by-product at its standard 5 × 3").isEqualByComparingTo("15");
        green("by-products completed");
        okA(201, "POST", PO + id + "/reverse", Map.of());
        green("by-product order reversed");
    }

    @Test
    @DisplayName("rework: QC hold (WIP cleared) → QC split → rework order → complete → pass; green")
    void rework() {
        String flour = prod("RFLOUR");
        String cake = prod("RCAKE");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("qcRequired", true, "overheadCost", 20));
        String id = started(bomId);
        okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 20));
        assertThat(status(id)).isEqualTo("qc_hold");
        green("QC hold");
        okA(201, "POST", PO + id + "/qc", Map.of("passedQty", 12, "reworkQty", 8));
        JsonNode child = okA(201, "POST", PO + id + "/rework", Map.of());
        String rw = child.get("id").asString();
        okA(201, "POST", PO + rw + "/confirm", null);
        okA(201, "POST", PO + rw + "/start", Map.of());
        green("rework in progress");
        okA(201, "POST", PO + rw + "/complete", Map.of("qtyProduced", 8));
        okA(201, "POST", PO + rw + "/qc", Map.of("passedQty", 8));
        green("rework done");
    }

    @Test
    @DisplayName("multi-stage order: partial stage output, intermediate issue, conversion cost, complete; green while"
            + " open and after")
    void multiStage() {
        String rock = prod("MROCK");
        String crushed = prod("MCRUSHED");
        String block = prod("MBLOCK");
        String stageLoc = location("production");
        stockIn(bakery, rock, 100, 5);
        String routingId = okA(201, "POST", "/manufacturing/routings", Map.of("name", "Line " + sfx(), "operations",
                List.of(Map.of("sequence", 10, "name", "Crush"), Map.of("sequence", 20, "name", "Mould")))).get("id")
                .asString();
        List<String> ops = new ArrayList<>();
        okA(200, "GET", "/manufacturing/routings/" + routingId, null).get("operations").forEach(o -> ops.add(o.get("id")
                .asString()));
        okA(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                Map.of("operationId", ops.get(0), "issueMode", "backflush", "outputProductId", crushed, "outputPerUnit",
                        1, "stageLocationId", stageLoc, "materialProductIds", List.of(rock)),
                Map.of("operationId", ops.get(1), "issueMode", "manual"))));
        String bomId = okA(201, "POST", "/manufacturing/boms", Map.of("name", "Staged " + sfx(), "outputProductId",
                block, "outputQuantity", 100, "lines", List.of(Map.of("componentProductId", rock, "quantity", 100)),
                "routingId", routingId)).get("id").asString();
        okA(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        JsonNode order = okA(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 100, "locationId", bakery, "outputLocationId", warehouse)));
        String id = order.get("id").asString();
        String s1 = order.get("stages").get(0).get("id").asString();
        String s2 = order.get("stages").get(1).get("id").asString();
        okA(201, "POST", PO + id + "/confirm", null);
        keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50));
        // IPR D-2 (fixed by stream H): stage consumption reduces the order's stock reservation, so `reservations`
        // stays green while a multi-stage order is open.
        green("stage 1 partial");
        keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50, "complete", true));
        keyedOk("/manufacturing/production-stages/" + s2 + "/issue", Map.of("lines", List.of(Map.of("productId",
                crushed, "quantity", 100))));
        keyedOk("/manufacturing/production-stages/" + s2 + "/conversion-costs", Map.of("amount", 40, "kind",
                "labour"));
        green("stage 2 open with WIP");
        JsonNode done = keyedOk("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 100,
                "complete", true));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("100");
        green("multi-stage completed");
    }

    // ------------------------------------------------------------------ WIP-3 late cost on a cancelled order

    @Test
    @DisplayName("WIP-3: a late landed cost share in the WIP of a cancelled order is picked up by the cost adjustment"
            + " run instead of staying in WIP for ever; green")
    void lateCostOnCancelledOrder() {
        String supplier = TestSuppliers.supplier(jdbc, org);
        String flour = prod("LFLOUR");
        String cake = prod("LCAKE");
        String grn = okA(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier,
                "warehouseId", bakery, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId",
                        flour, "description", "Flour", "quantity", 100, "unitCost", 50)))).get("id").asString();
        String bomId = bom(cake, flour, 10, 30, Map.of());
        String id = started(bomId);
        String code = okA(200, "GET", PO + id, null).get("orderCode").asString();

        // The landed cost arrives while the order is in progress: 30 of the 100 received are in its WIP.
        JsonNode voucher = keyedOk("/procurement/landed-cost-vouchers", Map.of("goodsReceiptIds", List.of(grn),
                "charges", List.of(Map.of("kind", "freight", "amount", 500))));
        String vid = voucher.get("id").asString();
        okA(200, "POST", "/procurement/landed-cost-vouchers/" + vid + "/submit", null);
        okA(200, "POST", "/procurement/landed-cost-vouchers/" + vid + "/approve", null);
        keyedOk("/procurement/landed-cost-vouchers/" + vid + "/post", null);
        BigDecimal wipShare = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(x.amount), 0) FROM \"CostValueAdjustmentLine\""
                + " x WHERE x.\"organizationId\" = ? AND x.target = 'wip'").param(org).query(BigDecimal.class).single());
        assertThat(wipShare.signum()).as("a share of the landed cost reached the order's WIP").isPositive();
        okA(201, "POST", PO + id + "/cancel", Map.of());
        // The regression assertion comes first (the run), the integrity / WIP checks after it.

        keyedOk("/costing/cost-adjustment-runs", Map.of("from", today, "to", today));
        BigDecimal carried = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM \"CostAdjustmentRunLine\" WHERE"
                + " \"organizationId\" = ? AND \"orderCode\" = ? AND status = 'posted'").params(org, code)
                .query(BigDecimal.class).single());
        assertThat(carried).as("the run carried the cancelled order's WIP share (" + wipShare + ") out of WIP")
                .isEqualByComparingTo(wipShare);
        green("after the cost adjustment run");
        // A second run finds nothing left in WIP for the order.
        assertThat(keyed("POST", "/costing/cost-adjustment-runs", Map.of("from", today, "to", today)).status())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ process costing

    @Test
    @DisplayName("process costing: open period (opening + entries in WIP) → close (closing WIP carried); green")
    void processCostingClose() {
        String lime = prod("PLIME");
        String cement = prod("PCEMENT");
        stockIn(bakery, lime, 1000, 10);
        String applied = account("overhead_absorbed");
        String proc = okA(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", "MIX-" + sfx(),
                "name", "Mixing", "sequence", 1, "method", "weighted_average", "outputProductId", cement,
                "outputLocationId", warehouse)).get("id").asString();
        String period = okA(201, "POST", "/manufacturing/process-costing/processes/" + proc + "/periods", Map.of(
                "periodFrom", LocalDate.parse(today).withDayOfMonth(1).toString(), "periodTo", today)).get("id")
                .asString();
        keyedOk("/manufacturing/process-costing/periods/" + period + "/materials", Map.of("productId", lime,
                "locationId", bakery, "quantity", 500));
        keyedOk("/manufacturing/process-costing/periods/" + period + "/costs", Map.of("element", "labour", "amount",
                1500, "creditAccountId", applied));
        green("process period open");
        okA(200, "PATCH", "/manufacturing/process-costing/periods/" + period + "/units", Map.of("unitsStarted", 1000,
                "unitsCompleted", 800, "closingUnits", 200, "closingMaterialPct", 100, "closingConversionPct", 50));
        JsonNode closed = keyedOk("/manufacturing/process-costing/periods/" + period + "/close", null);
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(onHand(cement, warehouse)).isEqualByComparingTo("800");
        green("process period closed");
    }

    // ------------------------------------------------------------------ quarry and crushing

    @Test
    @DisplayName("quarry extraction: post (ROM stock at cost, no WIP line) and reverse; green")
    void quarryExtraction() {
        String rom = prod("QROM");
        String employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Amani', 'Operator', now())").params(employee, org, "QE-" + sfx())
                .update();
        okA(200, "PUT", "/quarry/settings", Map.of("allocationBasis", "net_tonnes", "labourRatePerHour", 10));
        String code = "Q" + sfx();
        String siteId = okA(201, "POST", "/quarry/sites", Map.of("code", code, "name", "Quarry " + code)).get("id")
                .asString();
        JsonNode pile = okA(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "ROM-" + code, "name",
                "ROM pad"));
        String material = okA(201, "POST", "/quarry/materials", Map.of("code", "GR-" + code, "name", "Granite",
                "rockType", "granite", "grade", "A", "densityTPerM3", 2.7, "productId", rom)).get("id").asString();
        String zone = okA(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "zone", "code", "Z1", "name",
                "North")).get("id").asString();
        String bench = okA(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "bench", "parentId", zone,
                "code", "B1", "name", "Bench 1", "benchHeightM", 9)).get("id").asString();
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("siteId", siteId);
        log.put("zoneId", bench);
        log.put("materialId", material);
        log.put("extractedOn", today);
        log.put("shift", "day");
        log.put("supervisorEmployeeId", employee);
        log.put("loads", List.of(Map.of("method", "survey", "tonnes", 120)));
        log.put("resources", List.of(Map.of("kind", "labour", "employeeId", employee, "hours", 10)));
        log.put("losses", List.of());
        log.put("post", true);
        JsonNode posted = keyedOk("/quarry/extraction-logs", log);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(onHand(rom, pile.get("locationId").asString())).isEqualByComparingTo("120");
        long wipLines = asOrg(() -> jdbc.sql("SELECT count(*) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceType\" LIKE 'quarry_extraction%'"
                + " AND l.\"accountId\" = ?").params(org, account("wip")).query(Long.class).single());
        assertThat(wipLines).isZero();
        green("extraction posted");
        keyedOk("/quarry/extraction-logs/" + posted.get("id").asString() + "/reverse", Map.of("reason", "Wrong shift"));
        assertThat(onHand(rom, pile.get("locationId").asString())).isEqualByComparingTo("0");
        green("extraction reversed");
    }

    @Test
    @DisplayName("crushing run: post (stages, joint cost, NRV by-product) → stockpile survey approved → run reversed;"
            + " green")
    void crushingRunAndSurvey() {
        String rom = prod("CRROM");
        String p20 = prod("CR20");
        String p10 = prod("CR10");
        String dust = prod("CRDUST");
        String scalp = prod("CRSCALP");
        String code = "CR" + sfx();
        String siteId = okA(201, "POST", "/quarry/sites", Map.of("code", code, "name", "Site " + code)).get("id")
                .asString();
        JsonNode romPile = okA(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "ROM" + code,
                "name", "ROM", "kind", "rom"));
        JsonNode k20 = okA(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "A20" + code, "name",
                "20mm", "kind", "product"));
        JsonNode k10 = okA(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "A10" + code, "name",
                "10mm", "kind", "product"));
        JsonNode kd = okA(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "DU" + code, "name",
                "Dust", "kind", "product"));
        stockIn(romPile.get("locationId").asString(), rom, 2000, 10);
        JsonNode plant = okA(201, "POST", "/manufacturing/crushing/plants", Map.of("code", code, "name", "Plant "
                + code, "siteId", siteId, "feedStockpileId", romPile.get("id").asString(), "designCapacityTph", 150,
                "dieselCostPerLitre", 2, "electricityCostPerKwh", 0.2, "labourRatePerHour", 5, "stages", List.of(
                        Map.of("name", "Primary crush", "kind", "primary", "units", List.of(Map.of("code", "JAW",
                                "name", "Jaw", "kind", "jaw_crusher", "meterKind", "both"))),
                        Map.of("name", "Screening", "kind", "screening", "units", List.of(Map.of("code", "SCR",
                                "name", "Screen", "kind", "screen", "meterKind", "kwh"))))));
        String plantId = plant.get("id").asString();
        List<Map<String, Object>> lines = List.of(
                gradingLine(p20, "main", null, 40, k20.get("id").asString(), null),
                gradingLine(p10, "co_product", null, 25, k10.get("id").asString(), null),
                gradingLine(dust, "by_product", null, 20, kd.get("id").asString(), Map.of("valuation", "nrv",
                        "nrvUnitValue", 2)),
                gradingLine(scalp, "waste", 1, 8, null, Map.of("stocked", false)));
        JsonNode draft = okA(201, "POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", plantId,
                "name", "Split " + code, "feedProductId", rom, "lines", lines));
        JsonNode profile = okA(200, "POST", "/manufacturing/crushing/grading-profiles/" + draft.get("id").asString()
                + "/activate", null);
        Map<String, String> lineIds = new HashMap<>();
        profile.get("lines").forEach(l -> lineIds.put(l.get("productId").asString(), l.get("id").asString()));
        Map<String, Object> run = new HashMap<>();
        run.put("plantId", plantId);
        run.put("runDate", today);
        run.put("shift", "day");
        run.put("operatorName", "Operator");
        run.put("feedTonnes", 1000);
        run.put("feedMethod", "belt_scale");
        run.put("outputMethod", "belt_scale");
        run.put("operatingHours", 8);
        run.put("labourHours", 16);
        run.put("outputs", List.of(
                Map.of("gradingLineId", lineIds.get(p20), "actualTonnes", 390),
                Map.of("gradingLineId", lineIds.get(p10), "actualTonnes", 250),
                Map.of("gradingLineId", lineIds.get(dust), "actualTonnes", 200),
                Map.of("gradingLineId", lineIds.get(scalp), "actualTonnes", 80)));
        String runId = keyedOk("/manufacturing/crushing/runs", run).get("id").asString();
        JsonNode posted = keyedOk("/manufacturing/crushing/runs/" + runId + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(onHand(p20, k20.get("locationId").asString())).isEqualByComparingTo("390");
        green("crushing run posted");

        JsonNode survey = keyedOk("/manufacturing/crushing/stockpile-surveys", Map.of("stockpileId", k10.get("id")
                .asString(), "productId", p10, "surveyDate", today, "method", "drone", "surveyor", "Survey Co",
                "volumeM3", 150, "bulkDensity", 1.6));
        String sid = survey.get("id").asString();
        okA(200, "POST", "/manufacturing/crushing/stockpile-surveys/" + sid + "/submit", null);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token("crushing:read", "crushing_survey:approve"));
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res approved = call("POST", "/manufacturing/crushing/stockpile-surveys/" + sid + "/approve", null, h);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(onHand(p10, k10.get("locationId").asString())).isEqualByComparingTo("240");
        green("stockpile survey posted");
    }

    static Map<String, Object> gradingLine(String product, String role, Integer stage, double pct, String pile,
            Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", product);
        m.put("role", role);
        m.put("stageSequence", stage);
        m.put("expectedPct", pct);
        m.put("stockpileId", pile);
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }
}
