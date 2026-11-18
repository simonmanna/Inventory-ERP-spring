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
        if (LpEventTypes.CASH_DRAWER_OPENED.equals(e.type())) {
            String reason = oneOf(payload, "reason", LpEventTypes.DRAWER_REASONS);
            if (reason != null) {
                return reason;
            }
            if (payload.has("tillId") && !text(payload, "tillId")) {
                return "payload.tillId must hold 1 to 200 characters when given";
            }
        }
        if (LpEventTypes.isMeasurement(e.type())) {
            return measurement(e, payload);
        }
        if (LpEventTypes.STOCK_MOVEMENT.equals(e.type())) {
            return stockMovement(e, payload);
        }
        return LpEventTypes.isVision(e.type()) ? vision(e, payload) : null;
    }

    /** A line of a stock ledger at one location (LP5). */
    private static String stockMovement(EventInput e, JsonNode payload) {
        String type = e.type();
        if (!"item".equals(e.evidenceLevel())) {
            return "evidenceLevel must be item for type '" + type + "'";
        }
        String reason = first(required(payload, type, "locationId"), oneOf(payload, "direction",
                LpEventTypes.DIRECTIONS));
        if (reason != null) {
            return reason;
        }
        // A number, or a decimal string: this ERP's own projection writes decimals as strings.
        JsonNode quantity = payload.get("quantity");
        BigDecimal q = quantity == null ? null : quantity.isNumber() ? quantity.decimalValue()
                : quantity.isString() ? LpNumbers.decimal(quantity.asString()) : null;
        if (q == null || q.signum() <= 0) {
            return "payload.quantity must be a number above 0";
        }
        return null;
    }

    /** A reading of a pour meter (LP3). It is about one measurement device; ingest checks the device. */
    private static String measurement(EventInput e, JsonNode payload) {
        String type = e.type();
        if (!"item".equals(e.evidenceLevel())) {
            return "evidenceLevel must be item for type '" + type + "'";
        }
        if (!"sensor".equals(e.evidenceSource())) {
            return "evidenceSource must be sensor for type '" + type + "'";
        }
        if (e.deviceId() == null) {
            return "deviceId must name the measurement device for type '" + type + "'";
        }
        if (LpEventTypes.MEASUREMENT_GAP.equals(type)) {
            return "unavailable".equals(e.observationStatus())
                    ? first(span(payload, "from", "to"), oneOf(payload, "reason", LpEventTypes.MEASUREMENT_GAP_REASONS))
                    : "observationStatus must be unavailable for type '" + type + "'";
        }
        if (!"observed".equals(e.observationStatus())) {
            return "observationStatus must be observed for type '" + type + "'";
        }
        JsonNode volume = payload.get("volumeMl");
        if (volume == null || !volume.isNumber() || volume.decimalValue().signum() <= 0
                || volume.decimalValue().compareTo(BigDecimal.valueOf(LpEventTypes.MAX_POUR_ML)) > 0) {
            return "payload.volumeMl must be a number above 0 and at most " + LpEventTypes.MAX_POUR_ML;
        }
        for (String field : List.of("productId", "vendorEventId")) {
            if (payload.has(field) && !payload.get(field).isNull() && !text(payload, field)) {
                return "payload." + field + " must hold 1 to 200 characters when given";
            }
        }
        boolean started = payload.has("startedAt") && !payload.get("startedAt").isNull();
        boolean ended = payload.has("endedAt") && !payload.get("endedAt").isNull();
        if (started && ended) {
            return span(payload, "startedAt", "endedAt");
        }
        for (String field : List.of("startedAt", "endedAt")) {
            if (payload.has(field) && !payload.get(field).isNull() && (!payload.get(field).isString()
                    || instant(payload.get(field).asString()) == null)) {
                return "payload." + field + " must be an ISO-8601 instant";
            }
        }
        return null;
    }

    /**
     * An observation of a camera (LP1). It is about one zone, which is its device; the agent's ingest checks that the
     * zone is one of its own.
     */
    private static String vision(EventInput e, JsonNode payload) {
        String type = e.type();
        if (!"zone".equals(e.evidenceLevel())) {
            return "evidenceLevel must be zone for type '" + type + "'";
        }
        if (!"camera".equals(e.evidenceSource()) && !"sensor".equals(e.evidenceSource())) {
            return "evidenceSource must be camera or sensor for type '" + type + "'";
        }
        if (e.deviceId() == null) {
            return "deviceId must name the zone for type '" + type + "'";
        }
        if (!text(payload, "cameraId")) {
            return "payload.cameraId is required for type '" + type + "'";
        }
        if (payload.has("clipId") && !text(payload, "clipId")) {
            return "payload.clipId must hold 1 to 200 characters when given";
        }
        return switch (type) {
            case LpEventTypes.VISION_LINE_CROSSED -> first(required(payload, type, "class"),
                    oneOf(payload, "direction", LpEventTypes.DIRECTIONS), count(payload, 1));
            case LpEventTypes.VISION_DWELL -> first(required(payload, type, "class"),
                    span(payload, "startedAt", "endedAt"));
            case LpEventTypes.VISION_STATE_CHANGED -> first(required(payload, type, "object"),
                    required(payload, type, "to"));
            case LpEventTypes.VISION_HANDLING -> first(required(payload, type, "object"),
                    required(payload, type, "action"));
            case LpEventTypes.VISION_OCCUPANCY -> count(payload, 0);
            case LpEventTypes.VISION_GAP -> "unavailable".equals(e.observationStatus())
                    ? first(span(payload, "from", "to"), oneOf(payload, "reason", LpEventTypes.GAP_REASONS))
                    : "observationStatus must be unavailable for type '" + type + "'";
            case LpEventTypes.VISION_ACTIVITY -> activity(payload, type);
            default -> null;
        };
    }

    /** The name of an activity (LP7) and of a reviewer's label for it. */
    public static final Pattern ACTIVITY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** An activity seen by a recognizer (LP7, research). */
    private static String activity(JsonNode payload, String type) {
        JsonNode activity = payload.get("activity");
        if (activity == null || !activity.isString() || !ACTIVITY.matcher(activity.asString()).matches()) {
            return "payload.activity must be 1 to 64 characters: lowercase letters, digits or '_', starting with a"
                    + " letter";
        }
        String reason = first(span(payload, "startedAt", "endedAt"), required(payload, type, "recognizer"));
        if (reason != null) {
            return reason;
        }
        JsonNode score = payload.get("score");
        if (score != null && !score.isNull() && (!score.isNumber() || score.decimalValue().signum() < 0
                || score.decimalValue().compareTo(BigDecimal.ONE) > 0)) {
            return "payload.score must be between 0 and 1";
        }
        return null;
    }

    private static String first(String... reasons) {
        for (String r : reasons) {
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** A string of 1 to 200 characters. */
    private static boolean text(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        return v != null && v.isString() && !v.asString().isBlank() && v.asString().length() <= MAX_ID_LENGTH;
    }

    private static String required(JsonNode payload, String type, String field) {
        return text(payload, field) ? null : "payload." + field + " is required for type '" + type + "'";
    }

    private static String oneOf(JsonNode payload, String field, List<String> values) {
        JsonNode v = payload.get(field);
        return v != null && v.isString() && values.contains(v.asString()) ? null
                : "payload." + field + " must be one of: " + String.join(", ", values);
    }

    private static String count(JsonNode payload, int min) {
        JsonNode v = payload.get("count");
        boolean ok = v != null && v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0
                && v.decimalValue().compareTo(BigDecimal.valueOf(min)) >= 0;
        return ok ? null : "payload.count must be a whole number of " + min + " or more";
    }

    /** Two instants, the second not before the first. */
    private static String span(JsonNode payload, String from, String to) {
        Instant start = payload.get(from) != null && payload.get(from).isString()
                ? instant(payload.get(from).asString()) : null;
        if (start == null) {
            return "payload." + from + " must be an ISO-8601 instant";
        }
        Instant end = payload.get(to) != null && payload.get(to).isString() ? instant(payload.get(to).asString())
                : null;
        if (end == null) {
            return "payload." + to + " must be an ISO-8601 instant";
        }
        return end.isBefore(start) ? "payload." + to + " must not be before payload." + from : null;
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
