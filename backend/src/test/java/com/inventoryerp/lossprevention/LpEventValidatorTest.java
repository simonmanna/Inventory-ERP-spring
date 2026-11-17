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
}
