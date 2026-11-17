package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.CaseQuery;
import com.inventoryerp.lossprevention.application.LpCommands.CloseInput;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cases (ADR-S077 §6): one per finding, reviewed by a person and closed with a factual outcome. A case never records
 * misconduct, the person it is about never sees it, and its timeline is append-only.
 */
@Service
public class LpCaseService {

    public static final List<String> OUTCOMES = List.of("explained", "confirmed_variance", "false_alarm",
            "inconclusive");
    public static final List<String> CAUSE_CODES = List.of("spillage", "breakage", "complimentary", "staff_drink",
            "unrecorded_sale", "bottle_transfer", "measurement_error", "unexplained", "remake", "waste", "late_sync",
            "training");

    private static final String USER_NAME = "nullif(trim(coalesce(%1$s.\"firstName\", '') || ' ' ||"
            + " coalesce(%1$s.\"lastName\", '')), '')";
    private static final String SELECT = "SELECT c.*, coalesce(" + USER_NAME.formatted("su") + ", su.email) AS"
            + " \"subjectName\", coalesce(" + USER_NAME.formatted("au") + ", au.email) AS \"assigneeName\" FROM"
            + " \"LpCase\" c LEFT JOIN \"User\" su ON su.id = c.\"subjectUserId\" LEFT JOIN \"User\" au ON au.id ="
            + " c.\"assigneeId\"";

    /** A finding as the run stored it. */
    public record StoredFinding(String discrepancyId, String fingerprint, String engine, String branchId,
            LocalDate businessDate, String severity, Finding finding) {}

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final AuditService audit;
    private final EventBus events;

