package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.GlAutomationCommands.TemplateInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.TemplateLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recurring journal entries (ADR-S073; Nest has no counterpart). A template holds a balanced entry, a calendar
 * frequency and the next run date. Each occurrence goes through {@link PostingService} with the deterministic key
 * {@code recurring:<templateId>:<date>} and is recorded once in {@code RecurringJournalRun} (unique per template and
 * date), so a job that runs twice — or a manual run of an occurrence the job already made — books it once.
 * {@code mode = draft} stages the entry as a draft whose maker is the template author, so a second user approves it
 * (maker-checker). A closed or locked period refuses the occurrence; the job records the failure and retries the
 * next day without moving the schedule.
 */
@Service
public class RecurringJournalService {

    private static final Logger log = LoggerFactory.getLogger(RecurringJournalService.class);

    /** Missed occurrences one job run catches up per template. */
    static final int CATCH_UP_LIMIT = 60;

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AuditService audit;
    private final JsonMapper mapper;
    private final TransactionTemplate isolated;

    public RecurringJournalService(JdbcClient jdbc, PostingService posting, AuditService audit, JsonMapper mapper,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.audit = audit;
        this.mapper = mapper;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ queries

    /** Templates of the organization, newest first, optionally one status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        String where = "";
        if (status != null && !status.isEmpty()) {
            where = " AND status = ?";
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"RecurringJournalTemplate\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL" + where + " ORDER BY \"createdAt\" DESC").params(params))
                .stream().map(RecurringJournalService::render).toList();
    }

    /** One template; 404 when unknown in this organization. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return render(require(id, false));
    }

    /** Occurrence history of a template, newest first, with the entry number and its current status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String id) {
        require(id, false);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, r.\"runDate\", r.status, r.trigger,"
                + " r.error, r.\"journalEntryId\", r.\"createdAt\", e.\"entryNumber\", e.status::text AS \"entryStatus\""
                + " FROM \"RecurringJournalRun\" r LEFT JOIN \"JournalEntry\" e ON e.id = r.\"journalEntryId\" AND"
                + " e.\"organizationId\" = r.\"organizationId\" WHERE r.\"organizationId\" = ? AND r.\"templateId\" = ?"
                + " ORDER BY r.\"runDate\" DESC, r.\"createdAt\" DESC LIMIT 200")
                .params(TenantContext.organizationId(), id));
        rows.forEach(r -> {
            r.put("runDate", String.valueOf(r.get("runDate")));
            if (r.get("journalEntryId") != null && r.get("entryStatus") == null) {
                r.put("entryStatus", "discarded");
            }
        });
        return rows;
    }

    // ------------------------------------------------------------------ commands

    /** Create an active template; its first occurrence is its start date. */
    @Transactional
    public Map<String, Object> create(TemplateInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (in.frequency() == null) {
            throw ApiException.badRequest("frequency must be one of daily, weekly, monthly, quarterly, yearly");
        }
        if (in.startDate() == null) {
            throw ApiException.badRequest("startDate must be a valid ISO 8601 date string");
        }
        if (in.lines() == null) {
            throw ApiException.badRequest("lines must contain at least 2 elements");
        }
        String org = TenantContext.organizationId();
        String journalCode = in.journalCode() == null || in.journalCode().isBlank() ? "GEN" : in.journalCode();
        LocalDate start = day(in.startDate());
        LocalDate end = in.endDate() == null ? null : day(in.endDate());
        assertWindow(start, end);
        BigDecimal total = validateLines(org, journalCode, in.lines());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"RecurringJournalTemplate\" (id, \"organizationId\", name, \"journalCode\", description,"
                        + " frequency, \"intervalCount\", \"startDate\", \"endDate\", \"nextRunDate\", mode, status,"
                        + " \"currencyId\", \"branchId\", \"costCenterId\", lines, \"totalAmount\", \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?::jsonb, ?, ?)")
                .params(id, org, in.name().trim(), journalCode, in.description(), in.frequency(),
                        in.intervalCount() == null ? 1 : in.intervalCount(), Date.valueOf(start),
                        end == null ? null : Date.valueOf(end), Date.valueOf(start),
                        in.mode() == null ? "post" : in.mode(), in.currencyId(), in.branchId(), in.costCenterId(),
                        mapper.writeValueAsString(in.lines()), total, TenantContext.userId())
                .update();
        Map<String, Object> out = render(require(id, false));
        audit.recordInTx(AuditInput.of("RecurringJournalTemplate", id, "create", null, out));
        return out;
    }

