package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost allocation rules and runs (ADR-S007 §4). A rule has weighted target centres (percent, floor area, headcount
 * … — shares are weight / Σ weights). A {@code split} rule is applied inline by {@link CostCenterRulesService} when
 * a posting line carries its id; a {@code periodic} rule is run per period: the net P&amp;L balance of the source
 * centre (all revenue / expense accounts, or the rule's accounts) is moved to the targets with one balanced
 * allocation entry. A rule runs once per period (unique while posted); a run is undone by reversing it.
 */
@Service
public class CostAllocationService {

    /** One weighted target. */
    public record TargetInput(String costCenterId, BigDecimal weight) {}

    /** Create / replace body. */
    public record RuleInput(String code, String name, String kind, String driver, String sourceCostCenterId,
            List<String> accountIds, Boolean isActive, String notes, List<TargetInput> targets) {}

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final CostCenterRulesService rules;
    private final AuditService audit;

    public CostAllocationService(JdbcClient jdbc, PostingService posting, CostCenterRulesService rules,
            AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.rules = rules;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.*, c.code AS \"sourceCostCenterCode\","
                + " c.name AS \"sourceCostCenterName\" FROM \"CostAllocationRule\" r LEFT JOIN \"CostCenter\" c"
                + " ON c.id = r.\"sourceCostCenterId\" WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL"
                + (kind != null && !kind.isEmpty() ? " AND r.kind = ?" : " AND ?::text IS NULL") + " ORDER BY r.code")
                .params(org(), kind == null || kind.isEmpty() ? null : kind));
        rows.forEach(this::withTargets);
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return withTargets(require(id));
    }

    @Transactional
    public Map<String, Object> create(RuleInput in) {
        validate(in, null);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostAllocationRule\" (id, \"organizationId\", code, name, kind, driver,"
                        + " \"sourceCostCenterId\", \"accountIds\", \"isActive\", notes, \"createdBy\") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?)")
                .params(id, org(), in.code(), in.name(), in.kind(), in.driver() == null ? "percent" : in.driver(),
                        blank(in.sourceCostCenterId()), array(in.accountIds()), in.isActive() == null || in.isActive(),
                        in.notes(), TenantContext.userId())
                .update();
        writeTargets(id, in.targets());
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, RuleInput in) {
        Map<String, Object> r = require(id);
        RuleInput merged = new RuleInput(in.code() != null ? in.code() : (String) r.get("code"),
                in.name() != null ? in.name() : (String) r.get("name"), (String) r.get("kind"),
                in.driver() != null ? in.driver() : (String) r.get("driver"),
                in.sourceCostCenterId() != null ? in.sourceCostCenterId() : (String) r.get("sourceCostCenterId"),
                in.accountIds() != null ? in.accountIds() : strings(r.get("accountIds")),
                in.isActive() != null ? in.isActive() : (Boolean) r.get("isActive"),
                in.notes() != null ? in.notes() : (String) r.get("notes"),
                in.targets() != null ? in.targets() : null);
        validate(merged, id);
        jdbc.sql("UPDATE \"CostAllocationRule\" SET code = ?, name = ?, driver = ?, \"sourceCostCenterId\" = ?,"
                        + " \"accountIds\" = ?::text[], \"isActive\" = ?, notes = ?, \"updatedAt\" = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(merged.code(), merged.name(), merged.driver(), blank(merged.sourceCostCenterId()),
                        array(merged.accountIds()), merged.isActive(), merged.notes(), id, org())
                .update();
        if (in.targets() != null) {
            jdbc.sql("DELETE FROM \"CostAllocationTarget\" WHERE \"organizationId\" = ? AND \"ruleId\" = ?")
                    .params(org(), id).update();
            writeTargets(id, in.targets());
        }
        return get(id);
    }

    @Transactional
    public void remove(String id) {
        require(id);
        jdbc.sql("UPDATE \"CostAllocationRule\" SET \"deletedAt\" = CURRENT_TIMESTAMP, \"isActive\" = false WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()).update();
    }

    // ------------------------------------------------------------------ runs

    /** What a periodic run would move: per account the source balance and the per-target amounts. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String ruleId, LocalDate from, LocalDate to) {
        Map<String, Object> r = periodicRule(ruleId);
        Plan plan = plan(r, from, to);
        return Obj.of("rule", withTargets(r), "periodFrom", from.toString(), "periodTo", to.toString(),
                "totalAmount", JsNumbers.dec(plan.total), "accounts", plan.rows);
    }

    /** Post the allocation entry for one period. */
    @Transactional
    public Map<String, Object> run(String ruleId, LocalDate from, LocalDate to) {
        Map<String, Object> r = periodicRule(ruleId);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("cost-alloc:" + org() + ":" + ruleId)
                .query().listOfRows();
        long done = jdbc.sql("SELECT count(*) FROM \"CostAllocationRun\" WHERE \"organizationId\" = ? AND \"ruleId\" = ?"
                + " AND \"periodFrom\" = ? AND \"periodTo\" = ? AND status = 'posted'")
                .params(org(), ruleId, from, to).query(Long.class).single();
        if (done > 0) {
            throw ApiException.conflict("Rule " + r.get("code") + " was already run for " + from + " – " + to
                    + "; reverse that run first");
        }
        Plan plan = plan(r, from, to);
        if (plan.lines.isEmpty()) {
            throw ApiException.badRequest("Nothing to allocate: cost center " + r.get("sourceCostCenterCode")
                    + " has no balance on the rule's accounts in " + from + " – " + to);
        }
        String runId = UUID.randomUUID().toString();
        String description = "Cost allocation " + r.get("code") + " " + from + " – " + to;
        Map<String, Object> je = posting.post(new PostingRequest("GEN", to.atStartOfDay().toInstant(ZoneOffset.UTC)
                .plusSeconds(43200), description, null, null, "cost_allocation", runId, null,
                "accounting:cost_allocation:" + org() + ":" + ruleId + ":" + from + ":" + to + ":" + runId, null, null,
                null, plan.lines));
        jdbc.sql("INSERT INTO \"CostAllocationRun\" (id, \"organizationId\", \"ruleId\", \"periodFrom\", \"periodTo\","
                        + " \"totalAmount\", \"journalEntryId\", detail, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(runId, org(), ruleId, from, to, plan.total, je.get("id"), EntityJson.toJson(plan.rows),
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("CostAllocationRun", runId, "create", null, Obj.of("ruleId", ruleId,
                "periodFrom", from.toString(), "periodTo", to.toString(), "total", JsNumbers.dec(plan.total))));
        return getRun(runId);
    }

    /** Reverse a posted run (mirror entry); the period can then be run again. */
    @Transactional
    public Map<String, Object> reverseRun(String runId) {
        Map<String, Object> run = getRun(runId);
        if (!"posted".equals(run.get("status"))) {
            throw ApiException.badRequest("Only a posted allocation run can be reversed");
        }
        posting.reverse((String) run.get("journalEntryId"), null, "Reversal of cost allocation run");
        jdbc.sql("UPDATE \"CostAllocationRun\" SET status = 'reversed', \"reversedAt\" = CURRENT_TIMESTAMP,"
                + " \"reversedBy\" = ? WHERE id = ? AND \"organizationId\" = ?").params(TenantContext.userId(), runId,
                org()).update();
        return getRun(runId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String ruleId) {
        return SqlRows.list(jdbc.sql("SELECT u.*, r.code AS \"ruleCode\", r.name AS \"ruleName\", e.\"entryNumber\""
                + " FROM \"CostAllocationRun\" u JOIN \"CostAllocationRule\" r ON r.id = u.\"ruleId\""
                + " LEFT JOIN \"JournalEntry\" e ON e.id = u.\"journalEntryId\" WHERE u.\"organizationId\" = ?"
                + (ruleId != null && !ruleId.isEmpty() ? " AND u.\"ruleId\" = ?" : " AND ?::text IS NULL")
                + " ORDER BY u.\"createdAt\" DESC LIMIT 200").params(org(), ruleId == null || ruleId.isEmpty() ? null
                : ruleId));
    }

    private Map<String, Object> getRun(String runId) {
        Map<String, Object> run = SqlRows.one(jdbc.sql("SELECT u.*, e.\"entryNumber\" FROM \"CostAllocationRun\" u"
                + " LEFT JOIN \"JournalEntry\" e ON e.id = u.\"journalEntryId\" WHERE u.id = ? AND u.\"organizationId\" = ?")
                .params(runId, org()));
        if (run == null) {
            throw ApiException.notFound("Allocation run not found");
        }
        return run;
    }

    private record Plan(BigDecimal total, List<Line> lines, List<Map<String, Object>> rows) {}

    private Plan plan(Map<String, Object> r, LocalDate from, LocalDate to) {
        List<Map<String, Object>> targets = targets((String) r.get("id"));
        BigDecimal weightSum = BigDecimal.ZERO;
        for (Map<String, Object> t : targets) {
            weightSum = weightSum.add(SqlRows.dec(t, "weight"));
        }
        List<String> accountIds = strings(r.get("accountIds"));
        java.time.ZoneId zone = AccountingDay.zone(jdbc);
        List<Object> params = new ArrayList<>(List.of(org(), r.get("sourceCostCenterId"), AccountingDay.startTs(from, zone),
                AccountingDay.endExclusiveTs(to, zone)));
        String accountFilter;
        if (accountIds != null && !accountIds.isEmpty()) {
            accountFilter = " AND l.\"accountId\" = ANY(?::text[])";
            params.add(accountIds.toArray(String[]::new));
        } else {
            accountFilter = " AND c.\"reportSection\"::text IN ('revenue', 'contra_revenue', 'cogs', 'operating_expense',"
                    + " 'other_income', 'other_expense')";
        }
        List<Map<String, Object>> balances = SqlRows.list(jdbc.sql("SELECT l.\"accountId\", a.code, a.name,"
                + " SUM(l.\"baseDebit\" - l.\"baseCredit\") AS net FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"costCenterId\" = ? AND e.status IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?" + accountFilter
                + " GROUP BY l.\"accountId\", a.code, a.name HAVING SUM(l.\"baseDebit\" - l.\"baseCredit\") <> 0"
                + " ORDER BY a.code").params(params));
        List<Line> lines = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String description = "Allocation " + r.get("code");
        for (Map<String, Object> b : balances) {
            BigDecimal net = SqlRows.dec(b, "net").setScale(6, RoundingMode.HALF_UP);
            boolean debitBalance = net.signum() > 0;
            BigDecimal abs = net.abs();
            total = total.add(abs);
            String accountId = (String) b.get("accountId");
            lines.add(new Line(accountId, debitBalance ? null : JsNumbers.dec(abs), debitBalance ? JsNumbers.dec(abs) : null,
                    null, description, null, (String) r.get("sourceCostCenterId"), null));
            BigDecimal left = abs;
            List<Map<String, Object>> parts = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Map<String, Object> t = targets.get(i);
                BigDecimal part = i == targets.size() - 1 ? left
                        : CostCenterRulesService.Money6.share(abs, SqlRows.dec(t, "weight"), weightSum);
                left = left.subtract(part);
                if (part.signum() == 0) {
                    continue;
                }
                lines.add(new Line(accountId, debitBalance ? JsNumbers.dec(part) : null,
                        debitBalance ? null : JsNumbers.dec(part), null, description, null,
                        (String) t.get("costCenterId"), null));
                parts.add(Obj.of("costCenterId", t.get("costCenterId"), "costCenterCode", t.get("code"), "amount",
                        JsNumbers.dec(part)));
            }
            rows.add(Obj.of("accountId", accountId, "accountCode", b.get("code"), "accountName", b.get("name"),
                    "balance", JsNumbers.dec(net), "targets", parts));
        }
        return new Plan(total, lines, rows);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> periodicRule(String ruleId) {
        Map<String, Object> r = require(ruleId);
        if (!"periodic".equals(r.get("kind"))) {
            throw ApiException.badRequest("Only a periodic allocation rule can be run");
        }
        if (!Boolean.TRUE.equals(r.get("isActive"))) {
            throw ApiException.badRequest("Allocation rule " + r.get("code") + " is inactive");
        }
        return r;
    }

    private void validate(RuleInput in, String selfId) {
        if (in.code() == null || in.code().isBlank() || in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("code and name are required");
        }
        if (!"split".equals(in.kind()) && !"periodic".equals(in.kind())) {
            throw ApiException.badRequest("kind must be one of the following values: split, periodic");
        }
        long dup = jdbc.sql("SELECT count(*) FROM \"CostAllocationRule\" WHERE \"organizationId\" = ? AND code = ?"
                + " AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(org(), in.code(), selfId)
                .query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Allocation rule code " + in.code() + " already exists");
        }
        if ("periodic".equals(in.kind())) {
            if (blank(in.sourceCostCenterId()) == null) {
                throw ApiException.badRequest("A periodic rule needs a source cost center");
            }
            rules.assertCenter(in.sourceCostCenterId());
        }
        if (in.targets() != null) {
            if (in.targets().isEmpty()) {
                throw ApiException.badRequest("An allocation rule needs at least one target");
            }
            for (TargetInput t : in.targets()) {
                if (t.weight() == null || t.weight().signum() <= 0) {
                    throw ApiException.badRequest("Target weights must be greater than zero");
                }
                rules.assertCenter(t.costCenterId());
                if (t.costCenterId().equals(in.sourceCostCenterId())) {
                    throw ApiException.badRequest("A target cannot be the source cost center");
                }
            }
        } else if (selfId == null) {
            throw ApiException.badRequest("An allocation rule needs at least one target");
        }
        if (in.accountIds() != null && !in.accountIds().isEmpty()) {
            long n = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])")
                    .params(org(), in.accountIds().toArray(String[]::new)).query(Long.class).single();
            if (n != in.accountIds().size()) {
                throw ApiException.badRequest("One or more accounts do not exist in this organization");
            }
        }
    }

    private void writeTargets(String ruleId, List<TargetInput> targets) {
        int i = 1;
        for (TargetInput t : targets) {
            jdbc.sql("INSERT INTO \"CostAllocationTarget\" (id, \"organizationId\", \"ruleId\", \"costCenterId\", weight,"
                            + " \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), ruleId, t.costCenterId(), t.weight(), i++).update();
        }
    }

    private List<Map<String, Object>> targets(String ruleId) {
        return SqlRows.list(jdbc.sql("SELECT t.\"costCenterId\", t.weight, c.code, c.name FROM \"CostAllocationTarget\" t"
                + " JOIN \"CostCenter\" c ON c.id = t.\"costCenterId\" WHERE t.\"organizationId\" = ? AND t.\"ruleId\" = ?"
                + " ORDER BY t.\"lineNumber\", t.id").params(org(), ruleId));
    }

    private Map<String, Object> withTargets(Map<String, Object> r) {
        List<Map<String, Object>> t = targets((String) r.get("id"));
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> x : t) {
            sum = sum.add(SqlRows.dec(x, "weight"));
        }
        for (Map<String, Object> x : t) {
            x.put("sharePct", sum.signum() == 0 ? "0" : JsNumbers.dec(SqlRows.dec(x, "weight")
                    .multiply(BigDecimal.valueOf(100)).divide(sum, 4, RoundingMode.HALF_UP)));
        }
        r.put("targets", t);
        return r;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT r.*, c.code AS \"sourceCostCenterCode\" FROM"
                + " \"CostAllocationRule\" r LEFT JOIN \"CostCenter\" c ON c.id = r.\"sourceCostCenterId\""
                + " WHERE r.id = ? AND r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Allocation rule not found");
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        return v == null ? null : new ArrayList<>((List<String>) v);
    }

    private static String[] array(List<String> v) {
        return v == null || v.isEmpty() ? null : v.toArray(String[]::new);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
