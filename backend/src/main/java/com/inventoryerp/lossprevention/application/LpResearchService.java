package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.ActivityQuery;
import com.inventoryerp.lossprevention.application.LpCommands.ActivityReviewInput;
import com.inventoryerp.lossprevention.application.LpCommands.ReportQuery;
import com.inventoryerp.lossprevention.application.LpReportService.Period;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity recognition research (LP7, docs/phases/LP7-activity-research.md): the {@code vision.activity} observations
 * a recognizer sent in shadow mode, people's reviews of them, and how often a recognizer was right. No rule reads an
 * activity, so nothing here makes a finding or a case.
 */
@Service
public class LpResearchService {

    public static final String NOTE = "Activities come from research recognizers running in shadow mode: no rule reads"
            + " them and they never make a case. Reviews measure the recognizer, not the people in the picture.";

    static final List<String> VERDICTS = List.of("correct", "incorrect", "unsure");
    static final String UNREVIEWED = "unreviewed";
    static final int MAX_NOTE = 1000;

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final Clock clock = Clock.systemUTC();

    public LpResearchService(JdbcClient jdbc, LpScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** Activity events of a period within the caller's scope: {@code WHERE} over {@code LpEvent e}. */
    private String where(Period p, String branchId, List<Object> params) {
        scope.assertBranch(branchId);
        StringBuilder w = new StringBuilder(scope.where("e", params)).append(" AND e.type = '")
                .append(LpEventTypes.VISION_ACTIVITY)
                .append("' AND e.\"businessDate\" >= ? AND e.\"businessDate\" <= ?");
        params.add(java.sql.Date.valueOf(p.from()));
        params.add(java.sql.Date.valueOf(p.to()));
        if (LpScope.has(branchId)) {
            w.append(" AND e.\"branchId\" = ?");
            params.add(branchId);
        }
        return w.toString();
    }

    /** The latest review of each event. */
    private static final String LATEST = " LEFT JOIN LATERAL (SELECT x.id, x.verdict, x.label, x.note,"
            + " x.\"reviewedBy\", x.\"createdAt\", (SELECT count(*) FROM \"LpActivityReview\" y WHERE y.\"eventId\" ="
            + " e.id) AS reviews FROM \"LpActivityReview\" x WHERE x.\"eventId\" = e.id ORDER BY x.\"createdAt\" DESC,"
            + " x.id DESC LIMIT 1) r ON true";

    /** Activities, newest first, with their latest review. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(ActivityQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        Period p = LpReportService.period(new ReportQuery(q.from(), q.to(), q.branchId()));
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(where(p, q.branchId(), params));
        if (LpScope.has(q.zoneId())) {
            w.append(" AND e.\"deviceId\" = ?");
            params.add(q.zoneId());
        }
        if (LpScope.has(q.activity())) {
            w.append(" AND e.payload->>'activity' = ?");
            params.add(q.activity());
        }
        if (LpScope.has(q.recognizer())) {
            w.append(" AND e.payload->>'recognizer' = ?");
            params.add(q.recognizer());
        }
        if (LpScope.has(q.verdict())) {
            if (UNREVIEWED.equals(q.verdict())) {
                w.append(" AND r.id IS NULL");
            } else if (VERDICTS.contains(q.verdict())) {
                w.append(" AND r.verdict = ?");
                params.add(q.verdict());
            } else {
                throw ApiException.badRequest("verdict must be one of: unreviewed, correct, incorrect, unsure");
            }
        }
        String from = " FROM \"LpEvent\" e" + LATEST + " LEFT JOIN \"LpZone\" z ON z.id = e.\"deviceId\" AND"
                + " z.\"organizationId\" = e.\"organizationId\"" + w;
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.seq, e.\"branchId\", e.\"deviceId\","
                + " e.\"occurredAt\", e.confidence, e.payload, z.name AS \"zoneName\", z.\"zoneType\", r.id AS"
                + " \"reviewId\", r.verdict, r.label, r.note, r.\"reviewedBy\", r.\"createdAt\" AS \"reviewedAt\","
                + " r.reviews" + from + " ORDER BY e.\"occurredAt\" DESC, e.seq DESC LIMIT ? OFFSET ?")
                .params(with(params, size, (long) (page - 1) * size)));
        Map<String, String> names = names(rows.stream().map(r -> (String) r.get("reviewedBy")).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> payload = LpJson.map(r.get("payload"));
            Map<String, Object> review = r.get("reviewId") == null ? null : Obj.of("id", r.get("reviewId"),
                    "verdict", r.get("verdict"), "label", r.get("label"), "note", r.get("note"), "reviewedBy",
                    r.get("reviewedBy"), "reviewerName", names.get((String) r.get("reviewedBy")), "createdAt",
                    r.get("reviewedAt"));
            items.add(Obj.of("eventId", r.get("id"), "seq", r.get("seq"), "branchId", r.get("branchId"), "zoneId",
                    r.get("deviceId"), "zoneName", r.get("zoneName"), "zoneType", r.get("zoneType"), "cameraId",
                    payload.get("cameraId"), "activity", payload.get("activity"), "recognizer",
                    payload.get("recognizer"), "score", payload.get("score"), "startedAt", payload.get("startedAt"),
                    "endedAt", payload.get("endedAt"), "occurredAt", r.get("occurredAt"), "clipId",
                    payload.get("clipId"), "reviews", r.get("reviews") == null ? 0L : r.get("reviews"), "review",
                    review));
        }
        return PageResult.of(items, page, size, total);
    }

    private static List<Object> with(List<Object> params, Object... more) {
        List<Object> out = new ArrayList<>(params);
        out.addAll(List.of(more));
        return out;
    }

    private Map<String, String> names(List<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> wanted = ids.stream().filter(LpScope::has).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, coalesce(nullif(trim(coalesce(\"firstName\", '')"
                + " || ' ' || coalesce(\"lastName\", '')), ''), email) AS name FROM \"User\" WHERE \"organizationId\" ="
                + " ? AND id = ANY(?::text[])").params(LpScope.org(), wanted.toArray(String[]::new)))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    /** Records what a reviewer says about one activity. A later review does not change an earlier one. */
    @Transactional
    public Map<String, Object> review(String eventId, ActivityReviewInput dto) {
        String verdict = dto == null ? null : dto.verdict();
        if (verdict == null || !VERDICTS.contains(verdict)) {
            throw ApiException.badRequest("verdict must be one of: correct, incorrect, unsure");
        }
        String label = LpScope.has(dto.label()) ? dto.label().trim() : null;
        if (label != null && !LpEventValidator.ACTIVITY.matcher(label).matches()) {
            throw ApiException.badRequest("label must be 1 to 64 characters: lowercase letters, digits or '_',"
                    + " starting with a letter");
        }
        if (label != null && !"incorrect".equals(verdict)) {
            throw ApiException.badRequest("A label is given only with the verdict incorrect");
        }
        String note = LpScope.has(dto.note()) ? dto.note().trim() : null;
        if (note != null && note.length() > MAX_NOTE) {
            throw ApiException.badRequest("note holds at most " + MAX_NOTE + " characters");
        }
        List<Object> params = new ArrayList<>();
        String w = scope.where("e", params) + " AND e.type = '" + LpEventTypes.VISION_ACTIVITY + "' AND e.id = ?";
        params.add(eventId);
        Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT e.id, e.\"branchId\", e.payload->>'activity' AS"
                + " activity, e.payload->>'recognizer' AS recognizer FROM \"LpEvent\" e" + w).params(params));
        if (e == null) {
            throw ApiException.notFound("Activity not found");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        String reviewer = TenantContext.userId();
        jdbc.sql("INSERT INTO \"LpActivityReview\" (id, \"organizationId\", \"branchId\", \"eventId\", activity,"
                + " recognizer, verdict, label, note, \"reviewedBy\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?)").params(java.util.Arrays.asList(id, LpScope.org(), e.get("branchId"), e.get("id"),
                        e.get("activity"), e.get("recognizer"), verdict, label, note, reviewer, SqlRows.ts(now)))
                .update();
        return Obj.of("id", id, "eventId", e.get("id"), "verdict", verdict, "label", label, "note", note,
                "reviewedBy", reviewer, "createdAt", now);
    }

