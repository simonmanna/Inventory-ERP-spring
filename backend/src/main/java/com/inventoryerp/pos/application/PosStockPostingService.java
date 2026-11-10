package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.InventoryException;
import com.inventoryerp.pos.domain.StockPostingJob;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Posting Monitor back-office API over {@code StockPostingJob} / {@code InventoryException}, plus the per-shift
 * drain the cash-session close calls (port of {@code billing/stock-posting.service.ts} and
 * {@code StockPostingWorker.drainSession}).
 */
@Service
public class PosStockPostingService {

    private static final Logger log = LoggerFactory.getLogger("StockPostingWorker");

    /** A due job older than this means nothing is draining the queue. */
    private static final long STALLED_QUEUE_MS = 5 * 60_000L;

    private final PosDb db;
    private final AuditService audit;
    private final PosInvoiceService billing;

    public PosStockPostingService(PosDb db, AuditService audit, PosInvoiceService billing) {
        this.db = db;
        this.audit = audit;
        this.billing = billing;
    }

    public List<Map<String, Object>> listJobs(String status) {
        if ("all".equals(status)) {
            return db.list(StockPostingJob.class, "\"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 200", org());
        }
        return db.list(StockPostingJob.class,
                "\"organizationId\" = ? AND status = ? ORDER BY \"createdAt\" DESC LIMIT 200", org(), status);
    }

    public List<Map<String, Object>> listExceptions(String status) {
        if ("all".equals(status)) {
            return db.list(InventoryException.class, "\"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 200",
                    org());
        }
        return db.list(InventoryException.class,
                "\"organizationId\" = ? AND status = ? ORDER BY \"createdAt\" DESC LIMIT 200", org(), status);
    }

