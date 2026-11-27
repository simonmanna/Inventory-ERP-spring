package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.TestSuppliers;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockPostingService.IssuePosting;
import com.inventoryerp.kernel.common.BusinessZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 2 (stream C), accounting integrity: GL-1 / GL-5 base-currency GRNI and valuation, GL-2 quality downgrade
 * to a standard-costed product, GL-3 closing an integrity finding, GL-4 posting fallbacks and system rules, GL-8 debit
 * note stock account, GL-10 live value checks at period close, X-2 a quality hold moves the stock to QC quarantine.
 * Each test runs in a fresh isolated organisation, so a deliberately dirty GL never leaks into another test.
 */
class IprPhase2AccountingIT extends InventoryAuditSupport {

    @Autowired
    PostingService posting;

    @Autowired
    StockPostingService stockPosting;

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

    void fresh() {
        use(newOrg());
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyedOk(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    /** A journal entry in the audit org: {@code currency} null = base; one debit and one credit line. */
    String journal(String currency, Object rate, String debitAccount, String creditAccount, Object amount) {
        return asOrg(() -> (String) posting.post(new PostingRequest("INV", Instant.now(), "IPR test", currency, rate,
                "ipr_test", UUID.randomUUID().toString(), null, null, null, null, null, List.of(
                        Line.debit(debitAccount, amount, "IPR test"), Line.credit(creditAccount, amount, "IPR test"))))
                .get("id"));
    }

    BigDecimal accountBalance(String accountId) {
        return asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                + " l.\"accountId\" = ? AND e.status::text <> 'draft'").params(org, accountId).query(BigDecimal.class)
                .single());
    }