    /** Change a template. A new start date (or frequency) before any run moves the next run date with it. */
    @Transactional
    public Map<String, Object> update(String id, TemplateInput in) {
        String org = TenantContext.organizationId();
        Map<String, Object> t = require(id, true);
        if ("ended".equals(t.get("status"))) {
            throw ApiException.badRequest("An ended recurring entry cannot be changed");
        }
        String journalCode = in.journalCode() != null ? in.journalCode() : (String) t.get("journalCode");
        LocalDate start = in.startDate() != null ? day(in.startDate()) : (LocalDate) t.get("startDate");
        LocalDate end = in.endDate() != null ? day(in.endDate()) : (LocalDate) t.get("endDate");
        assertWindow(start, end);
        String frequency = in.frequency() != null ? in.frequency() : (String) t.get("frequency");
        int interval = in.intervalCount() != null ? in.intervalCount() : ((Number) t.get("intervalCount")).intValue();
        BigDecimal total = SqlRows.dec(t, "totalAmount");
        String lines = null;
        if (in.lines() != null) {
            total = validateLines(org, journalCode, in.lines());
            lines = mapper.writeValueAsString(in.lines());
        } else if (in.journalCode() != null) {
            assertJournal(org, journalCode);
        }
        LocalDate next = (LocalDate) t.get("nextRunDate");
        int runCount = ((Number) t.get("runCount")).intValue();
        if (runCount == 0 && (in.startDate() != null || in.frequency() != null)) {
            next = start;
        } else if (next != null && t.get("lastRunDate") != null
                && (in.frequency() != null || in.intervalCount() != null || in.startDate() != null)) {
            next = GlSchedules.nextAfter(start, frequency, interval, (LocalDate) t.get("lastRunDate"));
        }
        jdbc.sql("UPDATE \"RecurringJournalTemplate\" SET name = coalesce(?, name), \"journalCode\" = ?,"
                        + " description = coalesce(?, description), frequency = ?, \"intervalCount\" = ?, \"startDate\" = ?,"
                        + " \"endDate\" = ?, \"nextRunDate\" = ?, mode = coalesce(?, mode),"
                        + " \"currencyId\" = coalesce(?, \"currencyId\"), \"branchId\" = coalesce(?, \"branchId\"),"
                        + " \"costCenterId\" = coalesce(?, \"costCenterId\"), lines = coalesce(?::jsonb, lines),"
                        + " \"totalAmount\" = ?, \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(in.name(), journalCode, in.description(), frequency, interval, Date.valueOf(start),
                        end == null ? null : Date.valueOf(end), next == null ? null : Date.valueOf(next), in.mode(),
                        in.currencyId(), in.branchId(), in.costCenterId(), lines, total, id, org)
                .update();
        Map<String, Object> out = render(require(id, false));
        audit.recordInTx(AuditInput.of("RecurringJournalTemplate", id, "update", render(t), out));
        return out;
    }

    /** Stop generating until resumed. */
    @Transactional
    public Map<String, Object> pause(String id) {
        Map<String, Object> t = require(id, true);
        if (!"active".equals(t.get("status"))) {
            throw ApiException.badRequest("Only an active recurring entry can be paused");
        }
        setStatus(id, "paused", (LocalDate) t.get("nextRunDate"));
        return render(require(id, false));
    }

