package com.inventoryerp.kernel.idempotency;

import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Port of {@code business-outcome.ts}: the idempotency operation running on this thread, and the
 * helper that saves recovery evidence in the SAME transaction as the business records, so a crash
 * after commit never leaves money without an outcome.
 */
public final class BusinessOutcome {

    /**
     * The operation a money handler runs under ({@code businessOperation} AsyncLocalStorage). ADR-S094 C1a: an
     * operation reserved by {@link IdempotencyService} is one <em>attempt</em> of its key, identified by
     * {@code attempt} and {@code ownerToken}; its write transactions may commit only while the record still names
     * that attempt (the fence, {@link #fence}).
     */
    public record Operation(String organizationId, String key, String path, String approvedById, String approvedKind,
            JsonNode recovery, int attempt, String ownerToken) {

        /** An operation without an attempt (not fenced). */
        public Operation(String organizationId, String key, String path, String approvedById, String approvedKind,
                JsonNode recovery) {
            this(organizationId, key, path, approvedById, approvedKind, recovery, 0, null);
        }

        /** True when the operation is an attempt whose commits are fenced. */
        public boolean fenced() {
            return ownerToken != null;
        }
    }

    /**
     * Gaps h-06 F7: name of a REQUIRES_NEW transaction whose writes are safe to run again (a find-or-create row, the
     * approval request a gate reuses). Its commit does not stop a failed operation from releasing its key.
     */
    public static final String RETRY_SAFE_TX = "erp.idempotency.retry-safe";

    /** ADR-S094 C1a: the idempotency service's own bookkeeping transactions; never fenced, stamped or counted. */
    public static final String LEDGER_TX = "erp.idempotency.ledger";

    private static final ThreadLocal<Operation> CURRENT = new ThreadLocal<>();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private BusinessOutcome() {}

    public static Operation current() {
        return CURRENT.get();
    }

    public static <T> T run(Operation op, Supplier<T> body) {
        Operation previous = CURRENT.get();
        CURRENT.set(op);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static void set(Operation op) {
        if (op == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(op);
        }
    }

    /**
     * Merge {@code body} into the operation's stored outcome. Call with the JdbcClient of the business
     * transaction. {@code complete} marks the record {@code business_completed} (statusCode 201). An attempt that
     * lost its key to a retry finds no record of its own and fails ({@link #leaseLost}), so its transaction rolls back.
     */
    public static void record(JdbcClient tx, Object body, boolean complete) {
        Operation op = CURRENT.get();
        if (op == null) {
            return;
        }
        String current = tx.sql("SELECT \"responseJson\"::text FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ?"
                        + " AND key = ?" + owned(op))
                .params(ownedParams(op, op.organizationId(), op.key()))
                .query(String.class)
                .optional()
                .orElseThrow(() -> op.fenced() ? leaseLost(op)
                        : new IllegalStateException("Durable operation record is missing"));
        JsonNode existing = current == null ? MAPPER.createObjectNode() : MAPPER.readTree(current);
        ObjectNode merged = existing instanceof ObjectNode o ? o.deepCopy() : MAPPER.createObjectNode();
        JsonNode add = MAPPER.valueToTree(body);
        if (add instanceof ObjectNode addObj) {
            addObj.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
        }
        if (complete) {
            tx.sql("UPDATE \"IdempotencyRecord\" SET \"responseJson\" = ?::jsonb, status = 'business_completed',"
                            + " \"statusCode\" = 201, \"completedAt\" = now() WHERE \"organizationId\" = ? AND key = ?"
                            + owned(op))
                    .params(ownedParams(op, MAPPER.writeValueAsString(merged), op.organizationId(), op.key()))
                    .update();
        } else {
            tx.sql("UPDATE \"IdempotencyRecord\" SET \"responseJson\" = ?::jsonb WHERE \"organizationId\" = ? AND key = ?"
                            + owned(op))
                    .params(ownedParams(op, MAPPER.writeValueAsString(merged), op.organizationId(), op.key()))
                    .update();
        }
    }

    /**
     * ADR-S094 C1a, the fence of the outermost write transaction of an attempt, run just before it commits: stamp the
     * attempt as committed on its record, under the record's row lock. No row means a retry took the key over after
     * this attempt's lease ended, so the transaction must not commit ({@link #leaseLost}). The lock is held until the
     * commit, so no takeover can start between this check and the commit.
     */
    static void fence(JdbcClient tx, Operation op) {
        int owned = tx.sql("UPDATE \"IdempotencyRecord\" SET \"committedAttempt\" = ? WHERE \"organizationId\" = ?"
                        + " AND key = ? AND attempt = ? AND \"ownerToken\" = ?")
                .params(op.attempt(), op.organizationId(), op.key(), op.attempt(), op.ownerToken())
                .update();
        if (owned == 0) {
            throw leaseLost(op);
        }
    }

    /**
     * ADR-S094 C1a, for a write transaction opened inside another one of the same attempt (REQUIRES_NEW): it must not
     * lock the record (the outer transaction may hold that lock and is waiting for this one: a deadlock the database
     * cannot see), so it checks ownership with a plain read and leaves an evidence row instead of the stamp.
     */
    static void nestedEvidence(JdbcClient tx, Operation op) {
        long owned = tx.sql("SELECT count(*) FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?"
                        + " AND attempt = ? AND \"ownerToken\" = ?")
                .params(op.organizationId(), op.key(), op.attempt(), op.ownerToken())
                .query(Long.class)
                .single();
        if (owned == 0) {
            throw leaseLost(op);
        }
        tx.sql("INSERT INTO \"IdempotencyEvidence\" (id, \"organizationId\", key, attempt) VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), op.organizationId(), op.key(), op.attempt())
                .update();
    }

    /**
     * The answer to an attempt that lost its key to a retry: nothing of it was saved, and the client should ask again
     * with the same key, which replays the retry's result or reports it still pending.
     */
    static ApiException leaseLost(Operation op) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "OPERATION_PENDING");
        body.put("message", "A retry with the same Idempotency-Key took this operation over; nothing of this attempt "
                + "was saved. Retry with the same key.");
        body.put("operationKey", op.key());
        return ApiException.withBody(HttpStatus.CONFLICT, body);
    }

    /** SQL suffix matching only the record of {@code op}'s attempt (nothing for an unfenced operation). */
    static String owned(Operation op) {
        return op.fenced() ? " AND attempt = ? AND \"ownerToken\" = ?" : "";
    }

    /** {@code params} followed by the attempt and owner token when {@code op} is fenced. */
    static Object[] ownedParams(Operation op, Object... params) {
        if (!op.fenced()) {
            return params;
        }
        Object[] all = Arrays.copyOf(params, params.length + 2);
        all[params.length] = op.attempt();
        all[params.length + 1] = op.ownerToken();
        return all;
    }

    /**
     * Hash binding a manager approval to every economic field of a payload, excluding the proof itself
     * ({@code approvalToken}, {@code overridePin}): keys sorted recursively, undefined/null-free.
     */
    public static String approvalPayloadHash(Object payload) {
        JsonNode tree = payload == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(payload);
        Object canonical = canonical(tree, true);
        return sha256(MAPPER.writeValueAsString(canonical));
    }

    private static Object canonical(JsonNode node, boolean root) {
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            node.forEach(n -> list.add(canonical(n, false)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(e -> {
                if (root && ("approvalToken".equals(e.getKey()) || "overridePin".equals(e.getKey()))) {
                    return;
                }
                sorted.put(e.getKey(), canonical(e.getValue(), false));
            });
            return sorted;
        }
        return node;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
