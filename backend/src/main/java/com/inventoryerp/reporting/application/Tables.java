package com.inventoryerp.reporting.application;

import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Check;
import com.inventoryerp.reporting.spi.ReportBody.Column;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportBody.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds tabular report bodies: typed columns, normalised values (money to 2 decimals, timestamps to business dates),
 * optional group subtotals, a grand total over money and number columns, and per-row drill targets.
 */
final class Tables {

    private Tables() {}

    static Column text(String key, String label) {
        return new Column(key, label, "text");
    }

    static Column money(String key, String label) {
        return new Column(key, label, "money");
    }

    static Column number(String key, String label) {
        return new Column(key, label, "number");
    }

    static Column date(String key, String label) {
        return new Column(key, label, "date");
    }

    static Column pct(String key, String label) {
        return new Column(key, label, "percent");
    }

    static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    static BigDecimal r2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code a / b × 100}, 2 decimals; {@code null} when {@code b} is zero. */
    static BigDecimal pctOf(BigDecimal a, BigDecimal b) {
        return b == null || b.signum() == 0 || a == null ? null : a.multiply(BigDecimal.valueOf(100)).divide(b, 2,
                RoundingMode.HALF_UP);
    }

    /** {@code a / b}, {@code scale} decimals; {@code null} when {@code b} is zero. */
    static BigDecimal div(BigDecimal a, BigDecimal b, int scale) {
        return b == null || b.signum() == 0 || a == null ? null : a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /** An instant for a timestamp value (rows hold {@link Instant}; raw JDBC values are UTC {@link Timestamp}s). */
    static Instant instant(Object v) {
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        return v == null ? null : Instant.parse(v.toString());
    }

    /** A business date for a timestamp column (DB timestamps are UTC). */
    static String day(Object v, ZoneId zone) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate().toString();
        }
        if (v instanceof Instant i) {
            return i.atZone(zone).toLocalDate().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        String s = v.toString();
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    /** Values for the columns, typed. */
    static Map<String, Object> values(List<Column> cols, Map<String, Object> src, ZoneId zone) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Column c : cols) {
            Object v = src.get(c.key());
            out.put(c.key(), switch (c.type()) {
                case "money" -> v == null ? null : r2(dec(v));
                case "percent" -> v == null ? null : r2(dec(v));
                case "number" -> v == null ? null : dec(v).stripTrailingZeros().scale() > 4 ? dec(v).setScale(4,
                        RoundingMode.HALF_UP) : dec(v);
                case "date" -> day(v, zone);
                default -> v == null ? null : v.toString();
            });
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            if (e.getKey().startsWith("_") || e.getKey().endsWith("Id")) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Builder over a list of source rows. */
    static final class Builder {
        private final List<Column> cols;
        private final ZoneId zone;
        private final List<Row> rows = new ArrayList<>();
        private final Map<String, BigDecimal> totals = new LinkedHashMap<>();
        private final List<Check> checks = new ArrayList<>();
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private final java.util.Set<String> ids = new java.util.HashSet<>();
        private int seq;

        Builder(ZoneId zone, Column... cols) {
            this.zone = zone;
            this.cols = List.of(cols);
        }

        Builder(ZoneId zone, List<Column> cols) {
            this.zone = zone;
            this.cols = List.copyOf(cols);
        }

        List<Column> columns() {
            return cols;
        }

        /** A detail line; money and number columns count in the grand total. */
        Builder line(Map<String, Object> src, Map<String, Drill> drill) {
            return add("line", 0, false, src, drill, true);
        }

        Builder line(Map<String, Object> src) {
            return line(src, Map.of());
        }

        Builder add(String kind, int depth, boolean bold, Map<String, Object> src, Map<String, Drill> drill,
                boolean count) {
            Map<String, Object> v = values(cols, src, zone);
            if (count) {
                for (Column c : cols) {
                    if (("money".equals(c.type()) || "number".equals(c.type())) && v.get(c.key()) != null) {
                        totals.merge(c.key(), dec(v.get(c.key())), BigDecimal::add);
                    }
                }
            }
            Object id = src.getOrDefault("_id", src.get("id"));
            String rowId = id == null ? "r" + (++seq) : id.toString();
            if (!ids.add(rowId)) {
                rowId = rowId + "#" + (++seq);
                ids.add(rowId);
            }
            rows.add(new Row(rowId, kind, depth, bold, v, drill == null ? Map.of() : drill));
            return this;
        }

        /** Detail lines grouped by {@code groupKey} with a subtotal row per group. */
        Builder grouped(List<Map<String, Object>> src, String groupKey, String labelKey,
                Function<Map<String, Object>, Map<String, Drill>> drill) {
            Map<Object, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> r : src) {
                groups.computeIfAbsent(r.get(groupKey), k -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<Object, List<Map<String, Object>>> g : groups.entrySet()) {
                Map<String, BigDecimal> sub = new LinkedHashMap<>();
                for (Map<String, Object> r : g.getValue()) {
                    add("line", 1, false, r, drill == null ? Map.of() : drill.apply(r), true);
                    for (Column c : cols) {
                        if (("money".equals(c.type()) || "number".equals(c.type())) && r.get(c.key()) != null) {
                            sub.merge(c.key(), dec(r.get(c.key())), BigDecimal::add);
                        }
                    }
                }
                Map<String, Object> s = new LinkedHashMap<>(sub);
                s.put(cols.getFirst().key(), Objects.toString(g.getValue().getFirst().get(labelKey), "—") + " total");
                s.put("_id", "sub:" + g.getKey());
                add("subtotal", 0, true, s, Map.of(), false);
            }
            return this;
        }

        Builder check(String code, String label, boolean ok, String detail) {
            checks.add(new Check(code, label, ok, ok ? null : detail));
            return this;
        }

        /** Check that two amounts agree to the cent. */
        Builder tie(String code, String label, BigDecimal a, BigDecimal b) {
            BigDecimal diff = r2(dec(a).subtract(dec(b)));
            return check(code, label, diff.signum() == 0, "Difference " + diff.toPlainString());
        }

        Builder meta(String key, Object value) {
            meta.put(key, value);
            return this;
        }

        BigDecimal total(String key) {
            return totals.getOrDefault(key, BigDecimal.ZERO);
        }

        /** Body with a grand total row (when there is anything to total). */
        ReportBody build(boolean totalRow) {
            List<Row> out = new ArrayList<>(rows);
            if (totalRow && !totals.isEmpty() && !rows.isEmpty()) {
                Map<String, Object> t = new LinkedHashMap<>();
                for (Column c : cols) {
                    if (totals.containsKey(c.key())) {
                        t.put(c.key(), "number".equals(c.type()) ? totals.get(c.key()) : r2(totals.get(c.key())));
                    }
                }
                t.put(cols.getFirst().key(), "Total");
                out.add(new Row("total", "total", 0, true, t, Map.of()));
            }
            Map<String, Object> tot = new LinkedHashMap<>();
            totals.forEach((k, v) -> tot.put(k, r2(v)));
            tot.put("count", rows.stream().filter(r -> "line".equals(r.kind())).count());
            return new ReportBody(cols, out, tot, checks, meta);
        }

        ReportBody build() {
            return build(true);
        }
    }
}
