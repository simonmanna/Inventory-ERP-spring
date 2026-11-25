package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Capability;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Options;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Point;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Result;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Subgroup;
import com.inventoryerp.qualitycontrol.domain.QcControlPoint;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality-control statistics (ADR-S087 §12): pass rate and trend, failures by dimension, Pareto, and SPC charts of
 * measure control points through {@link SpcCalculator}. Read-only.
 */
@Service
public class QcStatisticsService {

    /** Filters shared by the summary and the Pareto chart; dates are UTC days of {@code doneAt}, inclusive. */
    public record StatsQuery(LocalDate from, LocalDate to, String controlPointId, String productId, String workCenterId,
            String teamId) {}

    public record SpcQuery(String controlPointId, LocalDate from, LocalDate to, String productId, String workCenterId,
            Integer baseline, String rules, Integer segment) {}

    /** dimension → [key expression, label expression, join]. */
    private static final Map<String, String[]> DIMENSIONS = Map.of(
            "failure_class", new String[] {"c.\"failureClassId\"", "fc.name",
                    " LEFT JOIN \"QcFailureClass\" fc ON fc.id = c.\"failureClassId\""},
            "control_point", new String[] {"c.\"controlPointId\"", "cp.reference || ' ' || cp.title",
                    " LEFT JOIN \"QcControlPoint\" cp ON cp.id = c.\"controlPointId\""},
            "product", new String[] {"c.\"productId\"", "p.name",
                    " LEFT JOIN \"Product\" p ON p.id = c.\"productId\" AND p.\"organizationId\" = c.\"organizationId\""},
            "work_center", new String[] {"c.\"workCenterId\"", "wc.name",
                    " LEFT JOIN \"WorkCenter\" wc ON wc.id = c.\"workCenterId\""},
            "team", new String[] {"c.\"teamId\"", "t.name", " LEFT JOIN \"QcTeam\" t ON t.id = c.\"teamId\""});

    private static final Map<String, String> UNSET = Map.of("failure_class", "Unclassified", "control_point",
            "Manual (no control point)", "product", "No product", "work_center", "No work centre", "team", "No team");

    private final QcDb db;

    public QcStatisticsService(QcDb db) {
        this.db = db;
    }

    // ================================================================== summary and Pareto

