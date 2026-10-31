package com.inventoryerp.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.alerts.web.AlertController;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Alert rules, one alert per subject and period, the exception report, tenancy, authorization. */
class AlertsIT extends ApiTestSupport {

    static final String TYPE = "expiring_document";

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A quarry site whose licence runs out in {@code days}: the simplest subject to raise. */
    String site(String org, int days) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"QuarrySite\" (id, \"organizationId\", code, name, \"licenceNumber\","
                + " \"licenceExpiresOn\") VALUES (?, ?, ?, ?, 'ML-1', ?)")
                .params(id, org, "AL-" + suffix(), "Alert site", java.sql.Date.valueOf(LocalDate.now().plusDays(days)))
                .update();
        return id;
    }

    long events(String org, String subjectLike) {
        return jdbc.sql("SELECT count(*) FROM \"AlertEvent\" WHERE \"organizationId\" = ? AND \"subjectKey\" LIKE ?")
                .params(org, subjectLike).query(Long.class).single();
    }

    Map<String, Object> rule(boolean enabled, Object threshold, List<String> channels) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("enabled", enabled);
        body.put("threshold", threshold);
        body.put("repeatEvery", "weekly");
        body.put("channels", channels);
        return body;
    }

    @Test
    @DisplayName("every type is listed with its defaults until an organization changes it")
    void catalog() {
        JsonNode rules = ok(200, "GET", "/alerts/rules", null);
        assertThat(rules.size()).isEqualTo(9);
        JsonNode updated = ok(200, "PUT", "/alerts/rules/credit_limit_breach", rule(true, 85, List.of("in_app")));
        assertThat(updated.get("threshold").asDouble()).isEqualTo(85d);
        assertThat(updated.get("customized").asBoolean()).isTrue();
        // Another organization keeps the default.
        Res other = call("GET", "/alerts/rules", null, otherToken);
        for (JsonNode r : other.body()) {
            if ("credit_limit_breach".equals(r.get("alertType").asString())) {
                assertThat(r.get("threshold").asDouble()).isEqualTo(100d);
                assertThat(r.get("customized").asBoolean()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a rule is validated: type, channel, repeat and permission")
    void validation() {
        assertThat(as("PUT", "/alerts/rules/nope", rule(true, 1, List.of("email"))).status()).isEqualTo(404);
        assertThat(as("PUT", "/alerts/rules/" + TYPE, rule(true, 1, List.of("pigeon"))).status()).isEqualTo(400);
        assertThat(as("PUT", "/alerts/rules/" + TYPE, rule(true, -1, List.of("email"))).status()).isEqualTo(400);
        Map<String, Object> badPermission = rule(true, 1, List.of("email"));
        badPermission.put("recipientPermission", "no:such");
        assertThat(as("PUT", "/alerts/rules/" + TYPE, badPermission).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a subject alerts once in its period, however often the scan runs; a change of state alerts again")
    void oncePerPeriod() {
        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(true, 30, List.of("in_app")));
        String site = site(demoOrg, 10);
        long bells = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category ="
                + " 'alerts'").param(demoOrg).query(Long.class).single();

        ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(events(demoOrg, "site:" + site + ":%")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category ="
                + " 'alerts'").param(demoOrg).query(Long.class).single()).isGreaterThan(bells);

        long after = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category ="
                + " 'alerts'").param(demoOrg).query(Long.class).single();
        JsonNode again = ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(again.get("raised").get(TYPE).asInt()).isZero();
        assertThat(events(demoOrg, "site:" + site + ":%")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category ="
                + " 'alerts'").param(demoOrg).query(Long.class).single()).isEqualTo(after);

        jdbc.sql("UPDATE \"QuarrySite\" SET \"licenceExpiresOn\" = ? WHERE id = ?")
                .params(java.sql.Date.valueOf(LocalDate.now().minusDays(1)), site).update();
        ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(events(demoOrg, "site:" + site + ":%")).isEqualTo(2);
    }

    @Test
    @DisplayName("a threshold keeps out what is further away, and a disabled type does not run")
    void thresholdAndDisable() {
        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(true, 5, List.of("in_app")));
        String far = site(demoOrg, 20);
        ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(events(demoOrg, "site:" + far + ":%")).isZero();

        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(false, 30, List.of("in_app")));
        JsonNode run = ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(run.get("raised").has(TYPE)).isFalse();
        assertThat(events(demoOrg, "site:" + far + ":%")).isZero();
        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(true, 30, List.of("in_app")));
    }

    @Test
    @DisplayName("one digest per type: the alert.raised event goes out once for several subjects")
    void digest() {
        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(true, 30, List.of("in_app", "whatsapp")));
        site(demoOrg, 3);
        site(demoOrg, 4);
        long before = jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"organizationId\" = ? AND \"eventName\" ="
                + " 'alert.raised'").param(demoOrg).query(Long.class).single();
        JsonNode run = ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        assertThat(run.get("raised").get(TYPE).asInt()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"organizationId\" = ? AND \"eventName\" ="
                + " 'alert.raised'").param(demoOrg).query(Long.class).single()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("the list, the report and the CSV show one organization; acknowledging stays inside it")
    void tenancyAndReport() throws Exception {
        ok(200, "PUT", "/alerts/rules/" + TYPE, rule(true, 30, List.of("in_app")));
        String site = site(demoOrg, 2);
        ok(201, "POST", "/alerts/run", Map.of("alertType", TYPE));
        JsonNode list = ok(200, "GET", "/alerts/events?alertType=" + TYPE + "&status=open&pageSize=200", null);
        assertThat(list.get("meta").get("total").asInt()).isPositive();
        String id = jdbc.sql("SELECT id FROM \"AlertEvent\" WHERE \"organizationId\" = ? AND \"subjectKey\" LIKE ?")
                .params(demoOrg, "site:" + site + ":%").query(String.class).single();

        assertThat(call("GET", "/alerts/events", null, otherToken).body().get("meta").get("total").asInt()).isZero();
        assertThat(call("POST", "/alerts/events/" + id + "/acknowledge", null, otherToken).status()).isEqualTo(404);

        JsonNode acked = ok(201, "POST", "/alerts/events/" + id + "/acknowledge", null);
        assertThat(acked.get("status").asString()).isEqualTo("acknowledged");

        JsonNode report = ok(200, "GET", "/alerts/exception-report", null);
        assertThat(report.get("rows").size()).isPositive();

        HttpResponse<String> csv = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/alerts/events/export.csv?alertType=" + TYPE)).header("Authorization", "Bearer " + adminToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).contains("alerts.csv");
        assertThat(csv.body()).contains("raisedAt,alertType,severity").contains("licence");
    }

    @Test
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(AlertController.class)).isEqualTo(7);
    }
}
