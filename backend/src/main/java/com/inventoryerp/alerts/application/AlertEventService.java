package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.application.AlertCommands.EventQuery;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Csv;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The exception report: what was raised, by whom it was seen, as a list, a summary per type and a CSV. */
@Service
public class AlertEventService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    private static final int EXPORT_LIMIT = 50_000;
    private static final List<String> CSV_COLUMNS = List.of("raisedAt", "alertType", "severity", "status", "title",
            "body", "link", "periodKey", "recipients", "acknowledgedAt");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AlertEventService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    private static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static String where(EventQuery q, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE \"organizationId\" = ?");
        params.add(org());
        if (has(q.alertType())) {
            w.append(" AND \"alertType\" = ?");
            params.add(q.alertType());
        }
        if (has(q.severity())) {
            w.append(" AND severity = ?");
            params.add(q.severity());
        }
        if (has(q.status())) {
            w.append(" AND status = ?");
            params.add(q.status());
        }
        if (has(q.from())) {
            w.append(" AND \"raisedAt\" >= ?");
            params.add(SqlRows.ts(day(q.from(), "from").atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (has(q.to())) {
            w.append(" AND \"raisedAt\" < ?");
            params.add(SqlRows.ts(day(q.to(), "to").plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
        }
        return w.toString();
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(EventQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        long total = jdbc.sql("SELECT count(*) FROM \"AlertEvent\"" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql("SELECT * FROM \"AlertEvent\"" + where
                + " ORDER BY \"raisedAt\" DESC, id LIMIT ? OFFSET ?").params(pageParams)), page, size, total);
    }

    /** Per type in the period: how many, how many critical, how many nobody acknowledged, and the latest. */
    @Transactional(readOnly = true)
    public Map<String, Object> exceptionReport(EventQuery q) {
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT \"alertType\", count(*) AS total, count(*)"
                + " FILTER (WHERE severity = 'critical') AS critical, count(*) FILTER (WHERE status = 'open') AS open,"
                + " max(\"raisedAt\") AS \"lastRaisedAt\" FROM \"AlertEvent\"" + where
                + " GROUP BY \"alertType\" ORDER BY critical DESC, total DESC").params(params));
        return Obj.of("from", q.from(), "to", q.to(), "rows", rows);
    }

    /** The filtered list as CSV, newest first, capped at {@value #EXPORT_LIMIT} rows. */
    @Transactional(readOnly = true)
    public byte[] exportCsv(EventQuery q) {
        List<Object> params = new ArrayList<>();
        String where = where(q, params);
        return Csv.of(CSV_COLUMNS, SqlRows.list(jdbc.sql("SELECT * FROM \"AlertEvent\"" + where
                + " ORDER BY \"raisedAt\" DESC, id LIMIT " + EXPORT_LIMIT).params(params)));
    }

    /** Somebody has seen it. It does not silence the subject: the next period alerts again if it is still true. */
    @Transactional
    public Map<String, Object> acknowledge(String id) {
        int n = jdbc.sql("UPDATE \"AlertEvent\" SET status = 'acknowledged', \"acknowledgedBy\" = ?,"
                + " \"acknowledgedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'open'")
                .params(TenantContext.userId(), id, org()).update();
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"AlertEvent\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Alert not found");
        }
        if (n > 0) {
            audit.recordInTx(AuditInput.of("AlertEvent", id, "update", Obj.of("status", "open"), Obj.of("status",
                    "acknowledged")));
        }
        return row;
    }
}
