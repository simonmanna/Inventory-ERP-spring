package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlmChangeService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** PLM integrity fixes 2026-11-24 (ML-1 … ML-11): locking, frozen approved content, stale base, separation of duties. */
class PlmIntegrityIT extends MfgTestSupport {

    static final String PLM = "/manufacturing/plm";
    static final String ECO = "/manufacturing/engineering-changes/";

    @Autowired
    PlmChangeService changes;

    private String approverToken;

    String approver() {
        if (approverToken == null) {
            String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                    + " JOIN \"User\" u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1")
                    .query(String.class).single();
            String email = "plm-int-" + suffix() + "@demo.test";
            createUser(demoOrg, email, "Approver@12345", role);
            approverToken = login("DEMO", email, "Approver@12345");
        }
        return approverToken;
    }

    JsonNode okAs(String token, int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    static String msg(Res r) {
        return r.body().get("message").asString();
    }

    String stageId(String name) {
        for (JsonNode s : ok(200, "GET", PLM + "/stages", null)) {
            if (s.get("name").asString().equals(name)) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no stage " + name);
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state)).get("id")
                .asString();
    }

    /** Two concurrent calls; the statuses in completion-independent order. */
    List<Res> twice(Supplier<Res> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return call.get();
            }));
        }
        go.countDown();
        List<Res> out = new ArrayList<>();
        for (Future<Res> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    /** BOM ECO (stock disposition none) whose new version swaps a component, approved by the second admin. */
    JsonNode approvedBomChange(String bomId, String component) {
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "stockDisposition",
                "none"));
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines", List.of(
                Map.of("componentProductId", component, "quantity", 3))));
        okAs(approver(), 201, "POST", ECO + eco.get("id").asString() + "/approve", null);
        return eco;
    }

    @Test
    @DisplayName("ML-1: two concurrent applies run the switch-over once; cancel after apply is refused")
    void concurrentApply() throws Exception {
        String flour = product("ML1F");
        String bread = product("ML1B");
        String bomId = bom(bread, flour, 1, 2, Map.of());
        String itemId = register(bread, "released");
        JsonNode eco = approvedBomChange(bomId, flour);
        String ecoId = eco.get("id").asString();

        List<Res> res = twice(() -> as("POST", PLM + "/changes/" + ecoId + "/apply", null));
        assertThat(res.stream().map(Res::status).toList()).containsExactlyInAnyOrder(201, 400);
        Res loser = res.stream().filter(r -> r.status() == 400).findFirst().orElseThrow();
        assertThat(msg(loser)).isEqualTo("Only an approved change can be applied (status applied).");
        assertThat(ok(200, "GET", PLM + "/items/" + itemId, null).get("revision").asString()).isEqualTo("B");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PlmChangeActivity\" WHERE \"changeId\" = ? AND kind = 'applied'")
                .param(ecoId).query(Long.class).single()).isEqualTo(1);
        assertThat(msg(as("POST", ECO + ecoId + "/reject", null))).isEqualTo("Cannot reject a applied change.");
    }

    @Test
    @DisplayName("ML-2: an approved change's BOM and documents are frozen; an edit after an approval voids it")
    void approvalFreezesContent() {
        String flour = product("ML2F");
        String salt = product("ML2S");
        String bun = product("ML2B");
        String bomId = bom(bun, flour, 1, 2, Map.of());
        JsonNode eco = approvedBomChange(bomId, salt);
        Res frozen = as("PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines", List.of(
                Map.of("componentProductId", flour, "quantity", 9))));
        assertThat(frozen.status()).isEqualTo(400);
        String newCode = ok(200, "GET", "/manufacturing/boms/" + eco.get("newBomId").asString(), null).get("code")
                .asString();
        assertThat(msg(frozen)).isEqualTo("BOM " + newCode + " is part of approved change "
                + eco.get("number").asString() + "; it can no longer be edited.");

        // Document change: the attached draft is frozen once the change is approved.
        JsonNode doc = ok(201, "POST", PLM + "/documents", Map.of("title", "Bun spec", "productId", bun));
        String docId = doc.get("id").asString();
        JsonNode d = ok(201, "POST", PLM + "/changes", Map.of("type", "document", "productId", bun, "documentIds",
                List.of(docId)));
        String dId = d.get("id").asString();
        ok(201, "POST", PLM + "/changes/" + dId + "/stage", Map.of("stageId", stageId("Engineering review")));
        okAs(approver(), 201, "POST", PLM + "/changes/" + dId + "/decisions", Map.of("decision", "approved"));
        Res docEdit = as("PATCH", PLM + "/documents/" + docId, Map.of("title", "Swapped"));
        assertThat(docEdit.status()).isEqualTo(400);
        assertThat(msg(docEdit)).isEqualTo("Document " + doc.get("number").asString() + " is part of approved change "
                + d.get("number").asString() + "; it can no longer be edited.");
        assertThat(msg(as("PATCH", PLM + "/documents/" + docId, Map.of("changeId", ""))))
                .endsWith("; it can no longer be edited.");
        Res onto = as("POST", PLM + "/documents", Map.of("title", "Late add", "changeId", dId));
        assertThat(msg(onto)).isEqualTo("Only a draft change can be edited (status approved).");

        // Edit after an approval: with a second review stage the change is still a draft after the first approval;
        // editing it voids that approval and sends it back.
        JsonNode qa = ok(201, "POST", PLM + "/stages", Map.of("name", "QA ML2 " + suffix(), "sequence", 25,
                "requiresApproval", true));
        String qaId = qa.get("id").asString();
        String ecoId = null;
        try {
            String other = bom(product("ML2O"), flour, 1, 1, Map.of());
            ecoId = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", other)).get("id").asString();
            ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Engineering review")));
            JsonNode atQa = okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions",
                    Map.of("decision", "approved"));
            assertThat(atQa.get("stage").get("id").asString()).isEqualTo(qaId);
            JsonNode edited = ok(200, "PATCH", PLM + "/changes/" + ecoId, Map.of("title", "Sneaky edit"));
            assertThat(edited.get("stage").get("name").asString()).isEqualTo("Engineering review");
            assertThat(edited.get("approval").get("satisfied").asBoolean()).isFalse();
            assertThat(edited.get("activity").toString()).contains("Approvals voided: the change changed after"
                    + " approval.").contains("Sent back to Engineering review for re-approval.");
        } finally {
            if (ecoId != null) {
                ok(201, "POST", PLM + "/changes/" + ecoId + "/cancel", Map.of("reason", "cleanup"));
            }
            ok(200, "DELETE", PLM + "/stages/" + qaId, null);
        }
    }

    @Test
    @DisplayName("ML-3: only the active version is revised; a change on a no-longer-active base is refused")
    void staleBase() {
        String flour = product("ML3F");
        String cake = product("ML3C");
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Draft", "outputProductId", cake,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", flour, "quantity", 1))));
        assertThat(msg(as("POST", "/manufacturing/boms/" + draft.get("id").asString() + "/revise", Map.of())))
                .isEqualTo("BOM " + draft.get("code").asString() + " v1 is draft; only the active version can be"
                        + " revised.");
        ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
        JsonNode eco = approvedBomChange(draft.get("id").asString(), flour);
        // Another version of the same output is activated outside the change: the base v1 is archived.
        JsonNode other = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Other", "outputProductId", cake,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", flour, "quantity", 5))));
        ok(201, "POST", "/manufacturing/boms/" + other.get("id").asString() + "/activate", null);
        Res stale = as("POST", PLM + "/changes/" + eco.get("id").asString() + "/apply", null);
        assertThat(stale.status()).isEqualTo(400);
        assertThat(msg(stale)).isEqualTo("Change " + eco.get("number").asString() + " was raised against "
                + draft.get("code").asString() + " v1, which is no longer the active version.");
        assertThat(ok(200, "GET", "/manufacturing/boms/" + other.get("id").asString(), null).get("status").asString())
                .isEqualTo("active");
    }

    @Test
    @DisplayName("ML-4: you cannot approve your own change or review your own request (unless the setting allows)")
    void separationOfDuties() {
        String flour = product("ML4F");
        String roll = product("ML4R");
        String bomId = bom(roll, flour, 1, 1, Map.of());
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId));
        String ecoId = eco.get("id").asString();
        Res direct = as("POST", ECO + ecoId + "/approve", null);
        assertThat(direct.status()).isEqualTo(403);
        assertThat(msg(direct)).isEqualTo("You cannot approve a change you raised.");
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Engineering review")));
        Res own = as("POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
        assertThat(own.status()).isEqualTo(403);
        assertThat(msg(own)).isEqualTo("You cannot approve a change you raised.");
        assertThat(okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions",
                Map.of("decision", "approved")).get("status").asString()).isEqualTo("approved");

        // Only BOM changes are fast-tracked.
        String itemId = register(roll, "released");
        JsonNode p = ok(201, "POST", PLM + "/changes", Map.of("type", "product", "productId", roll,
                "targetLifecycleState", "phase_out"));
        assertThat(msg(call("POST", ECO + p.get("id").asString() + "/approve", null, approver())))
                .isEqualTo("Only a BOM change can be fast-tracked; use the PLM approval stages.");

        JsonNode ecr = ok(201, "POST", PLM + "/requests", Map.of("title", "Own request", "productId", roll));
        Res review = as("POST", PLM + "/requests/" + ecr.get("id").asString() + "/review", Map.of("decision", "accept"));
        assertThat(review.status()).isEqualTo(403);
        assertThat(msg(review)).isEqualTo("You cannot review a request you raised.");

        // The organization may allow self-approval.
        ok(200, "PUT", "/settings/plm.allowSelfApproval", Map.of("value", true));
        try {
            ok(201, "POST", PLM + "/requests/" + ecr.get("id").asString() + "/review", Map.of("decision", "accept"));
            ok(201, "POST", PLM + "/changes/" + p.get("id").asString() + "/stage",
                    Map.of("stageId", stageId("Engineering review")));
            assertThat(ok(201, "POST", PLM + "/changes/" + p.get("id").asString() + "/decisions",
                    Map.of("decision", "approved")).get("status").asString()).isEqualTo("approved");
        } finally {
            as("DELETE", "/settings/plm.allowSelfApproval", null);
        }
        ok(201, "POST", PLM + "/changes/" + p.get("id").asString() + "/cancel", Map.of());
        assertThat(itemId).isNotNull();
    }

    @Test
    @DisplayName("ML-5: a last stage that requires approval is enforced; approval cannot be switched off under ECOs")
    void lastStageApproval() {
        String approvedStage = stageId("Approved");
        String flour = product("ML5F");
        String bomId = bom(product("ML5O"), flour, 1, 1, Map.of());
        String ecoId = null;
        ok(200, "PATCH", PLM + "/stages/" + approvedStage, Map.of("requiresApproval", true));
        try {
            ecoId = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId)).get("id").asString();
            ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", stageId("Engineering review")));
            JsonNode atLast = okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions",
                    Map.of("decision", "approved"));
            assertThat(atLast.get("stage").get("name").asString()).isEqualTo("Approved");
            assertThat(atLast.get("status").asString()).isEqualTo("draft");
            assertThat(msg(as("POST", PLM + "/changes/" + ecoId + "/apply", null)))
                    .isEqualTo("Only an approved change can be applied (status draft).");
            assertThat(msg(as("PATCH", PLM + "/stages/" + approvedStage, Map.of("requiresApproval", false))))
                    .startsWith("Stage Approved has ").endsWith(" change(s) awaiting its approval; move them first.");
            JsonNode done = okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions",
                    Map.of("decision", "approved"));
            assertThat(done.get("status").asString()).isEqualTo("approved");
        } finally {
            if (ecoId != null) {
                as("POST", PLM + "/changes/" + ecoId + "/cancel", Map.of("reason", "cleanup"));
            }
            ok(200, "PATCH", PLM + "/stages/" + approvedStage, Map.of("requiresApproval", false));
        }
    }

    @Test
    @DisplayName("ML-6 / ML-7: concurrent convert makes one ECO; concurrent transitions apply once")
    void concurrentRequestAndLifecycle() throws Exception {
        String loaf = product("ML6L");
        String itemId = register(loaf, "released");
        JsonNode ecr = ok(201, "POST", PLM + "/requests", Map.of("title", "Phase out", "productId", loaf));
        String ecrId = ecr.get("id").asString();
        List<Res> res = twice(() -> as("POST", PLM + "/requests/" + ecrId + "/convert", Map.of("type", "product",
                "targetLifecycleState", "phase_out")));
        assertThat(res.stream().map(Res::status).toList()).containsExactlyInAnyOrder(201, 400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EngineeringChange\" WHERE \"requestId\" = ?").param(ecrId)
                .query(Long.class).single()).isEqualTo(1);

        List<Res> moves = twice(() -> as("POST", PLM + "/items/" + itemId + "/transition", Map.of("toState",
                "phase_out")));
        assertThat(moves.stream().map(Res::status).toList()).containsExactlyInAnyOrder(201, 400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"PlmLifecycleEvent\" WHERE \"itemId\" = ? AND kind = 'transition'")
                .param(itemId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("ML-8: the effectivity job records a failed switch-over on the ECO as the system actor, once")
    void jobFailureRecorded() {
        String flour = product("ML8F");
        String out = product("ML8O");
        String bomId = bom(out, flour, 1, 1, Map.of());
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "stockDisposition",
                "none", "effectivity", "date", "effectiveDate", LocalDate.now().plusDays(5).toString()));
        String ecoId = eco.get("id").asString();
        try {
            okAs(approver(), 201, "POST", ECO + ecoId + "/approve", null);
            assertThat(ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null).get("applyQueued").asBoolean())
                    .isTrue();
            jdbc.sql("UPDATE \"EngineeringChange\" SET \"effectiveDate\" = ? WHERE id = ?")
                    .params(LocalDate.now().minusDays(2), ecoId).update();
            JsonNode other = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Other", "outputProductId", out,
                    "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", flour, "quantity", 2))));
            ok(201, "POST", "/manufacturing/boms/" + other.get("id").asString() + "/activate", null);

            for (int run = 0; run < 2; run++) {
                TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> {
                    try {
                        changes.autoApply(ecoId);
                        throw new AssertionError("the stale change must not apply");
                    } catch (com.inventoryerp.shared.web.ApiException e) {
                        changes.recordAutoApplyFailure(ecoId, e.getMessage());
                    }
                });
            }
            List<Map<String, Object>> lines = jdbc.sql("SELECT message, \"userId\" FROM \"PlmChangeActivity\" WHERE"
                    + " \"changeId\" = ? AND message LIKE 'Automatic switch-over failed:%'").param(ecoId).query()
                    .listOfRows();
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().get("userId")).isEqualTo("system");
            assertThat((String) lines.getFirst().get("message")).endsWith("which is no longer the active version.");
            JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
            assertThat(detail.get("activity").get(0).get("userName").asString()).isEqualTo("System");
            assertThat(detail.get("status").asString()).isEqualTo("approved");
        } finally {
            // Leave no due change behind: other suites run the effectivity job over the whole organization.
            as("POST", PLM + "/changes/" + ecoId + "/cancel", Map.of("reason", "cleanup"));
        }
    }

    @Test
    @DisplayName("ML-11: optional limit / offset on PLM items, item detail and legacy ECO list")
    void paging() {
        register(product("ML11A"), "design");
        String b = register(product("ML11B"), "design");
        ok(201, "POST", PLM + "/items/" + b + "/transition", Map.of("toState", "concept"));
        assertThat(ok(200, "GET", PLM + "/items?limit=1", null)).hasSize(1);
        assertThat(ok(200, "GET", PLM + "/items?limit=1&offset=1", null)).hasSize(1);
        assertThat(msg(as("GET", PLM + "/items?limit=0", null))).isEqualTo("limit must be between 1 and 1000");
        assertThat(ok(200, "GET", PLM + "/items/" + b, null).get("history")).hasSize(2);
        assertThat(ok(200, "GET", PLM + "/items/" + b + "?limit=1", null).get("history")).hasSize(1);
        assertThat(msg(as("GET", "/manufacturing/engineering-changes?limit=1001", null)))
                .isEqualTo("limit must be between 1 and 1000");
        assertThat(ok(200, "GET", "/manufacturing/engineering-changes", null).isArray()).isTrue();
    }
}
