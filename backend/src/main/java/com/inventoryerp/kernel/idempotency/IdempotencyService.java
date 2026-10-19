package com.inventoryerp.kernel.idempotency;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Idempotency-Key handling (port of {@code idempotency.service.ts}, docs/CONTRACT.md section 6).
 *
 * <ol>
 *   <li>Existing completed record + same request hash: replay the stored status and body.
 *   <li>Existing record + different hash: 409.
 *   <li>Existing pending record: 409 {@code OPERATION_PENDING} with the saved recovery evidence, except
 *       an abandoned money operation (no outcome after 2 minutes), which is released and re-run.
 *   <li>None: insert a pending row (the unique (organizationId, key) is the lock), run the handler,
 *       store the response. A failure keeps the row for recovery, except a definitive 4xx on a money
 *       route with no saved outcome, which is cached with {@code safeToRetry: true}.
 * </ol>
 * Records are written outside the business transaction (each statement commits on its own), exactly
 * like the Nest calls on the non-transactional Prisma client.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * Routes whose handlers save their business outcome in the same transaction as the money write; a
     * pending record with no saved outcome on these provably committed nothing.
     */
    public static final Pattern OUTCOME_ROUTES = Pattern.compile(
            "/(pos/(checkout|tabs/[^/]+/settle|orders/[^/]+/settle|split-bills/[^/]+/settle|invoices/[^/]+/(payments|refund)"
                    + "|sales/[^/]+/void|shift/handover)|cash-sessions/(open|close|movement|tender-settlements"
                    + "|[^/]+/(banking|force-close|reconcile))|accounts/cash-flow/(deposit|withdraw)|treasury/transfer"
                    + "|expenses(/[^/]+/(pay|void))?|payments(/[^/]+/void)?|supplier-payments)$");

    private static final Pattern RECOVERABLE_SALE = Pattern.compile("/pos/(checkout|tabs/[^/]+/settle|orders/[^/]+/settle)$");
    private static final Pattern PIN_STRIPPED = Pattern.compile("/cash-sessions/|/pos/shift/handover$");
    private static final long ABANDONED_AFTER_MS = 2 * 60_000L;

    /** Outcome of {@link #begin}: either a replay or permission to run the handler. */
    public sealed interface Begin permits Replay, Proceed {}

    /** Return this stored response without running the handler. */
    public record Replay(int statusCode, JsonNode body) implements Begin {}

    /** Run the handler under {@code operation}; {@code recorded} is false when no record exists (no tenant). */
    public record Proceed(BusinessOutcome.Operation operation, boolean recorded) implements Begin {}

    /** A request to protect. */
    public record Request(String key, String requestHash, String method, String path, JsonNode payload) {}

    /** Handler result for the transport-agnostic {@link #executeWithKey}. */
    public record Result(boolean replayed, int statusCode, Object body) {}

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public IdempotencyService(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Read + validate the {@code Idempotency-Key} header value; null when absent/blank. */
    public static String readKey(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            throw ApiException.conflict("Idempotency-Key header is too long (max 200 chars)");
        }
        return trimmed;
    }

    /** sha256(method + "\n" + path + "\n" + rawBody); manager/incoming PINs are excluded on cash routes. */
    public String hashRequest(String method, String path, String rawBody) {
        String body = rawBody;
        if (PIN_STRIPPED.matcher(path).find()) {
            JsonNode parsed = mapper.readTree(rawBody == null || rawBody.isEmpty() ? "{}" : rawBody);
            if (parsed instanceof ObjectNode o) {
                o.remove("managerPin");
                o.remove("incomingPin");
            }
            body = mapper.writeValueAsString(parsed);
        }
        return BusinessOutcome.sha256(method + "\n" + path + "\n" + (body == null ? "" : body));
    }

    /** Stable hash for non-HTTP callers (sync push ops). */
    public String hashPayload(Object payload) {
        return BusinessOutcome.sha256(mapper.writeValueAsString(payload));
    }

    /** Steps 1–3 of the algorithm: replay, refuse, or reserve the key. */
    public Begin begin(Request req) {
        String organizationId = TenantContext.optionalOrganizationId();
        if (organizationId == null || organizationId.isEmpty()) {
            // Unauthenticated routes (login) have no tenant to scope a replay record to.
            return new Proceed(null, false);
        }
        String key = req.key();
        boolean recoverableSale = RECOVERABLE_SALE.matcher(req.path()).find();
        JsonNode recovery = null;

        Optional<Row> existing = find(organizationId, key);
        if (existing.isPresent()) {
            Row row = existing.get();
            if (!row.requestHash().equals(req.requestHash())) {
                throw ApiException.conflict(
                        "Idempotency-Key '" + key + "' was previously used with a different request body");
            }
            if ("pending".equals(row.status()) || "indeterminate".equals(row.status())) {
                boolean noOutcome = row.responseJson() == null || row.responseJson().isEmpty();
                boolean abandoned = OUTCOME_ROUTES.matcher(stripQuery(req.path())).find() && noOutcome
                        && System.currentTimeMillis() - row.createdAt().toEpochMilli() > ABANDONED_AFTER_MS;
                if (recoverableSale) {
                    recovery = row.responseJson();
                } else if (abandoned) {
                    jdbc.sql("""
                                    DELETE FROM "IdempotencyRecord" WHERE "organizationId" = ? AND key = ? AND status = ?
                                      AND "responseJson" = '{}'::jsonb""")
                            .params(organizationId, key, row.status())
                            .update();
                    log.warn("Operation {} on {} was abandoned without an outcome; re-running", key, req.path());
                    return begin(req);
                } else {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", "OPERATION_PENDING");
                    body.put("message", "This operation needs recovery; do not submit it with a new key. Retry with the same key.");
                    body.put("operationKey", key);
                    body.put("recovery", row.responseJson());
                    throw ApiException.withBody(HttpStatus.CONFLICT, body);
                }
            } else {
                return new Replay(row.statusCode(), row.responseJson());
            }
        } else {
            try {
                jdbc.sql("""
                                INSERT INTO "IdempotencyRecord" (id, "organizationId", key, "requestHash", method, path,
                                  "statusCode", "responseJson", status, "createdAt")
                                VALUES (?, ?, ?, ?, ?, ?, 0, '{}'::jsonb, 'pending', now())""")
                        .params(UUID.randomUUID().toString(), organizationId, key, req.requestHash(), req.method(),
                                req.path())
                        .update();
            } catch (DuplicateKeyException race) {
                Row winner = find(organizationId, key).orElse(null);
                if (winner != null && !winner.requestHash().equals(req.requestHash())) {
                    throw ApiException.conflict("Idempotency key was used with a different request");
                }
                if (winner != null && ("completed".equals(winner.status()) || "business_completed".equals(winner.status()))) {
                    return new Replay(winner.statusCode(), winner.responseJson());
                }
                throw ApiException.conflict(
                        "Idempotency-Key '" + key + "' is being processed concurrently; retry shortly");
            }
        }

        String approvedById = null;
        String approvedKind = null;
        JsonNode token = req.payload() == null ? null : req.payload().get("approvalToken");
        if (token != null && !token.isNull() && !token.asString("").isEmpty()) {
            final JsonNode rec = recovery;
            var grant = jdbc.sql("""
                            SELECT "cashierId", "operationKey", endpoint, "payloadHash", "expiresAt", "managerId",
                                   "overrideKind"::text AS "overrideKind"
                            FROM "PosApprovalGrant" WHERE "organizationId" = ? AND "tokenHash" = ? LIMIT 1""")
                    .params(organizationId, BusinessOutcome.sha256(token.asString()))
                    .query((rs, n) -> Map.of(
                            "cashierId", String.valueOf(rs.getString("cashierId")),
                            "operationKey", String.valueOf(rs.getString("operationKey")),
                            "endpoint", String.valueOf(rs.getString("endpoint")),
                            "payloadHash", String.valueOf(rs.getString("payloadHash")),
                            "expiresAt", rs.getTimestamp("expiresAt").toInstant(),
                            "managerId", String.valueOf(rs.getString("managerId")),
                            "overrideKind", String.valueOf(rs.getString("overrideKind"))))
                    .optional();
            boolean hasOrder = rec != null && rec.hasNonNull("orderId");
            if (grant.isEmpty()
                    || !grant.get().get("cashierId").equals(TenantContext.userId())
                    || !grant.get().get("operationKey").equals(key)
                    || !req.path().endsWith((String) grant.get().get("endpoint"))
                    || !grant.get().get("payloadHash").equals(BusinessOutcome.approvalPayloadHash(req.payload()))
                    || (!hasOrder && ((Instant) grant.get().get("expiresAt")).isBefore(Instant.now()))) {
                throw ApiException.withBody(HttpStatus.FORBIDDEN, "Manager approval expired or does not match this operation");
            }
            approvedById = (String) grant.get().get("managerId");
            approvedKind = (String) grant.get().get("overrideKind");
        }
        return new Proceed(new BusinessOutcome.Operation(organizationId, key, req.path(), approvedById, approvedKind,
                recovery), true);
    }

    /** Store the handler's response and mark the record completed. */
    public void complete(BusinessOutcome.Operation op, int statusCode, Object body) {
        jdbc.sql("""
                        UPDATE "IdempotencyRecord" SET "statusCode" = ?, "responseJson" = ?::jsonb, status = 'completed',
                               "completedAt" = now()
                        WHERE "organizationId" = ? AND key = ?""")
                .params(statusCode, mapper.writeValueAsString(body), op.organizationId(), op.key())
                .update();
    }

    /**
     * After a handler failure: a transactionally saved outcome is replayed; a definitive client error on
     * a money route with no outcome is cached as {@code safeToRetry}; otherwise the record is kept for
     * recovery and {@code null} is returned (render the original error).
     */
    public Replay fail(BusinessOutcome.Operation op, int errorStatus, Object errorBody, boolean httpException) {
        Row saved;
        try {
            saved = find(op.organizationId(), op.key()).orElse(null);
        } catch (RuntimeException e) {
            saved = null;
        }
        if (saved != null && ("business_completed".equals(saved.status()) || "completed".equals(saved.status()))) {
            return new Replay(saved.statusCode(), saved.responseJson());
        }
        if (OUTCOME_ROUTES.matcher(stripQuery(op.path())).find() && httpException && errorStatus < 500
                && saved != null && (saved.responseJson() == null || saved.responseJson().isEmpty())) {
            ObjectNode body = mapper.createObjectNode();
            JsonNode err = mapper.valueToTree(errorBody);
            if (err instanceof ObjectNode o) {
                body.setAll(o);
            } else {
                body.set("message", err);
            }
            body.put("safeToRetry", true);
            jdbc.sql("""
                            UPDATE "IdempotencyRecord" SET status = 'completed', "statusCode" = ?, "responseJson" = ?::jsonb,
                                   "completedAt" = now()
                            WHERE "organizationId" = ? AND key = ?""")
                    .params(errorStatus, mapper.writeValueAsString(body), op.organizationId(), op.key())
                    .update();
            return new Replay(errorStatus, body);
        }
        log.warn("Operation {} retained for recovery after failure", op.key());
        return null;
    }

    /**
     * Transport-agnostic core (sync push processor: one key per offline operation). Same semantics as
     * the HTTP path; {@code handler} returns the status and body.
     */
    public Result executeWithKey(String key, String requestHash, String method, String path, JsonNode payload,
            Supplier<Result> handler) {
        Begin begin = begin(new Request(key, requestHash, method == null ? "OP" : method, path == null ? "sync" : path,
                payload));
        if (begin instanceof Replay r) {
            return new Result(true, r.statusCode(), r.body());
        }
        Proceed p = (Proceed) begin;
        if (!p.recorded()) {
            Result out = handler.get();
            return new Result(false, out.statusCode(), out.body());
        }
        try {
            Result out = BusinessOutcome.run(p.operation(), handler);
            complete(p.operation(), out.statusCode(), out.body());
            return new Result(false, out.statusCode(), out.body());
        } catch (ApiException e) {
            Object body = e.hasBody() ? e.body() : com.inventoryerp.kernel.web.GlobalExceptionHandler.nestBody(e.status(), e.getMessage());
            Replay r = fail(p.operation(), e.status().value(), body, true);
            if (r == null) {
                throw e;
            }
            if (r.statusCode() >= 400) {
                throw ApiException.withBody(HttpStatus.valueOf(r.statusCode()), r.body());
            }
            return new Result(true, r.statusCode(), r.body());
        } catch (RuntimeException e) {
            Replay r = fail(p.operation(), 500, null, false);
            if (r == null) {
                throw e;
            }
            return new Result(true, r.statusCode(), r.body());
        }
    }

    private record Row(String requestHash, String status, int statusCode, JsonNode responseJson, Instant createdAt) {}

    private Optional<Row> find(String organizationId, String key) {
        return jdbc.sql("""
                        SELECT "requestHash", status, "statusCode", "responseJson"::text AS "responseJson", "createdAt"
                        FROM "IdempotencyRecord" WHERE "organizationId" = ? AND key = ?""")
                .params(organizationId, key)
                .query((rs, n) -> {
                    Timestamp created = rs.getTimestamp("createdAt");
                    String json = rs.getString("responseJson");
                    return new Row(rs.getString("requestHash"), rs.getString("status"), rs.getInt("statusCode"),
                            json == null ? null : mapper.readTree(json), created.toInstant());
                })
                .optional();
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
