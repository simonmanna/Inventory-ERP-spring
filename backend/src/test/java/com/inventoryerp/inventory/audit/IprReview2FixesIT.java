package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.TestSuppliers;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR review 2 (stream R2): fixes from the independent accounting review of the IPR changes.
 *
 * <ul>
 *   <li>R2-1: the period close runs the inventory value checks as of the period end, and counts only the value
 *       findings raised up to the period end.
 *   <li>R2-2: a WIP line of a quarry extraction entry is one failure (quarry_extraction), not also late cost.
 *   <li>R2-3: lines on a non-asset rule account that no cost object explains are not WIP.
 *   <li>R2-4: the late cost of a cancelled multi-stage order follows the materials its stage reversals returned.
 * </ul>
 * Each test runs in a fresh isolated organisation.
 */
class IprReview2FixesIT extends InventoryAuditSupport {

    @Autowired
    PostingService posting;

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

    /** A base-currency journal entry dated {@code date}: one debit and one credit line. */
    String journal(Instant date, String sourceType, String debitAccount, String creditAccount, Object amount) {
        return asOrg(() -> (String) posting.post(new PostingRequest("INV", date, "IPR review 2", null, null,
                sourceType, UUID.randomUUID().toString(), null, null, null, null, null, List.of(
                        Line.debit(debitAccount, amount, "IPR review 2"),
                        Line.credit(creditAccount, amount, "IPR review 2")))).get("id"));
    }

