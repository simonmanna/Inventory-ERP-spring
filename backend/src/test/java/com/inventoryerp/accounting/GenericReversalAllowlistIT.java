package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.SubledgerSources;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import com.inventoryerp.kernel.web.ApiPathConfig;
import com.inventoryerp.kernel.workflow.WorkflowService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit 2026-11-23, P1-2 (fix step A2): the generic {@code POST /journal-entries/{id}/reverse}
 * accepted entries of every source outside the customer / supplier sub-ledgers (period close, VAT settlement, cash,
 * fixed assets, payroll, stock, …) and left their own records out of step with the GL. It now accepts manual entries
 * and recurring-journal runs only (owner decision 2026-11-23) and names the action that reverses anything else.
 *
 * <p>One test per row of the audit's behaviour table. The AR/AP documents row is {@link DocumentReversalIT}; reversing
 * a control-account adjustment (manual, needs {@code journal_entry:control_adjust}) is {@link ControlAccountPolicyIT}.
 */
class GenericReversalAllowlistIT extends AccountingFixtures {

    private static final String CASH = "1100";

    @Autowired
    WorkflowService workflows;

    @Test
    @DisplayName("A2: manual entries and recurring-journal runs stay reversible; a reversal is judged by its original")
    void manualAndRecurringStayReversible() {
        Books b = freshBooks();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String manual = postManualJe(b, today(), List.of(dr(CASH, 25), cr(revenue, 25)));
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + manual, null).has("sourceDocument")).isFalse();
        String reversal = okAs(b, 200, "POST", "/journal-entries/" + manual + "/reverse", null).get("id").asString();
        // The reversal of a manual entry is judged by that manual entry: reversible again.
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + reversal, null).has("sourceDocument")).isFalse();
        okAs(b, 200, "POST", "/journal-entries/" + reversal + "/reverse", null);

        String recurring = systemEntry(b, "recurring_entry", UUID.randomUUID().toString());
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + recurring, null).has("sourceDocument")).isFalse();
        okAs(b, 200, "POST", "/journal-entries/" + recurring + "/reverse", null);
        assertThat(status(recurring)).isEqualTo("reversed");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("P1-2: a period-close entry is refused and names the reopen route; the reopen's own reversal too")
    void periodCloseEntryRefused() {
        Books b = freshBooks();
        LocalDate lastMonth = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        String period = monthlyPeriods(b, lastMonth, lastMonth.plusMonths(1)).getFirst();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = lastMonth.plusDays(9).toString();
        postManualJe(b, day, List.of(dr(CASH, 300), cr(revenue, 300)));
        String closing = closePeriod(b, day);
        assertThat(closing).isNotNull();

        refused(b, closing, "period close P " + lastMonth, "reopen the fiscal period",
                "(POST /fiscal-periods/" + period + "/reopen)");
        JsonNode block = okAs(b, 200, "GET", "/journal-entries/" + closing, null).get("sourceDocument");
        assertThat(block.get("documentType").asString()).isEqualTo("period_close");
        assertThat(block.get("route").asString()).isEqualTo("POST /fiscal-periods/" + period + "/reopen");
        assertThat(block.get("path").asString()).isEqualTo("/fiscal-periods");
        assertThat(block.get("genericReversal").asBoolean()).isFalse();
        assertThat(periodStatus(period)).isEqualTo("closed");

        // The reopen reverses the closing entry itself; that reversal is not reversed generically either.
        Res reopen = moneyAs(b, "POST", "/fiscal-periods/" + period + "/reopen", null);
        assertThat(reopen.status()).as(reopen.raw().body()).isEqualTo(200);
        String reopenReversal = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(closing)
                .query(String.class).single();
        Res again = as(b, "POST", "/journal-entries/" + reopenReversal + "/reverse", null);
        assertThat(again.status()).as(again.raw().body()).isEqualTo(400);
        assertThat(again.body().get("message").asString())
                .contains("is the reversal of an entry posted by period close", "cannot be reversed generically");
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + reopenReversal, null).get("sourceDocument")
                .get("viaReversal").asBoolean()).isTrue();
        LedgerInvariants.entriesBalanced(jdbc, b.org(), null);
    }

    @Test
    @DisplayName("A2: a VAT settlement entry is refused; the refusal names the settlement undo of A3")
    void vatSettlementRefused() {
        Books b = freshBooks();
        String to = LocalDate.now().toString();
        String entry = systemEntry(b, "tax_settlement", to);
        refused(b, entry, "VAT settlement " + to, "undo the latest VAT settlement",
                "(POST /tax-settlements/{settlementId}/reverse)");
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + entry, null).get("sourceDocument").get("path").asString())
                .isEqualTo("/period-end");
    }

    @Test
    @DisplayName("A2: an FX revaluation is refused, and its automatic reversal keeps the FX-1 refusal")
    void fxRevaluationRefused() {
        Books b = freshBooks();
        String revaluation = systemEntry(b, "fx_revaluation", "account:USD:" + LocalDate.now());
        refused(b, revaluation, "FX revaluation", "run the FX revaluation again for the date", "(POST /fx-revaluations)");

        String automatic = (String) inTenant(b.org(), b.ownerId(), () -> posting.reverse(revaluation, null,
                "Automatic reversal of FX revaluation")).get("id");
        Res r = as(b, "POST", "/journal-entries/" + automatic + "/reverse", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("This entry is the automatic reversal of an FX"
                + " revaluation and cannot be reversed by hand; run the FX revaluation again for the date instead");
        JsonNode block = okAs(b, 200, "GET", "/journal-entries/" + automatic, null).get("sourceDocument");
        assertThat(block.get("documentType").asString()).isEqualTo("fx_revaluation");
        assertThat(block.get("viaReversal").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A2: opening-balance batches stay refused; a payment allocation is undone by voiding the payment")
    void openingBalanceAndPaymentAllocationRefused() {
        Books b = freshBooks();
        refused(b, systemEntry(b, "opening_balance", UUID.randomUUID().toString()), "opening-balance batch",
                "out of step with the control account", "post a control-account adjustment naming the partner");
        String payment = UUID.randomUUID().toString();
        refused(b, systemEntry(b, "payment_allocation", payment), "void the payment",
                "(POST /payments/" + payment + "/void)");
    }

    @Test
    @DisplayName("A2: a source with a reversal of its own is refused and the message names that route")
    void ownRouteSourcesNameTheirRoute() {
        Books b = freshBooks();
        String run = UUID.randomUUID().toString();
        String payroll = systemEntry(b, "payroll_run", run);
        refused(b, payroll, "payroll run", "reverse the payroll run", "(POST /hr/payroll/runs/" + run + "/reverse)");
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + payroll, null).get("sourceDocument").get("path")
                .asString()).isEqualTo("/hr/payroll/runs/" + run);

        String asset = UUID.randomUUID().toString();
        refused(b, systemEntry(b, "asset_acquisition", asset), "asset capitalisation",
                "(DELETE /fixed-assets/" + asset + "/acquisition)");
        String line = UUID.randomUUID().toString();
        refused(b, systemEntry(b, "bank_statement_line", line), "undo the bank match",
                "(POST /bank-reconciliation/unmatch/" + line + ")");
        // cost_allocation comes from two engines; no costing AllocationRun row here, so it is a cost-centre run.
        String ccRun = UUID.randomUUID().toString();
        refused(b, systemEntry(b, "cost_allocation", ccRun), "cost-centre allocation run",
                "(POST /cost-centers/allocation-runs/" + ccRun + "/reverse)");
        String revaluation = UUID.randomUUID().toString();
        refused(b, systemEntry(b, "inventory_revaluation", revaluation), "reverse the revaluation",
                "(POST /inventory/revaluations/" + revaluation + "/reverse)");

        // Documents posted under their number: the route takes the id found by that number (none here, so the route
        // keeps its placeholder). Runs each number lookup, so a wrong table or column fails here.
        Map<String, String> byNumber = Map.of("stock_out", "POST /inventory/stock-outs/{id}/reverse",
                "waste", "POST /inventory/waste/{id}/reverse",
                "stock_adjustment", "POST /inventory/adjustments/{id}/reverse",
                "stock_transfer", "POST /inventory/transfers/{id}/reverse",
                "production_order", "POST /manufacturing/production-orders/{id}/reverse",
                "landed_cost", "POST /procurement/landed-costs/{id}/reverse");
        byNumber.forEach((type, route) -> {
            String code = "A2-" + type.toUpperCase() + "-1";
            refused(b, systemEntry(b, type, code), " " + code + " ", "(" + route + ")");
        });
    }

    @Test
    @DisplayName("A2: a source with no reversal of its own is refused: an adjusting manual journal corrects it")
    void noRouteSourcesNeedAnAdjustingJournal() {
        Books b = freshBooks();
        for (String type : List.of("treasury_transfer", "cash_flow_deposit", "cash_session_variance", "tender_settlement",
                "asset_depreciation", "deferral_release", "payroll_remittance", "hr_gratuity", "store_credit_issue",
                "rebate_accrual", "fx_designation", "intercompany_journal")) {
            refused(b, systemEntry(b, type, UUID.randomUUID().toString()),
                    "correct it with an adjusting manual journal entry", "has no reversal of its own");
        }
    }

    @Test
    @DisplayName("A2: an unlisted or new source is refused by default")
    void unlistedSourceRefusedByDefault() {
        Books b = freshBooks();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        refused(b, systemEntry(b, "a2_unreviewed_source", UUID.randomUUID().toString()), "a2 unreviewed source",
                "reverse it from the document or process that posted it, or correct it with an adjusting manual journal");
        // The fixtures' own system source.
        refused(b, postJe(b, today(), List.of(dr(CASH, 5), cr(revenue, 5))), "acc0");
        // A reversal a document workflow posted under its own source type.
        refused(b, systemEntry(b, "stock_out_reversal", "SO-A2-1"), "it is itself a reversal");
        assertThat(SubledgerSources.genericallyReversible(null)).isTrue();
        assertThat(SubledgerSources.genericallyReversible("recurring_entry")).isTrue();
        assertThat(SubledgerSources.genericallyReversible("deferral_release")).isFalse();
    }

    @Test
    @DisplayName("A2: the journal_entry workflow's reverse transition applies the same guard")
    void workflowTransitionGuarded() {
        Books b = freshBooks();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String system = systemEntry(b, "treasury_transfer", UUID.randomUUID().toString());
        assertThatThrownBy(() -> inTenant(b.org(), b.ownerId(),
                () -> workflows.transition("journal_entry", system, "reverse", null, null)))
                .hasMessageContaining("cannot be reversed generically");
        assertThat(status(system)).isEqualTo("posted");

        String manual = postManualJe(b, today(), List.of(dr(CASH, 15), cr(revenue, 15)));
        inTenant(b.org(), b.ownerId(), () -> workflows.transition("journal_entry", manual, "reverse", null, null));
        assertThat(status(manual)).isEqualTo("reversed");
    }

    @Test
    @DisplayName("A2: every route a refusal names is served by the API")
    void namedRoutesExist() {
        Set<String> served = new HashSet<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.startsWith(ApiPathConfig.PREFIX) ? pattern.substring(ApiPathConfig.PREFIX.length())
                        : pattern;
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    served.add(method.name() + " " + normalise(path));
                }
            }
        }
        assertThat(SubledgerSources.kindsWithRoutes()).isNotEmpty().allSatisfy(kind ->
                assertThat(served).as(kind.documentType() + " → " + kind.route()).contains(normalise(kind.route())));
    }

    // ------------------------------------------------------------------ helpers

    /** {@code {assetId}} and {@code {id}} alike: path variables compare by position only. */
    private static String normalise(String route) {
        return route.replaceAll("\\{[^}]*}", "{}");
    }

    /** A posted base entry (Dr cash / Cr revenue 10) of {@code sourceType} through the engine; returns its id. */
    private String systemEntry(Books b, String sourceType, String sourceId) {
        String revenue = accountCodeOfSection(b.org(), "revenue");
        Map<String, Object> entry = inTenant(b.org(), b.ownerId(), () -> posting.post(PostingRequest.of("GEN",
                Instant.now(), "A2 " + sourceType, sourceType, sourceId, null, List.of(
                        PostingRequest.Line.debit(account(b.org(), CASH), 10, "Dr"),
                        PostingRequest.Line.credit(account(b.org(), revenue), 10, "Cr")))));
        return (String) entry.get("id");
    }

    /** The generic reversal answers 400 with every {@code parts}, and changes nothing. */
    private void refused(Books b, String entryId, String... parts) {
        Res r = as(b, "POST", "/journal-entries/" + entryId + "/reverse", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("cannot be reversed generically").contains(parts);
        assertThat(status(entryId)).as("still posted").isEqualTo("posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entryId)
                .query(Long.class).single()).isZero();
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + entryId, null).get("sourceDocument")
                .get("genericReversal").asBoolean()).isFalse();
    }

    private String status(String entryId) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entryId).query(String.class)
                .single();
    }

    private String periodStatus(String periodId) {
        return jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodId).query(String.class)
                .single();
    }
}
