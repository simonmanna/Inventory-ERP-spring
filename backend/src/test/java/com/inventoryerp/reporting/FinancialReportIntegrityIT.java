package com.inventoryerp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Financial reporting FR-1..FR-9 integrity (catalogue §7 acceptance criteria) on a fresh organization: trial balance
 * balances, the cash flow statements reconcile opening to closing cash, changes in equity ties to the balance sheet,
 * branch trial balances add up, the AR aging ties to the receivable control account net of unapplied receipts and
 * reconciles per customer, the VAT return ties to the VAT account, the budget column, ratios, expected credit loss,
 * the general ledger closing balance, and audit flags for backdated entries and duplicate payments.
 */
class FinancialReportIntegrityIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    AccountResolverService accounts;

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static JsonNode row(JsonNode result, String id) {
        for (JsonNode r : result.get("rows")) {
            if (r.get("id").asString().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("no row " + id + " in " + result.get("rows"));
    }

    static boolean ok(JsonNode result, String code) {
        for (JsonNode c : result.get("checks")) {
            if (c.get("code").asString().equals(code)) {
                if (!c.get("ok").asBoolean()) {
                    throw new AssertionError(code + ": " + c);
                }
                return true;
            }
        }
        throw new AssertionError("no check " + code + " in " + result.get("checks"));
    }

    JsonNode run(String id, Object body, String token) {
        Res r = call("POST", "/reports/run/" + id, body, token);
        assertThat(r.status()).as(id + " " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    @Test
    @DisplayName("FR-1..9: statements, subledgers, tax, budget, ratios, ECL and audit reconcile")
    void integrity() throws Throwable {
        try {
            checks();
        } catch (Throwable e) {
            String diag = System.getenv("REPORT_FAILURES_FILE");
            if (diag != null) {
                java.nio.file.Files.writeString(java.nio.file.Path.of(diag), e + " @ " + e.getStackTrace()[0] + " "
                        + (e.getStackTrace().length > 2 ? e.getStackTrace()[2] : ""));
            }
            throw e;
        }
    }

    void checks() {
        String code = "fri-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Integrity Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant early = now.minusSeconds(86_400L * 10);
        String day = today.toString();
        String branchA = UUID.randomUUID().toString();
        String branchB = UUID.randomUUID().toString();
        for (String[] b : new String[][] {{branchA, "A"}, {branchB, "B"}}) {
            jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                    + " now())").params(b[0], org, "BR-" + b[1], "Branch " + b[1]).update();
        }
        String customer = UUID.randomUUID().toString();
        String supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"creditLimit\","
                + " \"updatedAt\") VALUES (?, ?, 'C1', 'Cement Buyer', true, 500, now())").params(customer, org).update();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\") VALUES"
                + " (?, ?, 'S1', 'Diesel Supplier', true, now())").params(supplier, org).update();
        String taxId = UUID.randomUUID().toString();
        Map<String, String> acc = TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            Map<String, String> m = new HashMap<>();
            m.put("cash", accounts.ensureByCode("1105", "Test cash", "cash", null));
            m.put("bank", accounts.ensureByCode("1205", "Test bank", "bank", null));
            m.put("ar", accounts.ensureByCode("1305", "Test receivables", "receivable", null));
            m.put("rev", accounts.ensureByCode("4950", "Test revenue", "revenue", null));
            m.put("vat", accounts.ensureByCode("2205", "Test VAT payable", "tax", null));
            m.put("opex", accounts.ensureByCode("6950", "Test overhead", "operating_expense", null));
            m.put("eq", accounts.ensureByCode("3950", "Test capital", "equity", null));
            return m;
        });
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, type, rate, \"vatCategory\", \"accountId\","
                + " \"updatedAt\") VALUES (?, ?, 'VAT 18%', 'VAT18', 'vat', 18, 'standard', ?, now())").params(taxId, org,
                acc.get("vat")).update();
        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            Object[][] entries = {
                // date, debit, credit, amount, branch, partner, sourceType
                {early, "cash", "eq", 5000, branchA, null, "test"},
                {now, "ar", "rev", 1000, branchA, customer, "invoice"},
                {now, "ar", "vat", 180, branchA, customer, "invoice"},
                {now, "bank", "ar", 400, branchB, customer, "payment"},
                {now, "opex", "cash", 300, branchB, null, "test"},
                {now, "bank", "cash", 1000, branchA, null, "test"}};
            for (Object[] e : entries) {
                String partner = (String) e[5];
                String branch = (String) e[4];
                posting.post(new PostingRequest("GEN", (Instant) e[0], "fri", null, null, (String) e[6],
                        UUID.randomUUID().toString(), null, null, branch, null, null, List.of(
                                new Line(acc.get((String) e[1]), e[3], null, partner, "dr", branch, null, null),
                                new Line(acc.get((String) e[2]), null, e[3], partner, "cr", branch, null, null))));
            }
            return null;
        });
        // the invoice and the unallocated receipt in the subledger
        String doc = UUID.randomUUID().toString();
        Timestamp nowTs = Timestamp.valueOf(now.atOffset(ZoneOffset.UTC).toLocalDateTime());
        jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\", \"documentType\", \"partnerId\","
                + " \"issueDate\", \"dueDate\", status, subtotal, \"taxAmount\", \"totalAmount\", \"amountResidual\","
                + " \"branchId\", \"updatedAt\", \"documentTypeId\") VALUES (?, ?, 'INV-1', 'sales_invoice', ?, ?, ?,"
                + " 'posted', 1000, 180, 1180, 1180, ?, now(), (SELECT id FROM \"DocumentTypeDef\" ORDER BY (code ="
                + " 'sales_invoice') DESC LIMIT 1))").params(doc, org, customer, nowTs, nowTs, branchA).update();
        jdbc.sql("INSERT INTO \"DocumentLine\" (id, \"organizationId\", \"documentId\", description, quantity,"
                + " \"unitPrice\", \"taxId\", subtotal, \"taxAmount\", total) VALUES (?, ?, ?, 'Aggregate 20mm', 10, 100, ?,"
                + " 1000, 180, 1180)").params(UUID.randomUUID().toString(), org, doc, taxId).update();
        for (int i = 0; i < 2; i++) {
            jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                    + " \"paymentDate\", \"paymentMethod\", amount, \"allocatedAmount\", \"unallocatedAmount\", status,"
                    + " \"branchId\", \"updatedAt\") VALUES (?, ?, ?, 'inbound', ?, ?, 'mtn_momo', ?, 0, ?, 'posted', ?,"
                    + " now())").params(UUID.randomUUID().toString(), org, "RCPT-" + i, customer, nowTs, i == 0 ? 400 : 0,
                    i == 0 ? 400 : 0, branchB).update();
        }
        // budget for the revenue account this month
        String budget = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Budget\" (id, \"organizationId\", name, \"fiscalYear\", \"startDate\", \"endDate\","
                + " status) VALUES (?, ?, 'Plan', ?, ?, ?, 'approved')").params(budget, org, today.getYear(),
                today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31)).update();
        jdbc.sql("INSERT INTO \"BudgetLine\" (id, \"organizationId\", \"budgetId\", \"accountId\", period, amount) VALUES"
                + " (?, ?, ?, ?, ?, 1200)").params(UUID.randomUUID().toString(), org, budget, acc.get("rev"),
                today.toString().substring(0, 7)).update();

        Map<String, Object> month = Map.of("from", today.withDayOfMonth(1).isAfter(today.minusDays(10))
                ? today.minusDays(10).toString() : today.withDayOfMonth(1).toString(), "to", day);

        // FR-1 trial balance, cash flows, equity
        JsonNode tb = run("fs.trial-balance", Map.of("from", day, "to", day), token);
        ok(tb, "OPENING_BALANCES");
        ok(tb, "PERIOD_BALANCES");
        ok(tb, "CLOSING_BALANCES");
        JsonNode cf = run("fs.cash-flow", Map.of("from", day, "to", day), token);
        ok(cf, "CASH_RECONCILES_CURRENT");
        ok(cf, "ALL_ACCOUNTS_MAPPED");
        assertThat(dec(row(cf, "OPENING_CASH").get("values").get("CURRENT"))).isEqualByComparingTo("5000");
        assertThat(dec(row(cf, "NET").get("values").get("CURRENT"))).isEqualByComparingTo("100");
        JsonNode direct = run("fs.cash-flow-direct", Map.of("from", day, "to", day), token);
        ok(direct, "CASH_RECONCILES");
        assertThat(dec(row(direct, "R_CUST").get("values").get("amount"))).isEqualByComparingTo("400");
        assertThat(dec(row(direct, "P_EXP").get("values").get("amount"))).isEqualByComparingTo("-300");
        JsonNode soce = run("fs.changes-in-equity", Map.of("from", day, "to", day), token);
        ok(soce, "TIES_TO_BALANCE_SHEET");
        assertThat(dec(row(soce, "PROFIT").get("values").get("retained"))).isEqualByComparingTo("700");
        assertThat(dec(row(soce, "CLOSING").get("values").get("total"))).isEqualByComparingTo("5700");
        JsonNode reg = run("gl.journal-register", Map.of("from", day, "to", day), token);
        ok(reg, "ALL_BALANCED");
        JsonNode book = run("cash.bank-book", Map.of("from", day, "to", day), token);
        assertThat(dec(row(book, "C:" + acc.get("bank")).get("values").get("balance"))).isEqualByComparingTo("1400");
        JsonNode btb = run("branch.trial-balance", Map.of("asOf", day), token);
        ok(btb, "BRANCHES_ADD_UP");
        ok(btb, "BALANCED");

        // FR-2 receivables: aging ties to the control account net of the unapplied receipt
        JsonNode aging = run("ar.aging-summary", Map.of("asOf", day), token);
        ok(aging, "TIES_TO_GL");
        assertThat(dec(row(aging, customer).get("values").get("total"))).isEqualByComparingTo("780");
        assertThat(dec(row(aging, customer).get("values").get("unapplied"))).isEqualByComparingTo("-400");
        JsonNode recon = run("ar.reconciliation", Map.of("asOf", day), token);
        ok(recon, "TOTAL_RECONCILES");
        ok(recon, "PARTNERS_RECONCILE");
        JsonNode credit = run("ar.credit-utilization", Map.of("asOf", day), token);
        assertThat(dec(row(credit, customer).get("values").get("available"))).isEqualByComparingTo("-280");
        assertThat(call("POST", "/reports/run/ar.customer-statement", Map.of("from", day, "to", day), token)
                .status()).isEqualTo(400);
        JsonNode stmt = run("ar.customer-statement", Map.of("from", day, "to", day, "partnerIds", List.of(customer)),
                token);
        assertThat(dec(row(stmt, "C:" + customer).get("values").get("balance"))).isEqualByComparingTo("780");
        JsonNode unalloc = run("ar.unallocated-receipts", Map.of("asOf", day), token);
        assertThat(dec(unalloc.get("totals").get("open"))).isEqualByComparingTo("400");
        assertThat(call("POST", "/reports/run/ar.aging-summary", Map.of("options", Map.of("buckets", "x")), token)
                .status()).isEqualTo(400);
        assertThat(call("POST", "/reports/run/ar.aging-summary", Map.of("options", Map.of("nope", "1")), token)
                .status()).isEqualTo(400);

        // FR-3 VAT return ties to the VAT account; payment methods group MoMo
        JsonNode vat = run("tax.vat-return", Map.of("from", day, "to", day), token);
        assertThat(dec(row(vat, "BOX1").get("values").get("amount"))).isEqualByComparingTo("1000");
        assertThat(dec(row(vat, "BOX9").get("values").get("amount"))).isEqualByComparingTo("180");
        ok(vat, "TIES_TO_GL");
        JsonNode methods = run("cash.payment-methods", Map.of("from", day, "to", day), token);
        assertThat(methods.toString()).contains("MTN Mobile Money");
        JsonNode transfers = run("cash.transfers", Map.of("from", day, "to", day), token);
        assertThat(dec(transfers.get("totals").get("amount"))).isEqualByComparingTo("1000");

        // FR-5 budget column and ratios
        JsonNode pl = run("fs.profit-and-loss", Map.of("from", day, "to", day, "compare", List.of("BUDGET",
                "VARIANCE")), token);
        assertThat(dec(row(pl, "REV").get("values").get("BUDGET"))).isEqualByComparingTo("1200");
        assertThat(dec(row(pl, "REV").get("values").get("VARIANCE"))).isEqualByComparingTo("-200");
        JsonNode bva = run("mgmt.budget-vs-actual", Map.of("from", day, "to", day), token);
        assertThat(dec(row(bva, acc.get("rev")).get("values").get("variance"))).isEqualByComparingTo("-200");
        JsonNode ratios = run("mgmt.ratios", Map.of("from", day, "to", day), token);
        assertThat(dec(row(ratios, "CR").get("values").get("CURRENT"))).isEqualByComparingTo(new BigDecimal("5880")
                .divide(new BigDecimal("180"), 2, java.math.RoundingMode.HALF_UP));
        JsonNode cc = run("mgmt.monthly-pl", Map.of("from", day, "to", day), token);
        ok(cc, "SEGMENTS_ADD_UP");

        // FR-6 branch P&L adds up to the company
        JsonNode bpl = run("branch.profit-and-loss", Map.of("from", day, "to", day), token);
        ok(bpl, "SEGMENTS_ADD_UP");
        assertThat(dec(row(bpl, "NP").get("values").get("S_" + branchB))).isEqualByComparingTo("-300");

        // FR-8 expected credit loss on current receivables at 1 %
        JsonNode ecl = run("adv.ecl", Map.of("asOf", day), token);
        assertThat(dec(row(ecl, "current").get("values").get("ecl"))).isEqualByComparingTo("11.80");

        // FR-7 audit: a backdated entry and a duplicate payment
        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            posting.post(new PostingRequest("GEN", early, "late entry", null, null, "test", UUID.randomUUID()
                    .toString(), null, null, null, null, null, List.of(new Line(acc.get("opex"), 50, null, null, "dr", null, null, null), new Line(acc.get(
                            "cash"), null, 50, null, "cr", null, null, null))));
            return null;
        });
        JsonNode back = run("audit.backdated", Map.of("from", day, "to", day), token);
        assertThat(back.get("rows").size()).isGreaterThanOrEqualTo(1);
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                + " \"paymentDate\", \"paymentMethod\", amount, \"allocatedAmount\", \"unallocatedAmount\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'RCPT-2', 'inbound', ?, ?, 'cash', 400, 0, 400, 'posted', now())").params(
                UUID.randomUUID().toString(), org, customer, nowTs).update();
        JsonNode dup = run("audit.duplicate-payments", Map.of("from", day, "to", day), token);
        assertThat(dup.get("rows")).hasSize(1);
        JsonNode manual = run("audit.manual-journals", Map.of("from", month.get("from"), "to", day), token);
        assertThat(manual.toString()).contains("manual");

        // drill-down from every amount the engine marks reaches journal lines
        JsonNode drill = row(tb, acc.get("rev")).get("drill").get("credit");
        JsonNode lines = run(drill.get("reportId").asString(), drill.get("params"), token);
        assertThat(lines.get("rows").size()).isGreaterThanOrEqualTo(1);
    }
}
