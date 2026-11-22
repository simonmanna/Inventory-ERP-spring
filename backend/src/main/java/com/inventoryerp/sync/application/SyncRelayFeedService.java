package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.sync.application.SyncPushService.Device;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-S083 O7 (HQ side): what a branch node needs back from HQ, on {@code GET /sync/relay/feed}.
 * <ul>
 *   <li>{@code ops}: the outcome of every op the branch relayed (ledger rows and dead letters of the branch device
 *       changed since the cursor), so the branch moves its outbox to {@code hq_posted} / {@code hq_rejected} even when
 *       a relay response was lost, and recovers its states after a restore;</li>
 *   <li>{@code maxDeviceSeq}: the highest relay sequence HQ holds for the branch (its relay watermark), so a branch
 *       restored from a backup never reuses one ({@code SEQ_REUSED});</li>
 *   <li>{@code devices}: the branch's tills with their token hashes, expiry and revocation, so the branch
 *       authenticates them itself. Tills the branch reports as {@code seen} have their token expiry renewed: they
 *       pull from the branch, which never rotates tokens.</li>
 * </ul>
 */
@Service
public class SyncRelayFeedService {

    static final long OVERLAP_MS = 5_000;
    static final int PAGE = 1000;
    static final String ISO = "to_char(%s, 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')";

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final SyncPolicyService policy;
    private final SyncLedger ledger;

