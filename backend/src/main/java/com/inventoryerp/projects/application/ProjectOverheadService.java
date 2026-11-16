package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Overhead absorption into project WIP (ADR-S075 §4). Rules are a % of direct cost (all direct elements, or one) or
 * an amount per labour hour (timesheet hours captured on the project), for one project or every project. A period run
 * (YYYY-MM) computes, per WBS element, base × rate over the cost lines dated in the period and posts one journal entry:
 * Dr 1460 project WIP (overhead element, per WBS) / Cr overhead absorbed ({@code overhead_absorbed}). One posted run
 * per project and period — running it again returns the posted run unchanged; reversing frees the period.
 */
@Service
public class ProjectOverheadService {

    static final Set<String> BASES = Set.of("direct_cost_pct", "labour_hour");
    static final Set<String> ELEMENTS = Set.of("material", "labour", "equipment", "subcontract", "other");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record RuleInput(String projectId, String name, String basis, String costElement, BigDecimal rate,
            String validFrom, String validTo, Boolean isActive) {}

    private final JdbcClient jdbc;
    private final ProjectCostService costs;
    private final PostingService posting;
    private final AuditService audit;

    public ProjectOverheadService(JdbcClient jdbc, ProjectCostService costs, PostingService posting,
            AuditService audit) {
        this.jdbc = jdbc;
        this.costs = costs;
        this.posting = posting;
        this.audit = audit;
    }