    /** Queue depth plus a liveness verdict ({@code stalled} = due jobs nobody drains). */
    public Map<String, Object> counts() {
        String orgId = org();
        long now = System.currentTimeMillis();
        Timestamp staleAfter = Timestamp.from(Instant.ofEpochMilli(now - STALLED_QUEUE_MS));
        long pending = db.count(StockPostingJob.class, "\"organizationId\" = ? AND status = 'pending'", orgId);
        long processing = db.count(StockPostingJob.class, "\"organizationId\" = ? AND status = 'processing'", orgId);
        long failed = db.count(StockPostingJob.class, "\"organizationId\" = ? AND status = 'failed'", orgId);
        long doneWithReview = db.count(StockPostingJob.class,
                "\"organizationId\" = ? AND status = 'done' AND \"lastError\" IS NOT NULL", orgId);
        long openExceptions = db.count(InventoryException.class, "\"organizationId\" = ? AND status = 'open'", orgId);
        long overdue = db.count(StockPostingJob.class, "\"organizationId\" = ? AND status IN ('pending', 'processing')"
                + " AND \"nextRetryAt\" < ?", orgId, staleAfter);
        Map<String, Object> oldest = db.first(StockPostingJob.class,
                "\"organizationId\" = ? AND status IN ('pending', 'processing') ORDER BY \"createdAt\" ASC", orgId);

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("pending", pending);
        jobs.put("processing", processing);
        jobs.put("failed", failed);
        jobs.put("doneWithReview", doneWithReview);
        jobs.put("overdue", overdue);
        jobs.put("oldestPendingAgeMs", oldest == null ? 0 : now - ((Instant) oldest.get("createdAt")).toEpochMilli());
        jobs.put("oldestPendingInvoice", oldest == null ? null : oldest.get("invoiceNumber"));
        // Post-parity: a checkout commits Order, Invoice and Payment in separate transactions (as in Nest). A sale whose
        // payment step failed leaves a billed, unpaid, non-credit invoice; surface it so the pending-sale recovery or a
        // refund resolves it instead of it sitting in AR unnoticed.
        Map<String, Object> unpaid = SqlRows.one(db.jdbc().sql("""
                SELECT count(*) AS n, min(i."invoiceNumber") FILTER (WHERE i."createdAt" = m.oldest) AS oldest
                  FROM "Invoice" i
                  CROSS JOIN (SELECT min("createdAt") AS oldest FROM "Invoice" WHERE "organizationId" = ? AND "orderId" IS NOT NULL
                                 AND "settlementStatus"::text = 'unsettled' AND "paymentMode"::text IS DISTINCT FROM 'credit'
                                 AND status::text NOT IN ('cancelled', 'refunded')
                                 AND "createdAt" < now() - interval '5 minutes') m
                 WHERE i."organizationId" = ? AND i."orderId" IS NOT NULL AND i."settlementStatus"::text = 'unsettled'
                   AND i."paymentMode"::text IS DISTINCT FROM 'credit' AND i.status::text NOT IN ('cancelled', 'refunded')
                   AND i."createdAt" < now() - interval '5 minutes'""").params(orgId, orgId));
        long unpaidSales = unpaid == null ? 0 : ((Number) unpaid.get("n")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", jobs);
        out.put("exceptions", Obj.of("open", openExceptions));
        out.put("unpaidSales", Obj.of("count", unpaidSales, "oldestInvoice", unpaid == null ? null : unpaid.get("oldest")));
        out.put("stalled", overdue > 0);
        out.put("health", failed > 0 || overdue > 0 ? "degraded"
                : openExceptions > 0 || unpaidSales > 0 ? "attention" : "ok");
        return out;
    }

    /** Re-queue a FAILED job (a failed job deducted nothing, so this is safe). */
    public Map<String, Object> retryJob(String id) {
        Map<String, Object> job = db.first(StockPostingJob.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (job == null) {
            throw ApiException.notFound("Stock posting job not found");
        }
        if (!"failed".equals(job.get("status"))) {
            throw ApiException.badRequest("Only failed jobs can be retried (this one is " + job.get("status") + ")");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "pending");
        data.put("attempts", 0);
        data.put("lastError", null);
        data.put("nextRetryAt", PosDb.raw(NOW));
        data.put("claimToken", null);
        data.put("claimedAt", null);
        Map<String, Object> updated = db.update(StockPostingJob.class, data, "id = ? AND \"organizationId\" = ?", id,
                org());
        audit.record(AuditInput.of("StockPostingJob", id, "update", null,
                Obj.of("retried", true, "invoiceNumber", job.get("invoiceNumber"))));
        return Obj.of("id", updated.get("id"), "status", updated.get("status"));
    }

    public Map<String, Object> assignException(String id, String assignedToId) {
        Map<String, Object> row = db.first(InventoryException.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (row == null) {
            throw ApiException.notFound("Inventory exception not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assignedToId", assignedToId);
        return db.update(InventoryException.class, data, "id = ? AND \"organizationId\" = ?", id, org());
    }

    /** Close an exception with a note: {@code resolved} (drift corrected) or {@code discarded}. */
    public Map<String, Object> resolveException(String id, String status, String note) {
        if (note == null || note.trim().isEmpty()) {
            throw ApiException.badRequest("A note is required — record how the drift was corrected");
        }
        Map<String, Object> row = db.first(InventoryException.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (row == null) {
            throw ApiException.notFound("Inventory exception not found");
        }
        if (!"open".equals(row.get("status"))) {
            throw ApiException.badRequest("Already " + row.get("status"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("resolutionNote", note.trim());
        data.put("resolvedById", user());
        data.put("resolvedAt", PosDb.raw(NOW));
        Map<String, Object> updated = db.update(InventoryException.class, data, "id = ? AND \"organizationId\" = ?", id,
                org());
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", status);
        newValues.put("note", note.trim());
        newValues.put("invoiceNumber", row.get("invoiceNumber"));
        newValues.put("productId", row.get("productId"));
        newValues.put("menuItemId", row.get("menuItemId"));
        audit.record(AuditInput.of("InventoryException", id, "update", null, newValues));
        return Obj.of("id", updated.get("id"), "status", updated.get("status"), "resolvedAt", updated.get("resolvedAt"));
    }

    /**
     * Post one shift's due jobs now, in the caller's tenant scope (the close screen calls this so a cashier never
     * waits for the 30 s tick). Jobs in retry backoff are left alone.
     */
    private static final int DRAIN_MAX_JOBS = 200;
    private static final java.time.Duration DRAIN_BUDGET = java.time.Duration.ofSeconds(15);

    public void drainSession(String cashSessionId) {
        List<String> ids = db.jdbc().sql("""
                SELECT j.id FROM "StockPostingJob" j
                 WHERE j."organizationId" = ? AND j.status = 'pending' AND j."nextRetryAt" <= date_trunc('milliseconds', clock_timestamp())::timestamp(3)
                   AND j."invoiceId" IN (SELECT i.id FROM "Invoice" i WHERE i."organizationId" = ? AND i."cashSessionId" = ?)
                 ORDER BY j."createdAt" ASC LIMIT ?""")
                .params(org(), org(), cashSessionId, DRAIN_MAX_JOBS).query(String.class).list();
        // Post-parity: bounded (job count + wall time) so a stalled worker or app.jobs.enabled=false cannot turn the
        // close request into a time-out; whatever is left is posted by the 30 s worker and shows as pending postings.
        long deadline = System.nanoTime() + DRAIN_BUDGET.toNanos();
        for (String id : ids) {
            if (System.nanoTime() > deadline) {
                log.warn("shift {} drain stopped after its {} s budget; the worker posts the rest", cashSessionId,
                        DRAIN_BUDGET.toSeconds());
                break;
            }
            try {
                billing.processStockPostingJob(id);
            } catch (RuntimeException err) {
                log.warn("stock job {} (shift {}) failed: {}", id, cashSessionId, err.toString());
            }
        }
    }
}
