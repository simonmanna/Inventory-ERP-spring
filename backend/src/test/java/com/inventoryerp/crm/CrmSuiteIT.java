package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
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
 * CRM sales suite (ADR-S010) over HTTP: configurable stages, leads → opportunities, kanban / grouped / list views,
 * won / lost / reopen with stage history, planned activities, customer-specific pricing, quotation approval and
 * conversion to an Order and a proforma invoice, reports, dashboard, customers, visibility and tenant isolation.
 */
class CrmSuiteIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String service;
    String stocked;
    String salesToken;
    String salesUser;

    @BeforeAll
    void suiteFixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"creditLimit\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, 1000, now())")
                .params(customer, demoOrg, "CS-" + sfx, "Suite Customer " + sfx).update();
        service = product("Consulting " + sfx, 100, false);
        stocked = product("Trophy " + sfx, 50, true);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Sales rep " + sfx, new String[] {
                    "crm:deal:read", "crm:deal:write", "crm:lead:read", "crm:lead:write", "crm:activity:read",
                    "crm:activity:write", "crm:quotation:read", "crm:quotation:write", "crm:pricing:read"}).update();
        String email = "rep-" + sfx.toLowerCase() + "@demo.test";
        salesUser = createUser(demoOrg, email, "Sales@12345", role);
        salesToken = login("DEMO", email, "Sales@12345");
    }

    String product(String name, int price, boolean track) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"ProductType\", ?, 0, ?,"
                + " now())").params(id, demoOrg, "P-" + UUID.randomUUID().toString().substring(0, 8), name,
                track ? "stockable" : "service", price, track).update();
        return id;
    }

    JsonNode stageNamed(String name) {
        for (JsonNode s : ok(200, "GET", "/crm/config/stages", null)) {
            if (name.equals(s.get("name").asString())) {
                return s;
            }
        }
        throw new AssertionError("no stage " + name);
    }

    JsonNode opportunity(String name, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "partnerId", customer, "amount", 1000));
        body.putAll(extra);
        return ok(201, "POST", "/crm/opportunities", body);
    }

    @Test
    @DisplayName("default pipeline seeded once; admin adds, reorders and deletes a stage; legacy stage mapping")
    void configurableStages() {
        JsonNode lookups = ok(200, "GET", "/crm/config/lookups", null);
        List<String> names = new ArrayList<>();
        lookups.get("stages").forEach(s -> names.add(s.get("name").asString()));
        assertThat(names).containsSubsequence("New", "Qualified", "Proposition", "Negotiation", "Won");
        assertThat(lookups.get("lostReasons").size()).isPositive();
        JsonNode demo = ok(201, "POST", "/crm/config/stages", Map.of("name", "Demo " + sfx, "probability", 50,
                "legacyStage", "proposal", "color", "pink"));
        assertThat(demo.get("legacyStage").asString()).isEqualTo("proposal");
        assertThat(as("POST", "/crm/config/stages", Map.of("name", "Bad", "probability", 150)).status()).isEqualTo(400);
        assertThat(as("POST", "/crm/config/stages", Map.of("name", "Bad", "color", "neon")).status()).isEqualTo(400);
        List<String> ids = new ArrayList<>();
        ok(200, "GET", "/crm/config/stages", null).forEach(s -> ids.add(s.get("id").asString()));
        ids.remove(demo.get("id").asString());
        ids.addFirst(demo.get("id").asString());
        JsonNode reordered = ok(200, "POST", "/crm/config/stages/reorder", Map.of("ids", ids));
        assertThat(reordered.get(0).get("id").asString()).isEqualTo(demo.get("id").asString());
        // A stage holding an opportunity cannot be deleted.
        JsonNode opp = opportunity("Stage holder " + sfx, Map.of("stageId", demo.get("id").asString()));
        assertThat(as("DELETE", "/crm/config/stages/" + demo.get("id").asString(), null).status()).isEqualTo(400);
        ok(200, "DELETE", "/crm/opportunities/" + opp.get("id").asString(), null);
        ok(200, "DELETE", "/crm/config/stages/" + demo.get("id").asString(), null);
        ids.remove(demo.get("id").asString());
        ok(200, "POST", "/crm/config/stages/reorder", Map.of("ids", ids));
        assertThat(as("POST", "/crm/config/stages", Map.of("name", "X")).status()).isEqualTo(201);
        ok(200, "DELETE", "/crm/config/stages/" + stageNamed("X").get("id").asString(), null);
    }

    @Test
    @DisplayName("lead → opportunity conversion creates the customer; kanban move logs history and legacy stage")
    void leadLifecycle() {
        JsonNode lead = ok(201, "POST", "/crm/leads", Map.of("name", "Trophies for gala " + sfx, "companyName",
                "Gala Org " + sfx, "contactName", "Ann Lee", "email", "ann@gala.test", "phone", "+256700000001"));
        assertThat(lead.get("kind").asString()).isEqualTo("lead");
        assertThat(lead.get("legacyStage").asString()).isEqualTo("lead");
        String id = lead.get("id").asString();
        assertThat(as("POST", "/crm/opportunities/" + id + "/move", Map.of("stageId",
                stageNamed("Qualified").get("id").asString())).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/crm/leads?q=Gala%20Org%20" + sfx, null).get("meta").get("total").asInt())
                .isEqualTo(1);
        JsonNode opp = ok(200, "POST", "/crm/leads/" + id + "/convert", Map.of("createCustomer", true));
        assertThat(opp.get("kind").asString()).isEqualTo("opportunity");
        assertThat(opp.get("partner").get("name").asString()).isEqualTo("Gala Org " + sfx);
        assertThat(opp.get("partner").get("contacts").get(0).get("email").asString()).isEqualTo("ann@gala.test");
        String qualified = stageNamed("Qualified").get("id").asString();
        JsonNode moved = ok(200, "POST", "/crm/opportunities/" + id + "/move", Map.of("stageId", qualified,
                "index", 0));
        assertThat(moved.get("stageId").asString()).isEqualTo(qualified);
        assertThat(moved.get("legacyStage").asString()).isEqualTo("qualified");
        // Legacy route sees the same deal and stage.
        assertThat(ok(200, "GET", "/crm/deals/" + id, null).get("stage").asString()).isEqualTo("qualified");
        JsonNode timeline = ok(200, "GET", "/crm/opportunities/" + id + "/timeline", null);
        List<String> sources = new ArrayList<>();
        timeline.forEach(t -> sources.add(t.get("source").asString()));
        assertThat(sources).contains("stage", "activity", "audit");
    }

    @Test
    @DisplayName("won / lost / reopen; kanban columns, grouped table, list filters; legacy route stays in sync")
    void outcomes() {
        JsonNode a = opportunity("Win me " + sfx, Map.of("priority", 3, "expectedClose",
                LocalDate.now().plusDays(5).toString()));
        JsonNode b = opportunity("Lose me " + sfx, Map.of());
        String reason = ok(200, "GET", "/crm/config/lookups", null).get("lostReasons").get(0).get("id").asString();
        JsonNode won = ok(200, "POST", "/crm/opportunities/" + a.get("id").asString() + "/won", null);
        assertThat(won.get("status").asString()).isEqualTo("won");
        assertThat(won.get("stageName").asString()).isEqualTo("Won");
        assertThat(won.get("probability").asInt()).isEqualTo(100);
        JsonNode lost = ok(200, "POST", "/crm/opportunities/" + b.get("id").asString() + "/lost", Map.of(
                "lostReasonId", reason, "lostNote", "Budget cut"));
        assertThat(lost.get("status").asString()).isEqualTo("lost");
        assertThat(lost.get("legacyStage").asString()).isEqualTo("lost");
        assertThat(as("POST", "/crm/opportunities/" + a.get("id").asString() + "/lost", Map.of()).status())
                .isEqualTo(400);
        JsonNode kanban = ok(200, "GET", "/crm/opportunities/kanban?q=" + sfx, null);
        boolean seenWon = false;
        for (JsonNode col : kanban.get("columns")) {
            for (JsonNode card : col.get("items")) {
                assertThat(card.get("id").asString()).isNotEqualTo(b.get("id").asString());
                seenWon |= card.get("id").asString().equals(a.get("id").asString());
            }
        }
        assertThat(seenWon).isTrue();
        JsonNode grouped = ok(200, "GET", "/crm/opportunities/grouped?groupBy=status&q=" + sfx, null);
        assertThat(grouped.size()).isGreaterThanOrEqualTo(2);
        assertThat(as("GET", "/crm/opportunities/grouped?groupBy=nope", null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/crm/opportunities?status=lost&lostReasonId=" + reason + "&q=" + sfx, null)
                .get("meta").get("total").asInt()).isEqualTo(1);
        JsonNode reopened = ok(200, "POST", "/crm/opportunities/" + b.get("id").asString() + "/reopen", null);
        assertThat(reopened.get("status").asString()).isEqualTo("open");
        assertThat(reopened.get("lostReasonId").isNull()).isTrue();
        // Legacy stage change → suite status.
        ok(200, "PATCH", "/crm/deals/" + b.get("id").asString() + "/stage", Map.of("stage", "won"));
        assertThat(ok(200, "GET", "/crm/opportunities/" + b.get("id").asString(), null).get("status").asString())
                .isEqualTo("won");
        assertThat(as("DELETE", "/crm/opportunities/" + a.get("id").asString(), null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("planned activity: schedule, overdue state on the card, done with follow-up, reminder delivery")
    void plannedActivities() {
        JsonNode opp = opportunity("Follow me " + sfx, Map.of());
        String id = opp.get("id").asString();
        JsonNode act = ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", "Call back", "dealId", id,
                "dueAt", LocalDate.now().minusDays(1).toString(), "remindAt", LocalDate.now().minusDays(2).toString()));
        assertThat(ok(200, "GET", "/crm/opportunities/" + id, null).get("activityState").asString())
                .isEqualTo("overdue");
        assertThat(ok(200, "GET", "/crm/planner?state=overdue", null).get("counts").get("overdue").asInt())
                .isPositive();
        assertThat(as("POST", "/crm/planner", Map.of("type", "fax", "title", "x")).status()).isEqualTo(400);
        JsonNode done = ok(200, "POST", "/crm/planner/" + act.get("id").asString() + "/done", Map.of("outcome",
                "Interested", "followUp", Map.of("type", "meeting", "title", "Demo", "dueAt",
                        LocalDate.now().plusDays(3).toString())));
        assertThat(done.get("status").asString()).isEqualTo("done");
        assertThat(done.get("followUp").get("dealId").asString()).isEqualTo(id);
        assertThat(ok(200, "GET", "/crm/opportunities/" + id, null).get("activityState").asString())
                .isEqualTo("planned");
        // Reminder delivery (job logic, per organization).
        JsonNode r = ok(201, "POST", "/crm/planner", Map.of("type", "task", "title", "Remind " + sfx, "dealId", id,
                "remindAt", java.time.Instant.now().minusSeconds(60).toString()));
        com.inventoryerp.kernel.tenancy.TenantContext.run(
                com.inventoryerp.kernel.tenancy.TenantContext.TenantStore.organization(demoOrg),
                () -> planner.sendDueReminders());
        assertThat(jdbc.sql("SELECT \"reminderSentAt\" IS NOT NULL FROM \"Activity\" WHERE id = ?")
                .param(r.get("id").asString()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title = ?")
                .params(demoOrg, "Reminder: Remind " + sfx).query(Long.class).single()).isEqualTo(1L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.inventoryerp.crm.application.CrmPlannerService planner;

    @Test
    @DisplayName("price rules: customer rule beats category / global, min quantity, fixed price; standing discount")
    void pricing() {
        ok(201, "POST", "/crm/pricing/rules", Map.of("name", "Global 5% " + sfx, "kind", "discount_percent",
                "value", 5, "productId", service));
        ok(201, "POST", "/crm/pricing/rules", Map.of("name", "Customer fixed " + sfx, "kind", "fixed_price", "value",
                80, "partnerId", customer, "productId", service, "minQuantity", 10));
        assertThat(as("POST", "/crm/pricing/rules", Map.of("name", "Bad", "kind", "discount_percent", "value", 120))
                .status()).isEqualTo(400);
        JsonNode r = ok(200, "POST", "/crm/pricing/resolve", Map.of("partnerId", customer, "lines", List.of(
                Map.of("productId", service, "quantity", 1), Map.of("productId", service, "quantity", 10))));
        assertThat(r.get("lines").get(0).get("discountPercent").asInt()).isEqualTo(5);
        assertThat(r.get("lines").get(0).get("source").asString()).isEqualTo("rule");
        assertThat(r.get("lines").get(1).get("unitPrice").asInt()).isEqualTo(80);
        assertThat(r.get("lines").get(1).get("netPrice").asInt()).isEqualTo(80);
        JsonNode list = ok(200, "POST", "/crm/pricing/resolve", Map.of("lines", List.of(Map.of("productId", stocked))));
        assertThat(list.get("lines").get(0).get("source").asString()).isEqualTo("list");
        assertThat(list.get("lines").get(0).get("unitPrice").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("quotation: priced lines, discount + credit approval, approve, send, convert to Order (stock) and proforma")
    void quotations() {
        JsonNode opp = opportunity("Quote me " + sfx, Map.of());
        String dealId = opp.get("id").asString();
        ok(200, "PUT", "/crm/config/settings", Map.of("quoteMaxDiscountPercent", 10, "creditCheck", true,
                "autoWinOnConversion", true));
        // Within policy: auto-approved.
        JsonNode small = ok(201, "POST", "/crm/quotations", Map.of("dealId", dealId, "lines", List.of(
                Map.of("productId", service, "quantity", 2))));
        assertThat(small.get("number").asString()).startsWith("QT-");
        assertThat(small.get("partnerId").asString()).isEqualTo(customer);
        assertThat(small.get("lines").get(0).get("listPrice").asInt()).isEqualTo(100);
        JsonNode approved = ok(200, "POST", "/crm/quotations/" + small.get("id").asString() + "/submit", null);
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        // 30% manual discount and above the 1000 credit limit → approval with both reasons.
        JsonNode big = ok(201, "POST", "/crm/quotations", Map.of("dealId", dealId, "lines", List.of(
                Map.of("productId", service, "quantity", 20, "unitPrice", 100, "discountPercent", 30))));
        assertThat(big.get("lines").get(0).get("priceSource").asString()).isEqualTo("manual");
        String bigId = big.get("id").asString();
        JsonNode pending = ok(200, "POST", "/crm/quotations/" + bigId + "/submit", null);
        assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
        List<String> reasons = new ArrayList<>();
        pending.get("approvalReasons").forEach(x -> reasons.add(x.asString()));
        assertThat(reasons).anyMatch(x -> x.startsWith("Discount")).anyMatch(x -> x.contains("credit limit"));
        assertThat(as("PUT", "/crm/quotations/" + bigId, Map.of("notes", "x")).status()).isEqualTo(400);
        // The sales rep cannot approve; the admin can.
        assertThat(call("POST", "/crm/quotations/" + bigId + "/approve", Map.of(), salesToken).status())
                .isEqualTo(403);
        assertThat(ok(200, "POST", "/crm/quotations/" + bigId + "/approve", Map.of()).get("status").asString())
                .isEqualTo("approved");
        ok(200, "POST", "/crm/quotations/" + bigId + "/send", null);
        // Proforma conversion.
        JsonNode proforma = ok(200, "POST", "/crm/quotations/" + bigId + "/convert", Map.of("target", "proforma"));
        assertThat(proforma.get("status").asString()).isEqualTo("converted");
        assertThat(proforma.get("converted").get("number").asString()).startsWith("PRO-");
        assertThat(jdbc.sql("SELECT \"documentType\"::text FROM \"Document\" WHERE id = ?").param(
                proforma.get("converted").get("id").asString()).query(String.class).single())
                .isEqualTo("proforma_invoice");
        assertThat(as("POST", "/crm/quotations/" + bigId + "/convert", Map.of()).status()).isEqualTo(400);
        // Order conversion: stocked product without stock is blocked unless back-ordered.
        JsonNode stockQuote = ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "lines", List.of(
                Map.of("productId", stocked, "quantity", 3))));
        assertThat(stockQuote.get("lines").get(0).get("stock").get("sufficient").asBoolean()).isFalse();
        String sq = stockQuote.get("id").asString();
        ok(200, "POST", "/crm/quotations/" + sq + "/submit", null);
        Res blocked = as("POST", "/crm/quotations/" + sq + "/convert", Map.of("target", "order"));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().get("shortages").get(0).asString()).startsWith("Trophy");
        JsonNode order = ok(200, "POST", "/crm/quotations/" + sq + "/convert", Map.of("target", "order",
                "allowBackorder", true));
        assertThat(order.get("converted").get("type").asString()).isEqualTo("order");
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"Order\" WHERE id = ?").param(order.get("converted").get("id")
                .asString()).query(String.class).single()).isEqualTo(customer);
        // Small quote converted to an order wins the opportunity.
        JsonNode smallOrder = ok(200, "POST", "/crm/quotations/" + small.get("id").asString() + "/convert",
                Map.of("target", "order"));
        assertThat(ok(200, "GET", "/crm/opportunities/" + dealId, null).get("status").asString()).isEqualTo("won");
        // Open orders in the shared demo organization would block the POS shift-close tests.
        for (JsonNode o : List.of(order, smallOrder)) {
            ok(200, "POST", "/orders/" + o.get("converted").get("id").asString() + "/cancel",
                    Map.of("reason", "CRM test cleanup"));
        }
        // Revise / delete rules.
        JsonNode draft = ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "lines", List.of()));
        assertThat(as("POST", "/crm/quotations/" + draft.get("id").asString() + "/submit", null).status())
                .isEqualTo(400);
        ok(200, "DELETE", "/crm/quotations/" + draft.get("id").asString(), null);
        assertThat(ok(200, "GET", "/crm/customers/" + customer, null).get("quotations").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("reports, dashboard and customers respond with their sections")
    void reports() {
        opportunity("Report me " + sfx, Map.of());
        JsonNode r = ok(200, "GET", "/crm/reports?from=" + LocalDate.now().minusDays(30) + "&to=" + LocalDate.now(),
                null);
        for (String k : List.of("kpis", "funnel", "byStage", "byOwner", "bySource", "lostReasons", "trend", "forecast",
                "timeInStage", "quotations", "activities")) {
            assertThat(r.has(k)).as(k).isTrue();
        }
        assertThat(r.get("kpis").get("opportunities").asInt()).isPositive();
        JsonNode d = ok(200, "GET", "/crm/dashboard?scope=all", null);
        assertThat(d.get("scope").asString()).isEqualTo("all");
        assertThat(d.has("byStage")).isTrue();
        assertThat(ok(200, "GET", "/crm/customers?q=Suite%20Customer%20" + sfx, null).get("meta").get("total").asInt())
                .isEqualTo(1);
        assertThat(call("GET", "/crm/reports", null, salesToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("visibility: a rep sees own + unassigned; tenant isolation")
    void visibilityAndIsolation() {
        JsonNode adminOwned = opportunity("Admin owned " + sfx, Map.of());
        JsonNode mine = ok(201, "POST", "/crm/opportunities", Map.of("name", "Rep owned " + sfx, "partnerId",
                customer, "ownerId", salesUser));
        JsonNode list = call("GET", "/crm/opportunities?q=owned%20" + sfx, null, salesToken).body();
        List<String> seen = new ArrayList<>();
        list.get("data").forEach(x -> seen.add(x.get("id").asString()));
        assertThat(seen).contains(mine.get("id").asString()).doesNotContain(adminOwned.get("id").asString());
        assertThat(call("GET", "/crm/opportunities/" + adminOwned.get("id").asString(), null, salesToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/crm/opportunities/" + mine.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/crm/quotations?q=" + sfx, null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
        assertThat(call("POST", "/crm/config/stages", Map.of("name", "Nope"), salesToken).status()).isEqualTo(403);
    }
}
