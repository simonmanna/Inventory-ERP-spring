package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a customer deposit receipt is headed (Sales settings → Deposit receipt, {@code SalesSettings.depositReceiptLayout}).
 * Stored as JSON; a missing key takes its default, so receipts print the company settings name and details until the
 * organization edits them.
 *
 * @param companyName printed business name; blank = the company settings name ({@code Organization.name})
 * @param detailsText lines under the name, one per line; blank = address, phone, e-mail and TIN from the company settings
 * @param footerText note at the bottom; blank = no note
 */
public record DepositReceiptLayout(String title, String companyName, boolean showLogo, boolean showCompanyDetails,
        String detailsText, boolean showSignature, String footerText) {

    public static final DepositReceiptLayout DEFAULT = new DepositReceiptLayout("DEPOSIT RECEIPT", "", true, true, "",
            true, CustomerDepositService.DEFAULT_FOOTER);

    static final List<String> FLAGS = List.of("showLogo", "showCompanyDetails", "showSignature");
    static final Map<String, Integer> TEXTS = Map.of("title", 60, "companyName", 120, "detailsText", 600, "footerText",
            500);

    /** The stored value (a JSON map, possibly empty or partial) with defaults filled in; never fails. */
    public static DepositReceiptLayout of(Object stored) {
        Map<?, ?> m = asMap(stored);
        Map<String, Object> v = new LinkedHashMap<>(DEFAULT.toMap());
        for (String k : DEFAULT.toMap().keySet()) {
            Object x = m.get(k);
            if (x == null) {
                continue;
            }
            if (FLAGS.contains(k)) {
                if (x instanceof Boolean b) {
                    v.put(k, b);
                }
            } else {
                v.put(k, x.toString());
            }
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
    public static DepositReceiptLayout validate(Object input, DepositReceiptLayout base) {
        if (!(input instanceof Map<?, ?> m)) {
            throw ApiException.badRequest("depositReceiptLayout must be an object");
        }
        Map<String, Object> v = base.toMap();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object x = e.getValue();
            if (!v.containsKey(k)) {
                throw ApiException.badRequest("depositReceiptLayout." + k + " should not exist");
            }
            if (FLAGS.contains(k)) {
                if (!(x instanceof Boolean)) {
                    throw ApiException.badRequest("depositReceiptLayout." + k + " must be a boolean value");
                }
                v.put(k, x);
                continue;
            }
            String s = x == null ? "" : x.toString().strip();
            if (s.length() > TEXTS.get(k)) {
                throw ApiException.badRequest("depositReceiptLayout." + k + " must be at most " + TEXTS.get(k)
                        + " characters");
            }
            if ("title".equals(k) && s.isEmpty()) {
                s = DEFAULT.title();
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
        m.put("detailsText", detailsText);
        m.put("showSignature", showSignature);
        m.put("footerText", footerText);
        return m;
    }

    /** The custom detail lines (blank lines dropped). */
    public List<String> detailLines() {
        return detailsText.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static DepositReceiptLayout fromMap(Map<String, Object> v) {
        return new DepositReceiptLayout(s(v, "title"), s(v, "companyName"), b(v, "showLogo"), b(v,
                "showCompanyDetails"), s(v, "detailsText"), b(v, "showSignature"), s(v, "footerText"));
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
