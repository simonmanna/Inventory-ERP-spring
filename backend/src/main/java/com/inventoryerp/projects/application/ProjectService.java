package com.inventoryerp.projects.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project master data (Cost Management CM-8a, ADR-S038): projects with customer, contract, branch and cost centre;
 * sites with a delivery location; a hierarchical WBS with billable / cost flags; versioned budgets (original, then
 * revised; approving one supersedes the previous) by WBS element and cost element; cost-to-complete forecasts.
 */
@Service
public class ProjectService {

    public static final Set<String> COST_ELEMENTS = Set.of("material", "labour", "equipment", "subcontract", "other",
            "overhead");
    static final Set<String> STATUSES = Set.of("draft", "active", "on_hold", "completed", "closed", "cancelled");
    static final Set<String> FULFILMENT = Set.of("incremental", "directly_related", "excluded");

    public record ProjectInput(String code, String name, String customerId, String contractRef,
            BigDecimal contractValue, String branchId, String costCenterId, String startDate, String endDate,
            String notes) {}

    public record SiteInput(String code, String name, String address, String deliveryLocationId) {}

    public record WbsInput(String code, String name, String parentId, Boolean isBillable, Boolean isCostable) {}

    public record BudgetLineInput(String wbsId, String costElement, BigDecimal amount, BigDecimal revenue) {}

