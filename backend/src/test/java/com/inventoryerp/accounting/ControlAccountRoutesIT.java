package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Control-account policy, sub-ledger side (owner decision 2026-11-17, "close now"): an account a user chooses on a
 * sub-ledger document (a free invoice / credit-note / bill line, an expense allocation or accrual, a write-off account,
 * a payment's tender account) or configures as a product category's income account never puts a line on a designated
 * AR / AP control account — refused with 400 at create / update and again at posting. The documents' own receivable /
 * payable lines still post to the configured AR / AP, partner-tagged. Write-offs go through the sub-ledger: the
 * customer write-off is a credit note against the invoice, the supplier write-off lowers the bill's residual with
 * Dr AP (partner) / Cr a non-control income account. Each scenario runs in a fresh organization.
 */
class ControlAccountRoutesIT extends AccountingFixtures {

    static final String AR_REFUSAL = "designated accounts receivable control account";
    static final String AP_REFUSAL = "designated accounts payable control account";

    // ------------------------------------------------------------------ helpers

    String partner(Books b, boolean customer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, b.org(), "CR-" + id.substring(0, 8), (customer ? "Customer " : "Supplier ")
                        + id.substring(0, 4), customer, !customer).update();
        return id;
    }

    static Map<String, Object> serviceLine(String accountId, int amount) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("accountId", accountId);
        l.put("description", "Service " + amount);
        l.put("quantity", 1);
        l.put("unitPrice", amount);
        return l;
    }

    static Map<String, Object> document(String partnerId, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partnerId);
        body.put("issueDate", LocalDate.now().toString());
        body.put("lines", lines);
        return body;
    }

    static void refused(Res r, String fragment) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains(fragment);
    }

    String expenseAccount(Books b) {
        return mapped(b.org(), "default_expense");
    }

    String revenueAccount(Books b) {
        return mapped(b.org(), "sales_revenue");
    }

    String docStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(id).query(String.class).single();
    }

    BigDecimal residual(String id) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(id).query(BigDecimal.class)
                .single();
    }

    long writeOffEntries(String org, String billId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'vendor_bill_writeoff' AND \"sourceId\" = ? AND \"postingType\" = 'write_off'").params(org, billId)
                .query(Long.class).single();
    }

    /** A posted invoice of {@code amount} on a revenue line; returns {invoiceId, journalEntryId}. */
    String[] postedInvoice(Books b, String customer, int amount) {
        String inv = okAs(b, 200, "POST", "/invoices", document(customer, List.of(serviceLine(revenueAccount(b),
                amount)))).get("id").asString();
        String je = okAs(b, 200, "POST", "/invoices/" + inv + "/post", null).get("journalEntryId").asString();
        return new String[] {inv, je};
    }

    /** A posted bill of {@code amount} on an expense line; returns {billId, journalEntryId}. */
    String[] postedBill(Books b, String supplier, int amount) {
        String bill = okAs(b, 200, "POST", "/vendor-bills", document(supplier, List.of(serviceLine(expenseAccount(b),
                amount)))).get("id").asString();
        String je = okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of()).get("journalEntryId").asString();
        return new String[] {bill, je};
    }

    // ------------------------------------------------------------------ invoice / credit note lines

    @Test
    @DisplayName("[control-routes] invoice and credit-note lines refuse AR / AP (create, edit, post); the invoice still"
            + " posts its receivable to the configured AR, partner-tagged")
    void invoiceLines() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String arCode = mappedCode(b.org(), "accounts_receivable");
        String customer = partner(b, true);

        refused(as(b, "POST", "/invoices", document(customer, List.of(serviceLine(ar, 100)))), AR_REFUSAL);
        refused(as(b, "POST", "/invoices", document(customer, List.of(serviceLine(revenueAccount(b), 10),
                serviceLine(ap, 100)))), AP_REFUSAL);
        refused(as(b, "POST", "/invoices", document(customer, List.of(serviceLine(ar, 100)))), "Document line 1");
        refused(as(b, "POST", "/credit-notes", document(customer, List.of(serviceLine(ar, 10)))), AR_REFUSAL);

        // A normal income account: the invoice posts Dr AR (partner) / Cr revenue.
        String[] invoice = postedInvoice(b, customer, 100);
        assertThat(lines(invoice[1])).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(arCode);
            assertThat(l.get("partnerId")).isEqualTo(customer);
            assertThat((BigDecimal) l.get("debit")).isEqualByComparingTo("100");
        });

        // Editing a draft onto AR is refused too.
        String draft = okAs(b, 200, "POST", "/invoices", document(customer, List.of(serviceLine(revenueAccount(b), 40))))
                .get("id").asString();
        refused(as(b, "PATCH", "/invoices/" + draft, document(customer, List.of(serviceLine(ar, 40)))), AR_REFUSAL);

        // Defence in depth: a draft saved before the policy with an AR line is refused when posted, nothing posts.
        jdbc.sql("UPDATE \"DocumentLine\" SET \"accountId\" = ? WHERE \"documentId\" = ?").params(ar, draft).update();
        refused(as(b, "POST", "/invoices/" + draft + "/post", null), AR_REFUSAL);
        assertThat(docStatus(draft)).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ?").param(draft)
                .query(Long.class).single()).isZero();

        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
    }

    @Test
    @DisplayName("[control-routes] a product category whose income account is AR is a configuration error refused at"
            + " posting")
    void productCategoryIncomeAccount() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String customer = partner(b, true);
        String category = okAs(b, 201, "POST", "/product-categories", Map.of("name", "Mis-set " + customer.substring(0,
                6), "incomeAccountId", ar)).get("id").asString();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"categoryId\", \"updatedAt\") VALUES (?, ?, ?, 'Mis-set service',"
                + " 'service', 50, 0, false, ?, now())").params(product, b.org(), "CR-" + product.substring(0, 6),
                category).update();
        String inv = okAs(b, 200, "POST", "/invoices", document(customer, List.of(Map.of("productId", product,
                "quantity", 1, "unitPrice", 50)))).get("id").asString();
        Res post = as(b, "POST", "/invoices/" + inv + "/post", null);
        refused(post, AR_REFUSAL);
        assertThat(post.body().get("message").asString()).contains("Income account of a document line");
        assertThat(docStatus(inv)).isEqualTo("draft");
    }

    // ------------------------------------------------------------------ bill lines

    @Test
    @DisplayName("[control-routes] bill lines refuse AR / AP (create, edit, post); the bill still credits the configured"
            + " AP, partner-tagged")
    void billLines() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String apCode = mappedCode(b.org(), "accounts_payable");
        String supplier = partner(b, false);

        refused(as(b, "POST", "/vendor-bills", document(supplier, List.of(serviceLine(ap, 100)))), AP_REFUSAL);
        refused(as(b, "POST", "/vendor-bills", document(supplier, List.of(serviceLine(ar, 100)))), AR_REFUSAL);

        String[] bill = postedBill(b, supplier, 100);
        assertThat(lines(bill[1])).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(apCode);
            assertThat(l.get("partnerId")).isEqualTo(supplier);
            assertThat((BigDecimal) l.get("credit")).isEqualByComparingTo("100");
        });

        String draft = okAs(b, 200, "POST", "/vendor-bills", document(supplier, List.of(serviceLine(expenseAccount(b),
                30)))).get("id").asString();
        refused(as(b, "PATCH", "/vendor-bills/" + draft, document(supplier, List.of(serviceLine(ap, 30)))), AP_REFUSAL);
        jdbc.sql("UPDATE \"DocumentLine\" SET \"accountId\" = ? WHERE \"documentId\" = ?").params(ap, draft).update();
        refused(as(b, "POST", "/vendor-bills/" + draft + "/post", Map.of()), AP_REFUSAL);
        assertThat(docStatus(draft)).isEqualTo("draft");

        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), supplier, false);
    }

    // ------------------------------------------------------------------ expenses

    @Test
    @DisplayName("[control-routes] expense allocation, accrual and category accounts refuse AR / AP; an expense account"
            + " is accepted")
    void expenseLines() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String expense = expenseAccount(b);
        String supplier = partner(b, false);
        String sfx = supplier.substring(0, 6);

        refused(as(b, "POST", "/expense-categories", Map.of("name", "AR cat " + sfx, "ledgerAccountId", ar)),
                AR_REFUSAL);
        String category = okAs(b, 201, "POST", "/expense-categories", Map.of("name", "Ops " + sfx, "ledgerAccountId",
                expense)).get("id").asString();

        Map<String, Object> onAp = new LinkedHashMap<>(Map.of("title", "Repairs " + sfx, "amount", 100, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CREDIT", "categoryId", category, "supplierId", supplier));
        onAp.put("allocations", List.of(Map.of("percent", 100, "accountId", ap)));
        Res apAllocation = moneyAs(b, "POST", "/expenses", onAp);
        refused(apAllocation, AP_REFUSAL);
        assertThat(apAllocation.body().get("message").asString()).contains("Expense allocation line 1");

        Map<String, Object> onExpense = new LinkedHashMap<>(onAp);
        onExpense.put("title", "Repairs ok " + sfx);
        onExpense.put("allocations", List.of(Map.of("percent", 100, "accountId", expense)));
        Res ok = moneyAs(b, "POST", "/expenses", onExpense);
        assertThat(ok.status()).as(ok.raw().body()).isLessThan(300);

        refused(moneyAs(b, "POST", "/expense-accruals", Map.of("title", "Accrual " + sfx, "amount", 50, "accountId",
                ar, "accrualDate", LocalDate.now().toString())), AR_REFUSAL);
        Res accrual = moneyAs(b, "POST", "/expense-accruals", Map.of("title", "Accrual ok " + sfx, "amount", 50,
                "accountId", expense, "accrualDate", LocalDate.now().toString()));
        // An @Idempotent route answers 200 like Nest's idempotency interceptor (kernel/idempotency/Idempotent).
        assertThat(accrual.status()).as(accrual.raw().body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ payments

    @Test
    @DisplayName("[control-routes] a customer receipt or supplier payment whose tender account is AR / AP is refused")
    void paymentTenderAccount() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String customer = partner(b, true);
        String supplier = partner(b, false);
        refused(moneyAs(b, "POST", "/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", 10,
                "paymentMethod", "bank", "accountId", ar)), AR_REFUSAL);
        refused(moneyAs(b, "POST", "/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", 10,
                "paymentMethod", "bank", "accountId", ap)), AP_REFUSAL);
        refused(moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today(),
                "amount", 10, "paymentMethod", "cheque", "accountId", ap, "allowOverpayment", true)), AP_REFUSAL);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Payment\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ write-offs

    @Test
    @DisplayName("[control-routes] customer write-off: a control or balance-sheet account is refused; the bad debt"
            + " account lowers the invoice residual through a credit note and AR reconciles per customer")
    void customerWriteOff() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String arCode = mappedCode(b.org(), "accounts_receivable");
        String badDebt = mapped(b.org(), "bad_debt");
        String badDebtCode = mappedCode(b.org(), "bad_debt");
        String customer = partner(b, true);
        String inv = postedInvoice(b, customer, 300)[0];
        String path = "/sales/invoices/" + inv + "/write-off";

        refused(moneyAs(b, "POST", path, Map.of("reason", "Customer closed", "amount", 50, "accountId", ar)),
                AR_REFUSAL);
        refused(moneyAs(b, "POST", path, Map.of("reason", "Customer closed", "amount", 50, "accountId", ap)),
                AP_REFUSAL);
        refused(moneyAs(b, "POST", path, Map.of("reason", "Customer closed", "amount", 50, "accountId",
                mapped(b.org(), "default_bank"))), "not an expense or income account");
        assertThat(residual(inv)).isEqualByComparingTo("300");

        Res done = moneyAs(b, "POST", path, Map.of("reason", "Customer closed", "amount", 50, "accountId", badDebt));
        assertThat(done.status()).as(done.raw().body()).isLessThan(300);
        assertThat(residual(inv)).isEqualByComparingTo("250");
        String creditNote = done.body().get("id").asString();
        String je = jdbc.sql("SELECT \"journalEntryId\" FROM \"Document\" WHERE id = ?").param(creditNote)
                .query(String.class).single();
        List<Map<String, Object>> posted = lines(je);
        assertThat(posted).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(badDebtCode);
            assertThat((BigDecimal) l.get("debit")).isEqualByComparingTo("50");
        });
        assertThat(posted).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(arCode);
            assertThat(l.get("partnerId")).isEqualTo(customer);
            assertThat((BigDecimal) l.get("credit")).isEqualByComparingTo("50");
        });
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
    }

    @Test
    @DisplayName("[control-routes] supplier write-off: Dr AP (partner) / Cr purchase discounts lowers the bill residual"
            + " and settles it; control / balance-sheet accounts and over-residual amounts are refused; a replay posts"
            + " once; AP reconciles per supplier")
    void supplierWriteOff() {
        Books b = freshBooks();
        String ap = mapped(b.org(), "accounts_payable");
        String apCode = mappedCode(b.org(), "accounts_payable");
        String discountCode = mappedCode(b.org(), "purchase_discount");
        String supplier = partner(b, false);
        String bill = postedBill(b, supplier, 200)[0];
        Res paid = moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today(),
                "amount", 150, "paymentMethod", "cheque", "allocations", List.of(Map.of("documentId", bill, "amount",
                        150))));
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        assertThat(residual(bill)).isEqualByComparingTo("50");
        String path = "/vendor-bills/" + bill + "/write-off";

        refused(moneyAs(b, "POST", path, Map.of("reason", "Supplier waived", "accountId", ap)), AP_REFUSAL);
        refused(moneyAs(b, "POST", path, Map.of("reason", "Supplier waived", "accountId", mapped(b.org(),
                "default_bank"))), "not an expense or income account");
        refused(moneyAs(b, "POST", path, Map.of("reason", "Supplier waived", "amount", 60)), "open balance");
        refused(moneyAs(b, "POST", path, Map.of("reason", " ")), "reason");
        Res noKey = as(b, "POST", path, Map.of("reason", "Supplier waived"));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(writeOffEntries(b.org(), bill)).isZero();

        // The whole remaining 50 to the purchase_discount mapping; the same Idempotency-Key replays, posting once.
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + b.token());
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("reason", "Supplier waived the rounding difference");
        Res first = call("POST", path, body, headers);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", path, body, headers);
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(200);
        assertThat(writeOffEntries(b.org(), bill)).isEqualTo(1);
        JsonNode result = first.body();
        assertThat(result.get("writeOff").get("amount").asString()).isEqualTo("50");
        assertThat(result.get("status").asString()).isEqualTo("paid");
        assertThat(result.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(residual(bill)).isEqualByComparingTo("0");

        String entry = result.get("writeOff").get("journalEntryId").asString();
        List<Map<String, Object>> posted = lines(entry);
        assertThat(posted).hasSize(2);
        assertThat(posted).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(apCode);
            assertThat(l.get("partnerId")).isEqualTo(supplier);
            assertThat((BigDecimal) l.get("debit")).isEqualByComparingTo("50");
        });
        assertThat(posted).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(discountCode);
            assertThat((BigDecimal) l.get("credit")).isEqualByComparingTo("50");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = 'VendorBill'"
                + " AND \"entityId\" = ? AND \"newValues\"->>'kind' = 'write_off'").params(b.org(), bill)
                .query(Long.class).single()).isEqualTo(1);

        // Nothing left to write off; the bill detail and the supplier ledger show the write-off.
        refused(moneyAs(b, "POST", path, Map.of("reason", "Again")), "open balance");
        assertThat(okAs(b, 200, "GET", "/vendor-bills/" + bill, null).get("writtenOff").asString()).isEqualTo("50");
        JsonNode ledger = okAs(b, 200, "GET", "/vendor-bills/suppliers/" + supplier + "/ledger", null);
        boolean listed = false;
        for (JsonNode t : ledger.get("transactions")) {
            listed |= "write_off".equals(t.get("type").asString());
        }
        assertThat(listed).isTrue();
        assertThat(ledger.get("closingBalance").asDouble()).isZero();

        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), supplier, false);
        LedgerInvariants.trialBalanceBalanced(tbAsOf(b.org(), today(), TbView.POST_CLOSING));

        // Document-level reversal of the write-off (owner decision 2026-11-17): the generic journal reversal is
        // refused, a reason is required, the bill gets its 50 back, and a replay changes nothing.
        Res generic = as(b, "POST", "/journal-entries/" + entry + "/reverse", null);
        assertThat(generic.status()).as(generic.raw().body()).isEqualTo(400);
        String reversePath = "/vendor-bills/" + bill + "/write-offs/" + entry + "/reverse";
        refused(moneyAs(b, "POST", reversePath, Map.of("reason", " ")), "reason");
        Res reversed = moneyAs(b, "POST", reversePath, Map.of("reason", "Supplier withdrew the waiver"));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(reversed.body().get("writtenOff").asString()).isEqualTo("0");
        assertThat(reversed.body().get("status").asString()).isEqualTo("posted");
        assertThat(residual(bill)).isEqualByComparingTo("50");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entry).query(String.class)
                .single()).isEqualTo("reversed");
        Res again = moneyAs(b, "POST", reversePath, Map.of("reason", "Supplier withdrew the waiver"));
        assertThat(again.status()).as(again.raw().body()).isEqualTo(200);
        assertThat(residual(bill)).as("replay does not restore twice").isEqualByComparingTo("50");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = 'VendorBill'"
                + " AND \"entityId\" = ? AND \"newValues\"->>'kind' = 'write_off_reversal'").params(b.org(), bill)
                .query(Long.class).single()).isEqualTo(1);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), supplier, false);
        LedgerInvariants.trialBalanceBalanced(tbAsOf(b.org(), today(), TbView.POST_CLOSING));
    }
}
