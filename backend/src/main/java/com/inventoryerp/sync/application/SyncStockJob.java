package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-S083 S4: a transfer receipt recorded offline before the server had its dispatch waits as
 * {@code offline_awaiting_dispatch}. A dispatch synced from a device applies its waiting receipts at once; this job
 * covers a dispatch made anywhere else (the back office, another device's earlier batch).
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SyncStockJob {

    private static final Logger log = LoggerFactory.getLogger(SyncStockJob.class);

    private final JdbcClient jdbc;
    private final SyncStockService stock;

    public SyncStockJob(JdbcClient jdbc, SyncStockService stock) {
        this.jdbc = jdbc;
        this.stock = stock;
    }

    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT1M")
    @SchedulerLock(name = "sync-awaiting-dispatch", lockAtMostFor = "PT10M")
    public void tick() {
        List<Map<String, Object>> ready = jdbc.sql("SELECT DISTINCT e.\"organizationId\" AS org, e.payload->>'transferId'"
                + " AS transfer FROM \"InventoryException\" e JOIN \"StockTransfer\" t ON t.id = e.payload->>'transferId'"
                + " WHERE e.kind = ? AND e.status = 'open' AND t.status::text IN ('in_transit', 'partially_received')")
                .param(SyncStockService.AWAITING).query().listOfRows();
        for (Map<String, Object> r : ready) {
            String org = (String) r.get("org");
            String transfer = (String) r.get("transfer");
            try {
                int n = TenantContext.run(TenantContext.TenantStore.organization(org), () -> stock.applyAwaiting(transfer));
                log.info("Applied {} waiting offline receipt(s) of transfer {} ({})", n, transfer, org);
            } catch (RuntimeException e) {
                log.warn("Waiting offline receipts of transfer {} failed: {}", transfer, e.toString());
            }
        }
    }
}
