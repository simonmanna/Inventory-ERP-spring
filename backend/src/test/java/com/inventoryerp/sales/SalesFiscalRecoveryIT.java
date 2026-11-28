package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.sales.application.CashSaleFiscalService;
import com.inventoryerp.sales.application.FiscalAdapters;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C3b (§5.5): recovery of the fiscal outbox of cash Sales against the mock authority, which can accept a
 * request and lose the answer, lose the request, crash the worker after acceptance, or lack lookup and
 * deduplication. Each case shows how many requests reached the provider and how many fiscal documents it issued: an
 * unknown outcome is looked up before anything is sent again, a resend reuses the request reference, and a provider
 * that can neither be asked nor ignore a repeat gets nothing automatically (manual review).
 */
class SalesFiscalRecoveryIT extends CashSaleTestBase {

    @Autowired
    FiscalAdapters.Mock mock;

    @Autowired
    CashSaleFiscalService fiscal;

    String fiscalBranch;
    String fiscalLocation;

    @BeforeAll
    void fiscalCounter() {
        fiscalBranch = branch("Fiscal recovery counter " + sfx);
        fiscalLocation = location("Fiscal recovery floor " + sfx, fiscalBranch);
        receive(goods, fiscalLocation, 10_000);
        profile(fiscalBranch, fiscalLocation, false, Map.of("fiscalProvider", "mock", "numberPrefix", "R" + rnd()));
    }

    @AfterEach
    void resetMock() {
        mock.reset();
    }

    // ------------------------------------------------------------------ helpers

    /** A completed cash sale at the mock-fiscal counter; its fiscal document is queued, not sent. */
    String fiscalSale() {
        String id = draft(fiscalBranch, customer(), 1);
        pay(id, Map.of("method", "cash", "amount", sale(id).get("total").decimalValue()));
        confirmed(id);
        return id;
    }

    Map<String, Object> issue(String id) {
        return jdbc.sql("SELECT * FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND operation = 'issue'").param(id)
                .query().singleRow();
    }

