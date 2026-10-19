package com.inventoryerp.kernel.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
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

    /** The operation a money handler runs under ({@code businessOperation} AsyncLocalStorage). */
    public record Operation(String organizationId, String key, String path, String approvedById, String approvedKind,
            JsonNode recovery) {}

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
     * transaction. {@code complete} marks the record {@code business_completed} (statusCode 201).
     */
    public static void record(JdbcClient tx, Object body, boolean complete) {
        Operation op = CURRENT.get();
        if (op == null) {
            return;
        }
        String current = tx.sql("SELECT \"responseJson\"::text FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?")
                .params(op.organizationId(), op.key())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Durable operation record is missing"));
        JsonNode existing = current == null ? MAPPER.createObjectNode() : MAPPER.readTree(current);
        ObjectNode merged = existing instanceof ObjectNode o ? o.deepCopy() : MAPPER.createObjectNode();
        JsonNode add = MAPPER.valueToTree(body);
        if (add instanceof ObjectNode addObj) {
            addObj.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
        }
        if (complete) {
            tx.sql("""
                            UPDATE "IdempotencyRecord" SET "responseJson" = ?::jsonb, status = 'business_completed',
                                   "statusCode" = 201, "completedAt" = now()
                            WHERE "organizationId" = ? AND key = ?""")
                    .params(MAPPER.writeValueAsString(merged), op.organizationId(), op.key())
                    .update();
        } else {
            tx.sql("UPDATE \"IdempotencyRecord\" SET \"responseJson\" = ?::jsonb WHERE \"organizationId\" = ? AND key = ?")
                    .params(MAPPER.writeValueAsString(merged), op.organizationId(), op.key())
                    .update();
        }
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
