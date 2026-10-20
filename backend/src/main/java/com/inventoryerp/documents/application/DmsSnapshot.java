package com.inventoryerp.documents.application;

import com.inventoryerp.kernel.common.JsNumbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot + render data (port of {@code dms.snapshot.ts}, pure): the document-owned representation built only from
 * Document / DocumentLine columns, its sha256 seal (byte-compatible with {@code JSON.stringify(data, sortedKeys)}),
 * the flat render context and the {@code {{key}}} / {@code {{#each lines}}} substitution.
 */
public final class DmsSnapshot {

    private DmsSnapshot() {}

    private static final DateTimeFormatter JS_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern EACH = Pattern.compile("\\{\\{#each lines\\}\\}([\\s\\S]*?)\\{\\{/each\\}\\}");
    private static final Pattern KEY = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /** JavaScript {@code Date#toISOString()}. */
    public static String jsIso(Instant i) {
        return JS_ISO.format(i);
    }

    static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(v.toString());
    }

    /** {@code Number(v ?? 0)} for Decimal / number / string inputs. */
    static double num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n instanceof BigDecimal d ? d.doubleValue() : n.doubleValue();
        }
        return JsNumbers.number(v.toString());
    }

    /** {@code buildSnapshotData(doc, lines, type)} — numbers are JS numbers (integral → Long). */
    public static Map<String, Object> buildSnapshotData(Map<String, Object> doc, List<Map<String, Object>> lines,
            String typeCode, String typeName) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", doc.get("id"));
        d.put("documentNumber", doc.get("documentNumber"));
        d.put("documentTypeCode", typeCode);
        d.put("documentTypeName", typeName);
        d.put("status", str(doc.get("status")));
        Instant issue = instant(doc.get("issueDate"));
        Instant due = instant(doc.get("dueDate"));
        d.put("issueDate", issue == null ? null : jsIso(issue));
        d.put("dueDate", due == null ? null : jsIso(due));
        d.put("partnerId", doc.get("partnerId"));
        d.put("sourceDocument", doc.get("sourceDocument"));
        d.put("branchId", doc.get("branchId"));
        for (String f : List.of("subtotal", "discountTotal", "taxAmount", "totalAmount", "amountPaid", "amountResidual")) {
            d.put(f, JsNumbers.js(num(doc.get(f))));
        }
        d.put("currencyCode", doc.get("currencyId"));
        d.put("exchangeRate", JsNumbers.js(num(doc.get("exchangeRate"))));
        d.put("notes", doc.get("notes"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            String type = str(l.get("lineType"));
            if ("section".equals(type) || "note".equals(type)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", l.get("productId"));
            m.put("description", l.get("description"));
            for (String f : List.of("quantity", "unitPrice", "discountPercent", "taxAmount", "subtotal", "total")) {
                m.put(f, JsNumbers.js(num(l.get(f))));
            }
            m.put("lineNumber", l.get("lineNumber") instanceof Number n ? n.longValue() : l.get("lineNumber"));
            m.put("lineType", type);
            out.add(m);
        }
        d.put("lines", out);
        return d;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** {@code sha256(JSON.stringify(data, Object.keys(data).sort()) + '|' + templateVersionId)}. */
    public static String seal(Map<String, Object> data, String templateVersionId) {
        List<String> whitelist = new ArrayList<>(new TreeSet<>(data.keySet()));
        String canonical = stringify(data, whitelist);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((canonical + "|"
                    + (templateVersionId == null ? "" : templateVersionId)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code JSON.stringify(value, replacerArray)}: the property whitelist applies at every nesting level. */
    static String stringify(Object v, List<String> whitelist) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? "null" : JsNumbers.toJsString(d);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String k : whitelist) {
                if (!m.containsKey(k)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(k)).append(':').append(stringify(m.get(k), whitelist));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(stringify(list.get(i), whitelist));
            }
            return sb.append(']').toString();
        }
        return quote(v.toString());
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (Character.isSurrogate(c) && !pairedSurrogate(s, i))) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean pairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    /** {@code Intl.NumberFormat('en', {min: 2, max: 2})}. */
    static String money(double v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return f.format(new BigDecimal(Double.toString(v)).setScale(2, RoundingMode.HALF_UP));
    }

    /** Flat render map (money 2dp with grouping, dates as YYYY-MM-DD, short ids). */
    public static Map<String, Object> toRenderContext(Map<String, Object> data) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("documentNumber", data.get("documentNumber"));
        ctx.put("documentTypeName", data.get("documentTypeName"));
        ctx.put("status", data.get("status"));
        ctx.put("issueDate", date(data.get("issueDate")));
        ctx.put("dueDate", date(data.get("dueDate")));
        Object partner = data.get("partnerId");
        Object branch = data.get("branchId");
        ctx.put("partnerCode", partner == null || partner.toString().isEmpty() ? "—" : head8(partner.toString()));
        ctx.put("branchName", branch == null || branch.toString().isEmpty() ? "—" : head8(branch.toString()));
        for (String f : List.of("subtotal", "discountTotal", "taxAmount", "totalAmount", "amountPaid", "amountResidual")) {
            ctx.put(f, money(num(data.get(f))));
        }
        Object currency = data.get("currencyCode");
        ctx.put("currencyCode", currency == null ? "" : currency);
        ctx.put("generatedAt", jsIso(Instant.now()));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (data.get("lines") instanceof List<?> raw) {
            for (Object o : raw) {
                Map<?, ?> l = (Map<?, ?>) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lineNumber", l.get("lineNumber"));
                m.put("description", l.get("description"));
                m.put("quantity", JsNumbers.js(num(l.get("quantity"))));
                m.put("unitPrice", money(num(l.get("unitPrice"))));
                m.put("total", money(num(l.get("total"))));
                lines.add(m);
            }
        }
        ctx.put("lines", lines);
        return ctx;
    }

    private static String head8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static String date(Object v) {
        return v == null || v.toString().isEmpty() ? "" : jsIso(instant(v)).substring(0, 10);
    }

    /** {@code {{#each lines}}…{{/each}}} blocks, then {@code {{key}}} scalars; unknown placeholders stay. */
    public static String renderTemplate(String template, Map<String, Object> ctx) {
        Matcher each = EACH.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (each.find()) {
            String body = each.group(1);
            StringBuilder block = new StringBuilder();
            if (ctx.get("lines") instanceof List<?> lines) {
                for (Object o : lines) {
                    Map<?, ?> l = (Map<?, ?>) o;
                    Matcher k = KEY.matcher(body);
                    StringBuilder line = new StringBuilder();
                    while (k.find()) {
                        Object v = l.get(k.group(1));
                        k.appendReplacement(line, Matcher.quoteReplacement(v == null ? "" : jsString(v)));
                    }
                    k.appendTail(line);
                    block.append(line);
                }
            }
            each.appendReplacement(sb, Matcher.quoteReplacement(block.toString()));
        }
        each.appendTail(sb);
        Matcher k = KEY.matcher(sb.toString());
        StringBuilder out = new StringBuilder();
        while (k.find()) {
            String key = k.group(1);
            String rep;
            if (!ctx.containsKey(key)) {
                rep = k.group();
            } else {
                Object v = ctx.get(key);
                rep = v == null ? "" : jsString(v);
            }
            k.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        k.appendTail(out);
        return out.toString();
    }

    private static String jsString(Object v) {
        if (v instanceof Double || v instanceof Float) {
            return JsNumbers.toJsString(((Number) v).doubleValue());
        }
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                parts.add(o == null ? "" : jsString(o));
            }
            return String.join(",", parts);
        }
        if (v instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return v.toString();
    }
}
