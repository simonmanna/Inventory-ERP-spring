package com.inventoryerp.plm.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Three-way merge of a BOM change onto the BOM version that superseded its base (ADR-S086 §3). {@code base} is the
 * version the ECO was opened from, {@code theirs} the version active now, {@code ours} the ECO's draft. Each header
 * field and each line (keyed by component, variant and occurrence) is merged on its own:
 * <ul>
 *   <li>ours did not touch it → theirs (an upstream change, addition or removal is carried in);</li>
 *   <li>theirs did not touch it → ours;</li>
 *   <li>both made the same change → that change;</li>
 *   <li>both changed it differently → ours is kept and the difference is reported as a conflict.</li>
 * </ul>
 * A pure function: no I/O, no clock.
 */
public final class BomRebase {

    private BomRebase() {}

    /** Header fields merged one by one. */
    public static final List<String> HEADER_FIELDS = List.of("name", "outputQuantity", "outputUomId",
            "expectedYieldPct", "overheadCost", "defaultLocationId", "defaultOutputLocationId", "shelfLifeDays",
            "estimatedDurationMins", "qcRequired", "routingId", "notes");

    /** Line fields that make a line "changed". */
    public static final List<String> LINE_FIELDS = List.of("quantity", "uomId", "scrapPct", "isOptional", "notes");

    /**
     * A field or line both sides changed differently; {@code ours} was kept. For a line, the values are whole lines
     * (null when that side removed it) and {@code key} is the line key; for a header field, {@code key} is the field.
     */
    public record Conflict(String kind, String key, String componentProductId, Object base, Object theirs,
            Object ours) {}

    /** A line of the merged BOM and the side it came from ({@code ours} / {@code theirs}). */
    public record MergedLine(String key, Map<String, Object> line, String side) {}

    /**
     * @param header          the merged header (every {@link #HEADER_FIELDS} field)
     * @param lines           the merged lines, ours first in our order, then lines only theirs has, in their order
     * @param conflicts       differences kept as ours
     * @param fromTheirs      header fields and lines taken from theirs because only theirs changed them
     */
    public record Result(Map<String, Object> header, List<MergedLine> lines, List<Conflict> conflicts,
            List<String> fromTheirs) {}

    public static Result merge(Map<String, Object> baseHeader, Map<String, Object> theirsHeader,
            Map<String, Object> oursHeader, List<Map<String, Object>> baseLines, List<Map<String, Object>> theirsLines,
            List<Map<String, Object>> oursLines) {
        List<Conflict> conflicts = new ArrayList<>();
        List<String> fromTheirs = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        for (String f : HEADER_FIELDS) {
            Object b = baseHeader.get(f);
            Object t = theirsHeader.get(f);
            Object o = oursHeader.get(f);
            if (sameValue(o, b)) {
                header.put(f, t);
                if (!sameValue(t, b)) {
                    fromTheirs.add(f);
                }
            } else if (sameValue(t, b) || sameValue(o, t)) {
                header.put(f, o);
            } else {
                header.put(f, o);
                conflicts.add(new Conflict("header", f, null, b, t, o));
            }
        }

        Map<String, Map<String, Object>> base = keyed(baseLines);
        Map<String, Map<String, Object>> theirs = keyed(theirsLines);
        Map<String, Map<String, Object>> ours = keyed(oursLines);
        Set<String> keys = new LinkedHashSet<>(ours.keySet());
        keys.addAll(theirs.keySet());
        keys.addAll(base.keySet());
        List<MergedLine> lines = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> b = base.get(k);
            Map<String, Object> t = theirs.get(k);
            Map<String, Object> o = ours.get(k);
            Map<String, Object> chosen;
            String side;
            if (sameLine(o, b)) {
                chosen = t;
                side = "theirs";
                if (!sameLine(t, b)) {
                    fromTheirs.add(k);
                }
            } else if (sameLine(t, b) || sameLine(o, t)) {
                chosen = o;
                side = "ours";
            } else {
                chosen = o;
                side = "ours";
                String component = (String) firstNonNull(o, t, b).get("componentProductId");
                conflicts.add(new Conflict("line", k, component, b, t, o));
            }
            if (chosen != null) {
                lines.add(new MergedLine(k, chosen, side));
            }
        }
        return new Result(header, lines, conflicts, fromTheirs);
    }

    /**
     * Lines by key {@code component|variant#n} (n = the occurrence of that component in the BOM, in the given order),
     * so a component listed twice is matched occurrence by occurrence.
     */
    public static Map<String, Map<String, Object>> keyed(List<Map<String, Object>> lines) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map<String, Object> l : lines) {
            String id = l.get("componentProductId") + "|" + Objects.toString(l.get("componentVariantId"), "");
            int n = seen.merge(id, 1, Integer::sum);
            out.put(id + "#" + n, l);
        }
        return out;
    }

    /** The fields of {@link #LINE_FIELDS} that differ between two lines, as {@code {field, from, to}}. */
    public static List<Map<String, Object>> lineChanges(Map<String, Object> from, Map<String, Object> to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String f : LINE_FIELDS) {
            if (!sameValue(from.get(f), to.get(f))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", f);
                c.put("from", from.get(f));
                c.put("to", to.get(f));
                out.add(c);
            }
        }
        return out;
    }

    static boolean sameLine(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return a == b;
        }
        for (String f : LINE_FIELDS) {
            if (!sameValue(a.get(f), b.get(f))) {
                return false;
            }
        }
        return true;
    }

    /** Numbers by value ({@code 2} = {@code 2.000000}); blank strings equal null; anything else by equals. */
    public static boolean sameValue(Object a, Object b) {
        a = normal(a);
        b = normal(b);
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Object normal(Object v) {
        if (v instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return v;
    }

    @SafeVarargs
    private static Map<String, Object> firstNonNull(Map<String, Object>... maps) {
        for (Map<String, Object> m : maps) {
            if (m != null) {
                return m;
            }
        }
        throw new IllegalStateException("no line");
    }
}
