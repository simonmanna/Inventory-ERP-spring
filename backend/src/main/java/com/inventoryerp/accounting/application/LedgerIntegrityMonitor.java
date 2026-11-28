package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.LedgerIntegrityService.IntegrityCheck;
import com.inventoryerp.accounting.application.LedgerIntegrityService.IntegrityReport;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly ledger integrity monitor (finance readiness audit 2026-11-23, step A0). Runs {@link LedgerIntegrityService}
 * for the current organisation and keeps one open {@code LedgerIntegrityFinding} per failing check: opened when the check
 * starts failing, refreshed while it keeps failing, resolved once it passes. Opening findings raises one in-app alert to
 * every user holding {@code report:accounting} (the ledger integrity report's permission; once per user per set of
 * checks per day); a check that stays broken does not alert again. The inventory GL tie-out is left to
 * {@link InventoryTieOutMonitor}, which already alerts on it; the AR / AP tie-out (I8, finance audit A5) is one of
 * the checks recorded here. Nothing is corrected.
 */
@Service
public class LedgerIntegrityMonitor {

    /** Notification payload kind of the alert. */
    public static final String KIND = "ledger_integrity";

    /** Rows of a failing check kept on its finding. */
    static final int SAMPLE_ROWS = 20;

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityMonitor.class);

    /** The report, and the checks whose findings this run opened and resolved. */
    public record Outcome(IntegrityReport report, List<String> opened, List<String> resolved) {}

    private final LedgerIntegrityService integrity;
    private final NotificationsService notifications;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public LedgerIntegrityMonitor(LedgerIntegrityService integrity, NotificationsService notifications, JdbcClient jdbc,
            PlatformTransactionManager txm) {
        this.integrity = integrity;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** Run the checks for the current organisation (tenant scope set by the caller) and record the findings. */
    public Outcome check() {
        IntegrityReport report = integrity.run();
        String orgId = TenantContext.organizationId();
        List<String> opened = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        tx.executeWithoutResult(st -> {
            for (IntegrityCheck c : report.checks()) {
                if (LedgerIntegrityService.INVENTORY_GL.equals(c.key())) {
                    continue;
                }
                if (c.ok()) {
                    if (jdbc.sql("UPDATE \"LedgerIntegrityFinding\" SET status = 'resolved', \"resolvedAt\" = now()"
                                    + " WHERE \"organizationId\" = ? AND \"checkKey\" = ? AND status = 'open'")
                            .params(orgId, c.key()).update() > 0) {
                        resolved.add(c.key());
                    }
                    continue;
                }
                String sample = EntityJson.toJson(c.rows().subList(0, Math.min(SAMPLE_ROWS, c.rows().size())));
                int refreshed = jdbc.sql("UPDATE \"LedgerIntegrityFinding\" SET failures = ?, sample = ?::jsonb,"
                                + " \"lastSeenAt\" = now() WHERE \"organizationId\" = ? AND \"checkKey\" = ?"
                                + " AND status = 'open'")
                        .params(c.failures(), sample, orgId, c.key()).update();
                if (refreshed == 0 && jdbc.sql("INSERT INTO \"LedgerIntegrityFinding\" (id, \"organizationId\","
                                + " \"checkKey\", failures, invariant, sample) VALUES (?, ?, ?, ?, ?, ?::jsonb)"
                                + " ON CONFLICT DO NOTHING")
                        .params(UUID.randomUUID().toString(), orgId, c.key(), c.failures(), c.invariant(), sample)
                        .update() > 0) {
                    opened.add(c.key());
                }
            }
        });
        if (!opened.isEmpty()) {
            String list = report.checks().stream().filter(c -> opened.contains(c.key()))
                    .map(c -> c.key() + " (" + c.failures() + ")").collect(Collectors.joining(", "));
            try {
                notifications.sendToPermission(orgId, Permissions.REPORT__ACCOUNTING, "accounting",
                        "Ledger integrity check failed", "The nightly ledger check found problems: " + list
                                + ". The ledger integrity report (/reports/accounting/integrity) lists the entries and"
                                + " periods involved.",
                        Obj.of("kind", KIND, "checks", List.copyOf(opened)),
                        KIND + ":" + LocalDate.now(ZoneOffset.UTC) + ":" + String.join(",", opened));
            } catch (RuntimeException e) {
                log.error("[ledger-integrity] failed to send the alert: {}", e.toString());
            }
        }
        return new Outcome(report, List.copyOf(opened), List.copyOf(resolved));
    }
}
