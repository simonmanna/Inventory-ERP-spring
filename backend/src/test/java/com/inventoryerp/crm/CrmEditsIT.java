package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CRM edits the other suites never make: stages, teams, lookups, settings, cases, planner items and price rules. */
class CrmEditsIT extends ApiTestSupport {

    String sfx;
    String customer;
    String adminId;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
        customer = ok(201, "POST", "/partners", Map.of("code", "CRE-" + sfx, "name", "Edit customer " + sfx,
                "isCustomer", true)).get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    /** CRM actions answer 200 or 201 (Nest has no {@code @HttpCode} on some of them). */
    JsonNode post(String path, Object body) {
        Res r = as("POST", path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    @Test
    @DisplayName("configuration: stage, team, tag / source / lost reason and settings")
    void configuration() {
        String stage = ok(201, "POST", "/crm/config/stages", Map.of("name", "Trial " + sfx, "probability", 30))
                .get("id").asString();
        JsonNode s = ok(200, "PATCH", "/crm/config/stages/" + stage, Map.of("name", "Pilot " + sfx,
                "probability", 40));
        assertThat(s.get("name").asString()).isEqualTo("Pilot " + sfx);
        ok(200, "DELETE", "/crm/config/stages/" + stage, null);

        String team = ok(201, "POST", "/crm/config/teams", Map.of("name", "North " + sfx, "monthlyTarget", 1000,
                "leaderId", adminId, "memberIds", List.of(adminId))).get("id").asString();
        JsonNode t = ok(200, "PATCH", "/crm/config/teams/" + team, Map.of("name", "North-East " + sfx,
                "monthlyTarget", 2500));
        assertThat(t.get("name").asString()).isEqualTo("North-East " + sfx);
        assertThat(as("PATCH", "/crm/config/teams/" + team, Map.of("monthlyTarget", -1)).status()).isEqualTo(400);
        ok(200, "DELETE", "/crm/config/teams/" + team, null);

        for (String kind : List.of("tags", "sources", "lost-reasons")) {
            String id = ok(201, "POST", "/crm/config/" + kind, Map.of("name", kind + " " + sfx)).get("id").asString();
            assertThat(ok(200, "PATCH", "/crm/config/" + kind + "/" + id, Map.of("name", kind + " 2 " + sfx,
                    "color", "teal")).get("name").asString()).isEqualTo(kind + " 2 " + sfx);
            ok(200, "DELETE", "/crm/config/" + kind + "/" + id, null);
        }
        assertThat(ok(200, "GET", "/crm/config/lookups", null).toString()).doesNotContain("tags 2 " + sfx);

        JsonNode before = ok(200, "GET", "/crm/config/settings", null);
        JsonNode after = ok(200, "PUT", "/crm/config/settings", Map.of("quoteMaxDiscountPercent", 15,
                "quoteApprovalAmount", 500000));
        assertThat(after.toString()).contains("15");
        ok(200, "PUT", "/crm/config/settings", Map.of("quoteMaxDiscountPercent",
                before.path("quoteMaxDiscountPercent").asDouble(100)));
    }

    @Test
    @DisplayName("cases: edit, assign and close")
    void cases() {
        String id = ok(201, "POST", "/crm/cases", Map.of("subject", "Broken cup " + sfx, "partnerId", customer,
                "priority", "low")).get("id").asString();
        JsonNode edited = ok(200, "PATCH", "/crm/cases/" + id, Map.of("subject", "Broken cups " + sfx,
                "priority", "high", "dueAt", Instant.now().plusSeconds(86_400).toString()));
        assertThat(edited.get("priority").asString()).isEqualTo("high");
        JsonNode assigned = post("/crm/cases/" + id + "/assign", Map.of("assigneeId", adminId));
        assertThat(assigned.toString()).contains(adminId);
        post("/crm/cases/" + id + "/notes", Map.of("body", "Replacement sent"));
        assertThat(ok(200, "GET", "/crm/cases/" + id, null).toString()).contains("Replacement sent");
    }

    @Test
    @DisplayName("planner: reschedule, complete and cancel")
    void planner() {
        String due = Instant.now().plusSeconds(3600).toString();
        String first = ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", "Call " + sfx, "partnerId",
                customer, "dueAt", due)).get("id").asString();
        JsonNode moved = ok(200, "PATCH", "/crm/planner/" + first, Map.of("title", "Call again " + sfx,
                "dueAt", Instant.now().plusSeconds(7200).toString(), "duration", 15, "assignedToId", adminId));
        assertThat(moved.get("title").asString()).isEqualTo("Call again " + sfx);
        post("/crm/planner/" + first + "/done", Map.of("outcome", "reached"));
        String second = ok(201, "POST", "/crm/planner", Map.of("type", "task", "title", "Send menu " + sfx,
                "partnerId", customer, "dueAt", due)).get("id").asString();
        post("/crm/planner/" + second + "/cancel", null);
        ok(200, "GET", "/crm/planner?partnerId=" + customer, null);
        assertThat(jdbc.sql("SELECT status::text FROM \"Activity\" WHERE id = ?").param(first).query(String.class)
                .single()).isEqualTo("done");
        assertThat(jdbc.sql("SELECT status::text FROM \"Activity\" WHERE id = ?").param(second).query(String.class)
                .single()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("price rules: edit and delete, then resolve falls back to the list price")
    void priceRules() {
        String product = ok(201, "POST", "/products", Map.of("code", "CRP-" + sfx, "name", "Rule item " + sfx,
                "productType", "service", "salesPrice", 200)).get("id").asString();
        String rule = ok(201, "POST", "/crm/pricing/rules", Map.of("name", "Promo " + sfx, "kind",
                "discount_percent", "value", 10, "productId", product)).get("id").asString();
        ok(200, "PATCH", "/crm/pricing/rules/" + rule, Map.of("value", 20, "name", "Promo 20 " + sfx));
        JsonNode priced = ok(200, "POST", "/crm/pricing/resolve", Map.of("partnerId", customer, "lines",
                List.of(Map.of("productId", product, "quantity", 1))));
        assertThat(priced.get("lines").get(0).get("discountPercent").asInt()).isEqualTo(20);
        ok(200, "DELETE", "/crm/pricing/rules/" + rule, null);
        JsonNode list = ok(200, "POST", "/crm/pricing/resolve", Map.of("lines", List.of(Map.of("productId",
                product))));
        assertThat(list.get("lines").get(0).get("source").asString()).isEqualTo("list");
    }
}