    /**
     * Per recognizer and activity: events, how many were reviewed (latest review), each verdict, and precision =
     * correct ÷ (correct + incorrect). Also what reviewers said the incorrect ones really were.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(ActivityQuery q) {
        Period p = LpReportService.period(new ReportQuery(q.from(), q.to(), q.branchId()));
        List<Object> params = new ArrayList<>();
        String w = where(p, q.branchId(), params);
        String base = "WITH a AS (SELECT e.id, coalesce(e.payload->>'recognizer', '') AS recognizer,"
                + " coalesce(e.payload->>'activity', '') AS activity FROM \"LpEvent\" e" + w + "), r AS (SELECT"
                + " DISTINCT ON (x.\"eventId\") x.\"eventId\", x.verdict, x.label FROM \"LpActivityReview\" x JOIN a ON"
                + " a.id ="
                + " x.\"eventId\" ORDER BY x.\"eventId\", x.\"createdAt\" DESC, x.id DESC) ";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(base + "SELECT a.recognizer, a.activity, count(*) AS"
                + " events, count(r.verdict) AS reviewed, count(*) FILTER (WHERE r.verdict = 'correct') AS correct,"
                + " count(*) FILTER (WHERE r.verdict = 'incorrect') AS incorrect, count(*) FILTER (WHERE r.verdict ="
                + " 'unsure') AS unsure FROM a LEFT JOIN r ON r.\"eventId\" = a.id GROUP BY a.recognizer, a.activity"
                + " ORDER BY a.recognizer, a.activity").params(params))) {
            long correct = ((Number) r.get("correct")).longValue();
            long incorrect = ((Number) r.get("incorrect")).longValue();
            rows.add(Obj.of("recognizer", r.get("recognizer"), "activity", r.get("activity"), "events",
                    r.get("events"), "reviewed", r.get("reviewed"), "correct", correct, "incorrect", incorrect,
                    "unsure", r.get("unsure"), "precision", LpInsightsReportService.ratio(correct, correct + incorrect,
                            4)));
        }
        List<Map<String, Object>> relabels = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(base + "SELECT a.recognizer, a.activity, r.label,"
                + " count(*) AS count FROM a JOIN r ON r.\"eventId\" = a.id WHERE r.verdict = 'incorrect' AND"
                + " r.label IS NOT NULL GROUP BY a.recognizer, a.activity, r.label ORDER BY count(*) DESC,"
                + " a.recognizer, a.activity, r.label").params(params))) {
            relabels.add(Obj.of("recognizer", r.get("recognizer"), "activity", r.get("activity"), "label",
                    r.get("label"), "count", r.get("count")));
        }
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "relabels", relabels, "note", NOTE);
    }
}
