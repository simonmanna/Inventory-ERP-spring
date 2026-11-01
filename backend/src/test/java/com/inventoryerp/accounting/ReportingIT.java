package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.RevaluationService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.workflow.WorkflowRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Financial reports, POS → GL reconciliation, inventory valuation / GL tie-out, exports, dashboard, snapshots, FX
 * revaluation and the journal_entry workflow on Testcontainers Postgres.
 */
class ReportingIT extends ApiTestSupport {

    @Autowired
    RevaluationService revaluation;

    @Autowired
    PostingService posting;

    @Autowired
    CurrencyService currency;

    @Autowired
    WorkflowRegistry workflows;

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    /** GET without JSON parsing (CSV / PDF downloads). */
    HttpResponse<String> raw(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeAll
    void activity() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res fund = call("POST", "/accounts/cash-flow/deposit", Map.of("accountId", accountId("BANK-DEFAULT"),
                "counterpartAccountId", accountId("3100"), "operationType", "owner_contribution", "amount", 75000,
                "description", "Reporting capital"), h);
        assertThat(fund.status()).as(fund.raw().body()).isEqualTo(200);
    }

    @Test
    @DisplayName("statements: trial balance, detailed (filters, summary, empty classification), ledgers, P&L, BS, cash flow")
    void statements() {
        String today = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        assertThat(ok(200, "GET", "/reports/accounting/trial-balance?to=" + today, null).get("balanced").asBoolean()).isTrue();

        JsonNode detailed = ok(200, "GET", "/reports/accounting/detailed?from=2000-01-01&to=" + today, null);
        assertThat(detailed.get("balanced").asBoolean()).isTrue();
        assertThat(detailed.get("filtered").asBoolean()).isFalse();
        assertThat(detailed.get("maxLines").asInt()).isEqualTo(5000);
        JsonNode bank = null;
        for (JsonNode a : detailed.get("accounts")) {
            if ("BANK-DEFAULT".equals(a.get("code").asString())) {
                bank = a;
            }
        }
        assertThat(bank).isNotNull();
        JsonNode last = bank.get("lines").get(bank.get("lines").size() - 1);
        assertThat(new BigDecimal(last.get("balance").asString())).isEqualByComparingTo(bank.get("closing").asString());
        assertThat(last.get("journalCode").isNull()).isFalse();

        JsonNode one = ok(200, "GET", "/reports/accounting/detailed?summaryOnly=true&accountIds=" + accountId("BANK-DEFAULT"), null);
        assertThat(one.get("filtered").asBoolean()).isTrue();
        assertThat(one.get("accounts")).hasSize(1);
        assertThat(one.get("accounts").get(0).get("lines")).isEmpty();
        assertThat(one.get("truncated").asBoolean()).isFalse();
        JsonNode none = ok(200, "GET", "/reports/accounting/detailed?classification=nope", null);
        assertThat(none.get("accounts")).isEmpty();
        assertThat(none.get("totals").get("closing").asString()).isEqualTo("0");

        JsonNode ledger = ok(200, "GET", "/reports/accounting/account-ledger/" + accountId("BANK-DEFAULT"), null);
        assertThat(ledger.get("lines").size()).isGreaterThan(0);
        assertThat(ok(200, "GET", "/reports/accounting/general-ledger?page=1", null).get("meta").get("pageSize").asInt())
                .isEqualTo(100);
        assertThat(ok(200, "GET", "/reports/accounting/profit-and-loss", null).isObject()).isTrue();
        assertThat(ok(200, "GET", "/reports/accounting/balance-sheet?asOf=" + today, null).get("balanced").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/reports/accounting/balance-sheet/detailed?asOf=" + today, null).isObject()).isTrue();
        assertThat(ok(200, "GET", "/reports/accounting/cash-flow?fromDate=2000-01-01&toDate=" + today, null).isObject()).isTrue();
    }

    @Test
    @DisplayName("balance sheet: accumulated depreciation (contra asset) reduces total assets and the sheet balances")
    void balanceSheetContraAsset() {
        String asOf = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        JsonNode before = ok(200, "GET", "/reports/accounting/balance-sheet?asOf=" + asOf, null);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> posting.post(new PostingRequest("GEN",
                Instant.now().minus(Duration.ofHours(1)), "Depreciation", null, 1, "test", null, null, null, null, null,
                null, List.of(PostingRequest.Line.debit(accountId("5600"), 1000000, "Depreciation"),
                        PostingRequest.Line.credit(accountId("1490"), 1000000, "Depreciation")))));
        JsonNode after = ok(200, "GET", "/reports/accounting/balance-sheet?asOf=" + asOf, null);
        assertThat(after.get("balanced").asBoolean()).as(after.toString()).isTrue();
        assertThat(new BigDecimal(after.get("totalAssets").asString())
                .subtract(new BigDecimal(before.get("totalAssets").asString()))).isEqualByComparingTo("-1000000");
        JsonNode detailed = ok(200, "GET", "/reports/accounting/balance-sheet/detailed?asOf=" + asOf, null);
        assertThat(detailed.get("balanced").asBoolean()).isTrue();
        Res rebuilt = as("POST", "/reports/accounting/rebuild-snapshots", null);
        assertThat(rebuilt.status()).as(rebuilt.raw().body()).isEqualTo(201);
        JsonNode snap = ok(200, "GET", "/reports/accounting/balance-sheet?asOf=" + asOf, null);
        assertThat(snap.get("balanced").asBoolean()).as(snap.toString()).isTrue();
    }

