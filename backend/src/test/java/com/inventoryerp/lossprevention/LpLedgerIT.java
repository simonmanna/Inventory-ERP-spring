package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.lossprevention.web.LossPreventionEventController;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The event ledger: idempotent ingest in any order, per-event outcomes, append-only rows, tenancy, authorization. */
class LpLedgerIT extends ApiTestSupport {

    /** Unique per run: the DEMO organization and its ledger are shared with earlier runs. */
    final String source = "it-" + UUID.randomUUID().toString().substring(0, 8);

    String branchOf(String org) {
        return jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY id"
                + " LIMIT 1").param(org).query(String.class).optional().orElse(null);
    }

    Map<String, Object> event(String sourceEventId, String occurredAt) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", source);
        e.put("sourceEventId", sourceEventId);
        e.put("branchId", branchOf(demoOrg));
        e.put("deviceId", "till-1");
        e.put("actorId", "cashier-1");
        e.put("transactionId", "txn-" + sourceEventId);
        e.put("occurredAt", occurredAt);
        e.put("schemaVersion", 1);
        e.put("type", "sale.completed");
        e.put("payload", Map.of("total", "12.50"));
        e.put("evidenceLevel", "order");
        e.put("evidenceSource", "pos");
        e.put("observationStatus", "observed");
        return e;
    }

    static Map<String, Object> batch(List<Map<String, Object>> events) {
        return Map.of("events", events);
    }

    long stored(String org) {
        return jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE \"organizationId\" = ? AND source = ?")
                .params(org, source).query(Long.class).single();
    }

    @Test
    @DisplayName("a batch sent twice is stored once: the second answer is all duplicates")
    void idempotent() {
        List<Map<String, Object>> events = List.of(event("a1", "2026-11-10T09:00:00Z"),
                event("a2", "2026-11-10T09:01:00Z"));
        JsonNode first = ok(201, "POST", "/loss-prevention/events", batch(events));
        assertThat(first.get("accepted").asInt()).isEqualTo(2);
        assertThat(first.get("results").get(0).get("status").asString()).isEqualTo("accepted");
        assertThat(first.get("results").get(0).get("seq").asLong()).isPositive();

        JsonNode second = ok(201, "POST", "/loss-prevention/events", batch(events));
        assertThat(second.get("accepted").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(2);
        assertThat(second.get("results").get(1).get("status").asString()).isEqualTo("duplicate");

        // The same source id under a new eventId and a different payload: still the first copy.
        Map<String, Object> resent = event("a1", "2026-11-10T09:00:00Z");
        resent.put("payload", Map.of("total", "99.00"));
        assertThat(ok(201, "POST", "/loss-prevention/events", batch(List.of(resent))).get("duplicates").asInt())
                .isEqualTo(1);
        JsonNode kept = ok(200, "GET", "/loss-prevention/events?source=" + source + "&transactionId=txn-a1", null);
        assertThat(kept.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(kept.get("data").get(0).get("payload").get("total").asString()).isEqualTo("12.50");
    }

    @Test
    @DisplayName("the same event twice in one batch is stored once")
    void duplicateInsideBatch() {
        Map<String, Object> e = event("b1", "2026-11-10T10:00:00Z");
        JsonNode r = ok(201, "POST", "/loss-prevention/events", batch(List.of(e, e)));
        assertThat(r.get("accepted").asInt()).isEqualTo(1);
        assertThat(r.get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("events arrive in any order and are listed by when they happened; old events are accepted")
    void outOfOrder() {
        JsonNode r = ok(201, "POST", "/loss-prevention/events", batch(List.of(event("c3", "2026-11-11T12:00:00Z"),
                event("c1", "2026-11-11T08:00:00Z"), event("c2", "2025-03-01T10:00:00Z"))));
        assertThat(r.get("accepted").asInt()).isEqualTo(3);
        JsonNode list = ok(200, "GET", "/loss-prevention/events?source=" + source + "&from=2026-11-11&to=2026-11-11",
                null);
        List<String> ids = new ArrayList<>();
        list.get("data").forEach(n -> ids.add(n.get("sourceEventId").asString()));
        assertThat(ids).containsExactly("c3", "c1");
        // Ledger order is arrival order, whatever the event time.
        assertThat(list.get("data").get(0).get("seq").asLong()).isLessThan(list.get("data").get(1).get("seq").asLong());
        assertThat(list.get("data").get(0).get("businessDate").asString()).isEqualTo("2026-11-11");
    }

    @Test
    @DisplayName("a bad event is refused with its reason and does not fail the rest of its batch")
    void rejectedOneByOne() {
        Map<String, Object> unknownType = event("d1", "2026-11-12T09:00:00Z");
        unknownType.put("type", "sale.invented");
        Map<String, Object> unknownBranch = event("d2", "2026-11-12T09:00:00Z");
        unknownBranch.put("branchId", UUID.randomUUID().toString());
        Map<String, Object> good = event("d3", "2026-11-12T09:00:00Z");
        Map<String, Object> reusedId = event("d4", "2026-11-12T09:00:00Z");
        reusedId.put("eventId", good.get("eventId"));

        JsonNode r = ok(201, "POST", "/loss-prevention/events", batch(List.of(unknownType, unknownBranch, good,
                reusedId)));
        assertThat(r.get("accepted").asInt()).isEqualTo(1);
        assertThat(r.get("rejected").asInt()).isEqualTo(3);
        assertThat(r.get("results").get(0).get("reason").asString()).isEqualTo("Unknown event type 'sale.invented'");
        assertThat(r.get("results").get(1).get("reason").asString()).isEqualTo("Branch not found");
        assertThat(r.get("results").get(2).get("status").asString()).isEqualTo("accepted");
        assertThat(r.get("results").get(3).get("reason").asString())
                .isEqualTo("eventId is already used by another event");
    }

    @Test
    @DisplayName("an outside sender names the branch when the organization has branches")
    void branchRequired() {
        Map<String, Object> e = event("e1", "2026-11-12T09:00:00Z");
        e.remove("branchId");
        JsonNode r = ok(201, "POST", "/loss-prevention/events", batch(List.of(e)));
        if (branchOf(demoOrg) == null) {
            assertThat(r.get("accepted").asInt()).isEqualTo(1);
        } else {
            assertThat(r.get("results").get(0).get("reason").asString()).isEqualTo("branchId is required");
        }
    }

    @Test
    @DisplayName("the batch itself is validated: 1 to 500 events, no unknown field, no organization in the body")
    void batchShape() {
        assertThat(as("POST", "/loss-prevention/events", Map.of("events", List.of())).status()).isEqualTo(400);
        assertThat(as("POST", "/loss-prevention/events", Map.of()).status()).isEqualTo(400);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(event("f" + i, "2026-11-12T09:00:00Z"));
        }
        Res tooMany = as("POST", "/loss-prevention/events", batch(many));
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(validationMessages(tooMany)).contains("events must hold between 1 and 500 events");
        Map<String, Object> withOrg = event("f-org", "2026-11-12T09:00:00Z");
        withOrg.put("organizationId", otherOrg);
        assertThat(as("POST", "/loss-prevention/events", batch(List.of(withOrg))).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE source = ? AND \"sourceEventId\" LIKE 'f%'")
                .param(source).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a stored event cannot be changed")
    void appendOnly() {
        JsonNode posted = ok(201, "POST", "/loss-prevention/events", batch(List.of(event("g1",
                "2026-11-12T09:00:00Z"))));
        assertThat(posted.get("accepted").asInt()).as(posted.toString()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"LpEvent\" SET type = 'refund.issued' WHERE source = ? AND"
                + " \"sourceEventId\" = 'g1'").param(source).update()).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("each organization has its own ledger: same source ids, separate rows, no cross reading")
    void tenancy() {
        Map<String, Object> mine = event("h1", "2026-11-13T09:00:00Z");
        JsonNode posted = ok(201, "POST", "/loss-prevention/events", batch(List.of(mine)));
        assertThat(posted.get("accepted").asInt()).as(posted.toString()).isEqualTo(1);
        String rowId = jdbc.sql("SELECT id FROM \"LpEvent\" WHERE \"organizationId\" = ? AND source = ? AND"
                + " \"sourceEventId\" = 'h1'").params(demoOrg, source).query(String.class).single();

        Map<String, Object> theirs = event("h1", "2026-11-13T09:00:00Z");
        theirs.put("eventId", mine.get("eventId"));
        theirs.put("branchId", branchOf(otherOrg));
        Res other = call("POST", "/loss-prevention/events", batch(List.of(theirs)), otherToken);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(201);
        assertThat(other.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(stored(otherOrg)).isEqualTo(1);

        JsonNode seen = call("GET", "/loss-prevention/events?source=" + source, null, otherToken).body();
        assertThat(seen.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(seen.get("data").get(0).get("organizationId").asString()).isEqualTo(otherOrg);
        assertThat(call("GET", "/loss-prevention/events/" + rowId, null, otherToken).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/loss-prevention/events/" + rowId, null).get("sourceEventId").asString())
                .isEqualTo("h1");

        // A branch of another organization is not a branch of this one.
        Map<String, Object> foreign = event("h2", "2026-11-13T09:00:00Z");
        String otherBranch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, 'Other"
                + " branch', now())").params(otherBranch, otherOrg, "OB-" + otherBranch.substring(0, 6)).update();
        foreign.put("branchId", otherBranch);
        assertThat(ok(201, "POST", "/loss-prevention/events", batch(List.of(foreign))).get("results").get(0)
                .get("reason").asString()).isEqualTo("Branch not found");
    }

    @Test
    @DisplayName("filters: correlation ids, type, time; a bad date is a 400")
    void filters() {
        Map<String, Object> voided = event("i1", "2026-11-14T09:00:00Z");
        voided.put("type", "sale.line_voided");
        voided.put("evidenceLevel", "item");
        voided.put("orderId", "order-i");
        voided.put("lineId", "line-i");
        Map<String, Object> stage = event("i2", "2026-11-14T09:05:00Z");
        stage.put("type", "kitchen.stage");
        stage.put("evidenceLevel", "ticket");
        stage.put("evidenceSource", "kds");
        stage.put("observationStatus", "inferred");
        stage.put("orderId", "order-i");
        stage.put("ticketId", "ticket-i");
        stage.put("payload", Map.of("stage", "prepared"));
        assertThat(ok(201, "POST", "/loss-prevention/events", batch(List.of(voided, stage))).get("accepted").asInt())
                .isEqualTo(2);

        String base = "/loss-prevention/events?source=" + source;
        assertThat(ok(200, "GET", base + "&orderId=order-i", null).get("meta").get("total").asInt()).isEqualTo(2);
        assertThat(ok(200, "GET", base + "&ticketId=ticket-i", null).get("meta").get("total").asInt()).isEqualTo(1);
        JsonNode one = ok(200, "GET", base + "&type=kitchen.stage&lineId=", null).get("data").get(0);
        assertThat(one.get("observationStatus").asString()).isEqualTo("inferred");
        assertThat(one.get("payload").get("stage").asString()).isEqualTo("prepared");
        assertThat(ok(200, "GET", base + "&from=2026-11-14T09:01:00Z&to=2026-11-14T09:06:00Z", null).get("meta")
                .get("total").asInt()).isEqualTo(1);
        assertThat(as("GET", base + "&from=last-week", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("an API client with the lp:ingest scope posts events and can do nothing else here")
    void apiClient() throws Exception {
        JsonNode created = ok(201, "POST", "/api-clients", Map.of("name", "POS feed " + source, "scopes",
                List.of("lp:ingest")));
        String basic = Base64.getEncoder().encodeToString((created.get("clientId").asString() + ":"
                + created.get("clientSecret").asString()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> token = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/oauth/token")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic).POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&scope=" + URLEncoder.encode("lp:ingest",
                                StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        String clientToken = mapper.readTree(token.body()).get("access_token").asString();

        Res posted = call("POST", "/loss-prevention/events", batch(List.of(event("j1", "2026-11-15T09:00:00Z"))),
                clientToken);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(201);
        assertThat(posted.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(call("GET", "/loss-prevention/events", null, clientToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(LossPreventionEventController.class)).isEqualTo(3);
    }
}
