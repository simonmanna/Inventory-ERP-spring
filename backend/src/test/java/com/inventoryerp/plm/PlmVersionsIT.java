package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.plm.web.PlmAppController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PLM versions (ADR-S086 §3): revision snapshots, compare, as-built, conflict + rebase, order BOM update. */
class PlmVersionsIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    static Map<String, Object> line(String productId, int qty) {
        return Map.of("componentProductId", productId, "quantity", qty);
    }

    /** Draft + activate a BOM with these lines; returns the BOM. */
    JsonNode activeBom(String output, List<Map<String, Object>> lines) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Recipe " + suffix(),
                "outputProductId", output, "outputQuantity", 1, "lines", lines));
        return ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
    }

    /** Move to the type's review stage and approve (auto-advances to the last stage). */
    JsonNode approve(String ecoId) {
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        String review = null;
        for (JsonNode s : detail.get("stages")) {
            if (s.get("requiresApproval").asBoolean()) {
                review = s.get("id").asString();
                break;
            }
        }
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        JsonNode approved = okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        return approved;
    }

    JsonNode bomEco(String bomId) {
        return ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "title", "Change " + suffix(),
                "stockDisposition", "none"));
    }

    List<JsonNode> revisions(String itemId) {
        List<JsonNode> out = new ArrayList<>();
        ok(200, "GET", PLM + "/items/" + itemId + "/revisions", null).forEach(out::add);
        return out;
    }

    static List<String> components(JsonNode bom) {
        List<String> out = new ArrayList<>();
        bom.get("lines").forEach(l -> out.add(l.get("componentProductId").asString()));
        return out;
    }

    static String qtyOf(JsonNode bom, String productId) {
        for (JsonNode l : bom.get("lines")) {
            if (l.get("componentProductId").asString().equals(productId)) {
                return new java.math.BigDecimal(l.get("quantity").asString()).stripTrailingZeros().toPlainString();
            }
        }
        return null;
    }

    @Test
    @DisplayName("revisions: snapshot on register and on every revision, compare, never reuse a revision code")
    void revisionsAndCompare() {
        String flour = product("VFLOUR");
        String sugar = product("VSUGAR");
        String bun = product("VBUN");
        JsonNode v1 = activeBom(bun, List.of(line(flour, 2)));
        String itemId = register(bun, "released");

        List<JsonNode> first = revisions(itemId);
        assertThat(first).hasSize(1);
        JsonNode a = first.getFirst();
        assertThat(a.get("revision").asString()).isEqualTo("A");
        assertThat(a.get("status").asString()).isEqualTo("current");
        assertThat(a.get("recorded").asBoolean()).isTrue();
        assertThat(a.get("bom").get("id").asString()).isEqualTo(v1.get("id").asString());
        assertThat(a.get("bom").get("lines").asInt()).isEqualTo(1);
        assertThat(a.has("snapshot")).isFalse();

        JsonNode eco = bomEco(v1.get("id").asString());
        String ecoId = eco.get("id").asString();
        String newBom = eco.get("newBomId").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + newBom, Map.of("lines", List.of(line(flour, 3), line(sugar, 1))));
        approve(ecoId);
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(applied.get("newRevision").asString()).isEqualTo("B");

        List<JsonNode> both = revisions(itemId);
        assertThat(both).extracting(r -> r.get("revision").asString()).containsExactly("B", "A");
        JsonNode b = both.getFirst();
        assertThat(b.get("status").asString()).isEqualTo("current");
        assertThat(b.get("changeNumber").asString()).isEqualTo(eco.get("number").asString());
        assertThat(b.get("bom").get("id").asString()).isEqualTo(newBom);
        assertThat(both.get(1).get("status").asString()).isEqualTo("superseded");
        assertThat(both.get(1).get("effectiveTo").isNull()).isFalse();

        JsonNode full = ok(200, "GET", PLM + "/revisions/" + b.get("id").asString(), null);
        assertThat(full.get("snapshot").get("bom").get("lines")).hasSize(2);
        assertThat(full.get("snapshot").get("product").get("code").asString()).startsWith("VBUN-");
        assertThat(full.get("snapshot").get("cost").has("unitCost")).isTrue();

        JsonNode cmp = ok(200, "GET", PLM + "/revisions/compare?from=" + a.get("id").asString() + "&to="
                + b.get("id").asString(), null);
        assertThat(cmp.get("recorded").asBoolean()).isTrue();
        assertThat(cmp.get("bom").get("added").get(0).get("componentProductId").asString()).isEqualTo(sugar);
        assertThat(cmp.get("bom").get("changed").get(0).get("changes").get(0).get("field").asString())
                .isEqualTo("quantity");
        assertThat(cmp.get("bom").get("from").get("version").asInt()).isLessThan(cmp.get("bom").get("to")
                .get("version").asInt());

        // Revisions of another product cannot be compared.
        String roll = product("VROLL");
        activeBom(roll, List.of(line(flour, 1)));
        String rollItem = register(roll, "released");
        String rollRev = revisions(rollItem).getFirst().get("id").asString();
        assertThat(msg(as("GET", PLM + "/revisions/compare?from=" + rollRev + "&to=" + b.get("id").asString(), null)))
                .isEqualTo("Both revisions must belong to the same product.");
        assertThat(as("GET", PLM + "/revisions/nope", null).status()).isEqualTo(404);

        // A revision code is never reused.
        JsonNode eco2 = bomEco(newBom);
        Res reuse = as("PATCH", PLM + "/changes/" + eco2.get("id").asString(), Map.of("newRevision", "A"));
        assertThat(reuse.status()).isEqualTo(400);
        assertThat(msg(reuse)).startsWith("Revision A of VBUN-").endsWith(" was already used; choose another revision.");
        ok(201, "POST", PLM + "/changes/" + eco2.get("id").asString() + "/cancel", Map.of("reason", "test"));
    }

    @Test
    @DisplayName("as-built: a completed order records the revision whose BOM it used")
    void asBuilt() {
        String flour = product("AFLOUR");
        String sugar = product("ASUGAR");
        String cake = product("ACAKE");
        stockIn(bakery, flour, 50, 2);
        stockIn(bakery, sugar, 50, 1);
        JsonNode v1 = activeBom(cake, List.of(line(flour, 2)));
        String itemId = register(cake, "released");

        // Started on revision A, finished after the ECO switched the product to B: still built as A.
        JsonNode early = started(v1.get("id").asString());
        JsonNode eco = bomEco(v1.get("id").asString());
        String ecoId = eco.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 1), line(sugar, 1))));
        approve(ecoId);
        ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        ok(201, "POST", "/manufacturing/production-orders/" + early.get("id").asString() + "/complete",
                Map.of("qtyProduced", 1));
        JsonNode late = started(eco.get("newBomId").asString());
        ok(201, "POST", "/manufacturing/production-orders/" + late.get("id").asString() + "/complete",
                Map.of("qtyProduced", 1));
        drainOutboxFully();

        JsonNode built = ok(200, "GET", PLM + "/items/" + itemId + "/as-built", null);
        Map<String, JsonNode> byOrder = new java.util.HashMap<>();
        built.forEach(r -> byOrder.put(r.get("orderId").asString(), r));
        JsonNode e = byOrder.get(early.get("id").asString());
        JsonNode l = byOrder.get(late.get("id").asString());
        assertThat(e.get("revision").asString()).isEqualTo("A");
        assertThat(e.get("matchedBy").asString()).isEqualTo("bom");
        assertThat(l.get("revision").asString()).isEqualTo("B");
        assertThat(l.get("matchedBy").asString()).isEqualTo("bom");
        assertThat(l.get("orderCode").asString()).startsWith("MO-");

        List<JsonNode> revs = revisions(itemId);
        assertThat(revs.getFirst().get("ordersBuilt").asLong()).isEqualTo(1);
        assertThat(revs.get(1).get("ordersBuilt").asLong()).isEqualTo(1);

        // One row per order, however often the event is delivered.
        drainOutboxFully();
        assertThat(jdbc.sql("SELECT count(*) FROM \"PlmAsBuilt\" WHERE \"orderId\" IN (?, ?)")
                .params(early.get("id").asString(), late.get("id").asString()).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("conflict: a newer active version blocks apply; rebase merges both sides")
    void conflictAndRebase() {
        String flour = product("RFLOUR");
        String salt = product("RSALT");
        String sugar = product("RSUGAR");
        String yeast = product("RYEAST");
        String loaf = product("RLOAF");
        JsonNode v1 = activeBom(loaf, List.of(line(flour, 2), line(salt, 1)));
        register(loaf, "released");

        JsonNode eco = bomEco(v1.get("id").asString());
        String ecoId = eco.get("id").asString();
        String number = eco.get("number").asString();
        String draft = eco.get("newBomId").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + draft, Map.of("lines", List.of(line(flour, 2), line(salt, 2),
                line(sugar, 1))));
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("conflict").isNull()).isTrue();
        assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/rebase", null)))
                .isEqualTo(number + " is already based on the current version (" + v1.get("code").asString()
                        + " v" + v1.get("version").asInt() + ").");

        // Someone activates another version of the loaf outside the ECO.
        JsonNode upstream = activeBom(loaf, List.of(line(flour, 3), line(salt, 1), line(yeast, 1)));
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        String expected = "Change " + number + " was raised against " + v1.get("code").asString() + " v"
                + v1.get("version").asInt() + ", which is no longer the active version ("
                + upstream.get("code").asString() + " v" + upstream.get("version").asInt() + " is active); rebase "
                + number + " first.";
        assertThat(detail.get("conflict").get("message").asString()).isEqualTo(expected);
        approve(ecoId);
        assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/apply", null))).isEqualTo(expected);

        JsonNode rebased = ok(201, "POST", PLM + "/changes/" + ecoId + "/rebase", null);
        assertThat(rebased.get("rebase").get("conflicts")).isEmpty();
        assertThat(rebased.get("rebase").get("onto").get("id").asString()).isEqualTo(upstream.get("id").asString());
        assertThat(rebased.get("conflict").isNull()).isTrue();
        // Integrity fix 2026-11-24 (ML-2): the rebase rewrote the BOM that was approved, so the change goes back to
        // its first approval stage, conflicts or not.
        assertThat(rebased.get("status").asString()).isEqualTo("draft");
        assertThat(rebased.get("stage").get("requiresApproval").asBoolean()).isTrue();
        assertThat(rebased.get("bomId").asString()).isEqualTo(upstream.get("id").asString());
        JsonNode merged = ok(200, "GET", "/manufacturing/boms/" + draft, null);
        assertThat(components(merged)).containsExactlyInAnyOrder(flour, salt, sugar, yeast);
        assertThat(qtyOf(merged, flour)).isEqualTo("3");
        assertThat(qtyOf(merged, salt)).isEqualTo("2");
        assertThat(rebased.get("activity").get(0).get("kind").asString()).isEqualTo("rebased");

        okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
        ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        assertThat(ok(200, "GET", "/manufacturing/boms/" + draft, null).get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + upstream.get("id").asString(), null).get("status")
                .asString()).isEqualTo("archived");

        // Both sides changed the salt differently: ours is kept and reported (and the approval is withdrawn).
        JsonNode eco2 = bomEco(draft);
        String eco2Id = eco2.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco2.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 3), line(salt, 5), line(sugar, 1), line(yeast, 1))));
        approve(eco2Id);
        activeBom(loaf, List.of(line(flour, 3), line(salt, 4), line(sugar, 1), line(yeast, 1)));
        JsonNode r2 = ok(201, "POST", PLM + "/changes/" + eco2Id + "/rebase", null);
        assertThat(r2.get("rebase").get("conflicts")).hasSize(1);
        assertThat(r2.get("rebase").get("conflicts").get(0).get("componentProductId").asString()).isEqualTo(salt);
        assertThat(r2.get("status").asString()).isEqualTo("draft");
        assertThat(r2.get("stage").get("requiresApproval").asBoolean()).isTrue();
        assertThat(qtyOf(ok(200, "GET", "/manufacturing/boms/" + eco2.get("newBomId").asString(), null), salt))
                .isEqualTo("5");

        // Only BOM changes rebase.
        String docEco = ok(201, "POST", PLM + "/changes", Map.of("type", "document", "documentIds", List.of(
                ok(201, "POST", PLM + "/documents", Map.of("title", "Spec " + suffix(), "docType", "drawing"))
                        .get("id").asString()))).get("id").asString();
        assertThat(msg(as("POST", PLM + "/changes/" + docEco + "/rebase", null))).endsWith(" is not a BOM change.");
    }

    @Test
    @DisplayName("orders: BOM status and moving a not-started order onto the current version")
    void orderBomUpdate() {
        String flour = product("OFLOUR");
        String pie = product("OPIE");
        stockIn(bakery, flour, 50, 2);
        JsonNode v1 = activeBom(pie, List.of(line(flour, 2)));
        JsonNode order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", v1.get("id").asString(),
                "runs", 1, "locationId", bakery, "outputLocationId", warehouse));
        String orderId = order.get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);

        JsonNode fresh = ok(200, "GET", PLM + "/orders/" + orderId + "/bom-status", null);
        assertThat(fresh.get("superseded").asBoolean()).isFalse();
        assertThat(fresh.get("canUpdate").asBoolean()).isFalse();
        assertThat(msg(as("POST", PLM + "/orders/" + orderId + "/update-bom", null)))
                .isEqualTo("Production order " + order.get("orderCode").asString()
                        + " already uses the current BOM version.");

        JsonNode v2 = activeBom(pie, List.of(line(flour, 3)));
        JsonNode stale = ok(200, "GET", PLM + "/orders/" + orderId + "/bom-status", null);
        assertThat(stale.get("superseded").asBoolean()).isTrue();
        assertThat(stale.get("canUpdate").asBoolean()).isTrue();
        assertThat(stale.get("current").get("id").asString()).isEqualTo(v2.get("id").asString());

        JsonNode moved = ok(201, "POST", PLM + "/orders/" + orderId + "/update-bom", null);
        assertThat(moved.get("fromId").asString()).isEqualTo(orderId);
        assertThat(moved.get("status").asString()).isEqualTo("confirmed");
        String newId = moved.get("toId").asString();
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId, null).get("status").asString())
                .isEqualTo("cancelled");
        JsonNode replacement = ok(200, "GET", "/manufacturing/production-orders/" + newId, null);
        assertThat(replacement.get("bomId").asString()).isEqualTo(v2.get("id").asString());
        assertThat(replacement.get("status").asString()).isEqualTo("confirmed");

        // A started order finishes on its version.
        JsonNode running = started(v2.get("id").asString());
        activeBom(pie, List.of(line(flour, 4)));
        JsonNode blocked = ok(200, "GET", PLM + "/orders/" + running.get("id").asString() + "/bom-status", null);
        assertThat(blocked.get("superseded").asBoolean()).isTrue();
        assertThat(blocked.get("canUpdate").asBoolean()).isFalse();
        assertThat(blocked.get("blocker").asString()).contains("has started");
        assertThat(as("POST", PLM + "/orders/" + running.get("id").asString() + "/update-bom", null).status())
                .isEqualTo(400);
        assertThat(as("GET", PLM + "/orders/nope/bom-status", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("guards: permissions and tenant isolation on the version routes")
    void guards() {
        assertThat(assertAuthorizationMatrix(PlmAppController.class)).isGreaterThanOrEqualTo(16);
        String flour = product("GFLOUR");
        String bun = product("GBUN");
        activeBom(bun, List.of(line(flour, 1)));
        String itemId = register(bun, "released");
        String revId = revisions(itemId).getFirst().get("id").asString();
        assertThat(call("GET", PLM + "/revisions/" + revId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", PLM + "/items/" + itemId + "/revisions", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", PLM + "/items/" + itemId + "/as-built", null, otherToken).status()).isEqualTo(404);
    }
}
