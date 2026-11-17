package com.inventoryerp.lossprevention.application;

import com.inventoryerp.lossprevention.application.LpCommands.EventInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks one event against the canonical contract. Pure: no database, no clock of its own. The first failed check is
 * the reason the event is refused; the messages are part of the contract (docs/spec/loss-prevention.md).
 */
public final class LpEventValidator {

    private LpEventValidator() {}

    static final int MAX_ID_LENGTH = 200;
    static final int MAX_PAYLOAD_BYTES = 65_536;
    static final Duration MAX_FUTURE = Duration.ofHours(24);
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    /** The reason {@code e} is refused, or {@code null} when it is valid. */
    public static String reject(EventInput e, Instant now) {
        if (!isUuid(e.eventId())) {
            return "eventId must be a UUID";
        }
        if (e.source() == null || !SOURCE.matcher(e.source()).matches()) {
            return "source must be 1 to 40 characters: lowercase letters, digits, '-' or '_'";
        }
        if (blank(e.sourceEventId()) || e.sourceEventId().length() > MAX_ID_LENGTH) {
            return "sourceEventId is required and holds at most 200 characters";
        }
        if (blank(e.type()) || !LpEventTypes.types().contains(e.type())) {
            return "Unknown event type '" + e.type() + "'";
        }
        if (e.schemaVersion() == null || e.schemaVersion() < 1
                || e.schemaVersion() > LpEventTypes.SCHEMA_VERSIONS.get(e.type())) {
            return "Unsupported schemaVersion " + e.schemaVersion() + " for type '" + e.type() + "'";
        }
        Instant occurredAt = instant(e.occurredAt());
        if (occurredAt == null) {
            return "occurredAt must be an ISO-8601 instant";
        }
        if (occurredAt.isAfter(now.plus(MAX_FUTURE))) {
            return "occurredAt is more than 24 hours in the future";
        }
        if (e.businessDate() != null && date(e.businessDate()) == null) {
            return "businessDate must be an ISO date (YYYY-MM-DD)";
        }
        String badId = badId(e);
        if (badId != null) {
            return badId + " must hold 1 to 200 characters when given";
        }
        String evidence = evidence(e);
        if (evidence != null) {
            return evidence;
        }
        return payload(e);
    }

    private static String evidence(EventInput e) {
        if (e.evidenceLevel() == null || !LpEventTypes.EVIDENCE_LEVELS.contains(e.evidenceLevel())) {
            return "evidenceLevel must be one of: " + String.join(", ", LpEventTypes.EVIDENCE_LEVELS);
        }
        if (e.evidenceSource() == null || !LpEventTypes.EVIDENCE_SOURCES.contains(e.evidenceSource())) {
            return "evidenceSource must be one of: " + String.join(", ", LpEventTypes.EVIDENCE_SOURCES);
        }
        if (e.observationStatus() == null || !LpEventTypes.OBSERVATION_STATUSES.contains(e.observationStatus())) {
            return "observationStatus must be one of: " + String.join(", ", LpEventTypes.OBSERVATION_STATUSES);
        }
        BigDecimal c = e.confidence();
        if (c != null && (c.signum() < 0 || c.compareTo(BigDecimal.ONE) > 0)) {
            return "confidence must be between 0 and 1";
        }
        return null;
    }

    private static String payload(EventInput e) {
        JsonNode payload = e.payload();
        if (payload == null || !payload.isObject()) {
            return "payload must be a JSON object";
        }
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            return "payload is larger than " + MAX_PAYLOAD_BYTES + " bytes";
        }
        if (LpEventTypes.KITCHEN_STAGE.equals(e.type())) {
            JsonNode stage = payload.get("stage");
            if (stage == null || !stage.isString() || !LpEventTypes.KITCHEN_STAGES.contains(stage.asString())) {
                return "payload.stage must be one of: " + String.join(", ", LpEventTypes.KITCHEN_STAGES);
            }
        }
        return null;
    }

    /** The name of the first correlation id that is empty or too long, or {@code null}. */
    private static String badId(EventInput e) {
        List<String> names = List.of("branchId", "deviceId", "actorId", "transactionId", "orderId", "ticketId",
                "lineId", "sessionId");
        List<String> values = java.util.Arrays.asList(e.branchId(), e.deviceId(), e.actorId(), e.transactionId(),
                e.orderId(), e.ticketId(), e.lineId(), e.sessionId());
        for (int i = 0; i < names.size(); i++) {
            String v = values.get(i);
            if (v != null && (v.isBlank() || v.length() > MAX_ID_LENGTH)) {
                return names.get(i);
            }
        }
        return null;
    }

    static Instant instant(String v) {
        if (blank(v)) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    static LocalDate date(String v) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static boolean isUuid(String v) {
        if (v == null || v.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(v);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
