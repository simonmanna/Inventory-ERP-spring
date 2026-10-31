package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
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
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, \"updatedAt\")"
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

        JsonNode removed = signOff(ok(200, "PUT", path + "bank_reconciled", Map.of("signed", false)), "bank_reconciled");
        assertThat(removed.get("signed").asBoolean()).isFalse();

        assertThat(as("PUT", path + "nonsense", Map.of("signed", true)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a closed period's checklist can be read but not changed")
    void closedPeriod() {
        String closed = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'closed', now())")
                .params(closed, demoOrg, "Checklist 2008-05", "2008-05-01", "2008-05-31").update();
        assertThat(ok(200, "GET", "/fiscal-periods/" + closed + "/close-checklist", null).get("status").asString())
                .isEqualTo("closed");
        assertThat(as("PUT", "/fiscal-periods/" + closed + "/close-checklist/bank_reconciled", Map.of("signed", true))
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("tenant isolation and authorization")
    void isolation() {
        assertThat(call("GET", "/fiscal-periods/" + periodId + "/close-checklist", null, otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/fiscal-periods/" + periodId + "/close-checklist/bank_reconciled", Map.of("signed", true),
                otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(PeriodCloseController.class)).isEqualTo(5);
    }
}
