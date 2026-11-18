package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operations insights (ADR-S077 §10): computes the summary rows of one branch and business day from the event ledger:
 * the camera-free metrics of LP6a and the camera metrics of LP6b (footfall, queues, table occupancy). An accepted event
 * makes its day dirty; the insights job computes dirty days once they have settled, and a day is computed again
 * whenever a late event arrives. The ledger is only read.
 *
 * <p>What each metric's count and total add up is listed in {@link #METRICS} and in the spec.
 */
@Service
public class LpInsightsService {

    private static final Logger log = LoggerFactory.getLogger(LpInsightsService.class);

    /** The event types a metric reads. Any other accepted event leaves the summaries alone. */
    public static final Set<String> SOURCE_TYPES = Set.of(LpEventTypes.SALE_COMPLETED, LpEventTypes.SALE_LINE_VOIDED,
            LpEventTypes.REFUND_ISSUED, LpEventTypes.CASH_SESSION_CLOSED, LpEventTypes.KITCHEN_TICKET_CREATED,
            LpEventTypes.KITCHEN_STAGE, LpEventTypes.STOCK_COUNT_LINE, LpEventTypes.VISION_LINE_CROSSED,
            LpEventTypes.VISION_DWELL, LpEventTypes.VISION_OCCUPANCY, LpEventTypes.VISION_GAP);