    @Transactional(readOnly = true)
    public Map<String, Object> summary(StatsQuery q, String interval) {
        LocalDate to = q.to() != null ? q.to() : LocalDate.now(ZoneOffset.UTC);
        LocalDate from = q.from() != null ? q.from() : to.minusDays(89);
        List<Object> params = new ArrayList<>();
        String where = where(q, from, to, params);
        Map<String, Object> totals = SqlRows.one(db.jdbc().sql("SELECT count(*) FILTER (WHERE c.status = 'passed') AS"
                + " passed, count(*) FILTER (WHERE c.status = 'failed') AS failed, count(*) FILTER (WHERE c.status ="
                + " 'waived') AS waived FROM \"QcCheck\" c" + where).params(params));
        rate(totals);
        String unit = switch (interval == null ? "week" : interval) {
            case "day" -> "day";
            case "month" -> "month";
            default -> "week";
        };
        List<Map<String, Object>> trend = SqlRows.list(db.jdbc().sql("SELECT to_char(date_trunc('" + unit + "',"
                + " c.\"doneAt\"), 'YYYY-MM-DD') AS period, count(*) FILTER (WHERE c.status = 'passed') AS passed,"
                + " count(*) FILTER (WHERE c.status = 'failed') AS failed, count(*) FILTER (WHERE c.status = 'waived')"
                + " AS waived FROM \"QcCheck\" c" + where + " GROUP BY 1 ORDER BY 1").params(params));
        trend.forEach(QcStatisticsService::rate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("interval", unit);
        out.put("totals", totals);
        out.put("trend", trend);
        for (String dim : List.of("control_point", "product", "work_center", "team", "failure_class")) {
            out.put(camel(dim), byDimension(dim, q, from, to));
        }
        return out;
    }

    /** Failed checks (QC-done in the window) by {@code dimension}, largest first, with cumulative share. */
    @Transactional(readOnly = true)
    public Map<String, Object> pareto(StatsQuery q, String dimension) {
        String dim = dimension == null ? "failure_class" : dimension;
        String[] d = DIMENSIONS.get(dim);
        LocalDate to = q.to() != null ? q.to() : LocalDate.now(ZoneOffset.UTC);
        LocalDate from = q.from() != null ? q.from() : to.minusDays(89);
        List<Object> params = new ArrayList<>();
        String where = where(q, from, to, params);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT " + d[0] + " AS id, " + d[1] + " AS label,"
                + " count(*) AS count FROM \"QcCheck\" c" + d[2] + where + " AND c.status = 'failed' GROUP BY 1, 2"
                + " ORDER BY 3 DESC, 2").params(params));
        long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        long running = 0;
        for (Map<String, Object> r : rows) {
            long n = ((Number) r.get("count")).longValue();
            running += n;
            if (r.get("id") == null) {
                r.put("label", UNSET.get(dim));
            }
            r.put("percent", pct(n, total));
            r.put("cumulativePercent", pct(running, total));
        }
        return Obj.of("dimension", dim, "from", from.toString(), "to", to.toString(), "total", total, "rows", rows);
    }

    private List<Map<String, Object>> byDimension(String dim, StatsQuery q, LocalDate from, LocalDate to) {
        String[] d = DIMENSIONS.get(dim);
        List<Object> params = new ArrayList<>();
        String where = where(q, from, to, params);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT " + d[0] + " AS id, " + d[1] + " AS label,"
                + " count(*) FILTER (WHERE c.status = 'failed') AS failed, count(*) FILTER (WHERE c.status IN"
                + " ('passed', 'failed')) AS decided FROM \"QcCheck\" c" + d[2] + where + " GROUP BY 1, 2 HAVING"
                + " count(*) FILTER (WHERE c.status = 'failed') > 0 ORDER BY 3 DESC, 2 LIMIT 10").params(params));
        for (Map<String, Object> r : rows) {
            if (r.get("id") == null) {
                r.put("label", UNSET.get(dim));
            }
            r.put("failRate", pct(((Number) r.get("failed")).longValue(), ((Number) r.get("decided")).longValue()));
        }
        return rows;
    }

    private static String where(StatsQuery q, LocalDate from, LocalDate to, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ? AND c.status IN ('passed', 'failed',"
                + " 'waived') AND c.\"doneAt\" >= ? AND c.\"doneAt\" < ?");
        params.add(QcDb.org());
        params.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        params.add(Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        QcUtil.eq(where, params, "c.\"controlPointId\"", q.controlPointId());
        QcUtil.eq(where, params, "c.\"productId\"", q.productId());
        QcUtil.eq(where, params, "c.\"workCenterId\"", q.workCenterId());
        QcUtil.eq(where, params, "c.\"teamId\"", q.teamId());
        return where.toString();
    }

    /** Pass rate = passed / (passed + failed), in percent; waived checks are not decisions on quality. */
    private static void rate(Map<String, Object> r) {
        long passed = ((Number) r.get("passed")).longValue();
        long failed = ((Number) r.get("failed")).longValue();
        r.put("passRate", passed + failed == 0 ? null : pct(passed, passed + failed));
    }

    private static String pct(long n, long total) {
        return total == 0 ? "0" : BigDecimal.valueOf(n * 100L).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String camel(String dim) {
        return "by" + Arrays.stream(dim.split("_")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
    }

    // ================================================================== SPC

    private record Obs(BigDecimal value, Instant at, String ref, String checkId, String batch, String workOrder,
            String workCenter, String specKey, BigDecimal lsl, BigDecimal usl, BigDecimal norm) {}

    /**
     * The control chart of a measure point: current executions of its SPC-included checks (sample values when
     * recorded, else the execution's value), split into spec-version segments, grouped by the point's rational
     * subgroup setting, analysed by {@link SpcCalculator}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> spc(SpcQuery q) {
        Map<String, Object> point = db.require(QcControlPoint.class, q.controlPointId(), false,
                "Quality control point not found");
        if (!"measure".equals(QcUtil.str(point, "checkType"))) {
            throw ApiException.badRequest("SPC needs a measure control point.");
        }
        List<Object> params = new ArrayList<>(List.of(QcDb.org(), q.controlPointId()));
        StringBuilder filter = new StringBuilder();
        if (q.from() != null) {
            filter.append(" AND e.\"executedAt\" >= ?");
            params.add(Timestamp.from(q.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (q.to() != null) {
            filter.append(" AND e.\"executedAt\" < ?");
            params.add(Timestamp.from(q.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        QcUtil.eq(filter, params, "c.\"productId\"", q.productId());
        QcUtil.eq(filter, params, "c.\"workCenterId\"", q.workCenterId());
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT c.id AS \"checkId\", c.\"batchNumber\","
                + " c.\"workOrderId\", c.\"workCenterId\", c.snapshot->>'toleranceMin' AS lsl, c.snapshot->>'toleranceMax'"
                + " AS usl, c.snapshot->>'norm' AS norm, e.id AS \"executionId\", e.\"measureValue\" AS \"executionValue\","
                + " e.\"executedAt\", s.id AS \"sampleId\", s.\"measureValue\" AS \"sampleValue\", s.\"sampledAt\""
                + " FROM \"QcCheck\" c JOIN \"QcCheckExecution\" e ON e.\"checkId\" = c.id AND e.revision ="
                + " c.\"currentRevision\" LEFT JOIN \"QcCheckSample\" s ON s.\"executionId\" = e.id WHERE"
                + " c.\"organizationId\" = ? AND c.\"controlPointId\" = ? AND c.\"checkType\" = 'measure' AND"
                + " c.\"includeInSpc\" AND c.status <> 'cancelled'" + filter
                + " ORDER BY e.\"executedAt\", c.\"checkNumber\", s.\"sampleNo\"").params(params));
        long cancelled = db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" c WHERE c.\"organizationId\" = ? AND"
                + " c.\"controlPointId\" = ? AND c.\"checkType\" = 'measure' AND c.status = 'cancelled'")
                .params(QcDb.org(), q.controlPointId()).query(Long.class).single();

        // one execution → its sample values, or its own value
        Map<String, List<Map<String, Object>>> byExecution = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byExecution.computeIfAbsent(QcUtil.str(r, "executionId"), k -> new ArrayList<>()).add(r);
        }
        List<Obs> obs = new ArrayList<>();
        int missing = 0;
        for (List<Map<String, Object>> exec : byExecution.values()) {
            Map<String, Object> head = exec.getFirst();
            BigDecimal lsl = parse(head.get("lsl"));
            BigDecimal usl = parse(head.get("usl"));
            BigDecimal norm = parse(head.get("norm"));
            String spec = key(lsl) + "|" + key(usl) + "|" + key(norm);
            boolean sampled = exec.stream().anyMatch(r -> r.get("sampleValue") != null);
            if (sampled) {
                for (Map<String, Object> r : exec) {
                    if (r.get("sampleValue") == null) {
                        missing += r.get("sampleId") == null ? 0 : 1;
                        continue;
                    }
                    obs.add(new Obs((BigDecimal) r.get("sampleValue"), instant(r.get("sampledAt")),
                            QcUtil.str(r, "sampleId"), QcUtil.str(r, "checkId"), QcUtil.str(r, "batchNumber"),
                            QcUtil.str(r, "workOrderId"), QcUtil.str(r, "workCenterId"), spec, lsl, usl, norm));
                }
            } else if (head.get("executionValue") != null) {
                obs.add(new Obs((BigDecimal) head.get("executionValue"), instant(head.get("executedAt")),
                        QcUtil.str(head, "checkId"), QcUtil.str(head, "checkId"), QcUtil.str(head, "batchNumber"),
                        QcUtil.str(head, "workOrderId"), QcUtil.str(head, "workCenterId"), spec, lsl, usl, norm));
            } else {
                missing++;
            }
        }

        // a change of specification (limits or norm) starts a new segment; limits never span two
        List<List<Obs>> segments = new ArrayList<>();
        for (Obs o : obs) {
            if (segments.isEmpty() || !segments.getLast().getLast().specKey().equals(o.specKey())) {
                segments.add(new ArrayList<>());
            }
            segments.getLast().add(o);
        }
        List<Map<String, Object>> segmentInfo = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            List<Obs> s = segments.get(i);
            segmentInfo.add(Obj.of("index", i, "values", s.size(), "from", s.getFirst().at(), "to", s.getLast().at(),
                    "lsl", fmt(s.getFirst().lsl()), "usl", fmt(s.getFirst().usl()), "norm", fmt(s.getFirst().norm())));
        }
        Map<String, Object> excluded = Obj.of("missing", missing, "nonNumeric", 0, "cancelled", cancelled,
                "incompleteSubgroup", 0, "noSubgroupKey", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlPoint", Obj.of("id", point.get("id"), "reference", point.get("reference"), "title",
                point.get("title"), "unit", point.get("unit"), "decimals", point.get("decimals"), "spcSubgroup",
                point.get("spcSubgroup"), "spcSubgroupSize", point.get("spcSubgroupSize"), "spcWindowHours",
                point.get("spcWindowHours"), "spcSplitByWorkCenter", point.get("spcSplitByWorkCenter")));
        out.put("segments", segmentInfo);
        out.put("excluded", excluded);
        if (segments.isEmpty()) {
            out.put("chart", null);
            out.put("message", "No measurements recorded for this control point yet.");
            return out;
        }
        int seg = q.segment() == null ? segments.size() - 1 : q.segment();
        if (seg < 0 || seg >= segments.size()) {
            throw ApiException.badRequest("Segment " + seg + " does not exist; this point has " + segments.size()
                    + " specification segment(s).");
        }
        out.put("segment", seg);
        List<Obs> series = segments.get(seg);
        List<Subgroup> subgroups = subgroups(series, point, excluded);
        if (subgroups.isEmpty()) {
            out.put("chart", null);
            out.put("message", "Not enough values to form a subgroup.");
            return out;
        }
        Set<Integer> rules = rules(q.rules());
        Obs first = series.getFirst();
        Result r = SpcCalculator.analyze(subgroups, new Options(q.baseline(), rules, first.lsl(), first.usl()));
        out.put("rules", rules);
        out.put("spec", Obj.of("lsl", fmt(first.lsl()), "usl", fmt(first.usl()), "norm", fmt(first.norm())));
        out.put("chart", chart(r));
        out.put("capability", capability(r.capability()));
        out.put("dataset", Obj.of("checkIds", series.stream().map(Obs::checkId).distinct().toList(), "valueRefs",
                series.stream().map(Obs::ref).toList()));
        return out;
    }

    /** Rational subgroups per the point (§12); groups larger than 10 values are split in time order. */
    private static List<Subgroup> subgroups(List<Obs> series, Map<String, Object> point, Map<String, Object> excluded) {
        String mode = QcUtil.str(point, "spcSubgroup");
        boolean split = QcUtil.bool(point, "spcSplitByWorkCenter");
        List<Subgroup> out = new ArrayList<>();
        if ("individual".equals(mode)) {
            for (Obs o : series) {
                out.add(new Subgroup(o.ref(), o.at(), List.of(o.value()), List.of(o.ref())));
            }
            return out;
        }
        if ("consecutive".equals(mode)) {
            int n = point.get("spcSubgroupSize") == null ? 5 : QcUtil.integer(point, "spcSubgroupSize");
            // consecutive values of the same work centre when split
            Map<String, List<Obs>> lanes = new LinkedHashMap<>();
            for (Obs o : series) {
                lanes.computeIfAbsent(split ? String.valueOf(o.workCenter()) : "", k -> new ArrayList<>()).add(o);
            }
            int incomplete = 0;
            for (List<Obs> lane : lanes.values()) {
                int full = lane.size() / n * n;
                incomplete += lane.size() - full;
                for (int i = 0; i < full; i += n) {
                    out.add(group("#" + (out.size() + 1), lane.subList(i, i + n)));
                }
            }
            excluded.put("incompleteSubgroup", incomplete);
            out.sort((a, b) -> a.at().compareTo(b.at()));
            return out;
        }
        Map<String, List<Obs>> groups = new LinkedHashMap<>();
        int noKey = 0;
        long window = point.get("spcWindowHours") == null ? 8 : QcUtil.integer(point, "spcWindowHours");
        for (Obs o : series) {
            String k = switch (mode) {
                case "batch" -> o.batch();
                case "work_order" -> o.workOrder();
                default -> String.valueOf(Math.floorDiv(o.at().getEpochSecond(), window * 3600));
            };
            if (k == null) {
                noKey++;
                continue;
            }
            groups.computeIfAbsent(split ? o.workCenter() + "/" + k : k, x -> new ArrayList<>()).add(o);
        }
        excluded.put("noSubgroupKey", noKey);
        for (Map.Entry<String, List<Obs>> g : groups.entrySet()) {
            List<Obs> v = g.getValue();
            for (int i = 0; i < v.size(); i += SpcCalculator.MAX_SUBGROUP) {
                String key = "time_window".equals(mode) ? Instant.ofEpochSecond(Long.parseLong(g.getKey()
                        .substring(g.getKey().indexOf('/') + 1)) * window * 3600).toString() : g.getKey();
                out.add(group(i == 0 ? key : key + " #" + (i / SpcCalculator.MAX_SUBGROUP + 1),
                        v.subList(i, Math.min(v.size(), i + SpcCalculator.MAX_SUBGROUP))));
            }
        }
        out.sort((a, b) -> a.at().compareTo(b.at()));
        return out;
    }

    private static Subgroup group(String key, List<Obs> obs) {
        return new Subgroup(key, obs.getFirst().at(), obs.stream().map(Obs::value).toList(),
                obs.stream().map(Obs::ref).toList());
    }

    private static Set<Integer> rules(String spec) {
        if (spec == null || spec.isBlank()) {
            return new TreeSet<>(Set.of(1, 4));
        }
        return Arrays.stream(spec.split(",")).map(String::trim).map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Object> chart(Result r) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("method", r.method());
        c.put("sigmaEstimator", r.sigmaEstimator());
        c.put("values", r.values());
        c.put("subgroups", r.subgroups());
        c.put("baselineSubgroups", r.baselineSubgroups());
        c.put("limitsType", r.limitsType());
        c.put("provisional", r.provisional());
        c.put("center", fmt(r.center()));
        c.put("ucl", fmt(r.ucl()));
        c.put("lcl", fmt(r.lcl()));
        c.put("rangeCenter", fmt(r.rangeCenter()));
        c.put("rangeUcl", fmt(r.rangeUcl()));
        c.put("rangeLcl", fmt(r.rangeLcl()));
        c.put("sigma", fmt(r.sigma()));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Point p : r.points()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", p.index());
            m.put("key", p.key());
            m.put("at", p.at());
            m.put("n", p.n());
            m.put("value", fmt(p.value()));
            m.put("range", fmt(p.range()));
            m.put("ucl", fmt(p.ucl()));
            m.put("lcl", fmt(p.lcl()));
            m.put("rangeUcl", fmt(p.rangeUcl()));
            m.put("rangeLcl", fmt(p.rangeLcl()));
            m.put("baseline", p.baseline());
            m.put("violations", p.violations());
            points.add(m);
        }
        c.put("points", points);
        c.put("violations", r.points().stream().filter(p -> !p.violations().isEmpty()).count());
        return c;
    }

    private static Map<String, Object> capability(Capability c) {
        return Obj.of("valid", c.valid(), "reason", c.reason(), "n", c.n(), "mean", fmt(c.mean()), "sigma",
                fmt(c.sigma()), "cp", fmt(c.cp()), "cpk", fmt(c.cpk()), "cpu", fmt(c.cpu()), "cpl", fmt(c.cpl()));
    }

    static String fmt(BigDecimal d) {
        if (d == null) {
            return null;
        }
        BigDecimal r = d.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return (r.scale() < 0 ? r.setScale(0) : r).toPlainString();
    }

    private static BigDecimal parse(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(BigDecimal d) {
        return d == null ? "-" : d.stripTrailingZeros().toPlainString();
    }

    private static Instant instant(Object v) {
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof java.time.LocalDateTime l) {
            return l.toInstant(ZoneOffset.UTC);
        }
        return Objects.requireNonNull((java.time.OffsetDateTime) v).toInstant();
    }
}