    // ---- rules ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules(String projectId) {
        if (blank(projectId) != null) {
            costs.projectRow(projectId);
        }
        return SqlRows.list(jdbc.sql("SELECT r.*, p.code AS \"projectCode\" FROM \"ProjectOverheadRule\" r LEFT JOIN"
                + " \"Project\" p ON p.id = r.\"projectId\" WHERE r.\"organizationId\" = ? AND (CAST(? AS text) IS NULL OR"
                + " r.\"projectId\" IS NULL OR r.\"projectId\" = ?) ORDER BY r.\"projectId\" NULLS FIRST, r.name")
                .params(org(), blank(projectId), blank(projectId)));
    }

    @Transactional
    public Map<String, Object> createRule(RuleInput in) {
        String projectId = blank(in.projectId());
        if (projectId != null) {
            costs.projectRow(projectId);
        }
        if (blank(in.name()) == null) {
            throw ApiException.badRequest("name should not be empty");
        }
        String basis = basis(in.basis());
        String element = element(basis, in.costElement());
        BigDecimal rate = rate(in.rate());
        String from = day(in.validFrom());
        String to = day(in.validTo());
        dates(from, to);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProjectOverheadRule\" (id, \"organizationId\", \"projectId\", name, basis,"
                        + " \"costElement\", rate, \"validFrom\", \"validTo\", \"isActive\", \"createdBy\") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?)")
                .params(id, org(), projectId, in.name().trim(), basis, element, rate, from, to,
                        in.isActive() == null || in.isActive(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ProjectOverheadRule", id, "create", null, Obj.of("basis", basis, "rate",
                rate)));
        return rule(id);
    }

    /** Change a rule (null keeps the value; {@code costElement: ""} clears it). The project scope is fixed. */
    @Transactional
    public Map<String, Object> updateRule(String id, RuleInput in) {
        Map<String, Object> r = rule(id);
        String basis = in.basis() == null ? (String) r.get("basis") : basis(in.basis());
        String element = in.costElement() == null ? (String) r.get("costElement") : element(basis, in.costElement());
        if ("labour_hour".equals(basis)) {
            element = null;
        }
        String name = blank(in.name()) == null ? (String) r.get("name") : in.name().trim();
        BigDecimal rate = in.rate() == null ? SqlRows.dec(r, "rate") : rate(in.rate());
        String from = in.validFrom() == null ? str(r.get("validFrom")) : day(in.validFrom());
        String to = in.validTo() == null ? str(r.get("validTo")) : day(in.validTo());
        dates(from, to);
        boolean active = in.isActive() == null ? Boolean.TRUE.equals(r.get("isActive")) : in.isActive();
        jdbc.sql("UPDATE \"ProjectOverheadRule\" SET name = ?, basis = ?, \"costElement\" = ?, rate = ?, \"validFrom\" ="
                + " ?::date, \"validTo\" = ?::date, \"isActive\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(name, basis, element, rate, from, to, active, id, org()).update();
        audit.recordInTx(AuditInput.of("ProjectOverheadRule", id, "update", Obj.of("rate", r.get("rate")),
                Obj.of("rate", rate)));
        return rule(id);
    }

    @Transactional
    public Map<String, Object> deleteRule(String id) {
        rule(id);
        jdbc.sql("DELETE FROM \"ProjectOverheadRule\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        audit.recordInTx(AuditInput.of("ProjectOverheadRule", id, "delete", null, null));
        return Obj.of("id", id, "deleted", true);
    }

    private Map<String, Object> rule(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectOverheadRule\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Overhead rule not found");
        }
        return r;
    }

    // ---- runs -----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String projectId) {
        costs.projectRow(projectId);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectOverheadRun\" WHERE \"projectId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY period DESC, \"createdAt\" DESC").params(projectId, org()));
    }

    /** What a run for the period would post (nothing is written). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String projectId, String period) {
        costs.projectRow(projectId);
        YearMonth ym = period(period);
        Computed c = compute(projectId, ym);
        return Obj.of("projectId", projectId, "period", ym.toString(), "directCost", JsNumbers.dec(c.directCost()),
                "labourHours", JsNumbers.dec(c.hours()), "amount", JsNumbers.dec(c.amount()), "detail", c.detail(),
                "posted", existing(projectId, ym) != null);
    }

    /** Post the period's absorption for one project; the posted run of that period when there already is one. */
    @Transactional
    public Map<String, Object> run(String projectId, String period) {
        Map<String, Object> p = costs.capturable(projectId);
        YearMonth ym = period(period);
        Map<String, Object> done = existing(projectId, ym);
        if (done != null) {
            return done;
        }
        Computed c = compute(projectId, ym);
        if (c.amount().signum() <= 0) {
            throw ApiException.badRequest("No overhead to absorb on project " + p.get("code") + " for " + ym
                    + " (no applicable rule or no base cost)");
        }
        String id = UUID.randomUUID().toString();
        Instant date = ym.atEndOfMonth().atStartOfDay(ZoneOffset.UTC).toInstant();
        String text = "Project " + p.get("code") + " · overhead absorbed " + ym;
        List<Line> lines = new ArrayList<>();
        String credit = costs.overheadAbsorbedAccount();
        for (Map<String, Object> d : c.perWbs()) {
            BigDecimal a = (BigDecimal) d.get("amount");
            if (a.signum() > 0) {
                lines.add(costs.debit(p, Obj.of("id", d.get("wbsId")), null, a, text + " · " + d.get("wbsCode")));
            }
        }
        lines.add(new Line(credit, null, JsNumbers.dec(c.amount()), null, text, ProjectCostService.branch(p), null,
                Obj.of("projectId", projectId, "overheadRunId", id)));
        String je = costs.post(lines, text, date, "project_overhead", id, p);
        for (Map<String, Object> d : c.perWbs()) {
            BigDecimal a = (BigDecimal) d.get("amount");
            if (a.signum() > 0) {
                costs.insert(UUID.randomUUID().toString(), projectId, (String) d.get("wbsId"), null, "overhead",
                        "project_overhead", id, null, date, null, null, a, false, credit, je, text + " · "
                                + d.get("wbsCode"));
            }
        }
        jdbc.sql("INSERT INTO \"ProjectOverheadRun\" (id, \"organizationId\", \"projectId\", period, \"directCost\","
                        + " \"labourHours\", amount, detail, \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?::jsonb, ?, ?)")
                .params(id, org(), projectId, ym.toString(), c.directCost(), c.hours(), c.amount(),
                        JSON.writeValueAsString(c.detail()), je, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ProjectOverheadRun", id, "post", null, Obj.of("period", ym.toString(),
                "amount", c.amount())));
        return runRow(id);
    }

    /** Run the period for every active project with an applicable rule; projects already run are listed as such. */
    @Transactional
    public Map<String, Object> runAll(String period) {
        YearMonth ym = period(period);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, code FROM \"Project\" p WHERE"
                + " \"organizationId\" = ? AND status = 'active' AND EXISTS (SELECT 1 FROM \"ProjectOverheadRule\" r WHERE"
                + " r.\"organizationId\" = p.\"organizationId\" AND r.\"isActive\" AND (r.\"projectId\" IS NULL OR"
                + " r.\"projectId\" = p.id)) ORDER BY code").param(org()))) {
            String pid = (String) p.get("id");
            Map<String, Object> done = existing(pid, ym);
            if (done != null) {
                out.add(Obj.of("projectId", pid, "code", p.get("code"), "status", "already_posted", "runId",
                        done.get("id"), "amount", done.get("amount")));
                continue;
            }
            if (compute(pid, ym).amount().signum() <= 0) {
                out.add(Obj.of("projectId", pid, "code", p.get("code"), "status", "nothing_to_absorb"));
                continue;
            }
            Map<String, Object> r = run(pid, ym.toString());
            out.add(Obj.of("projectId", pid, "code", p.get("code"), "status", "posted", "runId", r.get("id"), "amount",
                    r.get("amount")));
        }
        return Obj.of("period", ym.toString(), "projects", out);
    }

    /** Reverse a posted run: its journal entry and the overhead cost lines; the period can be run again. */
    @Transactional
    public Map<String, Object> reverse(String runId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectOverheadRun\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(runId, org()));
        if (r == null) {
            throw ApiException.notFound("Overhead run not found");
        }
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("This overhead run is already reversed");
        }
        String reversal = r.get("journalEntryId") == null ? null : (String) posting.reverse(
                (String) r.get("journalEntryId"), Instant.now(), "Reversal of overhead run " + r.get("period")).get("id");
        jdbc.sql("UPDATE \"ProjectCostLine\" SET status = 'reversed', \"reversalEntryId\" = ? WHERE \"organizationId\" ="
                + " ? AND \"sourceType\" = 'project_overhead' AND \"sourceId\" = ? AND status = 'posted'").params(reversal,
                org(), runId).update();
        jdbc.sql("UPDATE \"ProjectOverheadRun\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedBy\" = ?,"
                + " \"reversedAt\" = now() WHERE id = ?").params(reversal, TenantContext.userId(), runId).update();
        audit.recordInTx(AuditInput.of("ProjectOverheadRun", runId, "cancel", null, Obj.of("reversalEntryId",
                reversal)));
        return runRow(runId);
    }

    private Map<String, Object> runRow(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectOverheadRun\" WHERE id = ?").param(id));
    }

    private Map<String, Object> existing(String projectId, YearMonth ym) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectOverheadRun\" WHERE \"projectId\" = ? AND"
                + " \"organizationId\" = ? AND period = ? AND status = 'posted'").params(projectId, org(), ym.toString()));
    }

    record Computed(BigDecimal directCost, BigDecimal hours, BigDecimal amount, List<Map<String, Object>> perWbs,
            List<Map<String, Object>> detail) {}

    /**
     * Base per WBS element from the posted direct cost lines dated in the period (overhead lines never count), times
     * every active rule of the project (and the organisation-wide ones) valid at the period end.
     */
    Computed compute(String projectId, YearMonth ym) {
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectOverheadRule\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" AND (\"projectId\" IS NULL OR \"projectId\" = ?) AND"
                + " (\"validFrom\" IS NULL OR \"validFrom\" <= ?::date) AND (\"validTo\" IS NULL OR \"validTo\" >= ?::date)"
                + " ORDER BY \"projectId\" NULLS LAST, name").params(org(), projectId, to.toString(), to.toString()));
        List<Map<String, Object>> base = SqlRows.list(jdbc.sql("SELECT c.\"wbsId\", w.code AS \"wbsCode\","
                + " c.\"costElement\", coalesce(sum(c.amount), 0) AS amount, coalesce(sum(CASE WHEN c.\"sourceType\" ="
                + " 'project_timesheet' THEN c.quantity ELSE 0 END), 0) AS hours FROM \"ProjectCostLine\" c JOIN"
                + " \"ProjectWbsElement\" w ON w.id = c.\"wbsId\" WHERE c.\"projectId\" = ? AND c.\"organizationId\" = ?"
                + " AND c.status = 'posted' AND c.\"costElement\" <> 'overhead' AND c.\"sourceType\" <> 'project_overhead'"
                + " AND c.date >= ?::date AND c.date <= ?::date GROUP BY 1, 2, 3 ORDER BY 2, 3").params(projectId, org(),
                from.toString(), to.toString()));
        Map<String, Map<String, Object>> perWbs = new LinkedHashMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        BigDecimal direct = BigDecimal.ZERO;
        BigDecimal hours = BigDecimal.ZERO;
        for (Map<String, Object> b : base) {
            direct = direct.add(SqlRows.dec(b, "amount"));
            hours = hours.add(SqlRows.dec(b, "hours"));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rules) {
            BigDecimal rate = SqlRows.dec(r, "rate");
            boolean perHour = "labour_hour".equals(r.get("basis"));
            Map<String, BigDecimal[]> byWbs = new LinkedHashMap<>();
            Map<String, String> codes = new LinkedHashMap<>();
            for (Map<String, Object> b : base) {
                if (!perHour && r.get("costElement") != null && !r.get("costElement").equals(b.get("costElement"))) {
                    continue;
                }
                String w = (String) b.get("wbsId");
                codes.put(w, (String) b.get("wbsCode"));
                BigDecimal[] acc = byWbs.computeIfAbsent(w, k -> new BigDecimal[] {BigDecimal.ZERO});
                acc[0] = acc[0].add(perHour ? SqlRows.dec(b, "hours") : SqlRows.dec(b, "amount"));
            }
            for (Map.Entry<String, BigDecimal[]> e : byWbs.entrySet()) {
                BigDecimal b = e.getValue()[0];
                BigDecimal amount = perHour ? b.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                        : b.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                if (amount.signum() <= 0) {
                    continue;
                }
                total = total.add(amount);
                Map<String, Object> w = perWbs.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>(Map.of("wbsId", k,
                        "wbsCode", codes.get(k), "amount", BigDecimal.ZERO)));
                w.put("amount", ((BigDecimal) w.get("amount")).add(amount));
                detail.add(Obj.of("ruleId", r.get("id"), "ruleName", r.get("name"), "basis", r.get("basis"),
                        "costElement", r.get("costElement"), "rate", JsNumbers.dec(rate), "wbsId", e.getKey(), "wbsCode",
                        codes.get(e.getKey()), "base", JsNumbers.dec(b), "amount", JsNumbers.dec(amount)));
            }
        }
        return new Computed(direct.setScale(2, RoundingMode.HALF_UP), hours, total, new ArrayList<>(perWbs.values()),
                detail);
    }

    private static YearMonth period(String v) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("period should not be empty");
        }
        try {
            YearMonth ym = YearMonth.parse(v.trim().length() > 7 ? v.trim().substring(0, 7) : v.trim());
            if (ym.isAfter(YearMonth.now(ZoneOffset.UTC))) {
                throw ApiException.badRequest("period cannot be in the future");
            }
            return ym;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("period must be a month (YYYY-MM)");
        }
    }

    private static String basis(String basis) {
        if (basis == null || !BASES.contains(basis)) {
            throw ApiException.badRequest("basis must be one of the following values: direct_cost_pct, labour_hour");
        }
        return basis;
    }

    private static String element(String basis, String element) {
        String e = blank(element);
        if (e == null || "labour_hour".equals(basis)) {
            return null;
        }
        if (!ELEMENTS.contains(e)) {
            throw ApiException.badRequest("costElement must be one of the following values: material, labour,"
                    + " equipment, subcontract, other");
        }
        return e;
    }

    private static BigDecimal rate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw ApiException.badRequest("rate must be greater than 0");
        }
        return rate;
    }

    private static void dates(String from, String to) {
        if (from != null && to != null && LocalDate.parse(to).isBefore(LocalDate.parse(from))) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
