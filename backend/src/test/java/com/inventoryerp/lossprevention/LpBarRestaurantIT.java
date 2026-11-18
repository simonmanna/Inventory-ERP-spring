package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.lossprevention.web.LossPreventionMeasurementController;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * LP3 and LP4 end to end: pour meters (routes, messages, the agent's configuration, readings checked against the
 * device at ingest, a B04 case through a replay) and the kitchen flow (a K01 case through a replay). No meter, camera
 * or kitchen display runs here: the events are what they would send.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpBarRestaurantIT extends LpVisionSupport {

    /** A past day nobody else uses: DEMO is shared between suites. */
    final LocalDate day = LocalDate.of(2015, 1, 1).plusDays((long) (Math.random() * 300));
    static String agentId;
    static String otherAgentId;
    static String deviceId;
    static String product;
    static String token;

    Map<String, Object> event(String type, String time, String device, String level, String source,
            Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", "lp3-" + tag);
        e.put("sourceEventId", "e-" + UUID.randomUUID());
        e.put("branchId", branch);
        if (device != null) {
            e.put("deviceId", device);
        }
        e.put("occurredAt", day + "T" + time + "Z");
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.put("payload", payload);
        e.put("evidenceLevel", level);
        e.put("evidenceSource", source);
        e.put("observationStatus", "observed");
        return e;
    }

    Map<String, Object> pour(String time, double ml) {
        return event("pour.measured", time, deviceId, "item", "sensor", Map.of("volumeMl", ml));
    }

    JsonNode replay(String engine) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + adminToken);
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/loss-prevention/runs/replay", Map.of("engine", engine, "branchId", branch, "from",
                day.toString(), "to", day.toString()), headers);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    @Test
    @Order(1)
    @DisplayName("a pour meter is created, checked, listed in its agent's configuration, changed and retired")
    void devices() throws Exception {
        Enrolled box = createAndEnrol("Bar box " + tag);
        agentId = box.agentId();
        token = box.token();
        otherAgentId = createAndEnrol("Other box " + tag).agentId();
        product = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? ORDER BY id LIMIT 1").param(demoOrg)
                .query(String.class).single();

        Res kind = as("POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name", "Spout",
                "kind", "scale", "externalId", "SP-" + tag));
        assertThat(kind.status()).isEqualTo(400);
        assertThat(kind.body().get("message").asString())
                .isEqualTo("kind must be one of: pour_spout, flow_meter, dispenser");
        Res measure = as("POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name", "Spout",
                "kind", "pour_spout", "externalId", "SP-" + tag, "standardPourMl", 0));
        assertThat(measure.body().get("message").asString())
                .isEqualTo("standardPourMl must be above 0 and at most 5000");
        Res noProduct = as("POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name", "Spout",
                "kind", "pour_spout", "externalId", "SP-" + tag, "productId", "missing"));
        assertThat(noProduct.body().get("message").asString()).isEqualTo("Product not found");

        JsonNode created = ok(201, "POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name",
                "Vodka spout " + tag, "kind", "pour_spout", "externalId", "SP-" + tag, "agentId", agentId,
                "productId", product, "station", "Main bar", "standardPourMl", 25));
        deviceId = created.get("id").asString();
        assertThat(created.get("agentName").asString()).isEqualTo("Bar box " + tag);
        Res twice = as("POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name", "Again",
                "kind", "pour_spout", "externalId", "SP-" + tag));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.body().get("message").asString())
                .isEqualTo("Another measurement device at this branch already uses this externalId");

        agentToken = token;
        JsonNode config = agentOk(200, "GET", "/loss-prevention/agents/config", null);
        assertThat(config.get("measurementDevices")).hasSize(1);
        assertThat(config.get("measurementDevices").get(0).get("externalId").asString()).isEqualTo("SP-" + tag);
        assertThat(config.get("measurementDevices").get(0).get("productId").asString()).isEqualTo(product);

        JsonNode list = ok(200, "GET", "/loss-prevention/measurement-devices?branchId=" + branch, null);
        List<String> ids = new java.util.ArrayList<>();
        list.forEach(d -> ids.add(d.get("id").asString()));
        assertThat(ids).contains(deviceId);
        Map<String, Object> rename = new LinkedHashMap<>();
        rename.put("name", "Vodka spout " + tag);
        rename.put("agentId", agentId);
        rename.put("productId", product);
        rename.put("station", "Main bar");
        rename.put("standardPourMl", 25);
        assertThat(ok(200, "PUT", "/loss-prevention/measurement-devices/" + deviceId, rename).get("station")
                .asString()).isEqualTo("Main bar");

        JsonNode spare = ok(201, "POST", "/loss-prevention/measurement-devices", Map.of("branchId", branch, "name",
                "Spare " + tag, "kind", "flow_meter", "externalId", "FM-" + tag));
        String spareId = spare.get("id").asString();
        assertThat(ok(201, "POST", "/loss-prevention/measurement-devices/" + spareId + "/retire", null)
                .get("retiredAt").isNull()).isFalse();
        assertThat(as("POST", "/loss-prevention/measurement-devices/" + spareId + "/retire", null).body()
                .get("message").asString()).isEqualTo("The measurement device is already retired");
        assertThat(as("PUT", "/loss-prevention/measurement-devices/" + spareId, Map.of("name", "x")).body()
                .get("message").asString()).isEqualTo("A retired measurement device cannot be changed");
    }

    @Test
    @Order(2)
    @DisplayName("readings are checked against their device; another agent's device is refused")
    void readings() throws Exception {
        agentToken = token;
        Map<String, Object> good = pour("20:00:00", 600);
        Map<String, Object> unknown = event("pour.measured", "20:00:01", UUID.randomUUID().toString(), "item",
                "sensor", Map.of("volumeMl", 25));
        Map<String, Object> zero = event("pour.measured", "20:00:02", deviceId, "item", "sensor",
                Map.of("volumeMl", 0));
        Map<String, Object> asCamera = event("pour.measured", "20:00:03", deviceId, "zone", "camera",
                Map.of("volumeMl", 25));
        JsonNode stored = agentOk(201, "POST", "/loss-prevention/events", Map.of("events", List.of(good, unknown, zero,
                asCamera)));
        assertThat(stored.get("accepted").asInt()).as(stored.toString()).isEqualTo(1);
        assertThat(stored.get("results").get(1).get("reason").asString()).isEqualTo("Measurement device not found");
        assertThat(stored.get("results").get(2).get("reason").asString())
                .isEqualTo("payload.volumeMl must be a number above 0 and at most 5000");
        assertThat(stored.get("results").get(3).get("reason").asString())
                .isEqualTo("evidenceLevel must be item for type 'pour.measured'");

        agentToken = clientTokenOf(otherAgentId);
        JsonNode refused = agentOk(201, "POST", "/loss-prevention/events", Map.of("events", List.of(
                pour("20:05:00", 25))));
        assertThat(refused.get("results").get(0).get("reason").asString())
                .isEqualTo("Measurement device not found for this agent");
    }

    @Test
    @Order(3)
    @DisplayName("B04: a meter poured more than the day's sales drew, found by a replay and opened as a case")
    void pourCase() {
        JsonNode run = replay("bar_pour");
        assertThat(run.get("findings").asInt()).as(run.toString()).isEqualTo(1);
        JsonNode cases = ok(200, "GET", "/loss-prevention/cases?ruleCode=B04&branchId=" + branch + "&from=" + day
                + "&to=" + day, null);
        assertThat(cases.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(cases.get("data").get(0).get("subjectId").asString()).isEqualTo(product);
    }

    @Test
    @Order(4)
    @DisplayName("K01: food served from the kitchen with no sale for its order becomes a case")
    void kitchenCase() {
        String order = "order-" + tag;
        String ticket = "ticket-" + tag;
        Map<String, Object> created = event("kitchen.ticket_created", "12:00:00", null, "ticket", "kds",
                Map.of("station", "Hot", "itemCount", 2));
        created.put("orderId", order);
        created.put("ticketId", ticket);
        Map<String, Object> served = event("kitchen.stage", "12:20:00", null, "ticket", "kds", Map.of("stage",
                "dispatched", "station", "Hot"));
        served.put("orderId", order);
        served.put("ticketId", ticket);
        served.put("observationStatus", "inferred");
        JsonNode stored = ok(201, "POST", "/loss-prevention/events", Map.of("events", List.of(created, served)));
        assertThat(stored.get("accepted").asInt()).as(stored.toString()).isEqualTo(2);

        JsonNode run = replay("kitchen_flow");
        assertThat(run.get("findings").asInt()).as(run.toString()).isEqualTo(1);
        JsonNode cases = ok(200, "GET", "/loss-prevention/cases?ruleCode=K01&branchId=" + branch + "&from=" + day
                + "&to=" + day, null);
        assertThat(cases.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(cases.get("data").get(0).get("subjectId").asString()).isEqualTo(ticket);
    }

    @Test
    @Order(5)
    @DisplayName("another organization sees none of it; every route needs its permission")
    void isolationAndAuthorization() {
        assertThat(call("GET", "/loss-prevention/measurement-devices/" + deviceId, null, otherToken).status())
                .isEqualTo(404);
        assertThat(assertAuthorizationMatrix(LossPreventionMeasurementController.class)).isEqualTo(5);
    }

    /** A fresh token for an agent enrolled earlier: a new one-time code, enrolled again. */
    String clientTokenOf(String agent) throws Exception {
        JsonNode code = ok(201, "POST", "/loss-prevention/agents/" + agent + "/enrolment-code", null);
        return enrol(code.get("enrolmentCode").asString()).token();
    }
}