    /** An open fiscal period June 2008 (org-local wall clock), far behind every movement the test makes today. */
    String june2008() {
        String period = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'IPR 2008-06', '2008-06-01', '2008-06-30 23:59:59', 'open', now())")
                .params(period, org).update();
        return period;
    }

    static final Instant IN_JUNE_2008 = Instant.parse("2008-06-15T12:00:00Z");

    String openValueFinding(String createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                + " status, \"createdAt\") VALUES (?, ?, 'R2 test', 0, 'integrity_valuation_vs_gl', 'test', 'open',"
                + " CAST(? AS timestamp))").params(id, org, createdAt).update();
        return id;
    }

    // ------------------------------------------------------------------ R2-1 period close as of the period end

    @Test
    @DisplayName("R2-1: a variance and a value finding raised after the period end do not hold the close")
    void laterVarianceDoesNotHoldClose() {
        fresh();
        String p = product("R2LATE");
        receive(p, 10, 10);
        String period = june2008();
        String sv = account("stock_valuation");
        String expense = account("stock_adjustment_expense");
        // Today's variance (the "November" one) and a finding the daily monitor raised today, after June 2008.
        String dirty = journal(Instant.now(), "ipr_test", sv, expense, 75);
        String finding = openValueFinding(java.time.LocalDateTime.now().toString());

        Res closed = keyed("POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(closed.status()).as("closing June 2008 with a variance raised only after it: " + closed.raw().body())
                .isIn(200, 201);

        asOrgRun(() -> posting.reverse(dirty, Instant.now(), "R2 cleanup"));
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE id = ?").param(finding).update();
        assertGlTieOut();
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("R2-1: a variance in the period stays a refusal after a later entry offsets it")
    void periodVarianceFixedLaterStillHolds() {
        fresh();
        String p = product("R2HOLD");
        receive(p, 10, 10);
        String period = june2008();
        String sv = account("stock_valuation");
        // A balance-sheet counter account: June's closing entry then has no P&L line to zero.
        String liability = jdbc.sql("SELECT a.id FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.\"organizationId\" = ? AND COALESCE(a.\"reportSection\"::text,"
                + " c.\"reportSection\"::text) = 'current_liabilities' AND a.\"isPostable\" AND a.\"controlAccountType\""
                + " IS NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
        journal(IN_JUNE_2008, "ipr_test", sv, liability, 75); // June overstated by 75
        journal(Instant.now(), "ipr_test", liability, sv, 75); // corrected today: the live tie-out is green again
        assertGlTieOut();

        Res refused = keyed("POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(msg(refused)).startsWith("Cannot close 'IPR 2008-06': the inventory value check(s) valuation_vs_gl");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("R2-1: a journal dated after the period end (future-dated) does not hold the close")
    void futureDatedJournalDoesNotHoldClose() {
        fresh();
        String p = product("R2FUT");
        receive(p, 10, 10);
        String period = june2008();
        String sv = account("stock_valuation");
        String expense = account("stock_adjustment_expense");
        String future = journal(Instant.now().plus(20, ChronoUnit.DAYS), "ipr_test", sv, expense, 75);

        Res closed = keyed("POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(closed.status()).as("closing June 2008 with a future-dated journal: " + closed.raw().body())
                .isIn(200, 201);

        asOrgRun(() -> posting.reverse(future, Instant.now().plus(20, ChronoUnit.DAYS), "R2 cleanup"));
        assertGlTieOut();
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("R2-1: an open value finding raised up to the period end still holds the close")
    void findingRaisedInPeriodHolds() {
        fresh();
        String period = june2008();
        String finding = openValueFinding("2008-06-20 10:00:00");
        Res refused = keyed("POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(msg(refused)).contains("1 open inventory integrity finding(s) on stock value");
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE id = ?").param(finding).update();
        keyedOk("/fiscal-periods/" + period + "/close", null);
    }

    // ------------------------------------------------------------------ R2-2 quarry WIP lines counted once

    @Test
    @DisplayName("R2-2: a WIP line of a quarry extraction entry is one quarry_extraction failure, not also late cost")
    void quarryWipLineCountedOnce() {
        fresh();
        String wip = account("wip");
        String expense = account("stock_adjustment_expense");
        journal(Instant.now(), "quarry_extraction", wip, expense, 40);

        JsonNode check = integrityCheck(integrityReport(), "wip_tieout");
        List<String> kinds = new ArrayList<>();
        check.get("rows").forEach(r -> kinds.add(r.get("kind").asString()));
        assertThat(kinds).as("WIP tie-out " + check).containsExactly("quarry_extraction");
        assertThat(check.get("failures").asLong()).isEqualTo(1);
        assertGlTieOut();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ R2-3 expensed rule accounts

    @Test
    @DisplayName("R2-3: a PRODUCTION_CONSUME rule that expenses consumption does not turn that expense account into WIP")
    void expenseRuleAccountIsNotWip() {
        fresh();
        String expenseAcct = jdbc.sql("SELECT a.id FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.\"organizationId\" = ? AND COALESCE(a.\"reportSection\"::text,"
                + " c.\"reportSection\"::text) = 'operating_expense' AND a.\"isPostable\" ORDER BY a.code LIMIT 1")
                .param(org).query(String.class).single();
        jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                + " \"debitOrCredit\", \"accountSource\", \"literalAccountId\", \"createdAt\", \"updatedAt\") VALUES (?,"
                + " ?, 'PRODUCTION_CONSUME', 9, 'debit', 'literal', ?, now(), now())")
                .params(UUID.randomUUID().toString(), org, expenseAcct).update();
        // An ordinary expense posting on that account, nothing to do with production.
        journal(Instant.now(), "expense", expenseAcct, account("cogs"), 60);

        JsonNode recon = okA(200, "GET", "/manufacturing/reports/wip-reconciliation", null);
        assertThat(recon.get("balanced").asBoolean()).as("WIP reconciliation " + recon).isTrue();
        assertThat(num(recon.get("glBalance"))).isEqualByComparingTo("0");
        JsonNode check = integrityCheck(integrityReport(), "wip_tieout");
        assertThat(check.get("ok").asBoolean()).as("WIP tie-out " + check).isTrue();
        assertGlTieOut();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ S-2 inventory analytics values

    String token(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "R2 " + role.substring(0, 8), permissions)
                .update();
        String email = "r2-" + role.substring(0, 8) + "@audit.test";
        createUser(org, email, "Review2@12345", role);
        return login(orgCode, email, "Review2@12345");
    }

    static JsonNode tile(JsonNode dashboard, String key) {
        for (JsonNode t : dashboard.get("tiles")) {
            if (key.equals(t.get("key").asString())) {
                return t;
            }
        }
        throw new AssertionError("no tile " + key + " in " + dashboard);
    }

    @Test
    @DisplayName("S-2: the inventory analytics dashboard hides stock values from a user without"
            + " inventory_valuation:read; quantities and counts stay")
    void inventoryAnalyticsHidesValuesWithoutValuationRead() {
        fresh();
        String p = product("R2KPI", Map.of("minQuantity", 50));
        receive(p, 10, 10);

        JsonNode full = okA(200, "GET", "/analytics/inventory", null);
        assertThat(num(tile(full, "stockValue").get("value"))).isEqualByComparingTo("100");

        String viewer = token("analytics:inventory");
        Res r = call("GET", "/analytics/inventory", null, viewer);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode d = r.body();
        for (String money : List.of("stockValue", "agedOver90", "slowStock", "deadStock", "discrepancyValue")) {
            assertThat(tile(d, money).get("value").isNull()).as(money + " in " + d).isTrue();
        }
        assertThat(tile(d, "belowMinimum").get("value").asLong()).isEqualTo(1);
        d.get("charts").forEach(c -> c.get("rows").forEach(row -> assertThat(row.get("value").isNull())
                .as("chart " + c.get("key")).isTrue()));
        d.get("tables").forEach(t -> t.get("rows").forEach(row -> assertThat(row.has("value")
                && !row.get("value").isNull()).as("table " + t.get("key") + " " + row).isFalse()));
        Res docs = call("GET", "/analytics/inventory/documents?kpi=below-minimum", null, viewer);
        assertThat(docs.status()).as(docs.raw().body()).isEqualTo(200);
        assertThat(docs.body().get("data")).hasSize(1);
        assertThat(docs.body().get("data").get(0).get("amount").isNull()).as(docs.raw().body()).isTrue();
        assertThat(num(docs.body().get("data").get(0).get("quantity"))).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ R2-4 multi-stage late cost

    String productionLocation() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        return okA(201, "POST", "/inventory/locations", Map.of("code", "R2-" + s, "name", "R2 line " + s, "type",
                "production")).get("id").asString();
    }

    @Test
    @DisplayName("R2-4: the late cost of a reversed multi-stage order follows the materials its stage reversal returned"
            + " (on hand), not cost of sales")
    void multiStageLateCostFollowsReturnedMaterials() {
        fresh();
        okA(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        String line = productionLocation();
        String stageLoc = productionLocation();
        String supplier = TestSuppliers.supplier(jdbc, org);
        String flour = product("R2FLOUR", Map.of("manufacturingRole", "finished", "salesPrice", 15));
        String dough = product("R2DOUGH", Map.of("manufacturingRole", "finished", "salesPrice", 15));
        String bread = product("R2BREAD", Map.of("manufacturingRole", "finished", "salesPrice", 15));
        String grn = okA(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier,
                "warehouseId", line, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId",
                        flour, "description", "Flour", "quantity", 100, "unitCost", 50)))).get("id").asString();
        String routingId = okA(201, "POST", "/manufacturing/routings", Map.of("name", "R2 line", "operations",
                List.of(Map.of("sequence", 10, "name", "Mix"), Map.of("sequence", 20, "name", "Bake")))).get("id")
                .asString();
        List<String> ops = new ArrayList<>();
        okA(200, "GET", "/manufacturing/routings/" + routingId, null).get("operations").forEach(o -> ops.add(o.get("id")
                .asString()));
        okA(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                Map.of("operationId", ops.get(0), "issueMode", "backflush", "outputProductId", dough, "outputPerUnit",
                        1, "stageLocationId", stageLoc, "materialProductIds", List.of(flour)),
                Map.of("operationId", ops.get(1), "issueMode", "manual"))));
        String bomId = okA(201, "POST", "/manufacturing/boms", Map.of("name", "R2 staged", "outputProductId", bread,
                "outputQuantity", 100, "lines", List.of(Map.of("componentProductId", flour, "quantity", 100)),
                "routingId", routingId)).get("id").asString();
        okA(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        JsonNode order = okA(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 100, "locationId", line, "outputLocationId", main)));
        String id = order.get("id").asString();
        String code = okA(200, "GET", "/manufacturing/production-orders/" + id, null).get("orderCode").asString();
        String s1 = order.get("stages").get(0).get("id").asString();
        okA(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null);
        keyedOk("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 50));

        // The landed cost arrives while stage 1 has consumed 50 of the 100 received: half of it reaches the WIP.
        JsonNode voucher = keyedOk("/procurement/landed-cost-vouchers", Map.of("goodsReceiptIds", List.of(grn),
                "charges", List.of(Map.of("kind", "freight", "amount", 500))));
        String vid = voucher.get("id").asString();
        okA(200, "POST", "/procurement/landed-cost-vouchers/" + vid + "/submit", null);
        okA(200, "POST", "/procurement/landed-cost-vouchers/" + vid + "/approve", null);
        keyedOk("/procurement/landed-cost-vouchers/" + vid + "/post", null);
        BigDecimal wipShare = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(x.amount), 0) FROM \"CostValueAdjustmentLine\""
                + " x WHERE x.\"organizationId\" = ? AND x.target = 'wip'").param(org).query(BigDecimal.class).single());
        assertThat(wipShare.signum()).as("a share of the landed cost reached the order's WIP").isPositive();

        // Reverse the whole order: the stage reversal returns the 50 flour to stock and cancels the order.
        keyedOk("/manufacturing/production-orders/" + id + "/stages/reverse", Map.of("reason", "R2 test"));
        assertThat(okA(200, "GET", "/manufacturing/production-orders/" + id, null).get("status").asString())
                .isEqualTo("cancelled");

        JsonNode run = keyedOk("/costing/cost-adjustment-runs", Map.of("from", java.time.LocalDate.now().toString(),
                "to", java.time.LocalDate.now().toString()));
        String je = run.get("journalEntryId").asString();
        BigDecimal toCogs = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(\"baseDebit\" - \"baseCredit\"), 0) FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?").params(je, account("cogs"))
                .query(BigDecimal.class).single());
        assertThat(toCogs).as("the share of reversed order " + code + " went to cost of sales").isZero();
        BigDecimal carried = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM \"CostAdjustmentRunLine\" WHERE"
                + " \"organizationId\" = ? AND \"orderCode\" = ? AND status = 'posted'").params(org, code)
                .query(BigDecimal.class).single());
        assertThat(carried).as("the run carried the order's WIP share out of WIP").isEqualByComparingTo(wipShare);

        assertGlTieOut();
        assertIntegrityGreen(true, "feature_risk");
        JsonNode wip = integrityCheck(integrityReport(), "wip_tieout");
        assertThat(wip.get("ok").asBoolean()).as("WIP tie-out " + wip).isTrue();
    }
}
