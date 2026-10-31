package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S005 accounting completion: budgets + budget-vs-actual, branch consolidation, opening balances (GL, customer,
 * supplier), tax summary, customer / supplier statements and invoice PDF / e-mail.
 */
class AccountingExtensionsIT extends ApiTestSupport {

    final String today = LocalDate.now().toString();

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    String partner(boolean supplier, String email) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "P-" + suffix(), (supplier ? "Supplier " : "Customer ") + suffix(), email, supplier,
                        !supplier)
                .update();
        return id;
    }

    String checkerToken;

    /** A second administrator: manual journal entries are maker-checker (the creator cannot post). */
    String checker() {
        if (checkerToken == null) {
            String adminRole = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                    .param(demoOrg).query(String.class).single();
            String email = "checker-" + UUID.randomUUID() + "@demo.test";
            createUser(demoOrg, email, "Check@1234567", adminRole);
            checkerToken = login("DEMO", email, "Check@1234567");
        }
        return checkerToken;
    }

    Map<String, String> key() {
        return key(adminToken);
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** Post a manual GEN entry (debit / credit pairs) on today's date, optionally tagged with branch / cost centre. */
    void journal(String debitAccount, String creditAccount, int amount, String branchId, String costCenterId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journalCode", "GEN");
        body.put("date", today);
        body.put("description", "IT " + suffix());
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        if (costCenterId != null) {
            body.put("costCenterId", costCenterId);
        }
        body.put("lines", List.of(Map.of("accountId", debitAccount, "debit", amount, "credit", 0),
                Map.of("accountId", creditAccount, "debit", 0, "credit", amount)));
        Res created = call("POST", "/journal-entries", body, key());
        assertThat(created.status()).as(created.raw().body()).isBetween(200, 201);
        if (!"posted".equals(created.body().get("status").asString())) {
            Res posted = call("POST", "/journal-entries/" + created.body().get("id").asString() + "/post", null,
                    key(checker()));
            assertThat(posted.status()).as(posted.raw().body()).isBetween(200, 201);
        }
    }

    HttpResponse<byte[]> raw(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("budgets: draft lines, validation, approve locks, reopen, vs-actual by cost centre, delete")
    void budgets() {
        String cc = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(cc, demoOrg, "CC-" + suffix(), "Kitchen " + suffix()).update();
        int year = LocalDate.now().getYear();
        String month = YearMonth.now().toString();
        String salary = account("5710");
        String revenue = account("4100");
        JsonNode b = ok(201, "POST", "/budgets", Map.of("name", "Budget " + suffix(), "fiscalYear", year,
                "costCenterId", cc, "lines", List.of(Map.of("accountId", salary, "period", month, "amount", 1000),
                        Map.of("accountId", revenue, "period", month, "amount", 5000))));
        String id = b.get("id").asString();
        assertThat(b.get("status").asString()).isEqualTo("draft");
        assertThat(b.get("lines")).hasSize(2);
        assertThat(b.get("totalAmount").asString()).isEqualTo("6000");

        Res outside = as("PUT", "/budgets/" + id + "/lines", Map.of("lines", List.of(Map.of("accountId", salary,
                "period", (year + 1) + "-01", "amount", 1))));
        assertThat(outside.body().get("message").asString()).contains("outside the budget dates");
        Res cash = as("PUT", "/budgets/" + id + "/lines", Map.of("lines", List.of(Map.of("accountId", account("1100"),
                "period", month, "amount", 1))));
        assertThat(cash.body().get("message").asString()).contains("does not allow budgeting");
        Res dup = as("POST", "/budgets", Map.of("name", b.get("name").asString(), "fiscalYear", year));
        assertThat(dup.status()).isEqualTo(409);

        journal(salary, account("1100"), 1200, null, cc);
        journal(account("1100"), revenue, 4000, null, cc);
        JsonNode vs = ok(200, "GET", "/budgets/" + id + "/vs-actual", null);
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        vs.get("rows").forEach(r -> rows.put(r.get("accountId").asString(), r));
        assertThat(rows.get(salary).get("actual").asString()).isEqualTo("1200");
        assertThat(rows.get(salary).get("variance").asString()).isEqualTo("200");
        assertThat(rows.get(salary).get("favorable").asBoolean()).isFalse();
        assertThat(rows.get(revenue).get("actual").asString()).isEqualTo("4000");
        assertThat(rows.get(revenue).get("favorable").asBoolean()).isFalse();
        assertThat(vs.get("totals").get("net").get("budget").asString()).isEqualTo("4000");
        assertThat(vs.get("totals").get("net").get("actual").asString()).isEqualTo("2800");

        assertThat(ok(200, "POST", "/budgets/" + id + "/approve", null).get("status").asString()).isEqualTo("approved");
        Res locked = as("PUT", "/budgets/" + id + "/lines", Map.of("lines", List.of()));
        assertThat(locked.body().get("message").asString()).contains("locked");
        assertThat(as("DELETE", "/budgets/" + id, null).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/budgets/" + id + "/reopen", null).get("status").asString()).isEqualTo("draft");
        assertThat(ok(200, "GET", "/budgets?status=draft&pageSize=100", null).toString()).contains(id);
        assertThat(as("DELETE", "/budgets/" + id, null).status()).isEqualTo(204);
        assertThat(as("GET", "/budgets/" + id, null).status()).isEqualTo(404);
        assertThat(as("POST", "/budgets", Map.of("fiscalYear", year)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("consolidated P&L and TB attribute lines to their branch")
    void consolidated() {
        String branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, demoOrg, "BR-" + suffix(), "Branch " + suffix()).update();
        journal(account("1100"), account("4100"), 750, branch, null);
        JsonNode pl = ok(200, "GET", "/reports/accounting/consolidated?statement=pl", null);
        assertThat(pl.get("branches").toString()).contains(branch);
        JsonNode revenueRow = null;
        for (JsonNode r : pl.get("rows")) {
            if ("4100".equals(r.get("code").asString())) {
                revenueRow = r;
            }
        }
        assertThat(revenueRow).isNotNull();
        assertThat(revenueRow.get("byBranch").get(branch).asString()).isEqualTo("750");
        assertThat(pl.get("totals").get("netProfit").get("byBranch").get(branch).asString()).isEqualTo("750");
        JsonNode tb = ok(200, "GET", "/reports/accounting/consolidated?statement=tb", null);
        assertThat(tb.get("totals").get("net").get("byBranch").get(branch).asString()).isEqualTo("0");
        assertThat(as("GET", "/reports/accounting/consolidated?statement=x", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("opening balances: GL entry balanced to equity, customer / supplier open items, statements, aging")
    void openingBalances() {
        String customer = partner(false, null);
        String supplier = partner(true, null);
        Map<String, Object> body = Map.of("date", today, "reference", "Migration",
                "accounts", List.of(Map.of("accountId", account("1100"), "debit", 500),
                        Map.of("accountId", account("1200"), "debit", 300)),
                "customers", List.of(Map.of("partnerId", customer, "amount", 250, "dueDate", today)),
                "suppliers", List.of(Map.of("partnerId", supplier, "amount", 90)));
        Res noKey = as("POST", "/opening-balances", body);
        assertThat(noKey.status()).isEqualTo(400);
        Res posted = call("POST", "/opening-balances", body, key());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        String je = posted.body().get("journalEntry").get("id").asString();
        List<Map<String, Object>> lines = jdbc.sql("SELECT a.code, l.debit, l.credit FROM \"JournalLine\" l JOIN \"Account\""
                + " a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? ORDER BY a.code").param(je).query().listOfRows();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(2).get("code")).isEqualTo("3100");
        assertThat((BigDecimal) lines.get(2).get("credit")).isEqualByComparingTo("800");
        assertThat(posted.body().get("invoices")).hasSize(1);
        assertThat(posted.body().get("bills")).hasSize(1);

        Res arRefused = call("POST", "/opening-balances", Map.of("date", today, "accounts",
                List.of(Map.of("accountId", account("1300"), "debit", 10))), key());
        assertThat(arRefused.body().get("message").asString()).contains("control account");

        JsonNode list = ok(200, "GET", "/opening-balances", null);
        assertThat(list.get("entries").toString()).contains(je);
        assertThat(list.get("documents").toString()).contains(customer);

        JsonNode st = ok(200, "GET", "/reports/ar/customers/" + customer + "/statement", null);
        assertThat(st.get("closingBalance").asString()).isEqualTo("250");
        assertThat(st.get("lines")).hasSize(1);
        assertThat(st.get("aging").get("totalOpen").asString()).isEqualTo("250");
        JsonNode later = ok(200, "GET", "/reports/ar/customers/" + customer + "/statement?from="
                + LocalDate.now().plusDays(1), null);
        assertThat(later.get("openingBalance").asString()).isEqualTo("250");
        assertThat(later.get("lines")).isEmpty();
        JsonNode sup = ok(200, "GET", "/reports/ap/suppliers/" + supplier + "/statement", null);
        assertThat(sup.get("closingBalance").asString()).isEqualTo("90");
        assertThat(as("GET", "/reports/ar/customers/" + UUID.randomUUID() + "/statement", null).status())
                .isEqualTo(404);
        Res noEmail = as("POST", "/reports/ar/customers/" + customer + "/statement/email", Map.of());
        assertThat(noEmail.status()).isEqualTo(400);
        JsonNode mailed = ok(200, "POST", "/reports/ar/customers/" + customer + "/statement/email",
                Map.of("to", "ap@customer.test"));
        assertThat(mailed.get("sentTo").asString()).isEqualTo("ap@customer.test");
    }

    @Test
    @DisplayName("tax summary: output tax from posted invoices by tax; invoice PDF and e-mail")
    void taxAndInvoiceDelivery() throws Exception {
        String tax = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 18, ?, now())").params(tax, demoOrg, "VAT " + suffix(), "V" + suffix(),
                account("2200")).update();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'Consulting', 'service', 100,"
                        + " 0, false, now())").params(product, demoOrg, "SV-" + suffix()).update();
        String customer = partner(false, "billing@customer.test");
        Res draft = as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", 2, "unitPrice", 100, "taxId", tax))));
        assertThat(draft.status()).as(draft.raw().body()).isBetween(200, 201);
        String invoice = draft.body().get("id").asString();
        Res notPosted = as("POST", "/invoices/" + invoice + "/email", Map.of());
        assertThat(notPosted.body().get("message").asString()).isEqualTo("Only posted invoices can be emailed");
        Res post = call("POST", "/invoices/" + invoice + "/post", null, key());
        assertThat(post.status()).as(post.raw().body()).isBetween(200, 201);

        JsonNode summary = ok(200, "GET", "/reports/tax/summary?from=" + today + "&to=" + LocalDate.now().plusDays(1),
                null);
        JsonNode line = null;
        for (JsonNode l : summary.get("output").get("lines")) {
            if (tax.equals(l.get("taxId").asString(null))) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("taxableAmount").asString()).isEqualTo("200");
        assertThat(line.get("taxAmount").asString()).isEqualTo("36");
        assertThat(summary.has("netTaxPayable")).isTrue();
        assertThat(summary.get("gl").get("accounts").toString()).contains("2200");

        HttpResponse<byte[]> pdf = raw("/invoices/" + invoice + "/pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4)).isEqualTo("%PDF");
        HttpResponse<byte[]> stPdf = raw("/reports/ar/customers/" + customer + "/statement.pdf");
        assertThat(new String(stPdf.body(), 0, 4)).isEqualTo("%PDF");
        JsonNode sent = ok(200, "POST", "/invoices/" + invoice + "/email", Map.of("message", "Thanks!"));
        assertThat(sent.get("sentTo").asString()).isEqualTo("billing@customer.test");
        assertThat(sent.get("delivered").asBoolean()).isFalse();
        String stamped = jdbc.sql("SELECT data->>'lastEmailedTo' FROM \"Document\" WHERE id = ?").param(invoice)
                .query(String.class).single();
        assertThat(stamped).isEqualTo("billing@customer.test");
        assertThat(as("POST", "/invoices/" + invoice + "/email", Map.of("to", "not-an-email")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("new routes are permission-guarded")
    void permissions() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Ext-" + role,
                new String[] {"report:ar"}).update();
        String email = "ext-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Ext@1234567", role);
        String token = login("DEMO", email, "Ext@1234567");
        assertThat(call("GET", "/budgets", null, token).status()).isEqualTo(403);
        assertThat(call("GET", "/opening-balances", null, token).status()).isEqualTo(403);
        assertThat(call("GET", "/reports/tax/summary", null, token).status()).isEqualTo(403);
        assertThat(call("GET", "/reports/accounting/consolidated", null, token).status()).isEqualTo(403);
        assertThat(call("GET", "/budgets", null, (String) null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("budget by branch: vs-actual counts only that branch's postings (BR-2)")
    void budgetByBranch() {
        String branch = ok(201, "POST", "/branches", Map.of("code", "BB-" + suffix(), "name", "Budget branch")).get("id")
                .asString();
        String other = ok(201, "POST", "/branches", Map.of("code", "BO-" + suffix(), "name", "Other branch")).get("id")
                .asString();
        String salary = account("5710");
        JsonNode b = ok(201, "POST", "/budgets", Map.of("name", "Branch budget " + suffix(), "fiscalYear",
                LocalDate.now().getYear(), "branchId", branch, "lines", List.of(Map.of("accountId", salary, "period",
                        YearMonth.now().toString(), "amount", 1000))));
        journal(salary, account("1100"), 300, branch, null);
        journal(salary, account("1100"), 9000, other, null);
        JsonNode vs = ok(200, "GET", "/budgets/" + b.get("id").asString() + "/vs-actual", null);
        assertThat(vs.get("rows").get(0).get("actual").asString()).isEqualTo("300");
    }
}
