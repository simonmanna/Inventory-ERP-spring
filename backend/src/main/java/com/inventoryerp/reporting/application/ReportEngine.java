package com.inventoryerp.reporting.application;

import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs library reports (FR-0, ADR-S043): checks the report's permission and filters, applies the caller's branch
 * data scope, resolves the period and comparative columns in the organization's time zone, calls the provider, adds
 * the standard header and records the run (parameters and a SHA-256 of the result) so a run can be reproduced and
 * verified later. Reports only read; the run log is the only write.
 */
@Service
public class ReportEngine {

    /** Standard report header (owner catalogue §2.A). */
    public record Header(String company, String reportId, String title, String category, String level, String period,
            String from, String to, String asOf, String currency, String basis, List<String> branches,
            String postingStatus, Instant generatedAt, String generatedBy, String runId, String resultHash) {}

    public record ReportResult(Header header, List<ReportBody.Column> columns, List<ReportBody.Row> rows,
            Map<String, Object> totals, List<ReportBody.Check> checks, Map<String, Object> meta,
            List<ReportPeriod> periods) {}

    private final ReportRegistry registry;
    private final BranchScopeService scopes;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReportEngine(ReportRegistry registry, BranchScopeService scopes, JdbcClient jdbc, JsonMapper json) {
        this.registry = registry;
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.json = json;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The library: the 15 categories with the reports the caller may run, in menu order. */
    public List<Map<String, Object>> library() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportCategory c : ReportCategory.values()) {
            List<ReportDefinition> reports = registry.all().stream().filter(d -> d.category() == c)
                    .filter(d -> TenantContext.has(d.permission())).toList();
            out.add(Obj.of("code", c.code(), "key", c.name(), "title", c.title(), "reports", reports));
        }
        return out;
    }

    /** One definition the caller may see; 404 when unknown, 403 without its permission. */
    public ReportDefinition definition(String id) {
        ReportDefinition d = registry.find(id).orElseThrow(() -> ApiException.notFound("Report " + id
                + " not found"));
        if (!TenantContext.has(d.permission())) {
            throw ApiException.forbidden("Missing required permission(s): " + d.permission());
        }
        return d;
    }

    /** Run an engine report and record the run. */
    @Transactional
    public ReportResult run(String id, ReportRequest request, String trigger) {
        ReportDefinition def = definition(id);
        if (!def.engine()) {
            throw ApiException.badRequest("Report " + id + " has its own screen" + (def.webRoute() == null ? ""
                    : ": " + def.webRoute()));
        }
        ReportRequest req = request == null ? ReportRequest.empty() : request;
        validateFilters(def, req);
        ReportRequest scoped = req.withBranches(scopedBranches(req.branchIds())).withOptions(options(def, req));
        ZoneId zone = OrgDates.orgTimezone(jdbc, org());
        LocalDate today = LocalDate.now(zone);
        LocalDate fiscalStart = fiscalStart(today);
        ReportPeriod current = ReportPeriods.current(def, scoped, today, fiscalStart);
        List<ReportPeriod> comparatives = def.accepts(FilterKey.COMPARE) ? ReportPeriods.comparatives(current,
                scoped.compare()) : List.of();
        ReportContext ctx = new ReportContext(org(), def, scoped, zone, current, comparatives, scoped.compare()
                .contains("VARIANCE"), scoped.compare().contains("VARIANCE_PCT"), scoped.compare().contains("BUDGET"),
                today, fiscalStart);
        long started = System.nanoTime();
        ReportBody body = registry.provider(id).run(ctx);
        int ms = (int) ((System.nanoTime() - started) / 1_000_000);
        String hash = sha256(json.writeValueAsString(Obj.of("columns", body.columns(), "rows", body.rows(), "totals",
                body.totals())));
        String runId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ReportRun\" (id, \"organizationId\", \"reportId\", params, \"resultHash\", \"rowCount\","
                        + " \"durationMs\", trigger, \"createdBy\") VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                .params(runId, org(), id, json.writeValueAsString(req), hash, body.rows().size(), ms, trigger,
                        TenantContext.userId())
                .update();
        List<ReportPeriod> periods = new ArrayList<>();
        periods.add(current);
        periods.addAll(comparatives);
        Header header = header(def, scoped, current, runId, hash);
        return new ReportResult(header, body.columns(), body.rows(), body.totals(), body.checks(), body.meta(),
                periods);
    }

    /** Recent runs: the caller's own, or everyone's with {@code report_library:manage}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String reportId) {
        boolean all = TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE);
        StringBuilder sql = new StringBuilder("SELECT r.id, r.\"reportId\", r.params, r.\"resultHash\", r.\"rowCount\","
                + " r.\"durationMs\", r.trigger, r.\"createdBy\", r.\"createdAt\" FROM \"ReportRun\" r"
                + " WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!all) {
            sql.append(" AND r.\"createdBy\" = ?");
            params.add(TenantContext.userId());
        }
        if (reportId != null && !reportId.isBlank()) {
            sql.append(" AND r.\"reportId\" = ?");
            params.add(reportId);
        }
        sql.append(" ORDER BY r.\"createdAt\" DESC LIMIT 100");
        return SqlRows.list(jdbc.sql(sql.toString()).params(params)).stream()
                .filter(r -> registry.find((String) r.get("reportId")).map(d -> TenantContext.has(d.permission()))
                        .orElse(false))
                .toList();
    }

    /** Re-run a recorded run with its parameters and compare the result hash (reproducibility of closed periods). */
    @Transactional
    public Map<String, Object> verify(String runId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT \"reportId\", params::text AS params, \"resultHash\","
                + " \"createdBy\", \"createdAt\" FROM \"ReportRun\" WHERE id = ? AND \"organizationId\" = ?")
                .params(runId, org()));
        if (r == null) {
            throw ApiException.notFound("Report run not found");
        }
        if (!TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE)
                && !String.valueOf(TenantContext.userId()).equals(r.get("createdBy"))) {
            throw ApiException.notFound("Report run not found");
        }
        ReportRequest params = json.readValue((String) r.get("params"), ReportRequest.class);
        ReportResult again = run((String) r.get("reportId"), params, "VERIFY");
        boolean same = again.header().resultHash().equals(r.get("resultHash"));
        return Obj.of("runId", runId, "reportId", r.get("reportId"), "originalHash", r.get("resultHash"),
                "currentHash", again.header().resultHash(), "originalAt", r.get("createdAt"), "reproducible", same,
                "verifyRunId", again.header().runId());
    }

    /** Option defaults filled; unknown keys and bad numbers or choices refused. */
    static Map<String, String> options(ReportDefinition def, ReportRequest r) {
        Map<String, String> out = new LinkedHashMap<>();
        for (com.inventoryerp.reporting.spi.OptionSpec o : def.options()) {
            if (o.defaultValue() != null) {
                out.put(o.key(), o.defaultValue());
            }
        }
        for (Map.Entry<String, String> e : r.options().entrySet()) {
            com.inventoryerp.reporting.spi.OptionSpec spec = def.options().stream().filter(o -> o.key().equals(
                    e.getKey())).findFirst().orElseThrow(() -> ApiException.badRequest("options." + e.getKey()
                    + " is not an option of report " + def.id()));
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) {
                continue;
            }
            if ("number".equals(spec.type())) {
                try {
                    new java.math.BigDecimal(v);
                } catch (NumberFormatException ex) {
                    throw ApiException.badRequest("options." + e.getKey() + " must be a number");
                }
            }
            if ("select".equals(spec.type()) && !spec.choices().contains(v)) {
                throw ApiException.badRequest("options." + e.getKey() + " must be one of: " + String.join(", ",
                        spec.choices()));
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** First day of the fiscal year containing {@code today}; January 1 when none is defined. */
    private LocalDate fiscalStart(LocalDate today) {
        return jdbc.sql("SELECT \"startDate\" FROM \"FiscalYear\" WHERE \"organizationId\" = ? AND \"startDate\" <= ?"
                + " AND \"endDate\" >= ? ORDER BY \"startDate\" DESC LIMIT 1").params(org(), today, today)
                .query(LocalDate.class).optional().orElse(today.withDayOfYear(1));
    }

    private List<String> scopedBranches(List<String> requested) {
        DataScope scope = scopes.scope();
        if (!scope.restricted()) {
            return requested;
        }
        if (requested.isEmpty()) {
            List<String> mine = scope.branchIds().stream().sorted().toList();
            return mine.isEmpty() ? List.of("-") : mine;
        }
        requested.forEach(scopes::assertBranch);
        return requested;
    }

    static void validateFilters(ReportDefinition def, ReportRequest r) {
        boolean period = def.accepts(FilterKey.PERIOD);
        boolean asOf = def.accepts(FilterKey.AS_OF);
        refuse(def, "from", r.from() != null && !r.from().isBlank() && !period);
        refuse(def, "to", r.to() != null && !r.to().isBlank() && !period && !asOf);
        refuse(def, "period", r.period() != null && !r.period().isBlank() && !period && !asOf);
        refuse(def, "asOf", r.asOf() != null && !r.asOf().isBlank() && !asOf);
        refuse(def, "branchIds", !r.branchIds().isEmpty() && !def.accepts(FilterKey.BRANCH));
        refuse(def, "costCenterIds", !r.costCenterIds().isEmpty() && !def.accepts(FilterKey.COST_CENTER));
        refuse(def, "partnerIds", !r.partnerIds().isEmpty() && !def.accepts(FilterKey.PARTNER));
        refuse(def, "accountIds", !r.accountIds().isEmpty() && !def.accepts(FilterKey.ACCOUNT));
        refuse(def, "journalIds", !r.journalIds().isEmpty() && !def.accepts(FilterKey.JOURNAL));
        refuse(def, "sourceTypes", !r.sourceTypes().isEmpty() && !def.accepts(FilterKey.SOURCE));
        refuse(def, "dimensions", !r.dimensions().isEmpty() && !def.accepts(FilterKey.DIMENSION));
        refuse(def, "postingStatus", r.postingStatus() != null && !def.accepts(FilterKey.POSTING_STATUS));
        refuse(def, "compare", !r.compare().isEmpty() && !def.accepts(FilterKey.COMPARE));
        refuse(def, "layoutCode", r.layoutCode() != null && !r.layoutCode().isBlank() && !def.accepts(
                FilterKey.LAYOUT));
        options(def, r);
    }

    private static void refuse(ReportDefinition def, String field, boolean set) {
        if (set) {
            throw ApiException.badRequest(field + " is not a filter of report " + def.id());
        }
    }

    private Header header(ReportDefinition def, ReportRequest r, ReportPeriod p, String runId, String hash) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT name, \"currencyCode\" FROM \"Organization\" WHERE id = ?")
                .param(org()));
        List<String> branches = r.branchIds().isEmpty() ? List.of() : jdbc.sql("SELECT name FROM \"Branch\" WHERE"
                + " \"organizationId\" = ? AND id = ANY(?::text[]) ORDER BY name").params(org(), r.branchIds().toArray(
                String[]::new)).query(String.class).list();
        String user = TenantContext.userId() == null ? null : jdbc.sql("SELECT nullif(concat_ws(' ', \"firstName\","
                + " \"lastName\"), '') FROM \"User\" WHERE id = ?").param(TenantContext.userId()).query(String.class)
                .optional().orElse(null);
        return new Header(o == null ? null : (String) o.get("name"), def.id(), def.title(), def.category().title(),
                def.level(), p.label(), p.from() == null ? null : p.from().toString(), p.from() == null ? null
                        : p.to().toString(), p.from() == null ? p.to().toString() : null, o == null ? null
                        : (String) o.get("currencyCode"), "Accrual", branches, r.postingStatus() == null ? "POSTED"
                        : r.postingStatus(), Instant.now(), user, runId, hash);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copy of the request parameters as a map (saved views and schedules store this). */
    public Map<String, Object> toMap(ReportRequest r) {
        return new LinkedHashMap<>(json.convertValue(r, Map.class));
    }

    public ReportRequest fromMap(Map<String, Object> m) {
        return m == null ? ReportRequest.empty() : json.convertValue(m, ReportRequest.class);
    }
}
