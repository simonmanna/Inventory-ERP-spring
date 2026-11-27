package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryTieOutMonitor;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityReport;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily inventory integrity check of one organisation (hardening R0; the pattern of {@link InventoryTieOutMonitor}):
 * runs every check of {@link InventoryIntegrityService} over all locations and opens one {@code InventoryException}
 * of kind {@code integrity_<check>} per failing check (Posting Monitor), with the failure count and the first rows in
 * its payload, plus one in-app alert to every user holding {@code inventory_valuation:read} (the integrity report's
 * permission; once per user per set of kinds per day). While an exception of a kind is open no second one of that
 * kind is raised; resolving it re-arms the check. It lists findings and never corrects anything. The value kinds
 * ({@link InventoryTieOutMonitor#INTEGRITY_VALUE_KINDS}) hold the period close; the quantity kinds do not. The
 * valuation-vs-GL kind is not raised while {@code inventory.glTieOut.enabled} is off. The report-only checks (IPR
 * Phase 0, {@link InventoryIntegrityService#REPORT_ONLY_CHECKS}) are raised the same way and never hold a close.
 */
@Service
public class InventoryIntegrityMonitor {

    /** Rows of a failing check copied into the exception payload. */
    static final int PAYLOAD_ROWS = 20;

    private static final Logger log = LoggerFactory.getLogger(InventoryIntegrityMonitor.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final InventoryIntegrityService integrity;
    private final SettingResolverService settings;
    private final NotificationsService notifications;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public InventoryIntegrityMonitor(InventoryIntegrityService integrity, SettingResolverService settings,
            NotificationsService notifications, JdbcClient jdbc, PlatformTransactionManager txm) {
        this.integrity = integrity;
        this.settings = settings;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** {@code InventoryException.kind} of a failing check. */
    public static String kind(String check) {
        return InventoryTieOutMonitor.INTEGRITY_KIND_PREFIX + check;
    }

    /**
     * Run the checks for the current organisation (tenant scope set by the caller) and raise the failing ones.
     * Returns the report and the kinds raised by this run.
     */
    public Result check() {
        IntegrityReport report = integrity.run(null);
        String orgId = TenantContext.organizationId();
        boolean tieOutEnabled = settings.resolveBool("inventory.glTieOut.enabled", SettingContext.EMPTY);
        List<String> raised = new ArrayList<>();
        for (IntegrityCheck c : report.failing()) {
            if ((InventoryIntegrityService.VALUATION_VS_GL.equals(c.key())
                    || InventoryIntegrityService.VALUATION_VS_GL_BY_PRODUCT.equals(c.key())) && !tieOutEnabled) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.execute(st -> raise(orgId, c)))) {
                raised.add(kind(c.key()));
            }
        }
        // IPR Phase 0: report-only checks raise integrity_<key> findings too. They are never value kinds, so the
        // period close and its checklist (which skip every other integrity_ kind) ignore them.
        List<String> reportOnlyRaised = new ArrayList<>();
        for (IntegrityCheck c : report.failingReportOnly()) {
            if (InventoryIntegrityService.GL_TIEOUT_BY_ACCOUNT.equals(c.key()) && !tieOutEnabled) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.execute(st -> raise(orgId, c)))) {
                reportOnlyRaised.add(kind(c.key()));
            }
        }
        if (!raised.isEmpty() || !reportOnlyRaised.isEmpty()) {
            List<String> all = new ArrayList<>(raised);
            all.addAll(reportOnlyRaised);
            try {
                notifications.sendToPermission(orgId, Permissions.INVENTORY_VALUATION__READ, "inventory",
                        "Inventory integrity check failed", "The daily inventory integrity check found "
                                + String.join(", ", all) + ". The findings are listed in the Posting Monitor;"
                                + " the inventory integrity report shows every row. Nothing was corrected.",
                        Obj.of("kind", "inventory_integrity", "kinds", raised, "reportOnlyKinds", reportOnlyRaised),
                        "inventory_integrity:" + LocalDate.now(ZoneOffset.UTC) + ":" + String.join(",", all));
            } catch (RuntimeException e) {
                log.error("[inventory-integrity] failed to send the alert: {}", e.toString());
            }
        }
        return new Result(report, raised, reportOnlyRaised);
    }

    /**
     * The report of one run and the exception kinds it raised: {@code raised} for the checks of the report,
     * {@code reportOnlyRaised} for its report-only checks (IPR Phase 0).
     */
    public record Result(IntegrityReport report, List<String> raised, List<String> reportOnlyRaised) {}

    private boolean raise(String orgId, IntegrityCheck c) {
        String kind = kind(c.key());
        // One finding per kind: a concurrent run (ShedLock aside) waits here and then sees the open row.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param(orgId + ":" + kind).query().listOfRows();
        long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ?"
                + " AND status = 'open'").params(orgId, kind).query(Long.class).single();
        if (open > 0) {
            return false;
        }
        String reason = "Integrity check " + c.key() + " failed: " + c.failures() + " row(s) break \"" + c.invariant()
                + "\"" + (c.note() != null ? " (" + c.note() + ")" : "");
        Map<String, Object> payload = Obj.of("check", c.key(), "invariant", c.invariant(), "tolerance",
                c.tolerance() == null ? null : c.tolerance().toPlainString(), "failures", c.failures(), "rows",
                c.rows().subList(0, Math.min(PAYLOAD_ROWS, c.rows().size())), "productIds",
                List.copyOf(InventoryIntegrityService.productIds(c)));
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                        + " payload, status, \"createdAt\") VALUES (?, ?, ?, 0, ?, ?, ?::jsonb, 'open', " + NOW + ")")
                .params(UUID.randomUUID().toString(), orgId, "Inventory integrity: " + c.key(), kind, reason,
                        EntityJson.toJson(payload))
                .update();
        return true;
    }
}
