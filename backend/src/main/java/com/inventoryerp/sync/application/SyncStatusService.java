package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-S083 S9: the sync status dashboard ({@code GET /sync/status}). What the server knows about its devices and branch
 * nodes: when each last synced, ops applied today, dead letters, and the last post-restore reconciliation. Ops still
 * queued on a device are only known to that device (the web page adds this browser's own queue).
 */
@Service
public class SyncStatusService {

    /** A device not heard from for this long is "stale" on the dashboard and in {@code erp.sync.devices.stale}. */
    public static final Duration STALE = Duration.ofHours(24);

    /** A device heard from within this window is "online". */
    public static final Duration ONLINE = Duration.ofMinutes(15);

    private final JdbcClient jdbc;

    public SyncStatusService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The dashboard for the caller's organisation. */
    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        String org = TenantContext.organizationId();
        Instant now = Instant.now();
        List<Map<String, Object>> devices = SqlRows.list(jdbc.sql("""
                SELECT d.id, d.name, d.platform, d."branchId", b.name AS "branchName", d."serverId",
                       d."protocolVersion", d."lastSeenAt", d."lastPullAt", d."lastPushSeq", d."revokedAt",
                       d."tokenExpiresAt", d."createdAt",
                       (SELECT count(*) FROM "SyncOpDeadLetter" x WHERE x."organizationId" = d."organizationId"
                          AND x."deviceId" = d.id AND x.status IN ('open', 'investigating')) AS "openDeadLetters",
                       (SELECT count(*) FROM "SyncAppliedOp" a WHERE a."organizationId" = d."organizationId"
                          AND a."deviceId" = d.id AND a."committedAt" >= date_trunc('day', now())) AS "opsToday"
                FROM "PosDevice" d
                LEFT JOIN "Branch" b ON b.id = d."branchId" AND b."organizationId" = d."organizationId"
                WHERE d."organizationId" = ?
                ORDER BY d."revokedAt" NULLS FIRST, coalesce(d."lastSeenAt", d."createdAt") DESC""").param(org));
        int active = 0;
        int stale = 0;
        int online = 0;
        for (Map<String, Object> d : devices) {
            Instant last = latest(d.get("lastSeenAt"), d.get("lastPullAt"));
            d.put("lastSyncAt", last);
            String state;
            if (d.get("revokedAt") != null) {
                state = "revoked";
            } else if (last == null) {
                state = "never";
            } else if (Duration.between(last, now).compareTo(ONLINE) <= 0) {
                state = "online";
            } else if (Duration.between(last, now).compareTo(STALE) < 0) {
                state = "idle";
            } else {
                state = "stale";
            }
            d.put("state", state);
            d.put("lagSeconds", last == null ? null : Duration.between(last, now).toSeconds());
            if (!"revoked".equals(state)) {
                active++;
                if ("stale".equals(state) || "never".equals(state)) {
                    stale++;
                }
                if ("online".equals(state)) {
                    online++;
                }
            }
        }

        Map<String, Object> ops = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                SELECT state, count(*) AS n FROM "SyncAppliedOp"
                WHERE "organizationId" = ? AND "committedAt" >= date_trunc('day', now()) GROUP BY state""").param(org))) {
            ops.put(String.valueOf(r.get("state")), ((Number) r.get("n")).longValue());
        }
        long failedToday = jdbc.sql("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE \"organizationId\" = ?"
                + " AND \"createdAt\" >= date_trunc('day', now())").param(org).query(Long.class).single();

        List<Map<String, Object>> deadLetters = SqlRows.list(jdbc.sql("""
                SELECT "failureCode", status, count(*) AS n FROM "SyncOpDeadLetter"
                WHERE "organizationId" = ? AND status IN ('open', 'investigating')
                GROUP BY "failureCode", status ORDER BY n DESC, "failureCode" """).param(org));
        long openDeadLetters = deadLetters.stream().mapToLong(r -> ((Number) r.get("n")).longValue()).sum();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("devices", active);
        summary.put("online", online);
        summary.put("stale", stale);
        summary.put("revoked", devices.size() - active);
        summary.put("appliedToday", ops.values().stream().mapToLong(v -> (Long) v).sum());
        summary.put("failedToday", failedToday);
        summary.put("openDeadLetters", openDeadLetters);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);
        out.put("summary", summary);
        out.put("opsToday", ops);
        out.put("deadLetters", deadLetters);
        out.put("branches", branches(devices));
        out.put("devices", devices);
        out.put("protocolVersions", protocolVersions(devices));
        out.put("reconciliation", SqlRows.one(jdbc.sql("""
                SELECT id, trigger, status, "issueCount", "startedAt", "finishedAt" FROM "SyncReconciliationRun"
                WHERE "organizationId" = ? ORDER BY "startedAt" DESC LIMIT 1""").param(org)));
        return out;
    }

    /** One tile per branch with devices: its relay node (if any) and its tills. */
    static List<Map<String, Object>> branches(List<Map<String, Object>> devices) {
        Map<String, Map<String, Object>> tiles = new LinkedHashMap<>();
        for (Map<String, Object> d : devices) {
            if (d.get("branchId") == null || "revoked".equals(d.get("state"))) {
                continue;
            }
            Map<String, Object> t = tiles.computeIfAbsent((String) d.get("branchId"), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("branchId", id);
                m.put("branchName", d.get("branchName"));
                m.put("node", null);
                m.put("tills", 0);
                m.put("tillsOnline", 0);
                m.put("tillsStale", 0);
                m.put("opsToday", 0L);
                m.put("openDeadLetters", 0L);
                m.put("lastSyncAt", null);
                return m;
            });
            t.put("opsToday", (Long) t.get("opsToday") + ((Number) d.get("opsToday")).longValue());
            t.put("openDeadLetters", (Long) t.get("openDeadLetters") + ((Number) d.get("openDeadLetters")).longValue());
            Instant last = (Instant) d.get("lastSyncAt");
            if (last != null && (t.get("lastSyncAt") == null || last.isAfter((Instant) t.get("lastSyncAt")))) {
                t.put("lastSyncAt", last);
            }
            if ("branch".equals(d.get("platform"))) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("deviceId", d.get("id"));
                node.put("name", d.get("name"));
                node.put("serverId", d.get("serverId"));
                node.put("state", d.get("state"));
                node.put("lastSyncAt", last);
                node.put("protocolVersion", d.get("protocolVersion"));
                t.put("node", node);
            } else {
                t.put("tills", (Integer) t.get("tills") + 1);
                if ("online".equals(d.get("state"))) {
                    t.put("tillsOnline", (Integer) t.get("tillsOnline") + 1);
                }
                if ("stale".equals(d.get("state")) || "never".equals(d.get("state"))) {
                    t.put("tillsStale", (Integer) t.get("tillsStale") + 1);
                }
            }
        }
        return new ArrayList<>(tiles.values());
    }

    static List<Map<String, Object>> protocolVersions(List<Map<String, Object>> devices) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> d : devices) {
            if (!"revoked".equals(d.get("state"))) {
                counts.merge(d.get("protocolVersion") == null ? "unknown" : (String) d.get("protocolVersion"), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((v, n) -> out.add(new LinkedHashMap<>(Map.of("version", v, "devices", n))));
        return out;
    }

    private static Instant latest(Object a, Object b) {
        Instant x = a instanceof Instant i ? i : null;
        Instant y = b instanceof Instant i ? i : null;
        if (x == null) {
            return y;
        }
        return y == null || x.isAfter(y) ? x : y;
    }
}
