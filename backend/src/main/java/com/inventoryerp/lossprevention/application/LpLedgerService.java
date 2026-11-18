package com.inventoryerp.lossprevention.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.EventInput;
import com.inventoryerp.lossprevention.application.LpCommands.EventQuery;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event ledger (ADR-S077 §3–§4): stores events once however often and in whatever order they arrive, and lists
 * them. Rows are never updated.
 */
@Service
public class LpLedgerService {

    public static final String ACCEPTED = "accepted";
    public static final String DUPLICATE = "duplicate";
    public static final String REJECTED = "rejected";

    private static final String INSERT = "INSERT INTO \"LpEvent\" (id, \"organizationId\", \"branchId\", \"eventId\","
            + " source, \"sourceEventId\", type, \"schemaVersion\", \"deviceId\", \"actorId\", \"transactionId\","
            + " \"orderId\", \"ticketId\", \"lineId\", \"sessionId\", \"businessDate\", \"occurredAt\","
            + " \"evidenceLevel\", \"evidenceSource\", \"observationStatus\", confidence, payload)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
            + " ON CONFLICT DO NOTHING RETURNING seq";

    private final JdbcClient jdbc;
    private final BranchScopeService branches;
    private final LpReconService recon;
    private final Clock clock = Clock.systemUTC();

    public LpLedgerService(JdbcClient jdbc, BranchScopeService branches, LpReconService recon) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.recon = recon;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * Stores a batch for the caller's organization. Each event has its own outcome: {@code accepted}, {@code duplicate}
     * (the same source and sourceEventId is already stored; the first copy is kept) or {@code rejected} with a reason.
     * Sending a batch again is safe.
     *
     * @param external an outside sender must name the branch when the organization has branches; this ERP's own
     *     projection may leave it empty when the source row has none
     */
    @Transactional
    public Map<String, Object> ingest(List<EventInput> events, boolean external) {
        String org = org();
        Instant now = clock.instant();
        Set<String> known = new HashSet<>(jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").param(org).query(String.class).list());
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        Set<LpReconService.WindowKey> touched = new HashSet<>();
        for (EventInput e : events) {
            String reason = LpEventValidator.reject(e, now);
            if (reason == null) {
                reason = branchReason(e, known, external);
            }
            Long seq = null;
            String status;
            if (reason != null) {
                status = REJECTED;
            } else {
                Optional<Long> stored = insert(org, e);
                if (stored.isPresent()) {
                    status = ACCEPTED;
                    seq = stored.get();
                    String engine = recon.engineOf(e.type());
                    if (engine != null) {
                        touched.add(new LpReconService.WindowKey(engine, e.branchId() == null ? "" : e.branchId(),
                                businessDate(e), e.type()));
                    }
                } else if (exists(org, e)) {
                    status = DUPLICATE;
                } else {
                    status = REJECTED;
                    reason = "eventId is already used by another event";
                }
            }
            switch (status) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                default -> rejected++;
            }
            results.add(Obj.of("eventId", e.eventId(), "source", e.source(), "sourceEventId", e.sourceEventId(),
                    "status", status, "seq", seq, "reason", reason));
        }
        // A stored event makes its window due again, whenever it arrives (ADR-S077 §5).
        recon.markDirty(touched);
        return Obj.of("accepted", accepted, "duplicates", duplicates, "rejected", rejected, "results", results);
    }

    private static String branchReason(EventInput e, Set<String> known, boolean external) {
        if (e.branchId() == null) {
            return external && !known.isEmpty() ? "branchId is required" : null;
        }
        return known.contains(e.branchId()) ? null : "Branch not found";
    }

    /** The sender's trading day, else the day of the event in the organization's zone. */
    private static LocalDate businessDate(EventInput e) {
        return e.businessDate() != null ? LpEventValidator.date(e.businessDate())
                : LpEventValidator.instant(e.occurredAt()).atZone(BusinessZone.get()).toLocalDate();
    }

    private Optional<Long> insert(String org, EventInput e) {
        Instant occurredAt = LpEventValidator.instant(e.occurredAt());
        LocalDate businessDate = businessDate(e);
        return jdbc.sql(INSERT)
                .params(java.util.Arrays.asList(UUID.randomUUID().toString(), org, e.branchId(), e.eventId(),
                        e.source(), e.sourceEventId(), e.type(), e.schemaVersion(), e.deviceId(), e.actorId(),
                        e.transactionId(), e.orderId(), e.ticketId(), e.lineId(), e.sessionId(),
                        java.sql.Date.valueOf(businessDate), SqlRows.ts(occurredAt), e.evidenceLevel(),
                        e.evidenceSource(), e.observationStatus(), e.confidence(), e.payload().toString()))
                .query(Long.class).optional();
    }

    private boolean exists(String org, EventInput e) {
        return jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE \"organizationId\" = ? AND source = ? AND"
                + " \"sourceEventId\" = ?").params(org, e.source(), e.sourceEventId()).query(Long.class).single() > 0;
    }

    private static Instant bound(String v, String field, boolean endOfDay) {
        Instant at = LpEventValidator.instant(v);
        if (at != null) {
            return at;
        }
        LocalDate day = v.length() == 10 ? LpEventValidator.date(v) : null;
        if (day == null) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD) or an ISO-8601 instant");
        }
        return (endOfDay ? day.plusDays(1) : day).atStartOfDay(BusinessZone.get()).toInstant();
    }

    private String where(EventQuery q, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE \"organizationId\" = ?");
        params.add(org());
        DataScope scope = branches.scope();
        if (has(q.branchId())) {
            branches.assertBranch(q.branchId());
        }
        if (scope.restricted()) {
            // A row without a branch is outside a restricted user's scope (ADR-S017).
            w.append(" AND \"branchId\" = ANY(?::text[])");
            params.add(scope.branchArray());
        }
        String[][] equals = {{"type", q.type()}, {"source", q.source()}, {"branchId", q.branchId()},
            {"deviceId", q.deviceId()}, {"actorId", q.actorId()}, {"transactionId", q.transactionId()},
            {"orderId", q.orderId()}, {"ticketId", q.ticketId()}, {"lineId", q.lineId()},
            {"sessionId", q.sessionId()}};
        for (String[] filter : equals) {
            if (has(filter[1])) {
                w.append(" AND \"").append(filter[0]).append("\" = ?");
                params.add(filter[1]);
            }
        }
        if (has(q.from())) {
            w.append(" AND \"occurredAt\" >= ?");
            params.add(SqlRows.ts(bound(q.from(), "from", false)));
        }
        if (has(q.to())) {
            w.append(" AND \"occurredAt\" < ?");
            params.add(SqlRows.ts(bound(q.to(), "to", true)));
        }
        return w.toString();
    }

    /** The ledger, latest first by when the event happened. A restricted user sees only their branches. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(EventQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        long total = jdbc.sql("SELECT count(*) FROM \"LpEvent\"" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql("SELECT * FROM \"LpEvent\"" + where
                + " ORDER BY \"occurredAt\" DESC, seq DESC LIMIT ? OFFSET ?").params(pageParams)), page, size, total);
    }

    /** One event; 404 when it is unknown, in another organization or outside the user's branches. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"LpEvent\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        DataScope scope = branches.scope();
        if (row == null || (scope.restricted() && !scope.allowsBranch((String) row.get("branchId")))) {
            throw ApiException.notFound("Event not found");
        }
        return row;
    }
}
