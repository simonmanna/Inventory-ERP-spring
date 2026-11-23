package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnLine;
import com.inventoryerp.inventory.application.StockReversalService.ReturnedRow;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hardening R4 (owner-approved 2026-11-23): the stock of an order posted before it was billed (the deprecated
 * order-level timings {@code at_confirmation} / {@code at_fulfillment_complete}) follows the order when it is cancelled,
 * reopened or loses a line.
 *
 * <ul>
 *   <li>Cancel: the order's stock-posting jobs are locked ({@code FOR UPDATE}, so a cancel waits for a job line being
 *       posted) and abandoned — a pending job never issues — and whatever the order already issued is returned against
 *       its original rows at their cost, lot and serial, net of earlier returns
 *       ({@link StockReversalService#returnFromSource}).</li>
 *   <li>Void of a line already issued: the voided share of the line's issue comes back the same way; the fired part
 *       then goes to kitchen waste once (as before), so a voided item is never issued twice.</li>
 *   <li>Reopen: the jobs a cancel abandoned run again from the start (what they issued was returned).</li>
 * </ul>
 * Runs in the caller's transaction, after the order row lock (lock order: order, job, stock).
 */
@Service
public class OrderStockReversalService {

    private static final Logger log = LoggerFactory.getLogger(OrderStockReversalService.class);

    /** Start of the {@code lastError} of a job abandoned because its order was cancelled; reopen revives those. */
    public static final String CANCELLED_NOTE = "Order cancelled";

    /** Ledger reference types of a POS issue posted against the order (before it has an invoice). */
    private static final String ORDER_ISSUE = "((l.\"referenceType\" IN ('pos_order', 'menu_recipe')"
            + " AND l.\"referenceId\" = :order) OR (l.\"referenceType\" IN ('pos_invoice_combo', 'pos_invoice_extra')"
            + " AND split_part(l.\"referenceId\", ':', 1) IN (SELECT i.id FROM \"OrderItem\" i WHERE i.\"orderId\" = :order"
            + " AND i.\"organizationId\" = :org)))";

    private final JdbcClient jdbc;
    private final StockReversalService reversals;
    private final AuditService audit;

    public OrderStockReversalService(JdbcClient jdbc, StockReversalService reversals, AuditService audit) {
        this.jdbc = jdbc;
        this.reversals = reversals;
        this.audit = audit;
    }

    /**
     * An order is being cancelled (caller holds the order row lock): abandon its stock-posting jobs and return what
     * they issued. A job being posted is waited for; one that posted part of the order has only that part returned.
     */
    @Transactional
    public void onOrderCancelled(String orderId) {
        String orgId = org();
        List<Map<String, Object>> jobs = lockJobs(orgId, orderId);
        List<String> codes = issueCodes(orgId, orderId, null);
        int returned = 0;
        if (!codes.isEmpty()) {
            String number = orderNumber(orgId, orderId);
            returned = reversals.returnFromSource(new ReturnFromSource(IssueSource.ledgerCodes(codes), null,
                    "pos_order_cancel", orderId, "Cancel " + number, "Order " + number + " cancelled: issued stock"
                            + " returned", null, null, true, "reversal_in")).size();
        }
        String note = CANCELLED_NOTE + (returned > 0 ? "; " + returned + " issued movement(s) returned to stock"
                : "; nothing was issued");
        for (Map<String, Object> job : jobs) {
            if ("abandoned".equals(job.get("status"))) {
                continue;
            }
            jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'abandoned', \"claimToken\" = NULL, \"claimedAt\" = NULL,"
                    + " \"processedAt\" = " + NOW + ", \"lastError\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(note, job.get("id"), orgId).update();
            audit.recordInTx(AuditInput.of("StockPostingJob", (String) job.get("id"), "update",
                    Obj.of("status", job.get("status")), Obj.of("status", "abandoned", "orderId", orderId,
                            "note", note)));
        }
        if (!jobs.isEmpty()) {
            // The order's open line failures will never be posted now.
            jdbc.sql("UPDATE \"InventoryException\" SET status = 'discarded', \"resolutionNote\" = ?, \"resolvedAt\" = "
                    + NOW + " WHERE \"organizationId\" = ? AND status = 'open' AND \"invoiceId\" IS NULL"
                    + " AND payload->>'orderItemId' IN (SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ?"
                    + " AND \"organizationId\" = ?)").params(note, orgId, orderId, orgId).update();
        }
        if (returned > 0) {
            log.info("[stock] order {} cancelled: {} issue row(s) returned", orderId, returned);
        }
    }

    /**
     * A line of an open order is voided ({@code voided} of {@code before}; caller holds the order row lock): when the
     * order's job already issued it, the voided share of what the line still has issued comes back at its original
     * cost, lot and serial. Kitchen waste for the fired part is recorded by the caller afterwards, so the line is never
     * issued twice. Lines issued before R4 (no {@code sourceLineId} on their rows) are not traced and stay as issued.
     */
    @Transactional
    public void onItemVoided(String orderId, String orderItemId, BigDecimal voided, BigDecimal before) {
        String orgId = org();
        if (voided == null || voided.signum() <= 0 || lockJobs(orgId, orderId).isEmpty()) {
            return;
        }
        List<String> codes = issueCodes(orgId, orderId, orderItemId);
        if (codes.isEmpty()) {
            return;
        }
        String number = orderNumber(orgId, orderId);
        IssueSource source = IssueSource.ledgerCodes(codes);
        List<ReturnLine> lines = null; // the whole line: everything still issued
        if (before != null && before.signum() > 0 && voided.compareTo(before) < 0) {
            BigDecimal share = voided.divide(before, 10, RoundingMode.HALF_UP);
            lines = new ArrayList<>();
            for (StockReversalService.Returnable r : reversals.returnableByProduct(source)) {
                BigDecimal qty = r.quantity().multiply(share).setScale(6, RoundingMode.HALF_UP);
                if (qty.signum() > 0) {
                    lines.add(ReturnLine.of(r.productId(), r.variantId(), qty));
                }
            }
            if (lines.isEmpty()) {
                return;
            }
        }
        List<ReturnedRow> rows = reversals.returnFromSource(new ReturnFromSource(source, lines, "pos_order_void",
                orderItemId, "Void on " + number, "Line voided on order " + number + ": issued stock returned", null,
                null, true, "reversal_in"));
        log.info("[stock] void on order {}: {} issue row(s) returned", orderId, rows.size());
    }

    /**
     * A cancelled order is reopened: the stock-posting jobs its cancel abandoned run again from the start (the cancel
     * returned what they had issued).
     */
    @Transactional
    public void onOrderReopened(String orderId) {
        String orgId = org();
        lockJobs(orgId, orderId);
        jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'pending', \"postedLineKeys\" = ARRAY[]::text[], attempts = 0,"
                + " \"lastError\" = NULL, \"processedAt\" = NULL, \"claimToken\" = NULL, \"claimedAt\" = NULL,"
                + " \"nextRetryAt\" = " + NOW + " WHERE \"organizationId\" = ? AND \"orderId\" = ? AND status = 'abandoned'"
                + " AND \"lastError\" LIKE ?").params(orgId, orderId, CANCELLED_NOTE + "%").update();
    }

    /** The order's stock-posting jobs, locked in id order (waits for a job line being posted). */
    private List<Map<String, Object>> lockJobs(String orgId, String orderId) {
        return SqlRows.list(jdbc.sql("SELECT id, status FROM \"StockPostingJob\" WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ? ORDER BY id FOR UPDATE").params(orgId, orderId));
    }

    /** Ledger codes of the order's issue rows (one line's when {@code orderItemId} is given), in original order. */
    private List<String> issueCodes(String orgId, String orderId, String orderItemId) {
        String line = orderItemId == null ? "" : " AND (l.\"sourceLineId\" = :item OR (l.\"referenceType\" IN"
                + " ('pos_invoice_combo', 'pos_invoice_extra') AND split_part(l.\"referenceId\", ':', 1) = :item))";
        var spec = jdbc.sql("SELECT l.\"ledgerCode\" FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = :org"
                        + " AND l.\"quantityChange\" < 0 AND " + ORDER_ISSUE + line + " ORDER BY l.\"createdAt\", l.id")
                .param("org", orgId).param("order", orderId);
        if (orderItemId != null) {
            spec = spec.param("item", orderItemId);
        }
        return spec.query(String.class).list();
    }

    private String orderNumber(String orgId, String orderId) {
        return jdbc.sql("SELECT \"orderNumber\" FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?")
                .params(orderId, orgId).query(String.class).optional().orElse(orderId);
    }
}