    public SyncRelayFeedService(JdbcClient jdbc, JsonMapper mapper, SyncPolicyService policy, SyncLedger ledger) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.policy = policy;
        this.ledger = ledger;
    }

    /** True when the device is a branch node (platform {@code branch}). */
    @Transactional(readOnly = true)
    public boolean isBranch(String deviceId) {
        return "branch".equals(jdbc.sql("SELECT platform FROM \"PosDevice\" WHERE id = ?").param(deviceId)
                .query(String.class).optional().orElse(null));
    }

    /** The feed for {@code device}, which must be a branch node bound to a branch. */
    @Transactional
    public Map<String, Object> feed(Device device, String cursor, List<String> seen) {
        if (!isBranch(device.id())) {
            throw ApiException.forbidden("Only a branch server may read the relay feed");
        }
        String org = device.organizationId();
        Instant serverTime = Instant.now();
        Instant since = Instant.EPOCH;
        if (cursor != null && !cursor.isBlank()) {
            try {
                since = Instant.parse(cursor).minusMillis(OVERLAP_MS);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("Invalid relay feed cursor");
            }
        }
        if (!seen.isEmpty()) {
            jdbc.sql("UPDATE \"PosDevice\" SET \"lastSeenAt\" = now(), \"tokenExpiresAt\" = GREATEST(coalesce("
                    + "\"tokenExpiresAt\", now()), now() + make_interval(days => ?)) WHERE \"organizationId\" = ? AND"
                    + " id = ANY (?) AND \"branchId\" IS NOT DISTINCT FROM ? AND \"revokedAt\" IS NULL AND platform <> 'branch'")
                    .params(policy.tokenTtlDays(org), org, seen.toArray(new String[0]), device.branchId()).update();
        }
        String ledgerTs = "coalesce(\"completedAt\", \"committedAt\")";
        List<Map<String, Object>> applied = SqlRows.list(jdbc.sql("SELECT \"opId\", \"deviceSeq\", state, \"httpStatus\","
                + " \"resultJson\"::text AS result, " + ISO.formatted(ledgerTs) + " AS ts FROM \"SyncAppliedOp\""
                + " WHERE \"organizationId\" = ? AND \"deviceId\" = ? AND " + ledgerTs + " > ? ORDER BY " + ledgerTs
                + ", \"opId\" LIMIT " + PAGE).params(org, device.id(), SqlRows.ts(since)));
        String dlTs = "GREATEST(\"createdAt\", \"lastAttemptAt\", \"resolvedAt\", \"investigatingAt\", \"recoveredAt\")";
        List<Map<String, Object>> dead = SqlRows.list(jdbc.sql("SELECT \"opId\", \"deviceSeq\", status, \"failureCode\","
                + " error, \"httpStatus\", retryable, " + ISO.formatted(dlTs) + " AS ts FROM \"SyncOpDeadLetter\""
                + " WHERE \"organizationId\" = ? AND \"deviceId\" = ? AND " + dlTs + " > ? ORDER BY " + dlTs
                + ", \"opId\" LIMIT " + PAGE).params(org, device.id(), SqlRows.ts(since)));
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> r : applied) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("opId", r.get("opId"));
            o.put("deviceSeq", r.get("deviceSeq"));
            o.put("state", r.get("state"));
            if ("applied".equals(r.get("state"))) {
                o.put("result", pushResult((String) r.get("opId"), r.get("httpStatus"), (String) r.get("result")));
            }
            ops.add(o);
        }
        for (Map<String, Object> r : dead) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("opId", r.get("opId"));
            o.put("deviceSeq", r.get("deviceSeq"));
            o.put("state", "dead_letter");
            o.put("deadLetterStatus", r.get("status"));
            o.put("failureCode", r.get("failureCode"));
            o.put("error", r.get("error"));
            o.put("httpStatus", r.get("httpStatus"));
            o.put("retryable", Boolean.TRUE.equals(r.get("retryable")));
            ops.add(o);
        }
        boolean more = applied.size() == PAGE || dead.size() == PAGE;
        String next = JsonConfig.toJsIso(serverTime);
        if (more) {
            String a = applied.size() == PAGE ? (String) applied.getLast().get("ts") : null;
            String d = dead.size() == PAGE ? (String) dead.getLast().get("ts") : null;
            next = a == null ? d : d == null ? a : (a.compareTo(d) < 0 ? a : d);
        }
        Integer maxSeq = jdbc.sql("SELECT GREATEST((SELECT max(\"deviceSeq\") FROM \"SyncAppliedOp\" WHERE"
                + " \"organizationId\" = ? AND \"deviceId\" = ?), (SELECT max(\"deviceSeq\") FROM \"SyncOpDeadLetter\" WHERE"
                + " \"organizationId\" = ? AND \"deviceId\" = ?))").params(org, device.id(), org, device.id())
                .query(Integer.class).optional().orElse(null);
        List<Map<String, Object>> devices = SqlRows.list(jdbc.sql("SELECT id, name, platform, \"branchId\", \"tokenHash\","
                + " prefix, \"registeredById\", " + ISO.formatted("\"revokedAt\"") + " AS \"revokedAt\", "
                + ISO.formatted("\"tokenExpiresAt\"") + " AS \"tokenExpiresAt\", \"previousTokenHash\", "
                + ISO.formatted("\"previousTokenValidUntil\"") + " AS \"previousTokenValidUntil\", \"revokedAtSeq\","
                + " \"offlineWindowHours\", " + ISO.formatted("\"createdAt\"") + " AS \"createdAt\" FROM \"PosDevice\""
                + " WHERE \"organizationId\" = ? AND \"branchId\" IS NOT DISTINCT FROM ? AND platform <> 'branch'"
                + " ORDER BY id").params(org, device.branchId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", device.id());
        out.put("branchId", device.branchId());
        out.put("serverId", ledger.serverId());
        out.put("devices", devices);
        out.put("ops", ops);
        out.put("maxDeviceSeq", maxSeq == null ? 0 : maxSeq);
        out.put("cursor", next);
        out.put("hasMore", more);
        out.put("serverTime", JsonConfig.toJsIso(serverTime));
        return out;
    }

    /** A ledger result in the shape {@code /sync/push} returned it (mapping, final numbers, outcome). */
    private Map<String, Object> pushResult(String opId, Object httpStatus, String resultJson) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", opId);
        r.put("status", "applied");
        r.put("httpStatus", httpStatus == null ? 201 : httpStatus);
        r.put("error", null);
        if (resultJson != null) {
            Object body = mapper.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(resultJson);
            if (body instanceof Map<?, ?> m) {
                for (String k : List.of("mapping", "finalNumbers", "outcome")) {
                    if (m.get(k) != null) {
                        r.put(k, m.get(k));
                    }
                }
            }
        }
        return r;
    }
}
