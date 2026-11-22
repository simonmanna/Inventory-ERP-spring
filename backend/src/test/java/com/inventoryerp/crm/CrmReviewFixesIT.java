package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesQuotationService;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Regression tests for the CRM review fixes (2026-11-22): stale approval decisions, expired-quote conversion,
 * owner validation on create, price-rule PATCH checks against the stored rule, shared lookups for every CRM read
 * permission, the quotation PDF under {@code /crm}, legacy {@code /crm/deals} stage changes and visibility going
 * through the pipeline rules, and lead-scoped record routes for lead-only users.
 */
class CrmReviewFixesIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String service;

    @Autowired
    SalesQuotationService quotations;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())")
                .params(customer, demoOrg, "RF-" + sfx, "Review Fix Customer " + sfx).update();
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "RF-" + sfx, "Review service " + sfx).update();
    }

    JsonNode quote(int quantity, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("partnerId", customer, "lines",
                List.of(Map.of("productId", service, "quantity", quantity, "unitPrice", 100))));
        body.putAll(extra);
        return ok(201, "POST", "/crm/quotations", body);
    }

    String status(String quoteId) {
        return jdbc.sql("SELECT status FROM \"SalesQuotation\" WHERE id = ?").param(quoteId).query(String.class).single();
    }

    String requestOf(String quoteId) {
        return jdbc.sql("SELECT \"approvalRequestId\" FROM \"SalesQuotation\" WHERE id = ?").param(quoteId)
                .query(String.class).optional().orElse(null);
    }

    void decide(String quoteId, String requestId, boolean approved) {
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> quotations.applyDecision(quoteId, requestId, approved, null));
    }

    @Test
    @DisplayName("a late engine decision on a superseded request never approves the revised quote")
    void staleApprovalIgnored() {
        String wf = ok(201, "POST", "/approval-workflows", Map.of("name", "Quote review " + sfx, "entityType",
                SalesQuotationService.APPROVAL_ENTITY, "steps", List.of(Map.of("stepOrder", 1, "name", "Manager",
                        "approverPermissions", List.of("approvals:decide"), "requiredCount", 1)))).get("id").asString();
        ok(200, "PUT", "/crm/config/settings", Map.of("quoteApprovalAmount", 100));
        try {
            String id = quote(5, Map.of()).get("id").asString();
            assertThat(ok(200, "POST", "/crm/quotations/" + id + "/submit", null).get("status").asString())
                    .isEqualTo("pending_approval");
            String first = requestOf(id);
            assertThat(first).isNotNull();

            // Revise: the old request is withdrawn; resubmitting raises a new one.
            ok(200, "POST", "/crm/quotations/" + id + "/revise", null);
            assertThat(jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ?").param(first)
                    .query(String.class).single()).isEqualTo("cancelled");
            ok(200, "POST", "/crm/quotations/" + id + "/submit", null);
            String second = requestOf(id);
            assertThat(second).isNotNull().isNotEqualTo(first);

            // An "approved" that was already queued for the first request is ignored.
            decide(id, first, true);
            assertThat(status(id)).isEqualTo("pending_approval");
            // The current request decides.
            decide(id, second, true);
            assertThat(status(id)).isEqualTo("approved");
        } finally {
            ok(200, "PUT", "/crm/config/settings", Map.of("quoteApprovalAmount", 0));
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("an approved quote past validUntil cannot be converted")
    void expiredQuoteNotConverted() {
        LocalDate past = LocalDate.now(BusinessZone.get()).minusDays(2);
        String id = quote(1, Map.of("issueDate", past.minusDays(10).toString(), "validUntil", past.toString()))
                .get("id").asString();
        JsonNode submitted = ok(200, "POST", "/crm/quotations/" + id + "/submit", null);
        if ("pending_approval".equals(submitted.get("status").asString())) {
            ok(200, "POST", "/crm/quotations/" + id + "/approve", Map.of());
        }
        Res r = as("POST", "/crm/quotations/" + id + "/convert", Map.of("target", "proforma"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Quotation has expired; revise it first");
        assertThat(status(id)).isNotEqualTo("converted");
    }

    @Test
    @DisplayName("creating an opportunity or lead with an owner outside the organization is refused")
    void ownerValidatedOnCreate() {
        String stranger = UUID.randomUUID().toString();
        Res opp = as("POST", "/crm/opportunities", Map.of("name", "Ghost owner " + sfx, "partnerId", customer,
                "ownerId", stranger));
        assertThat(opp.status()).isEqualTo(404);
        assertThat(opp.body().get("message").asString()).isEqualTo("User not found");
        assertThat(as("POST", "/crm/leads", Map.of("name", "Ghost lead " + sfx, "ownerId", stranger)).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("price rule PATCH is checked against the stored rule: target pair, discount cap, date order")
    void priceRulePatchUsesStoredValues() {
        String category = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PartnerCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(category, demoOrg, "Review cat " + sfx).update();
        String rule = ok(201, "POST", "/crm/pricing/rules", Map.of("name", "Fixed " + sfx, "kind", "fixed_price",
                "value", 150, "partnerId", customer, "productId", service, "validFrom", "2026-01-10")).get("id")
                .asString();

        Res both = as("PATCH", "/crm/pricing/rules/" + rule, Map.of("partnerCategoryId", category));
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.body().get("message").asString())
                .isEqualTo("Choose a customer or a customer category, not both");

        Res flip = as("PATCH", "/crm/pricing/rules/" + rule, Map.of("kind", "discount_percent"));
        assertThat(flip.status()).isEqualTo(400);
        assertThat(flip.body().get("message").asString()).isEqualTo("A discount cannot exceed 100%");

        Res dates = as("PATCH", "/crm/pricing/rules/" + rule, Map.of("validTo", "2026-01-01"));
        assertThat(dates.status()).isEqualTo(400);
        assertThat(dates.body().get("message").asString()).isEqualTo("validTo must be on or after validFrom");

        // Switching target explicitly (clear one, set the other) still works.
        Map<String, Object> sw = new java.util.HashMap<>();
        sw.put("partnerId", null);
        sw.put("partnerCategoryId", category);
        JsonNode switched = ok(200, "PATCH", "/crm/pricing/rules/" + rule, sw);
        assertThat(switched.get("partnerCategoryId").asString()).isEqualTo(category);
        ok(200, "DELETE", "/crm/pricing/rules/" + rule, null);
    }

    @Test
    @DisplayName("lookups load for any CRM read permission (not only crm:deal:read); none at all is still 403")
    void lookupsForAnyCrmReader() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Case desk " + sfx,
                        new String[] {"crm:case:read"}).update();
        String email = "cases-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Cases@12345", role);
        String token = login("DEMO", email, "Cases@12345");
        Res r = call("GET", "/crm/config/lookups", null, token);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().has("stages")).isTrue();
        assertThat(call("GET", "/crm/config/lookups", null, weakToken()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("quotation PDF is served under /crm/quotations as well")
    void crmQuotationPdf() {
        String id = quote(1, Map.of()).get("id").asString();
        HttpResponse<byte[]> pdf = downloadBytes("/crm/quotations/" + id + "/pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4)).isEqualTo("%PDF");
    }

    /** A user holding only {@code perms}; returns {id, token}. */
    String[] userWith(String label, String... perms) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, label + " " + sfx, perms).update();
        String email = label.toLowerCase().replace(' ', '-') + "-" + sfx.toLowerCase() + "@demo.test";
        String id = createUser(demoOrg, email, "Crm@123456", role);
        return new String[] {id, login("DEMO", email, "Crm@123456")};
    }

    String dealCol(String id, String col) {
        return jdbc.sql("SELECT " + col + "::text FROM \"Deal\" WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    @DisplayName("legacy PATCH /crm/deals/:id/stage runs the pipeline rules (status, history, lead and won guards)")
    void legacyStageChangeUsesPipeline() {
        String id = ok(201, "POST", "/crm/deals", Map.of("name", "Legacy " + sfx, "partnerId", customer, "amount", 500))
                .get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"CrmStageHistory\" WHERE \"dealId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1L);

        JsonNode won = ok(200, "PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "won"));
        assertThat(won.get("stage").asString()).isEqualTo("won");
        assertThat(dealCol(id, "status")).isEqualTo("won");
        assertThat(dealCol(id, "\"wonAt\"")).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"CrmStageHistory\" WHERE \"dealId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(2L);

        Res lost = as("PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "lost"));
        assertThat(lost.status()).isEqualTo(400);
        assertThat(lost.body().get("message").asString())
                .isEqualTo("A won opportunity cannot be marked lost; reopen it first");

        // Back to an open stage reopens the deal.
        assertThat(ok(200, "PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "proposal")).get("stage")
                .asString()).isEqualTo("proposal");
        assertThat(dealCol(id, "status")).isEqualTo("open");

        assertThat(as("PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "bogus")).status()).isEqualTo(400);
        assertThat(as("PATCH", "/crm/deals/" + id + "/stage", Map.of()).status()).isEqualTo(400);

        String lead = ok(201, "POST", "/crm/leads", Map.of("name", "Legacy lead " + sfx)).get("id").asString();
        Res leadWon = as("PATCH", "/crm/deals/" + lead + "/stage", Map.of("stage", "won"));
        assertThat(leadWon.status()).isEqualTo(400);
        assertThat(leadWon.body().get("message").asString())
                .isEqualTo("Convert the lead to an opportunity before marking it won");
    }

    @Test
    @DisplayName("legacy /crm/deals honours crm:deal:view_all like the pipeline")
    void legacyDealsVisibility() {
        String[] owner = userWith("Owner rep", "crm:deal:read", "crm:deal:write");
        String[] rep = userWith("Other rep", "crm:deal:read", "crm:deal:write");
        String id = ok(201, "POST", "/crm/deals", Map.of("name", "Private " + sfx, "partnerId", customer, "ownerId",
                owner[0])).get("id").asString();

        assertThat(call("GET", "/crm/deals/" + id, null, owner[1]).status()).isEqualTo(200);
        assertThat(call("GET", "/crm/deals/" + id, null, rep[1]).status()).isEqualTo(404);
        assertThat(call("PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "won"), rep[1]).status())
                .isEqualTo(404);
        JsonNode list = call("GET", "/crm/deals?partnerId=" + customer + "&pageSize=100", null, rep[1]).body();
        list.get("data").forEach(d -> assertThat(d.get("id").asString()).isNotEqualTo(id));
        // The admin (view_all) still sees it.
        assertThat(as("GET", "/crm/deals/" + id, null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a lead-only user opens and edits leads through /crm/leads/:id, never opportunities")
    void leadOnlyUser() {
        String[] u = userWith("Lead desk", "crm:lead:read", "crm:lead:write");
        String lead = call("POST", "/crm/leads", Map.of("name", "Desk lead " + sfx), u[1]).body().get("id").asString();
        String opp = ok(201, "POST", "/crm/opportunities", Map.of("name", "Desk opp " + sfx, "partnerId", customer))
                .get("id").asString();

        assertThat(call("GET", "/crm/leads/" + lead, null, u[1]).status()).isEqualTo(200);
        assertThat(call("GET", "/crm/leads/" + lead + "/timeline", null, u[1]).status()).isEqualTo(200);
        Res edit = call("PATCH", "/crm/leads/" + lead, Map.of("notes", "called back"), u[1]);
        assertThat(edit.status()).isEqualTo(200);
        assertThat(edit.body().get("notes").asString()).isEqualTo("called back");
        assertThat(call("POST", "/crm/leads/" + lead + "/lost", Map.of(), u[1]).status()).isEqualTo(200);
        assertThat(call("POST", "/crm/leads/" + lead + "/reopen", null, u[1]).status()).isEqualTo(200);

        // An opportunity id is not a lead, and the opportunity routes stay closed.
        Res asLead = call("GET", "/crm/leads/" + opp, null, u[1]);
        assertThat(asLead.status()).isEqualTo(404);
        assertThat(asLead.body().get("message").asString()).isEqualTo("Lead not found");
        assertThat(call("GET", "/crm/opportunities/" + lead, null, u[1]).status()).isEqualTo(403);

        assertThat(call("DELETE", "/crm/leads/" + lead, null, u[1]).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the last active stage cannot be deactivated (same floor as delete)")
    void lastActiveStageStaysActive() {
        // otherOrg is created fresh for this class, so its default pipeline is ours to empty.
        JsonNode stages = call("GET", "/crm/config/stages", null, otherToken).body();
        List<String> active = new java.util.ArrayList<>();
        stages.forEach(st -> {
            if (st.get("isActive").asBoolean()) {
                active.add(st.get("id").asString());
            }
        });
        assertThat(active).hasSizeGreaterThan(1);
        for (String id : active.subList(1, active.size())) {
            assertThat(call("PATCH", "/crm/config/stages/" + id, Map.of("isActive", false), otherToken).status())
                    .isEqualTo(200);
        }
        Res last = call("PATCH", "/crm/config/stages/" + active.get(0), Map.of("isActive", false), otherToken);
        assertThat(last.status()).isEqualTo(400);
        assertThat(last.body().get("message").asString()).isEqualTo("The pipeline needs at least one stage");
        assertThat(call("DELETE", "/crm/config/stages/" + active.get(0), null, otherToken).status()).isEqualTo(400);
    }
}
