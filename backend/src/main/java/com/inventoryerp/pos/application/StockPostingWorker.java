package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code StockPostingJob} rows (port of {@code billing/stock-posting.worker.ts}): claims due jobs with
 * {@code FOR UPDATE SKIP LOCKED}, processes each in its org's tenant scope, and raises a health alert every 5 min.
 * The per-shift drain lives in {@link PosStockPostingService#drainSession}. Disabled with {@code app.jobs.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class StockPostingWorker {

    private static final Logger log = LoggerFactory.getLogger("StockPostingWorker");
    private static final long STALE_CLAIM_MS = 60_000L;

    private final JdbcClient jdbc;
    private final PosInvoiceService billing;
    private final int batchSize;
    private final long lagAlertMs;

    public StockPostingWorker(JdbcClient jdbc, PosInvoiceService billing,
            @Value("${STOCK_POSTING_BATCH:20}") int batchSize,
            @Value("${STOCK_POSTING_LAG_ALERT_MS:900000}") long lagAlertMs) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.batchSize = batchSize;
        this.lagAlertMs = lagAlertMs;
    }

    @Scheduled(cron = "*/30 * * * * *")
    @SchedulerLock(name = "stock-posting-drain", lockAtMostFor = "PT5M")
    public void drain() {
        for (int round = 0; round < 20; round++) {
            if (drainBatch() < batchSize) {
                return;
            }
        }
    }

    /** Claim and process one batch; returns how many jobs were claimed. */
    int drainBatch() {
        String claimToken = UUID.randomUUID().toString();
        Timestamp staleBefore = Timestamp.from(Instant.now().minusMillis(STALE_CLAIM_MS));
        List<String> claimed = jdbc.sql("""
                UPDATE "StockPostingJob"
                SET "claimToken" = ?, "claimedAt" = NOW(), "status" = 'processing'
                WHERE "id" IN (
                  SELECT "id" FROM "StockPostingJob"
                  WHERE (("status" = 'pending' AND "nextRetryAt" <= NOW())
                         OR ("status" = 'processing' AND "claimedAt" < ?))
                  ORDER BY "createdAt" ASC
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                )
                RETURNING "id\"""")
                .params(claimToken, staleBefore, batchSize).query(String.class).list();
        if (claimed.isEmpty()) {
            return 0;
        }
        if (claimed.size() >= batchSize) {
            log.warn("stock posting backlog: claimed {} job(s) — on-hand is behind actual sales", claimed.size());
        }
        for (String id : claimed) {
            String orgId = jdbc.sql("SELECT \"organizationId\" FROM \"StockPostingJob\" WHERE id = ?").param(id)
                    .query(String.class).optional().orElse(null);
            if (orgId == null) {
                continue;
            }
            TenantContext.run(TenantStore.organization(orgId), () -> {
                try {
                    billing.processStockPostingJob(id);
                } catch (RuntimeException err) {
                    log.error("stock job {} failed: {}", id, err.toString());
                }
            });
        }
        return claimed.size();
    }

    /** Queue-health monitor: in-app alert per org with failed or lagging jobs. */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "stock-posting-health", lockAtMostFor = "PT4M")
    public void monitor() {
        Timestamp lagBefore = Timestamp.from(Instant.now().minusMillis(lagAlertMs));
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT "organizationId",
                       COUNT(*) FILTER (WHERE "status" = 'failed') AS failed,
                       COUNT(*) FILTER (WHERE "status" IN ('pending', 'processing') AND "createdAt" < ?) AS lagging,
                       MIN("createdAt") FILTER (WHERE "status" <> 'done') AS oldest
                  FROM "StockPostingJob"
                 WHERE "status" <> 'done'
                 GROUP BY "organizationId"
                HAVING COUNT(*) FILTER (WHERE "status" = 'failed') > 0
                    OR COUNT(*) FILTER (WHERE "status" IN ('pending', 'processing') AND "createdAt" < ?) > 0""")
                .params(lagBefore, lagBefore).query().listOfRows();
        for (Map<String, Object> r : rows) {
            long failed = ((Number) r.get("failed")).longValue();
            long lagging = ((Number) r.get("lagging")).longValue();
            String orgId = (String) r.get("organizationId");
            log.error("stock posting unhealthy for org {}: {} failed, {} older than {} min (oldest {})", orgId, failed,
                    lagging, Math.round(lagAlertMs / 60_000.0), r.get("oldest") == null ? "n/a" : r.get("oldest"));
            try {
                TenantContext.run(TenantStore.organization(orgId),
                        () -> billing.raiseStockPostingHealthAlert(failed, lagging));
            } catch (RuntimeException err) {
                log.error("stock posting health alert failed: {}", err.toString());
            }
        }
    }
}