    void retry(String id) {
        Res r = call("POST", "/sales/orders/" + id + "/fiscal/retry", null, adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
    }

    static String ref(String id) {
        return "so:" + id + ":issue:1";
    }

    static int n(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    String fiscalStatus(String id) {
        JsonNode v = sale(id).get("fiscalStatus");
        return v == null || v.isNull() ? null : v.asString();
    }

    long events(String id, String action) {
        return count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = ?", id, action);
    }

    // ------------------------------------------------------------------ FR-1 … FR-4

    @Test
    @DisplayName("FR-1 / FR-2: accepted but timed out → unknown; the retry looks it up and records it, sending nothing")
    void acceptedButTimedOutIsLookedUp() {
        String id = fiscalSale();
        Map<String, Object> d = issue(id);
        assertThat(d.get("status")).isEqualTo("pending");
        assertThat(n(d, "submissions")).isZero();
        assertThat(fiscalStatus(id)).isEqualTo("pending");

        mock.script("timeout_accepted");
        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("unknown");
        assertThat(d.get("claimedAt")).isNull();
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat(mock.documents(ref(id))).isEqualTo(1);
        assertThat(fiscalStatus(id)).isEqualTo("pending");

        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(d.get("fiscalNumber")).isNotNull();
        assertThat(n(d, "attempts")).isEqualTo(2);
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat(mock.submissions(ref(id))).isEqualTo(1);
        assertThat(mock.documents(ref(id))).isEqualTo(1);
        assertThat(fiscalStatus(id)).isEqualTo("issued");
        assertThat(events(id, "fiscal_issue_succeeded")).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-3: a lost request stays unknown within the provider's settlement time, then goes again under its"
            + " reference")
    void lostRequestIsSentAgainAfterSettlement() {
        String id = fiscalSale();
        mock.capabilities(true, false, Duration.ofHours(1));
        mock.script("timeout_lost");
        retry(id);
        Map<String, Object> d = issue(id);
        assertThat(d.get("status")).isEqualTo("unknown");
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat(mock.submissions(ref(id))).isZero();

        // looked up within the settlement time: not there yet, and nothing is sent
        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("unknown");
        assertThat(n(d, "attempts")).isEqualTo(2);
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat((String) d.get("lastError")).contains("settlement time");
        assertThat(mock.submissions(ref(id))).isZero();

        // after the settlement time: sent again under the same reference; one document
        jdbc.sql("UPDATE \"FiscalDocument\" SET \"submittedAt\" = \"submittedAt\" - interval '2 hours' WHERE id = ?")
                .param(d.get("id")).update();
        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(d.get("requestRef")).isEqualTo(ref(id));
        assertThat(n(d, "submissions")).isEqualTo(2);
        assertThat(mock.submissions(ref(id))).isEqualTo(1);
        assertThat(mock.documents(ref(id))).isEqualTo(1);
        assertThat(fiscalStatus(id)).isEqualTo("issued");
    }

    @Test
    @DisplayName("FR-4: without lookup or deduplication nothing is sent again: review, then reconciled by hand")
    void providerWithoutLookupGoesToReview() {
        String reviewer = user("fr-reviewer", fiscalBranch, Permissions.SALES__CASH_SALE_READ,
                Permissions.SALES__CASH_SALE_REVIEW);
        String clerk = user("fr-clerk", fiscalBranch, Permissions.SALES__CASH_SALE_READ);
        mock.capabilities(false, false, Duration.ZERO);

        // the provider issued the document; the answer was lost
        String id = fiscalSale();
        mock.script("timeout_accepted");
        retry(id);
        assertThat(issue(id).get("status")).isEqualTo("unknown");
        retry(id);
        Map<String, Object> d = issue(id);
        assertThat(d.get("status")).isEqualTo("review");
        assertThat((String) d.get("lastError")).startsWith("The provider cannot be asked whether it received this request");
        assertThat(mock.submissions(ref(id))).isEqualTo(1);
        assertThat(events(id, "fiscal_issue_review")).isEqualTo(1);
        retry(id);
        assertThat(issue(id).get("status")).isEqualTo("review");
        assertThat(mock.submissions(ref(id))).isEqualTo(1);

        String path = "/sales/orders/" + id + "/fiscal/" + d.get("id") + "/resolve";
        assertThat(call("POST", path, Map.of("resolution", "issued", "fiscalNumber", "FDN-HAND", "note", "Checked"), clerk)
                .status()).isEqualTo(403);
        Res noNote = call("POST", path, Map.of("resolution", "issued", "fiscalNumber", "FDN-HAND"), reviewer);
        assertThat(noNote.status()).isEqualTo(400);
        assertThat(message(noNote)).isEqualTo("note should not be empty");
        Res noNumber = call("POST", path, Map.of("resolution", "issued", "note", "Checked"), reviewer);
        assertThat(noNumber.status()).isEqualTo(400);
        assertThat(message(noNumber)).isEqualTo("fiscalNumber should not be empty: give the number the provider issued");
        Res odd = call("POST", path, Map.of("resolution", "maybe", "note", "Checked"), reviewer);
        assertThat(message(odd)).isEqualTo("resolution must be one of: issued, not_received");

        Res resolved = call("POST", path, Map.of("resolution", "issued", "fiscalNumber", "FDN-HAND-1", "note",
                "Found on the authority's portal"), reviewer);
        assertThat(resolved.status()).as(resolved.raw().body()).isEqualTo(200);
        assertThat(resolved.body().get("status").asString()).isEqualTo("succeeded");
        assertThat(resolved.body().get("fiscalNumber").asString()).isEqualTo("FDN-HAND-1");
        assertThat(resolved.body().get("resolution").asString()).isEqualTo("issued: Found on the authority's portal");
        assertThat(resolved.body().get("resolvedById").isNull()).isFalse();
        assertThat(fiscalStatus(id)).isEqualTo("issued");
        assertThat(events(id, "fiscal_issue_resolved")).isEqualTo(1);
        assertThat(mock.submissions(ref(id))).isEqualTo(1);
        Res again = call("POST", path, Map.of("resolution", "issued", "fiscalNumber", "FDN-HAND-2", "note", "Twice"),
                reviewer);
        assertThat(again.status()).isEqualTo(400);
        assertThat(message(again)).isEqualTo("Only an operation whose outcome is unknown can be reconciled by hand");

        // never received: sent again under the same reference
        String lost = fiscalSale();
        mock.script("timeout_lost");
        retry(lost);
        retry(lost);
        Map<String, Object> l = issue(lost);
        assertThat(l.get("status")).isEqualTo("review");
        assertThat(mock.submissions(ref(lost))).isZero();
        Res resent = call("POST", "/sales/orders/" + lost + "/fiscal/" + l.get("id") + "/resolve", Map.of("resolution",
                "not_received", "note", "Not on the authority's portal"), reviewer);
        assertThat(resent.status()).as(resent.raw().body()).isEqualTo(200);
        l = issue(lost);
        assertThat(l.get("status")).isEqualTo("succeeded");
        assertThat(l.get("requestRef")).isEqualTo(ref(lost));
        assertThat(n(l, "submissions")).isEqualTo(2);
        assertThat(mock.submissions(ref(lost))).isEqualTo(1);
        assertThat(mock.documents(ref(lost))).isEqualTo(1);
        assertThat(fiscalStatus(lost)).isEqualTo("issued");
    }

    // ------------------------------------------------------------------ RC-5, alerts

    @Test
    @DisplayName("RC-5: a worker dying after acceptance leaves the row sent; once its claim expires it is looked up"
            + " first: one send, one document")
    void crashAfterAcceptanceIsLookedUpFirst() {
        String id = fiscalSale();
        mock.script("crash_accepted");
        assertThatThrownBy(() -> asTenant(() -> fiscal.retryForSale(id)))
                .isInstanceOf(FiscalAdapters.Mock.SimulatedCrash.class);
        Map<String, Object> d = issue(id);
        assertThat(d.get("status")).isEqualTo("sent");
        assertThat(d.get("claimedAt")).isNotNull();
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat(mock.documents(ref(id))).isEqualTo(1);

        // a live claim is left to its worker
        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("sent");
        assertThat(n(d, "attempts")).isEqualTo(1);

        // the claim expires: the next attempt asks the provider before sending anything
        jdbc.sql("UPDATE \"FiscalDocument\" SET \"claimedAt\" = \"claimedAt\" - interval '3 minutes' WHERE id = ?")
                .param(d.get("id")).update();
        retry(id);
        d = issue(id);
        assertThat(d.get("status")).isEqualTo("succeeded");
        assertThat(d.get("claimedAt")).isNull();
        assertThat(n(d, "attempts")).isEqualTo(2);
        assertThat(n(d, "submissions")).isEqualTo(1);
        assertThat(mock.submissions(ref(id))).isEqualTo(1);
        assertThat(mock.documents(ref(id))).isEqualTo(1);
        assertThat(fiscalStatus(id)).isEqualTo("issued");
    }

    @Test
    @DisplayName("an operation unconfirmed after 30 minutes raises one alert: a timeline line and a notification")
    void stuckOperationRaisesOneAlert() {
        String id = fiscalSale();
        mock.script("timeout_accepted");
        retry(id);
        Map<String, Object> d = issue(id);
        assertThat(d.get("status")).isEqualTo("unknown");
        asTenant(fiscal::alertStuck);
        assertThat(issue(id).get("alertedAt")).as("younger than the alert threshold").isNull();

        jdbc.sql("UPDATE \"FiscalDocument\" SET \"createdAt\" = \"createdAt\" - interval '31 minutes' WHERE id = ?")
                .param(d.get("id")).update();
        String title = "Fiscal issue " + ref(id) + " is not confirmed";
        assertThat(asTenant(fiscal::alertStuck)).isPositive();
        assertThat(issue(id).get("alertedAt")).isNotNull();
        assertThat(events(id, "fiscal_issue_stuck")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND title ="
                + " ?", demoOrg, adminId, title)).isEqualTo(1);

        asTenant(fiscal::alertStuck);
        assertThat(events(id, "fiscal_issue_stuck")).as("alerted once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND title ="
                + " ?", demoOrg, adminId, title)).isEqualTo(1);
    }
}