    public record ForecastLineInput(String wbsId, String costElement, BigDecimal costToComplete,
            String fulfilmentClass) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProjectService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    // ---- projects ---------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> create(ProjectInput in) {
        String code = required(in.code(), "code").trim();
        String name = required(in.name(), "name").trim();
        if (jdbc.sql("SELECT count(*) FROM \"Project\" WHERE \"organizationId\" = ? AND code = ?").params(org(), code)
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("A project with code " + code + " already exists");
        }
        checkRefs(in);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Project\" (id, \"organizationId\", code, name, \"customerId\", \"contractRef\","
                        + " \"contractValue\", \"branchId\", \"costCenterId\", \"startDate\", \"endDate\", notes,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?)")
                .params(id, org(), code, name, blank(in.customerId()), in.contractRef(), money(in.contractValue()),
                        blank(in.branchId()), blank(in.costCenterId()), day(in.startDate()), day(in.endDate()),
                        in.notes(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("Project", id, "create", null, Obj.of("code", code, "name", name)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, ProjectInput in) {
        Map<String, Object> p = project(id, true);
        checkRefs(in);
        jdbc.sql("UPDATE \"Project\" SET name = coalesce(?, name), \"customerId\" = coalesce(?, \"customerId\"),"
                        + " \"contractRef\" = coalesce(?, \"contractRef\"), \"contractValue\" = coalesce(?,"
                        + " \"contractValue\"), \"branchId\" = coalesce(?, \"branchId\"), \"costCenterId\" = coalesce(?,"
                        + " \"costCenterId\"), \"startDate\" = coalesce(?::date, \"startDate\"), \"endDate\" ="
                        + " coalesce(?::date, \"endDate\"), notes = coalesce(?, notes), \"needsReview\" = false,"
                        + " \"updatedAt\" = now() WHERE id = ?")
                .params(blank(in.name()), blank(in.customerId()), in.contractRef(), in.contractValue() == null ? null
                                : money(in.contractValue()), blank(in.branchId()), blank(in.costCenterId()),
                        day(in.startDate()), day(in.endDate()), in.notes(), id)
                .update();
        audit.recordInTx(AuditInput.of("Project", id, "update", p, null));
        return get(id);
    }

    @Transactional
    public Map<String, Object> setStatus(String id, String status) {
        Map<String, Object> p = project(id, true);
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of the following values: draft, active, on_hold,"
                    + " completed, closed, cancelled");
        }
        String from = (String) p.get("status");
        if (Set.of("closed", "cancelled").contains(from)) {
            throw ApiException.badRequest("A " + from + " project cannot change status");
        }
        jdbc.sql("UPDATE \"Project\" SET status = ?, \"updatedAt\" = now() WHERE id = ?").params(status, id).update();
        audit.recordInTx(AuditInput.of("Project", id, "update", Obj.of("status", from), Obj.of("status", status)));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String search) {
        return SqlRows.list(jdbc.sql("SELECT p.*, pa.name AS \"customerName\" FROM \"Project\" p LEFT JOIN \"Partner\" pa"
                + " ON pa.id = p.\"customerId\" WHERE p.\"organizationId\" = ? AND (?::text IS NULL OR p.status = ?) AND"
                + " (?::text IS NULL OR p.code ILIKE '%' || ? || '%' OR p.name ILIKE '%' || ? || '%') ORDER BY p.code")
                .params(org(), blank(status), blank(status), blank(search), blank(search), blank(search)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = project(id, false);
        p.put("sites", SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectSite\" WHERE \"projectId\" = ? ORDER BY code")
                .param(id)));
        p.put("wbs", SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectWbsElement\" WHERE \"projectId\" = ? ORDER BY code")
                .param(id)));
        return p;
    }

    /** The project row of this organisation (404 otherwise); {@code lock} takes a row lock. */
    public Map<String, Object> project(String id, boolean lock) {
        Map<String, Object> p = id == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"Project\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Project not found");
        }
        return p;
    }

    // ---- sites and WBS ------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> addSite(String projectId, SiteInput in) {
        project(projectId, false);
        String code = required(in.code(), "code").trim();
        if (blank(in.deliveryLocationId()) != null && jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(in.deliveryLocationId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Location not found");
        }
        if (jdbc.sql("SELECT count(*) FROM \"ProjectSite\" WHERE \"projectId\" = ? AND code = ?").params(projectId,
                code).query(Long.class).single() > 0) {
            throw ApiException.conflict("Site " + code + " already exists on this project");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProjectSite\" (id, \"organizationId\", \"projectId\", code, name, address,"
                + " \"deliveryLocationId\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), projectId, code,
                required(in.name(), "name").trim(), in.address(), blank(in.deliveryLocationId())).update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectSite\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> addWbs(String projectId, WbsInput in) {
        project(projectId, false);
        String code = required(in.code(), "code").trim();
        if (blank(in.parentId()) != null) {
            wbs(projectId, in.parentId());
        }
        if (jdbc.sql("SELECT count(*) FROM \"ProjectWbsElement\" WHERE \"projectId\" = ? AND code = ?").params(
                projectId, code).query(Long.class).single() > 0) {
            throw ApiException.conflict("WBS element " + code + " already exists on this project");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProjectWbsElement\" (id, \"organizationId\", \"projectId\", \"parentId\", code, name,"
                        + " \"isBillable\", \"isCostable\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, blank(in.parentId()), code, required(in.name(), "name").trim(),
                        in.isBillable() == null || in.isBillable(), in.isCostable() == null || in.isCostable())
                .update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectWbsElement\" WHERE id = ?").param(id));
    }

    /** Change a site: code (unique on the project), name, address, delivery location; null fields stay. */
    @Transactional
    public Map<String, Object> updateSite(String siteId, SiteInput in) {
        Map<String, Object> s = site(siteId);
        String code = blank(in.code()) == null ? (String) s.get("code") : in.code().trim();
        if (!code.equals(s.get("code")) && jdbc.sql("SELECT count(*) FROM \"ProjectSite\" WHERE \"projectId\" = ? AND"
                + " code = ? AND id <> ?").params(s.get("projectId"), code, siteId).query(Long.class).single() > 0) {
            throw ApiException.conflict("Site " + code + " already exists on this project");
        }
        if (blank(in.deliveryLocationId()) != null && jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(in.deliveryLocationId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Location not found");
        }
        // "" clears the optional address / delivery location; null keeps them
        jdbc.sql("UPDATE \"ProjectSite\" SET code = ?, name = coalesce(?, name), address = CASE WHEN ?::text IS NULL"
                        + " THEN address ELSE nullif(?, '') END, \"deliveryLocationId\" = CASE WHEN ?::text IS NULL THEN"
                        + " \"deliveryLocationId\" ELSE nullif(?, '') END WHERE id = ?")
                .params(code, blank(in.name()) == null ? null : in.name().trim(), in.address(), in.address(),
                        in.deliveryLocationId(), in.deliveryLocationId(), siteId)
                .update();
        audit.recordInTx(AuditInput.of("ProjectSite", siteId, "update", s, null));
        return site(siteId);
    }

    /**
     * Delete a site nothing was costed to. Tasks pointing at it lose the link; a site with project cost lines stays
     * (the sub-ledger and its journal dimensions keep referring to it).
     */
    @Transactional
    public Map<String, Object> deleteSite(String siteId) {
        Map<String, Object> s = site(siteId);
        if (jdbc.sql("SELECT count(*) FROM \"ProjectCostLine\" WHERE \"siteId\" = ?").param(siteId).query(Long.class)
                .single() > 0) {
            throw ApiException.badRequest("Site " + s.get("code") + " has project cost lines and cannot be deleted");
        }
        jdbc.sql("UPDATE \"ProjectTask\" SET \"siteId\" = NULL WHERE \"siteId\" = ?").param(siteId).update();
        jdbc.sql("DELETE FROM \"ProjectSite\" WHERE id = ?").param(siteId).update();
        audit.recordInTx(AuditInput.of("ProjectSite", siteId, "delete", s, null));
        return Obj.of("id", siteId, "deleted", true);
    }

    /** A site of this organisation (404 otherwise). */
    Map<String, Object> site(String siteId) {
        Map<String, Object> s = siteId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectSite\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(siteId, org()));
        if (s == null) {
            throw ApiException.notFound("Site not found");
        }
        return s;
    }

    /**
     * Change a WBS element: code (unique on the project), name, parent (same project, never itself or one of its own
     * descendants; "" moves it to the top level), billable / costable flags. Null fields stay.
     */
    @Transactional
    public Map<String, Object> updateWbs(String wbsId, WbsInput in) {
        Map<String, Object> w = wbsElement(wbsId);
        String projectId = (String) w.get("projectId");
        String code = blank(in.code()) == null ? (String) w.get("code") : in.code().trim();
        if (!code.equals(w.get("code")) && jdbc.sql("SELECT count(*) FROM \"ProjectWbsElement\" WHERE \"projectId\" = ?"
                + " AND code = ? AND id <> ?").params(projectId, code, wbsId).query(Long.class).single() > 0) {
            throw ApiException.conflict("WBS element " + code + " already exists on this project");
        }
        String parentId = in.parentId() == null ? (String) w.get("parentId") : blank(in.parentId());
        if (parentId != null && !parentId.equals(w.get("parentId"))) {
            wbs(projectId, parentId);
            boolean cycle = jdbc.sql("WITH RECURSIVE sub AS (SELECT id FROM \"ProjectWbsElement\" WHERE id = ? UNION"
                    + " SELECT e.id FROM \"ProjectWbsElement\" e JOIN sub ON e.\"parentId\" = sub.id) SELECT count(*)"
                    + " FROM sub WHERE id = ?").params(wbsId, parentId).query(Long.class).single() > 0;
            if (cycle) {
                throw ApiException.badRequest("A WBS element cannot be placed under itself or one of its children");
            }
        }
        jdbc.sql("UPDATE \"ProjectWbsElement\" SET code = ?, name = coalesce(?, name), \"parentId\" = ?, \"isBillable\" ="
                        + " coalesce(?, \"isBillable\"), \"isCostable\" = coalesce(?, \"isCostable\") WHERE id = ?")
                .params(code, blank(in.name()) == null ? null : in.name().trim(), parentId, in.isBillable(),
                        in.isCostable(), wbsId)
                .update();
        audit.recordInTx(AuditInput.of("ProjectWbsElement", wbsId, "update", w, null));
        return wbsElement(wbsId);
    }

    /**
     * Delete a WBS element that nothing refers to: no child elements, no cost lines, budget or forecast lines,
     * contract variations or subcontracts. Tasks pointing at it lose the link.
     */
    @Transactional
    public Map<String, Object> deleteWbs(String wbsId) {
        Map<String, Object> w = wbsElement(wbsId);
        String code = (String) w.get("code");
        if (jdbc.sql("SELECT count(*) FROM \"ProjectWbsElement\" WHERE \"parentId\" = ?").param(wbsId).query(Long.class)
                .single() > 0) {
            throw ApiException.badRequest("Move or delete the child elements of WBS element " + code + " first");
        }
        long used = jdbc.sql("SELECT (SELECT count(*) FROM \"ProjectCostLine\" WHERE \"wbsId\" = :w)"
                + " + (SELECT count(*) FROM \"ProjectBudgetLine\" WHERE \"wbsId\" = :w)"
                + " + (SELECT count(*) FROM \"ProjectForecastLine\" WHERE \"wbsId\" = :w)"
                + " + (SELECT count(*) FROM \"ContractVariation\" WHERE \"wbsId\" = :w)"
                + " + (SELECT count(*) FROM \"Subcontract\" WHERE \"wbsId\" = :w)").param("w", wbsId)
                .query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("WBS element " + code + " is used by cost, budget or forecast lines,"
                    + " variations or subcontracts and cannot be deleted");
        }
        jdbc.sql("UPDATE \"ProjectTask\" SET \"wbsId\" = NULL WHERE \"wbsId\" = ?").param(wbsId).update();
        jdbc.sql("DELETE FROM \"ProjectWbsElement\" WHERE id = ?").param(wbsId).update();
        audit.recordInTx(AuditInput.of("ProjectWbsElement", wbsId, "delete", w, null));
        return Obj.of("id", wbsId, "deleted", true);
    }

    /** A WBS element of this organisation by id alone (404 otherwise). */
    Map<String, Object> wbsElement(String wbsId) {
        Map<String, Object> w = wbsId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectWbsElement\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(wbsId, org()));
        if (w == null) {
            throw ApiException.notFound("WBS element not found");
        }
        return w;
    }

    /** A WBS element of the project (404 otherwise). */
    public Map<String, Object> wbs(String projectId, String wbsId) {
        Map<String, Object> w = wbsId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectWbsElement\" WHERE"
                + " id = ? AND \"projectId\" = ? AND \"organizationId\" = ?").params(wbsId, projectId, org()));
        if (w == null) {
            throw ApiException.notFound("WBS element not found on this project");
        }
        return w;
    }

    // ---- budgets ------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createBudget(String projectId, List<BudgetLineInput> lines, String notes) {
        project(projectId, true);
        if (lines == null || lines.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty");
        }
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM \"ProjectBudget\" WHERE \"projectId\" = ?")
                .param(projectId).query(Integer.class).single();
        boolean anyApproved = jdbc.sql("SELECT count(*) FROM \"ProjectBudget\" WHERE \"projectId\" = ? AND status IN"
                + " ('approved', 'superseded')").param(projectId).query(Long.class).single() > 0;
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProjectBudget\" (id, \"organizationId\", \"projectId\", version, kind, notes,"
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), projectId, version, anyApproved
                ? "revised" : "original", notes, TenantContext.userId()).update();
        BigDecimal total = writeLines(projectId, id, lines);
        audit.recordInTx(AuditInput.of("ProjectBudget", id, "create", null, Obj.of("version", version, "total",
                total)));
        return budget(id);
    }

    /** Insert the budget lines (validated) and store the total. */
    private BigDecimal writeLines(String projectId, String budgetId, List<BudgetLineInput> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (BudgetLineInput l : lines) {
            wbs(projectId, l.wbsId());
            element(l.costElement());
            BigDecimal amount = money(l.amount());
            if (amount.signum() < 0) {
                throw ApiException.badRequest("amount must not be negative");
            }
            BigDecimal revenue = l.revenue() == null ? BigDecimal.ZERO : money(l.revenue());
            if (revenue.signum() < 0) {
                throw ApiException.badRequest("revenue must not be negative");
            }
            jdbc.sql("INSERT INTO \"ProjectBudgetLine\" (id, \"organizationId\", \"budgetId\", \"wbsId\","
                    + " \"costElement\", amount, revenue) VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID()
                    .toString(), org(), budgetId, l.wbsId(), l.costElement(), amount, revenue).update();
            total = total.add(amount);
        }
        jdbc.sql("UPDATE \"ProjectBudget\" SET total = ? WHERE id = ?").params(total, budgetId).update();
        return total;
    }

    /**
     * Change a draft budget: {@code lines} (when given) replace every line, {@code notes} (when given) replace the
     * notes. Approved and superseded versions are frozen.
     */
    @Transactional
    public Map<String, Object> updateBudget(String budgetId, List<BudgetLineInput> lines, String notes) {
        Map<String, Object> b = draftBudget(budgetId, "Only a draft budget can be changed");
        String projectId = (String) b.get("projectId");
        if (lines != null) {
            if (lines.isEmpty()) {
                throw ApiException.badRequest("lines should not be empty");
            }
            jdbc.sql("DELETE FROM \"ProjectBudgetLine\" WHERE \"budgetId\" = ?").param(budgetId).update();
            writeLines(projectId, budgetId, lines);
        }
        if (notes != null) {
            jdbc.sql("UPDATE \"ProjectBudget\" SET notes = nullif(?, '') WHERE id = ?").params(notes, budgetId).update();
        }
        Map<String, Object> after = budget(budgetId);
        audit.recordInTx(AuditInput.of("ProjectBudget", budgetId, "update", Obj.of("total", b.get("total")),
                Obj.of("total", after.get("total"))));
        return after;
    }

    /** Delete a draft budget version (its lines go with it). */
    @Transactional
    public Map<String, Object> deleteBudget(String budgetId) {
        Map<String, Object> b = draftBudget(budgetId, "Only a draft budget can be deleted");
        jdbc.sql("DELETE FROM \"ProjectBudget\" WHERE id = ?").param(budgetId).update();
        audit.recordInTx(AuditInput.of("ProjectBudget", budgetId, "delete", Obj.of("version", b.get("version"),
                "total", b.get("total")), null));
        return Obj.of("id", budgetId, "deleted", true);
    }

    /** The budget, locked with its project, when it is still a draft. */
    private Map<String, Object> draftBudget(String budgetId, String message) {
        Map<String, Object> b = budget(budgetId);
        project((String) b.get("projectId"), true);
        String status = jdbc.sql("SELECT status FROM \"ProjectBudget\" WHERE id = ? FOR UPDATE").param(budgetId)
                .query(String.class).single();
        if (!"draft".equals(status)) {
            throw ApiException.badRequest(message);
        }
        return b;
    }

    @Transactional
    public Map<String, Object> approveBudget(String budgetId) {
        Map<String, Object> b = budget(budgetId);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Only a draft budget can be approved");
        }
        jdbc.sql("UPDATE \"ProjectBudget\" SET status = 'superseded' WHERE \"projectId\" = ? AND status = 'approved'")
                .param(b.get("projectId")).update();
        jdbc.sql("UPDATE \"ProjectBudget\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = now() WHERE"
                + " id = ?").params(TenantContext.userId(), budgetId).update();
        audit.recordInTx(AuditInput.of("ProjectBudget", budgetId, "approve", null, null));
        return budget(budgetId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> budgets(String projectId) {
        project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectBudget\" WHERE \"projectId\" = ? ORDER BY version")
                .param(projectId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> budget(String budgetId) {
        Map<String, Object> b = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectBudget\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(budgetId, org()));
        if (b == null) {
            throw ApiException.notFound("Budget not found");
        }
        b.put("lines", SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectBudgetLine\" WHERE \"budgetId\" = ?")
                .param(budgetId)));
        return b;
    }

    /**
     * Apply an approved revised budget to the cost side of a contract variation (CM-8b): a new approved version is
     * created from the current one plus {@code delta} on the WBS element / cost element.
     */
    @Transactional
    public String reviseBudget(String projectId, String wbsId, String costElement, BigDecimal delta,
            BigDecimal revenueDelta, String note) {
        List<BudgetLineInput> lines = new ArrayList<>();
        String current = jdbc.sql("SELECT id FROM \"ProjectBudget\" WHERE \"projectId\" = ? AND status = 'approved'")
                .param(projectId).query(String.class).optional().orElse(null);
        boolean applied = false;
        if (current != null) {
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectBudgetLine\" WHERE"
                    + " \"budgetId\" = ?").param(current))) {
                BigDecimal amount = SqlRows.dec(l, "amount");
                BigDecimal revenue = SqlRows.dec(l, "revenue");
                if (!applied && wbsId.equals(l.get("wbsId")) && costElement.equals(l.get("costElement"))) {
                    amount = amount.add(delta).max(BigDecimal.ZERO);
                    revenue = revenue.add(revenueDelta).max(BigDecimal.ZERO);
                    applied = true;
                }
                lines.add(new BudgetLineInput((String) l.get("wbsId"), (String) l.get("costElement"), amount,
                        revenue));
            }
        }
        if (!applied) {
            lines.add(new BudgetLineInput(wbsId, costElement, delta.max(BigDecimal.ZERO),
                    revenueDelta.max(BigDecimal.ZERO)));
        }
        String id = (String) createBudget(projectId, lines, note).get("id");
        approveBudget(id);
        return id;
    }

    // ---- forecast -----------------------------------------------------------------------------------------------------

    @Transactional
    public List<Map<String, Object>> setForecast(String projectId, String asOf, List<ForecastLineInput> lines) {
        project(projectId, true);
        if (lines == null || lines.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty");
        }
        String d = day(asOf) == null ? java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString() : day(asOf);
        for (ForecastLineInput l : lines) {
            wbs(projectId, l.wbsId());
            element(l.costElement());
            BigDecimal etc = money(l.costToComplete());
            if (etc.signum() < 0) {
                throw ApiException.badRequest("costToComplete must not be negative");
            }
            String cls = l.fulfilmentClass() == null ? "incremental" : l.fulfilmentClass();
            if (!FULFILMENT.contains(cls)) {
                throw ApiException.badRequest("fulfilmentClass must be one of the following values: incremental,"
                        + " directly_related, excluded");
            }
            jdbc.sql("INSERT INTO \"ProjectForecastLine\" (id, \"organizationId\", \"projectId\", \"wbsId\","
                            + " \"costElement\", \"costToComplete\", \"fulfilmentClass\", \"asOf\", \"createdBy\") VALUES"
                            + " (?, ?, ?, ?, ?, ?, ?, ?::date, ?)")
                    .params(UUID.randomUUID().toString(), org(), projectId, l.wbsId(), l.costElement(), etc, cls, d,
                            TenantContext.userId())
                    .update();
        }
        return forecast(projectId);
    }

    /** The cost-to-complete in force: the latest line per WBS element and cost element. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forecast(String projectId) {
        return SqlRows.list(jdbc.sql("SELECT DISTINCT ON (\"wbsId\", \"costElement\") * FROM \"ProjectForecastLine\""
                + " WHERE \"projectId\" = ? AND \"organizationId\" = ? ORDER BY \"wbsId\", \"costElement\", \"createdAt\""
                + " DESC, id DESC").params(projectId, org()));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private void checkRefs(ProjectInput in) {
        if (blank(in.customerId()) != null && jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(in.customerId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Customer not found");
        }
        if (blank(in.branchId()) != null && jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(in.branchId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Branch not found");
        }
        if (in.contractValue() != null && in.contractValue().signum() < 0) {
            throw ApiException.badRequest("contractValue must not be negative");
        }
    }

    static void element(String e) {
        if (e == null || !COST_ELEMENTS.contains(e)) {
            throw ApiException.badRequest("costElement must be one of the following values: material, labour,"
                    + " equipment, subcontract, other, overhead");
        }
    }

    static String required(String v, String field) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        return v;
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    static String day(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Dates must be valid ISO 8601 date strings");
        }
    }

    static Date sqlDay(String v) {
        return v == null ? null : Date.valueOf(v);
    }
}
