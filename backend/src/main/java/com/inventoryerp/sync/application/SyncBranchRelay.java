package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.sync.application.SyncBranch.HqResponse;
import com.inventoryerp.sync.application.SyncBranch.HqUnreachable;
import com.inventoryerp.sync.application.SyncPushService.Device;
import com.inventoryerp.sync.application.SyncPushService.Op;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-S083 O7: the branch node's relay. A till's push is committed to the relay outbox ({@code accepted_local}) and
 * then forwarded to HQ at once when HQ answers (the till gets HQ's own result: mapping, final numbers, rejections);
 * otherwise the till is told the branch holds the op ({@code status applied}, {@code relayState accepted_local}) and the
 * relay job forwards it later ({@code pending_hq} → {@code hq_posted} / {@code hq_rejected}). Ops reach HQ in the order
 * the branch accepted them, under their original opIds, with the branch's own relay sequence and the till recorded in
 * {@code originChain}; HQ dedups by its ledger and posts GL. Master data and device identity come down from HQ
 * ({@link SyncBranchMirror}, {@code /sync/relay/feed}).
 */
@Service
public class SyncBranchRelay {

    private static final Logger log = LoggerFactory.getLogger(SyncBranchRelay.class);
    static final int BATCH = 100;
    static final Set<String> MONEY_OPS = Set.of("sale.checkout", "tab.settle", "sale.refund", "sale.void",
            "cash_session.movement", "expense.create", "payment.receive", "cash_drawer.open");

    private final SyncBranch branch;
    private final SyncBranchMirror mirror;
    private final JdbcClient jdbc;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final ReentrantLock relayLock = new ReentrantLock();
    private volatile boolean feedOk;

    public SyncBranchRelay(SyncBranch branch, SyncBranchMirror mirror, JdbcClient jdbc, IdempotencyService idempotency,
            PlatformTransactionManager transactions) {
        this.branch = branch;
        this.mirror = mirror;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.tx = new TransactionTemplate(transactions);
    }

    private <T> T inTx(Supplier<T> body) {
        return TenantContext.run(TenantStore.organization(branch.organizationId()), () -> tx.execute(s -> body.get()));
    }

    private void ensureState() {
        jdbc.sql("INSERT INTO \"SyncBranchState\" (\"organizationId\") VALUES (?) ON CONFLICT DO NOTHING")
                .param(branch.organizationId()).update();
    }

    /**
     * Forget that HQ's feed was read, as a restart does: the next relay reads HQ's relay watermark before it sends.
     * Call after restoring the branch database under a running process.
     */
    public void markRestored() {
        feedOk = false;
    }

    // ---------------------------------------------------------------- accept (till → branch)

    /** A till's push at the branch: every op is committed to the outbox before the till is answered. */
    public Map<String, Object> accept(Device device, List<Op> opsIn) {
        List<Op> ops = new ArrayList<>(opsIn);
        ops.sort(java.util.Comparator.comparingInt(Op::deviceSeq));
        Map<String, Map<String, Object>> refused = new LinkedHashMap<>();
        Map<String, Boolean> replay = new LinkedHashMap<>();
        boolean fresh = false;
        int lastSeq = 0;
        for (Op op : ops) {
            String outcome;
            try {
                outcome = inTx(() -> acceptOne(device, op, refused));
            } catch (DuplicateKeyException e) {
                outcome = "replay"; // the same opId arrived concurrently: answered from the stored row
            }
            replay.put(op.opId(), "replay".equals(outcome));
            fresh |= "new".equals(outcome);
            if (!refused.containsKey(op.opId())) {
                lastSeq = Math.max(lastSeq, op.deviceSeq());
            }
        }
        if (fresh) {
            relayPending(false, Duration.ofSeconds(2));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Op op : ops) {
            Map<String, Object> r = refused.get(op.opId());
            results.add(r != null ? r : inTx(() -> resultFor(op, Boolean.TRUE.equals(replay.get(op.opId())))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("lastPushSeq", lastSeq);
        out.put("serverTime", JsonConfig.toJsIso(Instant.now()));
        return out;
    }

    /** "new", "replay" or "refused" (answered without storing: {@code refused} holds the result). */
    private String acceptOne(Device device, Op op, Map<String, Map<String, Object>> refused) {
        String org = branch.organizationId();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", op.type());
        canonical.put("payload", SyncPushService.canonical(op.payload()));
        String hash = idempotency.hashPayload(canonical);
        Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT \"payloadHash\" FROM \"SyncRelayOutbox\" WHERE"
                + " \"organizationId\" = ? AND \"opId\" = ?").params(org, op.opId()));
        if (existing != null) {
            if (!hash.equals(existing.get("payloadHash"))) {
                refused.put(op.opId(), failed(op, 409, "Operation id " + op.opId() + " was already used for a different"
                        + " operation", "OP_ID_REUSED"));
                return "refused";
            }
            return "replay";
        }
        Map<String, Object> payload = op.payload() == null ? Map.of() : op.payload();
        String session = str(payload.get("cashSessionId")) != null ? str(payload.get("cashSessionId"))
                : str(payload.get("sessionId")) != null ? str(payload.get("sessionId"))
                : "cash_session.open".equals(op.type()) ? str(payload.get("clientId")) : null;
        String hold = device.cutoffCode();
        if (hold == null && device.strictSeq() && jdbc.sql("SELECT count(*) FROM \"SyncRelayOutbox\" WHERE"
                + " \"organizationId\" = ? AND \"deviceId\" = ? AND \"deviceSeq\" = ? AND \"opId\" <> ?")
                .params(org, device.id(), op.deviceSeq(), op.opId()).query(Long.class).single() > 0) {
            hold = "SEQ_REUSED";
        }
        if (hold == null && "cash_session.close".equals(op.type()) && session != null) {
            // C3: a branch shift closes while ops are pending HQ, never over money ops HQ rejected (still open there).
            long rejected = jdbc.sql("SELECT count(*) FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND"
                    + " \"cashSessionId\" = ? AND \"relayState\" = 'hq_rejected' AND \"opType\" = ANY (?) AND"
                    + " coalesce(\"hqDeadLetterStatus\", 'open') IN ('open', 'investigating')")
                    .params(org, session, MONEY_OPS.toArray(new String[0])).query(Long.class).single();
            if (rejected > 0) {
                refused.put(op.opId(), failed(op, 409, "This shift has " + rejected + " rejected sync operation(s)"
                        + " involving money. Resolve them under Sync dead letters, or close with a supervisor override and"
                        + " a reason.", "SYNC_DEAD_LETTERS_OPEN"));
                return "refused";
            }
        }
        List<String> chain = new ArrayList<>(op.originChain() == null ? List.of() : op.originChain());
        if (!chain.contains("device:" + device.id())) {
            chain.add("device:" + device.id());
        }
        ensureState();
        int relaySeq = jdbc.sql("UPDATE \"SyncBranchState\" SET \"relaySeq\" = GREATEST(\"relaySeq\", \"hqSeqHighWater\")"
                + " + 1, \"updatedAt\" = now() WHERE \"organizationId\" = ? RETURNING \"relaySeq\"").param(org)
                .query(Integer.class).single();
        jdbc.sql("INSERT INTO \"SyncRelayOutbox\" (id, \"organizationId\", \"opId\", \"payloadHash\", \"relaySeq\","
                + " \"deviceId\", \"deviceSeq\", \"opType\", \"actorUserId\", \"occurredAt\", payload, \"originChain\","
                + " \"supersedesOpId\", \"holdCode\", \"cashSessionId\", \"relayState\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?::jsonb, ?::jsonb, ?, ?, ?, 'accepted_local')")
                .params(UUID.randomUUID().toString(), org, op.opId(), hash, relaySeq, device.id(), op.deviceSeq(),
                        op.type(), op.actorUserId(), op.occurredAt(), EntityJson.toJson(payload),
                        EntityJson.toJson(chain), op.supersedesOpId(), hold, session)
                .update();
        return "new";
    }

    /** What the till is told about a stored op, from its outbox row. */
    private Map<String, Object> resultFor(Op op, boolean replay) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT \"relayState\", \"holdCode\", \"hqResult\"::text AS"
                + " result FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND \"opId\" = ?")
                .params(branch.organizationId(), op.opId()));
        String state = (String) row.get("relayState");
        if (("hq_posted".equals(state) || "hq_rejected".equals(state)) && row.get("result") != null) {
            Map<String, Object> r = new LinkedHashMap<>(mirror.parse((String) row.get("result")));
            r.put("opId", op.opId());
            if (replay && "hq_posted".equals(state)) {
                r.put("status", "replayed");
            }
            r.put("relayState", state);
            return r;
        }
        String hold = (String) row.get("holdCode");
        if (hold != null) {
            Map<String, Object> r = failed(op, "SEQ_REUSED".equals(hold) ? 409 : 403, holdMessage(hold, op), hold);
            r.put("relayState", state);
            return r;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("status", replay ? "replayed" : "applied");
        r.put("httpStatus", 202);
        r.put("error", null);
        r.put("relayState", state);
        return r;
    }

    static String holdMessage(String code, Op op) {
        return switch (code) {
            case "DEVICE_REVOKED" -> "This device was revoked; operations received after that are held for supervisor"
                    + " review";
            case "DEVICE_TOKEN_EXPIRED" -> "This device's token expired; operations received after that are held for"
                    + " supervisor review";
            default -> "Device sequence " + op.deviceSeq() + " was already used by another operation of this device; a"
                    + " rejected operation is corrected with a new operation (supersedesOpId), never re-sent under a new id";
        };
    }

    private static Map<String, Object> failed(Op op, int status, String error, String code) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("status", "failed");
        r.put("httpStatus", status);
        r.put("error", error);
        r.put("code", code);
        r.put("retryable", false);
        return r;
    }

    // ---------------------------------------------------------------- relay (branch → HQ)

    /** One job cycle: outcomes and devices from HQ, then pending ops, then the master-data mirror when due. */
    public void cycle() {
        try {
            feed();
            // HQ answers again: ops that never reached it go now, not after their backoff.
            inTx(() -> jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"nextAttemptAt\" = NULL WHERE \"organizationId\" = ? AND"
                    + " \"relayState\" IN ('accepted_local', 'pending_hq') AND \"hqStatus\" IS NULL")
                    .param(branch.organizationId()).update());
        } catch (RuntimeException e) {
            recordError("Relay feed: " + e.getMessage());
        }
        relayPending(false, Duration.ofSeconds(30));
        Instant last = inTx(() -> {
            ensureState();
            return jdbc.sql("SELECT \"lastPullAt\" FROM \"SyncBranchState\" WHERE \"organizationId\" = ?")
                    .param(branch.organizationId()).query((rs, n) -> {
                        java.sql.Timestamp t = rs.getTimestamp(1);
                        return t == null ? null : t.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
                    }).optional().orElse(null);
        });
        if (last == null || last.plus(branch.pullInterval()).isBefore(Instant.now().plusSeconds(1))) {
            try {
                pullMirror();
            } catch (RuntimeException e) {
                recordError("Master-data pull: " + e.getMessage());
            }
        }
    }

    /** Forwards due outbox rows to HQ in acceptance order; returns quietly when HQ cannot be reached. */
    public void relayPending(boolean feedFirst, Duration wait) {
        try {
            if (!relayLock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (feedFirst || !feedOk) {
                feed(); // a restored branch learns HQ's relay watermark before it sends anything
            }
            for (int round = 0; round < 50; round++) {
                if (!relayBatch()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            recordError("Relay: " + e.getMessage());
        } finally {
            relayLock.unlock();
        }
    }

    private boolean relayBatch() {
        String org = branch.organizationId();
        List<Map<String, Object>> rows = inTx(() -> {
            ensureState();
            // Rows never sent from this database whose sequence HQ already holds (a restore rolled the counter back)
            // get a fresh one above HQ's watermark. A row sent before keeps its sequence: HQ knows it by its opId.
            for (Map<String, Object> stale : SqlRows.list(jdbc.sql("SELECT o.id FROM \"SyncRelayOutbox\" o JOIN"
                    + " \"SyncBranchState\" s ON s.\"organizationId\" = o.\"organizationId\" WHERE o.\"organizationId\" = ?"
                    + " AND o.\"relayState\" IN ('accepted_local', 'pending_hq') AND o.attempts = 0 AND o.\"relaySeq\" <="
                    + " s.\"hqSeqHighWater\" ORDER BY o.\"relaySeq\"").param(org))) {
                int seq = jdbc.sql("UPDATE \"SyncBranchState\" SET \"relaySeq\" = GREATEST(\"relaySeq\","
                        + " \"hqSeqHighWater\") + 1 WHERE \"organizationId\" = ? RETURNING \"relaySeq\"").param(org)
                        .query(Integer.class).single();
                jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relaySeq\" = ? WHERE id = ?").params(seq, stale.get("id"))
                        .update();
            }
            List<Map<String, Object>> due = SqlRows.list(jdbc.sql("SELECT id, \"opId\", \"relaySeq\", \"opType\","
                    + " \"actorUserId\", \"occurredAt\", payload::text AS payload, \"originChain\"::text AS chain,"
                    + " \"supersedesOpId\", \"holdCode\" FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND"
                    + " \"relayState\" IN ('accepted_local', 'pending_hq') AND (\"nextAttemptAt\" IS NULL OR"
                    + " \"nextAttemptAt\" <= now()) ORDER BY \"relaySeq\" LIMIT " + BATCH).param(org));
            for (Map<String, Object> r : due) {
                jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relayState\" = 'pending_hq', attempts = attempts + 1,"
                        + " \"lastAttemptAt\" = now() WHERE id = ?").param(r.get("id")).update();
            }
            return due;
        });
        if (rows.isEmpty()) {
            return false;
        }
        String deviceId = (String) mirror.meta("branchDeviceId");
        List<Map<String, Object>> ops = new ArrayList<>();
        int maxSeq = 0;
        for (Map<String, Object> r : rows) {
            List<Object> chain = new ArrayList<>(com.inventoryerp.pos.application.PosSql.list(
                    mirror.parse("{\"c\":" + r.get("chain") + "}").get("c")));
            chain.add("server:" + branch.serverId());
            if (r.get("holdCode") != null) {
                chain.add("hold:" + r.get("holdCode"));
            }
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("opId", r.get("opId"));
            op.put("deviceSeq", r.get("relaySeq"));
            op.put("type", r.get("opType"));
            op.put("actorUserId", r.get("actorUserId"));
            op.put("occurredAt", r.get("occurredAt"));
            op.put("payload", mirror.parse((String) r.get("payload")));
            op.put("originChain", chain);
            if (r.get("supersedesOpId") != null) {
                op.put("supersedesOpId", r.get("supersedesOpId"));
            }
            ops.add(op);
            maxSeq = Math.max(maxSeq, ((Number) r.get("relaySeq")).intValue());
        }
        HqResponse res;
        try {
            res = branch.post("/sync/push", Map.of("deviceId", deviceId == null ? "" : deviceId, "ops", ops), null);
        } catch (HqUnreachable e) {
            backoff(rows, e.getMessage());
            recordError(e.getMessage());
            return false;
        }
        Object resultsObj = res.map().get("results");
        if (res.status() != 201 || !(resultsObj instanceof List<?> results)) {
            String why = "HQ answered " + res.status() + " to the relay push" + (res.status() == 401
                    ? " (the branch token was refused)" : "") + ": " + abbreviate(String.valueOf(res.body()));
            backoff(rows, why);
            recordError(why);
            return false;
        }
        Map<String, Map<String, Object>> byOp = new LinkedHashMap<>();
        for (Object o : results) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                byOp.put(String.valueOf(m.get("opId")), mm);
            }
        }
        final int sent = maxSeq;
        boolean[] retry = {false};
        inTx(() -> {
            for (Map<String, Object> r : rows) {
                Map<String, Object> result = byOp.get(String.valueOf(r.get("opId")));
                if (result == null) {
                    retry[0] = true;
                    backoffOne(r.get("id"), "HQ returned no result for this op");
                    continue;
                }
                String status = String.valueOf(result.get("status"));
                if ("applied".equals(status) || "replayed".equals(status)) {
                    jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relayState\" = 'hq_posted', \"hqStatus\" = ?,"
                            + " \"hqHttpStatus\" = ?, \"hqResult\" = ?::jsonb, \"failureCode\" = NULL,"
                            + " \"failureMessage\" = NULL, \"nextAttemptAt\" = NULL, \"relayedAt\" = now() WHERE id = ?")
                            .params(status, num(result.get("httpStatus")), EntityJson.toJson(result), r.get("id"))
                            .update();
                } else if (Boolean.TRUE.equals(result.get("retryable"))) {
                    retry[0] = true;
                    jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"hqStatus\" = 'failed', \"hqHttpStatus\" = ?,"
                            + " \"failureCode\" = ?, \"failureMessage\" = ?, \"nextAttemptAt\" = now() + make_interval("
                            + "secs => LEAST(300, 2 * power(2, GREATEST(attempts - 1, 0)))) WHERE id = ?")
                            .params(num(result.get("httpStatus")), result.get("code"), result.get("error"), r.get("id"))
                            .update();
                } else {
                    Map<String, Object> stored = new LinkedHashMap<>(result);
                    stored.put("retryable", false);
                    jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relayState\" = 'hq_rejected', \"hqStatus\" = 'failed',"
                            + " \"hqHttpStatus\" = ?, \"hqResult\" = ?::jsonb, \"failureCode\" = ?, \"failureMessage\" = ?,"
                            + " \"hqDeadLetterStatus\" = 'open', \"nextAttemptAt\" = NULL, \"relayedAt\" = now() WHERE id = ?")
                            .params(num(result.get("httpStatus")), EntityJson.toJson(stored), result.get("code"),
                                    result.get("error"), r.get("id")).update();
                }
            }
            jdbc.sql("UPDATE \"SyncBranchState\" SET \"hqSeqHighWater\" = GREATEST(\"hqSeqHighWater\", ?),"
                    + " \"lastRelayAt\" = now(), \"lastHqContactAt\" = now(), \"lastError\" = NULL, \"updatedAt\" = now()"
                    + " WHERE \"organizationId\" = ?").params(sent, org).update();
            return null;
        });
        return !retry[0] && rows.size() == BATCH;
    }

    private static Integer num(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private void backoff(List<Map<String, Object>> rows, String why) {
        inTx(() -> {
            for (Map<String, Object> r : rows) {
                backoffOne(r.get("id"), why);
            }
            return null;
        });
    }

    /** Exponential backoff from the row's attempt count: 2 s doubling, at most 5 minutes. */
    private void backoffOne(Object id, String why) {
        jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"failureMessage\" = ?, \"nextAttemptAt\" = now() + make_interval(secs =>"
                + " LEAST(300, 2 * power(2, GREATEST(attempts - 1, 0)))) WHERE id = ?").params(abbreviate(why), id)
                .update();
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    private void recordError(String why) {
        log.warn("Branch relay: {}", why);
        try {
            inTx(() -> {
                ensureState();
                jdbc.sql("UPDATE \"SyncBranchState\" SET \"lastError\" = ?, \"lastErrorAt\" = now(), \"updatedAt\" = now()"
                        + " WHERE \"organizationId\" = ?").params(abbreviate(why), branch.organizationId()).update();
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Branch relay state update failed: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- feed + mirror (HQ → branch)

    /** Outcomes of relayed ops, HQ's relay watermark and the branch's devices, from {@code /sync/relay/feed}. */
    public void feed() {
        String org = branch.organizationId();
        for (int page = 0; page < 20; page++) {
            Map<String, Object> state = inTx(() -> {
                ensureState();
                return SqlRows.one(jdbc.sql("SELECT \"feedCursor\", \"lastFeedAt\" FROM \"SyncBranchState\" WHERE"
                        + " \"organizationId\" = ?").param(org));
            });
            List<String> seen = inTx(() -> jdbc.sql("SELECT d.id FROM \"PosDevice\" d, \"SyncBranchState\" s WHERE"
                    + " s.\"organizationId\" = ? AND d.\"organizationId\" = ? AND d.\"lastSeenAt\" IS NOT NULL AND"
                    + " (s.\"lastFeedAt\" IS NULL OR d.\"lastSeenAt\" > s.\"lastFeedAt\" - interval '1 minute')")
                    .params(org, org).query(String.class).list());
            StringBuilder path = new StringBuilder("/sync/relay/feed?");
            if (state.get("feedCursor") != null) {
                path.append("cursor=").append(enc((String) state.get("feedCursor"))).append('&');
            }
            if (!seen.isEmpty()) {
                path.append("seen=").append(enc(String.join(",", seen)));
            }
            HqResponse res = branch.get(path.toString());
            if (res.status() != 200) {
                throw new IllegalStateException("HQ answered " + res.status() + " to the relay feed: "
                        + abbreviate(String.valueOf(res.body())));
            }
            Map<String, Object> body = res.map();
            inTx(() -> {
                applyFeed(org, body);
                return null;
            });
            feedOk = true;
            if (!Boolean.TRUE.equals(body.get("hasMore"))) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyFeed(String org, Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        for (Object o : (List<Object>) body.getOrDefault("devices", List.of())) {
            Map<String, Object> d = (Map<String, Object>) o;
            ids.add((String) d.get("id"));
            jdbc.sql("INSERT INTO \"PosDevice\" (id, \"organizationId\", \"branchId\", name, platform, \"tokenHash\", prefix,"
                    + " \"registeredById\", \"revokedAt\", \"tokenExpiresAt\", \"previousTokenHash\","
                    + " \"previousTokenValidUntil\", \"revokedAtSeq\", \"offlineWindowHours\", \"createdAt\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp, ?::timestamp, ?, ?::timestamp, ?, ?, ?::timestamp, now())"
                    + " ON CONFLICT (id) DO UPDATE SET \"branchId\" = EXCLUDED.\"branchId\", name = EXCLUDED.name,"
                    + " platform = EXCLUDED.platform, \"tokenHash\" = EXCLUDED.\"tokenHash\", prefix = EXCLUDED.prefix,"
                    + " \"revokedAt\" = EXCLUDED.\"revokedAt\", \"tokenExpiresAt\" = EXCLUDED.\"tokenExpiresAt\","
                    + " \"previousTokenHash\" = EXCLUDED.\"previousTokenHash\", \"previousTokenValidUntil\" ="
                    + " EXCLUDED.\"previousTokenValidUntil\", \"revokedAtSeq\" = EXCLUDED.\"revokedAtSeq\","
                    + " \"offlineWindowHours\" = EXCLUDED.\"offlineWindowHours\", \"updatedAt\" = now()")
                    .params(d.get("id"), org, d.get("branchId"), d.get("name"), d.get("platform"), d.get("tokenHash"),
                            d.get("prefix"), d.get("registeredById"), ts(d.get("revokedAt")), ts(d.get("tokenExpiresAt")),
                            d.get("previousTokenHash"), ts(d.get("previousTokenValidUntil")), num(d.get("revokedAtSeq")),
                            num(d.get("offlineWindowHours")), ts(d.get("createdAt")))
                    .update();
        }
        // A till HQ no longer lists for this branch (moved, deleted) stops authenticating here.
        jdbc.sql("UPDATE \"PosDevice\" SET \"revokedAt\" = now(), \"updatedAt\" = now() WHERE \"organizationId\" = ? AND"
                + " \"revokedAt\" IS NULL AND NOT (id = ANY (?))").params(org, ids.toArray(new String[0])).update();
        for (Object o : (List<Object>) body.getOrDefault("ops", List.of())) {
            Map<String, Object> r = (Map<String, Object>) o;
            Object opId = r.get("opId");
            if ("applied".equals(r.get("state")) && r.get("result") != null) {
                jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relayState\" = 'hq_posted', \"hqStatus\" = 'applied',"
                        + " \"hqHttpStatus\" = ?, \"hqResult\" = ?::jsonb, \"failureCode\" = NULL, \"failureMessage\" = NULL,"
                        + " \"nextAttemptAt\" = NULL, \"relayedAt\" = coalesce(\"relayedAt\", now()) WHERE \"organizationId\""
                        + " = ? AND \"opId\" = ? AND \"relayState\" <> 'hq_posted'")
                        .params(num(((Map<String, Object>) r.get("result")).get("httpStatus")),
                                EntityJson.toJson(r.get("result")), org, opId).update();
            } else if ("dead_letter".equals(r.get("state")) && !Boolean.TRUE.equals(r.get("retryable"))) {
                String status = String.valueOf(r.get("deadLetterStatus"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("opId", opId);
                result.put("status", "failed");
                result.put("httpStatus", r.get("httpStatus"));
                result.put("error", r.get("error"));
                result.put("code", r.get("failureCode"));
                result.put("retryable", false);
                jdbc.sql("UPDATE \"SyncRelayOutbox\" SET \"relayState\" = CASE WHEN \"relayState\" = 'hq_posted' THEN"
                        + " \"relayState\" ELSE 'hq_rejected' END, \"hqResult\" = CASE WHEN \"relayState\" = 'hq_posted'"
                        + " THEN \"hqResult\" ELSE ?::jsonb END, \"failureCode\" = ?, \"failureMessage\" = ?,"
                        + " \"hqDeadLetterStatus\" = ?, \"nextAttemptAt\" = NULL WHERE \"organizationId\" = ? AND \"opId\" = ?")
                        .params(EntityJson.toJson(result), r.get("failureCode"), r.get("error"), status, org, opId)
                        .update();
            }
        }
        Map<String, Object> metaPatch = new LinkedHashMap<>();
        metaPatch.put("branchDeviceId", body.get("deviceId"));
        metaPatch.put("branchId", body.get("branchId"));
        metaPatch.put("hqServerId", body.get("serverId"));
        jdbc.sql("UPDATE \"SyncBranchState\" SET \"feedCursor\" = ?, \"hqSeqHighWater\" = GREATEST(\"hqSeqHighWater\", ?),"
                + " meta = meta || ?::jsonb, \"lastFeedAt\" = now(), \"lastHqContactAt\" = now(), \"lastError\" = NULL,"
                + " \"updatedAt\" = now() WHERE \"organizationId\" = ?")
                .params(body.get("cursor"), num(body.get("maxDeviceSeq")) == null ? 0 : num(body.get("maxDeviceSeq")),
                        EntityJson.toJson(metaPatch), org).update();
    }

    /** HQ's pull scopes into the mirror, with HQ's currency, offline policy and licence for the tills. */
    public void pullMirror() {
        String org = branch.organizationId();
        String cursor = inTx(() -> {
            ensureState();
            return jdbc.sql("SELECT \"hqCursor\" FROM \"SyncBranchState\" WHERE \"organizationId\" = ?").param(org)
                    .query(String.class).optional().orElse(null);
        });
        String path = "/sync/pull?scopes=" + enc(String.join(",", SyncBranchMirror.mirroredScopes()))
                + (cursor == null ? "" : "&cursor=" + enc(cursor));
        HqResponse res = branch.get(path);
        if (res.status() != 200) {
            throw new IllegalStateException("HQ answered " + res.status() + " to the master-data pull: "
                    + abbreviate(String.valueOf(res.body())));
        }
        Map<String, Object> body = res.map();
        inTx(() -> {
            if (body.get("data") instanceof Map<?, ?> data) {
                for (Map.Entry<?, ?> e : data.entrySet()) {
                    mirror.store(org, String.valueOf(e.getKey()), e.getValue());
                }
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            for (String k : List.of("currency", "offlinePolicy", "license", "serverId", "protocolVersion")) {
                meta.put(k.equals("serverId") ? "hqServerId" : k, body.get(k));
            }
            meta.put("hqServerTime", body.get("serverTime"));
            jdbc.sql("UPDATE \"SyncBranchState\" SET \"hqCursor\" = ?, meta = meta || ?::jsonb, \"lastPullAt\" = now(),"
                    + " \"lastHqContactAt\" = now(), \"updatedAt\" = now() WHERE \"organizationId\" = ?")
                    .params(body.get("cursor"), EntityJson.toJson(meta), org).update();
            return null;
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Object ts(Object iso) {
        return iso == null ? null : String.valueOf(iso).replace("T", " ").replace("Z", "");
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isEmpty() ? s : null;
    }

    // ---------------------------------------------------------------- what the tills see

    /** Additive pull members at a branch: no token rotation (tokens are HQ's), HQ's offline policy, this server's id. */
    public Map<String, Object> afterPull(Map<?, ?> device) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("deviceTokenExpiresAt", device.get("tokenExpiresAt") instanceof Instant t ? JsonConfig.toJsIso(t) : null);
        Map<String, Object> policy = new LinkedHashMap<>();
        if (mirror.meta("offlinePolicy") instanceof Map<?, ?> p) {
            p.forEach((k, v) -> policy.put(String.valueOf(k), v));
        }
        Integer override = inTx(() -> jdbc.sql("SELECT \"offlineWindowHours\" FROM \"PosDevice\" WHERE id = ?")
                .param(device.get("id")).query(Integer.class).optional().orElse(null));
        if (override != null && (!(policy.get("offlineWindowHours") instanceof Number n) || override < n.intValue())) {
            policy.put("offlineWindowHours", override);
        }
        extra.put("offlinePolicy", policy);
        extra.put("serverId", branch.serverId());
        extra.put("deviceBranchId", device.get("branchId"));
        extra.put("relay", Map.of("mode", "branch", "pendingHqPosting", pendingCount()));
        return extra;
    }

    private long pendingCount() {
        return inTx(() -> jdbc.sql("SELECT count(*) FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND"
                + " \"relayState\" IN ('accepted_local', 'pending_hq')").param(branch.organizationId())
                .query(Long.class).single());
    }

    /** {@code GET /sync/branch/status}: the relay's health and outbox counts. */
    public Map<String, Object> status() {
        String org = branch.organizationId();
        return inTx(() -> {
            ensureState();
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String s : List.of("accepted_local", "pending_hq", "hq_posted", "hq_rejected")) {
                counts.put(s, 0L);
            }
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"relayState\" AS s, count(*) AS n FROM"
                    + " \"SyncRelayOutbox\" WHERE \"organizationId\" = ? GROUP BY \"relayState\"").param(org))) {
                counts.put((String) r.get("s"), ((Number) r.get("n")).longValue());
            }
            Map<String, Object> st = SqlRows.one(jdbc.sql("SELECT \"relaySeq\", \"hqSeqHighWater\", "
                    + iso("\"lastPullAt\"") + " AS \"lastPullAt\", " + iso("\"lastFeedAt\"") + " AS \"lastFeedAt\", "
                    + iso("\"lastRelayAt\"") + " AS \"lastRelayAt\", " + iso("\"lastHqContactAt\"") + " AS"
                    + " \"lastHqContactAt\", \"lastError\", " + iso("\"lastErrorAt\"") + " AS \"lastErrorAt\" FROM"
                    + " \"SyncBranchState\" WHERE \"organizationId\" = ?").param(org));
            long rejectedOpen = jdbc.sql("SELECT count(*) FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND"
                    + " \"relayState\" = 'hq_rejected' AND coalesce(\"hqDeadLetterStatus\", 'open') IN ('open',"
                    + " 'investigating')").param(org).query(Long.class).single();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", "branch");
            out.put("serverId", branch.serverId());
            out.put("outbox", counts);
            out.put("pendingHqPosting", (Long) counts.get("accepted_local") + (Long) counts.get("pending_hq"));
            out.put("rejectedOpen", rejectedOpen);
            out.putAll(st);
            return out;
        });
    }

    private static String iso(String col) {
        return SyncRelayFeedService.ISO.formatted(col);
    }

    /**
     * {@code GET /sync/branch/shift-report}: a shift's cash as the branch recorded it (C3 operational report from local
     * facts), keyed by the shift's client id; labelled with how many of its ops still wait for HQ.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> shiftReport(String cashSessionId) {
        String org = branch.organizationId();
        return inTx(() -> {
            BigDecimal opening = BigDecimal.ZERO;
            BigDecimal sales = BigDecimal.ZERO;
            BigDecimal in = BigDecimal.ZERO;
            BigDecimal out = BigDecimal.ZERO;
            long pending = 0;
            long rejected = 0;
            int count = 0;
            String closedCounted = null;
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"opType\", payload::text AS payload,"
                    + " \"relayState\" FROM \"SyncRelayOutbox\" WHERE \"organizationId\" = ? AND \"cashSessionId\" = ?"
                    + " AND \"holdCode\" IS NULL ORDER BY \"relaySeq\"").params(org, cashSessionId))) {
                count++;
                String state = (String) r.get("relayState");
                if ("hq_rejected".equals(state)) {
                    rejected++;
                    continue;
                }
                if (!"hq_posted".equals(state)) {
                    pending++;
                }
                Map<String, Object> p = mirror.parse((String) r.get("payload"));
                switch ((String) r.get("opType")) {
                    case "cash_session.open" -> opening = dec(p.get("openingFloat"));
                    case "sale.checkout" -> {
                        for (Object t : com.inventoryerp.pos.application.PosSql.list(p.get("tenders"))) {
                            if (t instanceof Map<?, ?> tm && "cash".equals(tm.get("method"))) {
                                sales = sales.add(dec(tm.get("amount")));
                            }
                        }
                    }
                    case "cash_session.movement" -> {
                        String type = String.valueOf(p.get("movementType"));
                        if (type.equals("pay_in")) {
                            in = in.add(dec(p.get("amount")));
                        } else if (type.equals("pay_out")) {
                            out = out.add(dec(p.get("amount")));
                        }
                    }
                    case "cash_session.close" -> closedCounted = p.get("closingCounted") == null ? null
                            : String.valueOf(p.get("closingCounted"));
                    default -> { }
                }
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("cashSessionId", cashSessionId);
            report.put("operations", count);
            report.put("openingFloat", opening.toPlainString());
            report.put("cashSales", sales.toPlainString());
            report.put("payIns", in.toPlainString());
            report.put("payOuts", out.toPlainString());
            report.put("expectedCash", opening.add(sales).add(in).subtract(out).toPlainString());
            report.put("closingCounted", closedCounted);
            report.put("pendingHqPosting", pending);
            report.put("rejected", rejected);
            report.put("note", pending == 0 ? "All operations posted at HQ"
                    : "Includes " + pending + " operation(s) pending HQ posting");
            return report;
        });
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
