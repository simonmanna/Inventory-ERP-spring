package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Document reversals (owner decision 2026-11-17, V520): an entry a customer / supplier sub-ledger document posted is
 * never reversed by the generic {@code POST /journal-entries/{id}/reverse} (400 naming the document and its action);
 * the document-level workflows (invoice cancel, bill void, credit-note / debit-note reversal, payment void) reverse the
 * GL, update the sub-ledger, refuse or unwind allocations, need a reason, honour the {@code document_reversal}
 * separation-of-duties pair and permissions, and are idempotent with one {@code DocumentReversal} row per document.
 * Each scenario runs in a fresh organization.
 */
class DocumentReversalIT extends AccountingFixtures {

    static final Map<String, Object> REASON = Map.of("reason", "Raised in error");

    // ------------------------------------------------------------------ helpers

    String partner(Books b, boolean customer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, b.org(), "DR-" + id.substring(0, 8), (customer ? "Customer " : "Supplier ")
                        + id.substring(0, 4), customer, !customer).update();
        return id;
    }

    /** One service product per organisation: a credit note may only credit a product its invoice sold (ACC-4). */
    final Map<String, String> services = new java.util.HashMap<>();

    String service(Books b) {
        return services.computeIfAbsent(b.org(), org -> {
            String id = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                            + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0,"
                            + " false, now())")
                    .params(id, org, "DR-" + id.substring(0, 6), "Reversal service").update();
            return id;
        });
    }

    /** Another user with the owner's role (Administrator: every permission). */
    String secondAdmin(Books b) {
        String role = jdbc.sql("SELECT ur.\"A\" FROM \"_UserRoles\" ur WHERE ur.\"B\" = ? LIMIT 1").param(b.ownerId())
                .query(String.class).single();
        String email = "dr-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Checker@12345", role);
        return login(b.code(), email, "Checker@12345");
    }

    /** A user of the books' organization holding only {@code permissions}. */
    String userWith(Books b, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, b.org(), "Limited-" + role, permissions).update();
        String email = "dr-limited-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Limited@12345", role);
        return login(b.code(), email, "Limited@12345");
    }

    Res keyed(String token, String method, String path, Object body) {
        return call(method, path, body, Map.of("Authorization", "Bearer " + token, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    /** A posted sales invoice of {@code amount} (one service line); returns {id, journalEntryId}. */
    JsonNode postedInvoice(Books b, String customer, int amount) {
        String id = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", service(b), "quantity", 1, "unitPrice", amount)))).get("id").asString();
        return okAs(b, 200, "POST", "/invoices/" + id + "/post", null);
    }

    JsonNode postedBill(Books b, String supplier, int amount) {
        String id = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "lines",
                List.of(Map.of("productId", service(b), "quantity", 1, "unitPrice", amount)))).get("id").asString();
        return okAs(b, 200, "POST", "/vendor-bills/" + id + "/post", Map.of());
    }

    JsonNode receipt(Books b, String customer, String documentId, int amount) {
        Res r = moneyAs(b, "POST", "/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", amount,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", documentId, "amount", amount))));
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    JsonNode supplierPayment(Books b, String supplier, String billId, int amount) {
        Res r = moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today(), "amount",
                amount, "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", billId, "amount", amount))));
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    String postedCreditNote(Books b, String customer, String invoiceId, int amount) {
        String id = okAs(b, 200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(),
                "reversedDocumentId", invoiceId, "lines", List.of(Map.of("productId", service(b), "quantity", 1,
                        "unitPrice", amount)))).get("id").asString();
        okAs(b, 200, "POST", "/credit-notes/" + id + "/post", null);
        return id;
    }

    String postedDebitNote(Books b, String customer, String invoiceId, int amount) {
        String id = okAs(b, 201, "POST", "/procurement/debit-notes", Map.of("direction", "outbound", "partnerId",
                customer, "documentId", invoiceId, "reason", "price_adjustment", "lines", List.of(Map.of("description",
                        "Under-billed", "quantity", 1, "unitPrice", amount)))).get("id").asString();
        okAs(b, 200, "PATCH", "/procurement/debit-notes/" + id + "/post", null);
        return id;
    }

    String entryOf(String sourceType, String sourceId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = ? AND \"sourceId\" = ? ORDER BY"
                + " \"postedAt\" LIMIT 1").params(sourceType, sourceId).query(String.class).single();
    }

    String status(String entryId) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entryId).query(String.class)
                .single();
    }

    long reversalsOf(String entryId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entryId)
                .query(Long.class).single();
    }

    BigDecimal residual(String documentId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(BigDecimal.class).single();
    }

    String docStatus(String documentId) {
        return jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(documentId).query(String.class)
                .single();
    }

    long reversalRows(String documentType, String documentId) {
        return jdbc.sql("SELECT count(*) FROM \"DocumentReversal\" WHERE \"documentType\" = ? AND \"documentId\" = ?")
                .params(documentType, documentId).query(Long.class).single();
    }

    void genericReverseRefused(Books b, String entryId, String... messageParts) {
        Res r = as(b, "POST", "/journal-entries/" + entryId + "/reverse", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("cannot be reversed generically").contains(messageParts);
        assertThat(status(entryId)).as("still posted").isEqualTo("posted");
        assertThat(reversalsOf(entryId)).isZero();
    }

    // ------------------------------------------------------------------ generic route

    @Test
    @DisplayName("[document-reversals] the generic journal reversal refuses every sub-ledger entry and names the action;"
            + " a manual entry stays reversible")
    void genericReverseRefusesSubledgerEntries() {
        Books b = freshBooks();
        String customer = partner(b, true);
        String supplier = partner(b, false);

        JsonNode inv = postedInvoice(b, customer, 100);
        String invoiceEntry = inv.get("journalEntryId").asString();
        genericReverseRefused(b, invoiceEntry, "sales invoice", "cancel the invoice",
                "POST /invoices/" + inv.get("id").asString() + "/cancel");
        JsonNode detail = okAs(b, 200, "GET", "/journal-entries/" + invoiceEntry, null);
        assertThat(detail.get("sourceDocument").get("documentType").asString()).isEqualTo("sales_invoice");
        assertThat(detail.get("sourceDocument").get("path").asString()).isEqualTo("/invoices/" + inv.get("id").asString());

        JsonNode bill = postedBill(b, supplier, 80);
        genericReverseRefused(b, bill.get("journalEntryId").asString(), "void the bill", "/vendor-bills/");

        String cn = postedCreditNote(b, customer, inv.get("id").asString(), 10);
        genericReverseRefused(b, entryOf("credit_note", cn), "reverse the credit note", "/credit-notes/" + cn);

        String dn = postedDebitNote(b, customer, inv.get("id").asString(), 5);
        genericReverseRefused(b, entryOf("debit_note", dn), "reverse the debit note",
                "/procurement/debit-notes/" + dn + "/reverse");

        JsonNode pay = receipt(b, customer, inv.get("id").asString(), 20);
        genericReverseRefused(b, pay.get("journalEntryId").asString(), "void the payment", "/payments/");

        // A manual entry is still reversed generically.
        String arCode = mappedCode(b.org(), "accounts_receivable");
        String revenueCode = accountCodeOfSection(b.org(), "revenue");
        String manual = postManualJe(b, LocalDate.now().toString(), List.of(dr(arCode, 7).partner(customer),
                cr(revenueCode, 7)));
        Res reversed = as(b, "POST", "/journal-entries/" + manual + "/reverse", null);
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + manual, null).has("sourceDocument")).isFalse();
    }

    @Test
    @DisplayName("[document-reversals] the reversal a document workflow posted cannot be reversed generically either")
    void reversalOfSubledgerEntryRefused() {
        Books b = freshBooks();
        String customer = partner(b, true);
        JsonNode inv = postedInvoice(b, customer, 100);
        okAs(b, 200, "POST", "/invoices/" + inv.get("id").asString() + "/cancel", REASON);
        String reversal = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?")
                .param(inv.get("journalEntryId").asString()).query(String.class).single();
        Res r = as(b, "POST", "/journal-entries/" + reversal + "/reverse", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("is the reversal of an entry posted by", "sales invoice");
        assertThat(okAs(b, 200, "GET", "/journal-entries/" + reversal, null).get("sourceDocument").get("viaReversal")
                .asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ invoice / bill

    @Test
    @DisplayName("[document-reversals] invoice cancel: reason required, refused while a payment is applied, then GL"
            + " mirrored, residual zero, one reversal record; a replay changes nothing")
    void invoiceCancelLifecycle() {
        Books b = freshBooks();
        String customer = partner(b, true);
        JsonNode inv = postedInvoice(b, customer, 100);
        String id = inv.get("id").asString();
        String je = inv.get("journalEntryId").asString();

        Res noReason = as(b, "POST", "/invoices/" + id + "/cancel", null);
        assertThat(noReason.status()).as(noReason.raw().body()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to cancel an invoice");

        JsonNode pay = receipt(b, customer, id, 40);
        assertThat(residual(id)).isEqualByComparingTo("60");
        Res refused = as(b, "POST", "/invoices/" + id + "/cancel", REASON);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .contains("cannot be cancelled while settlements are applied", pay.get("paymentNumber").asString(),
                        "void each payment first");
        assertThat(status(je)).isEqualTo("posted");

        Res voidPay = moneyAs(b, "POST", "/payments/" + pay.get("id").asString() + "/void", Map.of("reason", "Bounced"));
        assertThat(voidPay.status()).as(voidPay.raw().body()).isEqualTo(200);
        assertThat(residual(id)).as("void gives the allocation back").isEqualByComparingTo("100");
        assertThat(reversalRows("payment", pay.get("id").asString())).isEqualTo(1);

        JsonNode cancelled = okAs(b, 200, "POST", "/invoices/" + id + "/cancel", REASON);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("reversal").get("reason").asString()).isEqualTo("Raised in error");
        assertThat(cancelled.get("reversal").get("originalEntryIds").get(0).asString()).isEqualTo(je);
        assertThat(residual(id)).isEqualByComparingTo("0");
        assertThat(status(je)).isEqualTo("reversed");
        assertThat(reversalsOf(je)).isEqualTo(1);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);

        JsonNode replay = okAs(b, 200, "POST", "/invoices/" + id + "/cancel", Map.of("reason", "Again"));
        assertThat(replay.get("reversal").get("reason").asString()).as("first reversal kept").isEqualTo("Raised in error");
        assertThat(reversalsOf(je)).as("no second reversal").isEqualTo(1);
        assertThat(reversalRows("sales_invoice", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = 'Document' AND"
                + " \"entityId\" = ? AND action = 'cancel'").params(b.org(), id).query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("[document-reversals] bill void: refused while a supplier payment is applied; void the payment, then"
            + " the bill voids and AP reconciles")
    void billVoidLifecycle() {
        Books b = freshBooks();
        String supplier = partner(b, false);
        JsonNode bill = postedBill(b, supplier, 300);
        String id = bill.get("id").asString();
        JsonNode pay = supplierPayment(b, supplier, id, 100);

        Res refused = as(b, "POST", "/vendor-bills/" + id + "/cancel", REASON);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("cannot be voided while settlements are applied",
                pay.get("paymentNumber").asString());
        Res noReason = as(b, "POST", "/vendor-bills/" + id + "/cancel", Map.of());
        assertThat(noReason.status()).isEqualTo(400);

        Res voidPay = moneyAs(b, "POST", "/payments/" + pay.get("id").asString() + "/void", Map.of("reason",
                "Paid twice"));
        assertThat(voidPay.status()).as(voidPay.raw().body()).isEqualTo(200);
        assertThat(residual(id)).isEqualByComparingTo("300");
        JsonNode voided = okAs(b, 200, "POST", "/vendor-bills/" + id + "/cancel", REASON);
        assertThat(voided.get("status").asString()).isEqualTo("cancelled");
        assertThat(status(bill.get("journalEntryId").asString())).isEqualTo("reversed");
        assertThat(reversalRows("vendor_bill", id)).isEqualTo(1);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), supplier, false);
    }

    // ------------------------------------------------------------------ credit / debit notes

    @Test
    @DisplayName("[document-reversals] credit-note reversal: GL mirrored, its allocation unwound (invoice residual"
            + " restored), status cancelled, idempotent")
    void creditNoteReversal() {
        Books b = freshBooks();
        String customer = partner(b, true);
        JsonNode inv = postedInvoice(b, customer, 100);
        String invoiceId = inv.get("id").asString();
        String cn = postedCreditNote(b, customer, invoiceId, 30);
        assertThat(residual(invoiceId)).isEqualByComparingTo("70");
        String cnEntry = entryOf("credit_note", cn);

        Res noReason = keyed(b.token(), "POST", "/credit-notes/" + cn + "/reverse", Map.of());
        assertThat(noReason.status()).as(noReason.raw().body()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to reverse a credit note");

        Res done = keyed(b.token(), "POST", "/credit-notes/" + cn + "/reverse", REASON);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.body().get("status").asString()).isEqualTo("cancelled");
        assertThat(done.body().get("reversal").get("unwound")).hasSize(1);
        assertThat(residual(invoiceId)).as("credit given back to the invoice").isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT count(*) FROM \"CreditAllocation\" WHERE \"creditDocumentId\" = ?").param(cn)
                .query(Long.class).single()).isZero();
        assertThat(status(cnEntry)).isEqualTo("reversed");
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);

        Res replay = keyed(b.token(), "POST", "/credit-notes/" + cn + "/reverse", REASON);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(reversalsOf(cnEntry)).isEqualTo(1);
        assertThat(reversalRows("credit_note", cn)).isEqualTo(1);
        assertThat(residual(invoiceId)).isEqualByComparingTo("100");

        // With the credit note gone, the invoice can be cancelled.
        okAs(b, 200, "POST", "/invoices/" + invoiceId + "/cancel", REASON);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
    }

    @Test
    @DisplayName("[document-reversals] credit-note reversal needs credit_note:reverse")
    void creditNoteReversalPermission() {
        Books b = freshBooks();
        String customer = partner(b, true);
        JsonNode inv = postedInvoice(b, customer, 100);
        String cn = postedCreditNote(b, customer, inv.get("id").asString(), 10);
        String clerk = userWith(b, "credit_note:read", "credit_note:create", "credit_note:post");
        assertThat(keyed(clerk, "POST", "/credit-notes/" + cn + "/reverse", REASON).status()).isEqualTo(403);
        assertThat(docStatus(cn)).isNotEqualTo("cancelled");
    }

    @Test
    @DisplayName("[document-reversals] debit-note reversal gives back what it raised on the invoice; refused once that"
            + " part is paid")
    void debitNoteReversal() {
        Books b = freshBooks();
        String customer = partner(b, true);
        JsonNode inv = postedInvoice(b, customer, 100);
        String invoiceId = inv.get("id").asString();
        String dn = postedDebitNote(b, customer, invoiceId, 20);
        assertThat(residual(invoiceId)).isEqualByComparingTo("120");
        assertThat(jdbc.sql("SELECT \"documentAdjustment\" FROM \"DebitNote\" WHERE id = ?").param(dn)
                .query(BigDecimal.class).single()).isEqualByComparingTo("20");

        Res invoiceCancel = as(b, "POST", "/invoices/" + invoiceId + "/cancel", REASON);
        assertThat(invoiceCancel.status()).isEqualTo(400);
        assertThat(invoiceCancel.body().get("message").asString()).contains("posted debit notes");

        Res noReason = keyed(b.token(), "PATCH", "/procurement/debit-notes/" + dn + "/reverse", Map.of());
        assertThat(noReason.status()).isEqualTo(400);
        Res done = keyed(b.token(), "PATCH", "/procurement/debit-notes/" + dn + "/reverse", REASON);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.body().get("status").asString()).isEqualTo("reversed");
        assertThat(residual(invoiceId)).isEqualByComparingTo("100");
        assertThat(status(entryOf("debit_note", dn))).isEqualTo("reversed");
        assertThat(keyed(b.token(), "PATCH", "/procurement/debit-notes/" + dn + "/reverse", REASON).status())
                .isEqualTo(200);
        assertThat(reversalRows("debit_note", dn)).isEqualTo(1);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);

        // A second note whose raise is already paid: refused, naming the payment.
        String dn2 = postedDebitNote(b, customer, invoiceId, 30);
        JsonNode pay = receipt(b, customer, invoiceId, 120);
        Res refused = keyed(b.token(), "PATCH", "/procurement/debit-notes/" + dn2 + "/reverse", REASON);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("already settled",
                pay.get("paymentNumber").asString());
        assertThat(status(entryOf("debit_note", dn2))).isEqualTo("posted");
    }

    // ------------------------------------------------------------------ controls

    @Test
    @DisplayName("[document-reversals] with the document_reversal pair on, the invoice's maker cannot cancel it; a"
            + " second user can")
    void separationOfDuties() {
        Books b = freshBooks();
        String customer = partner(b, true);
        String id = postedInvoice(b, customer, 100).get("id").asString();
        okAs(b, 200, "PUT", "/approval-controls/sod-rules/document_reversal", Map.of("isActive", true));

        Res maker = as(b, "POST", "/invoices/" + id + "/cancel", REASON);
        assertThat(maker.status()).as(maker.raw().body()).isEqualTo(403);
        assertThat(maker.body().get("message").asString()).contains("Separation of duties");
        assertThat(docStatus(id)).isEqualTo("posted");

        Res checker = call("POST", "/invoices/" + id + "/cancel", REASON, secondAdmin(b));
        assertThat(checker.status()).as(checker.raw().body()).isEqualTo(200);
        assertThat(docStatus(id)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("[document-reversals] a payment matched to a bank statement line is not voided until it is unmatched")
    void bankMatchedPaymentVoidRefused() {
        Books b = freshBooks();
        String customer = partner(b, true);
        String invoiceId = postedInvoice(b, customer, 100).get("id").asString();
        JsonNode pay = receipt(b, customer, invoiceId, 100);
        String payId = pay.get("id").asString();
        String line = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"BankStatementLine\" (id, \"organizationId\", \"bankAccountId\", \"postedAt\","
                        + " \"externalRef\", description, amount, \"currencyCode\", status, \"matchedPaymentId\") VALUES"
                        + " (?, ?, ?, now(), 'STMT-1', 'Customer receipt', 100, ?, 'matched', ?)")
                .params(line, b.org(), mapped(b.org(), "default_bank"), baseCurrency(b.org()), payId).update();

        Res refused = keyed(b.token(), "POST", "/payments/" + payId + "/void", Map.of("reason", "Bounced"));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("STMT-1", "unmatch it in bank reconciliation");
        assertThat(residual(invoiceId)).isEqualByComparingTo("0");

        jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'unmatched', \"matchedPaymentId\" = NULL WHERE id = ?")
                .param(line).update();
        Res voided = keyed(b.token(), "POST", "/payments/" + payId + "/void", Map.of("reason", "Bounced"));
        assertThat(voided.status()).as(voided.raw().body()).isEqualTo(200);
        assertThat(residual(invoiceId)).isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT jsonb_array_length(unwound) FROM \"DocumentReversal\" WHERE \"documentType\" ="
                + " 'payment' AND \"documentId\" = ?").param(payId).query(Integer.class).single()).isEqualTo(1);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
    }
}
