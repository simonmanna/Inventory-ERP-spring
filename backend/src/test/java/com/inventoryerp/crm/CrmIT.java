package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmService;
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
 * CRM on Testcontainers Postgres (ports {@code crm.service.spec.ts}): deals lifecycle with stage-change timeline,
 * won → customer, soft delete / restore, activities, the {@code invoice.created} deal link, analytics, authorization
 * matrix and tenant isolation.
 */
class CrmIT extends ApiTestSupport {

    @Autowired
    CrmService crm;

    String partner;

    @BeforeAll
    void fixtures() {
        partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, 'Prospect Ltd', false, false, now())")
                .params(partner, demoOrg, "CRM-" + UUID.randomUUID().toString().substring(0, 6)).update();
    }

    JsonNode deal(String name, int amount) {
        return ok(201, "POST", "/crm/deals", Map.of("name", name, "partnerId", partner, "amount", amount,
                "expectedClose", LocalDate.now().plusDays(10).toString()));
    }

    @Test
    @DisplayName("createDeal: unknown partner 404; defaults lead / USD / owner = caller; audit + event")
    void createDeal() {
        Res missing = as("POST", "/crm/deals", Map.of("name", "X", "partnerId", UUID.randomUUID().toString()));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().get("message").asString()).isEqualTo("Partner not found");
        Res bad = as("POST", "/crm/deals", Map.of("name", "X", "partnerId", partner, "stage", "nope"));
        assertThat(bad.status()).isEqualTo(400);
        JsonNode d = deal("Coffee machines", 1200);
        assertThat(d.get("stage").asString()).isEqualTo("lead");
        assertThat(d.get("currencyCode").asString()).isEqualTo("USD");
        assertThat(d.get("ownerId").isNull()).isFalse();
        String id = d.get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'Deal' AND \"entityId\" = ? AND action ="
                + " 'create'").param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'crm.deal.created' AND"
                + " payload->>'dealId' = ?").param(id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("changeStage logs deal_stage_change; won converts the partner to a customer and blocks delete")
    void stages() {
        String id = deal("Grinder", 300).get("id").asString();
        JsonNode moved = ok(200, "PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "proposal"));
        assertThat(moved.get("stage").asString()).isEqualTo("proposal");
        JsonNode detail = ok(200, "GET", "/crm/deals/" + id, null);
        assertThat(detail.get("activities").get(0).get("type").asString()).isEqualTo("deal_stage_change");
        assertThat(detail.get("activities").get(0).get("title").asString()).isEqualTo("Stage: lead → proposal");
        assertThat(detail.get("amount").asInt()).isEqualTo(300);
        assertThat(detail.get("partner").has("contacts")).isTrue();
        assertThat(detail.get("owner").get("email").asString()).isNotBlank();

        ok(200, "PATCH", "/crm/deals/" + id + "/stage", Map.of("stage", "won"));
        assertThat(jdbc.sql("SELECT \"isCustomer\" FROM \"Partner\" WHERE id = ?").param(partner).query(Boolean.class)
                .single()).isTrue();
        Res del = as("DELETE", "/crm/deals/" + id, null);
        assertThat(del.status()).isEqualTo(400);
        assertThat(del.body().get("message").asString()).isEqualTo("Cannot delete a won deal; archive it instead");
    }

    @Test
    @DisplayName("soft delete, archived list, restore; list is { data, meta } with numeric amount")
    void deleteRestore() {
        String id = deal("Cups " + UUID.randomUUID().toString().substring(0, 4), 50).get("id").asString();
        assertThat(ok(200, "DELETE", "/crm/deals/" + id, null).get("ok").asBoolean()).isTrue();
        assertThat(as("GET", "/crm/deals/" + id, null).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/crm/deals/deleted", null).toString()).contains(id);
        assertThat(ok(200, "PATCH", "/crm/deals/" + id + "/restore", null).get("deletedAt").isNull()).isTrue();
        Res again = as("PATCH", "/crm/deals/" + id + "/restore", null);
        assertThat(again.body().get("message").asString()).isEqualTo("Archived deal not found");
        JsonNode list = ok(200, "GET", "/crm/deals?partnerId=" + partner + "&pageSize=2", null);
        assertThat(list.get("meta").get("pageSize").asInt()).isEqualTo(2);
        assertThat(list.get("data").get(0).get("amount").isNumber()).isTrue();
        assertThat(list.get("data").get(0).get("partner").get("name").asString()).isEqualTo("Prospect Ltd");
        JsonNode patched = ok(200, "PATCH", "/crm/deals/" + id, Map.of("notes", "call back", "amount", 75));
        assertThat(patched.get("notes").asString()).isEqualTo("call back");
    }

    @Test
    @DisplayName("activities: create / update / complete / upcoming / delete / restore")
    void activities() {
        String dealId = deal("Pastries", 90).get("id").asString();
        Res badType = as("POST", "/crm/activities", Map.of("type", "fax", "title", "x"));
        assertThat(badType.status()).isEqualTo(400);
        Res noDeal = as("POST", "/crm/activities", Map.of("type", "call", "title", "x", "dealId",
                UUID.randomUUID().toString()));
        assertThat(noDeal.body().get("message").asString()).isEqualTo("Deal not found");
        JsonNode a = ok(201, "POST", "/crm/activities", Map.of("type", "task", "title", "Send quote", "dealId", dealId,
                "partnerId", partner, "dueAt", LocalDate.now().plusDays(2) + "T10:00:00Z"));
        assertThat(a.get("status").asString()).isEqualTo("todo");
        String id = a.get("id").asString();
        assertThat(ok(200, "GET", "/crm/activities/upcoming?limit=50", null).toString()).contains(id);
        assertThat(ok(200, "PATCH", "/crm/activities/" + id, Map.of("status", "in_progress")).get("status").asString())
                .isEqualTo("in_progress");
        JsonNode done = ok(201, "POST", "/crm/activities/" + id + "/complete", null);
        assertThat(done.get("completed").asBoolean()).isTrue();
        assertThat(done.get("status").asString()).isEqualTo("done");
        assertThat(ok(200, "GET", "/crm/activities?dealId=" + dealId, null).get("meta").get("total").asInt())
                .isEqualTo(1);
        ok(200, "DELETE", "/crm/activities/" + id, null);
        assertThat(as("PATCH", "/crm/activities/" + id, Map.of("title", "y")).body().get("message").asString())
                .isEqualTo("Activity not found");
        assertThat(ok(200, "GET", "/crm/activities/deleted", null).toString()).contains(id);
        assertThat(ok(200, "PATCH", "/crm/activities/" + id + "/restore", null).get("id").asString()).isEqualTo(id);
    }

    @Test
    @DisplayName("invoice.created links the invoice to the partner's open deal, replay-safe")
    void invoiceLink() {
        String p = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, 'Linked', true, now())")
                .params(p, demoOrg, "LNK-" + UUID.randomUUID().toString().substring(0, 6)).update();
        String dealId = ok(201, "POST", "/crm/deals", Map.of("name", "Linked deal", "partnerId", p)).get("id").asString();
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", p, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("description", "Consulting", "quantity", 1, "unitPrice", 10))));
        var payload = mapper.createObjectNode().put("organizationId", demoOrg).put("documentId", inv.get("id")
                .asString());
        crmHandle(payload);
        crmHandle(payload);
        List<String> titles = jdbc.sql("SELECT title FROM \"Activity\" WHERE \"dealId\" = ? AND \"subjectType\" ="
                + " 'Invoice'").param(dealId).query(String.class).list();
        assertThat(titles).containsExactly("Invoice " + inv.get("documentNumber").asString() + " created");
    }

    private void crmHandle(JsonNode payload) {
        try {
            var m = CrmService.class.getDeclaredMethod("handleInvoiceCreated", JsonNode.class);
            m.setAccessible(true);
            m.invoke(crm, payload);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("analytics: pipeline, win-rate, forecast, top customers, partner 360")
    void analytics() {
        deal("Forecast deal", 400);
        JsonNode pipeline = ok(200, "GET", "/crm/analytics/pipeline", null);
        assertThat(pipeline.get("stages")).hasSize(4);
        assertThat(pipeline.get("stages").get(0).get("weight").asDouble()).isEqualTo(0.1);
        assertThat(pipeline.get("openPipelineValue").asDouble()).isGreaterThan(0);
        JsonNode win = ok(200, "GET", "/crm/analytics/win-rate", null);
        assertThat(win.has("rate")).isTrue();
        JsonNode forecast = ok(200, "GET", "/crm/analytics/forecast?months=2", null);
        assertThat(forecast.size()).isGreaterThanOrEqualTo(2);
        assertThat(forecast.get(0).get("month").asString()).matches("\\d{4}-\\d{2}");
        assertThat(ok(200, "GET", "/crm/analytics/top-customers?limit=3", null).get(0).has("dealValue")).isTrue();
        JsonNode p360 = ok(200, "GET", "/crm/analytics/partner/" + partner, null);
        assertThat(p360.get("orderCount").asInt()).isZero();
        assertThat(p360.get("lastOrderAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix (21 parity + 67 sales-suite routes)")
    void isolationAndAuthz() {
        String id = deal("Private", 10).get("id").asString();
        assertThat(call("GET", "/crm/deals/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/crm/deals", null, otherToken).body().get("meta").get("total").asInt()).isZero();
        assertThat(assertAuthorizationMatrix("com.inventoryerp.crm")).isEqualTo(96);
    }
}
