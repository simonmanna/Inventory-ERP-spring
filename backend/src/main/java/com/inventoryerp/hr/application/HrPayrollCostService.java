package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.accounting.domain.CostCenter;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Cost;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Debit;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Rule;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Share;
import com.inventoryerp.hr.domain.HrCostAllocationRule;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrPayrollCostLine;
import com.inventoryerp.hr.domain.HrPayrollRun;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Payroll cost allocation (HR-5). Rules split an employee's or department's pay across cost centres; when a run is
 * approved the salary debit is split with them (with the employee's branch on each line) and what was charged is kept
 * per employee in {@code HrPayrollCostLine}. With no rule in the organisation the journal is the single Nest line and
 * only the cost lines are added, so the department report still works.
 */
@Service
public class HrPayrollCostService {

    private static final BigDecimal HUNDRED = new BigDecimal(100);
    private static final List<String> SCOPES = List.of("employee", "department");
    private static final String REPORT = """
            SELECT l."departmentId", d.name AS "departmentName", l."costCenterId", c.code AS "costCenterCode",
                   c.name AS "costCenterName", l."branchId", b.name AS "branchName",
                   count(DISTINCT l."employeeId") AS employees, sum(l.amount) AS amount
            FROM "HrPayrollCostLine" l
            LEFT JOIN "HrDepartment" d ON d.id = l."departmentId"
            LEFT JOIN "CostCenter" c ON c.id = l."costCenterId"
            LEFT JOIN "Branch" b ON b.id = l."branchId"
            """;
    private static final String REPORT_GROUP = " GROUP BY l.\"departmentId\", d.name, l.\"costCenterId\", c.code, c.name,"
            + " l.\"branchId\", b.name ORDER BY d.name NULLS LAST, c.code NULLS LAST, b.name NULLS LAST";

    private final HrDb hr;

    public HrPayrollCostService(HrDb hr) {
        this.hr = hr;
    }

    // ─── rules ───

    public Map<String, Object> listRules(Map<String, String> query) {
        HrDb.Where w = hr.where(HrCostAllocationRule.class).eqIf("scope", query.get("scope"))
                .eqIf("targetId", query.get("targetId"));
        List<Map<String, Object>> rows = w.list("scope ASC, \"targetId\" ASC, percent DESC");
        hr.oneAll(rows, "costCenter", CostCenter.class, "costCenterId", "id", "code", "name");
        return HrDb.rows(rows, rows.size());
    }

    /** Replace a target's split as one set: percentages must sum to 100; an empty list clears it. */
    public Map<String, Object> setRules(Map<String, Object> dto) {
        Object scope = dto.get("scope");
        Object targetId = dto.get("targetId");
        if (!SCOPES.contains(scope) || !truthy(targetId)) {
            throw ApiException.badRequest("scope (employee or department) and targetId are required");
        }
        Class<?> target = "employee".equals(scope) ? HrEmployee.class : HrDepartment.class;
        if (hr.byId(target, targetId) == null) {
            throw ApiException.notFound("Employee or department not found");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object o : PosSql.list(dto.get("lines"))) {
            lines.add(PosSql.map(o));
        }
        assertSplit(lines);
        return hr.inTx(() -> {
            hr.jdbc().sql("DELETE FROM \"HrCostAllocationRule\" WHERE \"organizationId\" = ? AND scope = ? AND"
                    + " \"targetId\" = ?").params(org(), scope, targetId).update();
            for (Map<String, Object> l : lines) {
                hr.insert(HrCostAllocationRule.class, Obj.of("organizationId", org(), "scope", scope, "targetId", targetId,
                        "costCenterId", l.get("costCenterId"), "percent", new BigDecimal(String.valueOf(l.get("percent"))),
                        "createdBy", user()));
            }
            hr.auditInTx(AuditInput.of("HrCostAllocationRule", String.valueOf(targetId), "update", null,
                    Obj.of("scope", scope, "lines", lines)));
            return listRules(Map.of("scope", String.valueOf(scope), "targetId", String.valueOf(targetId)));
        });
    }

    private void assertSplit(List<Map<String, Object>> lines) {
        if (lines.isEmpty()) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> l : lines) {
            if (!truthy(l.get("costCenterId")) || l.get("percent") == null) {
                throw ApiException.badRequest("Every line needs costCenterId and percent");
            }
            if (!seen.add(l.get("costCenterId"))) {
                throw ApiException.badRequest("A cost centre appears twice");
            }
            if (hr.byId(CostCenter.class, l.get("costCenterId")) == null) {
                throw ApiException.notFound("Cost centre not found");
            }
            BigDecimal percent;
            try {
                percent = new BigDecimal(String.valueOf(l.get("percent")));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Invalid percent: " + l.get("percent"));
            }
            if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("A percentage must be above 0 and at most 100");
            }
            sum = sum.add(percent);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("The percentages must add up to 100");
        }
    }

    // ─── reports ───

    public Map<String, Object> runReport(String runId) {
        if (hr.byId(HrPayrollRun.class, runId) == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        List<Map<String, Object>> rows = SqlRows.list(hr.jdbc().sql(REPORT + " WHERE l.\"organizationId\" = ? AND"
                + " l.\"runId\" = ?" + REPORT_GROUP).params(org(), runId));
        return HrDb.rows(rows, rows.size());
    }

    /** Approved runs whose period lies inside {@code from} … {@code to}, by department, cost centre and branch. */
    public Map<String, Object> periodReport(Map<String, String> query) {
        if (!truthy(query.get("from")) || !truthy(query.get("to"))) {
            throw ApiException.badRequest("from and to are required");
        }
        Instant from = HrDb.jsDate(query.get("from"));
        Instant to = HrDb.jsDate(query.get("to"));
        List<Map<String, Object>> rows = SqlRows.list(hr.jdbc().sql(REPORT
                + " JOIN \"HrPayrollRun\" r ON r.id = l.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\""
                + " WHERE l.\"organizationId\" = ? AND p.\"startDate\" >= ? AND p.\"endDate\" <= ?" + REPORT_GROUP)
                .params(org(), SqlRows.ts(from), SqlRows.ts(to)));
        return HrDb.rows(rows, rows.size());
    }

    // ------------------------------------------------------------------ payroll engine hooks (package-private)

    /**
     * Split {@code amount} (the run's salary debit) over the run's employees: {@code redirected} is what other modules'
     * inputs already took from an employee's pay. Writes the run's cost lines. Returns the journal debits, or an empty
     * list when the organisation has no rules, in which case the caller posts the single salary line.
     */
    List<Debit> allocate(String runId, List<Map<String, Object>> items, BigDecimal amount,
            Map<String, BigDecimal> redirected) {
        List<Map<String, Object>> rules = hr.where(HrCostAllocationRule.class).list("percent DESC, \"costCenterId\" ASC");
        Map<String, List<Rule>> byEmployee = new LinkedHashMap<>();
        Map<String, List<Rule>> byDepartment = new LinkedHashMap<>();
        for (Map<String, Object> r : rules) {
            ("employee".equals(r.get("scope")) ? byEmployee : byDepartment)
                    .computeIfAbsent((String) r.get("targetId"), k -> new ArrayList<>())
                    .add(new Rule((String) r.get("costCenterId"), SqlRows.dec(r, "percent")));
        }
        List<Object> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.get("employeeId")));
        Map<Object, Map<String, Object>> employees = new LinkedHashMap<>();
        for (Map<String, Object> e : hr.where(HrEmployee.class).in("id", ids).list(null)) {
            employees.put(e.get("id"), e);
        }
        List<Cost> costs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String employeeId = (String) item.get("employeeId");
            Map<String, Object> employee = employees.getOrDefault(employeeId, Map.of());
            BigDecimal cost = SqlRows.dec(item, "grossPay")
                    .subtract(redirected.getOrDefault(employeeId, BigDecimal.ZERO)).max(BigDecimal.ZERO);
            costs.add(new Cost(employeeId, (String) employee.get("departmentId"), (String) employee.get("branchId"), cost));
        }
        List<Share> shares = HrPayrollCostAllocator.split(costs, byEmployee, byDepartment, amount);
        for (Share s : shares) {
            hr.insert(HrPayrollCostLine.class, Obj.of("organizationId", org(), "runId", runId,
                    "employeeId", s.employeeId(), "departmentId", s.departmentId(), "branchId", s.branchId(),
                    "costCenterId", s.costCenterId(), "amount", s.amount()));
        }
        return rules.isEmpty() ? List.of() : HrPayrollCostAllocator.debits(shares);
    }

    /** The run was reversed: it charged nothing after all. */
    void clearRun(String runId) {
        hr.jdbc().sql("DELETE FROM \"HrPayrollCostLine\" WHERE \"runId\" = ? AND \"organizationId\" = ?")
                .params(runId, org()).update();
    }
}
