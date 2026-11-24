package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.LedgerIntegrityMonitor;
import com.inventoryerp.accounting.application.LedgerIntegrityMonitor.Outcome;
import com.inventoryerp.accounting.application.LedgerIntegrityService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit A5 (P2-5): the customer and supplier sub-ledgers against their control accounts. One measure
 * — base currency at each document's posting rate, less unapplied payments, per partner — behind the AR / AP
 * reconciliation reports, the nightly tie-out snapshot ({@code GET /reports/accounting/tieout}), integrity check I8
 * ({@code ar_ap_tieout}) and the advisory close-checklist item.
 *
 * <p>The first two tests reproduce the two kinds of difference the audit found in the demo books; both failed before the
 * fix.
 */
class ArApTieOutIT extends AccountingFixtures {

    @Autowired
    LedgerIntegrityMonitor monitor;

    // ------------------------------------------------------------------ the DEMO differences

    @Test
    @DisplayName("a foreign invoice storing rate 1 (FX-1: not stated) is measured at the rate it posted at")
    void foreignInvoiceAtItsPostingRate() {
        String ccy = demoRate(testCurrency(), 3740);
        String customer = demoPartner("A5 FX customer", false);
        String inv = demoMoney("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "currencyId",
                ccy, "lines", List.of(Map.of("productId", service(), "quantity", 1, "unitPrice", 600)))).get("id")
                .asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        Map<String, Object> doc = jdbc.sql("SELECT \"exchangeRate\", \"amountResidual\" FROM \"Document\" WHERE id = ?")
                .param(inv).query().singleRow();
        assertThat((BigDecimal) doc.get("exchangeRate")).as("no stated rate stored (FX-1)").isEqualByComparingTo("1");
        BigDecimal base = ((BigDecimal) doc.get("amountResidual")).multiply(new BigDecimal("3740"));
        assertThat(glOnControl(demoOrg, "receivable", customer)).as("posted at the rate table's 3,740")
                .isEqualByComparingTo(base);

