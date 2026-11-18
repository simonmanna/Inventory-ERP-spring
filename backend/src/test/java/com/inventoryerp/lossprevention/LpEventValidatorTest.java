package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.lossprevention.application.LpCommands.EventInput;
import com.inventoryerp.lossprevention.application.LpEventValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The canonical envelope, check by check. */
class LpEventValidatorTest {

    static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    /** A valid sale event; each test changes one field. */
    static EventInput event(String type, String occurredAt, JsonNode payload, String level, String evidenceSource,
            String status, BigDecimal confidence) {
        return new EventInput(UUID.randomUUID().toString(), "erp-pos", "sale-1", null, "till-1", "user-1", "inv-1",
                "order-1", null, null, "session-1", "2026-11-17", occurredAt, 1, type, payload, level, evidenceSource,
                status, confidence);
    }

    static EventInput valid() {
        return event("sale.completed", "2026-11-17T09:15:20Z", json("{}"), "order", "pos", "observed", null);
    }

    static String reject(EventInput e) {
        return LpEventValidator.reject(e, NOW);
    }

    @Test
    @DisplayName("a complete event passes, with or without an offset, a business date and a confidence")
    void accepts() {
        assertThat(reject(valid())).isNull();
        assertThat(reject(event("sale.completed", "2026-11-17T11:15:20+02:00", json("{\"total\":\"12.50\"}"), "order",
                "pos", "observed", new BigDecimal("0.85")))).isNull();
        EventInput v = valid();
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, null,
                null, null, null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isNull();
    }

