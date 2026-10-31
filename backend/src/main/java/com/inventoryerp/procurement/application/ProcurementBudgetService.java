package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement budgets and spend control (PROC-3). A budget caps what may be bought in a period, optionally for one
 * branch, department or product category, in base currency excluding tax. Consumption is read from the documents:
 * <b>committed</b> is the open purchase orders, <b>reserved</b> the submitted and approved requests no order was raised
 * from yet, so a request hands its reservation to its order. {@link #enforce} locks the budget rows, so two documents
 * cannot both take the last of a budget. These budgets are operational spend ceilings; the GL budgets of
 * {@code accounting} ({@code /budgets}) stay the plan-versus-actual view by account.
 */
@Service
public class ProcurementBudgetService {

    /** Create or update body. */
    public record BudgetInput(String name, String startDate, String endDate, String branchId, String departmentId,
            String categoryId, BigDecimal amount, BigDecimal warnPercent, String controlMode, String notes) {}

    /** A document line: the product (may be null) and its base-currency value excluding tax. */
    public record DocLine(String productId, BigDecimal amount) {}

    /**
     * The document to check. {@code kind} is {@value #REQUEST} or {@value #ORDER}, {@code id} may be null for a
     * preview, and {@code fromRequestId} is the request an order is raised from, whose reservation it replaces.
     */
    public record Doc(String kind, String id, String branchId, String departmentId, List<DocLine> lines,
            String fromRequestId) {}

    public static final String REQUEST = "purchase_request";
    public static final String ORDER = "purchase_order";

    private record Usage(BigDecimal committed, BigDecimal reserved) {
        BigDecimal total() {
            return committed.add(reserved);
        }
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProcurementBudgetService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ── Spend control ──────────────────────────────────────────────────────

    /**
     * Check a document against every active budget that covers it, inside the caller's transaction. Refuses with a
     * 409 {@code PROCUREMENT_BUDGET_EXCEEDED} on a blocking budget; records a warn-mode overrun in the audit trail.
     *
     * @return one row per budget covering the document, as {@link #preview}
     */
    @Transactional
    public List<Map<String, Object>> enforce(Doc doc) {
        List<Map<String, Object>> results = evaluate(doc, true);
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("exceeded"))) {
                continue;
            }
            String budgetId = (String) r.get("budgetId");
            Map<String, Object> detail = Obj.of("document", doc.kind(), "documentId", doc.id(), "projected",
                    r.get("projected"), "budget", r.get("amount"), "mode", r.get("controlMode"));
            if ("block".equals(r.get("controlMode"))) {
                audit.record(AuditInput.of("ProcurementBudget", budgetId, "reject", null, detail));
                throw ApiException.withBody(HttpStatus.CONFLICT, Obj.of("code", "PROCUREMENT_BUDGET_EXCEEDED", "message",
                        "This document takes budget \"" + r.get("name") + "\" over: " + plain(r.get("projected"))
                                + " of " + plain(r.get("amount")) + ".",
                        "budgetId", budgetId, "projected", r.get("projected"), "amount", r.get("amount"), "remaining",
                        r.get("remaining")));
            }
            audit.record(AuditInput.of("ProcurementBudget", budgetId, "update", null, detail));
        }
        return results;
    }

    /** What {@link #enforce} would answer, without locks or refusal; for forms. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> preview(Doc doc) {
        return evaluate(doc, false);
    }

    private List<Map<String, Object>> evaluate(Doc doc, boolean lock) {
        String today = LocalDate.now(BusinessZone.get()).toString();
        List<Map<String, Object>> budgets = SqlRows.list(jdbc.sql("SELECT * FROM \"ProcurementBudget\" WHERE"
                + " \"organizationId\" = ? AND status = 'active' AND ?::date BETWEEN \"startDate\" AND \"endDate\""
                + " ORDER BY id" + (lock ? " FOR UPDATE" : "")).params(org(), today));
        List<Map<String, Object>> out = new ArrayList<>();
        if (budgets.isEmpty()) {
            return out;
        }
        Map<String, Object> categories = categories(doc.lines());
        for (Map<String, Object> b : budgets) {
            if (!applies(b.get("branchId"), doc.branchId()) || !applies(b.get("departmentId"), doc.departmentId())) {
                continue;
            }
            BigDecimal value = BigDecimal.ZERO;
            for (DocLine l : doc.lines()) {
                if (applies(b.get("categoryId"), l.productId() == null ? null : categories.get(l.productId()))) {
                    value = value.add(l.amount());
                }
            }
            if (value.signum() == 0) {
                continue;
            }
            Usage usage = usage(b, ORDER.equals(doc.kind()) ? doc.id() : null,
                    REQUEST.equals(doc.kind()) ? doc.id() : doc.fromRequestId());
            BigDecimal amount = SqlRows.dec(b, "amount");
            BigDecimal projected = usage.total().add(value);
            boolean exceeded = projected.compareTo(amount) > 0;
            BigDecimal percent = percent(projected, amount);
            out.add(Obj.of("budgetId", b.get("id"), "name", b.get("name"), "controlMode", b.get("controlMode"),
                    "amount", amount, "committed", usage.committed(), "reserved", usage.reserved(), "document", value,
                    "projected", projected, "remaining", amount.subtract(usage.total()), "percent", percent,
                    "exceeded", exceeded,
                    "warning", !exceeded && percent.compareTo(SqlRows.dec(b, "warnPercent")) >= 0));
        }
        return out;
    }

    /** A budget's scope column holds when it is empty or equal to the document's value. */
    private static boolean applies(Object scope, Object value) {
        return scope == null || scope.equals(value);
    }

    private Map<String, Object> categories(List<DocLine> lines) {
        List<String> ids = lines.stream().map(DocLine::productId).filter(Objects::nonNull).distinct().toList();
        Map<String, Object> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"categoryId\" FROM \"Product\" WHERE"
                + " \"organizationId\" = :org AND id IN (:ids)").param("org", org()).param("ids", ids))) {
            out.put((String) r.get("id"), r.get("categoryId"));
        }
        return out;
    }

    /** Committed and reserved value in the budget's scope and period, leaving out the document being checked. */
    private Usage usage(Map<String, Object> b, String excludeOrderId, String excludeRequestId) {
        String start = String.valueOf(b.get("startDate"));
        String end = String.valueOf(b.get("endDate"));

        StringBuilder orders = new StringBuilder("SELECT COALESCE(SUM(l.subtotal * o.\"exchangeRate\"), 0) AS v FROM"
                + " \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" LEFT JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL"
                + " AND o.status::text <> 'cancelled' AND o.\"orderDate\"::date BETWEEN ?::date AND ?::date");
        List<Object> orderParams = new ArrayList<>(List.of(org(), start, end));
        scope(orders, orderParams, b, "o");
        if (excludeOrderId != null) {
            orders.append(" AND o.id <> ?");
            orderParams.add(excludeOrderId);
        }

        StringBuilder requests = new StringBuilder("SELECT COALESCE(SUM(l.quantity * COALESCE(l.\"estimatedUnitPrice\","
                + " 0)), 0) AS v FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" r ON r.id ="
                + " l.\"purchaseRequestId\" LEFT JOIN \"Product\" p ON p.id = l.\"productId\" WHERE"
                + " r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND r.status::text IN ('submitted', 'approved')"
                + " AND r.\"createdAt\"::date BETWEEN ?::date AND ?::date AND NOT EXISTS (SELECT 1 FROM"
                + " \"PurchaseOrder\" x WHERE x.\"requestId\" = r.id AND x.\"deletedAt\" IS NULL AND x.status::text <>"
                + " 'cancelled')");
        List<Object> requestParams = new ArrayList<>(List.of(org(), start, end));
        scope(requests, requestParams, b, "r");
        if (excludeRequestId != null) {
            requests.append(" AND r.id <> ?");
            requestParams.add(excludeRequestId);
        }
        return new Usage(jdbc.sql(orders.toString()).params(orderParams).query(BigDecimal.class).single(),
                jdbc.sql(requests.toString()).params(requestParams).query(BigDecimal.class).single());
    }

    private static void scope(StringBuilder sql, List<Object> params, Map<String, Object> b, String alias) {
        if (b.get("branchId") != null) {
            sql.append(" AND ").append(alias).append(".\"branchId\" = ?");
            params.add(b.get("branchId"));
        }
        if (b.get("departmentId") != null) {
            sql.append(" AND ").append(alias).append(".\"departmentId\" = ?");
            params.add(b.get("departmentId"));
        }
        if (b.get("categoryId") != null) {
            sql.append(" AND p.\"categoryId\" = ?");
            params.add(b.get("categoryId"));
        }
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return part.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("100");
        }
        return part.multiply(new BigDecimal("100")).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static String plain(Object amount) {
        return ((BigDecimal) amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ── Budgets ────────────────────────────────────────────────────────────

    /** {@code {data, meta}} newest first, each with its consumption. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(int page, int pageSize, String status, String branchId) {
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (branchId != null && !branchId.isBlank()) {
            where.append(" AND \"branchId\" = ?");
            params.add(branchId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"ProcurementBudget\"" + where
                + " ORDER BY \"createdAt\" DESC OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize)
                .params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"ProcurementBudget\"" + where).params(params).query(Long.class)
                .single();
        return PageResult.of(rows.stream().map(this::withUsage).toList(), page, pageSize, total);
    }

    /** One budget with its consumption and the documents behind it. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> b = withUsage(require(id));
        List<Object> orderParams = new ArrayList<>(List.of(org(), b.get("startDate").toString(),
                b.get("endDate").toString()));
        StringBuilder orders = new StringBuilder("SELECT o.id, o.\"orderNumber\", o.status::text AS status,"
                + " SUM(l.subtotal * o.\"exchangeRate\") AS value FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o"
                + " ON o.id = l.\"purchaseOrderId\" LEFT JOIN \"Product\" p ON p.id = l.\"productId\" WHERE"
                + " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status::text <> 'cancelled' AND"
                + " o.\"orderDate\"::date BETWEEN ?::date AND ?::date");
        scope(orders, orderParams, b, "o");
        orders.append(" GROUP BY o.id, o.\"orderNumber\", o.status ORDER BY o.\"orderNumber\" DESC LIMIT 100");
        b.put("orders", SqlRows.list(jdbc.sql(orders.toString()).params(orderParams)));
        return b;
    }

    private Map<String, Object> withUsage(Map<String, Object> b) {
        Usage usage = usage(b, null, null);
        BigDecimal amount = SqlRows.dec(b, "amount");
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("committed", usage.committed());
        out.put("reserved", usage.reserved());
        out.put("remaining", amount.subtract(usage.total()));
        out.put("percent", percent(usage.total(), amount));
        return out;
    }

    @Transactional
    public Map<String, Object> create(BudgetInput in) {
        validate(in);
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ProcurementBudget\" (id, \"organizationId\", name,"
                        + " \"startDate\", \"endDate\", \"branchId\", \"departmentId\", \"categoryId\", amount,"
                        + " \"warnPercent\", \"controlMode\", notes, \"createdBy\", \"updatedBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?::date, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ") RETURNING *")
                .params(id, org(), in.name().trim(), in.startDate(), in.endDate(), blank(in.branchId()),
                        blank(in.departmentId()), blank(in.categoryId()), in.amount(), warn(in), mode(in), in.notes(),
                        user(), user()));
        audit.recordInTx(AuditInput.of("ProcurementBudget", id, "create", null, row));
        return row;
    }

    /** Edit a draft. */
    @Transactional
    public Map<String, Object> update(String id, BudgetInput in) {
        validate(in);
        Map<String, Object> before = require(id);
        if (!"draft".equals(before.get("status"))) {
            throw ApiException.badRequest("Only a draft budget can be edited; revise the amount of an active one");
        }
        Map<String, Object> row = SqlRows.one(jdbc.sql("UPDATE \"ProcurementBudget\" SET name = ?, \"startDate\" ="
                        + " ?::date, \"endDate\" = ?::date, \"branchId\" = ?, \"departmentId\" = ?, \"categoryId\" = ?,"
                        + " amount = ?, \"warnPercent\" = ?, \"controlMode\" = ?, notes = ?, \"updatedBy\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? RETURNING *")
                .params(in.name().trim(), in.startDate(), in.endDate(), blank(in.branchId()), blank(in.departmentId()),
                        blank(in.categoryId()), in.amount(), warn(in), mode(in), in.notes(), user(), id, org()));
        audit.recordInTx(AuditInput.of("ProcurementBudget", id, "update", before, row));
        return row;
    }

    /** draft → active. */
    @Transactional
    public Map<String, Object> activate(String id) {
        return transition(id, "draft", "active", "approve");
    }

    /** active → closed. */
    @Transactional
    public Map<String, Object> close(String id) {
        return transition(id, "active", "closed", "update");
    }

    private Map<String, Object> transition(String id, String from, String to, String action) {
        Map<String, Object> before = require(id);
        if (!from.equals(before.get("status"))) {
            throw ApiException.badRequest("Budget is " + before.get("status") + ", expected " + from);
        }
        Map<String, Object> row = SqlRows.one(jdbc.sql("UPDATE \"ProcurementBudget\" SET status = ?, \"updatedBy\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? RETURNING *")
                .params(to, user(), id, org()));
        audit.recordInTx(AuditInput.of("ProcurementBudget", id, action, before, row));
        return row;
    }

    /** Change the amount of an active budget; a reason is required and kept in the audit trail. */
    @Transactional
    public Map<String, Object> revise(String id, BigDecimal amount, String reason) {
        Map<String, Object> before = lock(id);
        if (!"active".equals(before.get("status"))) {
            throw ApiException.badRequest("Only an active budget can be revised");
        }
        Map<String, Object> row = SqlRows.one(jdbc.sql("UPDATE \"ProcurementBudget\" SET amount = ?, \"updatedBy\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? RETURNING *")
                .params(amount, user(), id, org()));
        audit.recordInTx(AuditInput.of("ProcurementBudget", id, "update", Obj.of("amount", before.get("amount")),
                Obj.of("amount", amount, "reason", reason)));
        return row;
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> before = require(id);
        if (!"draft".equals(before.get("status"))) {
            throw ApiException.badRequest("Only a draft budget can be deleted");
        }
        jdbc.sql("DELETE FROM \"ProcurementBudget\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("ProcurementBudget", id, "delete", before, null));
    }

    private Map<String, Object> require(String id) {
        return found(SqlRows.one(jdbc.sql("SELECT * FROM \"ProcurementBudget\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org())));
    }

    private Map<String, Object> lock(String id) {
        return found(SqlRows.one(jdbc.sql("SELECT * FROM \"ProcurementBudget\" WHERE id = ? AND \"organizationId\" = ?"
                + " FOR UPDATE").params(id, org())));
    }

    private static Map<String, Object> found(Map<String, Object> row) {
        if (row == null) {
            throw ApiException.notFound("Procurement budget not found");
        }
        return row;
    }

    private static void validate(BudgetInput in) {
        if (LocalDate.parse(in.endDate()).isBefore(LocalDate.parse(in.startDate()))) {
            throw ApiException.badRequest("The end date is before the start date");
        }
    }

    private static BigDecimal warn(BudgetInput in) {
        return in.warnPercent() == null ? new BigDecimal("90") : in.warnPercent();
    }

    private static String mode(BudgetInput in) {
        return in.controlMode() == null ? "block" : in.controlMode();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