    /** Resume a paused template; occurrences missed while paused are skipped (the next one is today or later). */
    @Transactional
    public Map<String, Object> resume(String id) {
        Map<String, Object> t = require(id, true);
        if (!"paused".equals(t.get("status"))) {
            throw ApiException.badRequest("Only a paused recurring entry can be resumed");
        }
        LocalDate today = LocalDate.now(BusinessZone.get());
        LocalDate next = (LocalDate) t.get("nextRunDate");
        if (next != null && next.isBefore(today)) {
            next = GlSchedules.nextAfter((LocalDate) t.get("startDate"), (String) t.get("frequency"),
                    ((Number) t.get("intervalCount")).intValue(), today.minusDays(1));
        }
        LocalDate end = (LocalDate) t.get("endDate");
        boolean ended = next == null || (end != null && next.isAfter(end));
        setStatus(id, ended ? "ended" : "active", ended ? null : next);
        return render(require(id, false));
    }

    /** Soft-delete a template; its history and the entries it made stay. */
    @Transactional
    public void remove(String id) {
        require(id, true);
        jdbc.sql("UPDATE \"RecurringJournalTemplate\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, TenantContext.organizationId()).update();
        audit.recordInTx(AuditInput.of("RecurringJournalTemplate", id, "delete"));
    }

    /**
     * Manual "run now": generate the occurrence of {@code date} (default today). Replays the existing occurrence when
     * that date was already generated; refused in a closed or locked period.
     */
    @Transactional
    public Map<String, Object> runNow(String id, String date) {
        require(id, false);
        LocalDate when = date == null || date.isEmpty() ? LocalDate.now(BusinessZone.get()) : day(date);
        return generate(id, when, "manual");
    }

    /**
     * Daily job body for the current organization: every active template whose next run date is on or before
     * {@code today}, catching up missed occurrences (at most {@value #CATCH_UP_LIMIT} per template). Each occurrence
     * commits on its own; a failure is recorded on the run history and leaves the schedule where it was.
     */
    public Map<String, Object> runDue(LocalDate today) {
        String org = TenantContext.organizationId();
        List<String> due = isolated.execute(s -> jdbc.sql("SELECT id FROM \"RecurringJournalTemplate\" WHERE"
                + " \"organizationId\" = ? AND status = 'active' AND \"deletedAt\" IS NULL AND \"nextRunDate\" <= ?"
                + " ORDER BY \"nextRunDate\", id").params(org, Date.valueOf(today)).query(String.class).list());
        int generated = 0;
        int failed = 0;
        for (String id : due) {
            for (int i = 0; i < CATCH_UP_LIMIT; i++) {
                LocalDate next = isolated.execute(s -> nextDue(org, id, today));
                if (next == null) {
                    break;
                }
                try {
                    isolated.executeWithoutResult(s -> generate(id, next, "job"));
                    generated++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("[recurring-entries] template {} on {} failed: {}", id, next, e.getMessage());
                    isolated.executeWithoutResult(s -> recordFailure(org, id, next, e.getMessage()));
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", due.size());
        out.put("generated", generated);
        out.put("failed", failed);
        return out;
    }

    // ------------------------------------------------------------------ internals

    private LocalDate nextDue(String org, String id, LocalDate today) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT status, \"nextRunDate\", \"endDate\" FROM"
                + " \"RecurringJournalTemplate\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, org));
        if (t == null || !"active".equals(t.get("status")) || t.get("nextRunDate") == null) {
            return null;
        }
        LocalDate next = (LocalDate) t.get("nextRunDate");
        LocalDate end = (LocalDate) t.get("endDate");
        return next.isAfter(today) || (end != null && next.isAfter(end)) ? null : next;
    }

    /** One occurrence inside the caller's transaction. */
    private Map<String, Object> generate(String id, LocalDate date, String trigger) {
        String org = TenantContext.organizationId();
        Map<String, Object> t = require(id, true);
        Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT * FROM \"RecurringJournalRun\" WHERE"
                + " \"organizationId\" = ? AND \"templateId\" = ? AND \"runDate\" = ? AND status IN ('posted', 'draft')")
                .params(org, id, Date.valueOf(date)));
        if (existing != null) {
            Map<String, Object> out = renderRun(existing);
            out.put("replayed", true);
            return out;
        }
        String key = "recurring:" + id + ":" + date;
        PostingRequest request = new PostingRequest((String) t.get("journalCode"),
                date.atStartOfDay(ZoneOffset.UTC).toInstant(), description(t, date), (String) t.get("currencyId"),
                null, "recurring_entry", id, null, key, (String) t.get("branchId"), (String) t.get("costCenterId"),
                null, postingLines(t.get("lines")));
        boolean draft = "draft".equals(t.get("mode"));
        Map<String, Object> entry;
        if (draft) {
            entry = posting.stageDraft(request);
            // Maker = the template author, so the author cannot approve what the template generates.
            jdbc.sql("UPDATE \"JournalEntry\" SET \"postingKey\" = ?, \"createdBy\" = coalesce(?, \"createdBy\")"
                    + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(key, t.get("createdBy"), entry.get("id"), org).update();
        } else {
            entry = posting.post(request);
        }
        String entryId = (String) entry.get("id");
        String status = draft ? "draft" : "posted";
        jdbc.sql("DELETE FROM \"RecurringJournalRun\" WHERE \"templateId\" = ? AND \"runDate\" = ? AND status = 'failed'")
                .params(id, Date.valueOf(date)).update();
        String runId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"RecurringJournalRun\" (id, \"organizationId\", \"templateId\", \"runDate\", status,"
                        + " \"journalEntryId\", trigger, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(runId, org, id, Date.valueOf(date), status, entryId, trigger, TenantContext.userId()).update();
        advance(t, date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", runId);
        out.put("templateId", id);
        out.put("runDate", date.toString());
        out.put("status", status);
        out.put("trigger", trigger);
        out.put("journalEntryId", entryId);
        out.put("entryNumber", draft ? null : entry.get("entryNumber"));
        out.put("replayed", false);
        return out;
    }

    /** Move the schedule past {@code date} when it was the next (or a later) occurrence; end it past the end date. */
    private void advance(Map<String, Object> t, LocalDate date) {
        LocalDate next = (LocalDate) t.get("nextRunDate");
        LocalDate last = (LocalDate) t.get("lastRunDate");
        LocalDate newLast = last == null || date.isAfter(last) ? date : last;
        String status = (String) t.get("status");
        if (next != null && !date.isBefore(next)) {
            next = GlSchedules.nextAfter((LocalDate) t.get("startDate"), (String) t.get("frequency"),
                    ((Number) t.get("intervalCount")).intValue(), date);
            LocalDate end = (LocalDate) t.get("endDate");
            if (end != null && next.isAfter(end)) {
                next = null;
                status = "ended";
            }
        }
        jdbc.sql("UPDATE \"RecurringJournalTemplate\" SET \"nextRunDate\" = ?, \"lastRunDate\" = ?, status = ?,"
                        + " \"runCount\" = \"runCount\" + 1, \"updatedAt\" = now() WHERE id = ?")
                .params(next == null ? null : Date.valueOf(next), Date.valueOf(newLast), status, t.get("id")).update();
    }

    private void recordFailure(String org, String id, LocalDate date, String error) {
        jdbc.sql("INSERT INTO \"RecurringJournalRun\" (id, \"organizationId\", \"templateId\", \"runDate\", status, error,"
                        + " trigger) VALUES (?, ?, ?, ?, 'failed', ?, 'job') ON CONFLICT (\"templateId\", \"runDate\") DO"
                        + " UPDATE SET error = excluded.error, \"createdAt\" = now() WHERE"
                        + " \"RecurringJournalRun\".status = 'failed'")
                .params(UUID.randomUUID().toString(), org, id, Date.valueOf(date), error).update();
    }

    private void setStatus(String id, String status, LocalDate next) {
        jdbc.sql("UPDATE \"RecurringJournalTemplate\" SET status = ?, \"nextRunDate\" = ?, \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(status, next == null ? null : Date.valueOf(next), id, TenantContext.organizationId()).update();
        audit.recordInTx(AuditInput.of("RecurringJournalTemplate", id, "update", null, Map.of("status", status)));
    }

    private Map<String, Object> require(String id, boolean lock) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"RecurringJournalTemplate\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL" + (lock ? " FOR UPDATE" : ""))
                .params(id, TenantContext.organizationId()));
        if (t == null) {
            throw ApiException.notFound("Recurring entry not found");
        }
        return t;
    }

    private static String description(Map<String, Object> t, LocalDate date) {
        String base = t.get("description") != null ? (String) t.get("description") : (String) t.get("name");
        return base + " (" + date + ")";
    }

    private static List<PostingRequest.Line> postingLines(Object raw) {
        JsonNode lines = raw instanceof JsonNode n ? n : EntityJson.parseJson(String.valueOf(raw)) instanceof JsonNode p
                ? p : null;
        List<PostingRequest.Line> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (JsonNode l : lines) {
            out.add(new PostingRequest.Line(text(l, "accountId"), positive(text(l, "debit")),
                    positive(text(l, "credit")), text(l, "partnerId"), text(l, "description"), text(l, "branchId"),
                    text(l, "costCenterId"), null));
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal positive(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        BigDecimal d = new BigDecimal(v);
        return d.signum() > 0 ? d : null;
    }

    /** Lines must balance, use one side each, and name accounts of this organization; returns the total debit. */
    private BigDecimal validateLines(String org, String journalCode, List<TemplateLine> lines) {
        assertJournal(org, journalCode);
        if (lines.size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        Set<String> accounts = new HashSet<>();
        int n = 1;
        for (TemplateLine l : lines) {
            boolean dr = l.debit() != null && l.debit().signum() > 0;
            boolean cr = l.credit() != null && l.credit().signum() > 0;
            if (dr == cr) {
                throw ApiException.badRequest("Line " + n + ": exactly one of debit or credit must be positive");
            }
            debit = debit.add(dr ? l.debit() : BigDecimal.ZERO);
            credit = credit.add(cr ? l.credit() : BigDecimal.ZERO);
            accounts.add(l.accountId());
            n++;
        }
        if (debit.compareTo(credit) != 0) {
            throw ApiException.badRequest("Unbalanced entry: total debit " + plain(debit) + " != total credit "
                    + plain(credit));
        }
        long found = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])"
                + " AND \"deletedAt\" IS NULL").params(org, accounts.toArray(String[]::new)).query(Long.class).single();
        if (found != accounts.size()) {
            throw ApiException.badRequest("One or more accounts do not exist in this organization");
        }
        return debit;
    }

    private void assertJournal(String org, String code) {
        long n = jdbc.sql("SELECT count(*) FROM \"Journal\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest("Journal '" + code + "' not found");
        }
    }

    private static void assertWindow(LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("endDate must not be before startDate");
        }
    }

    static LocalDate day(String raw) {
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid date " + raw);
        }
    }

    private static Map<String, Object> render(Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>(t);
        for (String k : List.of("startDate", "endDate", "nextRunDate", "lastRunDate")) {
            out.put(k, t.get(k) == null ? null : t.get(k).toString());
        }
        out.put("totalAmount", plain(SqlRows.dec(t, "totalAmount")));
        out.remove("deletedAt");
        out.remove("organizationId");
        return out;
    }

    private static Map<String, Object> renderRun(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("runDate", String.valueOf(r.get("runDate")));
        out.remove("organizationId");
        return out;
    }
}