    @Test
    @DisplayName("identity: eventId is a UUID, source is a short slug, sourceEventId is given")
    void identity() {
        EventInput v = valid();
        assertThat(reject(new EventInput("nope", v.source(), v.sourceEventId(), null, null, null, null, null, null,
                null, null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isEqualTo("eventId must be a UUID");
        assertThat(reject(new EventInput(v.eventId(), "ERP POS", v.sourceEventId(), null, null, null, null, null,
                null, null, null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isEqualTo("source must be 1 to 40 characters: lowercase letters, digits, '-' or '_'");
        assertThat(reject(new EventInput(v.eventId(), v.source(), " ", null, null, null, null, null, null, null,
                null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isEqualTo("sourceEventId is required and holds at most 200 characters");
    }

    @Test
    @DisplayName("type and schema version must be known")
    void typeAndVersion() {
        assertThat(reject(event("sale.invented", "2026-11-17T09:00:00Z", json("{}"), "order", "pos", "observed",
                null))).isEqualTo("Unknown event type 'sale.invented'");
        EventInput v = valid();
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, null,
                null, null, null, null, v.occurredAt(), 2, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isEqualTo("Unsupported schemaVersion 2 for type 'sale.completed'");
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, null,
                null, null, null, null, v.occurredAt(), null, v.type(), v.payload(), "order", "pos", "observed",
                null))).isEqualTo("Unsupported schemaVersion null for type 'sale.completed'");
    }

    @Test
    @DisplayName("time: an instant, at most a day ahead; an old event is welcome; the business date is a date")
    void time() {
        assertThat(reject(event("sale.completed", "yesterday", json("{}"), "order", "pos", "observed", null)))
                .isEqualTo("occurredAt must be an ISO-8601 instant");
        assertThat(reject(event("sale.completed", "2026-11-18T12:00:01Z", json("{}"), "order", "pos", "observed",
                null))).isEqualTo("occurredAt is more than 24 hours in the future");
        assertThat(reject(event("sale.completed", "2026-11-18T11:59:00Z", json("{}"), "order", "pos", "observed",
                null))).isNull();
        assertThat(reject(event("sale.completed", "2025-01-01T00:00:00Z", json("{}"), "order", "pos", "observed",
                null))).isNull();
        EventInput v = valid();
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, null,
                null, null, null, "17/11/2026", v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed",
                null))).isEqualTo("businessDate must be an ISO date (YYYY-MM-DD)");
    }

    @Test
    @DisplayName("a correlation id, when given, holds 1 to 200 characters")
    void correlationIds() {
        EventInput v = valid();
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, "",
                null, null, null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos", "observed", null)))
                .isEqualTo("orderId must hold 1 to 200 characters when given");
        assertThat(reject(new EventInput(v.eventId(), v.source(), v.sourceEventId(), null, null, null, null, null,
                "t".repeat(201), null, null, null, v.occurredAt(), 1, v.type(), v.payload(), "order", "pos",
                "observed", null))).isEqualTo("ticketId must hold 1 to 200 characters when given");
    }

    @Test
    @DisplayName("evidence: level, source, status and confidence are from the contract")
    void evidence() {
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("{}"), "plate", "pos", "observed",
                null))).isEqualTo("evidenceLevel must be one of: item, ticket, order, session, zone");
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("{}"), "order", null, "observed",
                null))).isEqualTo("evidenceSource must be one of: pos, kds, stock, camera, sensor, manual");
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("{}"), "order", "pos", "guessed",
                null))).isEqualTo("observationStatus must be one of: observed, inferred, unavailable");
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("{}"), "order", "pos", "observed",
                new BigDecimal("1.01")))).isEqualTo("confidence must be between 0 and 1");
    }

    @Test
    @DisplayName("payload: an object of bounded size; a kitchen stage names its stage")
    void payload() {
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", null, "order", "pos", "observed", null)))
                .isEqualTo("payload must be a JSON object");
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("[1]"), "order", "pos", "observed",
                null))).isEqualTo("payload must be a JSON object");
        assertThat(reject(event("sale.completed", "2026-11-17T09:00:00Z", json("{\"note\":\"" + "x".repeat(70_000)
                + "\"}"), "order", "pos", "observed", null))).isEqualTo("payload is larger than 65536 bytes");
        assertThat(reject(event("kitchen.stage", "2026-11-17T09:00:00Z", json("{\"stage\":\"eaten\"}"), "ticket",
                "kds", "inferred", null))).startsWith("payload.stage must be one of: preparation_started, prepared,");
        assertThat(reject(event("kitchen.stage", "2026-11-17T09:00:00Z", json("{\"stage\":\"dispatched\"}"), "ticket",
                "kds", "inferred", null))).isNull();
    }

    /** A camera observation of zone {@code zone-1}; each test changes one part. */
    static EventInput seen(String type, String payload, String level, String evidenceSource, String status,
            String deviceId) {
        return new EventInput(UUID.randomUUID().toString(), "edge-agent", "obs-1", null, deviceId, null, null, null,
                null, null, null, null, "2026-11-17T09:15:20Z", 1, type, json(payload), level, evidenceSource, status,
                new BigDecimal("0.9"));
    }

    static String rejectSeen(String type, String payload) {
        return reject(seen(type, payload, "zone", "camera", "observed", "zone-1"));
    }

    @Test
    @DisplayName("vision: each type with its required payload passes")
    void visionAccepts() {
        assertThat(rejectSeen("vision.line_crossed", "{\"cameraId\":\"c1\",\"class\":\"person\",\"direction\":\"in\","
                + "\"count\":2}")).isNull();
        assertThat(rejectSeen("vision.dwell", "{\"cameraId\":\"c1\",\"class\":\"person\",\"startedAt\":"
                + "\"2026-11-17T09:13:00Z\",\"endedAt\":\"2026-11-17T09:15:00Z\",\"clipId\":\"clip-1\"}")).isNull();
        assertThat(rejectSeen("vision.state_changed", "{\"cameraId\":\"c1\",\"object\":\"cash_drawer\",\"to\":"
                + "\"open\"}")).isNull();
        assertThat(rejectSeen("vision.handling", "{\"cameraId\":\"c1\",\"object\":\"bottle\",\"action\":"
                + "\"take\"}")).isNull();
        assertThat(rejectSeen("vision.occupancy", "{\"cameraId\":\"c1\",\"count\":0}")).isNull();
        assertThat(reject(seen("vision.gap", "{\"cameraId\":\"c1\",\"from\":\"2026-11-17T09:00:00Z\",\"to\":"
                + "\"2026-11-17T09:10:00Z\",\"reason\":\"stream_lost\"}", "zone", "camera", "unavailable", "zone-1")))
                .isNull();
    }

    @Test
    @DisplayName("vision: about one zone, seen by a camera or a sensor, on a named camera")
    void visionEnvelope() {
        String drawer = "{\"cameraId\":\"c1\",\"object\":\"cash_drawer\",\"to\":\"open\"}";
        assertThat(reject(seen("vision.state_changed", drawer, "session", "camera", "observed", "zone-1")))
                .isEqualTo("evidenceLevel must be zone for type 'vision.state_changed'");
        assertThat(reject(seen("vision.state_changed", drawer, "zone", "pos", "observed", "zone-1")))
                .isEqualTo("evidenceSource must be camera or sensor for type 'vision.state_changed'");
        assertThat(reject(seen("vision.state_changed", drawer, "zone", "camera", "observed", null)))
                .isEqualTo("deviceId must name the zone for type 'vision.state_changed'");
        assertThat(rejectSeen("vision.state_changed", "{\"object\":\"cash_drawer\",\"to\":\"open\"}"))
                .isEqualTo("payload.cameraId is required for type 'vision.state_changed'");
        assertThat(rejectSeen("vision.state_changed", "{\"cameraId\":\"c1\",\"object\":\"cash_drawer\",\"to\":"
                + "\"open\",\"clipId\":\"\"}")).isEqualTo("payload.clipId must hold 1 to 200 characters when given");
        assertThat(reject(seen("vision.state_changed", drawer, "zone", "sensor", "observed", "zone-1"))).isNull();
    }

    @Test
    @DisplayName("vision: payload checks per type")
    void visionPayloads() {
        assertThat(rejectSeen("vision.line_crossed", "{\"cameraId\":\"c1\",\"class\":\"person\",\"direction\":"
                + "\"up\",\"count\":1}")).isEqualTo("payload.direction must be one of: in, out");
        assertThat(rejectSeen("vision.line_crossed", "{\"cameraId\":\"c1\",\"class\":\"person\",\"direction\":"
                + "\"in\",\"count\":0}")).isEqualTo("payload.count must be a whole number of 1 or more");
        assertThat(rejectSeen("vision.occupancy", "{\"cameraId\":\"c1\",\"count\":1.5}"))
                .isEqualTo("payload.count must be a whole number of 0 or more");
        assertThat(rejectSeen("vision.dwell", "{\"cameraId\":\"c1\",\"class\":\"person\",\"startedAt\":"
                + "\"2026-11-17T09:15:00Z\",\"endedAt\":\"2026-11-17T09:13:00Z\"}"))
                .isEqualTo("payload.endedAt must not be before payload.startedAt");
        assertThat(rejectSeen("vision.dwell", "{\"cameraId\":\"c1\",\"class\":\"person\",\"startedAt\":"
                + "\"yesterday\",\"endedAt\":\"2026-11-17T09:13:00Z\"}"))
                .isEqualTo("payload.startedAt must be an ISO-8601 instant");
        assertThat(rejectSeen("vision.state_changed", "{\"cameraId\":\"c1\",\"object\":\"cash_drawer\"}"))
                .isEqualTo("payload.to is required for type 'vision.state_changed'");
        assertThat(rejectSeen("vision.gap", "{\"cameraId\":\"c1\",\"from\":\"2026-11-17T09:00:00Z\",\"to\":"
                + "\"2026-11-17T09:10:00Z\",\"reason\":\"stream_lost\"}"))
                .isEqualTo("observationStatus must be unavailable for type 'vision.gap'");
        assertThat(reject(seen("vision.gap", "{\"cameraId\":\"c1\",\"from\":\"2026-11-17T09:00:00Z\",\"to\":"
                + "\"2026-11-17T09:10:00Z\",\"reason\":\"lunch\"}", "zone", "camera", "unavailable", "zone-1")))
                .isEqualTo("payload.reason must be one of: stream_lost, agent_down, clock_drift, disabled");
    }

    @Test
    @DisplayName("cash_drawer.opened: a known reason, and a till id when given")
    void drawerOpened() {
        assertThat(reject(event("cash_drawer.opened", "2026-11-17T09:15:20Z", json("{\"reason\":\"no_sale\","
                + "\"tillId\":\"till-1\"}"), "session", "pos", "observed", null))).isNull();
        assertThat(reject(event("cash_drawer.opened", "2026-11-17T09:15:20Z", json("{\"reason\":\"curious\"}"),
                "session", "pos", "observed", null)))
                .isEqualTo("payload.reason must be one of: sale, refund, cash_movement, no_sale, float, other");
        assertThat(reject(event("cash_drawer.opened", "2026-11-17T09:15:20Z", json("{\"reason\":\"sale\","
                + "\"tillId\":7}"), "session", "pos", "observed", null)))
                .isEqualTo("payload.tillId must hold 1 to 200 characters when given");
    }

    static String rejectMeter(String type, String payload, String level, String source, String status) {
        return reject(seen(type, payload, level, source, status, "device-1"));
    }

    @Test
    @DisplayName("pour.measured: about one device, from a sensor, a volume above 0 and at most 5000 ml")
    void pourMeasured() {
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25}", "item", "sensor", "observed")).isNull();
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25.5,\"productId\":\"p1\",\"startedAt\":"
                + "\"2026-11-17T09:15:18Z\",\"endedAt\":\"2026-11-17T09:15:20Z\"}", "item", "sensor", "observed"))
                .isNull();
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25}", "zone", "sensor", "observed"))
                .isEqualTo("evidenceLevel must be item for type 'pour.measured'");
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25}", "item", "camera", "observed"))
                .isEqualTo("evidenceSource must be sensor for type 'pour.measured'");
        assertThat(reject(seen("pour.measured", "{\"volumeMl\":25}", "item", "sensor", "observed", null)))
                .isEqualTo("deviceId must name the measurement device for type 'pour.measured'");
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25}", "item", "sensor", "inferred"))
                .isEqualTo("observationStatus must be observed for type 'pour.measured'");
        for (String bad : new String[] {"{}", "{\"volumeMl\":0}", "{\"volumeMl\":5001}", "{\"volumeMl\":\"25\"}"}) {
            assertThat(rejectMeter("pour.measured", bad, "item", "sensor", "observed"))
                    .isEqualTo("payload.volumeMl must be a number above 0 and at most 5000");
        }
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25,\"productId\":\"\"}", "item", "sensor",
                "observed")).isEqualTo("payload.productId must hold 1 to 200 characters when given");
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25,\"startedAt\":\"2026-11-17T09:15:20Z\","
                + "\"endedAt\":\"2026-11-17T09:15:10Z\"}", "item", "sensor", "observed"))
                .isEqualTo("payload.endedAt must not be before payload.startedAt");
        assertThat(rejectMeter("pour.measured", "{\"volumeMl\":25,\"endedAt\":\"yesterday\"}", "item", "sensor",
                "observed")).isEqualTo("payload.endedAt must be an ISO-8601 instant");
    }

    @Test
    @DisplayName("measurement.gap: unavailable, with a period and a known reason")
    void measurementGap() {
        String gap = "{\"from\":\"2026-11-17T09:00:00Z\",\"to\":\"2026-11-17T09:10:00Z\",\"reason\":\"adapter_down\"}";
        assertThat(rejectMeter("measurement.gap", gap, "item", "sensor", "unavailable")).isNull();
        assertThat(rejectMeter("measurement.gap", gap, "item", "sensor", "observed"))
                .isEqualTo("observationStatus must be unavailable for type 'measurement.gap'");
        assertThat(rejectMeter("measurement.gap", gap.replace("adapter_down", "stream_lost"), "item", "sensor",
                "unavailable")).isEqualTo("payload.reason must be one of: device_offline, adapter_down, clock_drift,"
                        + " disabled");
    }

    @Test
    @DisplayName("stock.movement: an item at a location, in or out, a quantity above 0 as a number or decimal string")
    void stockMovement() {
        String ok = "{\"locationId\":\"loc-1\",\"direction\":\"out\",\"quantity\":4,\"kind\":\"transfer_out\"}";
        assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json(ok), "item", "stock", "observed",
                null))).isNull();
        assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json(ok), "order", "stock", "observed",
                null))).isEqualTo("evidenceLevel must be item for type 'stock.movement'");
        assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json("{\"direction\":\"out\","
                + "\"quantity\":4}"), "item", "stock", "observed", null)))
                .isEqualTo("payload.locationId is required for type 'stock.movement'");
        assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json(ok.replace("\"out\"",
                "\"sideways\"")), "item", "stock", "observed", null)))
                .isEqualTo("payload.direction must be one of: in, out");
        for (String good : new String[] {"\"4\"", "\"2.500000\"", "0.25"}) {
            assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json(ok.replace("\"quantity\":4",
                    "\"quantity\":" + good)), "item", "stock", "observed", null))).as(good).isNull();
        }
        for (String bad : new String[] {"0", "-1", "\"-2\"", "\"four\"", "null", "true"}) {
            assertThat(reject(event("stock.movement", "2026-11-17T09:15:20Z", json(ok.replace("\"quantity\":4",
                    "\"quantity\":" + bad)), "item", "stock", "observed", null)))
                    .isEqualTo("payload.quantity must be a number above 0");
        }
    }

    @Test
    @DisplayName("vision.activity: a named activity over a period, from a named recognizer, a score from 0 to 1")
    void visionActivity() {
        String ok = "{\"cameraId\":\"c1\",\"activity\":\"shelf_sweep\",\"recognizer\":\"research_v1\","
                + "\"startedAt\":\"2026-11-17T09:15:00Z\",\"endedAt\":\"2026-11-17T09:15:20Z\",\"score\":0.7}";
        assertThat(rejectSeen("vision.activity", ok)).isNull();
        assertThat(rejectSeen("vision.activity", ok.replace(",\"score\":0.7", ""))).isNull();
        for (String bad : new String[] {"Shelf sweep", "9lives", "", "a".repeat(65)}) {
            assertThat(rejectSeen("vision.activity", ok.replace("shelf_sweep", bad))).isEqualTo("payload.activity"
                    + " must be 1 to 64 characters: lowercase letters, digits or '_', starting with a letter");
        }
        assertThat(rejectSeen("vision.activity", ok.replace(",\"recognizer\":\"research_v1\"", "")))
                .isEqualTo("payload.recognizer is required for type 'vision.activity'");
        assertThat(rejectSeen("vision.activity", ok.replace("09:15:20", "09:14:00")))
                .isEqualTo("payload.endedAt must not be before payload.startedAt");
        assertThat(rejectSeen("vision.activity", ok.replace("0.7", "1.5")))
                .isEqualTo("payload.score must be between 0 and 1");
    }
}
