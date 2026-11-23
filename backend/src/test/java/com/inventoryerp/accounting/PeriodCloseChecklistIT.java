package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.InventoryTieOutMonitor;
import com.inventoryerp.accounting.web.PeriodCloseController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIN-3: period-close checklist. Checks scoped to the period use April 2008, a month no other suite touches. */
class PeriodCloseChecklistIT extends ApiTestSupport {

    String periodId;

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    @BeforeAll
    void period() {
        periodId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, "
                + "\"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'open', now())")
                .params(periodId, demoOrg, "Checklist 2008-04", "2008-04-01", "2008-04-30").update();
    }

    JsonNode check(JsonNode checklist, String key) {
        for (JsonNode c : checklist.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key);
    }

    JsonNode signOff(JsonNode checklist, String key) {
        for (JsonNode s : checklist.get("signOffs")) {
            if (key.equals(s.get("key").asString())) {
                return s;
            }
        }
        throw new AssertionError("no sign-off " + key);
    }

    @Test
    @DisplayName("a draft journal in the period is an advisory finding, not a blocking one")
    void draftJournalAdvisory() {
        String path = "/fiscal-periods/" + periodId + "/close-checklist";
        assertThat(check(ok(200, "GET", path, null), "draft_journals").get("ok").asBoolean()).isTrue();
        ok(200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", "2008-04-10", "description", "Draft",
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 5),
                        Map.of("accountId", accountId("3100"), "credit", 5))));
        JsonNode after = check(ok(200, "GET", path, null), "draft_journals");
        assertThat(after.get("count").asInt()).isEqualTo(1);
        assertThat(after.get("ok").asBoolean()).isFalse();
        assertThat(after.get("blocking").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("sign off, take it back; an unknown step is refused")
    void signOffs() {
        String path = "/fiscal-periods/" + periodId + "/close-checklist/";
        Map<String, Object> sign = new LinkedHashMap<>();
        sign.put("signed", true);
        sign.put("note", "All accounts agreed");
        JsonNode signed = signOff(ok(200, "PUT", path + "bank_reconciled", sign), "bank_reconciled");
        assertThat(signed.get("signed").asBoolean()).isTrue();
        assertThat(signed.get("note").asString()).isEqualTo("All accounts agreed");
        assertThat(signed.get("signedAt").isNull()).isFalse();

        JsonNode removed = signOff(ok(200, "PUT", path + "bank_reconciled", Map.of("signed", false)),
                "bank_reconciled");
        assertThat(removed.get("signed").asBoolean()).isFalse();

        assertThat(as("PUT", path + "nonsense", Map.of("signed", true)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a closed period's checklist can be read but not changed")
    void closedPeriod() {
        String closed = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, "
                + "\"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'closed', now())")
                .params(closed, demoOrg, "Checklist 2008-05", "2008-05-01", "2008-05-31").update();
        assertThat(ok(200, "GET", "/fiscal-periods/" + closed + "/close-checklist", null).get("status").asString())
                .isEqualTo("closed");
        assertThat(as("PUT", "/fiscal-periods/" + closed + "/close-checklist/bank_reconciled", Map.of("signed", true))
                .status()).isEqualTo(400);
    }

    String exception(String org, String kind, String createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason, status,"
                + " \"createdAt\") VALUES (?, ?, 'test', 0, ?, 'test', 'open', ?::timestamp)")
                .params(id, org, kind, createdAt).update();
        return id;
    }

    @Test
    @DisplayName("a GL tie-out variance is its own advisory item and does not block the close; sale-line exceptions do")
    void tieOutVarianceIsAdvisory() {
        // The other organisation of this suite: nothing else (shifts, queued jobs, pending stock documents) blocks it.
        String period = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'Tie-out 2008-06', '2008-06-01', '2008-06-30 23:59:59', 'open', now())")
                .params(period, otherOrg).update();
        String tieOut = exception(otherOrg, InventoryTieOutMonitor.KIND, "2008-06-03 02:40:00");
        String path = "/fiscal-periods/" + period + "/close-checklist";
        JsonNode checklist = call("GET", path, null, otherToken).body();
        JsonNode variance = check(checklist, "inventory_gl_tieout");
        assertThat(variance.get("count").asInt()).isEqualTo(1);
        assertThat(variance.get("ok").asBoolean()).isFalse();
        assertThat(variance.get("blocking").asBoolean()).isFalse();
        assertThat(variance.get("label").asString()).contains("ties to the GL");
        assertThat(check(checklist, "inventory_exceptions").get("count").asInt()).isZero();
        assertThat(checklist.get("readyToClose").asBoolean()).isTrue();

        // The close passes the inventory-exception gate (it stops later: nothing to close in an empty period).
        Res close = call("POST", "/fiscal-periods/" + period + "/close", null, Map.of("Authorization", "Bearer " + otherToken,
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(close.status()).isEqualTo(400);
        assertThat(close.body().get("message").asString()).startsWith("Nothing to close for 'Tie-out 2008-06'");

        // A sale-line exception still blocks, with the sale-line message; the tie-out item is unchanged.
        exception(otherOrg, "missing_recipe", "2008-06-04 10:00:00");
        JsonNode blocked = call("GET", path, null, otherToken).body();
        assertThat(check(blocked, "inventory_exceptions").get("count").asInt()).isEqualTo(1);
        assertThat(check(blocked, "inventory_gl_tieout").get("count").asInt()).isEqualTo(1);
        assertThat(blocked.get("readyToClose").asBoolean()).isFalse();
        Res refused = call("POST", "/fiscal-periods/" + period + "/close", null, Map.of("Authorization", "Bearer " + otherToken,
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(refused.body().get("message").asString()).isEqualTo("Cannot close 'Tie-out 2008-06': 1 open inventory"
                + " exception(s) in this period — sale lines whose stock/COGS was not posted. Resolve them in the Posting"
                + " Monitor before closing.");

        // Resolving the variance clears the item.
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE id = ?").param(tieOut).update();
        assertThat(check(call("GET", path, null, otherToken).body(), "inventory_gl_tieout").get("ok").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("inventory integrity (R0): an open value finding blocks the close; a quantity finding does not")
    void integrityValueFindingBlocks() {
        String period = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, 'Integrity 2008-07', '2008-07-01', '2008-07-31 23:59:59', 'open', now())")
                .params(period, otherOrg).update();
        Map<String, String> auth = Map.of("Authorization", "Bearer " + otherToken);
        String path = "/fiscal-periods/" + period + "/close-checklist";
        String quantity = exception(otherOrg, "integrity_ledger_vs_quant", "2008-07-03 03:10:00");
        try {
            JsonNode checklist = call("GET", path, null, otherToken).body();
            assertThat(check(checklist, "inventory_exceptions").get("count").asInt()).isZero();
            assertThat(check(checklist, "inventory_integrity_value").get("ok").asBoolean()).isTrue();
            assertThat(check(checklist, "inventory_integrity_value").get("blocking").asBoolean()).isTrue();
            Res passes = call("POST", "/fiscal-periods/" + period + "/close", null, withKey(auth));
            assertThat(passes.body().get("message").asString()).startsWith("Nothing to close for 'Integrity 2008-07'");

            String value = exception(otherOrg, "integrity_valuation_vs_gl", "2008-07-03 03:10:00");
            JsonNode blocked = call("GET", path, null, otherToken).body();
            assertThat(check(blocked, "inventory_integrity_value").get("count").asInt()).isEqualTo(1);
            assertThat(check(blocked, "inventory_exceptions").get("count").asInt()).isZero();
            assertThat(blocked.get("readyToClose").asBoolean()).isFalse();
            Res refused = call("POST", "/fiscal-periods/" + period + "/close", null, withKey(auth));
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).isEqualTo("Cannot close 'Integrity 2008-07': 1 open"
                    + " inventory integrity finding(s) on stock value (valuation vs stock ledger or GL). Investigate"
                    + " and resolve them in the Posting Monitor before closing.");
            jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE id = ?").param(value).update();
            assertThat(check(call("GET", path, null, otherToken).body(), "inventory_integrity_value").get("ok")
                    .asBoolean()).isTrue();
        } finally {
            jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE \"organizationId\" = ? AND kind LIKE"
                    + " 'integrity%'").param(otherOrg).update();
        }
        assertThat(quantity).isNotNull();
    }

    static Map<String, String> withKey(Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    @Test
    @DisplayName("tenant isolation and authorization")
    void isolation() {
        assertThat(call("GET", "/fiscal-periods/" + periodId + "/close-checklist", null,
                otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/fiscal-periods/" + periodId + "/close-checklist/bank_reconciled", Map.of("signed",
                true),
                otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(PeriodCloseController.class)).isEqualTo(5);
    }
}
