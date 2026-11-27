package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.manufacturing.application.ProductionService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;

/**
 * IPR residuals of Phase 2 in manufacturing (stream H), each scenario in its own organisation with the production
 * posting rules a real organisation gets, and the integrity report (report-only checks included, {@code feature_risk}
 * aside), the GL tie-out, the WIP reconciliation report and the WIP tie-out green after it:
 * <ul>
 *   <li>D-2: a multi-stage order's reservation follows what its stages still have to consume (it stayed at the planned
 *       quantity, so every open multi-stage order showed {@code over_reserved});</li>
 *   <li>D-3: {@code ProductionService.getProduct} returns the product's columns, not only id and unit;</li>
 *   <li>GL-11 refinement: WIP posted through a rule that takes its account from a product-category field is in the
 *       WIP tie-out;</li>
 *   <li>WIP-7: a process-costing variance is attributed by its posting key, not by the process code;</li>
 *   <li>WIP-8: reversing a legacy completion (no output {@code totalCost}) puts back the value it took out of WIP;</li>
 *   <li>R2: partial output racing complete on one order, and a quality hold racing an issue of the held stock.</li>
 * </ul>
 */
class ManufacturingResidualsIT extends InventoryAuditSupport {

    static final String PO = "/manufacturing/production-orders/";

    @Autowired
    ProductionService production;

    @Autowired
    AccountResolverService accounts;

    String bakery;
    String warehouse;
    String today;
    int seq;

    /** A fresh organisation with the default posting rules, a production and a warehouse location. */
    void fresh() {
        AuditOrg o = newOrg();
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
        today = LocalDate.now(BusinessZone.get()).toString();
        okA(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        bakery = location("production");
        warehouse = location("warehouse");
    }

    String sfx() {
        return UUID.randomUUID().toString().substring(0, 6) + (++seq);
    }

    String location(String type) {
        return okA(201, "POST", "/inventory/locations", Map.of("code", "RS-" + sfx(), "name", "Residual " + sfx(),
                "type", type)).get("id").asString();
    }

    String prod(String code) {
        return prod(code, Map.of());
    }

    String prod(String code, Map<String, Object> extra) {
        Map<String, Object> cols = new HashMap<>(Map.of("manufacturingRole", "finished", "salesPrice", 15));
        cols.putAll(extra);
        return product(code, cols);
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

    String bom(String output, String component, int outputQty, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Recipe " + sfx(), "outputProductId", output,
                "outputQuantity", outputQty, "lines", List.of(Map.of("componentProductId", component, "quantity", qty))));
        body.putAll(extra);
        String id = okA(201, "POST", "/manufacturing/boms", body).get("id").asString();
        okA(201, "POST", "/manufacturing/boms/" + id + "/activate", null);
        return id;
    }

