package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.sales.application.FiscalAdapters;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C3b (§7.4): the Reverse of a cash Sale, through {@code POST /sales/orders/:id/reverse} with production
 * beans. Covers:
 * <ul>
 *   <li>a refund over each tender, and the money kept on the customer's account, with exact journals, stock, outcome
 *       and replay;
 *   <li>credit-note approval asked for before the fiscal cancel (TA-10);
 *   <li>a fault at each step of the reversal transaction rolling all of it back after the fiscal cancel committed, and
 *       the retry with the same key running once (RV-1…RV-4);
 *   <li>a remote fiscal authority: the reversal refused while the document's outcome is unknown (FR-5), the cancel as
 *       phase 1, looked up and sent again under the same reference;
 *   <li>request checks, permissions and concurrent reversals.
 * </ul>
 */
class SalesCashReversalIT extends CashSaleTestBase {

    @Autowired
    FiscalAdapters.Mock mock;

    @AfterEach
    void resetMock() {
        mock.reset();
    }

    // ------------------------------------------------------------------ helpers

    /** A reversal of every line of the sale, as the cashier confirms it at the counter. */
    Map<String, Object> reverseBody(String id, String settlement) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : jdbc.sql("SELECT id, quantity FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY"
                + " \"sortOrder\", id").param(id).query().listOfRows()) {
            lines.add(Map.of("orderLineId", l.get("id"), "quantity", l.get("quantity")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "The customer brought the goods back");
        body.put("settlement", settlement);
        body.put("lines", lines);
        return body;
    }

    Res reverse(String token, String id, Map<String, Object> body) {
        return call("POST", "/sales/orders/" + id + "/reverse", body, key(token));
    }

    /** A completed cash sale of {@code qty} × goods at 100 + VAT 18 %, paid in cash. */
    String doneSale(String customer, int qty) {
        String id = paidDraft(customer, qty);
        assertThat(confirmed(id).get("status").asString()).isEqualTo("done");
        return id;
    }

    List<Map<String, Object>> refunds(String partner) {
        return jdbc.sql("SELECT \"paymentMethod\", \"accountId\", amount FROM \"Payment\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND direction = 'outbound' ORDER BY amount DESC").params(demoOrg, partner).query()
                .listOfRows();
    }

    long returnsOf(String id) {
        return count("SELECT count(*) FROM \"SalesReturn\" WHERE \"orderId\" = ?", id);
    }

    long postedCredits(String invoiceId) {
        return count("SELECT count(*) FROM \"Document\" WHERE \"reversedDocumentId\" = ? AND \"documentType\"::text ="
                + " 'credit_note' AND status::text IN ('posted', 'paid')", invoiceId);
    }

    String col(String id, String column) {
        return jdbc.sql("SELECT \"" + column + "\"::text FROM \"SalesOrder\" WHERE id = ?").param(id).query(String.class)
                .optional().orElse(null);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    // ------------------------------------------------------------------ refund and credit

    @Test
    @DisplayName("RV: a refund reversal takes every line back, credits the invoice and pays each tender back, once")
    void refundReversal() {
        String c = customer();
        BigDecimal before = onHand();
        BigDecimal valueBefore = (BigDecimal) ledger.stock(goods, location).get("value");
        String id = draft(branch, c, 10);
        pay(id, Map.of("method", "cash", "amount", 1000));
        pay(id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 180, "reference", "RV" + UUID.randomUUID()
                .toString().substring(0, 8)));
        confirmed(id);
        String inv = invoiceOf(id);
        assertThat(onHand()).isEqualByComparingTo(before.subtract(BigDecimal.TEN));
        assertThat(sale(id).get("actions").toString()).contains("reverse");
        assertThat(text(sale(id), "fiscalStatus")).isEqualTo("issued");

        Map<String, String> headers = key(adminToken);
        Res r = call("POST", "/sales/orders/" + id + "/reverse", reverseBody(id, "refund"), headers);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode s = r.body();
        assertThat(text(s, "status")).isEqualTo("cancelled");
        assertThat(text(s, "reversedAt")).isNotNull();
        assertThat(text(s, "reversalStage")).isEqualTo("reversed");
        assertThat(text(s, "reversalReason")).isEqualTo("The customer brought the goods back");
        assertThat(text(s, "fiscalStatus")).isEqualTo("cancelled");
        assertThat(text(s, "paymentStatus")).isEqualTo("refunded");
        assertThat(text(s, "creditStatus")).isEqualTo("full");
        assertThat(text(s, "settlementStatus")).isEqualTo("settled");
        assertThat(s.get("amountRefunded").decimalValue()).isEqualByComparingTo("1180");
        assertThat(s.get("amountInvoiced").decimalValue()).isEqualByComparingTo("1180");
        assertThat(s.get("amountCredited").decimalValue()).isEqualByComparingTo("1180");
        assertThat(s.get("actions").toString()).doesNotContain("reverse");

        // the goods are back on the counter at the cost they left at
        assertThat(onHand()).isEqualByComparingTo(before);
        assertThat((BigDecimal) ledger.stock(goods, location).get("value")).isEqualByComparingTo(valueBefore);
        // one return, closed with the credit note
        Map<String, Object> ret = jdbc.sql("SELECT id, status, outcome, \"creditNoteId\" FROM \"SalesReturn\" WHERE"
                + " \"orderId\" = ?").param(id).query().singleRow();
        assertThat(ret.get("status")).isEqualTo("closed");
        assertThat(ret.get("outcome")).isEqualTo("credit_note");
        assertThat(ret.get("id")).isEqualTo(col(id, "reversalReturnId"));
        // the credit note against the sale's invoice: Dr returns 1000, Dr VAT 180 / Cr AR 1180
        String cn = col(id, "reversalCreditNoteId");
        assertThat(ret.get("creditNoteId")).isEqualTo(cn);
        assertThat(sourceKind(cn)).as("ADR-S094 §8.2").isEqualTo("credit");
        // ADR-S094 C4b: the reversal cancels the sale's fiscal document; its credit note is not registered as a credit
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND \"ownerKind\" ="
                + " 'document'", id)).isZero();
        Map<String, Object> doc = jdbc.sql("SELECT status::text AS status, \"reversedDocumentId\", \"totalAmount\","
                + " \"journalEntryId\" FROM \"Document\" WHERE id = ?").param(cn).query().singleRow();
        assertThat(doc.get("status")).isIn("posted", "paid");
        assertThat(doc.get("reversedDocumentId")).isEqualTo(inv);
        assertThat((BigDecimal) doc.get("totalAmount")).isEqualByComparingTo("1180");
        String ar = ledger.mapped("accounts_receivable");
        Map<String, BigDecimal> cnLines = net((String) doc.get("journalEntryId"));
        assertThat(cnLines.get(ar)).isEqualByComparingTo("-1180");
        assertThat(cnLines.get(vatAccount)).isEqualByComparingTo("180");
        assertThat(cnLines).hasSize(3);
        // paid back over each tender, its own amount, method and account
        List<Map<String, Object>> out = refunds(c);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("paymentMethod")).isEqualTo("cash");
        assertThat(out.get(0).get("accountId")).isEqualTo(cashAccount);
        assertThat((BigDecimal) out.get(0).get("amount")).isEqualByComparingTo("1000");
        assertThat(out.get(1).get("paymentMethod")).isEqualTo("mobile_money");
        assertThat(out.get(1).get("accountId")).isEqualTo(mtnAccount);
        assertThat((BigDecimal) out.get(1).get("amount")).isEqualByComparingTo("180");
        // nothing left open: the customer's receivable is zero and ties out, VAT nets to zero
        assertThat(balance(ar, c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        assertThat(balance(vatAccount, null)).isEqualByComparingTo("0");
        // the fiscal document was cancelled before the reversal (provider none: at once)
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'cancel' AND"
                + " status = 'succeeded'", id)).isEqualTo(1);
        // the timeline
        assertThat(count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = 'reverse' AND"
                + " \"fromStatus\" = 'done' AND \"toStatus\" = 'cancelled'", id)).isEqualTo(1);

        // the same request replays; nothing runs twice
        Res again = call("POST", "/sales/orders/" + id + "/reverse", reverseBody(id, "refund"), headers);
        assertThat(again.status()).as(again.raw().body()).isEqualTo(200);
        assertThat(returnsOf(id)).isEqualTo(1);
        assertThat(refunds(c)).hasSize(2);
        assertThat(postedCredits(inv)).isEqualTo(1);
        // a new request is refused
        Res twice = reverse(adminToken, id, reverseBody(id, "refund"));
        assertThat(twice.status()).as(twice.raw().body()).isEqualTo(400);
        assertThat(message(twice)).isEqualTo("This sale is already reversed");
    }

    @Test
    @DisplayName("RV: a credit reversal keeps the money on the customer's account; nothing is paid out")
    void creditReversal() {
        String c = customer();
        String id = doneSale(c, 4);
        Res r = reverse(adminToken, id, reverseBody(id, "credit"));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode s = r.body();
        assertThat(text(s, "status")).isEqualTo("cancelled");
        assertThat(text(s, "paymentStatus")).isEqualTo("credited");
        assertThat(text(s, "settlementStatus")).isEqualTo("settled");
        assertThat(s.get("amountRetainedAsCredit").decimalValue()).isEqualByComparingTo("472");
        assertThat(s.get("amountRefunded").decimalValue()).isEqualByComparingTo("0");
        assertThat(refunds(c)).isEmpty();
        // the customer holds the 472 as a credit balance
        assertThat(balance(ledger.mapped("accounts_receivable"), c)).isEqualByComparingTo("-472");
        assertThat(count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = 'reverse' AND"
                + " data->>'settlement' = 'credit'", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ approval (TA-10)

    @Test
    @DisplayName("TA-10: credit-note approval is asked for before the fiscal cancel; once approved the reversal runs")
    void approvalComesBeforeTheFiscalCancel() {
        String cashier = user("rv-cashier", branch, Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_VOID);
        String c = customer();
        String id = doneSale(c, 3);
        String inv = invoiceOf(id);
        String wf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, ?, 'credit_note', now())").params(wf, demoOrg, "Credit gate " + sfx).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Manager', ?, now())").params(UUID
                        .randomUUID().toString(), demoOrg, wf, new String[] {Permissions.APPROVALS__DECIDE}).update();
        try {
            Res gated = reverse(cashier, id, reverseBody(id, "refund"));
            assertThat(gated.status()).as(gated.raw().body()).isEqualTo(409);
            String cn = col(id, "reversalCreditNoteId");
            assertThat(cn).isNotNull();
            String request = jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"entityType\" = 'credit_note' AND"
                    + " \"entityId\" = ? AND status = 'pending'").param(cn).query(String.class).single();
            assertThat(message(gated)).startsWith("Credit note ").endsWith("for this reversal needs approval (request "
                    + request + "); reverse the sale again once it is approved");
            // nothing was cancelled at the fiscal authority, nothing came back, nothing was credited
            JsonNode waiting = sale(id);
            assertThat(text(waiting, "status")).isEqualTo("done");
            assertThat(text(waiting, "fiscalStatus")).isEqualTo("issued");
            assertThat(text(waiting, "reversalStage")).isEqualTo("approval_pending");
            assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'cancel'",
                    id)).isZero();
            assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(cn).query(String.class)
                    .single()).isEqualTo("draft");
            assertThat(returnsOf(id)).isZero();
            // asked again while pending: the same draft and the same request
            Res still = reverse(cashier, id, reverseBody(id, "refund"));
            assertThat(still.status()).as(still.raw().body()).isEqualTo(409);
            assertThat(message(still)).contains("(request " + request + ")");
            assertThat(count("SELECT count(*) FROM \"Document\" WHERE \"reversedDocumentId\" = ? AND \"documentType\"::text"
                    + " = 'credit_note'", inv)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action ="
                    + " 'reverse_approval_requested'", id)).isEqualTo(1);

            Res decided = call("POST", "/approvals/" + request + "/decide", Map.of("status", "approved", "comment",
                    "Goods checked back in"), adminToken);
            assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);

            Res done = reverse(cashier, id, reverseBody(id, "refund"));
            assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
            assertThat(text(done.body(), "status")).isEqualTo("cancelled");
            assertThat(text(done.body(), "fiscalStatus")).isEqualTo("cancelled");
            // the approved draft is the credit note that was posted
            assertThat(col(id, "reversalCreditNoteId")).isEqualTo(cn);
            assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(cn).query(String.class)
                    .single()).isIn("posted", "paid");
            assertThat(postedCredits(inv)).isEqualTo(1);
            assertThat(refunds(c)).hasSize(1);
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(wf).update();
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"workflowId\" = ? AND status = 'pending'")
                    .param(wf).update();
        }
    }

    // ------------------------------------------------------------------ atomicity (RV-1…RV-4)

    @Test
    @DisplayName("RV-1…RV-4: a fault anywhere in the reversal rolls all of it back; the fiscal cancel stays, the retry"
            + " runs once")
    void anyFailureInTheReversalRollsItBack() throws Exception {
        String c = customer();
        String id = doneSale(c, 5);
        String inv = invoiceOf(id);
        String saleSql = "'" + id + "'";
        Map<String, String> faults = new LinkedHashMap<>();
        // RV-1: the return itself, and the goods coming back into stock
        faults.put("SalesReturn", "(to_jsonb(NEW)->>'orderId') = " + saleSql);
        faults.put("InventoryLedger", "(to_jsonb(NEW)->>'referenceType') = 'sales_return' AND EXISTS (SELECT 1 FROM"
                + " \"SalesReturn\" r WHERE r.\"orderId\" = " + saleSql + ")");
        // RV-2: the credit note's journal
        faults.put("JournalEntry", "(to_jsonb(NEW)->>'sourceType') = 'credit_note' AND (to_jsonb(NEW)->>'sourceId') IN"
                + " (SELECT id FROM \"Document\" WHERE \"reversedDocumentId\" = '" + inv + "')");
        // RV-3: the refund
        faults.put("Payment", "(to_jsonb(NEW)->>'direction') = 'outbound' AND (to_jsonb(NEW)->>'partnerId') = '" + c
                + "'");
        // the last step, after the sale is cancelled
        faults.put("SalesOrderEvent", "(to_jsonb(NEW)->>'orderId') = " + saleSql + " AND (to_jsonb(NEW)->>'action') ="
                + " 'reverse'");
        BigDecimal before = onHand();
        int version = sale(id).get("version").asInt();
        Map<String, String> headers = null;
        for (Map.Entry<String, String> f : faults.entrySet()) {
            inject(f.getKey(), f.getValue());
            long fired = hits();
            headers = key(adminToken);
            Res r;
            try {
                r = call("POST", "/sales/orders/" + id + "/reverse", reverseBody(id, "refund"), headers);
            } finally {
                clearFault(f.getKey());
            }
            assertThat(r.status()).as("fault on %s → %s", f.getKey(), r.raw().body()).isEqualTo(500);
            assertThat(hits()).as("fault on %s fired", f.getKey()).isEqualTo(fired + 1);
            JsonNode s = sale(id);
            assertThat(text(s, "status")).as(f.getKey()).isEqualTo("done");
            assertThat(s.get("version").asInt()).as(f.getKey()).isEqualTo(version);
            assertThat(text(s, "reversedAt")).as(f.getKey()).isNull();
            // RV-4: phase 1 committed: the fiscal document is cancelled and the reversal waits to be asked for again
            assertThat(text(s, "fiscalStatus")).as(f.getKey()).isEqualTo("cancelled");
            assertThat(text(s, "reversalStage")).as(f.getKey()).isEqualTo("reversal_pending");
            assertThat(returnsOf(id)).as(f.getKey()).isZero();
            assertThat(postedCredits(inv)).as(f.getKey()).isZero();
            assertThat(refunds(c)).as(f.getKey()).isEmpty();
            assertThat(onHand()).as(f.getKey()).isEqualByComparingTo(before);
        }
        // the last request, retried with its key once the fault is gone, runs once
        Res retry = call("POST", "/sales/orders/" + id + "/reverse", reverseBody(id, "refund"), headers);
        assertThat(retry.status()).as(retry.raw().body()).isEqualTo(200);
        assertThat(text(retry.body(), "status")).isEqualTo("cancelled");
        assertThat(returnsOf(id)).isEqualTo(1);
        assertThat(postedCredits(inv)).isEqualTo(1);
        assertThat(refunds(c)).hasSize(1);
        assertThat(onHand()).isEqualByComparingTo(before.add(new BigDecimal("5")));
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'cancel'",
                id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ remote fiscal authority (FR-5, phase 1)

    @Test
    @DisplayName("FR-5 / phase 1: refused while the fiscal outcome is unknown; the cancel is looked up and sent again"
            + " under its reference before the reversal runs")
    void remoteFiscalCancelIsTheFirstPhase() {
        String fiscalBranch = branch("Fiscal counter " + sfx);
        String fiscalLocation = location("Fiscal floor " + sfx, fiscalBranch);
        receive(goods, fiscalLocation, 1_000);
        profile(fiscalBranch, fiscalLocation, false, Map.of("fiscalProvider", "mock", "numberPrefix", "F" + rnd()));
        String c = customer();
        String id = draft(fiscalBranch, c, 2);
        pay(id, Map.of("method", "cash", "amount", sale(id).get("total").decimalValue()));
        confirmed(id);
        // the confirm only queued the fiscal document: nothing was sent inside its transaction
        assertThat(text(sale(id), "fiscalStatus")).isEqualTo("pending");

        // the provider accepts the issue, the answer is lost: the outcome is unknown
        mock.script("timeout_accepted");
        assertThat(call("POST", "/sales/orders/" + id + "/fiscal/retry", null, adminToken).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'issue'")
                .param(id).query(String.class).single()).isEqualTo("unknown");
        // FR-5: no reversal while the document may exist at the authority
        Res blocked = reverse(adminToken, id, reverseBody(id, "refund"));
        assertThat(blocked.status()).as(blocked.raw().body()).isEqualTo(409);
        assertThat(message(blocked)).isEqualTo("The fiscal document is still being issued; retry once it is issued or"
                + " failed");
        assertThat(text(sale(id), "status")).isEqualTo("done");
        assertThat(col(id, "reversalRequestedAt")).isNull();
        // the lookup settles it: issued, one fiscal document
        assertThat(call("POST", "/sales/orders/" + id + "/fiscal/retry", null, adminToken).status()).isEqualTo(200);
        assertThat(text(sale(id), "fiscalStatus")).isEqualTo("issued");
        assertThat(mock.documents("so:" + id + ":issue:1")).isEqualTo(1);

        // phase 1: the cancellation never reaches the authority
        mock.script("timeout_lost");
        Res pending = reverse(adminToken, id, reverseBody(id, "refund"));
        assertThat(pending.status()).as(pending.raw().body()).isEqualTo(409);
        assertThat(message(pending)).isEqualTo("Fiscal cancellation requested; the reversal runs once it succeeds");
        JsonNode waiting = sale(id);
        assertThat(text(waiting, "status")).isEqualTo("done");
        assertThat(text(waiting, "fiscalStatus")).isEqualTo("cancellation_pending");
        assertThat(text(waiting, "reversalStage")).isEqualTo("fiscal_cancel_pending");
        assertThat(col(id, "reversalRequestedAt")).isNotNull();
        assertThat(col(id, "reversalReason")).isEqualTo("The customer brought the goods back");
        assertThat(returnsOf(id)).isZero();

        // asked again: looked up (not there), sent again under the same reference, cancelled; then the reversal runs
        Res done = reverse(adminToken, id, reverseBody(id, "refund"));
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(text(done.body(), "status")).isEqualTo("cancelled");
        assertThat(text(done.body(), "fiscalStatus")).isEqualTo("cancelled");
        Map<String, Object> cancel = jdbc.sql("SELECT \"requestRef\", status, submissions FROM \"FiscalDocument\" WHERE"
                + " \"salesOrderId\" = ? AND operation = 'cancel'").param(id).query().singleRow();
        assertThat(cancel.get("requestRef")).isEqualTo("so:" + id + ":cancel:1");
        assertThat(cancel.get("status")).isEqualTo("succeeded");
        assertThat(((Number) cancel.get("submissions")).intValue()).isEqualTo(2);
        assertThat(mock.submissions("so:" + id + ":cancel:1")).isEqualTo(1);
        assertThat(mock.documents("so:" + id + ":cancel:1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND \"ownerKind\" ="
                + " 'document'", id)).as("ADR-S094 C4b: no credit-note registration").isZero();
        assertThat(returnsOf(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ checks, permissions, concurrency

    @Test
    @DisplayName("the reversal checks its request, the sale's type and state, its key and its permission")
    void reversalChecks() {
        String c = customer();
        String id = paidDraft(c, 3);
        Res draft = reverse(adminToken, id, reverseBody(id, "refund"));
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(400);
        assertThat(message(draft)).isEqualTo("Only a completed cash sale can be reversed");
        confirmed(id);

        Map<String, Object> noReason = reverseBody(id, "refund");
        noReason.remove("reason");
        assertThat(message(reverse(adminToken, id, noReason))).isEqualTo("reason should not be empty");
        Map<String, Object> badSettlement = reverseBody(id, "cash");
        assertThat(message(reverse(adminToken, id, badSettlement))).isEqualTo("settlement must be one of: credit, refund");
        Map<String, Object> partial = reverseBody(id, "refund");
        String line = jdbc.sql("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ?").param(id).query(String.class)
                .single();
        partial.put("lines", List.of(Map.of("orderLineId", line, "quantity", 1)));
        Res part = reverse(adminToken, id, partial);
        assertThat(part.status()).isEqualTo(400);
        assertThat(message(part)).startsWith("A reversal takes every line back in full");
        Map<String, Object> none = reverseBody(id, "refund");
        none.put("lines", List.of());
        assertThat(reverse(adminToken, id, none).status()).isEqualTo(400);
        Map<String, Object> unknown = reverseBody(id, "refund");
        unknown.put("refundTo", "bank");
        assertThat(reverse(adminToken, id, unknown).status()).isEqualTo(400);
        Map<String, Object> stranger = reverseBody(id, "refund");
        stranger.put("lines", List.of(Map.of("orderLineId", line, "quantity", 3), Map.of("orderLineId", UUID.randomUUID()
                .toString(), "quantity", 1)));
        assertThat(reverse(adminToken, id, stranger).status()).isEqualTo(404);

        // a money route: no key, no reversal
        Map<String, String> noKeyHeaders = new LinkedHashMap<>();
        noKeyHeaders.put("Authorization", "Bearer " + adminToken);
        noKeyHeaders.put(NO_IDEMPOTENCY_KEY, "1");
        Res noKey = call("POST", "/sales/orders/" + id + "/reverse", reverseBody(id, "refund"), noKeyHeaders);
        assertThat(noKey.status()).as(noKey.raw().body()).isEqualTo(400);
        // the void permission reverses; the action shows only to who holds it
        String clerk = user("rv-clerk", branch, Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_CONFIRM);
        assertThat(reverse(clerk, id, reverseBody(id, "refund")).status()).isEqualTo(403);
        Res seen = call("GET", "/sales/orders/" + id, null, clerk);
        assertThat(seen.status()).as(seen.raw().body()).isEqualTo(200);
        assertThat(seen.body().get("actions").toString()).doesNotContain("reverse");
        // nothing moved
        assertThat(text(sale(id), "status")).isEqualTo("done");
        assertThat(returnsOf(id)).isZero();

        // an order-type sale is not reversed: its goods come back through a return
        String order = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", goods,
                "quantity", 1, "unitPrice", 100)))).get("id").asString();
        Res notCash = reverse(adminToken, order, reverseBody(order, "refund"));
        assertThat(notCash.status()).as(notCash.raw().body()).isEqualTo(400);
        assertThat(message(notCash)).isEqualTo("Only a cash sale is reversed; return an order's goods through a return");
    }

    @Test
    @DisplayName("CC-6: two reversals of one sale at once: one reverses it, the other is refused")
    void parallelReversalsReverseOnce() throws Exception {
        String c = customer();
        String id = doneSale(c, 2);
        List<Res> results = parallel(List.of(() -> reverse(adminToken, id, reverseBody(id, "refund")),
                () -> reverse(adminToken, id, reverseBody(id, "refund"))));
        assertThat(results.stream().map(Res::status).sorted().toList()).as(results.toString()).containsExactly(200, 400);
        Res refused = results.stream().filter(r -> r.status() == 400).findFirst().orElseThrow();
        assertThat(message(refused)).isEqualTo("This sale is already reversed");
        assertThat(returnsOf(id)).isEqualTo(1);
        assertThat(refunds(c)).hasSize(1);
        assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'cancel'",
                id)).isEqualTo(1);
    }
}