    String token(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "IPR " + role.substring(0, 8), permissions)
                .update();
        String email = "ipr2-" + role.substring(0, 8) + "@audit.test";
        createUser(org, email, "Ipr2@12345", role);
        return login(orgCode, email, "Ipr2@12345");
    }

    String ownStockAccount(String name) {
        String sv = account("stock_valuation");
        String own = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"categoryId\", \"normalBalance\","
                + " \"reportSection\", \"updatedAt\") SELECT ?, \"organizationId\", ?, ?, \"categoryId\","
                + " \"normalBalance\", \"reportSection\", now() FROM \"Account\" WHERE id = ?")
                .params(own, "13" + UUID.randomUUID().toString().substring(0, 4), name, sv).update();
        return own;
    }

    String activateStandard(String productId, Object total) {
        String today = LocalDate.now(BusinessZone.get()).toString();
        JsonNode draft = okA(201, "POST", "/inventory/standard-costs", Map.of("productId", productId, "effectiveFrom",
                today, "materialCost", total, "convertMethod", true));
        String id = draft.get("id").asString();
        okA(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        keyedOk("POST", "/inventory/standard-costs/" + id + "/activate", null);
        return id;
    }

    // ------------------------------------------------------------------ GL-1 / GL-5

    @Test
    @DisplayName("GL-1/GL-5: GRNI reconciliation and the valuation GL tie-out read base-currency amounts")
    void baseCurrencyGrniAndValuation() {
        fresh();
        String p = product("FXITEM");
        receive(p, 10, 100);
        String sv = account("stock_valuation");
        String grni = account("grni_accrued");
        String expense = account("stock_adjustment_expense");
        // Base nets to zero on each account; the transaction amounts (1 USD vs 3,700 UGX) do not.
        journal("USD", 3700, sv, expense, 1);
        journal(null, null, expense, sv, 3700);
        journal("USD", 3700, grni, expense, 1);
        journal(null, null, expense, grni, 3700);

        JsonNode report = okA(200, "GET", "/procurement/reports/grni", null);
        BigDecimal open = new BigDecimal(report.get("glBalance").asString());
        assertThat(open.abs()).as("GRNI GL in base currency: only the receipt's 1,000 " + report)
                .isEqualByComparingTo("1000");
        assertGlTieOut();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ GL-4

    @Test
    @DisplayName("GL-4: without rules a return to supplier debits GRNI, a production consume WIP, an unknown type"
            + " fails closed; a system default rule cannot be deleted")
    void postingFallbacks() {
        fresh();
        String p = product("FALLBACK");
        receive(p, 10, 10);
        String rtv = "rtv-" + UUID.randomUUID();
        asOrgRun(() -> stockPosting.postIssue(new IssuePosting(p, main, null, BigDecimal.ONE, Instant.now(),
                "ipr_test", rtv, null, null, "RETURN_TO_SUPPLIER", new BigDecimal("10"))));
        String consume = "con-" + UUID.randomUUID();
        asOrgRun(() -> stockPosting.postIssue(new IssuePosting(p, main, null, BigDecimal.ONE, Instant.now(),
                "ipr_test", consume, null, null, "PRODUCTION_CONSUME", new BigDecimal("10"))));
        assertThat(debitAccount(rtv)).isEqualTo(account("grni_accrued"));
        assertThat(debitAccount(consume)).isEqualTo(account("wip"));
        assertThatThrownBy(() -> asOrgRun(() -> stockPosting.postIssue(new IssuePosting(p, main, null, BigDecimal.ONE,
                Instant.now(), "ipr_test", "x-" + UUID.randomUUID(), null, null, "MYSTERY_MOVE", BigDecimal.TEN))))
                .hasMessageContaining("No posting rule or default account is configured for stock movements of type"
                        + " MYSTERY_MOVE");

        okA(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        String ruleId = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND"
                + " \"movementType\"::text = 'STOCK_IN' AND \"lineIndex\" = 0 AND \"productId\" IS NULL AND"
                + " \"categoryId\" IS NULL LIMIT 1").param(org).query(String.class).single());
        Res del = api("DELETE", "/inventory/posting-rules/" + ruleId, null);
        assertThat(del.status()).as(del.raw().body()).isEqualTo(400);
        assertThat(msg(del)).startsWith("This is a system default posting rule for STOCK_IN and cannot be deleted.");
    }

    String debitAccount(String sourceId) {
        return asOrg(() -> jdbc.sql("SELECT l.\"accountId\" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceId\" = ? AND l.\"baseDebit\" > 0")
                .params(org, sourceId).query(String.class).single());
    }

    // ------------------------------------------------------------------ GL-8

    @Test
    @DisplayName("GL-8: a return to vendor credits the product's own stock account, so the per-account tie-out holds")
    void debitNoteCreditsProductStockAccount() {
        fresh();
        String p = product("OWNACC");
        String own = ownStockAccount("Own stock");
        okA(201, "POST", "/inventory/posting-rules", Map.of("movementType", "STOCK_IN", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "literal", "literalAccountId", own, "productId", p));
        okA(201, "POST", "/inventory/posting-rules", Map.of("movementType", "STOCK_IN", "lineIndex", 1,
                "debitOrCredit", "credit", "accountSource", "account_mapping", "accountMappingKey", "grni_accrued",
                "productId", p));
        String supplier = TestSuppliers.supplier(jdbc, org);
        okA(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", main,
                "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", p, "description", "x",
                        "quantity", 10, "unitCost", 100))));
        assertThat(accountBalance(own)).isEqualByComparingTo("1000");
        JsonNode note = okA(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId",
                supplier, "reason", "returned_goods", "locationId", main, "lines", List.of(Map.of("productId", p,
                        "description", "x", "quantity", 2, "unitPrice", 100))));
        keyedOk("PATCH", "/procurement/debit-notes/" + note.get("id").asString() + "/post", null);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        assertThat(accountBalance(own)).as("the return credits the product's own stock account")
                .isEqualByComparingTo("800");
        assertGlTieOut();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ GL-3

    @Test
    @DisplayName("GL-3: closing an integrity value finding needs inventory_valuation:manage and a passing check")
    void integrityFindingResolution() {
        fresh();
        String p = product("FINDING");
        receive(p, 10, 10);
        String sv = account("stock_valuation");
        String expense = account("stock_adjustment_expense");
        String dirty = journal(null, null, sv, expense, 50);
        String finding = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                + " status) VALUES (?, ?, 'test', 0, 'integrity_valuation_vs_gl', 'test', 'open')").params(finding, org)
                .update();
        String path = "/pos/posting-monitor/exceptions/" + finding + "/resolve";
        Map<String, Object> body = Map.of("status", "resolved", "note", "checked");

        Res noPerm = call("POST", path, body, token("pos:override", "pos:read"));
        assertThat(noPerm.status()).as(noPerm.raw().body()).isEqualTo(403);
        assertThat(msg(noPerm)).isEqualTo("Closing an inventory integrity finding needs the inventory_valuation:manage"
                + " permission");

        Res stillFails = api("POST", path, body);
        assertThat(stillFails.status()).as(stillFails.raw().body()).isEqualTo(400);
        assertThat(msg(stillFails)).startsWith("The integrity check valuation_vs_gl still fails");

        journal(null, null, expense, sv, 50);
        assertThat(dirty).isNotNull();
        okA(201, "POST", path, body);
        assertGlTieOut();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ GL-10

    @Test
    @DisplayName("GL-10: a period close re-runs the inventory value checks live, whatever was raised when")
    void periodCloseRunsValueChecks() {
        fresh();
        String p = product("CLOSE");
        receive(p, 10, 10);
        String period = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'IPR 2008-06', '2008-06-01', '2008-06-30 23:59:59', 'open', now())")
                .params(period, org).update();
        String sv = account("stock_valuation");
        String expense = account("stock_adjustment_expense");
        journal(null, null, sv, expense, 75);

        Res refused = keyed("POST", "/fiscal-periods/" + period + "/close", null);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(msg(refused)).startsWith("Cannot close 'IPR 2008-06': the inventory value check(s) valuation_vs_gl");

        journal(null, null, expense, sv, 75);
        assertGlTieOut();
        keyedOk("POST", "/fiscal-periods/" + period + "/close", null);
    }

    // ------------------------------------------------------------------ X-2 + GL-2 (quality)

    @Test
    @DisplayName("X-2: a hold moves the lot's stock to QC quarantine (not sellable); GL-2: a downgrade to a standard"
            + " product posts stock at standard plus a variance; release moves the rest back")
    void qualityHoldQuarantineAndStandardDowngrade() {
        fresh();
        String first = product("GRADE-1");
        String second = product("GRADE-2");
        receive(first, 10, 30);
        activateStandard(second, 15);
        JsonNode spec = okA(201, "POST", "/quality/specifications", Map.of("code", "SPEC-" + UUID.randomUUID()
                .toString().substring(0, 6), "name", "Spec", "productId", first, "parameters", List.of(Map.of("code",
                        "MOIST", "name", "Moisture", "kind", "numeric", "maxValue", 3))));
        okA(200, "POST", "/quality/specifications/" + spec.get("id").asString() + "/activate", null);
        JsonNode plan = okA(201, "POST", "/quality/inspection-plans", Map.of("code", "PLAN-" + UUID.randomUUID()
                .toString().substring(0, 6), "name", "Plan", "stage", "final", "productId", first, "specificationId",
                spec.get("id").asString(), "independentRelease", false));
        JsonNode lot = okA(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", first,
                "quantity", 10, "planId", plan.get("id").asString(), "locationId", main));
        String lotId = lot.get("id").asString();

        JsonNode held = okA(200, "POST", "/quality/inspection-lots/" + lotId + "/hold", Map.of("reason", "Awaiting test"));
        assertThat(held.get("status").asString()).isEqualTo("hold");
        String qc = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code ="
                + " 'QC-QUARANTINE' AND \"deletedAt\" IS NULL").param(org).query(String.class).single());
        assertThat(onHand(first, main)).as("held stock is no longer in the sellable location").isEqualByComparingTo("0");
        assertThat(onHand(first, qc)).isEqualByComparingTo("10");
        JsonNode report = integrityReport();
        assertThat(integrityCheck(report, "quality_hold_unquarantined").get("ok").asBoolean()).isTrue();

        Res down = keyed("POST", "/quality/inspection-lots/" + lotId + "/downgrade", Map.of("productId", second,
                "quantity", 4, "unitCost", 20, "toLocationId", main, "reason", "Moisture above limit"));
        assertThat(down.status()).as(down.raw().body()).isEqualTo(200);
        assertThat(onHand(second, main)).isEqualByComparingTo("4");
        assertGlTieOut();
        assertIntegrityGreen();

        Res rel = keyed("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of("concessionReason",
                "Customer accepted"));
        assertThat(rel.status()).as(rel.raw().body()).isEqualTo(200);
        assertThat(onHand(first, main)).isEqualByComparingTo("6");
        assertThat(onHand(first, qc)).isEqualByComparingTo("0");
        assertGlTieOut();
        assertIntegrityGreen();
    }
}
