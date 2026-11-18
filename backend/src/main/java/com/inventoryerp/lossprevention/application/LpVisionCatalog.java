package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fixed vocabulary of the camera platform (LP1, docs/spec/loss-prevention.md "Vision security"): zone types, what
 * each links to, the parameters the edge agent reads with their defaults, and the limits of clips and uploads.
 */
public final class LpVisionCatalog {

    private LpVisionCatalog() {}

    public static final List<String> ZONE_TYPES = List.of("cash_drawer", "checkout", "bar_counter", "kitchen_pass",
            "dining", "cafe_counter", "storage", "entrance", "scan_area", "queue");
    public static final List<String> LINK_TYPES = List.of("till", "station", "table_area", "counter", "location",
            "table");
    public static final List<String> IN_SIDES = List.of("left", "right");
    public static final List<String> STREAM_STATES = List.of("ok", "connecting", "lost", "disabled");
    public static final List<String> CONTENT_TYPES = List.of("video/mp4", "video/webm", "video/x-matroska",
            "image/jpeg");
    public static final List<String> EVIDENCE_STATES = List.of("on_agent", "requested", "uploading", "uploaded",
            "missing", "integrity_failed", "deleted");

    /** The link types each zone type accepts. */
    public static final Map<String, List<String>> LINKS = Map.of(
            "cash_drawer", List.of("till"),
            "checkout", List.of("till"),
            "bar_counter", List.of("station", "location"),
            "kitchen_pass", List.of("station"),
            "cafe_counter", List.of("counter", "till"),
            "dining", List.of("table_area", "table"),
            "storage", List.of("location"),
            "entrance", List.of(),
            "scan_area", List.of("till"),
            "queue", List.of("till"));

    /** Zone types that must be linked (to a till): their rules compare with that till's POS records. */
    public static final Set<String> LINK_REQUIRED = Set.of("cash_drawer", "checkout", "scan_area");
    /** Zone types that may be a counting line (two points). */
    public static final Set<String> LINE_ALLOWED = Set.of("kitchen_pass", "cafe_counter", "storage", "entrance");
    /** Zone types that must be a counting line. */
    public static final Set<String> LINE_ONLY = Set.of("entrance");

    public static final int MAX_POINTS = 32;
    public static final int MAX_PARAMS_BYTES = 4096;
    public static final int MAX_LINK_LENGTH = 200;

    /** The longest clip the backend accepts, in seconds, and the largest, in bytes. */
    public static final int MAX_CLIP_SECONDS = 600;
    public static final long MAX_CLIP_BYTES = 64L * 1024 * 1024;
    /** The largest upload chunk; the request body limit of the API is 2 MB. */
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;
    /** The largest still or live frame. */
    public static final int MAX_IMAGE_BYTES = 1_572_864;
    public static final int MAX_CLIPS_PER_CALL = 100;
    public static final int MAX_CAMERAS_PER_HEARTBEAT = 64;

    /** Seconds before and after a POS event of a clip cut for a case (ADR-S077 §8). */
    public static final int CASE_CLIP_BEFORE_SECONDS = 20;
    public static final int CASE_CLIP_AFTER_SECONDS = 30;

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** What the agent uses for each zone type until the zone's own {@code params} say otherwise. */
    public static final Map<String, Map<String, Object>> DEFAULT_PARAMS = defaults();

    private static Map<String, Map<String, Object>> defaults() {
        Map<String, Map<String, Object>> d = new LinkedHashMap<>();
        d.put("cash_drawer", Obj.of("method", "reference_diff", "openThreshold", new BigDecimal("0.18"),
                "minOpenSeconds", 1, "minClosedSeconds", 2, "preRollSeconds", 20, "postRollSeconds", 30,
                "minConfidence", HALF));
        d.put("checkout", Obj.of("presenceMethod", "detector", "minPresenceSeconds", 3, "clipMinDwellSeconds", 90,
                "occupancyEverySeconds", 60, "preRollSeconds", 10, "postRollSeconds", 10, "minConfidence", HALF));
        d.put("entrance", Obj.of("classes", List.of("person"), "minConfidence", HALF));
        d.put("kitchen_pass", Obj.of("classes", List.of("plate"), "preRollSeconds", 20, "postRollSeconds", 30,
                "minConfidence", HALF));
        d.put("cafe_counter", Obj.of("classes", List.of("cup"), "preRollSeconds", 20, "postRollSeconds", 30,
                "minConfidence", HALF));
        d.put("bar_counter", Obj.of("objects", List.of("bottle"), "preRollSeconds", 20, "postRollSeconds", 30,
                "minConfidence", HALF));
        d.put("dining", Obj.of("classes", List.of("plate", "person"), "occupancyEverySeconds", 300,
                "minConfidence", HALF));
        d.put("storage", Obj.of("classes", List.of("person", "bag"), "preRollSeconds", 20, "postRollSeconds", 30,
                "minConfidence", HALF));
        // LP5: the items crossing the scanner of a checkout, and those carried around it.
        d.put("scan_area", Obj.of("classes", List.of("item"), "flowDirection", "left_to_right", "laneMargin",
                new BigDecimal("0.15"), "preRollSeconds", 10, "postRollSeconds", 10, "minConfidence", HALF));
        // LP6b: how long each person waits, and how many wait.
        d.put("queue", Obj.of("classes", List.of("person"), "minWaitSeconds", 5, "occupancyEverySeconds", 60,
                "minConfidence", HALF));
        return java.util.Collections.unmodifiableMap(d);
    }

    /** Allowed values of the text parameters. */
    static final Map<String, List<String>> TEXT_PARAMS = Map.of("method", List.of("reference_diff"),
            "presenceMethod", List.of("detector", "motion"),
            "flowDirection", List.of("left_to_right", "right_to_left", "top_to_bottom", "bottom_to_top"));

    /** Parameters that are a share or a probability: from 0 to 1. */
    static final Set<String> UNIT_PARAMS = Set.of("openThreshold", "minConfidence", "laneMargin");

    /** The defaults of the zone type with the zone's own values over them. */
    public static Map<String, Object> effectiveParams(String zoneType, Map<String, Object> own) {
        Map<String, Object> out = new LinkedHashMap<>(DEFAULT_PARAMS.getOrDefault(zoneType, Map.of()));
        if (own != null) {
            out.putAll(own);
        }
        return out;
    }
}
