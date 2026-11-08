package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.approvals.ApprovalControllers.ApprovalWorkflowController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S061 configurable approval rules and the approval hub, on the other organization of the suite so rules never
 * reach DEMO data: rule conditions and precedence, stage conditions, stages assigned to users and roles, request
 * priority, the hub inbox / timeline / summary, the builder's simulate and meta routes, validation, authorization and
 * tenancy.
 */
class ApprovalRulesIT extends ApiTestSupport {

    String role;
    User maker;
    User checker;
    User senior;

    record User(String token, String id) {}

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ?").param(otherOrg).query(String.class)
                .single();
        maker = user();
        checker = user();
        senior = user();
    }

    User user() {
        String email = "rules-" + sfx() + "@other.test";
        String id = createUser(otherOrg, email, "Rules@12345", role);
        return new User(login(otherOrgCode, email, "Rules@12345"), id);
    }

    JsonNode okAs(User u, int status, String method, String path, Object body) {
        Res r = call(method, path, body, u.token());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    static Map<String, Object> leaf(String field, String operator, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("operator", operator);
        m.put("value", value);
        return m;
    }

    static Map<String, Object> all(Object... rules) {
        return Map.of("logic", "and", "rules", List.of(rules));
    }

    static Map<String, Object> step(int order, String name) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("stepOrder", order);
        s.put("name", name);
        s.put("approverPermissions", List.of("approvals:decide"));
        s.put("requiredCount", 1);
        return s;
    }

    @SafeVarargs
    final String rule(String type, Map<String, Object> extra, Map<String, Object>... steps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Rule " + sfx());
        body.put("entityType", type);
        body.put("steps", List.of(steps));
        body.putAll(extra);
        return okAs(senior, 201, "POST", "/approval-workflows", body).get("id").asString();
    }

    JsonNode request(User u, String type, Map<String, Object> snapshot) {
        return okAs(u, 201, "POST", "/approvals/request", Map.of("entityType", type, "entityId",
                UUID.randomUUID().toString(), "snapshot", snapshot));
    }

    @Test
    @DisplayName("rules: conditions pick the rule, precedence beats specificity, no match falls to the plain rule")
    void ruleConditions() {
        String type = "rule_" + sfx();
        String plain = rule(type, Map.of(), step(1, "Manager"));
        String large = rule(type, Map.of("conditions", all(leaf("amount", "gt", 1000),
                leaf("category", "in", List.of("capex")))), step(1, "Finance"));
        String dell = rule(type, Map.of("precedence", 10, "conditions", all(leaf("vendor.name", "eq", "Dell"))),
                step(1, "CEO"));

        assertThat(request(maker, type, Map.of("amount", 5000, "category", "CAPEX")).get("workflowId").asString())
                .isEqualTo(large);
        assertThat(request(maker, type, Map.of("amount", 5000, "category", "opex")).get("workflowId").asString())
                .isEqualTo(plain);
        assertThat(request(maker, type, Map.of("amount", 5000, "category", "capex", "vendor", Map.of("name", "dell")))
                .get("workflowId").asString()).isEqualTo(dell);

        JsonNode w = okAs(senior, 200, "GET", "/approval-workflows/" + large, null);
        assertThat(w.get("conditions").get("rules")).hasSize(2);
        assertThat(w.get("priority").asString()).isEqualTo("medium");
        assertThat(w.get("precedence").asInt()).isZero();
    }

    @Test
    @DisplayName("stages: a stage whose condition fails is skipped, both when requesting and when advancing")
    void stageConditions() {
        String type = "stage_" + sfx();
        Map<String, Object> ceo = step(2, "CEO");
        ceo.put("conditions", all(leaf("amount", "gte", 10000)));
        rule(type, Map.of(), step(1, "Manager"), ceo);

        String small = request(maker, type, Map.of("amount", 500)).get("id").asString();
        assertThat(okAs(checker, 201, "POST", "/approvals/" + small + "/decide", Map.of("status", "approved"))
                .get("status").asString()).isEqualTo("approved");

        String big = request(maker, type, Map.of("amount", 50000)).get("id").asString();
        JsonNode after = okAs(checker, 201, "POST", "/approvals/" + big + "/decide", Map.of("status", "approved"));
        assertThat(after.get("status").asString()).isEqualTo("pending");
        assertThat(after.get("currentStep").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("assignees: a user stage takes only its users, a role stage its holders but never the requester")
    void assignees() {
        String type = "assign_" + sfx();
        Map<String, Object> named = step(1, "Finance Director");
        named.put("approverScope", "user");
        named.put("approverUserIds", List.of(checker.id()));
        Map<String, Object> byRole = step(2, "Controllers");
        byRole.put("approverScope", "role");
        byRole.put("approverRoleIds", List.of(role));
        rule(type, Map.of("priority", "high"), named, byRole);

        JsonNode req = request(maker, type, Map.of("amount", 10));
        String id = req.get("id").asString();
        assertThat(req.get("priority").asString()).isEqualTo("high");

        Res wrong = call("POST", "/approvals/" + id + "/decide", Map.of("status", "approved"), senior.token());
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString()).isEqualTo("You are not assigned to decide on this step");
        okAs(checker, 201, "POST", "/approvals/" + id + "/decide", Map.of("status", "approved"));

        Res self = call("POST", "/approvals/" + id + "/decide", Map.of("status", "approved"), maker.token());
        assertThat(self.status()).isEqualTo(400);
        assertThat(okAs(senior, 201, "POST", "/approvals/" + id + "/decide", Map.of("status", "approved"))
                .get("status").asString()).isEqualTo("approved");

        assertThat(request(maker, type, Map.of("amount", 10, "priority", "critical")).get("priority").asString())
                .isEqualTo("critical");
    }

    @Test
    @DisplayName("hub: inbox shows only what the caller can decide; timeline names stages, assignees and decisions")
    void hub() {
        String type = "hub_" + sfx();
        Map<String, Object> named = step(1, "Finance Director");
        named.put("approverScope", "user");
        named.put("approverUserIds", List.of(checker.id()));
        Map<String, Object> skipped = step(3, "Board");
        skipped.put("conditions", all(leaf("amount", "gt", 1_000_000)));
        rule(type, Map.of("priority", "high"), named, step(2, "CFO Review"), skipped);
        String id = request(maker, type, Map.of("amount", 28500, "poNumber", "PO-10284",
                "supplierName", "Dell Technologies", "budgetCode", "CAPEX-IT-2024")).get("id").asString();

        List<String> checkerInbox = ids(okAs(checker, 200, "GET", "/approvals/inbox?entityType=" + type, null));
        assertThat(checkerInbox).containsExactly(id);
        assertThat(ids(okAs(senior, 200, "GET", "/approvals/inbox?entityType=" + type, null))).isEmpty();
        assertThat(ids(okAs(maker, 200, "GET", "/approvals/inbox?scope=requested&entityType=" + type, null)))
                .containsExactly(id);
        assertThat(ids(okAs(checker, 200, "GET", "/approvals/inbox?q=dell&entityType=" + type, null)))
                .containsExactly(id);

        JsonNode card = okAs(checker, 200, "GET", "/approvals/inbox?entityType=" + type, null).get(0);
        assertThat(card.get("reference").asString()).isEqualTo("PO-10284");
        assertThat(card.get("title").asString()).isEqualTo("Dell Technologies");
        assertThat(card.get("priority").asString()).isEqualTo("high");
        assertThat(card.get("stage").get("total").asInt()).isEqualTo(2);
        assertThat(card.get("canDecide").asBoolean()).isTrue();
        assertThat(okAs(checker, 200, "GET", "/approvals/summary", null).get("awaitingMeUrgent").asInt())
                .isPositive();

        okAs(checker, 201, "POST", "/approvals/" + id + "/decide", Map.of("status", "approved", "comment", "ok"));
        JsonNode t = okAs(senior, 200, "GET", "/approvals/" + id + "/timeline", null);
        JsonNode stages = t.get("stages");
        assertThat(stages.get(0).get("state").asString()).isEqualTo("approved");
        assertThat(stages.get(0).get("assignee").get("kind").asString()).isEqualTo("user");
        assertThat(stages.get(0).get("decisions").get(0).get("approver").get("id").asString()).isEqualTo(checker.id());
        assertThat(stages.get(1).get("state").asString()).isEqualTo("current");
        assertThat(stages.get(2).get("state").asString()).isEqualTo("skipped");
        assertThat(t.get("canDecide").asBoolean()).isTrue();
        assertThat(t.get("request").get("requester").get("id").asString()).isEqualTo(maker.id());
        List<String> labels = new ArrayList<>();
        t.get("details").forEach(d -> labels.add(d.get("label").asString()));
        assertThat(labels).contains("Amount", "Supplier Name", "Budget Code");
    }

    @Test
    @DisplayName("builder: simulate shows the rule and stages without writing; meta lists snapshot fields")
    void builder() {
        String type = "sim_" + sfx();
        Map<String, Object> big = step(2, "CFO");
        big.put("conditions", all(leaf("amount", "gte", 5000)));
        String id = rule(type, Map.of(), step(1, "Manager"), big);
        request(maker, type, Map.of("amount", 1, "costCenter", "CC-1"));
        long before = jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"entityType\" = ?").param(type)
                .query(Long.class).single();

        JsonNode sim = okAs(senior, 200, "POST", "/approval-workflows/simulate",
                Map.of("entityType", type, "snapshot", Map.of("amount", 100)));
        assertThat(sim.get("workflow").get("id").asString()).isEqualTo(id);
        assertThat(sim.get("stages").get(0).get("applies").asBoolean()).isTrue();
        assertThat(sim.get("stages").get(1).get("applies").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"entityType\" = ?").param(type)
                .query(Long.class).single()).isEqualTo(before);

        List<String> keys = new ArrayList<>();
        okAs(senior, 200, "GET", "/approval-workflows/meta?entityType=" + type, null).get("fields")
                .forEach(f -> keys.add(f.get("key").asString()));
        assertThat(keys).contains("amount", "departmentId", "costCenter");
    }

    @Test
    @DisplayName("validation: assignee scopes need assignees, conditions must be well formed, priority is checked")
    void validation() {
        String type = "val_" + sfx();
        Map<String, Object> empty = step(1, "Nobody");
        empty.put("approverScope", "user");
        Res r = call("POST", "/approval-workflows", Map.of("name", "X", "entityType", type, "steps", List.of(empty)),
                senior.token());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Step 1 is assigned to users but names none");

        Map<String, Object> tooMany = step(1, "Pair");
        tooMany.put("approverScope", "user");
        tooMany.put("approverUserIds", List.of(checker.id()));
        tooMany.put("requiredCount", 2);
        r = call("POST", "/approval-workflows", Map.of("name", "X", "entityType", type, "steps", List.of(tooMany)),
                senior.token());
        assertThat(r.body().get("message").asString())
                .isEqualTo("Step 1 requires more approvals than it has assigned users");

        r = call("POST", "/approval-workflows", Map.of("name", "X", "entityType", type, "steps",
                List.of(step(1, "A")), "conditions", all(leaf("amount", "bigger", 1))), senior.token());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("conditions.rules.0.operator must be one of");

        String id = rule(type, Map.of(), step(1, "A"));
        assertThat(call("PATCH", "/approval-workflows/" + id, Map.of("priority", "urgent"), senior.token()).status())
                .isEqualTo(400);
        assertThat(okAs(senior, 200, "PATCH", "/approval-workflows/" + id, Map.of("priority", "critical",
                "precedence", 3, "description", "Large spend")).get("precedence").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("tenancy: another organization cannot read a request's timeline; scope=all needs approvals:read")
    void tenancyAndAccess() {
        String type = "ten_" + sfx();
        rule(type, Map.of(), step(1, "A"));
        String id = request(maker, type, Map.of("amount", 1)).get("id").asString();
        assertThat(as("GET", "/approvals/" + id + "/timeline", null).status()).isEqualTo(404);
        for (JsonNode c : ok(200, "GET", "/approvals/inbox?scope=all", null)) {
            assertThat(c.get("id").asString()).isNotEqualTo(id);
        }
        assertThat(call("GET", "/approvals/inbox?scope=all", null, weakToken()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("authorization: every approval-workflows route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ApprovalWorkflowController.class)).isEqualTo(7);
    }

    static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("id").asString()));
        return out;
    }
}