    public LpCaseService(JdbcClient jdbc, LpScope scope, AuditService audit, EventBus events) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.audit = audit;
        this.events = events;
    }

    private void timeline(String caseId, String kind, String actorId, String note, Map<String, Object> data) {
        jdbc.sql("INSERT INTO \"LpCaseEvent\" (id, \"organizationId\", \"caseId\", kind, \"actorId\", note, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)")
                .params(java.util.Arrays.asList(UUID.randomUUID().toString(), LpScope.org(), caseId, kind, actorId,
                        note, scope.json(data == null ? Map.of() : data)))
                .update();
    }

    // ------------------------------------------------------------------ written by reconciliation

    /**
     * Brings cases into line with a run. A new finding opens a case. A finding that is still there updates its open
     * case. A finding that is gone is noted on its case, and closes it as a false alarm when nobody had started on it.
     * A case a person closed is never changed here.
     */
    @Transactional
    public void sync(String runId, List<StoredFinding> findings, Collection<String> withdrawn) {
        String org = LpScope.org();
        for (StoredFinding f : findings) {
            Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT * FROM \"LpCase\" WHERE \"organizationId\" = ?"
                    + " AND fingerprint = ?").params(org, f.fingerprint()));
            if (existing == null) {
                open(runId, f);
                continue;
            }
            String id = (String) existing.get("id");
            if ("closed".equals(existing.get("state"))) {
                if (existing.get("closedBy") != null) {
                    continue;
                }
                jdbc.sql("UPDATE \"LpCase\" SET state = 'open', outcome = NULL, \"causeCode\" = NULL, \"closedAt\" ="
                        + " NULL, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
                timeline(id, "reopened", null, "The variance was found again by a later reconciliation run.",
                        Obj.of("runId", runId));
            }
            Map<String, Object> before = existing.get("currentDiscrepancyId") == null ? null : SqlRows.one(jdbc.sql(
                    "SELECT expected, observed, delta FROM \"LpDiscrepancy\" WHERE id = ?")
                    .param(existing.get("currentDiscrepancyId")));
            Finding now = f.finding();
            if (before != null && !(same(before.get("expected"), now.expected())
                    && same(before.get("observed"), now.observed()) && same(before.get("delta"), now.delta()))) {
                timeline(id, "finding_changed", null, "A later reconciliation run changed the figures.",
                        Obj.of("runId", runId, "before", before, "after", Obj.of("expected", now.expected(),
                                "observed", now.observed(), "delta", now.delta())));
            }
            jdbc.sql("UPDATE \"LpCase\" SET \"currentDiscrepancyId\" = ?, \"exposureAmount\" = ?, \"exposureMethod\" ="
                    + " ?, severity = ?, title = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                    .params(java.util.Arrays.asList(f.discrepancyId(), now.exposureAmount(), now.exposureMethod(),
                            f.severity(), now.title(), id))
                    .update();
        }
        for (String fingerprint : withdrawn) {
            Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT id, state FROM \"LpCase\" WHERE \"organizationId\" = ?"
                    + " AND fingerprint = ? AND state <> 'closed'").params(org, fingerprint));
            if (c == null) {
                continue;
            }
            String id = (String) c.get("id");
            timeline(id, "finding_withdrawn", null, "A later reconciliation run no longer finds this variance.",
                    Obj.of("runId", runId));
            if ("open".equals(c.get("state"))) {
                jdbc.sql("UPDATE \"LpCase\" SET state = 'closed', outcome = 'false_alarm', \"closedAt\" ="
                        + " CURRENT_TIMESTAMP, \"closedBy\" = NULL, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                        .param(id).update();
                timeline(id, "closed", null, "Closed automatically: nobody had started the review and the variance"
                        + " is no longer found.", Obj.of("outcome", "false_alarm", "runId", runId));
            }
        }
    }

    private static boolean same(Object stored, BigDecimal now) {
        BigDecimal a = LpNumbers.decimal(stored);
        if (a == null || now == null) {
            return a == null && now == null;
        }
        return a.compareTo(now) == 0;
    }

    private void open(String runId, StoredFinding f) {
        String org = LpScope.org();
        Finding finding = f.finding();
        // One number sequence per organization; the lock holds until this transaction ends.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("lp-case:" + org).query(Object.class).optional();
        int seq = jdbc.sql("SELECT coalesce(max(\"caseSeq\"), 0) + 1 FROM \"LpCase\" WHERE \"organizationId\" = ?")
                .param(org).query(Integer.class).single();
        String number = "LP-" + String.format("%06d", seq);
        String subjectUser = null;
        if ("actor".equals(finding.subjectType()) && finding.subjectId() != null) {
            subjectUser = jdbc.sql("SELECT id FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(finding.subjectId(), org).query(String.class).optional().orElse(null);
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpCase\" (id, \"organizationId\", \"caseSeq\", \"caseNumber\", fingerprint, engine,"
                + " \"ruleCode\", \"branchId\", \"businessDate\", title, severity, \"subjectType\", \"subjectId\","
                + " \"subjectUserId\", \"currentDiscrepancyId\", \"exposureAmount\", \"exposureMethod\") VALUES (?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(java.util.Arrays.asList(id, org, seq, number, f.fingerprint(), f.engine(), finding.ruleCode(),
                        f.branchId(), java.sql.Date.valueOf(f.businessDate()), finding.title(), f.severity(),
                        finding.subjectType(), finding.subjectId(), subjectUser, f.discrepancyId(),
                        finding.exposureAmount(), finding.exposureMethod()))
                .update();
        timeline(id, "opened", null, null, Obj.of("runId", runId, "discrepancyId", f.discrepancyId()));
        events.publishOnCommit(DomainEvents.LP_CASE_OPENED, Obj.of("organizationId", org, "caseId", id,
                "caseNumber", number, "ruleCode", finding.ruleCode(), "severity", f.severity(), "branchId",
                f.branchId().isEmpty() ? null : f.branchId(), "title", finding.title()));
    }

    // ------------------------------------------------------------------ reads

    private static LocalDate day(String v, String field) {
        LocalDate d = v.length() == 10 ? LpEventValidator.date(v) : null;
        if (d == null) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
        return d;
    }

    /** Latest first. Cases about the caller are left out, as are branches outside their data scope. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(CaseQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        List<Object> params = new ArrayList<>();
        scope.assertBranch(q.branchId());
        StringBuilder w = new StringBuilder(scope.caseWhere("c", params));
        String[][] equals = {{"state", q.state()}, {"outcome", q.outcome()}, {"engine", q.engine()},
            {"ruleCode", q.ruleCode()}, {"branchId", q.branchId()}, {"subjectId", q.subjectId()},
            {"assigneeId", q.assigneeId()}, {"severity", q.severity()}};
        for (String[] f : equals) {
            if (LpScope.has(f[1])) {
                w.append(" AND c.\"").append(f[0]).append("\" = ?");
                params.add(f[1]);
            }
        }
        if (LpScope.has(q.from())) {
            w.append(" AND c.\"businessDate\" >= ?");
            params.add(java.sql.Date.valueOf(day(q.from(), "from")));
        }
        if (LpScope.has(q.to())) {
            w.append(" AND c.\"businessDate\" <= ?");
            params.add(java.sql.Date.valueOf(day(q.to(), "to")));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"LpCase\" c" + w).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql(SELECT + w + " ORDER BY c.\"openedAt\" DESC, c.\"caseSeq\" DESC"
                + " LIMIT ? OFFSET ?").params(pageParams)), page, size, total);
    }

    /** The case when the caller may see it; otherwise 404, so its existence is not disclosed. */
    private Map<String, Object> load(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.caseWhere("c", params) + " AND c.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql(SELECT + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Case not found");
        }
        return row;
    }

    /** The case with its findings, the ledger events behind the current one, and its timeline. Records the view. */
    @Transactional
    public Map<String, Object> get(String id) {
        Map<String, Object> c = load(id);
        timeline(id, "viewed", TenantContext.userId(), null, null);
        List<Map<String, Object>> findings = SqlRows.list(jdbc.sql("SELECT * FROM \"LpDiscrepancy\" WHERE"
                + " \"organizationId\" = ? AND fingerprint = ? ORDER BY \"createdAt\" DESC, id LIMIT 50")
                .params(LpScope.org(), c.get("fingerprint")));
        List<Map<String, Object>> ledger = List.of();
        if (c.get("currentDiscrepancyId") != null) {
            ledger = SqlRows.list(jdbc.sql("SELECT e.* FROM \"LpEvent\" e WHERE e.\"organizationId\" = ? AND e.id ="
                    + " ANY(SELECT unnest(d.\"evidenceEventIds\") FROM \"LpDiscrepancy\" d WHERE d.id = ?)"
                    + " ORDER BY e.\"occurredAt\", e.seq LIMIT 200")
                    .params(LpScope.org(), c.get("currentDiscrepancyId")));
        }
        List<Map<String, Object>> timeline = SqlRows.list(jdbc.sql("SELECT t.*, coalesce(" + USER_NAME.formatted("u")
                + ", u.email) AS \"actorName\" FROM \"LpCaseEvent\" t LEFT JOIN \"User\" u ON u.id = t.\"actorId\""
                + " WHERE t.\"caseId\" = ? ORDER BY t.\"createdAt\", t.id").param(id));
        Map<String, Object> out = new java.util.LinkedHashMap<>(c);
        out.put("findings", findings);
        out.put("events", ledger);
        out.put("timeline", timeline);
        return out;
    }

    // ------------------------------------------------------------------ review

    private Map<String, Object> open(String id) {
        Map<String, Object> c = load(id);
        if ("closed".equals(c.get("state"))) {
            throw ApiException.badRequest("A closed case cannot be changed; reopen it first");
        }
        return c;
    }

    @Transactional
    public Map<String, Object> assign(String id, String assigneeId) {
        Map<String, Object> c = open(id);
        String assignee = LpScope.has(assigneeId) ? assigneeId : null;
        if (assignee != null) {
            if (jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS"
                    + " NULL").params(assignee, LpScope.org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Assignee not found");
            }
            if (assignee.equals(c.get("subjectUserId"))) {
                throw ApiException.badRequest("The subject of a case cannot be assigned to it");
            }
        }
        jdbc.sql("UPDATE \"LpCase\" SET \"assigneeId\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(assignee, id)).update();
        timeline(id, "assigned", TenantContext.userId(), null, Obj.of("assigneeId", assignee, "previous",
                c.get("assigneeId")));
        audit.recordInTx(AuditInput.of("LpCase", id, "assign", Obj.of("assigneeId", c.get("assigneeId")),
                Obj.of("assigneeId", assignee)));
        return load(id);
    }

    @Transactional
    public Map<String, Object> note(String id, String note) {
        load(id);
        timeline(id, "note", TenantContext.userId(), note, null);
        jdbc.sql("UPDATE \"LpCase\" SET \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        return load(id);
    }

    @Transactional
    public Map<String, Object> startReview(String id) {
        Map<String, Object> c = open(id);
        if ("reviewing".equals(c.get("state"))) {
            throw ApiException.badRequest("Only an open or escalated case can go to review");
        }
        jdbc.sql("UPDATE \"LpCase\" SET state = 'reviewing', \"reviewStartedAt\" = coalesce(\"reviewStartedAt\","
                + " CURRENT_TIMESTAMP), \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        timeline(id, "review_started", TenantContext.userId(), null, Obj.of("from", c.get("state")));
        return load(id);
    }

    @Transactional
    public Map<String, Object> escalate(String id, String reason) {
        Map<String, Object> c = open(id);
        if ("escalated".equals(c.get("state"))) {
            throw ApiException.badRequest("Only an open or reviewing case can be escalated");
        }
        jdbc.sql("UPDATE \"LpCase\" SET state = 'escalated', \"reviewStartedAt\" = coalesce(\"reviewStartedAt\","
                + " CURRENT_TIMESTAMP), \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        timeline(id, "escalated", TenantContext.userId(), reason, Obj.of("from", c.get("state")));
        return load(id);
    }

    /** Closes with a factual outcome. {@code explained} needs the cause; no outcome states misconduct. */
    @Transactional
    public Map<String, Object> close(String id, CloseInput dto) {
        Map<String, Object> c = open(id);
        if (!OUTCOMES.contains(dto.outcome())) {
            throw ApiException.badRequest("outcome must be one of: " + String.join(", ", OUTCOMES));
        }
        String cause = LpScope.has(dto.causeCode()) ? dto.causeCode() : null;
        if ("explained".equals(dto.outcome()) && cause == null) {
            throw ApiException.badRequest("causeCode is required for an explained case");
        }
        if (cause != null && !CAUSE_CODES.contains(cause)) {
            throw ApiException.badRequest("causeCode must be one of: " + String.join(", ", CAUSE_CODES));
        }
        String user = TenantContext.userId();
        jdbc.sql("UPDATE \"LpCase\" SET state = 'closed', outcome = ?, \"causeCode\" = ?, \"closedAt\" ="
                + " CURRENT_TIMESTAMP, \"closedBy\" = ?, \"reviewStartedAt\" = coalesce(\"reviewStartedAt\","
                + " CURRENT_TIMESTAMP), \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(dto.outcome(), cause, Objects.requireNonNullElse(user, "api-client"),
                        id))
                .update();
        timeline(id, "closed", user, dto.note(), Obj.of("outcome", dto.outcome(), "causeCode", cause, "from",
                c.get("state")));
        audit.recordInTx(AuditInput.of("LpCase", id, "update", Obj.of("state", c.get("state")), Obj.of("state",
                "closed", "outcome", dto.outcome(), "causeCode", cause)));
        return load(id);
    }

    @Transactional
    public Map<String, Object> reopen(String id, String reason) {
        Map<String, Object> c = load(id);
        if (!"closed".equals(c.get("state"))) {
            throw ApiException.badRequest("Only a closed case can be reopened");
        }
        jdbc.sql("UPDATE \"LpCase\" SET state = 'reviewing', outcome = NULL, \"causeCode\" = NULL, \"closedAt\" = NULL,"
                + " \"closedBy\" = NULL, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        timeline(id, "reopened", TenantContext.userId(), reason, Obj.of("outcome", c.get("outcome"), "causeCode",
                c.get("causeCode")));
        audit.recordInTx(AuditInput.of("LpCase", id, "reopen", Obj.of("state", "closed", "outcome",
                c.get("outcome")), Obj.of("state", "reviewing")));
        return load(id);
    }
}
