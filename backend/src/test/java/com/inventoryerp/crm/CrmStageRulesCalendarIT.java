package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CRM enhancements T2b: stage rules (required fields, automatic follow-up), the calendar range and the ICS feed. */
class CrmStageRulesCalendarIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())").params(customer, demoOrg, "SR-" + sfx,
                        "Stage Rules Customer " + sfx).update();
    }

    @Test
    @DisplayName("a stage with required fields refuses a deal without them; entering it creates one follow-up")
    void stageRules() {
        assertThat(call("POST", "/crm/config/stages", Map.of("name", "Bad " + sfx, "legacyStage", "qualified",
                "requiredFields", List.of("budget")), adminToken).status()).isEqualTo(400);
        String stage = ok(201, "POST", "/crm/config/stages", Map.of("name", "Proposal " + sfx, "probability", 40,
                "legacyStage", "proposal", "requiredFields", List.of("amount", "expectedClose"), "followUpType", "call",
                "followUpTitle", "Walk through proposal " + sfx, "followUpDays", 2)).get("id").asString();
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Rules deal " + sfx, "partnerId", customer))
                .get("id").asString();
        try {
            JsonNode refused = call("POST", "/crm/opportunities/" + deal + "/move", Map.of("stageId", stage),
                    adminToken).body();
            assertThat(refused.get("message").asString()).isEqualTo("Fill in Expected revenue, Expected closing before"
                    + " moving to Proposal " + sfx);

            ok(200, "PATCH", "/crm/opportunities/" + deal, Map.of("amount", 900, "expectedClose", LocalDate.now()
                    .plusDays(20).toString()));
            ok(200, "POST", "/crm/opportunities/" + deal + "/move", Map.of("stageId", stage));
            Map<String, Object> f = jdbc.sql("SELECT type::text AS type, status::text AS status, \"dueAt\","
                    + " \"assignedToId\" FROM \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(deal,
                            "Walk through proposal " + sfx).query().singleRow();
            assertThat(f.get("type")).isEqualTo("call");
            assertThat(f.get("status")).isEqualTo("todo");
            assertThat(f.get("assignedToId")).isNotNull();
            LocalDate due = ((java.sql.Timestamp) f.get("dueAt")).toLocalDateTime().atZone(java.time.ZoneOffset.UTC)
                    .withZoneSameInstant(BusinessZone.get()).toLocalDate();
            assertThat(due).isEqualTo(LocalDate.now(BusinessZone.get()).plusDays(2));

            // Leave and come back: the open follow-up is not duplicated.
            String other = jdbc.sql("SELECT id FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" AND NOT"
                    + " \"isWon\" AND id <> ? AND cardinality(\"requiredFields\") = 0 ORDER BY sequence LIMIT 1")
                    .params(demoOrg, stage).query(String.class).single();
            ok(200, "POST", "/crm/opportunities/" + deal + "/move", Map.of("stageId", other));
            ok(200, "POST", "/crm/opportunities/" + deal + "/move", Map.of("stageId", stage));
            assertThat(jdbc.sql("SELECT count(*) FROM \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(deal,
                    "Walk through proposal " + sfx).query(Long.class).single()).isEqualTo(1);

            JsonNode listed = null;
            for (JsonNode s : ok(200, "GET", "/crm/config/stages", null)) {
                if (s.get("id").asString().equals(stage)) {
                    listed = s;
                }
            }
            assertThat(listed.get("requiredFields").toString()).contains("amount", "expectedClose");
            assertThat(listed.get("followUpDays").asInt()).isEqualTo(2);
        } finally {
            jdbc.sql("UPDATE \"Deal\" SET \"deletedAt\" = now() WHERE id = ?").param(deal).update();
            jdbc.sql("UPDATE \"CrmStage\" SET \"isActive\" = false WHERE id = ?").param(stage).update();
        }
    }

    @Test
    @DisplayName("the won stage's required fields apply to the Won button")
    void wonStageRules() {
        String won = jdbc.sql("SELECT id FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" AND \"isWon\""
                + " ORDER BY sequence DESC LIMIT 1").param(demoOrg).query(String.class).single();
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Won rules " + sfx, "partnerId", customer,
                "amount", 10)).get("id").asString();
        jdbc.sql("UPDATE \"Deal\" SET phone = NULL WHERE id = ?").param(deal).update();
        jdbc.sql("UPDATE \"CrmStage\" SET \"requiredFields\" = ARRAY['phone'] WHERE id = ?").param(won).update();
        try {
            Res r = call("POST", "/crm/opportunities/" + deal + "/won", null, adminToken);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).startsWith("Fill in Phone before moving to");
            ok(200, "PATCH", "/crm/opportunities/" + deal, Map.of("phone", "+256 700 111222"));
            assertThat(ok(200, "POST", "/crm/opportunities/" + deal + "/won", null).get("status").asString())
                    .isEqualTo("won");
        } finally {
            jdbc.sql("UPDATE \"CrmStage\" SET \"requiredFields\" = ARRAY[]::TEXT[] WHERE id = ?").param(won).update();
        }
    }

    @Test
    @DisplayName("the planner range returns activities due between two dates and refuses a bad range")
    void plannerRange() {
        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String id = ok(201, "POST", "/crm/planner", Map.of("type", "meeting", "title", "Range meeting " + sfx, "dueAt",
                tomorrow)).get("id").asString();
        LocalDate today = LocalDate.now(BusinessZone.get());
        JsonNode r = ok(200, "GET", "/crm/planner?state=range&from=" + today.minusDays(1) + "&to=" + today.plusDays(7),
                null);
        List<String> ids = new ArrayList<>();
        r.get("data").forEach(a -> ids.add(a.get("id").asString()));
        assertThat(ids).contains(id);
        assertThat(call("GET", "/crm/planner?state=range&from=" + today, null, adminToken).status()).isEqualTo(400);
        assertThat(call("GET", "/crm/planner?state=range&from=" + today + "&to=" + today.plusDays(90), null, adminToken)
                .status()).isEqualTo(400);
    }

    HttpResponse<String> fetch(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the ICS feed serves the owner's activities by secret token; a new link or turning it off kills the old")
    void icsFeed() throws Exception {
        String due = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String act = ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", "Feed call, " + sfx + "; agenda",
                "dueAt", due, "duration", 45)).get("id").asString();
        ok(200, "DELETE", "/crm/planner/calendar-feed", null);
        assertThat(ok(200, "GET", "/crm/planner/calendar-feed", null).get("active").asBoolean()).isFalse();

        String path = ok(201, "POST", "/crm/planner/calendar-feed", null).get("path").asString();
        assertThat(path).startsWith("/api/v1/public/crm/calendar/").endsWith(".ics");
        HttpResponse<String> feed = fetch(path);
        assertThat(feed.statusCode()).isEqualTo(200);
        assertThat(feed.headers().firstValue("Content-Type").orElse("")).startsWith("text/calendar");
        assertThat(feed.body()).startsWith("BEGIN:VCALENDAR\r\n").contains("UID:" + act + "@crm.inventory-erp")
                .contains("SUMMARY:Call: Feed call\\, " + sfx + "\\; agenda").endsWith("END:VCALENDAR\r\n");
        assertThat(ok(200, "GET", "/crm/planner/calendar-feed", null).get("lastUsedAt").isNull()).isFalse();

        String second = ok(201, "POST", "/crm/planner/calendar-feed", null).get("path").asString();
        assertThat(fetch(path).statusCode()).as("old link revoked").isEqualTo(404);
        assertThat(fetch(second).statusCode()).isEqualTo(200);
        ok(200, "DELETE", "/crm/planner/calendar-feed", null);
        assertThat(fetch(second).statusCode()).as("turned off").isEqualTo(404);
        assertThat(fetch("/api/v1/public/crm/calendar/short.ics").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("ICS lines are folded at 75 octets without splitting a UTF-8 character")
    void icsFolding() {
        StringBuilder out = new StringBuilder();
        String longText = "SUMMARY:" + "Café meeting ".repeat(12);
        com.inventoryerp.crm.application.CrmCalendarFeedService.line(out, longText);
        for (String l : out.toString().split("\r\n")) {
            assertThat(l.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(out.toString().replace("\r\n ", "").trim()).isEqualTo(longText.trim());
    }
}