    /** Metric code → what its count and its total are. Shown by the routes, so a figure always has its definition. */
    public static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("sales", "count: completed sales; total: amount charged after discount"),
            Map.entry("sale_items", "count: completed sales; total: item quantity sold"),
            Map.entry("sale_discount", "count: sales with a discount; total: discount given"),
            Map.entry("sale_table_recorded", "count: sales whose payload has the tableId field (null when the sale"
                    + " had no table); sales projected before LP6a lack it"),
            Map.entry("voids", "count: voided lines (largest quantity per line); total: voided amount"),
            Map.entry("refunds", "count: refunds; total: amount refunded"),
            Map.entry("kitchen_tickets", "count: kitchen tickets created"),
            Map.entry("kitchen_recalls", "count: tickets recalled (remade) at least once"),
            Map.entry("kitchen_prep", "count: tickets marked ready; total: seconds from created to ready"),
            Map.entry("kitchen_serve", "count: tickets dispatched; total: seconds from created to dispatched"),
            Map.entry("table_orders", "count: sales with a table; total: their amount"),
            Map.entry("tables_used", "count: distinct tables with a sale that day"),
            Map.entry("cash_session", "per session: count 1; total: last closing difference (counted − expected)"),
            Map.entry("pour_lines", "count: bottle-count lines"),
            Map.entry("pour_sold_ml", "count: lines with sold and variance; total: ml drawn by sales"),
            Map.entry("pour_consumed_ml", "count: same lines; total: ml consumed (sold − variance)"),
            Map.entry("pour_consumed_cost", "count: those with a cost; total: consumed ml × cost per ml"),
            // LP6b: from cameras. They count people; they never identify anyone.
            Map.entry("footfall_in", "count: people who crossed an entrance line inward"),
            Map.entry("footfall_out", "count: people who crossed an entrance line outward"),
            Map.entry("queue_wait", "count: people who waited in a queue zone at least its minWaitSeconds; total:"
                    + " seconds waited; p50, p90: of the waits"),
            Map.entry("queue_seconds", "count: periods counted in queue zones; total: seconds observed"),
            Map.entry("queue_length", "count: the same periods; total: person-seconds (people × seconds); p50, p90:"
                    + " of the people per period"),
            Map.entry("table_seconds", "count: periods counted in dining zones; total: seconds observed"),
            Map.entry("table_occupied", "count: periods with a person; total: seconds with a person"),
            Map.entry("table_seatings", "count: seatings (a period with a person right after one without)"),
            Map.entry("table_wait_order", "count: seatings with a first kitchen ticket for the zone's table; total:"
                    + " seconds from the seating to that ticket; p50, p90: of those waits"),
            Map.entry("camera_gap", "count: gaps of entrance, queue and dining zones; total: seconds without a usable"
                    + " picture, on the day the gap is stored"));

    /** A day that is due once nothing has marked it for this long. */
    static final Duration SETTLE = Duration.ofSeconds(60);
    static final int MAX_DAYS_PER_TICK = 200;
    static final int MAX_RECOMPUTE_DAYS = 92;
    private static final Pattern ZONE = Pattern.compile("[A-Za-z0-9/_+\\-]+");

    /** A branch ('' for none) and a business day. */
    public record DayKey(String branchId, LocalDate businessDate) {}

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final TransactionTemplate newTx;
    private final Clock clock = Clock.systemUTC();

    public LpInsightsService(JdbcClient jdbc, LpScope scope, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ marking (called by ingest)

    /**
     * The days an accepted event changes. A kitchen stage also changes the day before: a ticket created before
     * midnight and finished after it belongs to the day it was created.
     */
    public static Set<DayKey> keysFor(String type, String branchId, LocalDate businessDate) {
        if (!SOURCE_TYPES.contains(type) || businessDate == null) {
            return Set.of();
        }
        String branch = branchId == null ? "" : branchId;
        if (LpEventTypes.KITCHEN_STAGE.equals(type)) {
            return Set.of(new DayKey(branch, businessDate), new DayKey(branch, businessDate.minusDays(1)));
        }
        return Set.of(new DayKey(branch, businessDate));
    }

    /**
     * As {@link #keysFor(String, String, LocalDate)}; an occupancy period belongs to the day it started, so one
     * reported in the first hour of a day also changes the day before (LP6b).
     */
    public static Set<DayKey> keysFor(String type, String branchId, LocalDate businessDate, Instant occurredAt) {
        Set<DayKey> keys = keysFor(type, branchId, businessDate);
        if (!LpEventTypes.VISION_OCCUPANCY.equals(type) || occurredAt == null || keys.isEmpty()) {
            return keys;
        }
        java.time.ZonedDateTime local = occurredAt.atZone(BusinessZone.get());
        if (!local.toLocalDate().equals(businessDate) || local.getHour() > 0) {
            return keys;
        }
        String branch = branchId == null ? "" : branchId;
        return Set.of(new DayKey(branch, businessDate), new DayKey(branch, businessDate.minusDays(1)));
    }

    /** Marks days dirty in the caller's transaction. A day already dirty keeps its first marking time. */
    public void markDirty(Set<DayKey> days) {
        String org = LpScope.org();
        for (DayKey d : days) {
            jdbc.sql("INSERT INTO \"LpMetricDay\" (id, \"organizationId\", \"branchId\", \"businessDate\")"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"branchId\", \"businessDate\") DO"
                    + " UPDATE SET version = \"LpMetricDay\".version + 1, \"dirtyAt\" = CASE WHEN"
                    + " \"LpMetricDay\".state = 'dirty' THEN \"LpMetricDay\".\"dirtyAt\" ELSE CURRENT_TIMESTAMP END,"
                    + " state = 'dirty'")
                    .params(UUID.randomUUID().toString(), org, d.branchId(), java.sql.Date.valueOf(d.businessDate()))
                    .update();
        }
    }

    /**
     * Marks every day of a period that has metric events dirty, for the caller's branches (or one branch), so the
     * job computes them again: after the definitions change, or for events stored before LP6a.
     */
    @Transactional
    public Map<String, Object> recompute(LpCommands.InsightsRecomputeInput in) {
        LocalDate from = LpScope.has(in.from()) ? LpEventValidator.date(in.from()) : null;
        LocalDate to = LpScope.has(in.to()) ? LpEventValidator.date(in.to()) : null;
        if (from == null || to == null) {
            throw ApiException.badRequest("from and to must be ISO dates (YYYY-MM-DD)");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.toEpochDay() - from.toEpochDay() + 1 > MAX_RECOMPUTE_DAYS) {
            throw ApiException.badRequest("A recompute covers at most " + MAX_RECOMPUTE_DAYS + " days");
        }
        scope.assertBranch(in.branchId());
        List<Object> params = new ArrayList<>();
        String w = scope.where("e", params).replace("e.\"branchId\" = ANY", "coalesce(e.\"branchId\", '') = ANY")
                + " AND e.type = ANY(?::text[]) AND e.\"businessDate\" >= ? AND e.\"businessDate\" <= ?";
        params.add(SOURCE_TYPES.toArray(String[]::new));
        params.add(java.sql.Date.valueOf(from));
        params.add(java.sql.Date.valueOf(to));
        if (LpScope.has(in.branchId())) {
            w += " AND e.\"branchId\" = ?";
            params.add(in.branchId());
        }
        Set<DayKey> days = new HashSet<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT DISTINCT coalesce(e.\"branchId\", '') AS branch,"
                + " e.\"businessDate\" AS day FROM \"LpEvent\" e" + w).params(params))) {
            days.add(new DayKey((String) r.get("branch"), (LocalDate) r.get("day")));
        }
        markDirty(days);
        return Obj.of("from", from, "to", to, "days", days.size());
    }

    // ------------------------------------------------------------------ computing (called by the job)

    /** Computes the organization's settled dirty days, oldest first. Returns how many were computed. */
    public int computeDue() {
        String org = LpScope.org();
        Instant cutoff = clock.instant().minus(SETTLE);
        List<Map<String, Object>> due = SqlRows.list(jdbc.sql("SELECT id, \"branchId\", \"businessDate\", version FROM"
                + " \"LpMetricDay\" WHERE \"organizationId\" = ? AND state = 'dirty' AND \"dirtyAt\" <= ? ORDER BY"
                + " \"businessDate\", \"branchId\" LIMIT " + MAX_DAYS_PER_TICK).params(org, SqlRows.ts(cutoff)));
        int done = 0;
        for (Map<String, Object> d : due) {
            String id = (String) d.get("id");
            DayKey key = new DayKey((String) d.get("branchId"), (LocalDate) d.get("businessDate"));
            long version = ((Number) d.get("version")).longValue();
            try {
                newTx.executeWithoutResult(s -> compute(id, key, version));
                done++;
            } catch (RuntimeException err) {
                log.warn("loss prevention insights: day {} {} of org {} failed: {}", key.businessDate(),
                        key.branchId(), org, err.toString());
                newTx.executeWithoutResult(s -> jdbc.sql("UPDATE \"LpMetricDay\" SET \"lastError\" = ?, attempts ="
                        + " attempts + 1 WHERE id = ?").params(abbreviate(err.toString()), id).update());
            }
        }
        return done;
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    /** Replaces the summary rows of one day. The day stays dirty when it was marked again meanwhile. */
    void compute(String dayId, DayKey key, long version) {
        String org = LpScope.org();
        Object[] day = {org, key.branchId(), java.sql.Date.valueOf(key.businessDate())};
        jdbc.sql("DELETE FROM \"LpMetricValue\" WHERE \"organizationId\" = ? AND \"branchId\" = ? AND"
                + " \"businessDate\" = ?").params(day).update();
        int rows = 0;
        rows += insert(salesSql(), day, day);
        rows += insert(voidsSql(), day, day);
        rows += insert(refundsSql(), day, day);
        rows += insert(kitchenSql(), day, new Object[] {org, org, org}, day);
        rows += insert(tablesSql(), day, day);
        rows += insert(cashSql(), day, day);
        rows += insert(pourSql(), day, day);
        // LP6b: camera metrics.
        Object[] date = {java.sql.Date.valueOf(key.businessDate())};
        rows += insert(footfallSql(), day, day);
        rows += insert(queueWaitSql(), day, day);
        rows += insert(queueLengthSql(), day, date, date, day);
        rows += insert(tableOccupancySql(), day, date, date, day);
        rows += insert(seatingsSql(), day, date, date, new Object[] {org}, day);
        rows += insert(cameraGapSql(), day, day);
        Long maxSeq = jdbc.sql("SELECT max(seq) FROM \"LpEvent\" WHERE \"organizationId\" = ? AND"
                + " coalesce(\"branchId\", '') = ? AND \"businessDate\" = ?").params(day).query(Long.class).single();
        Timestamp now = SqlRows.ts(clock.instant());
        jdbc.sql("UPDATE \"LpMetricDay\" SET state = CASE WHEN version = ? THEN 'computed' ELSE 'dirty' END,"
                + " \"computedAt\" = ?, \"maxSeq\" = ?, \"rowCount\" = ?, \"lastError\" = NULL, attempts = 0 WHERE"
                + " id = ?")
                .params(version, now, maxSeq, rows, dayId).update();
    }

    private int insert(String sql, Object[]... paramGroups) {
        List<Object> params = new ArrayList<>();
        for (Object[] g : paramGroups) {
            params.addAll(List.of(g));
        }
        return jdbc.sql(sql).params(params).update();
    }

    // ------------------------------------------------------------------ SQL, one statement per source

    private static final String COLUMNS = "INSERT INTO \"LpMetricValue\" (\"organizationId\", \"branchId\","
            + " \"businessDate\", hour, metric, dimension, \"dimensionKey\", count, total, p50, p90) ";
    /** Events of one type, organization, branch and business day: params org, branch, day. */
    private static final String DAY = " FROM \"LpEvent\" e WHERE e.\"organizationId\" = ? AND coalesce(e.\"branchId\","
            + " '') = ? AND e.\"businessDate\" = ? AND e.type = ";
    /** The constant columns of every insert: params org, branch, day. */
    private static final String KEY = "SELECT ?, ?, ?::date, ";

    /** The business time zone, checked before it is written into SQL. */
    private static String businessZone() {
        String zone = BusinessZone.get().getId();
        if (!ZONE.matcher(zone).matches()) {
            throw new IllegalStateException("Unexpected time zone id " + zone);
        }
        return zone;
    }

    /** The hour of the event in the business time zone. */
    static String hourOf(String column) {
        return "extract(hour FROM (" + column + " AT TIME ZONE 'UTC') AT TIME ZONE '" + businessZone() + "')::int";
    }

    /** The calendar date of a time in the business time zone. */
    static String localDate(String column) {
        return "((" + column + " AT TIME ZONE 'UTC') AT TIME ZONE '" + businessZone() + "')::date";
    }

    private static String quoted(String type) {
        return "'" + type + "'";
    }

    /**
     * Rows grouped by a key and by hour: the total, each hour, each key, and each key per hour. {@code values} lists
     * {@code (metric, count, total)} over the aggregate columns of {@code g}.
     */
    private static String byKeyAndHour(String source, String aggregates, String dimension, String values) {
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, GROUPING(h) AS gh, k, h, " + aggregates
                + " FROM s GROUP BY GROUPING SETS ((), (h), (k), (k, h)) " + SOME + ") " + COLUMNS + KEY
                + "CASE WHEN g.gh = 1 THEN -1 ELSE g.h END, v.metric, CASE WHEN g.gk = 1 THEN '' ELSE '" + dimension
                + "' END, CASE WHEN g.gk = 1 THEN '' ELSE g.k END, v.n, v.total, NULL, NULL FROM g CROSS JOIN LATERAL"
                + " (VALUES " + values + ") v(metric, n, total)";
    }

    /**
     * Grouping sets give the whole-day group even when the day has no row of the source; that group (a count of 0 and
     * empty sums) is not written, so a day without, say, refunds has no refund rows instead of failing.
     */
    private static final String SOME = "HAVING count(*) > 0";

    /** As {@link #byKeyAndHour}, with {@code (metric, count, total, p50, p90)} values. */
    private static String byKeyAndHourWithPercentiles(String source, String aggregates, String dimension,
            String values) {
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, GROUPING(h) AS gh, k, h, " + aggregates
                + " FROM s GROUP BY GROUPING SETS ((), (h), (k), (k, h)) " + SOME + ") " + COLUMNS + KEY
                + "CASE WHEN g.gh = 1 THEN -1 ELSE g.h END, v.metric, CASE WHEN g.gk = 1 THEN '' ELSE '" + dimension
                + "' END, CASE WHEN g.gk = 1 THEN '' ELSE g.k END, v.n, v.total, round(v.p50::numeric, 2),"
                + " round(v.p90::numeric, 2) FROM g CROSS JOIN LATERAL (VALUES " + values + ") v(metric, n, total, p50,"
                + " p90)";
    }

    /** A sale sent again (same transaction) counts once, as its latest copy. */
    static String salesSql() {
        String source = "SELECT DISTINCT ON (coalesce(e.\"transactionId\", e.id)) coalesce(e.\"actorId\", '') AS k, "
                + hourOf("e.\"occurredAt\"") + " AS h, coalesce(lp_num(e.payload->>'total'), 0) AS total,"
                + " coalesce(lp_num(e.payload->>'discountTotal'), 0) AS discount,"
                + " jsonb_exists(e.payload, 'tableId') AS \"tableField\", (SELECT"
                + " coalesce(sum(lp_num(l->>'quantity')), 0) FROM jsonb_array_elements(CASE WHEN"
                + " jsonb_typeof(e.payload->'lines') = 'array' THEN e.payload->'lines' ELSE '[]'::jsonb END) l)"
                + " AS items" + DAY + quoted(LpEventTypes.SALE_COMPLETED)
                + " ORDER BY coalesce(e.\"transactionId\", e.id), e.seq DESC";
        return byKeyAndHour(source, "count(*) AS n, sum(total) AS total, sum(items) AS items, count(*) FILTER (WHERE"
                + " discount > 0) AS dn, sum(discount) AS discount, count(*) FILTER (WHERE \"tableField\") AS tn",
                "actor", "('sales', g.n, g.total), ('sale_items', g.n, g.items), ('sale_discount', g.dn, g.discount),"
                + " ('sale_table_recorded', g.tn, 0)");
    }

    /** A line voided in steps is sent once per step with the quantity so far: the largest counts. */
    static String voidsSql() {
        String source = "SELECT DISTINCT ON (coalesce(e.\"lineId\", e.id)) coalesce(e.\"actorId\", '') AS k, "
                + hourOf("e.\"occurredAt\"") + " AS h, coalesce(lp_num(e.payload->>'amount'), 0) AS amount" + DAY
                + quoted(LpEventTypes.SALE_LINE_VOIDED) + " ORDER BY coalesce(e.\"lineId\", e.id),"
                + " lp_num(e.payload->>'quantity') DESC NULLS LAST, e.seq DESC";
        return byKeyAndHour(source, "count(*) AS n, sum(amount) AS total", "actor", "('voids', g.n, g.total)");
    }

    static String refundsSql() {
        String source = "SELECT coalesce(e.\"actorId\", '') AS k, " + hourOf("e.\"occurredAt\"") + " AS h,"
                + " coalesce(lp_num(e.payload->>'amount'), 0) AS amount" + DAY + quoted(LpEventTypes.REFUND_ISSUED);
        return byKeyAndHour(source, "count(*) AS n, sum(amount) AS total", "actor", "('refunds', g.n, g.total)");
    }

    /**
     * Tickets created on the day, with the first ready and dispatched times of each, wherever they fall. A time
     * before creation (a sender's clock) is left out. Params: day, then the organization three times.
     */
    static String kitchenSql() {
        String stage = "(SELECT min(x.\"occurredAt\") FROM \"LpEvent\" x WHERE x.\"organizationId\" = ? AND"
                + " x.\"ticketId\" = t.ticket AND x.type = " + quoted(LpEventTypes.KITCHEN_STAGE)
                + " AND x.payload->>'stage' = ";
        String source = "WITH t AS (SELECT DISTINCT ON (e.\"ticketId\") e.\"ticketId\" AS ticket,"
                + " coalesce(e.payload->>'station', '') AS k, " + hourOf("e.\"occurredAt\"") + " AS h, e.\"occurredAt\""
                + " AS created" + DAY + quoted(LpEventTypes.KITCHEN_TICKET_CREATED) + " AND e.\"ticketId\" IS NOT NULL"
                + " ORDER BY e.\"ticketId\", e.\"occurredAt\") SELECT t.k, t.h, extract(epoch FROM " + stage
                + "'prepared') - t.created) AS prep, extract(epoch FROM " + stage + "'dispatched') - t.created) AS"
                + " serve, EXISTS (SELECT 1 FROM \"LpEvent\" x WHERE x.\"organizationId\" = ? AND x.\"ticketId\" ="
                + " t.ticket AND x.type = " + quoted(LpEventTypes.KITCHEN_STAGE) + " AND x.payload->>'stage' ="
                + " 'remade') AS recalled FROM t";
        String aggregates = "count(*) AS n, count(*) FILTER (WHERE recalled) AS rn, count(prep) FILTER (WHERE prep >="
                + " 0) AS pn, coalesce(sum(prep) FILTER (WHERE prep >= 0), 0) AS ps, percentile_cont(0.5) WITHIN GROUP"
                + " (ORDER BY prep) FILTER (WHERE prep >= 0) AS pp50, percentile_cont(0.9) WITHIN GROUP (ORDER BY"
                + " prep) FILTER (WHERE prep >= 0) AS pp90, count(serve) FILTER (WHERE serve >= 0) AS sn,"
                + " coalesce(sum(serve) FILTER (WHERE serve >= 0), 0) AS ss, percentile_cont(0.5) WITHIN GROUP (ORDER"
                + " BY serve) FILTER (WHERE serve >= 0) AS sp50, percentile_cont(0.9) WITHIN GROUP (ORDER BY serve)"
                + " FILTER (WHERE serve >= 0) AS sp90";
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, GROUPING(h) AS gh, k, h, " + aggregates
                + " FROM s GROUP BY GROUPING SETS ((), (h), (k), (k, h)) " + SOME + ") " + COLUMNS + KEY
                + "CASE WHEN g.gh = 1 THEN -1 ELSE g.h END, v.metric, CASE WHEN g.gk = 1 THEN '' ELSE 'station' END,"
                + " CASE WHEN g.gk = 1 THEN '' ELSE g.k END, v.n, v.total, round(v.p50::numeric, 2),"
                + " round(v.p90::numeric, 2) FROM g CROSS JOIN LATERAL (VALUES ('kitchen_tickets', g.n, 0::numeric,"
                + " NULL::float8, NULL::float8), ('kitchen_recalls', g.rn, 0, NULL, NULL), ('kitchen_prep', g.pn,"
                + " g.ps, g.pp50, g.pp90), ('kitchen_serve', g.sn, g.ss, g.sp50, g.sp90)) v(metric, n, total, p50,"
                + " p90)";
    }

    /** Sales that name a table ({@code payload.tableId}), per table, and how many tables had one. */
    static String tablesSql() {
        String source = "SELECT DISTINCT ON (coalesce(e.\"transactionId\", e.id)) e.payload->>'tableId' AS k,"
                + " coalesce(lp_num(e.payload->>'total'), 0) AS total" + DAY + quoted(LpEventTypes.SALE_COMPLETED)
                + " AND coalesce(e.payload->>'tableId', '') <> '' ORDER BY coalesce(e.\"transactionId\", e.id), e.seq"
                + " DESC";
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, k, count(*) AS n, sum(total) AS total,"
                + " count(DISTINCT k) AS tables FROM s GROUP BY GROUPING SETS ((), (k)) " + SOME + ") " + COLUMNS + KEY
                + "-1, v.metric, CASE WHEN g.gk = 1 THEN '' ELSE 'table' END, CASE WHEN g.gk = 1 THEN '' ELSE g.k END,"
                + " v.n, v.total, NULL, NULL FROM g CROSS JOIN LATERAL (VALUES ('table_orders', g.n, g.total),"
                + " ('tables_used', g.tables, 0)) v(metric, n, total) WHERE g.gk = 1 OR v.metric = 'table_orders'";
    }

    /** One row per session closed on the day: its last closing difference. */
    static String cashSql() {
        String source = "SELECT DISTINCT ON (coalesce(e.\"sessionId\", e.id)) coalesce(e.\"sessionId\", e.id) AS k,"
                + " lp_num(e.payload->>'difference') AS d" + DAY + quoted(LpEventTypes.CASH_SESSION_CLOSED)
                + " ORDER BY coalesce(e.\"sessionId\", e.id), e.\"occurredAt\" DESC, e.seq DESC";
        return "WITH c AS (" + source + ") " + COLUMNS + KEY + "-1, 'cash_session', 'session', c.k, 1, c.d, NULL, NULL"
                + " FROM c WHERE c.d IS NOT NULL";
    }

    /**
     * Bottle-count lines per product (a line sent again replaces the earlier copy). Consumed volume is what sales
     * drew plus the shortage: {@code soldMl − varianceMl}, since the variance is counted − system.
     */
    static String pourSql() {
        String source = "SELECT k, sold, CASE WHEN sold IS NOT NULL AND variance IS NOT NULL THEN sold - variance END"
                + " AS consumed, cost FROM (SELECT DISTINCT ON (coalesce(e.\"lineId\", e.id))"
                + " coalesce(e.payload->>'productId', '') AS k, lp_num(e.payload->>'soldMl') AS sold,"
                + " lp_num(e.payload->>'varianceMl') AS variance, lp_num(e.payload->>'costPerMl') AS cost" + DAY
                + quoted(LpEventTypes.STOCK_COUNT_LINE) + " ORDER BY coalesce(e.\"lineId\", e.id), e.seq DESC) l";
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, k, count(*) AS n, count(consumed) AS cn,"
                + " coalesce(sum(sold) FILTER (WHERE consumed IS NOT NULL), 0) AS sold, coalesce(sum(consumed), 0) AS"
                + " consumed, count(consumed) FILTER (WHERE cost IS NOT NULL) AS kn, coalesce(sum(consumed * cost)"
                + " FILTER (WHERE cost IS NOT NULL), 0) AS cost FROM s GROUP BY GROUPING SETS ((), (k)) " + SOME + ") "
                + COLUMNS
                + KEY + "-1, v.metric, CASE WHEN g.gk = 1 THEN '' ELSE 'product' END, CASE WHEN g.gk = 1 THEN '' ELSE"
                + " g.k END, v.n, v.total, NULL, NULL FROM g CROSS JOIN LATERAL (VALUES ('pour_lines', g.n,"
                + " 0::numeric), ('pour_sold_ml', g.cn, g.sold), ('pour_consumed_ml', g.cn, g.consumed),"
                + " ('pour_consumed_cost', g.kn, g.cost)) v(metric, n, total)";
    }

    // ------------------------------------------------------------------ LP6b: camera metrics

    /**
     * Observations of one type from the zones of some types, of one organization, branch and day: params org, branch,
     * day. {@code zoneTypes} is SQL text: quoted names.
     */
    private static String zoned(String type, String zoneTypes) {
        return " FROM \"LpEvent\" e JOIN \"LpZone\" z ON z.id = e.\"deviceId\" AND z.\"organizationId\" ="
                + " e.\"organizationId\" WHERE e.\"organizationId\" = ? AND coalesce(e.\"branchId\", '') = ? AND"
                + " e.\"businessDate\" = ? AND e.type = " + quoted(type) + " AND z.\"zoneType\" IN (" + zoneTypes + ")";
    }

    /** People only: an observation with no class is taken as one of people. */
    private static final String PERSON = " AND coalesce(e.payload->>'class', 'person') = 'person'";

    /** When an occupancy period started: it is reported at its end. */
    static final String PERIOD_START = "(e.\"occurredAt\" - make_interval(secs =>"
            + " coalesce(lp_num(e.payload->>'periodSeconds'), 0)::float8))";

    /**
     * People-occupancy periods of a zone type that started on the day: params org, branch, day (the earliest day
     * stored), day (the day after is the latest), day (the start's date). A period ending just after midnight is
     * stored under the next day.
     */
    private static String occupancyFrom(String zoneType, boolean startedOnDay) {
        return " FROM \"LpEvent\" e JOIN \"LpZone\" z ON z.id = e.\"deviceId\" AND z.\"organizationId\" ="
                + " e.\"organizationId\" WHERE e.\"organizationId\" = ? AND coalesce(e.\"branchId\", '') = ? AND"
                + " e.\"businessDate\" >= ?::date - " + (startedOnDay ? 0 : 1)
                + " AND e.\"businessDate\" <= ?::date + 1"
                + " AND e.type = " + quoted(LpEventTypes.VISION_OCCUPANCY) + " AND z.\"zoneType\" = " + quoted(zoneType)
                + PERSON + (startedOnDay ? " AND " + localDate(PERIOD_START) + " = ?::date" : "");
    }

    /** People crossing an entrance line, in and out, per hour and entrance. */
    static String footfallSql() {
        String n = "coalesce(lp_num(e.payload->>'count'), 1)";
        String source = "SELECT e.\"deviceId\" AS k, " + hourOf("e.\"occurredAt\"") + " AS h, CASE WHEN"
                + " e.payload->>'direction' = 'in' THEN " + n + " ELSE 0 END AS i, CASE WHEN"
                + " e.payload->>'direction' = 'out' THEN " + n + " ELSE 0 END AS o"
                + zoned(LpEventTypes.VISION_LINE_CROSSED, "'entrance'") + PERSON;
        return byKeyAndHour(source, "sum(i)::bigint AS i, sum(o)::bigint AS o", "zone",
                "('footfall_in', g.i, 0), ('footfall_out', g.o, 0)");
    }

    /** One wait per person who left a queue zone, at the hour the wait ended. */
    static String queueWaitSql() {
        String seconds = "coalesce(lp_num(e.payload->>'durationSeconds'), extract(epoch FROM"
                + " ((e.payload->>'endedAt')::timestamptz - (e.payload->>'startedAt')::timestamptz)))";
        String source = "SELECT e.\"deviceId\" AS k, " + hourOf("e.\"occurredAt\"") + " AS h, greatest(0, " + seconds
                + ") AS d" + zoned(LpEventTypes.VISION_DWELL, "'queue'") + PERSON;
        return byKeyAndHourWithPercentiles(source, "count(*) AS n, coalesce(sum(d), 0) AS total,"
                + " percentile_cont(0.5) WITHIN GROUP (ORDER BY d) AS p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY"
                + " d) AS p90", "zone", "('queue_wait', g.n, g.total, g.p50, g.p90)");
    }

    /** How many waited, per period of a queue zone: seconds observed and person-seconds. */
    static String queueLengthSql() {
        String source = "SELECT e.\"deviceId\" AS k, " + hourOf(PERIOD_START) + " AS h,"
                + " coalesce(lp_num(e.payload->>'count'), 0) AS c, coalesce(lp_num(e.payload->>'periodSeconds'), 0)"
                + " AS secs" + occupancyFrom("queue", true);
        return byKeyAndHourWithPercentiles(source, "count(*) AS n, coalesce(sum(secs), 0) AS secs,"
                + " coalesce(sum(c * secs), 0) AS ps, percentile_cont(0.5) WITHIN GROUP (ORDER BY c) AS p50,"
                + " percentile_cont(0.9) WITHIN GROUP (ORDER BY c) AS p90", "zone", "('queue_seconds', g.n, g.secs,"
                + " NULL::float8, NULL::float8), ('queue_length', g.n, g.ps, g.p50, g.p90)");
    }

    /** Seconds each dining zone was observed, and seconds it had a person. */
    static String tableOccupancySql() {
        String source = "SELECT e.\"deviceId\" AS k, " + hourOf(PERIOD_START) + " AS h,"
                + " coalesce(lp_num(e.payload->>'count'), 0) AS c, coalesce(lp_num(e.payload->>'periodSeconds'), 0)"
                + " AS secs" + occupancyFrom("dining", true);
        return byKeyAndHour(source, "count(*) AS n, coalesce(sum(secs), 0) AS secs, count(*) FILTER (WHERE c > 0)"
                + " AS np, coalesce(sum(secs) FILTER (WHERE c > 0), 0) AS occ", "zone",
                "('table_seconds', g.n, g.secs), ('table_occupied', g.np, g.occ)");
    }

    /**
     * Seatings of dining zones and, for a zone linked to a table, the wait from the seating to the first kitchen
     * ticket of that table before the table is next empty (at most 4 hours). A seating is a period with a person that
     * starts where a period without one ended; after a gap in the counts nothing is a seating. Params: org, branch,
     * day, day, day, org.
     */
    static String seatingsSql() {
        String p = "SELECT e.\"deviceId\" AS k, z.\"linkedType\" AS lt, z.\"linkedId\" AS tbl, " + PERIOD_START
                + " AS ps, e.\"occurredAt\" AS pe, coalesce(lp_num(e.payload->>'count'), 0) AS c"
                + occupancyFrom("dining", false);
        String seat = "SELECT q.k, q.lt, q.tbl, q.ps AS seated, (SELECT min(x.ps) FROM q x WHERE x.k = q.k AND x.ps >"
                + " q.ps AND x.c = 0) AS freed FROM q WHERE q.c > 0 AND q.pc = 0 AND q.ppe >= q.ps - interval '1"
                + " second' AND " + localDate("q.ps") + " = ?::date";
        String limit = "least(coalesce(seat.freed, seat.seated + interval '4 hours'), seat.seated + interval '4"
                + " hours')";
        String source = "WITH p AS (" + p + "), q AS (SELECT p.*, lag(p.c) OVER w AS pc, lag(p.pe) OVER w AS ppe FROM"
                + " p WINDOW w AS (PARTITION BY p.k ORDER BY p.ps, p.pe)), seat AS (" + seat + ") SELECT seat.k, "
                + hourOf("seat.seated") + " AS h, CASE WHEN seat.lt = 'table' THEN extract(epoch FROM (SELECT"
                + " min(t.\"occurredAt\") FROM \"LpEvent\" t WHERE t.\"organizationId\" = ? AND t.type = "
                + quoted(LpEventTypes.KITCHEN_TICKET_CREATED) + " AND t.payload->>'tableId' = seat.tbl AND"
                + " t.\"occurredAt\" >= seat.seated AND t.\"occurredAt\" < " + limit + ") - seat.seated) END AS d FROM"
                + " seat";
        return byKeyAndHourWithPercentiles(source, "count(*) AS n, count(d) AS dn, coalesce(sum(d), 0) AS dt,"
                + " percentile_cont(0.5) WITHIN GROUP (ORDER BY d) AS p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY"
                + " d) AS p90", "zone", "('table_seatings', g.n, 0::numeric, NULL::float8, NULL::float8),"
                + " ('table_wait_order', g.dn, g.dt, g.p50, g.p90)");
    }

    /** Seconds without a usable picture of the zones the camera metrics read, per zone (day rows only). */
    static String cameraGapSql() {
        String source = "SELECT e.\"deviceId\" AS k, greatest(0, extract(epoch FROM ((e.payload->>'to')::timestamptz"
                + " - (e.payload->>'from')::timestamptz))) AS secs"
                + zoned(LpEventTypes.VISION_GAP, "'entrance', 'queue', 'dining'");
        return "WITH s AS (" + source + "), g AS (SELECT GROUPING(k) AS gk, k, count(*) AS n, sum(secs) AS secs FROM"
                + " s GROUP BY GROUPING SETS ((), (k)) " + SOME + ") " + COLUMNS + KEY + "-1, 'camera_gap', CASE WHEN"
                + " g.gk = 1 THEN '' ELSE 'zone' END, CASE WHEN g.gk = 1 THEN '' ELSE g.k END, g.n, g.secs, NULL, NULL"
                + " FROM g";
    }
}
