package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Daily inventory-to-GL tie-out check of one organisation (the {@code GET /reports/inventory/gl-tieout} computation):
 * when the stock valuation and the inventory control account(s) differ by more than {@code inventory.glTieOut.tolerance}
 * it opens one {@code InventoryException} of kind {@code gl_tieout_variance} (Posting Monitor) and sends an in-app
 * alert to every user holding {@code report:accounting} (the tie-out report's permission; once per user per day).
 * While such an exception is open no second one is raised; resolving it re-arms the check.
 */
@Service
public class InventoryTieOutMonitor {

    /** {@code InventoryException.kind} of a tie-out variance. */
    public static final String KIND = "gl_tieout_variance";

    /**
     * Prefix of the {@code InventoryException.kind} raised by the inventory integrity monitor (hardening R0,
     * {@code integrity_<check>}). Declared here because accounting (period close) reads them and inventory depends on
     * accounting, not the other way round.
     */
    public static final String INTEGRITY_KIND_PREFIX = "integrity_";

    /**
     * Integrity kinds on stock value: while one is open (raised since the period started) the period cannot close.
     * The quantity integrity kinds are findings for the Posting Monitor and never hold a close.
     */
    public static final List<String> INTEGRITY_VALUE_KINDS = List.of(INTEGRITY_KIND_PREFIX + "valuation_vs_subledger",
            INTEGRITY_KIND_PREFIX + "valuation_vs_gl", INTEGRITY_KIND_PREFIX + "valuation_vs_gl_by_product");

    private static final Logger log = LoggerFactory.getLogger(InventoryTieOutMonitor.class);

    private final InventoryValuationService valuation;
    private final SettingResolverService settings;
    private final NotificationsService notifications;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public InventoryTieOutMonitor(InventoryValuationService valuation, SettingResolverService settings,
            NotificationsService notifications, JdbcClient jdbc, PlatformTransactionManager txm) {
        this.valuation = valuation;
        this.settings = settings;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * Run the check for the current organisation (tenant scope set by the caller). Returns the tie-out result, or null
     * when the check is switched off.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> check() {
        if (!settings.resolveBool("inventory.glTieOut.enabled", SettingContext.EMPTY)) {
            return null;
        }
        Number configured = settings.resolveNumber("inventory.glTieOut.tolerance", SettingContext.EMPTY);
        double tolerance = Math.max(0, configured == null ? 1 : configured.doubleValue());
        Map<String, Object> result = valuation.glTieOut(null, tolerance);
        if (Boolean.TRUE.equals(result.get("withinTolerance"))) {
            return result;
        }
        String orgId = TenantContext.organizationId();
        Boolean raised = tx.execute(st -> {
            long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?"
                    + " AND kind = ? AND status = 'open'").params(orgId, KIND).query(Long.class).single();
            if (open > 0) {
                return false;
            }
            String reason = "Stock valuation " + result.get("subledgerValue") + " differs from the inventory GL balance "
                    + result.get("glBalance") + " by " + result.get("variance") + " (tolerance "
                    + JsNumbers.toJsString(tolerance) + ")";
            Map<String, Object> payload = Obj.of("asOf", String.valueOf(result.get("asOf")), "subledgerValue",
                    result.get("subledgerValue"), "glBalance", result.get("glBalance"), "variance",
                    result.get("variance"), "tolerance", JsNumbers.js(tolerance), "bySource", result.get("bySource"),
                    "unpostedMovements", result.get("unpostedMovements"));
            jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                            + " payload, status, \"createdAt\") VALUES (?, ?, ?, 0, ?, ?, ?::jsonb, 'open',"
                            + " date_trunc('milliseconds', clock_timestamp())::timestamp(3))")
                    .params(UUID.randomUUID().toString(), orgId, "Inventory–GL tie-out variance", KIND, reason,
                            EntityJson.toJson(payload))
                    .update();
            return true;
        });
        if (Boolean.TRUE.equals(raised)) {
            try {
                notifications.sendToPermission(orgId, Permissions.REPORT__ACCOUNTING, "inventory",
                        "Inventory does not tie to the GL", "Stock valuation and the inventory control account differ by "
                                + result.get("variance") + " (tolerance " + JsNumbers.toJsString(tolerance)
                                + "). Review the inventory GL tie-out report; the variance is listed in the Posting"
                                + " Monitor.",
                        Obj.of("kind", KIND, "variance", result.get("variance"), "subledgerValue",
                                result.get("subledgerValue"), "glBalance", result.get("glBalance"), "href",
                                "/inventory-gl-tieout"),
                        KIND + ":" + LocalDate.now(ZoneOffset.UTC));
            } catch (RuntimeException e) {
                log.error("[gl-tieout] failed to send the variance alert: {}", e.toString());
            }
        }
        return result;
    }
}
