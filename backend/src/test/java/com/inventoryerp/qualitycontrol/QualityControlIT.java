package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** QC1 of the quality-control module (ADR-S087): configuration, versioning, check engine, lifecycle, security. */
class QualityControlIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String product;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, now())")
                .params(product, demoOrg, "QC-" + sfx, "Concrete block " + sfx).update();
    }

    String next() {
        return sfx + "-" + (++seq);
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "QcTest-" + role, permissions).update();
        String email = "qc-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Qc@12345678", role);
        return login("DEMO", email, "Qc@12345678");
    }

    Map<String, Object> measurePoint(String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", title);
        p.put("operation", "manufacturing");
        p.put("checkType", "measure");
        p.put("productIds", List.of(product));
        p.put("norm", 5);
        p.put("toleranceMin", 4);
        p.put("toleranceMax", 6);
        p.put("unit", "%");
        p.put("decimals", 2);
        p.put("failureClassRequired", true);
        return p;
    }

    JsonNode manual(Map<String, Object> body) {
        return ok(201, "POST", "/quality-control/checks", body);
    }

    String failureClass(String code) {
        for (JsonNode fc : ok(200, "GET", "/quality-control/failure-classes", null)) {
            if (code.equals(fc.get("code").asString())) {
                return fc.get("id").asString();
            }
        }
        throw new AssertionError("no failure class " + code);
    }

    // ------------------------------------------------------------------ configuration

    @Test
    @DisplayName("teams, default failure classes, control point versioning and deactivation")
    void configuration() {
        JsonNode team = ok(201, "POST", "/quality-control/teams", Map.of("name", "Block QA " + sfx, "memberUserIds",
                List.of(adminId)));
        assertThat(team.get("members")).hasSize(1);
        assertThat(as("POST", "/quality-control/teams", Map.of("name", "block qa " + sfx)).status()).isEqualTo(409);

        JsonNode classes = ok(200, "GET", "/quality-control/failure-classes", null);
        assertThat(classes.findValuesAsString("code")).contains("MATERIAL", "DIMENSIONAL", "STRENGTH", "MOISTURE",
                "PACKAGING", "DOCUMENTATION", "PROCESS");

        Map<String, Object> body = measurePoint("Moisture " + next());
        body.put("teamId", team.get("id").asString());
        JsonNode p = ok(201, "POST", "/quality-control/control-points", body);
        String id = p.get("id").asString();
        assertThat(p.get("reference").asString()).startsWith("QCP-");
        assertThat(p.get("version").asInt()).isEqualTo(1);
        assertThat(p.get("versions")).hasSize(1);
        assertThat(p.get("teamName").asString()).isEqualTo("Block QA " + sfx);

        body.put("toleranceMax", 5.5);
        body.put("reason", "tightened after customer complaint");
        JsonNode v2 = ok(200, "PUT", "/quality-control/control-points/" + id, body);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("versions").get(0).get("reason").asString()).isEqualTo("tightened after customer complaint");
        assertThat(v2.get("versions").get(1).get("config").get("toleranceMax").decimalValue()).isEqualByComparingTo("6");

        JsonNode off = ok(200, "POST", "/quality-control/control-points/" + id + "/deactivate", Map.of("reason", "test"));
        assertThat(off.get("isActive").asBoolean()).isFalse();
        assertThat(off.get("version").asInt()).isEqualTo(3);
        assertThat(off.get("note").asString()).contains("Checks already generated stay valid");

        // validation
        Map<String, Object> bad = measurePoint("Bad " + next());
        bad.remove("toleranceMin");
        bad.remove("toleranceMax");
        assertThat(as("POST", "/quality-control/control-points", bad).body().get("message").asString())
                .isEqualTo("A measure control point needs a minimum or a maximum.");
        Map<String, Object> qty = measurePoint("Qty " + next());
        qty.put("controlPer", "quantity");
        assertThat(as("POST", "/quality-control/control-points", qty).body().get("message").asString())
                .isEqualTo("A quantity control point needs a test percentage.");
        Map<String, Object> rm = measurePoint("Mode " + next());
        rm.put("receiptMode", "quarantine_on_receipt");
        assertThat(as("POST", "/quality-control/control-points", rm).body().get("message").asString())
                .isEqualTo("A receipt mode applies to receipt control points only.");
        Map<String, Object> receipt = measurePoint("Receipt " + next());
        receipt.put("operation", "receipt");
        assertThat(ok(201, "POST", "/quality-control/control-points", receipt).get("receiptMode").asString())
                .isEqualTo("pre_receipt");
    }

    @Test
    @DisplayName("a deactivated point keeps the checks it created; new checks from it are refused")
    void deactivatedPointKeepsChecks() {
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint("Keep " + next()))
                .get("id").asString();
        JsonNode check = manual(Map.of("controlPointId", point, "productId", product));
        ok(200, "POST", "/quality-control/control-points/" + point + "/deactivate", Map.of());
        assertThat(as("POST", "/quality-control/checks", Map.of("controlPointId", point)).status()).isEqualTo(400);
        JsonNode done = ok(200, "POST", "/quality-control/checks/" + check.get("id").asString() + "/execute",
                Map.of("measureValue", 5));
        assertThat(done.get("status").asString()).isEqualTo("passed");
        assertThat(done.get("controlPointVersionId").isNull()).isFalse();
    }

    // ------------------------------------------------------------------ check types

    @Test
    @DisplayName("measure: dry-run evaluation saves nothing; out-of-tolerance sample fails and needs a failure class")
    void measureWithSamples() {
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint("Measure " + next()))
                .get("id").asString();
        JsonNode check = manual(Map.of("controlPointId", point, "productId", product, "batchNumber", "B-" + sfx));
        String id = check.get("id").asString();
        assertThat(check.get("checkNumber").asString()).startsWith("QCC-");
        assertThat(check.get("origin").asString()).isEqualTo("manual");
        assertThat(check.get("blocksSource").asBoolean()).isFalse();
        assertThat(check.get("snapshot").get("toleranceMin").decimalValue()).isEqualByComparingTo("4");

        List<Map<String, Object>> samples = List.of(Map.of("sampleRef", "S1", "measureValue", 5.2),
                Map.of("sampleRef", "S2", "measureValue", 6.4, "batchNumber", "B-" + sfx));
        JsonNode dry = ok(200, "POST", "/quality-control/checks/" + id + "/evaluate", Map.of("samples", samples));
        assertThat(dry.get("result").asString()).isEqualTo("failed");
        assertThat(dry.get("evaluation").get("values").get(1).get("reason").asString()).isEqualTo("above maximum 6");
        assertThat(ok(200, "GET", "/quality-control/checks/" + id, null).get("status").asString()).isEqualTo("pending");

        assertThat(as("POST", "/quality-control/checks/" + id + "/execute", Map.of("samples", samples)).body()
                .get("message").asString()).isEqualTo("A failure class is required when this check fails.");
        assertThat(as("POST", "/quality-control/checks/" + id + "/execute", Map.of("measureValue", 5.123)).body()
                .get("message").asString()).isEqualTo("measureValue has more than 2 decimals.");

        JsonNode failed = ok(200, "POST", "/quality-control/checks/" + id + "/execute", Map.of("samples", samples,
                "failureClassId", failureClass("MOISTURE")));
        assertThat(failed.get("status").asString()).isEqualTo("failed");
        assertThat(failed.get("failureClassCode").asString()).isEqualTo("MOISTURE");
        JsonNode exec = failed.get("executions").get(0);
        assertThat(exec.get("revision").asInt()).isEqualTo(1);
        assertThat(exec.get("samples")).hasSize(2);
        assertThat(exec.get("samples").get(1).get("result").asString()).isEqualTo("failed");

        // completed checks are immutable
        Res again = as("POST", "/quality-control/checks/" + id + "/execute", Map.of("measureValue", 5));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Quality check " + check.get("checkNumber").asString() + " is already failed.");
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"QcCheckExecution\" SET notes = 'x' WHERE \"checkId\" = ?")
                .param(id).update()).hasMessageContaining("append-only");

        // reset (manage) opens revision 2; revision 1 stays as recorded
        JsonNode reset = ok(200, "POST", "/quality-control/checks/" + id + "/reset", Map.of("reason", "probe recalibrated"));
        assertThat(reset.get("status").asString()).isEqualTo("pending");
        assertThat(reset.get("currentRevision").asInt()).isEqualTo(2);
        assertThat(reset.get("failureClassId").isNull()).isTrue();
        JsonNode passed = ok(200, "POST", "/quality-control/checks/" + id + "/execute", Map.of("measureValue", 5.05));
        assertThat(passed.get("status").asString()).isEqualTo("passed");
        assertThat(passed.get("executions")).hasSize(2);
        JsonNode rev1 = passed.get("executions").get(1);
        assertThat(rev1.get("revision").asInt()).isEqualTo(1);
        assertThat(rev1.get("result").asString()).isEqualTo("failed");
        assertThat(rev1.get("superseded").asBoolean()).isTrue();
        assertThat(rev1.get("samples")).hasSize(2);
        assertThat(passed.get("events").findValuesAsString("action")).containsExactly("created", "executed", "reset",
                "executed");
        assertThat(as("POST", "/quality-control/checks/" + id + "/waive", Map.of("reason", "x", "disposition",
                "other")).body().get("message").asString()).isEqualTo("A passed check cannot be waived.");
    }

    @Test
    @DisplayName("instructions, pass / fail and manual measure checks")
    void otherTypes() {
        JsonNode ins = manual(Map.of("checkType", "instructions", "instructions", "Wear gloves"));
        assertThat(as("POST", "/quality-control/checks/" + ins.get("id").asString() + "/execute",
                Map.of("result", "failed")).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/quality-control/checks/" + ins.get("id").asString() + "/execute", Map.of())
                .get("status").asString()).isEqualTo("passed");

        JsonNode pf = manual(Map.of("checkType", "pass_fail", "title", "Visual " + next()));
        String pid = pf.get("id").asString();
        assertThat(as("POST", "/quality-control/checks/" + pid + "/execute", Map.of()).body().get("message").asString())
                .isEqualTo("result is required.");
        assertThat(as("POST", "/quality-control/checks/" + pid + "/execute", Map.of("result", "passed", "samples",
                List.of(Map.of("result", "failed")))).body().get("message").asString())
                .isEqualTo("A failed sample cannot give a passed check.");
        JsonNode pfDone = ok(200, "POST", "/quality-control/checks/" + pid + "/execute", Map.of("samples",
                List.of(Map.of("sampleRef", "A", "result", "passed"), Map.of("sampleRef", "B", "result", "failed")),
                "failedQty", 2));
        assertThat(pfDone.get("status").asString()).isEqualTo("failed");
        assertThat(new BigDecimal(pfDone.get("failedQty").asString())).isEqualByComparingTo("2");

        assertThat(as("POST", "/quality-control/checks", Map.of("checkType", "measure")).body().get("message")
                .asString()).isEqualTo("A measure check needs a minimum or a maximum.");
        assertThat(as("POST", "/quality-control/checks", Map.of()).body().get("message").asString())
                .isEqualTo("checkType is required for a check without a control point.");
    }

    @Test
    @DisplayName("worksheet: authoritative evaluation; a template edit keeps an existing check on its version")
    void worksheetVersioning() {
        List<Map<String, Object>> fields = List.of(
                Map.of("key", "length_mm", "label", "Length", "type", "number", "required", true, "min", 390, "max", 410),
                Map.of("key", "surface", "label", "Surface", "type", "select", "options", List.of("smooth", "cracked"),
                        "passValues", List.of("smooth")));
        JsonNode t1 = ok(201, "POST", "/quality-control/worksheet-templates", Map.of("name", "Block " + next(),
                "fields", fields));
        assertThat(as("POST", "/quality-control/worksheet-templates", Map.of("name", "Empty", "fields",
                List.of(Map.of("key", "Bad Key", "label", "x", "type", "text")))).status()).isEqualTo(400);

        JsonNode check = manual(Map.of("checkType", "worksheet", "worksheetTemplateId", t1.get("id").asString()));
        String id = check.get("id").asString();
        assertThat(check.get("worksheetTemplateId").asString()).isEqualTo(t1.get("id").asString());

        // edit after use -> version 2; the check stays on version 1
        JsonNode t2 = ok(200, "PATCH", "/quality-control/worksheet-templates/" + t1.get("id").asString(),
                Map.of("fields", List.of(Map.of("key", "length_mm", "label", "Length", "type", "number", "min", 395,
                        "max", 405))));
        assertThat(t2.get("version").asInt()).isEqualTo(2);
        assertThat(t2.get("id").asString()).isNotEqualTo(t1.get("id").asString());
        assertThat(t2.get("versions")).hasSize(2);
        assertThat(as("PATCH", "/quality-control/worksheet-templates/" + t1.get("id").asString(),
                Map.of("name", "old")).body().get("message").asString())
                .isEqualTo("Only the latest version of a worksheet template can be edited.");
        JsonNode pinned = ok(200, "GET", "/quality-control/checks/" + id, null);
        assertThat(pinned.get("worksheetTemplateId").asString()).isEqualTo(t1.get("id").asString());
        assertThat(pinned.get("snapshot").get("worksheet").get("version").asInt()).isEqualTo(1);

        assertThat(as("POST", "/quality-control/checks/" + id + "/execute", Map.of("worksheetAnswers",
                Map.of("length_mm", 400, "colour", "red"))).body().get("message").asString())
                .isEqualTo("Unknown worksheet field colour.");
        // 392 passes version 1 (390..410) although version 2 would fail it (395..405)
        JsonNode done = ok(200, "POST", "/quality-control/checks/" + id + "/execute", Map.of("worksheetAnswers",
                Map.of("length_mm", 392, "surface", "smooth")));
        assertThat(done.get("status").asString()).isEqualTo("passed");
        assertThat(done.get("executions").get(0).get("worksheetAnswers").get("length_mm").decimalValue())
                .isEqualByComparingTo("392");
    }

    @Test
    @DisplayName("picture: the photo must be attached to the check; control-point documents need configure")
    void pictureAndAttachments() {
        JsonNode check = manual(Map.of("checkType", "picture", "title", "Pallet photo " + next()));
        String id = check.get("id").asString();
        assertThat(as("POST", "/quality-control/checks/" + id + "/execute", Map.of()).body().get("message").asString())
                .isEqualTo("A picture check needs a photo.");
        assertThat(as("POST", "/quality-control/checks/" + id + "/execute", Map.of("pictureDocumentId",
                UUID.randomUUID().toString())).body().get("message").asString())
                .isEqualTo("The photo must be attached to the check first.");

        String operator = tokenWith(Permissions.QUALITY_CONTROL__READ, Permissions.QUALITY_CONTROL__CHECK,
                Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        Res up = multipart("/dms/records/qc_check/" + id + "/attachments", "photo.txt", "pallet", operator);
        assertThat(up.status()).as(up.raw().body()).isEqualTo(201);
        String doc = up.body().get(0).get("id").asString();
        JsonNode done = call("POST", "/quality-control/checks/" + id + "/execute", Map.of("pictureDocumentId", doc),
                operator).body();
        assertThat(done.get("status").asString()).isEqualTo("passed");
        assertThat(done.get("executions").get(0).get("pictureDocumentId").asString()).isEqualTo(doc);

        String point = ok(201, "POST", "/quality-control/control-points", measurePoint("Docs " + next()))
                .get("id").asString();
        assertThat(multipart("/dms/records/qc_control_point/" + point + "/attachments", "sop.txt", "sop", operator)
                .status()).isEqualTo(403);
        assertThat(multipart("/dms/records/qc_control_point/" + point + "/attachments", "sop.txt", "sop", adminToken)
                .status()).isEqualTo(201);

        // another tenant cannot see the check or its files
        assertThat(call("GET", "/quality-control/checks/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/dms/records/qc_check/" + id + "/attachments", null, otherToken).status())
                .isIn(403, 404);
        assertThat(call("GET", "/quality-control/checks?search=" + check.get("checkNumber").asString(), null,
                otherToken).body().get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ lifecycle and authorization

    @Test
    @DisplayName("waive / reset / correction need manage; waiver needs reason and disposition")
    void manageActions() {
        String operator = tokenWith(Permissions.QUALITY_CONTROL__READ, Permissions.QUALITY_CONTROL__CHECK);
        JsonNode check = manual(Map.of("checkType", "pass_fail"));
        String id = check.get("id").asString();
        assertThat(call("POST", "/quality-control/checks/" + id + "/waive", Map.of("reason", "x", "disposition",
                "other"), operator).status()).isEqualTo(403);
        assertThat(call("POST", "/quality-control/checks/" + id + "/reset", Map.of("reason", "x"), operator).status())
                .isEqualTo(403);
        assertThat(as("POST", "/quality-control/checks/" + id + "/waive", Map.of("reason", "x")).status()).isEqualTo(400);
        assertThat(as("POST", "/quality-control/checks/" + id + "/waive", Map.of("disposition", "other")).status())
                .isEqualTo(400);
        assertThat(as("POST", "/quality-control/checks/" + id + "/waive", Map.of("reason", "x", "disposition",
                "quantity_excluded")).body().get("message").asString())
                .isEqualTo("excludedQty is required for disposition quantity_excluded.");

        ok(200, "POST", "/quality-control/checks/" + id + "/execute", Map.of("result", "failed"));
        JsonNode waived = ok(200, "POST", "/quality-control/checks/" + id + "/waive", Map.of("reason",
                "accepted by customer", "disposition", "accepted_as_is"));
        assertThat(waived.get("status").asString()).isEqualTo("waived");
        assertThat(waived.get("waiveDisposition").asString()).isEqualTo("accepted_as_is");

        // source closed: no reset / waive / execute; a correction check is independent
        jdbc.sql("UPDATE \"QcCheck\" SET \"sourceClosed\" = true WHERE id = ?").param(id).update();
        assertThat(as("POST", "/quality-control/checks/" + id + "/reset", Map.of("reason", "x")).body().get("message")
                .asString()).contains("is closed: its source document is completed");
        JsonNode corr = ok(201, "POST", "/quality-control/checks/" + id + "/corrections", Map.of("reason",
                "result recorded on the wrong batch"));
        assertThat(corr.get("origin").asString()).isEqualTo("correction");
        assertThat(corr.get("correctsCheckId").asString()).isEqualTo(id);
        assertThat(corr.get("blocksSource").asBoolean()).isFalse();
        ok(200, "POST", "/quality-control/checks/" + corr.get("id").asString() + "/execute", Map.of("result", "passed"));
        JsonNode original = ok(200, "GET", "/quality-control/checks/" + id, null);
        assertThat(original.get("status").asString()).isEqualTo("waived");
        assertThat(original.get("corrections")).hasSize(1);
        assertThat(original.get("events").findValuesAsString("action")).contains("correction_opened");

        JsonNode cancelMe = manual(Map.of("checkType", "pass_fail"));
        assertThat(ok(200, "POST", "/quality-control/checks/" + cancelMe.get("id").asString() + "/cancel",
                Map.of("reason", "duplicate")).get("status").asString()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("two concurrent executions of one check: exactly one wins")
    void concurrentExecution() throws Exception {
        String id = manual(Map.of("checkType", "pass_fail")).get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> as("POST", "/quality-control/checks/" + id + "/execute", Map.of("result", "passed")).status(),
                    () -> as("POST", "/quality-control/checks/" + id + "/execute", Map.of("result", "failed")).status());
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"QcCheckExecution\" WHERE \"checkId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("generation identity: one live check per key (DB-enforced); counter allocation is race-free")
    void generationIdentity() throws Exception {
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint("Gen " + next()))
                .get("id").asString();
        String key = point + "|production_order|" + UUID.randomUUID() + "|-|-#0";
        String first = insertGenerated(point, key);
        assertThatThrownBy(() -> insertGenerated(point, key)).hasMessageContaining("QcCheck_org_generation_key");
        jdbc.sql("UPDATE \"QcCheck\" SET status = 'cancelled', \"cancelReason\" = 'line removed' WHERE id = ?")
                .param(first).update();
        String restored = insertGenerated(point, key);
        assertThat(restored).isNotEqualTo(first);

        String base = "base-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> new TransactionTemplate(txm).execute(s -> jdbc.sql("INSERT INTO \"QcGenerationCounter\""
                        + " (id, \"organizationId\", \"baseKey\", \"nextSeq\") VALUES (?, ?, ?, 0) ON CONFLICT"
                        + " (\"organizationId\", \"baseKey\") DO UPDATE SET \"nextSeq\" = \"QcGenerationCounter\".\"nextSeq\""
                        + " + 1 RETURNING \"nextSeq\"").params(UUID.randomUUID().toString(), demoOrg, base)
                        .query(Integer.class).single()));
            }
            Set<Integer> seqs = new HashSet<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                seqs.add(f.get());
            }
            assertThat(seqs).hasSize(16).contains(0, 15);
        } finally {
            pool.shutdownNow();
        }
    }

    String insertGenerated(String point, String key) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"QcCheck\" (id, \"organizationId\", \"checkNumber\", title, \"controlPointId\", origin,"
                + " \"generationKey\", \"checkType\", \"sourceType\", blocking) VALUES (?, ?, ?, 'gen', ?, 'generated', ?,"
                + " 'measure', 'production_order', true)").params(id, demoOrg, "QCT-" + id.substring(0, 8), point, key)
                .update();
        return id;
    }

    @Test
    @DisplayName("every route answers 401 without a token and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.qualitycontrol")).isEqualTo(28);
        assertThat(sweepGetRoutes("com.inventoryerp.qualitycontrol")).isEmpty();
        assertThat(ok(200, "GET", "/quality-control/overview", null).get("byStatus").has("pending")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    Res multipart(String path, String filename, String content, String token) {
        try {
            String boundary = "----qc" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
