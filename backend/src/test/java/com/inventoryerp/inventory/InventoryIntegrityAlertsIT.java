package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.InventoryIntegrityMonitor;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.health.BacklogMetrics;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR T08 (audit S-3): system monitors used to write in-app notifications without a user, which no inbox returns.
 * The integrity alert now reaches every user holding the integrity report's permission (through
 * {@code GET /notifications}), never a user without it, and only once per user per finding per day. Also covers the
 * stock-posting and integrity gauges served to Prometheus.
 */
class InventoryIntegrityAlertsIT extends InventoryAuditSupport {

    private static final String TITLE = "Inventory integrity check failed";

    @Autowired
    InventoryIntegrityMonitor monitor;

    @Autowired
    NotificationsService notifications;

    @Autowired
    BacklogMetrics backlog;

    @Autowired
    MeterRegistry registry;

    /** A user of {@code o} whose only role holds {@code permissions}; returns {@code [userId, token]}. */
    String[] userHolding(AuditOrg o, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, o.id(), "R-" + role, permissions).update();
        String email = "t08-" + UUID.randomUUID().toString().substring(0, 8) + "@audit.test";
        String id = createUser(o.id(), email, "T08@12345", role);
        return new String[] {id, login(o.code(), email, "T08@12345")};
    }

    /** The integrity alerts in the inbox of the token's user. */
    List<JsonNode> integrityAlerts(String token) {
        Res r = call("GET", "/notifications?pageSize=100", null, token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        r.body().get("data").forEach(n -> {
            if (TITLE.equals(n.get("title").asString())) {
                out.add(n);
            }
        });
        return out;
    }

    JsonNode payload(JsonNode notification) {
        JsonNode p = notification.get("payload");
        return p.isString() ? mapper.readTree(p.asString()) : p;
    }

    /** One isolated org whose ledger and quant disagree for one product. */
    AuditOrg brokenOrg() {
        AuditOrg o = newOrg();
        String p = product(o, "T08", Map.of());
        Res in = call("POST", "/inventory/direct-stock/in", Map.of("locationId", o.main(), "responsibleById",
                o.userId(),
                "approvedById", o.userId(), "items", List.of(item(p, 10, 5))), o.token());
        assertThat(in.status()).as(in.raw().body()).isEqualTo(200);
        jdbc.sql("UPDATE \"StockItem\" SET quantity = quantity + 1 WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(o.id(), p).update();
        return o;
    }

    @Test
    @DisplayName("the integrity alert reaches every holder of inventory_valuation:read, nobody else, once a day")
    void integrityAlertReachesPermissionHolders() {
        AuditOrg o = brokenOrg();
        String[] holder = userHolding(o, Permissions.INVENTORY_VALUATION__READ);
        String[] outsider = userHolding(o, Permissions.INVENTORY__READ);
        String[] inactive = userHolding(o, Permissions.INVENTORY_VALUATION__READ);
        jdbc.sql("UPDATE \"User\" SET \"isActive\" = false WHERE id = ?").param(inactive[0]).update();
        TenantStore store = TenantStore.organization(o.id());

        InventoryIntegrityMonitor.Result first = TenantContext.run(store, monitor::check);
        assertThat(first.raised()).contains("integrity_ledger_vs_quant");

        List<JsonNode> got = integrityAlerts(holder[1]);
        assertThat(got).hasSize(1);
        JsonNode p = payload(got.getFirst());
        assertThat(p.get("kind").asString()).isEqualTo("inventory_integrity");
        assertThat(p.get("dedupeKey").asString()).startsWith("inventory_integrity:")
                .contains("integrity_ledger_vs_quant");
        assertThat(got.getFirst().get("status").asString()).isEqualTo("sent");
        assertThat(integrityAlerts(o.token())).as("the org admin holds every permission").hasSize(1);
        assertThat(integrityAlerts(outsider[1])).isEmpty();
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ?", inactive[0])).isZero();
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND \"userId\" IS NULL",
                o.id())).as("no inbox-less row any more").isZero();

        // Resolve and re-raise the same finding on the same day: a new exception, no second bell.
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE \"organizationId\" = ? AND kind LIKE"
                + " 'integrity\\_%'").param(o.id()).update();
        assertThat(TenantContext.run(store, monitor::check).raised()).isEqualTo(first.raised());
        assertThat(integrityAlerts(holder[1])).hasSize(1);
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title = ?", o.id(),
                TITLE)).as("admin + holder").isEqualTo(2);
    }

    @Test
    @DisplayName("sendToPermission: dedupe per user, a later holder still gets the alert, no holder sends nothing")
    void sendToPermissionDedupesPerUser() {
        AuditOrg o = newOrg();
        String perm = Permissions.INVENTORY_VALUATION__READ;
        String key = "t08-probe:" + UUID.randomUUID();
        assertThat(notifications.sendToPermission(o.id(), perm, "inventory", "Probe", "body", Map.of("a", 1), key))
                .as("the org admin").isEqualTo(1);
        assertThat(notifications.sendToPermission(o.id(), perm, "inventory", "Probe", "body", Map.of("a", 1), key))
                .isZero();
        String[] late = userHolding(o, perm);
        assertThat(notifications.sendToPermission(o.id(), perm, "inventory", "Probe", "body", null, key)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'dedupeKey' = ?",
                late[0], key)).isEqualTo(1);
        assertThat(notifications.sendToPermission(o.id(), "no_such:permission", "inventory", "Probe", "body", null,
                key)).isZero();
    }

    @Test
    @DisplayName("gauges: open integrity findings per kind; failed jobs apart; pending excludes failed/abandoned")
    void gauges() {
        AuditOrg o = brokenOrg();
        TenantContext.run(TenantStore.organization(o.id()), monitor::check);
        backlog.refreshIntegrityFindings();
        Gauge open = registry.find("erp.inventory.integrity.open").tag("kind", "integrity_ledger_vs_quant").gauge();
        assertThat(open).isNotNull();
        long dbOpen = count("SELECT count(*) FROM \"InventoryException\" WHERE status = 'open' AND kind ="
                + " 'integrity_ledger_vs_quant'");
        assertThat(open.value()).isEqualTo((double) dbOpen).isGreaterThanOrEqualTo(1);

        Gauge failed = registry.find("erp.stock.posting.jobs.failed").gauge();
        Gauge pending = registry.find("erp.stock.posting.jobs.pending").gauge();
        assertThat(failed).isNotNull();
        assertThat(pending).isNotNull();
        double failedBefore = failed.value();
        double pendingBefore = pending.value();
        List<String> jobs = new ArrayList<>();
        try {
            for (String status : List.of("failed", "abandoned")) {
                String id = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"StockPostingJob\" (id, \"organizationId\", status, attempts, \"lastError\","
                        + " \"idempotencyKey\") VALUES (?, ?, ?, 5, 'T08 probe', ?)").params(id, o.id(), status,
                        "t08-probe:" + id).update();
                jobs.add(id);
            }
            assertThat(failed.value()).isEqualTo(failedBefore + 1);
            assertThat(pending.value()).isEqualTo(pendingBefore);
            assertThat(pending.value()).isEqualTo((double) count("SELECT count(*) FROM \"StockPostingJob\" WHERE"
                    + " status NOT IN ('done', 'failed', 'abandoned')"));
        } finally {
            jdbc.sql("DELETE FROM \"StockPostingJob\" WHERE id IN (:ids)").param("ids", jobs).update();
        }

        // A cleared kind keeps reporting 0 instead of vanishing (a probe kind of this org only).
        String probe = "integrity_t08probe_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                + " status, \"createdAt\") VALUES (?, ?, 'probe', 0, ?, 'probe', 'open', now())")
                .params(UUID.randomUUID().toString(), o.id(), probe).update();
        backlog.refreshIntegrityFindings();
        assertThat(registry.find("erp.inventory.integrity.open").tag("kind", probe).gauge().value()).isEqualTo(1.0);
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE kind = ?").param(probe).update();
        backlog.refreshIntegrityFindings();
        assertThat(registry.find("erp.inventory.integrity.open").tag("kind", probe).gauge().value()).isZero();
    }
}