    @Test
    @DisplayName("snapshots rebuild (201) then tie-out; POS → GL reconciliation window and guard")
    void snapshotsAndControls() {
        Res rebuilt = as("POST", "/reports/accounting/rebuild-snapshots", null);
        assertThat(rebuilt.status()).as(rebuilt.raw().body()).isEqualTo(201);
        assertThat(rebuilt.body().get("rebuiltAt").asString()).endsWith("Z");
        JsonNode tieout = ok(200, "GET", "/reports/accounting/tieout", null);
        assertThat(tieout.has("arBalanced")).isTrue();

        JsonNode recon = ok(200, "GET", "/reports/accounting/pos-gl-reconciliation", null);
        assertThat(recon.get("window").get("from").asString()).matches("\\d{4}-\\d\\d-\\d\\dT.*\\.\\d{3}Z");
        assertThat(recon.get("pos").get("invoiceCount").isNumber()).isTrue();
        assertThat(recon.get("inventory").get("unpostedJobs").isNumber()).isTrue();
        assertThat(recon.has("balanced")).isTrue();
        assertThat(as("GET", "/reports/accounting/pos-gl-reconciliation?from=2026-02-01&to=2026-01-01", null).status())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("inventory: valuation (current + historical basis), invalid asOf 400, GL tie-out")
    void inventory() {
        JsonNode current = ok(200, "GET", "/reports/inventory/valuation", null);
        assertThat(current.get("basis").asString()).isEqualTo("current_cost_by_method");
        assertThat(current.get("groupedBy").asString()).isEqualTo("product");
        assertThat(ok(200, "GET", "/reports/inventory/valuation?asOf=2020-01-01", null).get("basis").asString()).isEqualTo("ledger");
        Res bad = as("GET", "/reports/inventory/valuation?asOf=garbage", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).isEqualTo("Invalid asOf: garbage");
        JsonNode tie = ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=5", null);
        assertThat(tie.get("tolerance").asInt()).isEqualTo(5);
        assertThat(tie.has("withinTolerance")).isTrue();
    }

    @Test
    @DisplayName("exports: trial balance + AR aging CSV, invoice PDF 404; dashboard KPIs and activity")
    void exportsAndDashboard() throws Exception {
        HttpResponse<String> csv = raw("/reports/export/trial-balance.csv?asOf=2030-01-01");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.headers().firstValue("Content-Disposition").orElse(""))
                .isEqualTo("attachment; filename=\"trial-balance-2030-01-01.csv\"");
        String[] lines = csv.body().split("\n");
        assertThat(lines[0]).isEqualTo("code,name,category,classification,debit,credit,balance");
        assertThat(lines[1]).matches("[^,]+,.*,-?\\d+\\.\\d{2},-?\\d+\\.\\d{2},-?\\d+\\.\\d{2}");
        assertThat(raw("/reports/export/ar-aging.csv").body()).startsWith("documentNumber,partner,issueDate,dueDate,bucket,amount,residual\n");
        HttpResponse<String> pdf = raw("/reports/export/invoice/x.pdf?id=" + UUID.randomUUID());
        assertThat(pdf.statusCode()).isEqualTo(404);
        assertThat(pdf.body()).isEqualTo("Not found");

        JsonNode kpi = ok(200, "GET", "/reports/dashboard-kpi", null);
        assertThat(kpi.get("cashPosition").isNumber()).isTrue();
        assertThat(kpi.get("arAging").has("over90")).isTrue();
        JsonNode activity = ok(200, "GET", "/reports/dashboard-activity?limit=3", null);
        assertThat(activity.get("data").size()).isBetween(1, 3);
        assertThat(as("GET", "/reports/dashboard-activity?bogus=1", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("FX revaluation: foreign bank balance revalued to the closing rate, gain posted; journal_entry workflow")
    void fxRevaluationAndWorkflow() {
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg).query(String.class).single();
        Instant now = Instant.now();
        RevaluationService.Result result = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> {
            currency.upsertRate("EUR", base, now.minus(Duration.ofDays(2)), new BigDecimal("2"), "test");
            posting.post(new PostingRequest("GEN", now.minus(Duration.ofDays(1)), "EUR deposit", "EUR", 2, "test", null, null,
                    null, null, null, null, List.of(PostingRequest.Line.debit(accountId("BANK-DEFAULT"), 100, "EUR in"),
                            PostingRequest.Line.credit(accountId("3100"), 100, "EUR in"))));
            currency.upsertRate("EUR", base, now.minus(Duration.ofMinutes(1)), new BigDecimal("2.5"), "test");
            return revaluation.run(now);
        });
        assertThat(result.revalued()).isEqualTo(1);
        assertThat(result.totalGain()).isEqualTo("50");
        BigDecimal fxGain = jdbc.sql("SELECT \"fxGain\" FROM \"FxRevaluation\" WHERE \"organizationId\" = ? AND \"fiscalPeriodId\" = 'adhoc'")
                .param(demoOrg).query(BigDecimal.class).single();
        assertThat(fxGain).isEqualByComparingTo("50");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = 'fx_revaluation'")
                .param(demoOrg).query(Long.class).single()).isEqualTo(1);

        assertThat(workflows.get("journal_entry").transitions().get(0).action()).isEqualTo("reverse");
    }
}
