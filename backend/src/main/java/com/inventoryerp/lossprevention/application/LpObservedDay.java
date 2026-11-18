package com.inventoryerp.lossprevention.application;

import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import com.inventoryerp.lossprevention.application.LpEngine.RuleConfig;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One calendar day of a branch as the LP3 and LP4 camera and meter engines see it: the zones, the measurement devices
 * and the watermarks recorded with the run, and the observations indexed by zone or device. Pure: built from the
 * engine input only, so a run repeats.
 */
final class LpObservedDay {

    /** A zone as the run saw it. */
    record Zone(String id, String cameraId, String agentId, String type, String linkedType, String linkedId,
            boolean enabled, Instant createdAt, Instant retiredAt) {

        /** Drawn before the window began and not retired before it ended. */
        boolean liveDuring(Instant from, Instant to) {
            return enabled && (createdAt == null || !createdAt.isAfter(from)) && (retiredAt == null
                    || retiredAt.isAfter(to));
        }
    }

    /** A pour meter as the run saw it. */
    record Device(String id, String agentId, String kind, String productId, String station,
            BigDecimal standardPourMl, boolean enabled, Instant createdAt, Instant retiredAt) {

        boolean liveDuring(Instant from, Instant to) {
            return enabled && (createdAt == null || !createdAt.isAfter(from)) && (retiredAt == null
                    || retiredAt.isAfter(to));
        }
    }

    final Instant start;
    final Instant end;
    final Map<String, Zone> zones = new LinkedHashMap<>();
    final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Instant> cameraMarks = new HashMap<>();
    private final Map<String, Instant> agentMarks = new HashMap<>();
    /** Observations and readings by their zone or device id. */
    final Map<String, List<LedgerEvent>> byDevice = new HashMap<>();

    LpObservedDay(Input in) {
        Map<String, Object> context = in.context() == null ? Map.of() : in.context();
        ZoneId tz = zoneOf(context.get("timeZone"));
        start = in.businessDate().atStartOfDay(tz).toInstant();
        end = in.businessDate().plusDays(1).atStartOfDay(tz).toInstant();
        for (Map<String, Object> z : maps(context.get("zones"))) {
            zones.put(str(z.get("id")), new Zone(str(z.get("id")), str(z.get("cameraId")), str(z.get("agentId")),
                    str(z.get("zoneType")), str(z.get("linkedType")), str(z.get("linkedId")),
                    !Boolean.FALSE.equals(z.get("enabled")), instant(z.get("createdAt")),
                    instant(z.get("retiredAt"))));
        }
        for (Map<String, Object> d : maps(context.get("devices"))) {
            devices.put(str(d.get("id")), new Device(str(d.get("id")), str(d.get("agentId")), str(d.get("kind")),
                    str(d.get("productId")), str(d.get("station")), LpNumbers.decimal(d.get("standardPourMl")),
                    !Boolean.FALSE.equals(d.get("enabled")), instant(d.get("createdAt")),
                    instant(d.get("retiredAt"))));
        }
        for (Map<String, Object> c : maps(context.get("cameras"))) {
            Instant mark = instant(c.get("observedThrough"));
            if (mark != null) {
                cameraMarks.put(str(c.get("id")), mark);
            }
        }
        for (Map<String, Object> a : maps(context.get("agents"))) {
            Instant mark = instant(a.get("observedThrough"));
            if (mark != null) {
                agentMarks.put(str(a.get("id")), mark);
            }
        }
        for (LedgerEvent e : in.events()) {
            if ((LpEventTypes.isVision(e.type()) || LpEventTypes.isMeasurement(e.type())) && e.deviceId() != null) {
                byDevice.computeIfAbsent(e.deviceId(), k -> new ArrayList<>()).add(e);
            }
        }
    }

    boolean owns(Instant t) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    List<LedgerEvent> of(String deviceId) {
        return byDevice.getOrDefault(deviceId, List.of());
    }

    /** How far the zone's camera has been observed and reported: its own watermark, else its agent's. */
    Instant watermark(Zone zone) {
        Instant camera = cameraMarks.get(zone.cameraId());
        return camera != null ? camera : agentMarks.get(zone.agentId());
    }

    /**
     * How far an agent has sent a meter's readings; {@link Instant#MAX} for a meter an outside system posts (no
     * watermark: the settling time stands in for one).
     */
    Instant watermark(Device device) {
        if (device.agentId() == null) {
            return Instant.MAX;
        }
        return agentMarks.get(device.agentId());
    }

    /** A {@code vision.gap} or {@code measurement.gap} of the zone or device overlaps the period. */
    boolean gapDuring(String deviceId, Instant from, Instant to) {
        for (LedgerEvent g : of(deviceId)) {
            if (!LpEventTypes.VISION_GAP.equals(g.type()) && !LpEventTypes.MEASUREMENT_GAP.equals(g.type())) {
                continue;
            }
            Instant gapFrom = instant(g.text("from"));
            Instant gapTo = instant(g.text("to"));
            if (gapFrom != null && gapTo != null && !gapFrom.isAfter(to) && !gapTo.isBefore(from)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- shared rule helpers

    static int seconds(RuleConfig rule, String key, int fallback) {
        return Math.min(LpCashierVisionEngine.MAX_WINDOW_SECONDS, Math.max(0, rule.intValue(key, fallback)));
    }

    static BigDecimal confidence(RuleConfig rule, String fallback) {
        BigDecimal v = rule.num("minConfidence");
        return v == null ? new BigDecimal(fallback) : v;
    }

    /** Seen, not inferred, and at least as sure as the rule asks. A sender that states no confidence is taken as sure. */
    static boolean observed(LedgerEvent e, BigDecimal min) {
        return "observed".equals(e.observationStatus()) && (e.confidence() == null || e.confidence().compareTo(min) >= 0);
    }

    static boolean within(LedgerEvent e, Instant from, Instant to) {
        return e != null && !e.occurredAt().isBefore(from) && !e.occurredAt().isAfter(to);
    }

    /** Free-text links (a station) compare without case or surrounding spaces. */
    static boolean sameName(String a, String b) {
        return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    static Instant instant(Object v) {
        return v == null ? null : LpEventValidator.instant(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static ZoneId zoneOf(Object v) {
        try {
            return v == null ? ZoneOffset.UTC : ZoneId.of(String.valueOf(v));
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
