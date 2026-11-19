package com.inventoryerp.sales.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a quotation prints (Sales settings → Quotation layout, {@code SalesSettings.quoteLayout}). Stored as JSON; a
 * missing key takes its default, so every organization starts with the classic layout.
 *
 * @param companyName printed company name; blank = the company settings name ({@code Organization.name})
 */
public record QuoteLayout(String title, String companyName, boolean showLogo, boolean showCompanyDetails,
        String accentColor, String paperSize, String orientation, boolean showUnit, boolean showDiscount, boolean showTax,
        boolean showSize, boolean showSections, boolean showSalesperson, boolean showNotes, boolean showTerms,
        boolean showSignature, String headerText, String footerText) {

    public static final QuoteLayout DEFAULT = new QuoteLayout("QUOTATION", "", true, true, "#1F2937", "A4", "portrait",
            true, true, false, true, true, true, true, true, false, "", "");

    static final Set<String> PAPER = Set.of("A4", "A5", "Letter");
    static final Set<String> ORIENTATION = Set.of("portrait", "landscape");
    static final List<String> FLAGS = List.of("showLogo", "showCompanyDetails", "showUnit", "showDiscount", "showTax",
            "showSize", "showSections", "showSalesperson", "showNotes", "showTerms", "showSignature");
    static final Map<String, Integer> TEXTS = Map.of("title", 60, "companyName", 120, "headerText", 2000, "footerText",
            500);
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    /** The stored value (a JSON map, possibly empty or partial) with defaults filled in; never fails. */
    public static QuoteLayout of(Object stored) {
        Map<?, ?> m = asMap(stored);
        Map<String, Object> d = DEFAULT.toMap();
        Map<String, Object> v = new LinkedHashMap<>(d);
        for (String k : d.keySet()) {
            Object x = m.get(k);
            if (x == null) {
                continue;
            }
            if (d.get(k) instanceof Boolean) {
                if (x instanceof Boolean b) {
                    v.put(k, b);
                }
            } else {
                v.put(k, x.toString());
            }
        }
        if (!PAPER.contains(v.get("paperSize"))) {
            v.put("paperSize", DEFAULT.paperSize());
        }
        if (!ORIENTATION.contains(v.get("orientation"))) {
            v.put("orientation", DEFAULT.orientation());
        }
        if (!HEX.matcher(v.get("accentColor").toString()).matches()) {
            v.put("accentColor", DEFAULT.accentColor());
        }
        if (v.get("title").toString().isBlank()) {
            v.put("title", DEFAULT.title());
        }
        return fromMap(v);
    }

    /**
     * A layout sent by the settings screen: a partial map merged onto {@code base}; unknown keys and invalid values are
     * rejected (400).
     */
    public static QuoteLayout validate(Object input, QuoteLayout base) {
        if (!(input instanceof Map<?, ?> m)) {
            throw ApiException.badRequest("quoteLayout must be an object");
        }
        Map<String, Object> v = base.toMap();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object x = e.getValue();
            if (!v.containsKey(k)) {
                throw ApiException.badRequest("quoteLayout." + k + " should not exist");
            }
            if (FLAGS.contains(k)) {
                if (!(x instanceof Boolean)) {
                    throw ApiException.badRequest("quoteLayout." + k + " must be a boolean value");
                }
                v.put(k, x);
                continue;
            }
            String s = x == null ? "" : x.toString().trim();
            if (TEXTS.containsKey(k) && s.length() > TEXTS.get(k)) {
                throw ApiException.badRequest("quoteLayout." + k + " must be at most " + TEXTS.get(k) + " characters");
            }
            switch (k) {
                case "title" -> s = s.isEmpty() ? DEFAULT.title() : s;
                case "accentColor" -> {
                    if (!HEX.matcher(s).matches()) {
                        throw ApiException.badRequest("quoteLayout.accentColor must be a hex colour like #1F2937");
                    }
                }
                case "paperSize" -> {
                    if (!PAPER.contains(s)) {
                        throw ApiException.badRequest("quoteLayout.paperSize must be one of: A4, A5, Letter");
                    }
                }
                case "orientation" -> {
                    if (!ORIENTATION.contains(s)) {
                        throw ApiException.badRequest("quoteLayout.orientation must be one of: landscape, portrait");
                    }
                }
                default -> { }
            }
            v.put(k, s);
        }
        return fromMap(v);
    }

    /** JSON shape (every key present). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("companyName", companyName);
        m.put("showLogo", showLogo);
        m.put("showCompanyDetails", showCompanyDetails);
        m.put("accentColor", accentColor);
        m.put("paperSize", paperSize);
        m.put("orientation", orientation);
        m.put("showUnit", showUnit);
        m.put("showDiscount", showDiscount);
        m.put("showTax", showTax);
        m.put("showSize", showSize);
        m.put("showSections", showSections);
        m.put("showSalesperson", showSalesperson);
        m.put("showNotes", showNotes);
        m.put("showTerms", showTerms);
        m.put("showSignature", showSignature);
        m.put("headerText", headerText);
        m.put("footerText", footerText);
        return m;
    }

    private static QuoteLayout fromMap(Map<String, Object> v) {
        return new QuoteLayout(s(v, "title"), s(v, "companyName"), b(v, "showLogo"), b(v, "showCompanyDetails"),
                s(v, "accentColor"), s(v, "paperSize"), s(v, "orientation"), b(v, "showUnit"), b(v, "showDiscount"),
                b(v, "showTax"), b(v, "showSize"), b(v, "showSections"), b(v, "showSalesperson"), b(v, "showNotes"),
                b(v, "showTerms"), b(v, "showSignature"), s(v, "headerText"), s(v, "footerText"));
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A JSONB column value as a map: rows come back as a Jackson tree (or JSON text); tests pass plain maps. */
    private static Map<?, ?> asMap(Object stored) {
        try {
            if (stored instanceof Map<?, ?> map) {
                return map;
            }
            if (stored instanceof JsonNode node && node.isObject()) {
                return MAPPER.convertValue(node, Map.class);
            }
            if (stored instanceof String text && !text.isBlank()) {
                return MAPPER.readValue(text, Map.class);
            }
        } catch (RuntimeException e) {
            // unreadable stored value: defaults
        }
        return Map.of();
    }

    private static String s(Map<String, Object> v, String k) {
        return v.get(k) == null ? "" : v.get(k).toString();
    }

    private static boolean b(Map<String, Object> v, String k) {
        return Boolean.TRUE.equals(v.get(k));
    }
}
