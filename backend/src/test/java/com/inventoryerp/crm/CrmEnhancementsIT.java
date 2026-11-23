package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmCaseService;
import com.inventoryerp.crm.application.CrmPlannerService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * CRM enhancements T1 (post-parity): case response-time alerts, the overdue-activity digest, quotation emails on the
 * timeline and the advisory duplicate check.
 */
class CrmEnhancementsIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String service;
    String adminId;

    @Autowired
    CrmCaseService cases;

    @Autowired
    CrmPlannerService planner;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, phone, \"isCustomer\","
                + " \"isCompany\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, true, now())").params(customer,
                        demoOrg, "EN-" + sfx, "Enhance Customer " + sfx, "buyer-" + sfx.toLowerCase() + "@example.test",
                        "+256 7" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                                100_000_000L))).update();
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "EN-" + sfx, "Enhance service " + sfx).update();
    }

    long notifications(String userId, String kind, String key, String value) {
        return jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND"
                + " payload->>'kind' = ? AND payload->>'" + key + "' = ?").params(demoOrg, userId, kind, value)
                .query(Long.class).single();
    }

    int runSla() {
        return TenantContext.run(TenantContext.TenantStore.organization(demoOrg), cases::sendSlaAlerts);
    }

    @Test
    @DisplayName("a case is warned once near its due time, flagged once when overdue, and a new due time re-arms both")
    void caseSlaAlerts() {
        String id = ok(201, "POST", "/crm/cases", Map.of("subject", "Cracked slab " + sfx, "partnerId", customer,
                "priority", "normal", "assigneeId", adminId)).get("id").asString();
        // 80 % of the response time used: due in 2 h, raised 8 h ago.
        jdbc.sql("UPDATE \"CrmCase\" SET \"createdAt\" = (now() AT TIME ZONE 'UTC') - interval '8 hours', \"dueAt\" ="
                + " (now() AT TIME ZONE 'UTC') + interval '2 hours' WHERE id = ?").param(id).update();
        runSla();
        assertThat(notifications(adminId, "crm.case.sla_warning", "caseId", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"slaWarnedAt\" IS NOT NULL FROM \"CrmCase\" WHERE id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        runSla();
        assertThat(notifications(adminId, "crm.case.sla_warning", "caseId", id)).as("warned once").isEqualTo(1);

        jdbc.sql("UPDATE \"CrmCase\" SET \"dueAt\" = (now() AT TIME ZONE 'UTC') - interval '1 minute' WHERE id = ?")
                .param(id).update();
        runSla();
        runSla();
        assertThat(notifications(adminId, "crm.case.sla_breached", "caseId", id)).as("flagged once").isEqualTo(1);
        JsonNode c = ok(200, "GET", "/crm/cases/" + id, null);
        assertThat(c.get("slaBreachedAt").isNull()).isFalse();
        List<String> kinds = new ArrayList<>();
        c.get("events").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("sla");

        // A new deadline is watched again.
        String due = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        JsonNode moved = ok(200, "PATCH", "/crm/cases/" + id, Map.of("dueAt", due));
        assertThat(moved.get("slaWarnedAt").isNull()).isTrue();
        assertThat(moved.get("slaBreachedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("a resolved case raises no alert even when past its due time")
    void closedCaseIsQuiet() {
        String id = ok(201, "POST", "/crm/cases", Map.of("subject", "Billing query " + sfx, "partnerId", customer,
                "assigneeId", adminId)).get("id").asString();
        ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "resolved", "note", "Explained"));
        jdbc.sql("UPDATE \"CrmCase\" SET \"dueAt\" = (now() AT TIME ZONE 'UTC') - interval '1 hour' WHERE id = ?")
                .param(id).update();
        runSla();
        assertThat(notifications(adminId, "crm.case.sla_breached", "caseId", id)).isZero();
    }

    @Test
    @DisplayName("the daily digest tells each person how many activities are overdue")
    void overdueDigest() {
        String twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        ok(201, "POST", "/crm/activities", Map.of("type", "call", "title", "Chase deposit " + sfx, "dueAt",
                twoDaysAgo));
        // the digest goes out once per person per day (CrmDigestLog); forget today's so this test can rerun
        jdbc.sql("DELETE FROM \"CrmDigestLog\" WHERE \"userId\" = ?").param(adminId).update();
        long before = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'kind' ="
                + " 'crm.activity.overdue_digest'").param(adminId).query(Long.class).single();
        int sent = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), planner::sendOverdueDigest);
        assertThat(sent).isPositive();
        Map<String, Object> last = jdbc.sql("SELECT title, payload->>'link' AS link, (payload->>'count')::int AS n"
                + " FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'kind' = 'crm.activity.overdue_digest'"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").param(adminId).query().singleRow();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'kind' ="
                + " 'crm.activity.overdue_digest'").param(adminId).query(Long.class).single()).isEqualTo(before + 1);
        assertThat(((Number) last.get("n")).intValue()).isPositive();
        assertThat(last.get("title").toString()).contains("overdue");
        assertThat(last.get("link")).isEqualTo("/crm/activities?state=overdue");
    }

    @Test
    @DisplayName("emailing a quotation leaves a done email activity on the opportunity and the customer")
    void quotationEmailLogged() {
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Lobby floor " + sfx, "partnerId", customer,
                "amount", 500)).get("id").asString();
        String q = ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "dealId", deal, "lines", List.of(
                Map.of("productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        JsonNode submitted = ok(200, "POST", "/crm/quotations/" + q + "/submit", null);
        if ("pending_approval".equals(submitted.get("status").asString())) {
            ok(200, "POST", "/crm/quotations/" + q + "/approve", null);
        }
        String number = jdbc.sql("SELECT number FROM \"SalesQuotation\" WHERE id = ?").param(q).query(String.class)
                .single();
        ok(200, "POST", "/crm/quotations/" + q + "/email", Map.of("to", "lobby-" + sfx.toLowerCase()
                + "@example.test"));

        Map<String, Object> a = jdbc.sql("SELECT type::text AS type, status::text AS status, \"partnerId\", body FROM"
                + " \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(deal, "Quotation " + number + " emailed")
                .query().singleRow();
        assertThat(a.get("type")).isEqualTo("email");
        assertThat(a.get("status")).isEqualTo("done");
        assertThat(a.get("partnerId")).isEqualTo(customer);
        assertThat(a.get("body").toString()).contains("lobby-" + sfx.toLowerCase() + "@example.test");
    }

    @Test
    @DisplayName("duplicates match email (any case), phone (last digits) and company, and skip the record itself")
    void duplicateCheck() {
        String email = "buyer-" + sfx.toLowerCase() + "@example.test";
        JsonNode byEmail = ok(200, "GET", "/crm/leads/duplicates?email=" + email.toUpperCase(), null);
        assertThat(ids(byEmail.get("partners"))).contains(customer);
        assertThat(byEmail.get("partners").get(0).get("matchedOn").toString()).contains("email");

        String phone = jdbc.sql("SELECT phone FROM \"Partner\" WHERE id = ?").param(customer).query(String.class)
                .single();
        String local = "0" + phone.replaceAll("\\D", "").substring(3); // +256 772 … → 0772 …
        assertThat(ids(ok(200, "GET", "/crm/leads/duplicates?phone=" + local, null).get("partners"))).contains(
                customer);

        String company = "Dupe Works " + sfx;
        String lead = ok(201, "POST", "/crm/leads", Map.of("name", "Terrace " + sfx, "companyName", company))
                .get("id").asString();
        JsonNode byCompany = ok(200, "GET", "/crm/leads/duplicates?companyName=" + ("  " + company.toLowerCase())
                .replace(" ", "%20"), null);
        assertThat(ids(byCompany.get("deals"))).contains(lead);
        assertThat(ids(ok(200, "GET", "/crm/leads/duplicates?excludeId=" + lead + "&companyName=" + company.replace(
                " ", "%20"), null).get("deals"))).doesNotContain(lead);

        JsonNode none = ok(200, "GET", "/crm/leads/duplicates?phone=12", null);
        assertThat(none.get("deals").size() + none.get("partners").size()).isZero();
        assertThat(call("GET", "/crm/leads/duplicates?email=" + email, null, otherToken).body().get("partners")
                .size()).as("other tenant").isZero();
    }

    static List<String> ids(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("id").isNull() ? null : n.get("id").asString()));
        return out;
    }
}
