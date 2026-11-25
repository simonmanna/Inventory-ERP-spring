package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.plm.web.PlmAppController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ECO types with their own pipelines, tags, kanban state and comment-only reviewers (ADR-S086 §2). */
class PlmTypesIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    JsonNode newType(String name) {
        return ok(201, "POST", PLM + "/change-types", Map.of("name", name, "color", "#2563eb", "description",
                "New products from prototype to release"));
    }

    List<JsonNode> stagesOf(String typeId) {
        List<JsonNode> out = new ArrayList<>();
        ok(200, "GET", PLM + "/stages?typeId=" + typeId, null).forEach(out::add);
        return out;
    }

    /** A product ECO (prototype → released) of the given type. */
    JsonNode productEco(String typeId) {
        String p = product("TYP");
        register(p, "prototype");
        Map<String, Object> body = new java.util.HashMap<>(Map.of("type", "product", "productId", p,
                "targetLifecycleState", "released"));
        if (typeId != null) {
            body.put("changeTypeId", typeId);
        }
        return ok(201, "POST", PLM + "/changes", body);
    }

    @Test
    @DisplayName("types: default pipeline, a new type gets its own; ECOs live in their type's stages")
    void typesAndPipelines() {
        JsonNode types = ok(200, "GET", PLM + "/change-types", null);
        JsonNode def = null;
        for (JsonNode t : types) {
            if (t.get("isDefault").asBoolean()) {
                def = t;
            }
        }
        assertThat(def).isNotNull();
        assertThat(def.get("stageCount").asLong()).isGreaterThanOrEqualTo(2);

        String name = "New product introduction " + suffix();
        JsonNode npi = newType(name);
        String npiId = npi.get("id").asString();
        assertThat(npi.get("isDefault").asBoolean()).isFalse();
        assertThat(npi.get("color").asString()).isEqualTo("#2563eb");
        Res dup = as("POST", PLM + "/change-types", Map.of("name", name.toUpperCase()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(msg(dup)).isEqualTo("A change type named " + name.toUpperCase() + " already exists.");
        assertThat(as("POST", PLM + "/change-types", Map.of("name", "x", "color", "blue")).status()).isEqualTo(400);

        List<JsonNode> npiStages = stagesOf(npiId);
        assertThat(npiStages).extracting(s -> s.get("name").asString())
                .containsExactly("New", "Engineering review", "Approved");
        assertThat(npiStages.getLast().get("isFinal").asBoolean()).isTrue();
        assertThat(npiStages.getFirst().get("typeName").asString()).isEqualTo(name);
        // Stage names are unique per type, not per organization.
        JsonNode gate = ok(201, "POST", PLM + "/stages", Map.of("name", "Prototype gate", "sequence", 15, "typeId",
                npiId, "folded", false));
        assertThat(gate.get("typeId").asString()).isEqualTo(npiId);
        assertThat(as("POST", PLM + "/stages", Map.of("name", "prototype gate", "typeId", npiId)).status())
                .isEqualTo(409);
        assertThat(msg(as("PATCH", PLM + "/stages/" + gate.get("id").asString(), Map.of("typeId",
                def.get("id").asString())))).isEqualTo("A stage cannot move to another change type.");

        JsonNode eco = productEco(npiId);
        String ecoId = eco.get("id").asString();
        assertThat(eco.get("changeType").get("id").asString()).isEqualTo(npiId);
        assertThat(eco.get("stage").get("name").asString()).isEqualTo("New");
        assertThat(eco.get("stage").get("typeId").asString()).isEqualTo(npiId);
        assertThat(eco.get("stages")).hasSize(4);
        // A stage of another pipeline is refused.
        String defaultReview = null;
        for (JsonNode s : stagesOf(def.get("id").asString())) {
            if (s.get("name").asString().equals("Engineering review")) {
                defaultReview = s.get("id").asString();
            }
        }
        Res other = as("POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", defaultReview));
        assertThat(other.status()).isEqualTo(400);
        assertThat(msg(other)).isEqualTo("Stage Engineering review belongs to another change type.");
        // Within its pipeline it moves as before.
        JsonNode atGate = ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId",
                gate.get("id").asString()));
        assertThat(atGate.get("stage").get("name").asString()).isEqualTo("Prototype gate");
        assertThat(ok(200, "GET", PLM + "/changes?changeTypeId=" + npiId, null).toString()).contains(ecoId);
        assertThat(ok(200, "GET", PLM + "/changes?changeTypeId=" + def.get("id").asString(), null).toString())
                .doesNotContain(ecoId);

        // Changing the type starts over in the new type's first stage.
        JsonNode moved = ok(200, "PATCH", PLM + "/changes/" + ecoId, Map.of("changeTypeId", def.get("id").asString()));
        assertThat(moved.get("changeType").get("id").asString()).isEqualTo(def.get("id").asString());
        assertThat(moved.get("stage").get("id").asString())
                .isEqualTo(stagesOf(def.get("id").asString()).getFirst().get("id").asString());
        assertThat(moved.get("activity").toString()).contains("Type changed from " + name);

        // A type with open changes, and the default type, cannot be deleted; an unused one can.
        ok(200, "PATCH", PLM + "/changes/" + ecoId, Map.of("changeTypeId", npiId));
        assertThat(msg(as("DELETE", PLM + "/change-types/" + npiId, null))).isEqualTo("Change type " + name
                + " has 1 open change(s); move them first.");
        assertThat(msg(as("DELETE", PLM + "/change-types/" + def.get("id").asString(), null)))
                .isEqualTo("The default change type cannot be deleted.");
        assertThat(msg(as("PATCH", PLM + "/change-types/" + def.get("id").asString(), Map.of("isDefault", false))))
                .isEqualTo("Make another change type the default instead.");
        ok(201, "POST", PLM + "/changes/" + ecoId + "/cancel", Map.of("reason", "test"));
        ok(200, "DELETE", PLM + "/change-types/" + npiId, null);
        assertThat(ok(200, "GET", PLM + "/change-types", null).toString()).doesNotContain(npiId);
        assertThat(as("GET", PLM + "/stages?typeId=" + npiId, null).status()).isEqualTo(404);
        // The cancelled ECO still reads (its type resolves to the default).
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("status").asString()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("default type: a BOM revision and a change request land there; switching the default moves it")
    void defaultType() {
        String flour = product("DFLOUR");
        String bun = product("DBUN");
        String bomId = bom(bun, flour, 1, 1, Map.of());
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId));
        assertThat(eco.get("changeType").get("isDefault").asBoolean()).isTrue();
        assertThat(eco.get("stage").get("name").asString()).isEqualTo("New");
        ok(201, "POST", PLM + "/changes/" + eco.get("id").asString() + "/cancel", Map.of());

        JsonNode other = newType("Cost reduction " + suffix());
        String otherId = other.get("id").asString();
        JsonNode viaBom = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "changeTypeId",
                otherId));
        assertThat(viaBom.get("changeType").get("id").asString()).isEqualTo(otherId);
        assertThat(viaBom.get("stage").get("typeId").asString()).isEqualTo(otherId);
        ok(201, "POST", PLM + "/changes/" + viaBom.get("id").asString() + "/cancel", Map.of());

        String oldDefault = eco.get("changeType").get("id").asString();
        try {
            assertThat(ok(200, "PATCH", PLM + "/change-types/" + otherId, Map.of("isDefault", true))
                    .get("isDefault").asBoolean()).isTrue();
            for (JsonNode t : ok(200, "GET", PLM + "/change-types", null)) {
                if (t.get("id").asString().equals(oldDefault)) {
                    assertThat(t.get("isDefault").asBoolean()).isFalse();
                }
            }
            JsonNode req = ok(201, "POST", PLM + "/requests", Map.of("title", "Cheaper flour", "productId", bun));
            JsonNode converted = ok(201, "POST", PLM + "/requests/" + req.get("id").asString() + "/convert",
                    Map.of("type", "bom"));
            assertThat(converted.get("changeType").get("id").asString()).isEqualTo(otherId);
            ok(201, "POST", PLM + "/changes/" + converted.get("id").asString() + "/cancel", Map.of());
        } finally {
            ok(200, "PATCH", PLM + "/change-types/" + oldDefault, Map.of("isDefault", true));
        }
        ok(200, "DELETE", PLM + "/change-types/" + otherId, null);
    }

    @Test
    @DisplayName("tags and kanban state: filter, reset on stage change, blocked reason")
    void tagsAndKanban() {
        JsonNode urgent = ok(201, "POST", PLM + "/tags", Map.of("name", "Customer " + suffix(), "color", "#dc2626"));
        String tagId = urgent.get("id").asString();
        assertThat(as("POST", PLM + "/tags", Map.of("name", urgent.get("name").asString())).status()).isEqualTo(409);
        JsonNode eco = productEco(null);
        String ecoId = eco.get("id").asString();
        assertThat(eco.get("kanbanState").asString()).isEqualTo("normal");
        assertThat(eco.get("tags")).isEmpty();

        JsonNode tagged = ok(200, "PATCH", PLM + "/changes/" + ecoId, Map.of("tagIds", List.of(tagId)));
        assertThat(tagged.get("tags").get(0).get("name").asString()).isEqualTo(urgent.get("name").asString());
        assertThat(ok(200, "GET", PLM + "/changes?tagId=" + tagId, null).toString()).contains(ecoId);
        assertThat(as("PATCH", PLM + "/changes/" + ecoId, Map.of("tagIds", List.of(UUID.randomUUID().toString())))
                .status()).isEqualTo(404);

        JsonNode blocked = ok(201, "POST", PLM + "/changes/" + ecoId + "/kanban-state", Map.of("kanbanState",
                "blocked", "blockedReason", "Waiting for the supplier sample"));
        assertThat(blocked.get("kanbanState").asString()).isEqualTo("blocked");
        assertThat(blocked.get("blockedReason").asString()).isEqualTo("Waiting for the supplier sample");
        assertThat(blocked.get("activity").get(0).get("message").asString())
                .isEqualTo("Blocked: Waiting for the supplier sample");
        assertThat(ok(200, "GET", PLM + "/changes?kanbanState=blocked", null).toString()).contains(ecoId);
        assertThat(as("POST", PLM + "/changes/" + ecoId + "/kanban-state", Map.of("kanbanState", "stuck")).status())
                .isEqualTo(400);
        // A new stage starts clean.
        String review = eco.get("stages").get(1).get("id").asString();
        JsonNode moved = ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        assertThat(moved.get("kanbanState").asString()).isEqualTo("normal");
        assertThat(moved.get("blockedReason").isNull()).isTrue();
        // The activity log records the stage each entry left the ECO in.
        Long withStage = jdbc.sql("SELECT count(*) FROM \"PlmChangeActivity\" WHERE \"changeId\" = ? AND"
                + " \"stageId\" = ?").params(ecoId, review).query(Long.class).single();
        assertThat(withStage).isPositive();

        // Deleting a tag takes it off the ECO.
        ok(200, "DELETE", PLM + "/tags/" + tagId, null);
        assertThat(ok(200, "GET", PLM + "/changes/" + ecoId, null).get("tags")).isEmpty();
        ok(201, "POST", PLM + "/changes/" + ecoId + "/cancel", Map.of());
        Res closed = as("POST", PLM + "/changes/" + ecoId + "/kanban-state", Map.of("kanbanState", "ready"));
        assertThat(closed.status()).isEqualTo(400);
        assertThat(msg(closed)).isEqualTo("Change " + eco.get("number").asString() + " is rejected.");
    }

    @Test
    @DisplayName("comment-only reviewers: their rejection never blocks, their approval never satisfies")
    void commentReviewers() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "PLM reviewer " + suffix(), new String[] {Permissions.PLM__READ,
                        Permissions.PLM__APPROVE}).update();
        String email = "plm-reviewer-" + suffix() + "@demo.test";
        String reviewer = createUser(demoOrg, email, "Plm@12345", role);
        String reviewerToken = login("DEMO", email, "Plm@12345");

        JsonNode type = newType("Reviewed changes " + suffix());
        String typeId = type.get("id").asString();
        List<JsonNode> pipeline = stagesOf(typeId);
        String review = pipeline.get(1).get("id").asString();
        ok(200, "PUT", PLM + "/stages/" + review + "/approvers", Map.of("approvers", List.of(
                Map.of("userId", approverId(), "kind", "required"), Map.of("userId", reviewer, "kind", "comment"))));

        JsonNode eco = productEco(typeId);
        String ecoId = eco.get("id").asString();
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        Res rejected = call("POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "rejected",
                "comment", "Prefer the old packaging"), reviewerToken);
        assertThat(rejected.status()).isEqualTo(201);
        JsonNode state = rejected.body().get("approval");
        assertThat(state.get("blocker").asString()).startsWith("Stage Engineering review needs approval from: ");
        assertThat(state.toString()).contains("\"kind\":\"comment\"");
        JsonNode approved = okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions",
                Map.of("decision", "approved"));
        assertThat(approved.get("stage").get("name").asString()).isEqualTo("Approved");
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        ok(201, "POST", PLM + "/changes/" + ecoId + "/cancel", Map.of());

        // Reviewers only: any plm:approve holder decides, and the reviewer's approval alone is not enough.
        ok(200, "PUT", PLM + "/stages/" + review + "/approvers", Map.of("approvers", List.of(
                Map.of("userId", reviewer, "kind", "comment"))));
        JsonNode eco2 = productEco(typeId);
        String eco2Id = eco2.get("id").asString();
        ok(201, "POST", PLM + "/changes/" + eco2Id + "/stage", Map.of("stageId", review));
        JsonNode byReviewer = call("POST", PLM + "/changes/" + eco2Id + "/decisions", Map.of("decision", "approved"),
                reviewerToken).body();
        assertThat(byReviewer.get("stage").get("id").asString()).isEqualTo(review);
        assertThat(byReviewer.get("approval").get("blocker").asString())
                .isEqualTo("Stage Engineering review needs an approval.");
        JsonNode byAdmin = okAs(approver(), 201, "POST", PLM + "/changes/" + eco2Id + "/decisions",
                Map.of("decision", "approved"));
        assertThat(byAdmin.get("status").asString()).isEqualTo("approved");
        ok(201, "POST", PLM + "/changes/" + eco2Id + "/cancel", Map.of());
        ok(200, "DELETE", PLM + "/change-types/" + typeId, null);
    }

    @Test
    @DisplayName("authorization matrix and tenant isolation of the new routes")
    void guards() {
        assertThat(assertAuthorizationMatrix(PlmAppController.class)).isGreaterThanOrEqualTo(9);
        JsonNode type = newType("Isolated " + suffix());
        assertThat(call("GET", PLM + "/change-types", null, otherToken).body().toString())
                .doesNotContain(type.get("id").asString());
        assertThat(call("PATCH", PLM + "/change-types/" + type.get("id").asString(), Map.of("name", "x"), otherToken)
                .status()).isEqualTo(404);
        ok(200, "DELETE", PLM + "/change-types/" + type.get("id").asString(), null);
    }
}
