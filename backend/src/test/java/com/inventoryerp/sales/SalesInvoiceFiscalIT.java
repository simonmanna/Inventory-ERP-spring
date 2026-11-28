package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.sales.application.FiscalAdapters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C4b (AD-5): every posted invoice of an order-type Sale is registered with the fiscal authority of the
 * sale's branch through the outbox, one operation per document ({@code ownerKind = document}), and so is a credit note
 * on a registered sale invoice, once the invoice is registered. A registered invoice is credited, not cancelled. The
 * authority is the mock provider (remote: queued, sent by the retry route) or {@code none} (local: completed at once).
 */
class SalesInvoiceFiscalIT extends CashSaleTestBase {

    @Autowired
    FiscalAdapters.Mock mock;

    final String today = LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    String mockBranch;
    String service;

    @BeforeAll
    void fiscalBranch() {
        mockBranch = branch("Fiscal orders " + sfx);
        String floor = location("Fiscal orders floor " + sfx, mockBranch);
        profile(mockBranch, floor, false, Map.of("fiscalProvider", "mock", "numberPrefix", "F" + rnd()));
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"categoryId\", \"updatedAt\") VALUES (?, ?, ?,"
                + " ?, 'service', 100, 0, false, 'ordered', ?, now())").params(service, demoOrg, "FIS-"
                        + UUID.randomUUID().toString().substring(0, 8), "Fiscal service " + sfx, category).update();
    }

    @AfterEach
    void resetMock() {
        mock.reset();
    }

    // ------------------------------------------------------------------ helpers

    /** A confirmed order-type sale of {@code qty} × 100.00 + VAT 18 % at {@code b}. */
    String order(String b, String customer, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", customer);
        body.put("branchId", b);
        body.put("lines", List.of(Map.of("productId", service, "quantity", qty, "unitPrice", 100, "taxId", vat)));
        String id = ok(201, "POST", "/sales/orders", body).get("id").asString();
        ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
        return id;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    /** A posted regular invoice of the sale. */
    String invoice(String so) {
        String id = money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular")).get("id").asString();
        money("/invoices/" + id + "/post", null);
        return id;
    }

    /** A posted down-payment invoice of {@code amount}. */
    String downPayment(String so, int amount) {
        String id = money("/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "amount", amount))
                .get("id").asString();
        money("/invoices/" + id + "/post", null);
        return id;
    }

    /** A posted value credit note of 100.00 + VAT on {@code invoiceId}. */
    String creditNote(String invoiceId, String customer) {
        String id = money("/credit-notes", Map.of("partnerId", customer, "issueDate", today, "reversedDocumentId",
                invoiceId, "lines", List.of(Map.of("description", "Price adjustment", "quantity", 1, "unitPrice", 100,
                        "taxId", vat)))).get("id").asString();
        money("/credit-notes/" + id + "/post", null);
        return id;
    }

    Res cancel(String invoiceId) {
        return call("POST", "/invoices/" + invoiceId + "/cancel", Map.of("reason", "Wrong customer"), key(adminToken));
    }

    void retry(String so) {
        Res r = call("POST", "/sales/orders/" + so + "/fiscal/retry", null, adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
    }

    /** The fiscal operation {@code operation} of a document. */
    Map<String, Object> op(String documentId, String operation) {
        return jdbc.sql("SELECT * FROM \"FiscalDocument\" WHERE \"invoiceId\" = ? AND operation = ?")
                .params(documentId, operation).query().singleRow();
    }

    long ops(String documentId) {
        return count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"invoiceId\" = ?", documentId);
    }

    static String ref(String documentId, String operation) {
        return "doc:" + documentId + ":" + operation + ":1";
    }

    String saleFiscalStatus(String so) {
        return jdbc.sql("SELECT \"fiscalStatus\" FROM \"SalesOrder\" WHERE id = ?").param(so).query(String.class)
                .optional().orElse(null);
    }

    long events(String so, String action) {
        return count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = ?", so, action);
    }

    String status(String documentId) {
        return jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(documentId).query(String.class)
                .single();
    }

    static BigDecimal num(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    // ------------------------------------------------------------------ FI-O1 … FI-O3: registration on post

    @Test
    @DisplayName("FI-O1: a posted order invoice queues its fiscal document; the retry registers it with its numbers")
    void postedInvoiceIsRegistered() {
        String c = customer();
        String so = order(mockBranch, c, 10);
        String before = saleFiscalStatus(so);
        String inv = invoice(so);

        Map<String, Object> d = op(inv, "issue");
        assertThat(d.get("ownerKind")).isEqualTo("document");
        assertThat(d.get("salesOrderId")).isEqualTo(so);
        assertThat(d.get("submissionId")).isNull();
        assertThat(d.get("provider")).isEqualTo("mock");
        assertThat(d.get("status")).as("queued in the posting transaction, sent afterwards").isEqualTo("pending");
        assertThat(d.get("requestRef")).isEqualTo(ref(inv, "issue"));
        assertThat(mock.submissions(ref(inv, "issue"))).isZero();
        assertThat(saleFiscalStatus(so)).as("the sale's own status is a cash sale's document").isEqualTo(before);

        JsonNode listed = ok(200, "GET", "/sales/orders/" + so + "/fiscal", null);
        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).get("requestRef").asString()).isEqualTo(ref(inv, "issue"));

        retry(so);
        d = op(inv, "issue");
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(String.valueOf(d.get("fiscalNumber"))).startsWith("FDN-");
        assertThat(mock.submissions(ref(inv, "issue"))).isEqualTo(1);
        Map<String, Object> sent = mock.payload(ref(inv, "issue"));
        String number = jdbc.sql("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ?").param(inv)
                .query(String.class).single();
        assertThat(sent.get("invoiceNumber")).isEqualTo(number);
        assertThat(sent.get("kind")).isEqualTo("sale");
        assertThat(num(sent.get("subtotal"))).isEqualByComparingTo("1000");
        assertThat(num(sent.get("taxAmount"))).isEqualByComparingTo("180");
        assertThat(num(sent.get("total"))).isEqualByComparingTo("1180");
        assertThat(saleFiscalStatus(so)).isEqualTo(before);
        assertThat(events(so, "fiscal_issue_requested")).isEqualTo(1);
        assertThat(events(so, "fiscal_issue_succeeded")).isEqualTo(1);
    }

    @Test
    @DisplayName("FI-O2: a sale's invoices are registered side by side (down payment and final invoice both open)")
    void invoicesOfOneSaleAreRegisteredSideBySide() {
        String c = customer();
        String so = order(mockBranch, c, 10);
        String dp = downPayment(so, 300);
        String inv = invoice(so);
        assertThat(op(dp, "issue").get("status")).isEqualTo("pending");
        assertThat(op(inv, "issue").get("status")).as("not blocked by the down payment's open operation")
                .isEqualTo("pending");
        assertThat(mock.submissions(ref(dp, "issue"))).isZero();

        retry(so);
        Map<String, Object> first = op(dp, "issue");
        Map<String, Object> second = op(inv, "issue");
        assertThat(first.get("status")).isEqualTo("succeeded");
        assertThat(second.get("status")).isEqualTo("succeeded");
        assertThat(first.get("fiscalNumber")).isNotEqualTo(second.get("fiscalNumber"));
        assertThat(mock.payload(ref(dp, "issue")).get("kind")).isEqualTo("sale_down_payment");
    }

    @Test
    @DisplayName("FI-O3: a branch without an outside authority (provider none) registers the invoice at once")
    void localProviderCompletesAtOnce() {
        String so = order(branch, customer(), 2);
        String inv = invoice(so);
        Map<String, Object> d = op(inv, "issue");
        assertThat(d.get("provider")).isEqualTo("none");
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(d.get("ownerKind")).isEqualTo("document");
    }

    // ------------------------------------------------------------------ FI-O4, FI-O7: credit notes

    @Test
    @DisplayName("FI-O4: a credit note waits for its invoice's registration, then goes with the invoice's fiscal number")
    void creditNoteWaitsForItsInvoice() {
        String c = customer();
        String so = order(mockBranch, c, 10);
        String inv = invoice(so);
        String cn = creditNote(inv, c);

        Map<String, Object> d = op(cn, "credit_note");
        assertThat(d.get("ownerKind")).isEqualTo("document");
        assertThat(d.get("salesOrderId")).isEqualTo(so);
        assertThat(d.get("provider")).as("the provider that registers the invoice").isEqualTo("mock");
        assertThat(d.get("status")).isEqualTo("pending");
        assertThat(d.get("requestRef")).isEqualTo(ref(cn, "credit_note"));

        // the invoice's registration times out after the provider accepted it: its outcome is unknown
        mock.script("timeout_accepted");
        retry(so);
        assertThat(op(inv, "issue").get("status")).isEqualTo("unknown");
        d = op(cn, "credit_note");
        assertThat(d.get("status")).as("waits while the invoice's registration is open").isEqualTo("pending");
        assertThat(((Number) d.get("submissions")).intValue()).isZero();
        assertThat(mock.submissions(ref(cn, "credit_note"))).isZero();

        retry(so);
        Map<String, Object> issue = op(inv, "issue");
        assertThat(issue.get("status")).isEqualTo("succeeded");
        assertThat(op(cn, "credit_note").get("status")).isEqualTo("succeeded");
        Map<String, Object> sent = mock.payload(ref(cn, "credit_note"));
        assertThat(sent.get("originalFiscalNumber")).isEqualTo(issue.get("fiscalNumber"));
        assertThat(sent.get("originalRequestRef")).isEqualTo(ref(inv, "issue"));
        assertThat(sent.get("originalInvoiceId")).isEqualTo(inv);
        assertThat(num(sent.get("total"))).isEqualByComparingTo("118");
        assertThat(events(so, "fiscal_credit_note_succeeded")).isEqualTo(1);
    }

    @Test
    @DisplayName("FI-O7: an invoice the authority rejected fails its queued credit note; later credits are not sent")
    void rejectedInvoiceFailsItsCreditNote() {
        String c = customer();
        String so = order(mockBranch, c, 10);
        String inv = invoice(so);
        String cn = creditNote(inv, c);

        mock.script("reject");
        retry(so);
        assertThat(op(inv, "issue").get("status")).isEqualTo("failed");
        Map<String, Object> d = op(cn, "credit_note");
        assertThat(d.get("status")).isEqualTo("failed");
        assertThat(d.get("lastError")).isEqualTo("The credited invoice is not registered with the fiscal authority");
        assertThat(mock.submissions(ref(cn, "credit_note"))).isZero();

        String second = creditNote(inv, c);
        assertThat(ops(second)).as("a credit on an unregistered invoice has nothing to credit at the authority")
                .isZero();
    }

    // ------------------------------------------------------------------ FI-O5: cancel

    @Test
    @DisplayName("FI-O5: a registered invoice is credited, not cancelled; a local provider records the cancel")
    void registeredInvoiceIsCreditedNotCancelled() {
        String so = order(mockBranch, customer(), 10);
        String inv = invoice(so);

        Res open = cancel(inv);
        assertThat(open.status()).as(open.raw().body()).isEqualTo(409);
        assertThat(message(open)).isEqualTo("The invoice's fiscal registration is still open; retry once it is"
                + " registered or has failed");
        assertThat(status(inv)).isEqualTo("posted");

        retry(so);
        Res registered = cancel(inv);
        assertThat(registered.status()).as(registered.raw().body()).isEqualTo(400);
        assertThat(message(registered)).isEqualTo("This invoice is registered with the fiscal authority: credit it"
                + " with a credit note instead of cancelling it");
        assertThat(status(inv)).isEqualTo("posted");

        String local = order(branch, customer(), 2);
        String localInv = invoice(local);
        Res cancelled = cancel(localInv);
        assertThat(cancelled.status()).as(cancelled.raw().body()).isEqualTo(200);
        assertThat(status(localInv)).isEqualTo("cancelled");
        Map<String, Object> d = op(localInv, "cancel");
        assertThat(d.get("ownerKind")).isEqualTo("document");
        assertThat(d.get("provider")).isEqualTo("none");
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(d.get("requestRef")).isEqualTo(ref(localInv, "cancel"));
    }

    // ------------------------------------------------------------------ FI-O6: cash sales

    @Test
    @DisplayName("FI-O6: a cash sale keeps its one sale-level document; a credit on its invoice is registered once")
    void cashSaleKeepsItsSaleDocument() {
        String c = customer();
        String id = paidDraft(c, 1);
        confirmed(id);
        String inv = invoiceOf(id);
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ?", id)).isEqualTo(1);
        Map<String, Object> issue = op(inv, "issue");
        assertThat(issue.get("ownerKind")).as("the confirm registers the sale, not its invoice").isEqualTo("sale");
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND \"ownerKind\" ="
                + " 'document'", id)).isZero();

        String paid = status(inv);
        Res refused = cancel(inv);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(status(inv)).isEqualTo(paid);

        String cn = creditNote(inv, c);
        Map<String, Object> credit = op(cn, "credit_note");
        assertThat(credit.get("ownerKind")).isEqualTo("document");
        assertThat(credit.get("salesOrderId")).isEqualTo(id);
        assertThat(credit.get("status")).isEqualTo("succeeded");
        assertThat(saleFiscalStatus(id)).as("a credit does not cancel the sale's document").isEqualTo("issued");
    }

    // ------------------------------------------------------------------ FI-O8: authorization

    @Test
    @DisplayName("FI-O8: an order's fiscal routes follow the order permissions; reconciling needs invoice creation")
    void fiscalRoutesFollowTheSaleType() {
        String so = order(mockBranch, customer(), 10);
        String inv = invoice(so);
        String viewer = user("fiscal-viewer", mockBranch, "sales:order:read", "sales:order:read_all");
        String cashViewer = user("fiscal-cash-viewer", mockBranch, "sales:cash_sale:read");
        String outsider = user("fiscal-outsider", mockBranch, "sales:quotation:read");
        String cashReviewer = user("fiscal-cash-reviewer", mockBranch, "sales:cash_sale:review", "sales:order:read",
                "sales:order:read_all");
        String invoicer = user("fiscal-invoicer", mockBranch, "sales:invoice:create", "sales:order:read",
                "sales:order:read_all");

        assertThat(call("GET", "/sales/orders/" + so + "/fiscal", null, viewer).status()).isEqualTo(200);
        assertThat(call("GET", "/sales/orders/" + so + "/fiscal", null, outsider).status()).isEqualTo(403);
        assertThat(call("POST", "/sales/orders/" + so + "/fiscal/retry", null, outsider).status()).isEqualTo(403);
        // an order is not visible to a cash-sale reader (C3: orders follow the order permissions)
        assertThat(call("GET", "/sales/orders/" + so + "/fiscal", null, cashViewer).status()).isEqualTo(404);

        mock.script("timeout_accepted");
        assertThat(call("POST", "/sales/orders/" + so + "/fiscal/retry", null, viewer).status()).isEqualTo(200);
        Map<String, Object> d = op(inv, "issue");
        assertThat(d.get("status")).isEqualTo("unknown");
        String path = "/sales/orders/" + so + "/fiscal/" + d.get("id") + "/resolve";
        Map<String, Object> body = Map.of("resolution", "issued", "fiscalNumber", "FDN-PORTAL-" + rnd(), "note",
                "Found on the authority's portal");
        assertThat(call("POST", path, body, viewer).status()).isEqualTo(403);
        Res cashOnly = call("POST", path, body, cashReviewer);
        assertThat(cashOnly.status()).isEqualTo(403);
        assertThat(message(cashOnly)).isEqualTo("Missing required permission(s): sales:invoice:create");
        Res resolved = call("POST", path, body, invoicer);
        assertThat(resolved.status()).as(resolved.raw().body()).isEqualTo(200);
        assertThat(op(inv, "issue").get("status")).isEqualTo("succeeded");
        assertThat(op(inv, "issue").get("fiscalNumber")).isEqualTo(body.get("fiscalNumber"));
    }
}