        JsonNode row = partnerRow(run("ar.reconciliation"), customer);
        assertThat(row).as("the customer is in the AR reconciliation").isNotNull();
        assertThat(dec(row.get("values").get("subledger"))).as("open item at 3,740, not at the stored 1")
                .isEqualByComparingTo(base);
        assertThat(dec(row.get("values").get("difference"))).isEqualByComparingTo("0");
        assertThat(i8Partners(demoReport())).doesNotContain(customer);
    }

    @Test
    @DisplayName("a customer's credit-note refund is no unapplied supplier payment")
    void customerRefundIsNotAnUnappliedSupplierPayment() {
        String customer = demoPartner("A5 refund customer", false);
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", service(), "quantity", 1, "unitPrice", 50)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        Res refund = call("POST", "/sales/credit-notes/" + cn + "/refund", Map.of("paymentMethod", "bank"), demoKey());
        assertThat(refund.status()).as(refund.raw().body()).isIn(200, 201);

        assertThat(partnerRow(run("ap.reconciliation"), customer)).as("the refund went to the receivable").isNull();
        JsonNode ar = partnerRow(run("ar.reconciliation"), customer);
        if (ar != null) {
            assertThat(dec(ar.get("values").get("difference"))).isEqualByComparingTo("0");
        }
        assertThat(i8Partners(demoReport())).doesNotContain(customer);
    }

    // ------------------------------------------------------------------ one measure everywhere

    @Test
    @DisplayName("I8, the tie-out snapshot, the close checklist and the monitor report each partner that differs")
    void differencesReportedPerPartner() {
        Books b = freshBooks();
        String day = today();
        String receivable = receivableCode(b);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String period = periodFor(b.org(), day);
        String one = partner(b);
        String two = partner(b);

        JsonNode clean = check(report(b), LedgerIntegrityService.AR_AP_TIEOUT);
        assertThat(clean.get("ok").asBoolean()).as(clean.toString()).isTrue();
        assertThat(clean.get("skipped").asBoolean()).isFalse();
        assertThat(monitor(b).opened()).isEmpty();

        // A receivable posted with no document behind it: the sub-ledger has nothing for the partner.
        String orphan = postJe(b, day, List.of(dr(receivable, 250).partner(one), cr(revenue, 250)));
        JsonNode i8 = check(report(b), LedgerIntegrityService.AR_AP_TIEOUT);
        assertThat(i8.get("ok").asBoolean()).isFalse();
        assertThat(i8.get("failures").asLong()).isEqualTo(1);
        JsonNode r = i8.get("rows").get(0);
        assertThat(r.get("side").asString()).isEqualTo("AR");
        assertThat(r.get("partnerId").asString()).isEqualTo(one);
        assertThat(dec(r.get("subledger"))).isEqualByComparingTo("0");
        assertThat(dec(r.get("gl"))).isEqualByComparingTo("250");
        assertThat(dec(r.get("difference"))).as("sub-ledger − GL, as the report shows it").isEqualByComparingTo("-250");

        JsonNode tie = tieOut(b);
        assertThat(tie.get("arBalanced").asBoolean()).isFalse();
        assertThat(dec(tie.get("arVariance"))).as("GL − sub-ledger, as the snapshot always stored it")
                .isEqualByComparingTo("250");
        assertThat(dec(tie.get("arDetails").get("glBalance"))).isEqualByComparingTo("250");
        assertThat(dec(tie.get("arDetails").get("subLedgerBalance"))).isEqualByComparingTo("0");
        assertThat(tie.get("arDetails").get("partners").get(0).get("partnerId").asString()).isEqualTo(one);
        assertThat(tie.get("apBalanced").asBoolean()).isTrue();

        JsonNode item = checklistItem(b, period);
        assertThat(item.get("ok").asBoolean()).isFalse();
        assertThat(item.get("blocking").asBoolean()).as("advisory").isFalse();
        assertThat(item.get("count").asLong()).isEqualTo(1);

        Outcome opened = monitor(b);
        assertThat(opened.opened()).containsExactly(LedgerIntegrityService.AR_AP_TIEOUT);

        // Moved between two customers: the totals still agree, so only a per-partner tie-out sees it.
        inTenant(b.org(), b.ownerId(), () -> posting.reverse(orphan, null, null));
        postJe(b, day, List.of(dr(receivable, 80).partner(one), cr(receivable, 80).partner(two)));
        JsonNode moved = check(report(b), LedgerIntegrityService.AR_AP_TIEOUT);
        assertThat(moved.get("failures").asLong()).isEqualTo(2);
        assertThat(i8Partners(report(b))).containsExactlyInAnyOrder(one, two);
        JsonNode movedTie = tieOut(b);
        assertThat(dec(movedTie.get("arVariance"))).isEqualByComparingTo("0");
        assertThat(movedTie.get("arBalanced").asBoolean()).as("totals agree, two partners differ").isFalse();
        assertThat(movedTie.get("arDetails").get("partnersDiffering").asInt()).isEqualTo(2);
        assertThat(checklistItem(b, period).get("count").asLong()).isEqualTo(2);

        // Put right: the finding resolves.
        postJe(b, day, List.of(dr(receivable, 80).partner(two), cr(receivable, 80).partner(one)));
        assertThat(check(report(b), LedgerIntegrityService.AR_AP_TIEOUT).get("ok").asBoolean()).isTrue();
        assertThat(tieOut(b).get("arBalanced").asBoolean()).isTrue();
        assertThat(checklistItem(b, period).get("ok").asBoolean()).isTrue();
        assertThat(monitor(b).resolved()).containsExactly(LedgerIntegrityService.AR_AP_TIEOUT);
    }

    // ------------------------------------------------------------------ helpers

    private String demoRate(String ccy, int rate) {
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", baseCurrency(demoOrg), "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", rate));
        return ccy;
    }

    private String service() {
        return demoProduct("A5 service " + UUID.randomUUID().toString().substring(0, 4), false, 100, 0);
    }

    private JsonNode run(String reportId) {
        Res r = call("POST", "/reports/run/" + reportId, Map.of("asOf", today()), adminToken);
        assertThat(r.status()).as(reportId + " " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    private static JsonNode partnerRow(JsonNode report, String partnerId) {
        for (JsonNode row : report.get("rows")) {
            if (partnerId.equals(row.get("id").asString())) {
                return row;
            }
        }
        return null;
    }

    private BigDecimal glOnControl(String org, String category, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                        + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE l.\"organizationId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND c.key = ? AND l.\"partnerId\" = ?")
                .params(org, category, partnerId).query(BigDecimal.class).single();
    }

    private JsonNode demoReport() {
        return ok(200, "GET", "/reports/accounting/integrity", null);
    }

    private JsonNode report(Books b) {
        return okAs(b, 200, "GET", "/reports/accounting/integrity", null);
    }

    private static JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key + " in " + report);
    }

    private static List<String> i8Partners(JsonNode report) {
        List<String> out = new ArrayList<>();
        check(report, LedgerIntegrityService.AR_AP_TIEOUT).get("rows").forEach(r -> {
            if (r.hasNonNull("partnerId")) {
                out.add(r.get("partnerId").asString());
            }
        });
        return out;
    }

    private JsonNode tieOut(Books b) {
        okAs(b, 201, "POST", "/reports/accounting/rebuild-snapshots", null);
        return okAs(b, 200, "GET", "/reports/accounting/tieout", null);
    }

    private JsonNode checklistItem(Books b, String period) {
        JsonNode checklist = okAs(b, 200, "GET", "/fiscal-periods/" + period + "/close-checklist", null);
        for (JsonNode c : checklist.get("checks")) {
            if ("ar_ap_tieout".equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no ar_ap_tieout item in " + checklist);
    }

    private Outcome monitor(Books b) {
        return TenantContext.run(TenantStore.organization(b.org()), monitor::check);
    }

    private String receivableCode(Books b) {
        return jdbc.sql("SELECT a.code FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = 'receivable' AND a.\"isPostable\" AND a.\"isActive\""
                + " AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(b.org()).query(String.class).single();
    }

    private String partner(Books b) {
        return okAs(b, 201, "POST", "/partners", Map.of("code", "A5-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Tie-out partner", "isCustomer", true)).get("id").asString();
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }
}
