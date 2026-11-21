package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.sync.application.SyncPushService.Device;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * ADR-S083 C5: resumable, verifiable first sync. A device opens a snapshot pinned to {@code snapshotAt}, downloads
 * each scope in keyset pages ({@code id > after}), verifies each page's checksum, and completes the snapshot with
 * per-scope row counts and a combined checksum. Only after the server acknowledges completion does it receive the
 * delta cursor (watermarks = {@code snapshotAt}) and activate the downloaded data — a partial snapshot never replaces
 * the active dataset. Rows changed or deleted while the snapshot downloads come back through the deltas that start at
 * {@code snapshotAt} (5-second overlap, tombstones on protocol 2), so the end state is consistent.
 */
@Service
public class SyncSnapshotService {

    public static final int PAGE_SIZE = 500;
    public static final Duration TTL = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final SyncPullService pull;

    public SyncSnapshotService(JdbcClient jdbc, SyncPullService pull) {
        this.jdbc = jdbc;
        this.pull = pull;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Page checksum: sha256 of the page's rows as served (JSON). */
    public static String pageChecksum(List<Map<String, Object>> rows) {
        return sha256(EntityJson.toJson(rows));
    }

    /** Scope checksum: sha256 of the scope's page checksums joined by commas, in page order. */
    public static String scopeChecksum(List<String> pageChecksums) {
        return sha256(String.join(",", pageChecksums));
    }

    public Map<String, Object> create(Device device, String scopesRaw) {
        List<String> scopes = new ArrayList<>(SyncPullService.parseScopes(scopesRaw));
        scopes.remove("messages");
        if (scopes.isEmpty()) {
            throw ApiException.badRequest("A snapshot needs at least one scope other than messages");
        }
        Instant at = Instant.now();
        Instant expires = at.plus(TTL);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SyncSnapshot\" (id, \"organizationId\", \"deviceId\", \"snapshotAt\", \"expiresAt\", scopes,"
                        + " status) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'open')")
                .params(id, device.organizationId(), device.id(), SqlRows.ts(at), SqlRows.ts(expires),
                        EntityJson.toJson(scopes))
                .update();
        Map<String, Object> pages = new LinkedHashMap<>();
        int total = 0;
        for (String scope : scopes) {
            int rows = pull.snapshotRows(scope).size();
            int n = Math.max(1, (rows + PAGE_SIZE - 1) / PAGE_SIZE);
            pages.put(scope, n);
            total += n;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", id);
        out.put("snapshotAt", JsonConfig.toJsIso(at));
        out.put("expiresAt", JsonConfig.toJsIso(expires));
        out.put("scopes", scopes);
        out.put("pageSize", PAGE_SIZE);
        out.put("pages", pages);
        out.put("pageCount", total);
        return out;
    }

    private Map<String, Object> openSnapshot(Device device, String id) {
        Map<String, Object> snap = SqlRows.one(jdbc.sql("SELECT id, \"snapshotAt\", \"expiresAt\", scopes::text AS scopes,"
                        + " status FROM \"SyncSnapshot\" WHERE id = ? AND \"organizationId\" = ? AND \"deviceId\" = ?")
                .params(id, device.organizationId(), device.id()));
        if (snap == null) {
            throw ApiException.notFound("Snapshot not found");
        }
        if (((Instant) snap.get("expiresAt")).isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "Snapshot expired — start a new one");
        }
        if (!"open".equals(snap.get("status"))) {
            throw ApiException.conflict("Snapshot already completed");
        }
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopesOf(Map<String, Object> snap) {
        Object parsed = PosSql.java(EntityJson.tree(String.valueOf(snap.get("scopes"))));
        return parsed instanceof List<?> l ? (List<String>) l : List.of();
    }

    public Map<String, Object> page(Device device, String id, String scope, String afterRaw) {
        Map<String, Object> snap = openSnapshot(device, id);
        if (scope == null || !scopesOf(snap).contains(scope)) {
            throw ApiException.badRequest("Scope " + scope + " is not part of this snapshot");
        }
        String after = afterRaw == null ? "" : afterRaw;
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean more = false;
        for (Map<String, Object> r : pull.snapshotRows(scope)) {
            if (String.valueOf(r.get("id")).compareTo(after) <= 0 && !after.isEmpty()) {
                continue;
            }
            if (rows.size() == PAGE_SIZE) {
                more = true;
                break;
            }
            rows.add(r);
        }
        String next = more ? String.valueOf(rows.getLast().get("id")) : null;
        String checksum = pageChecksum(rows);
        jdbc.sql("INSERT INTO \"SyncSnapshotPage\" (id, \"organizationId\", \"snapshotId\", scope, \"afterKey\", \"nextAfter\","
                        + " \"rowCount\", checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"snapshotId\", scope, \"afterKey\")"
                        + " DO UPDATE SET \"nextAfter\" = EXCLUDED.\"nextAfter\", \"rowCount\" = EXCLUDED.\"rowCount\","
                        + " checksum = EXCLUDED.checksum, \"createdAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), device.organizationId(), id, scope, after, next, rows.size(), checksum)
                .update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", id);
        out.put("scope", scope);
        out.put("after", after);
        out.put("rows", rows);
        out.put("nextAfter", next);
        out.put("hasMore", more);
        out.put("pageChecksum", checksum);
        return out;
    }

    /**
     * Verify the device holds every page of every scope (row counts + combined checksum along the served page chain)
     * and acknowledge with the delta cursor. Nothing is activated server-side; the acknowledgement is the client's
     * signal that it may swap its staged data in.
     */
    public Map<String, Object> complete(Device device, String id, Map<String, Map<String, Object>> claimed) {
        Map<String, Object> snap = openSnapshot(device, id);
        List<String> scopes = scopesOf(snap);
        List<String> problems = new ArrayList<>();
        for (String scope : scopes) {
            Map<String, Object> c = claimed == null ? null : claimed.get(scope);
            if (c == null) {
                problems.add(scope + ": not reported");
                continue;
            }
            Map<String, Map<String, Object>> byAfter = new LinkedHashMap<>();
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT \"afterKey\", \"nextAfter\", \"rowCount\", checksum"
                    + " FROM \"SyncSnapshotPage\" WHERE \"snapshotId\" = ? AND scope = ?").params(id, scope))) {
                byAfter.put(String.valueOf(p.get("afterKey")), p);
            }
            List<String> sums = new ArrayList<>();
            long count = 0;
            String key = "";
            boolean complete = false;
            for (int guard = 0; guard < 100_000; guard++) {
                Map<String, Object> p = byAfter.get(key);
                if (p == null) {
                    break;
                }
                sums.add(String.valueOf(p.get("checksum")));
                count += ((Number) p.get("rowCount")).longValue();
                if (p.get("nextAfter") == null) {
                    complete = true;
                    break;
                }
                key = String.valueOf(p.get("nextAfter"));
            }
            if (!complete) {
                problems.add(scope + ": not every page was downloaded");
                continue;
            }
            Object rowCount = c.get("rowCount");
            if (!(rowCount instanceof Number n) || n.longValue() != count) {
                problems.add(scope + ": row count " + rowCount + " does not match " + count);
            }
            if (!scopeChecksum(sums).equals(c.get("checksum"))) {
                problems.add(scope + ": checksum mismatch");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.conflict("Snapshot verification failed — download the listed scopes again: "
                    + String.join("; ", problems));
        }
        jdbc.sql("UPDATE \"SyncSnapshot\" SET status = 'completed', \"completedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .param(id).update();
        Instant at = (Instant) snap.get("snapshotAt");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", id);
        out.put("status", "completed");
        out.put("snapshotAt", JsonConfig.toJsIso(at));
        out.put("cursor", SyncPullService.cursorAt(scopes, at));
        return out;
    }
}
