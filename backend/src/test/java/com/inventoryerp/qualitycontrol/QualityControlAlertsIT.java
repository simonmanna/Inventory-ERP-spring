package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcAlertAgingSource;
import com.inventoryerp.qualitycontrol.application.QcGate;
import com.inventoryerp.qualitycontrol.application.QcSourceDoc;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** QC3 of the quality-control module (ADR-S087 §8): alerts, stages, assignment, NCR escalation, notifications. */
class QualityControlAlertsIT extends ApiTestSupport {

    @Autowired
    QcGate gate;

    @Autowired
    QcAlertAgingSource aging;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String product;
    String team;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, now())")
                .params(product, demoOrg, "QCA-" + sfx, "Paver " + sfx).update();
        team = ok(201, "POST", "/quality-control/teams", Map.of("name", "Alert team " + sfx)).get("id").asString();
    }

    String next() {
        return sfx + "-" + (++seq);
    }

    String stage(String name) {
        for (JsonNode s : ok(200, "GET", "/quality-control/alert-stages", null)) {
            if (name.equals(s.get("name").asString())) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no stage " + name);
    }

    JsonNode manualCheck() {
        return ok(201, "POST", "/quality-control/checks", Map.of("checkType", "pass_fail", "title", "Visual " + next(),
                "productId", product, "batchNumber", "B-" + next()));
    }

    /** A generated pass/fail check on a fake production order (QC2 gate), with the point's autoAlert. */
    String generatedCheck(boolean autoAlert) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", "Auto " + next());
        p.put("operation", "manufacturing");
        p.put("checkType", "pass_fail");
        p.put("productIds", List.of(product));
        p.put("blocking", false);
        p.put("autoAlert", autoAlert);
        p.put("teamId", team);
        p.put("failureMessage", "Reject the pallet and call the shift lead.");
        String point = ok(201, "POST", "/quality-control/control-points", p).get("id").asString();
        String source = UUID.randomUUID().toString();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> gate.sync(new QcSourceDoc("production_order", source, "MO-" + next(),
                        "manufacturing", null, null, null, null, null, List.of(new QcSourceDoc.Line("L1", product, null,
                                BigDecimal.TEN, null))))));
        // earlier tests' points match the same product, so pick this point's check
        return jdbc.sql("SELECT id FROM \"QcCheck\" WHERE \"sourceId\" = ? AND \"controlPointId\" = ?")
                .params(source, point).query(String.class).single();
    }

    static List<String> ids(JsonNode rows) {
        List<String> out = new java.util.ArrayList<>();
        rows.forEach(r -> out.add(r.get("id").asString()));
        return out;
    }

    long alertsOf(String checkId) {
        return jdbc.sql("SELECT count(*) FROM \"QcAlert\" WHERE \"checkId\" = ?").param(checkId).query(Long.class)
                .single();
    }

    @Test
    @DisplayName("default stages; a manual alert from a check moves through stages with history; closing leaves the check")
    void stagesAndHistory() {
        JsonNode stages = ok(200, "GET", "/quality-control/alert-stages", null);
        assertThat(stages.findValuesAsString("name")).containsSubsequence("New", "Confirmed", "Action Proposed",
                "Solved");
        JsonNode check = manualCheck();
        String checkId = check.get("id").asString();
        ok(200, "POST", "/quality-control/checks/" + checkId + "/execute", Map.of("result", "failed"));
        assertThat(alertsOf(checkId)).as("manual checks never auto-alert").isZero();

        JsonNode alert = ok(201, "POST", "/quality-control/alerts", Map.of("title", "Cracked edges", "checkId", checkId,
                "priority", 2));
        String id = alert.get("id").asString();
        assertThat(alert.get("alertNumber").asString()).startsWith("QCA-");
        assertThat(alert.get("origin").asString()).isEqualTo("manual");
        assertThat(alert.get("stageName").asString()).isEqualTo("New");
        assertThat(alert.get("productId").asString()).as("copied from the check").isEqualTo(product);
        assertThat(alert.get("batchNumber").asString()).isEqualTo(check.get("batchNumber").asString());

        ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", stage("Confirmed")));
        JsonNode solved = ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", stage("Solved"),
                "comment", "supplier replaced the mould"));
        assertThat(solved.get("closedAt").isNull()).isFalse();
        assertThat(solved.get("stageHistory")).hasSize(3);
        assertThat(solved.get("stageHistory").get(2).get("comment").asString()).isEqualTo("supplier replaced the mould");
        assertThat(ok(200, "GET", "/quality-control/checks/" + checkId, null).get("status").asString())
                .as("closing an alert never changes its check").isEqualTo("failed");
        JsonNode reopened = ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId",
                stage("New")));
        assertThat(reopened.get("closedAt").isNull()).isTrue();
        assertThat(ok(200, "GET", "/quality-control/checks/" + checkId, null).get("alerts")).hasSize(1);

        JsonNode board = ok(200, "GET", "/quality-control/alerts/kanban?search=" + alert.get("alertNumber").asString(),
                null);
        assertThat(board.get("columns").get(0).get("stage").get("name").asString()).isEqualTo("New");
        assertThat(board.get("columns").get(0).get("alerts").findValuesAsString("id")).contains(id);
    }

    @Test
    @DisplayName("assignment changes are kept; tags filter; settings validate names")
    void assignmentAndTags() {
        JsonNode alert = ok(201, "POST", "/quality-control/alerts", Map.of("title", "Wrong label " + next()));
        String id = alert.get("id").asString();
        String tag = ok(201, "POST", "/quality-control/tags", Map.of("name", "Labelling " + sfx, "color", "#aa0000"))
                .get("id").asString();
        assertThat(as("POST", "/quality-control/tags", Map.of("name", "labelling " + sfx)).status()).isEqualTo(409);
        String cause = ok(201, "POST", "/quality-control/root-causes", Map.of("name", "Printer drift " + sfx))
                .get("id").asString();
        JsonNode updated = ok(200, "PATCH", "/quality-control/alerts/" + id, Map.of("teamId", team,
                "responsibleUserId", adminId, "tagIds", List.of(tag), "rootCauseId", cause, "correctiveAction",
                "Recalibrate the printer"));
        assertThat(updated.get("assignmentHistory")).hasSize(1);
        assertThat(updated.get("assignmentHistory").get(0).get("toTeamId").asString()).isEqualTo(team);
        assertThat(updated.get("tags").findValuesAsString("id")).containsExactly(tag);
        assertThat(updated.get("rootCauseName").asString()).isEqualTo("Printer drift " + sfx);
        ok(200, "PATCH", "/quality-control/alerts/" + id, Map.of("priority", 3));
        assertThat(ok(200, "GET", "/quality-control/alerts/" + id, null).get("assignmentHistory"))
                .as("no assignment change, no row").hasSize(1);
        ok(200, "PATCH", "/quality-control/alerts/" + id, Map.of("responsibleUserId", ""));
        assertThat(ok(200, "GET", "/quality-control/alerts/" + id, null).get("assignmentHistory")).hasSize(2);
        assertThat(ids(ok(200, "GET", "/quality-control/alerts?tagId=" + tag, null).get("data"))).containsExactly(id);

        String extra = ok(201, "POST", "/quality-control/alert-stages", Map.of("name", "On hold " + sfx)).get("id")
                .asString();
        ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", extra));
        assertThat(as("PATCH", "/quality-control/alert-stages/" + extra, Map.of("isActive", false)).body()
                .get("message").asString()).startsWith("Move the 1 alert(s) out of stage On hold");
        ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", stage("New")));
        ok(200, "PATCH", "/quality-control/alert-stages/" + extra, Map.of("isActive", false));
        assertThat(as("POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", extra)).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a failed generated check raises one automatic alert, even after a reset and a second failure")
    void autoAlertOncePerCheck() {
        String checkId = generatedCheck(true);
        ok(200, "POST", "/quality-control/checks/" + checkId + "/execute", Map.of("result", "failed"));
        assertThat(alertsOf(checkId)).isEqualTo(1);
        JsonNode auto = ok(200, "GET", "/quality-control/alerts?checkId=" + checkId, null).get("data").get(0);
        assertThat(auto.get("origin").asString()).isEqualTo("auto");
        assertThat(auto.get("alertNumber").asString()).startsWith("QCA-");
        assertThat(auto.get("teamId").asString()).isEqualTo(team);
        assertThat(auto.get("description").asString()).isEqualTo("Reject the pallet and call the shift lead.");

        ok(200, "POST", "/quality-control/checks/" + checkId + "/reset", Map.of("reason", "re-test"));
        ok(200, "POST", "/quality-control/checks/" + checkId + "/execute", Map.of("result", "failed"));
        assertThat(alertsOf(checkId)).as("one auto alert per check").isEqualTo(1);

        String quiet = generatedCheck(false);
        ok(200, "POST", "/quality-control/checks/" + quiet + "/execute", Map.of("result", "failed"));
        assertThat(alertsOf(quiet)).isZero();
    }

    @Test
    @DisplayName("escalation creates one NCR; repeating it returns the same NCR; resolving the alert leaves the NCR")
    void escalation() {
        String checkId = generatedCheck(true);
        ok(200, "POST", "/quality-control/checks/" + checkId + "/execute", Map.of("result", "failed", "failedQty", 4));
        String id = ok(200, "GET", "/quality-control/alerts?checkId=" + checkId, null).get("data").get(0).get("id")
                .asString();
        ok(200, "PATCH", "/quality-control/alerts/" + id, Map.of("correctiveAction", "Re-press the batch"));
        JsonNode first = ok(200, "POST", "/quality-control/alerts/" + id + "/escalate", Map.of("severity", "major"));
        assertThat(first.get("created").asBoolean()).isTrue();
        String ncrId = first.get("ncrId").asString();
        JsonNode again = ok(200, "POST", "/quality-control/alerts/" + id + "/escalate", Map.of());
        assertThat(again.get("created").asBoolean()).isFalse();
        assertThat(again.get("ncrId").asString()).isEqualTo(ncrId);
        Map<String, Object> ncr = jdbc.sql("SELECT title, source, severity, \"quantityAffected\", description, status"
                + " FROM \"QualityNcr\" WHERE id = ?").param(ncrId).query().singleRow();
        assertThat((String) ncr.get("title")).startsWith(first.get("alert").get("alertNumber").asString() + ": ");
        assertThat(ncr.get("source")).isEqualTo("in_process");
        assertThat(ncr.get("severity")).isEqualTo("major");
        assertThat((BigDecimal) ncr.get("quantityAffected")).isEqualByComparingTo("4");
        assertThat((String) ncr.get("description")).contains("Proposed corrective action: Re-press the batch");
        assertThat(jdbc.sql("SELECT count(*) FROM \"QualityNcr\" WHERE title LIKE ?").param(first.get("alert")
                .get("alertNumber").asString() + ":%").query(Long.class).single()).isEqualTo(1);

        ok(200, "POST", "/quality-control/alerts/" + id + "/move", Map.of("stageId", stage("Solved")));
        assertThat(jdbc.sql("SELECT status FROM \"QualityNcr\" WHERE id = ?").param(ncrId).query(String.class).single())
                .as("resolving the alert does not close the NCR").isEqualTo(ncr.get("status"));
        assertThat(ids(ok(200, "GET", "/quality-control/alerts?ncrId=" + ncrId, null).get("data"))).containsExactly(id);
    }

    @Test
    @DisplayName("the responsible person is notified of a new alert; aging alerts reach the alert scan")
    void notificationsAndAging() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "QcAlert-" + role,
                        new String[] {Permissions.QUALITY_CONTROL__READ}).update();
        String inspector = createUser(demoOrg, "qca-" + UUID.randomUUID() + "@demo.test", "Qc@12345678", role);
        JsonNode alert = ok(201, "POST", "/quality-control/alerts", Map.of("title", "Moisture high " + next(),
                "responsibleUserId", inspector));
        drainOutboxFully();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE ?")
                .params(inspector, "Quality alert " + alert.get("alertNumber").asString() + "%").query(Long.class)
                .single()).isEqualTo(1);
        assertThat(ok(200, "GET", "/notification-routing/events", null).findValuesAsString("event"))
                .contains("quality_control.alert.created", "quality_control.alert.assigned",
                        "quality_control.alert.stage_changed");

        List<AlertItem> items = TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId),
                () -> aging.collect(new AlertScan(demoOrg, LocalDate.now(), BigDecimal.ZERO)));
        assertThat(items).extracting(AlertItem::subjectKey).contains("qc_alert:" + alert.get("id").asString());
    }

    @Test
    @DisplayName("alert evidence needs alert; another tenant sees nothing; the matrix covers every route")
    void securityAndMatrix() {
        String id = ok(201, "POST", "/quality-control/alerts", Map.of("title", "Evidence " + next())).get("id")
                .asString();
        String operator = tokenWith(Permissions.QUALITY_CONTROL__READ, Permissions.QUALITY_CONTROL__CHECK,
                Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        assertThat(multipart("/dms/records/qc_alert/" + id + "/attachments", "photo.txt", "crack", operator).status())
                .isEqualTo(403);
        assertThat(call("POST", "/quality-control/alerts", Map.of("title", "x"), operator).status()).isEqualTo(403);
        String raiser = tokenWith(Permissions.QUALITY_CONTROL__READ, Permissions.QUALITY_CONTROL__ALERT,
                Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        assertThat(multipart("/dms/records/qc_alert/" + id + "/attachments", "photo.txt", "crack", raiser).status())
                .isEqualTo(201);
        assertThat(call("POST", "/quality-control/alert-stages", Map.of("name", "x"), raiser).status()).isEqualTo(403);

        assertThat(call("GET", "/quality-control/alerts/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/quality-control/alerts?pageSize=200", null, otherToken).body().get("data")
                .findValuesAsString("id")).doesNotContain(id);
        assertThat(assertAuthorizationMatrix("com.inventoryerp.qualitycontrol")).isEqualTo(47);
        assertThat(sweepGetRoutes("com.inventoryerp.qualitycontrol")).isEmpty();
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "QcAlerts-" + role, permissions).update();
        String email = "qca-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Qc@12345678", role);
        return login("DEMO", email, "Qc@12345678");
    }

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
