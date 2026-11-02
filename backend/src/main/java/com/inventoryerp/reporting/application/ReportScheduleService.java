package com.inventoryerp.reporting.application;

import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.Attachment;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.reporting.application.ReportEngine.ReportResult;
import com.inventoryerp.reporting.application.ReportExporter.Export;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scheduled report delivery: a report with saved filters (a period preset keeps it current) rendered daily, weekly or
 * monthly at an hour of the organization's day and e-mailed as CSV / XLSX / PDF to the recipients, with an in-app
 * notification to the owner. It runs as the owner, with the owner's permissions and branch scope at delivery time, so
 * a schedule never shows more than its owner may see.
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKLY", "MONTHLY");

    /** Schedule input; {@code dayOfWeek} 1 = Monday, {@code dayOfMonth} 1..28. */
    public record ScheduleInput(String reportId, String name, Map<String, Object> params, String format,
            String frequency, Integer dayOfWeek, Integer dayOfMonth, Integer hourOfDay, List<String> recipients) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ReportEngine engine;
    private final ReportExporter exporter;
    private final NotificationsService notifications;
    private final PermissionService permissions;

    public ReportScheduleService(JdbcClient jdbc, JsonMapper json, ReportEngine engine, ReportExporter exporter,
            NotificationsService notifications, PermissionService permissions) {
        this.jdbc = jdbc;
        this.json = json;
        this.engine = engine;
        this.exporter = exporter;
        this.notifications = notifications;
        this.permissions = permissions;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        boolean all = TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ReportSchedule\" WHERE \"organizationId\" = ?"
                + (all ? "" : " AND \"ownerId\" = ?") + " ORDER BY name").params(all ? List.of(org())
                : List.of(org(), TenantContext.userId())));
    }

    @Transactional
    public Map<String, Object> create(ScheduleInput in) {
        ReportDefinition def = engine.definition(in.reportId());
        if (!def.engine()) {
            throw ApiException.badRequest("Only library reports run by the engine can be scheduled");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (!ReportExporter.FORMATS.contains(in.format())) {
            throw ApiException.badRequest("format must be one of: " + String.join(", ", ReportExporter.FORMATS));
        }
        if (!FREQUENCIES.contains(in.frequency())) {
            throw ApiException.badRequest("frequency must be one of: DAILY, MONTHLY, WEEKLY");
        }
        if ("WEEKLY".equals(in.frequency()) && (in.dayOfWeek() == null || in.dayOfWeek() < 1 || in.dayOfWeek() > 7)) {
            throw ApiException.badRequest("dayOfWeek must be between 1 (Monday) and 7 (Sunday)");
        }
        if ("MONTHLY".equals(in.frequency()) && (in.dayOfMonth() == null || in.dayOfMonth() < 1
                || in.dayOfMonth() > 28)) {
            throw ApiException.badRequest("dayOfMonth must be between 1 and 28");
        }
        int hour = in.hourOfDay() == null ? 7 : in.hourOfDay();
        if (hour < 0 || hour > 23) {
            throw ApiException.badRequest("hourOfDay must be between 0 and 23");
        }
        List<String> recipients = in.recipients() == null ? List.of() : in.recipients().stream().map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (recipients.size() > 20) {
            throw ApiException.badRequest("recipients may contain at most 20 addresses");
        }
        for (String r : recipients) {
            if (!EMAIL.matcher(r).matches()) {
                throw ApiException.badRequest("Invalid recipient e-mail: " + r);
            }
        }
        ReportRequest request = engine.fromMap(in.params());
        ReportEngine.validateFilters(def, request);
        ZoneId zone = OrgDates.orgTimezone(jdbc, org());
        Instant next = nextRun(in.frequency(), in.dayOfWeek(), in.dayOfMonth(), hour, zone, Instant.now());
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO "ReportSchedule" (id, "organizationId", "reportId", name, params, format, frequency,
                  "dayOfWeek", "dayOfMonth", "hourOfDay", recipients, "ownerId", "nextRunAt")
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::text[], ?, ?)""")
                .params(id, org(), in.reportId(), in.name().trim(), json.writeValueAsString(request), in.format(),
                        in.frequency(), "WEEKLY".equals(in.frequency()) ? in.dayOfWeek() : null,
                        "MONTHLY".equals(in.frequency()) ? in.dayOfMonth() : null, hour,
                        recipients.toArray(String[]::new), TenantContext.userId(), SqlRows.ts(next))
                .update();
        return get(id);
    }

    @Transactional
    public Map<String, Object> setActive(String id, boolean active) {
        Map<String, Object> s = mine(id);
        jdbc.sql("UPDATE \"ReportSchedule\" SET \"isActive\" = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(active, s.get("id")).update();
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        mine(id);
        jdbc.sql("DELETE FROM \"ReportSchedule\" WHERE id = ?").param(id).update();
    }

    /** Deliver now, as the owner, without moving the next run (no surrounding transaction: a failure is recorded). */
    public Map<String, Object> runNow(String id) {
        Map<String, Object> s = mine(id);
        return deliver(s, false);
    }

    /** Deliver every due schedule of every organization (the job; one failure never stops the others). */
    public int runDue(Instant now) {
        List<Map<String, Object>> due = TenantContext.unscoped(() -> SqlRows.list(jdbc.sql("SELECT * FROM"
                + " \"ReportSchedule\" WHERE \"isActive\" AND \"nextRunAt\" <= ? ORDER BY \"nextRunAt\" LIMIT 200")
                .param(SqlRows.ts(now))));
        int sent = 0;
        for (Map<String, Object> s : due) {
            String org = (String) s.get("organizationId");
            String owner = (String) s.get("ownerId");
            try {
                Boolean ok = TenantContext.run(TenantContext.TenantStore.user(org, owner), () -> {
                    TenantContext.setPermissions(permissions.permissionsOf(org, owner));
                    return (Boolean) deliver(s, true).get("ok");
                });
                sent += Boolean.TRUE.equals(ok) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("report schedule {} failed: {}", s.get("id"), e.toString());
            }
        }
        return sent;
    }

    private Map<String, Object> deliver(Map<String, Object> s, boolean advance) {
        String id = (String) s.get("id");
        String status;
        String error = null;
        int delivered = 0;
        try {
            ReportRequest request = engine.fromMap(params(s.get("params")));
            ReportResult result = engine.run((String) s.get("reportId"), request, "SCHEDULE");
            Export file = exporter.render(result, (String) s.get("format"));
            String subject = result.header().title() + " — " + result.header().period();
            String body = "Scheduled report \"" + s.get("name") + "\" for " + result.header().company() + ".\n"
                    + result.header().period() + ", amounts in " + result.header().currency() + ".\nRun "
                    + result.header().runId();
            for (String to : recipients(s)) {
                notifications.sendExternalEmail(to, subject, body, new Attachment(file.filename(), file.contentType(),
                        file.content()));
                delivered++;
            }
            notifications.send(new SendInput(org(), (String) s.get("ownerId"), "in_app", "reports", subject,
                    "Scheduled report \"" + s.get("name") + "\" delivered to " + delivered + " recipient(s).",
                    Obj.of("kind", "report_schedule", "scheduleId", id, "runId", result.header().runId(), "href",
                            "/reports/library/" + s.get("reportId"), "link", "/reports/library/" + s.get("reportId"))));
            status = "OK";
        } catch (RuntimeException e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("report schedule {} not delivered: {}", id, error);
        }
        Instant next = advance ? nextRun((String) s.get("frequency"), (Integer) s.get("dayOfWeek"),
                (Integer) s.get("dayOfMonth"), (Integer) s.get("hourOfDay"), OrgDates.orgTimezone(jdbc, org()),
                Instant.now()) : null;
        jdbc.sql("UPDATE \"ReportSchedule\" SET \"lastRunAt\" = now(), \"lastStatus\" = ?, \"lastError\" = ?,"
                + " \"nextRunAt\" = coalesce(?, \"nextRunAt\"), \"updatedAt\" = now() WHERE id = ?")
                .params(status, error, SqlRows.ts(next), id).update();
        return Obj.of("ok", "OK".equals(status), "status", status, "error", error, "delivered", delivered);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Object p) {
        if (p instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return p == null ? Map.of() : json.readValue(String.valueOf(p), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> recipients(Map<String, Object> s) {
        Object r = s.get("recipients");
        if (r instanceof List<?> l) {
            return (List<String>) l;
        }
        if (r instanceof String[] a) {
            return List.of(a);
        }
        if (r instanceof java.sql.Array a) {
            try {
                return List.of((String[]) a.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return new ArrayList<>();
    }

    /** First run strictly after {@code after} at {@code hour} local time on a matching day. */
    static Instant nextRun(String frequency, Integer dayOfWeek, Integer dayOfMonth, int hour, ZoneId zone,
            Instant after) {
        LocalDate d = after.atZone(zone).toLocalDate();
        for (int i = 0; i < 400; i++, d = d.plusDays(1)) {
            boolean match = switch (frequency) {
                case "DAILY" -> true;
                case "WEEKLY" -> d.getDayOfWeek().getValue() == dayOfWeek;
                case "MONTHLY" -> d.getDayOfMonth() == dayOfMonth;
                default -> throw ApiException.badRequest("Unknown frequency " + frequency);
            };
            if (match) {
                ZonedDateTime at = d.atTime(hour, 0).atZone(zone);
                if (at.toInstant().isAfter(after)) {
                    return at.toInstant();
                }
            }
        }
        throw new IllegalStateException("No next run for " + frequency);
    }

    private Map<String, Object> get(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ReportSchedule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    private Map<String, Object> mine(String id) {
        Map<String, Object> s = get(id);
        if (s == null || (!String.valueOf(TenantContext.userId()).equals(s.get("ownerId"))
                && !TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE))) {
            throw ApiException.notFound("Report schedule not found");
        }
        return s;
    }
}
