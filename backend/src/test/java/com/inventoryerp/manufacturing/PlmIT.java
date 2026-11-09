package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlmChangeService;
import com.inventoryerp.manufacturing.web.PlmController;
import java.util.UUID;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Product lifecycle management (ADR-S063): lifecycle gates, staged ECOs, approvals, impact, ECRs, documents. */
class PlmIT extends MfgTestSupport {

    @Autowired
    PlmChangeService changes;

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    String stageId(String name) {
        for (JsonNode s : ok(200, "GET", PLM + "/stages", null)) {
            if (s.get("name").asString().equals(name)) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no stage " + name);
    }

    @Test
    @DisplayName("lifecycle: design blocks production, release needs an active BOM, obsolete is guarded")
    void lifecycle() {
        String flour = product("PFLOUR");
        String bread = product("PBREAD");
        String cake = product("PCAKE");
        stockIn(bakery, flour, 50, 2);

        String itemId = register(bread, "design");
        assertThat(as("POST", PLM + "/items", Map.of("productId", bread)).status()).isEqualTo(409);
        // No BOM yet: release is allowed only once a BOM exists and is active.
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Bread", "outputProductId", bread,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", flour, "quantity", 2))));
        Res noBom = as("POST", PLM + "/items/" + itemId + "/transition", Map.of("toState", "released"));
        assertThat(noBom.status()).isEqualTo(400);
        assertThat(msg(noBom)).startsWith("Release needs an active BOM for ");
        String bomId = draft.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);

        Res blocked = as("POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).endsWith(" is design in its lifecycle and cannot be produced.");

        assertThat(msg(as("POST", PLM + "/items/" + itemId + "/transition", Map.of("toState", "phase_out"))))
                .endsWith(" from design to phase out.");
        ok(201, "POST", PLM + "/items/" + itemId + "/transition", Map.of("toState", "prototype", "note", "Trial"));
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId", bakery,
                "outputLocationId", warehouse));
        ok(201, "POST", PLM + "/items/" + itemId + "/transition", Map.of("toState", "released"));

        // Flour is a component of an active BOM: it cannot become obsolete.
        String flourItem = register(flour, "released");
        Res inUse = as("POST", PLM + "/items/" + flourItem + "/transition", Map.of("toState", "obsolete"));
        assertThat(msg(inUse)).contains("is still a component of active BOM(s): ");
        // Bread has an open order.
        assertThat(msg(as("POST", PLM + "/items/" + itemId + "/transition", Map.of("toState", "obsolete"))))
                .contains("has open production orders: MO-");

        // An obsolete component blocks BOM activation.
        String oldFlour = product("POLD");
        String oldItem = register(oldFlour, "design");
        ok(201, "POST", PLM + "/items/" + oldItem + "/transition", Map.of("toState", "obsolete"));
        JsonNode cakeBom = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Cake", "outputProductId", cake,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", oldFlour, "quantity", 1))));
        assertThat(msg(as("POST", "/manufacturing/boms/" + cakeBom.get("id").asString() + "/activate", null)))
                .contains(" uses obsolete component(s): ");

        JsonNode detail = ok(200, "GET", PLM + "/items/" + itemId, null);
        assertThat(detail.get("lifecycleState").asString()).isEqualTo("released");
        assertThat(detail.get("history")).hasSize(3);
        assertThat(detail.get("boms")).hasSize(1);
        assertThat(detail.get("openOrders")).hasSize(1);
        assertThat(detail.get("allowedTransitions").toString()).contains("phase_out");

        // Products with an active BOM can be registered in bulk (cake's BOM is still draft).
        String pie = product("PPIE");
        bom(pie, flour, 1, 1, Map.of());
        assertThat(ok(201, "POST", PLM + "/items/register-active", null).get("registered").asInt()).isGreaterThan(0);
        assertThat(ok(200, "GET", PLM + "/items?productId=" + pie, null).get(0).get("lifecycleState").asString())
                .isEqualTo("released");
    }

    @Test
    @DisplayName("BOM ECO: stages, approval auto-advance, impact, apply bumps the revision")
    void bomChange() {
        String flour = product("QFLOUR");
        String sugar = product("QSUGAR");
        String bun = product("QBUN");
        stockIn(bakery, flour, 20, 3);
        stockIn(bakery, sugar, 20, 1);
        String bomId = bom(bun, flour, 1, 2, Map.of());
        String itemId = register(bun, "released");

        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "title", "Sugar for"
                + " flour", "priority", "high", "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        assertThat(eco.get("number").asString()).startsWith("ECO-");
        assertThat(eco.get("stage").get("name").asString()).isEqualTo("New");
        assertThat(eco.get("priority").asString()).isEqualTo("high");
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId))))
                .contains("already has an open change " + eco.get("number").asString());
        String newBom = eco.get("newBomId").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + newBom, Map.of("lines", List.of(
                Map.of("componentProductId", sugar, "quantity", 1))));

        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(detail.get("comparison").get("added").get(0).get("product").get("id").asString()).isEqualTo(sugar);
        assertThat(detail.get("comparison").get("removed")).hasSize(1);

        JsonNode impact = ok(200, "GET", PLM + "/changes/" + ecoId + "/impact", null);
        assertThat(impact.get("removedComponents").get(0).get("obsolescenceRisk").asBoolean()).isTrue();
        assertThat(impact.get("cost").has("error")).isFalse();
        assertThat(impact.get("cost").has("delta")).isTrue();
        assertThat(impact.get("warnings").toString()).contains("no other active use");

        // The review stage cannot be skipped, and leaving it needs an approval.
        assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Approved")))))
                .isEqualTo("Cannot skip stage Engineering review: it needs approval.");
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Engineering review")));
        assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Approved")))))
                .isEqualTo("Stage Engineering review needs an approval.");
        assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/apply", null)))
                .isEqualTo("Only an approved change can be applied (status draft).");

        JsonNode rejected = ok(201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "rejected",
                "comment", "Check sweetness"));
        assertThat(rejected.get("approval").get("blocker").asString()).startsWith("Stage Engineering review was"
                + " rejected by ");
        JsonNode approved = ok(201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("stage").get("name").asString()).isEqualTo("Approved");

        // Back to review voids the approval; approving again re-approves.
        JsonNode back = ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId",
                stageId("Engineering review")));
        assertThat(back.get("status").asString()).isEqualTo("draft");
        assertThat(back.get("approval").get("satisfied").asBoolean()).isFalse();
        ok(201, "POST", PLM + "/changes/" + ecoId + "/comments", Map.of("message", "Re-check done"));
        ok(201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));

        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(applied.get("newRevision").asString()).isEqualTo("B");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + newBom, null).get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("status").asString())
                .isEqualTo("archived");
        JsonNode item = ok(200, "GET", PLM + "/items/" + itemId, null);
        assertThat(item.get("revision").asString()).isEqualTo("B");
        assertThat(item.get("history").get(0).get("kind").asString()).isEqualTo("revision");
        JsonNode done = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(done.get("activity").get(0).get("kind").asString()).isEqualTo("applied");
        assertThat(done.get("approvals")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(ok(200, "GET", PLM + "/overview", null).get("changes").get("byStatus").get("applied").asLong())
                .isPositive();
    }

    @Test
    @DisplayName("named approvers: only listed users decide; a required approver must approve")
    void namedApprovers() {
        String flour = product("NFLOUR");
        String roll = product("NROLL");
        String bomId = bom(roll, flour, 1, 1, Map.of());
        String someoneElse = createUser(demoOrg, "plm-" + suffix() + "@demo.test", "Plm@12345", null);
        JsonNode qa = ok(201, "POST", PLM + "/stages", Map.of("name", "QA sign-off " + suffix(), "sequence", 25,
                "requiresApproval", true));
        String qaId = qa.get("id").asString();
        String ecoId = null;
        try {
            ok(200, "PUT", PLM + "/stages/" + qaId + "/approvers", Map.of("approvers", List.of(
                    Map.of("userId", someoneElse, "kind", "required"))));
            ecoId = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId)).get("id").asString();
            ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Engineering review")));
            JsonNode atQa = ok(201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
            assertThat(atQa.get("stage").get("id").asString()).isEqualTo(qaId);
            assertThat(atQa.get("approval").get("canDecide").asBoolean()).isFalse();
            assertThat(atQa.get("approval").get("blocker").asString()).contains("needs approval from: ");
            Res notMine = as("POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
            assertThat(notMine.status()).isEqualTo(403);
            assertThat(msg(notMine)).isEqualTo("You are not an approver of stage " + qa.get("name").asString() + ".");
            assertThat(msg(as("DELETE", PLM + "/stages/" + qaId, null))).contains("has 1 open change(s)");
        } finally {
            if (ecoId != null) {
                ok(201, "POST", PLM + "/changes/" + ecoId + "/cancel", Map.of("reason", "test cleanup"));
            }
            ok(200, "DELETE", PLM + "/stages/" + qaId, null);
        }
        JsonNode cancelled = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(cancelled.get("status").asString()).isEqualTo("rejected");
        assertThat(cancelled.get("rejectedReason").asString()).isEqualTo("test cleanup");
    }

    @Test
    @DisplayName("routing, product and document ECOs; effectivity date; ECR review and convert")
    void typedChanges() {
        String flour = product("RFLOUR");
        String loaf = product("RLOAF");
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "OV-" + suffix(), "name", "Oven"));
        JsonNode routing = ok(201, "POST", "/manufacturing/routings", Map.of("name", "Loaf line " + suffix(),
                "productId", loaf, "operations", List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId",
                        wc.get("id").asString(), "durationMins", 30))));
        String routingId = routing.get("id").asString();
        String bomId = bom(loaf, flour, 1, 1, Map.of("routingId", routingId));
        String itemId = register(loaf, "released");

        // Routing change: edit the copy's operations, approve, apply → BOM re-pointed, old routing inactive.
        JsonNode r = ok(201, "POST", PLM + "/changes", Map.of("type", "routing", "routingId", routingId));
        String rId = r.get("id").asString();
        String newRouting = r.get("newRoutingId").asString();
        assertThat(r.get("newRouting").get("isActive").asBoolean()).isFalse();
        JsonNode edited = ok(200, "PUT", PLM + "/changes/" + rId + "/routing-operations", Map.of("operations",
                List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId", wc.get("id").asString(),
                        "durationMins", 25), Map.of("sequence", 2, "name", "Cool", "durationMins", 10))));
        assertThat(edited.get("routingComparison").get("added")).hasSize(1);
        assertThat(edited.get("routingComparison").get("changed").get(0).get("changes").has("durationMins")).isTrue();
        ok(201, "POST", PLM + "/changes/" + rId + "/stage", Map.of("stageId", stageId("Engineering review")));
        ok(201, "POST", PLM + "/changes/" + rId + "/decisions", Map.of("decision", "approved"));
        ok(201, "POST", PLM + "/changes/" + rId + "/apply", null);
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("routingId").asString())
                .isEqualTo(newRouting);
        assertThat(ok(200, "GET", "/manufacturing/routings/" + routingId, null).get("isActive").asBoolean())
                .isFalse();
        assertThat(ok(200, "GET", PLM + "/items/" + itemId, null).get("revision").asString()).isEqualTo("B");

        // Product change with a future effective date: approved, but apply waits for the date.
        String future = LocalDate.now().plusDays(10).toString();
        JsonNode p = ok(201, "POST", PLM + "/changes", Map.of("type", "product", "productId", loaf,
                "targetLifecycleState", "phase_out", "effectivity", "date", "effectiveDate", future));
        String pId = p.get("id").asString();
        assertThat(ok(201, "POST", "/manufacturing/engineering-changes/" + pId + "/approve", null).get("status")
                .asString()).isEqualTo("approved");
        JsonNode queued = ok(201, "POST", PLM + "/changes/" + pId + "/apply", Map.of());
        assertThat(queued.get("status").asString()).isEqualTo("approved");
        assertThat(queued.get("applyQueued").asBoolean()).isTrue();
        assertThat(queued.get("pending").asString()).startsWith("takes effect on " + future + " (");
        ok(201, "POST", PLM + "/changes/" + pId + "/cancel", Map.of());
        JsonNode p2 = ok(201, "POST", PLM + "/changes", Map.of("type", "product", "productId", loaf,
                "targetLifecycleState", "phase_out"));
        ok(201, "POST", "/manufacturing/engineering-changes/" + p2.get("id").asString() + "/approve", null);
        ok(201, "POST", PLM + "/changes/" + p2.get("id").asString() + "/apply", null);
        JsonNode item = ok(200, "GET", PLM + "/items/" + itemId, null);
        assertThat(item.get("lifecycleState").asString()).isEqualTo("phase_out");
        assertThat(item.get("revision").asString()).isEqualTo("B");

        // Document change: the draft is released on apply; revising makes v2 (rev B) and supersedes v1 on release.
        JsonNode doc = ok(201, "POST", PLM + "/documents", Map.of("title", "Loaf drawing", "docType", "drawing",
                "productId", loaf, "url", "https://cad.example/loaf"));
        String docId = doc.get("id").asString();
        JsonNode d = ok(201, "POST", PLM + "/changes", Map.of("type", "document", "productId", loaf, "documentIds",
                List.of(docId)));
        assertThat(msg(as("POST", PLM + "/documents/" + docId + "/release", null)))
                .endsWith(" when that change is applied.");
        ok(201, "POST", "/manufacturing/engineering-changes/" + d.get("id").asString() + "/approve", null);
        ok(201, "POST", PLM + "/changes/" + d.get("id").asString() + "/apply", null);
        assertThat(ok(200, "GET", PLM + "/documents/" + docId, null).get("status").asString()).isEqualTo("released");
        JsonNode v2 = ok(201, "POST", PLM + "/documents/" + docId + "/revise", null);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("revision").asString()).isEqualTo("B");
        assertThat(msg(as("POST", PLM + "/documents/" + docId + "/revise", null))).contains("already has a draft");
        ok(201, "POST", PLM + "/documents/" + v2.get("id").asString() + "/release", null);
        assertThat(ok(200, "GET", PLM + "/documents/" + docId, null).get("status").asString())
                .isEqualTo("superseded");

        // ECR: review needs a reason to reject; accepted request converts into a BOM ECO of the product.
        JsonNode ecr = ok(201, "POST", PLM + "/requests", Map.of("title", "Cheaper flour", "productId", loaf,
                "reasonCode", "cost_reduction", "priority", "urgent", "dueDate", "2020-01-01"));
        String ecrId = ecr.get("id").asString();
        assertThat(ecr.get("number").asString()).startsWith("ECR-");
        assertThat(ok(200, "GET", PLM + "/requests?status=open", null).toString()).contains(ecrId);
        assertThat(ok(200, "GET", PLM + "/requests/" + ecrId, null).get("overdue").asBoolean()).isTrue();
        ok(200, "PATCH", PLM + "/requests/" + ecrId, Map.of("dueDate", ""));
        assertThat(ok(200, "GET", PLM + "/requests/" + ecrId, null).get("dueDate").isNull()).isTrue();
        assertThat(msg(as("POST", PLM + "/requests/" + ecrId + "/review", Map.of("decision", "reject"))))
                .isEqualTo("Give a reason to reject the change request.");
        ok(201, "POST", PLM + "/requests/" + ecrId + "/review", Map.of("decision", "accept"));
        JsonNode converted = ok(201, "POST", PLM + "/requests/" + ecrId + "/convert", Map.of("type", "bom"));
        assertThat(converted.get("type").asString()).isEqualTo("bom");
        assertThat(converted.get("bomId").asString()).isEqualTo(bomId);
        assertThat(converted.get("request").get("status").asString()).isEqualTo("converted");
        assertThat(converted.get("priority").asString()).isEqualTo("urgent");
        assertThat(msg(as("POST", PLM + "/requests/" + ecrId + "/convert", Map.of("type", "bom"))))
                .endsWith(" is converted.");
    }

    /** Two-line BOM (removed component first), activated. */
    String twoLineBom(String output, String removed, String kept) {
        JsonNode b = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Disp " + suffix(), "outputProductId", output,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", removed, "quantity", 1),
                        Map.of("componentProductId", kept, "quantity", 1))));
        ok(201, "POST", "/manufacturing/boms/" + b.get("id").asString() + "/activate", null);
        return b.get("id").asString();
    }

    /** BOM ECO replacing {@code removed} by {@code added}, approved directly. */
    JsonNode approvedSwap(String bomId, String kept, String added, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("type", "bom", "bomId", bomId));
        body.putAll(extra);
        JsonNode eco = ok(201, "POST", PLM + "/changes", body);
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines", List.of(
                Map.of("componentProductId", kept, "quantity", 1), Map.of("componentProductId", added, "quantity", 1))));
        ok(201, "POST", "/manufacturing/engineering-changes/" + eco.get("id").asString() + "/approve", null);
        return eco;
    }

    @Test
    @DisplayName("dispositions: scrap raises a draft adjustment; rework replaces unstarted orders and flags started ones")
    void scrapAndReplaceOrders() {
        String old = product("DOLD");
        String keep = product("DKEEP");
        String neu = product("DNEW");
        String out = product("DOUT");
        stockIn(bakery, old, 10, 2);
        stockIn(bakery, keep, 10, 1);
        String bomId = twoLineBom(out, old, keep);
        JsonNode draft = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 2,
                "locationId", bakery, "outputLocationId", warehouse, "scheduledFor", "2030-01-15"));
        JsonNode confirmed = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse));
        ok(201, "POST", "/manufacturing/production-orders/" + confirmed.get("id").asString() + "/confirm", null);
        JsonNode running = started(bomId);

        JsonNode eco = approvedSwap(bomId, keep, neu, Map.of("stockDisposition", "scrap", "wipDisposition", "rework"));
        String ecoId = eco.get("id").asString();
        JsonNode plan = ok(200, "GET", PLM + "/changes/" + ecoId + "/impact", null).get("dispositionPlan");
        assertThat(plan.get("stock")).hasSize(1);
        assertThat(plan.get("stock").get(0).get("productId").asString()).isEqualTo(old);
        assertThat(plan.get("orders").toString()).contains("replace").contains("flag_rework");

        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        JsonNode result = applied.get("dispositionResult");
        assertThat(result.get("documents").get(0).get("kind").asString()).isEqualTo("stock_adjustment");
        String adjId = result.get("documents").get(0).get("id").asString();
        assertThat(jdbc.sql("SELECT status::text FROM \"StockAdjustment\" WHERE id = ?").param(adjId)
                .query(String.class).single()).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT \"qtyActual\" FROM \"StockAdjustmentItem\" WHERE \"adjId\" = ? AND \"productId\" = ?")
                .params(adjId, old).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0");
        // The draft and the confirmed order are re-created on the new version; the running one is flagged.
        assertThat(result.get("replacedOrders")).hasSize(2);
        assertThat(result.get("flaggedOrders").get(0).get("id").asString()).isEqualTo(running.get("id").asString());
        String newBom = eco.get("newBomId").asString();
        for (JsonNode r : result.get("replacedOrders")) {
            JsonNode from = ok(200, "GET", "/manufacturing/production-orders/" + r.get("fromId").asString(), null);
            JsonNode to = ok(200, "GET", "/manufacturing/production-orders/" + r.get("toId").asString(), null);
            assertThat(from.get("status").asString()).isEqualTo("cancelled");
            assertThat(to.get("bomId").asString()).isEqualTo(newBom);
            assertThat(to.get("plannedQty").asString()).isEqualTo(from.get("plannedQty").asString());
            assertThat(to.get("status").asString()).isEqualTo(r.get("status").asString());
            if (r.get("fromId").asString().equals(draft.get("id").asString())) {
                assertThat(to.get("scheduledFor").asString()).startsWith("2030-01-15");
            } else {
                assertThat(to.get("status").asString()).isEqualTo("confirmed");
            }
        }
        JsonNode flagged = ok(200, "GET", "/manufacturing/production-orders/" + running.get("id").asString(), null);
        assertThat(flagged.get("qcRequired").asBoolean()).isTrue();
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("activity").get(0).get("message").asString())
                .contains("Raised ADJ-").contains("Replaced MO-").contains("Flagged for rework MO-");
    }

    @Test
    @DisplayName("dispositions: use-up queues the switch-over until the stock is consumed; force switches now")
    void useUpQueue() {
        String old = product("UOLD");
        String keep = product("UKEEP");
        String neu = product("UNEW");
        String out = product("UOUT");
        stockIn(bakery, old, 3, 2);
        String bomId = twoLineBom(out, old, keep);
        JsonNode eco = approvedSwap(bomId, keep, neu, Map.of());
        String ecoId = eco.get("id").asString();
        assertThat(eco.get("stockDisposition").asString()).isEqualTo("use_up");

        JsonNode queued = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(queued.get("status").asString()).isEqualTo("approved");
        assertThat(queued.get("applyQueued").asBoolean()).isTrue();
        assertThat(queued.get("pending").asString()).contains("to be used up");
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("pending").asString()).contains("(3)");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("status").asString()).isEqualTo("active");

        // The hourly job leaves it queued while stock remains, and switches over once it is gone.
        assertThat(runJob()).doesNotContain(eco.get("number").asString());
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", old, "quantity", 3))));
        assertThat(runJob()).contains(eco.get("number").asString());
        JsonNode done = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(done.get("status").asString()).isEqualTo("applied");
        assertThat(done.get("applyQueued").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("status").asString())
                .isEqualTo("archived");

        // force: switch over now; the remaining stock is reported.
        String old2 = product("UOLD2");
        String out2 = product("UOUT2");
        stockIn(bakery, old2, 2, 2);
        JsonNode eco2 = approvedSwap(twoLineBom(out2, old2, keep), keep, neu, Map.of());
        JsonNode forced = ok(201, "POST", PLM + "/changes/" + eco2.get("id").asString() + "/apply",
                Map.of("force", true));
        assertThat(forced.get("status").asString()).isEqualTo("applied");
        assertThat(forced.get("dispositionResult").get("skipped").get(0).get("reason").asString())
                .startsWith("switched over with 2");
    }

    /** One run of the PLM effectivity job for the demo organization; returns the applied ECO numbers. */
    List<String> runJob() {
        return TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> {
            List<String> applied = new java.util.ArrayList<>();
            for (String id : changes.dueChanges()) {
                String n = changes.autoApply(id);
                if (n != null) {
                    applied.add(n);
                }
            }
            return applied;
        });
    }

    @Test
    @DisplayName("dispositions: supplier return as a draft debit note, rework transfer to quarantine")
    void returnAndRework() {
        String old = product("ROLD");
        String noSupplier = product("RNOSUP");
        String keep = product("RKEEP");
        String neu = product("RNEW");
        String out = product("ROUT");
        stockIn(warehouse, old, 4, 5);
        String supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())").params(supplier, demoOrg, "SUP-" + suffix(),
                "Flour Mill " + suffix()).update();
        jdbc.sql("INSERT INTO \"SupplierProduct\" (id, \"organizationId\", \"partnerId\", \"productId\", preferred)"
                + " VALUES (?, ?, ?, ?, true)").params(UUID.randomUUID().toString(), demoOrg, supplier, old).update();
        JsonNode eco = approvedSwap(twoLineBom(out, old, keep), keep, neu, Map.of("stockDisposition",
                "return_to_supplier"));
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + eco.get("id").asString() + "/apply", null);
        JsonNode doc = applied.get("dispositionResult").get("documents").get(0);
        assertThat(doc.get("kind").asString()).isEqualTo("debit_note");
        assertThat(doc.get("partnerId").asString()).isEqualTo(supplier);
        Map<String, Object> note = jdbc.sql("SELECT direction::text AS direction, reason::text AS reason, status::text"
                + " AS status FROM \"DebitNote\" WHERE id = ?").param(doc.get("id").asString()).query().singleRow();
        assertThat(note).containsEntry("direction", "inbound").containsEntry("reason", "returned_goods")
                .containsEntry("status", "draft");
        assertThat(jdbc.sql("SELECT quantity FROM \"DebitNoteLine\" WHERE \"debitNoteId\" = ?")
                .param(doc.get("id").asString()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("4");

        // A component without a supplier is reported, not returned; rework moves stock to QC quarantine (draft).
        stockIn(bakery, noSupplier, 6, 1);
        String out2 = product("ROUT2");
        JsonNode eco2 = approvedSwap(twoLineBom(out2, noSupplier, keep), keep, neu, Map.of("stockDisposition",
                "rework"));
        JsonNode r2 = ok(201, "POST", PLM + "/changes/" + eco2.get("id").asString() + "/apply", null);
        JsonNode trf = r2.get("dispositionResult").get("documents").get(0);
        assertThat(trf.get("kind").asString()).isEqualTo("stock_transfer");
        assertThat(jdbc.sql("SELECT l.code FROM \"StockTransfer\" t JOIN \"InventoryLocation\" l ON l.id ="
                + " t.\"toLocId\" WHERE t.id = ?").param(trf.get("id").asString()).query(String.class).single())
                .isEqualTo("QC-QUARANTINE");
        String out3 = product("ROUT3");
        String old3 = product("ROLD3");
        stockIn(bakery, old3, 1, 1);
        JsonNode eco3 = approvedSwap(twoLineBom(out3, old3, keep), keep, neu, Map.of("stockDisposition",
                "return_to_supplier"));
        JsonNode r3 = ok(201, "POST", PLM + "/changes/" + eco3.get("id").asString() + "/apply", null);
        assertThat(r3.get("dispositionResult").get("documents")).isEmpty();
        assertThat(r3.get("dispositionResult").get("skipped").get(0).get("reason").asString())
                .isEqualTo("no supplier on file");
    }

    @Test
    @DisplayName("authorization matrix, tenant isolation and no 5xx on unknown ids")
    void guards() {
        assertThat(assertAuthorizationMatrix(PlmController.class)).isGreaterThan(35);
        String loaf = product("TLOAF");
        String itemId = register(loaf, "design");
        assertThat(call("GET", PLM + "/items/" + itemId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", PLM + "/items", null, otherToken).body().toString()).doesNotContain(itemId);
        assertThat(sweepGetRoutes("com.inventoryerp.manufacturing.web").stream()
                .filter(f -> f.contains("/plm/")).toList()).isEmpty();
    }
}
