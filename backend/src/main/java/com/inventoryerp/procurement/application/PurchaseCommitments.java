package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.CommitmentService;
import com.inventoryerp.accounting.application.CommitmentService.Line;
import com.inventoryerp.accounting.application.CommitmentService.Shortfall;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase-order side of budget control (ADR-PROCUREMENT-001 wave 5). {@link #sync} re-states an order's commitments
 * from its current state (open quantity × unit price × functional rate, per line); it is called after every change that
 * moves an order's open value. {@link #checkBudget} applies the {@code budget.control} policy before an order is raised.
 *
 * <p>Budget account per line: the stock account a receipt would debit for stocked products, otherwise the category's
 * expense account (or {@code default_expense}). Cost centre and project come from the request line the order line was
 * sourced from.
 */
@Component
public class PurchaseCommitments {

    static final Set<String> OPEN_STATUSES = Set.of("active", "approved", "sent", "acknowledged", "partially_received");

    /** A not-yet-saved order line for {@link #checkBudget}. */
    public record Draft(String key, String productId, BigDecimal quantity, BigDecimal unitPrice, String requestLineId) {}

    private final JdbcClient jdbc;
    private final CommitmentService commitments;
    private final StockPostingService stockPosting;
    private final AccountDeterminationService determination;
    private final ProcurementPolicyService policies;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    public PurchaseCommitments(JdbcClient jdbc, CommitmentService commitments, StockPostingService stockPosting,
            AccountDeterminationService determination, ProcurementPolicyService policies, AuditService audit,
            EventBus events, PlatformTransactionManager txm) {
        this.tx = new TransactionTemplate(txm);
        this.jdbc = jdbc;
        this.commitments = commitments;
        this.stockPosting = stockPosting;
        this.determination = determination;
        this.policies = policies;
        this.audit = audit;
        this.events = events;
    }

    /** Re-state the commitments of one order (joins the caller's transaction, or opens one). */
    public void sync(String purchaseOrderId) {
        tx.executeWithoutResult(st -> doSync(purchaseOrderId));
    }

    private void doSync(String purchaseOrderId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", status::text AS status, \"branchId\","
                + " \"currencyCode\", \"exchangeRate\", to_char(\"orderDate\", 'YYYY-MM') AS period, \"deletedAt\""
                + " FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?").params(purchaseOrderId, org()));
        if (po == null) {
            return;
        }
        boolean open = po.get("deletedAt") == null && OPEN_STATUSES.contains(String.valueOf(po.get("status")));
        BigDecimal rate = rate(po.get("currencyCode"), po.get("exchangeRate"));
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT pl.id, pl.\"productId\", pl.quantity,"
                        + " pl.\"receivedQuantity\", pl.\"cancelledQuantity\", pl.\"unitPrice\", rl.\"costCenterId\","
                        + " rl.\"projectId\" FROM \"PurchaseOrderLine\" pl LEFT JOIN \"PurchaseRequestLine\" rl ON"
                        + " rl.id = pl.\"requestLineId\" WHERE pl.\"purchaseOrderId\" = ? AND pl.\"organizationId\" = ?"
                        + " ORDER BY pl.\"lineNumber\", pl.id")
                .params(purchaseOrderId, org()))) {
            BigDecimal price = Money.dec(l.get("unitPrice")).multiply(rate);
            BigDecimal qty = Money.dec(l.get("quantity"));
            BigDecimal openQty = qty.subtract(Money.dec(l.get("receivedQuantity")))
                    .subtract(Money.dec(l.get("cancelledQuantity"))).max(BigDecimal.ZERO);
            BigDecimal amount = Money.round(qty.multiply(price));
            lines.add(new Line((String) l.get("id"), account((String) l.get("productId"), amount),
                    (String) po.get("branchId"), (String) l.get("costCenterId"), (String) l.get("projectId"),
                    (String) po.get("period"), amount, open ? Money.round(openQty.multiply(price)) : BigDecimal.ZERO));
        }
        commitments.sync("purchase_order", purchaseOrderId, (String) po.get("orderNumber"), lines);
    }

    /**
     * Apply {@code budget.control} to a new order: {@code off} (default) does nothing; {@code warn} returns the
     * shortfalls as warnings; {@code block} refuses; {@code block_with_override} refuses unless the caller holds
     * {@code budget:override} (then warns and audits). Returns the warnings to show on the order.
     */
    public List<String> checkBudget(String sourceId, String branchId, String currencyCode, BigDecimal exchangeRate,
            List<Draft> drafts) {
        String mode = policies.text(ProcurementPolicyRegistry.BUDGET_CONTROL,
                ProcurementPolicyService.PolicyContext.branch(branchId));
        if (mode == null || "off".equals(mode)) {
            return List.of();
        }
        BigDecimal rate = rate(currencyCode, exchangeRate);
        String period = jdbc.sql("SELECT to_char(" + ProcurementSql.NOW + ", 'YYYY-MM')").query(String.class).single();
        List<Line> lines = new ArrayList<>();
        for (Draft d : drafts) {
            BigDecimal amount = Money.round(d.quantity().multiply(d.unitPrice()).multiply(rate));
            Map<String, Object> rl = d.requestLineId() == null ? null : SqlRows.one(jdbc.sql("SELECT \"costCenterId\","
                    + " \"projectId\" FROM \"PurchaseRequestLine\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(d.requestLineId(), org()));
            lines.add(new Line(d.key(), account(d.productId(), amount), branchId,
                    rl == null ? null : (String) rl.get("costCenterId"), rl == null ? null : (String) rl.get("projectId"),
                    period, amount, amount));
        }
        List<Shortfall> shortfalls = commitments.check(lines, sourceId);
        if (shortfalls.isEmpty()) {
            return List.of();
        }
        List<String> messages = shortfalls.stream().map(s -> "Budget exceeded for " + label(s) + " on budget '"
                + s.budgetName() + "': available " + JsNumbers.dec(Money.round(s.available())) + ", this order needs "
                + JsNumbers.dec(Money.round(s.requested()))).toList();
        boolean canOverride = "block_with_override".equals(mode)
                && TenantContext.permissions().contains(Permissions.BUDGET_CONTROL__OVERRIDE);
        if ("block".equals(mode) || "block_with_override".equals(mode) && !canOverride) {
            throw ApiException.badRequest(String.join("; ", messages)
                    + ("block_with_override".equals(mode) ? " (a user with budget:override can approve the overrun)" : ""));
        }
        for (Shortfall s : shortfalls) {
            events.publish("budget.exceeded", Obj.of("organizationId", org(), "sourceType", "purchase_order",
                    "sourceId", sourceId == null ? "" : sourceId, "accountId", s.accountId(), "overBy",
                    JsNumbers.dec(Money.round(s.overBy())), "mode", canOverride ? "override" : mode));
        }
        if (canOverride) {
            audit.record(AuditInput.of("Budget", shortfalls.get(0).budgetId(), "update", null,
                    Obj.of("budgetOverride", true, "shortfalls", shortfalls.stream().map(Shortfall::toMap).toList())));
        }
        return messages;
    }

    /** Commitments register (all sources), newest first. */
    public com.inventoryerp.shared.web.PageResult<Map<String, Object>> list(String sourceType, String sourceId,
            String accountId, String status, int page, int limit) {
        return commitments.list(sourceType, sourceId, accountId, status, page, limit);
    }

    private static String label(Shortfall s) {
        return s.accountCode() == null ? s.accountId() : s.accountCode() + " " + s.accountName();
    }

    /** Budget account a line commits against. */
    String account(String productId, BigDecimal amount) {
        if (productId == null) {
            return determination.expenseAccount(null, null);
        }
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.\"trackInventory\", p.\"productType\"::text AS"
                + " \"productType\", c.\"expenseAccountId\" FROM \"Product\" p LEFT JOIN \"ProductCategory\" c ON"
                + " c.id = p.\"categoryId\" WHERE p.id = ? AND p.\"organizationId\" = ?").params(productId, org()));
        if (p != null && PurchaseOrdersService.stocked(p)) {
            return stockPosting.stockAccount(productId, amount.signum() > 0 ? amount : BigDecimal.ONE);
        }
        return determination.expenseAccount(null, p == null ? null : (String) p.get("expenseAccountId"));
    }

    /** Functional-currency rate of an order (1 when it is in the organisation's currency). */
    private BigDecimal rate(Object currencyCode, Object exchangeRate) {
        if (currencyCode == null) {
            return BigDecimal.ONE;
        }
        String functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        if (functional == null || functional.equalsIgnoreCase(String.valueOf(currencyCode))) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = exchangeRate == null ? BigDecimal.ONE : Money.dec(exchangeRate);
        return rate.signum() > 0 ? rate : BigDecimal.ONE;
    }
}