    String created(String bomId) {
        String id = okA(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery, "outputLocationId", warehouse)).get("id").asString();
        okA(201, "POST", PO + id + "/confirm", null);
        return id;
    }

    String started(String bomId) {
        String id = created(bomId);
        okA(201, "POST", PO + id + "/start", Map.of());
        return id;
    }

    void activateStandard(String productId, Object total) {
        JsonNode draft = okA(201, "POST", "/inventory/standard-costs", Map.of("productId", productId, "effectiveFrom",
                today, "materialCost", total, "convertMethod", true));
        String id = draft.get("id").asString();
        okA(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        keyedOk("/inventory/standard-costs/" + id + "/activate", null);
    }

    /** Integrity (incl. report-only, feature_risk excepted), GL tie-out, WIP report and WIP tie-out are green. */
    void green(String when) {
        JsonNode report = integrityReport();
        assertThat(failingChecks(report, "checks")).as("integrity checks " + when).isEmpty();
        assertGlTieOut();
        JsonNode recon = okA(200, "GET", "/manufacturing/reports/wip-reconciliation", null);
        assertThat(recon.get("balanced").asBoolean()).as("WIP reconciliation report " + when + ": " + recon).isTrue();
        assertThat(failingChecks(report, "reportOnlyChecks").stream().filter(f -> !"feature_risk".equals(f.substring(0,
                f.indexOf(' ')))).toList()).as("report-only integrity checks " + when).isEmpty();
        JsonNode wip = integrityCheck(report, "wip_tieout");
        assertThat(wip.get("ok").asBoolean()).as("WIP tie-out " + when + ": " + wip).isTrue();
    }

    BigDecimal orderReservation(String orderId) {
        return asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = 'production_order' AND \"sourceId\" = ? AND status ="
                + " 'active'").params(org, orderId).query(BigDecimal.class).single());
    }

    // ------------------------------------------------------------------ D-2

    @Test
    @DisplayName("D-2: a multi-stage order's reservation drops as its stages consume (100 → 50 → consumed), comes back"
            + " when the consuming posting is reversed, and is released at completion; reservations green throughout")
    void stageConsumptionReducesReservation() {
        fresh();
        String rock = prod("DROCK");
        String crushed = prod("DCRUSHED");
        String block = prod("DBLOCK");
        String stageLoc = location("production");
        stockIn(bakery, rock, 150, 5);
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
        assertThat(orderReservation(id)).isEqualByComparingTo("100");

        keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50));
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("100");
        assertThat(orderReservation(id)).as("half consumed").isEqualByComparingTo("50");
        green("stage 1 half consumed");

        JsonNode done1 = keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50,
                "complete", true));
        assertThat(orderReservation(id)).as("all consumed").isEqualByComparingTo("0");
        green("stage 1 consumed");

        JsonNode postings = done1.get("stages").get(0).get("postings");
        String last = postings.get(postings.size() - 1).get("id").asString();
        keyedOk("/manufacturing/stage-postings/" + last + "/reverse", Map.of("reason", "Wrong count"));
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("100");
        assertThat(orderReservation(id)).as("the reversed posting's material is needed again").isEqualByComparingTo(
                "50");
        green("stage 1 posting reversed");

        keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50, "complete", true));
        keyedOk("/manufacturing/production-stages/" + s2 + "/issue", Map.of("lines", List.of(Map.of("productId",
                crushed, "quantity", 100))));
        green("stage 2 open");
        JsonNode done = keyedOk("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 100,
                "complete", true));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(orderReservation(id)).isEqualByComparingTo("0");
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("50");
        green("multi-stage completed");
    }

    // ------------------------------------------------------------------ D-3

    @Test
    @DisplayName("D-3: ProductionService.getProduct returns the product's columns (costing method included)")
    void getProductReturnsColumns() throws Exception {
        fresh();
        String p = prod("D3", Map.of("costingMethod", "STANDARD"));
        Object target = AopTestUtils.getTargetObject(production);
        Method m = ProductionService.class.getDeclaredMethod("getProduct", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = asOrg(() -> {
            try {
                return (Map<String, Object>) m.invoke(target, p);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(row.get("uomId")).isEqualTo(uomId);
        assertThat(String.valueOf(row.get("costingMethod"))).isEqualTo("STANDARD");
        assertThat(row.get("name")).isEqualTo("D3");
    }

    // ------------------------------------------------------------------ GL-11

    @Test
    @DisplayName("GL-11: WIP posted to an account taken from a product-category field (category_field rules for"
            + " PRODUCTION_CONSUME / PRODUCTION_OUTPUT) ties out while the order is in progress and after completion")
    void wipTieOutFollowsCategoryFieldRules() {
        fresh();
        String wip = account("wip");
        String lineWip = asOrg(() -> {
            String key = jdbc.sql("SELECT c.key FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                    + " WHERE a.id = ?").param(wip).query(String.class).single();
            return accounts.ensureByCode("1" + (4000 + (int) (Math.random() * 999)), "WIP line 2 " + sfx(), key, null);
        });
        String category = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"internalUseAccountId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, now())").params(category, org, "Line 2 " + sfx(), lineWip).update();
        okA(201, "POST", "/inventory/posting-rules", Map.of("movementType", "PRODUCTION_CONSUME", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "category_field", "accountMappingKey", "internal_use",
                "categoryId", category));
        okA(201, "POST", "/inventory/posting-rules", Map.of("movementType", "PRODUCTION_OUTPUT", "lineIndex", 1,
                "debitOrCredit", "credit", "accountSource", "category_field", "accountMappingKey", "internal_use",
                "categoryId", category));
        String flour = prod("CFLOUR", Map.of("categoryId", category));
        String cake = prod("CCAKE", Map.of("categoryId", category));
        stockIn(bakery, flour, 40, 100);
        String id = started(bom(cake, flour, 20, 4, Map.of()));
        BigDecimal onLine = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM"
                + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ?"
                + " AND l.\"accountId\" = ?").params(org, lineWip).query(BigDecimal.class).single());
        assertThat(onLine).as("the consume posting used the category's account").isEqualByComparingTo("400");
        green("in progress on the category's WIP account");
        okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 20));
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("20");
        green("completed");
    }

    // ------------------------------------------------------------------ WIP-7

    @Test
    @DisplayName("WIP-7: a process-costing standard variance is attributed by its posting key even when the process"
            + " code equals a production order's code")
    void processVarianceAttributedByPostingKey() {
        fresh();
        String flour = prod("PFLOUR");
        String cake = prod("PCAKE");
        stockIn(bakery, flour, 40, 100);
        String orderId = created(bom(cake, flour, 20, 4, Map.of()));
        String orderCode = okA(200, "GET", PO + orderId, null).get("orderCode").asString();

        String lime = prod("PLIME");
        String cement = prod("PCEMENT");
        stockIn(bakery, lime, 100, 10);
        activateStandard(cement, 12);
        JsonNode proc = okA(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", orderCode, "name",
                "Grinding", "sequence", 1, "outputProductId", cement, "outputLocationId", warehouse));
        String from = LocalDate.now().withDayOfMonth(1).toString();
        String period = okA(201, "POST", "/manufacturing/process-costing/processes/" + proc.get("id").asString()
                + "/periods", Map.of("periodFrom", from, "periodTo", LocalDate.now().toString())).get("id").asString();
        keyedOk("/manufacturing/process-costing/periods/" + period + "/materials", Map.of("productId", lime,
                "locationId", bakery, "quantity", 100));
        okA(200, "PATCH", "/manufacturing/process-costing/periods/" + period + "/units", Map.of("unitsStarted", 100,
                "unitsCompleted", 100, "closingUnits", 0));
        keyedOk("/manufacturing/process-costing/periods/" + period + "/close", null);
        assertThat(onHand(cement, warehouse)).isEqualByComparingTo("100");
        long variances = asOrg(() -> count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'production_variance' AND \"sourceId\" = ?", org, orderCode));
        assertThat(variances).as("the process variance entry carries the process code").isEqualTo(1);
        green("process period closed with a standard variance");
    }

    // ------------------------------------------------------------------ WIP-8

    @Test
    @DisplayName("WIP-8: reversing a legacy completion (main output without totalCost; pool 1,000,000 over 300,000"
            + " units) puts back exactly what it took out of WIP, not 3.333333 × 300,000")
    void legacyReversalPutsBackThePool() {
        fresh();
        String ore = prod("LORE");
        String bead = prod("LBEAD");
        stockIn(bakery, ore, 1, 1_000_000);
        String id = started(bom(bead, ore, 300_000, 1, Map.of()));
        okA(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 300_000));
        assertThat(onHand(bead, warehouse)).isEqualByComparingTo("300000");
        // No green check here: while the 300,000 beads are on hand the valuation reads 300,000 × 3.333333 = 999,999.90
        // against the ledger's and the GL's 1,000,000 (engine rounding of the average cost to 6 dp, reported as a new
        // defect, not this finding). The state after the reversal below is checked in full.
        // A completion written before the output kept its value (Nest data / an order without an output row).
        asOrgRun(() -> jdbc.sql("UPDATE \"ProductionOutput\" SET \"totalCost\" = 0 WHERE \"orderId\" = ? AND kind ="
                + " 'output'").param(id).update());
        assertThat(okA(200, "GET", PO + id, null).get("outputUnitCost").asString()).isEqualTo("3.333333");
        okA(201, "POST", PO + id + "/reverse", Map.of());
        assertThat(onHand(bead, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(ore, bakery)).isEqualByComparingTo("1");
        green("legacy completion reversed");
    }

    // ------------------------------------------------------------------ R2

    @Test
    @DisplayName("R2: a partial output and the completion race on one order: no deadlock, every receipt counted once")
    void partialOutputVersusComplete() throws Exception {
        fresh();
        String flour = prod("RFLOUR");
        String cake = prod("RCAKE");
        stockIn(bakery, flour, 40, 100);
        String id = started(bom(cake, flour, 20, 4, Map.of("overheadCost", 40)));
        List<Callable<Res>> racers = List.of(
                () -> keyed("POST", PO + id + "/partial-output", Map.of("quantity", 5)),
                () -> api("POST", PO + id + "/complete", Map.of("qtyProduced", 15)));
        List<Raced<Res>> out = race(Duration.ofSeconds(60), racers);
        for (Raced<Res> r : out) {
            assertThat(r.ok()).as(r.message()).isTrue();
            assertThat(r.value().status()).as(r.value().raw().body()).isLessThan(500);
        }
        Res partial = out.get(0).value();
        Res complete = out.get(1).value();
        assertThat(complete.status()).as(complete.raw().body()).isEqualTo(201);
        BigDecimal expected = new BigDecimal(partial.status() == 200 ? "20" : "15");
        assertThat(onHand(cake, warehouse)).as("partial " + partial.status() + ": " + partial.raw().body())
                .isEqualByComparingTo(expected);
        assertThat(okA(200, "GET", PO + id, null).get("status").asString()).isEqualTo("completed");
        green("after the race");
    }

    @Test
    @DisplayName("R2: a quality hold (move to QC quarantine) and an issue of the held stock race: no deadlock, no"
            + " negative stock, every unit accounted for once")
    void qualityHoldVersusIssue() throws Exception {
        fresh();
        orgSetting("inventory.allowNegativeStock", "false");
        String grit = product("QGRIT");
        receive(grit, 10, 30);
        JsonNode spec = okA(201, "POST", "/quality/specifications", Map.of("code", "SPEC-" + sfx(), "name", "Spec",
                "productId", grit, "parameters", List.of(Map.of("code", "MOIST", "name", "Moisture", "kind", "numeric",
                        "maxValue", 3))));
        okA(200, "POST", "/quality/specifications/" + spec.get("id").asString() + "/activate", null);
        JsonNode plan = okA(201, "POST", "/quality/inspection-plans", Map.of("code", "PLAN-" + sfx(), "name", "Plan",
                "stage", "final", "productId", grit, "specificationId", spec.get("id").asString(),
                "independentRelease", false));
        String lot = okA(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", grit,
                "quantity", 10, "planId", plan.get("id").asString(), "locationId", main)).get("id").asString();

        List<Callable<Res>> racers = List.of(
                () -> api("POST", "/quality/inspection-lots/" + lot + "/hold", Map.of("reason", "Awaiting test")),
                () -> api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(grit, 6, null)))));
        List<Raced<Res>> out = race(Duration.ofSeconds(60), racers);
        for (Raced<Res> r : out) {
            assertThat(r.ok()).as(r.message()).isTrue();
            assertThat(r.value().status()).as(r.value().raw().body()).isLessThan(500);
        }
        Res hold = out.get(0).value();
        Res issue = out.get(1).value();
        String qc = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code ="
                + " 'QC-QUARANTINE' AND \"deletedAt\" IS NULL").param(org).query(String.class).optional().orElse(null));
        BigDecimal atMain = onHand(grit, main);
        BigDecimal atQc = qc == null ? BigDecimal.ZERO : onHand(grit, qc);
        String outcome = "hold " + hold.status() + " " + hold.raw().body() + " / issue " + issue.status() + " "
                + issue.raw().body();
        assertThat(atMain.signum()).as("no negative stock at main; " + outcome).isGreaterThanOrEqualTo(0);
        assertThat(atQc.signum()).as(outcome).isGreaterThanOrEqualTo(0);
        BigDecimal issued = issue.status() == 200 ? new BigDecimal("6") : BigDecimal.ZERO;
        assertThat(atMain.add(atQc)).as("conservation; " + outcome).isEqualByComparingTo(BigDecimal.TEN.subtract(
                issued));
        // The hold moves the lot's 10 to quarantine and the issue needs 6 of the same 10: exactly one of them wins, the
        // other is refused under the stock-cell lock (L4/L5) with a 4xx, never both and never neither.
        assertThat(hold.status() == 200 ^ issue.status() == 200).as("exactly one wins; " + outcome).isTrue();
        assertThat(atQc).as(outcome).isEqualByComparingTo(hold.status() == 200 ? "10" : "0");
        assertIntegrityGreen(true, "feature_risk");
        assertGlTieOut();
    }
}
