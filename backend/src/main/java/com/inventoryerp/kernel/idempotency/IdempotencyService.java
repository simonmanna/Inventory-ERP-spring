package com.inventoryerp.kernel.idempotency;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.SQLException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

/**
 * Idempotency-Key handling (port of {@code idempotency.service.ts}, docs/CONTRACT.md section 6).
 *
 * <ol>
 *   <li>Existing completed record + same request hash: replay the stored status and body.
 *   <li>Existing record + different hash: 409.
 *   <li>Existing pending record: 409 {@code OPERATION_PENDING} with the saved recovery evidence, except
 *       an abandoned money operation, which a retry takes over (below).
 *   <li>None: insert a pending row (the unique (organizationId, key) is the lock), run the handler,
 *       store the response. A failure keeps the row for recovery, except a definitive 4xx on a money
 *       route with no saved outcome, which is cached with {@code safeToRetry: true}.
 * </ol>
 * Records are written outside the business transaction (each statement commits on its own), exactly
 * like the Nest calls on the non-transactional Prisma client.
 *
 * <p>ADR-S094 C1a (D-IDEM-1, approved deviation OD-14): each execution of a key is an <em>attempt</em> with an owner
 * token and a lease ({@code erp.idempotency.lease-seconds}, default 120 s, the old abandonment age). Nest deleted an
 * abandoned money record and ran the handler again even when the first execution was still running, so a request
 * slower than 2 minutes could post twice. Now:
 * <ul>
 *   <li>a retry inside the lease answers {@code OPERATION_PENDING};
 *   <li>after the lease, a retry of a money route that left no evidence of a commit takes the key over by
 *       compare-and-set ({@code attempt + 1}, new token), never by deleting it; a POS sale route still resumes from its
 *       saved recovery evidence, once the lease has ended;
 *   <li>every write transaction of an attempt must still own the key when it commits
 *       ({@link IdempotencyTransactionListener}), so a superseded attempt rolls back instead of posting twice;
 *   <li>completion, failure caching and release touch only the record of their own attempt.
 * </ul>
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

    /**
     * Sales readiness R2 (P1-3): money routes whose handlers do not save a business outcome themselves. Every write
     * transaction of such a handler stamps {@code {"committed": true}} on the operation record before it commits
     * ({@link IdempotencyTransactionListener}), so a record left pending with no outcome provably committed nothing
     * and is re-run once abandoned, while one with the stamp answers OPERATION_PENDING (check before retrying).
     */
    public static final Pattern EVIDENCE_ROUTES = Pattern.compile(
            "/(invoices(/[^/]+/(post|cancel))?|credit-notes(/[^/]+/(post|allocate|reverse))?"
                    + "|customer-deposits(/[^/]+/(apply|refund|void|applications/[^/]+/reverse))?"
                    + "|payments/[^/]+/allocations|payments/mobile-money"
                    + "|sales/(orders/[^/]+/(invoices|deposits|apply-deposits|deliveries|milestones/[^/]+/invoice)"
                    + "|invoices/(batch|[^/]+/write-off)|credit-notes(/[^/]+/(allocate|refund))?"
                    + "|cash-sales/[^/]+/(approve-and-invoice|generate-invoice|cancel-invoice|void)"
                    + "|deliveries/[^/]+/(issue|confirm|fail|return|cancel|pod-correction)"
                    + "|returns/[^/]+/receive|rebates/accruals/[^/]+/settle))$");

    private static final Pattern RECOVERABLE_SALE = Pattern.compile("/pos/(checkout|tabs/[^/]+/settle|orders/[^/]+/settle)$");
    private static final Pattern PIN_STRIPPED = Pattern.compile("/cash-sessions/|/pos/shift/handover$");
    /** PostgreSQL {@code lock_not_available}: a NOWAIT lock met a row another transaction holds. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

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
    /**
     * Writes the response cache like Prisma writes a {@code Json} column: a {@code Decimal} becomes a JSON number, so a
     * replay answers {@code "debit": 1000} where the first response said {@code "debit": "1000"} (Nest parity).
     */
    private final JsonMapper cacheMapper;
    /** ADR-S094 C1a: how long an attempt owns its key before a retry may take it over. */
    private long leaseSeconds = 120;

    public IdempotencyService(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        SimpleModule prismaJson = new SimpleModule("prisma-json-column");
        prismaJson.addSerializer(BigDecimal.class, new ValueSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctx) {
                BigDecimal stripped = value.stripTrailingZeros();
                gen.writeNumber(stripped.scale() < 0 ? stripped.setScale(0) : stripped);
            }
        });
        this.cacheMapper = mapper.rebuild().addModule(prismaJson).build();
    }

    /** ADR-S094 C1a: the lease of an attempt, in seconds. */
    @Value("${erp.idempotency.lease-seconds:120}")
    void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
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
        for (int round = 0; ; round++) {
            Optional<Row> existing = find(organizationId, key);
            if (existing.isEmpty()) {
                String token = UUID.randomUUID().toString();
                try {
                    jdbc.sql("""
                                    INSERT INTO "IdempotencyRecord" (id, "organizationId", key, "requestHash", method, path,
                                      "statusCode", "responseJson", status, "createdAt", attempt, "ownerToken",
                                      "leaseExpiresAt")
                                    VALUES (?, ?, ?, ?, ?, ?, 0, '{}'::jsonb, 'pending', now(), 1, ?,
                                      now() + make_interval(secs => ?))""")
                            .params(UUID.randomUUID().toString(), organizationId, key, req.requestHash(), req.method(),
                                    req.path(), token, (double) leaseSeconds)
                            .update();
                    return proceed(organizationId, req, null, 1, token);
                } catch (DuplicateKeyException race) {
                    Row winner = find(organizationId, key).orElse(null);
                    if (winner != null && !winner.requestHash().equals(req.requestHash())) {
                        throw ApiException.conflict("Idempotency key was used with a different request");
                    }
                    if (winner != null && ("completed".equals(winner.status())
                            || "business_completed".equals(winner.status()))) {
                        return new Replay(winner.statusCode(), winner.responseJson());
                    }
                    throw ApiException.conflict(
                            "Idempotency-Key '" + key + "' is being processed concurrently; retry shortly");
                }
            }
            Row row = existing.get();
            if (!row.requestHash().equals(req.requestHash())) {
                throw ApiException.conflict(
                        "Idempotency-Key '" + key + "' was previously used with a different request body");
            }
            if (!"pending".equals(row.status()) && !"indeterminate".equals(row.status())) {
                return new Replay(row.statusCode(), row.responseJson());
            }
            // ADR-S094 C1a: a POS sale resumes from its saved recovery evidence; any other money route runs again only
            // when no write of the earlier attempt committed. Either way, only once that attempt's lease has ended.
            boolean mayTakeOver = recoverableSale || (recordsOutcome(req.path()) && !hasEvidence(organizationId, row));
            if (mayTakeOver) {
                String token = UUID.randomUUID().toString();
                Boolean took = takeOver(organizationId, key, row.attempt(), token, recoverableSale);
                if (Boolean.TRUE.equals(took)) {
                    log.warn("Operation {} on {}: attempt {} left the key without an outcome; attempt {} takes it over",
                            key, req.path(), row.attempt(), row.attempt() + 1);
                    return proceed(organizationId, req, recoverableSale ? row.responseJson() : null,
                            row.attempt() + 1, token);
                }
                if (took != null && round == 0 && changed(organizationId, row)) {
                    continue; // the record changed between the read and the takeover: look at it again once
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "OPERATION_PENDING");
            body.put("message", "This operation needs recovery; do not submit it with a new key. Retry with the same key.");
            body.put("operationKey", key);
            body.put("recovery", row.responseJson());
            throw ApiException.withBody(HttpStatus.CONFLICT, body);
        }
    }

    /** The approval-token check of a request allowed to run, and its operation: attempt {@code attempt} of the key. */
    private Proceed proceed(String organizationId, Request req, JsonNode recovery, int attempt, String token) {
        String key = req.key();
        String approvedById = null;
        String approvedKind = null;
        JsonNode approvalToken = req.payload() == null ? null : req.payload().get("approvalToken");
        if (approvalToken != null && !approvalToken.isNull() && !approvalToken.asString("").isEmpty()) {
            final JsonNode rec = recovery;
            var grant = jdbc.sql("""
                            SELECT "cashierId", "operationKey", endpoint, "payloadHash", "expiresAt", "managerId",
                                   "overrideKind"::text AS "overrideKind"
                            FROM "PosApprovalGrant" WHERE "organizationId" = ? AND "tokenHash" = ? LIMIT 1""")
                    .params(organizationId, BusinessOutcome.sha256(approvalToken.asString()))
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
                recovery, attempt, token), true);
    }

    /**
     * ADR-S094 C1a: take the key over from attempt {@code seen}, whose lease has ended, by compare-and-set. True when
     * this request now owns attempt {@code seen + 1}; false when the record does not qualify (its lease is still
     * running, an outcome or commit evidence exists, or another retry took it first); {@code null} when the owner
     * holds the record's lock right now (it is committing). {@code evenWithEvidence} takes over a record whose earlier
     * attempt saved recovery evidence (a POS sale resumes from it).
     */
    private Boolean takeOver(String organizationId, String key, int seen, String token, boolean evenWithEvidence) {
        try {
            int taken = jdbc.sql("""
                            UPDATE "IdempotencyRecord" r
                            SET attempt = r.attempt + 1, "ownerToken" = ?,
                                "leaseExpiresAt" = now() + make_interval(secs => ?)
                            WHERE r.id = (SELECT id FROM "IdempotencyRecord" WHERE "organizationId" = ? AND key = ?
                                          FOR UPDATE NOWAIT)
                              AND r.attempt = ? AND r.status IN ('pending', 'indeterminate')
                              AND coalesce(r."leaseExpiresAt", r."createdAt" + interval '2 minutes') < now()
                              AND (? OR (r."committedAttempt" IS NULL AND r."responseJson" = '{}'::jsonb
                                    AND NOT EXISTS (SELECT 1 FROM "IdempotencyEvidence" e
                                                    WHERE e."organizationId" = r."organizationId" AND e.key = r.key)))""")
                    .params(token, (double) leaseSeconds, organizationId, key, seen, evenWithEvidence)
                    .update();
            return taken == 1;
        } catch (DataAccessException e) {
            if (lockNotAvailable(e)) {
                return null;
            }
            throw e;
        }
    }

    /** True when the record no longer matches what {@code seen} showed (another attempt, status or evidence). */
    private boolean changed(String organizationId, Row seen) {
        Row now = find(organizationId, seen.key()).orElse(null);
        return now == null || now.attempt() != seen.attempt() || !now.status().equals(seen.status())
                || !java.util.Objects.equals(now.committedAttempt(), seen.committedAttempt())
                || !java.util.Objects.equals(now.responseJson(), seen.responseJson());
    }

    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * ADR-S094 C1a: true when an attempt of the key committed a write: a saved outcome or {@code committed} stamp, the
     * commit stamp of an outermost transaction, or the evidence row of a nested one.
     */
    private boolean hasEvidence(String organizationId, Row row) {
        if ((row.responseJson() != null && !row.responseJson().isEmpty()) || row.committedAttempt() != null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM \"IdempotencyEvidence\" WHERE \"organizationId\" = ? AND key = ?)")
                .params(organizationId, row.key())
                .query(Boolean.class)
                .single());
    }

    /** True when {@code row} is still the record of {@code op}'s attempt (always, for an unfenced operation). */
    private static boolean owns(BusinessOutcome.Operation op, Row row) {
        return !op.fenced() || (row.attempt() == op.attempt() && op.ownerToken().equals(row.ownerToken()));
    }

    /** Store the handler's response and mark the record completed (only the record of the operation's attempt). */
    public void complete(BusinessOutcome.Operation op, int statusCode, Object body) {
        int stored = jdbc.sql("""
                        UPDATE "IdempotencyRecord" SET "statusCode" = ?, "responseJson" = ?::jsonb, status = 'completed',
                               "completedAt" = now()
                        WHERE "organizationId" = ? AND key = ?""" + BusinessOutcome.owned(op))
                .params(BusinessOutcome.ownedParams(op, statusCode, cacheMapper.writeValueAsString(body),
                        op.organizationId(), op.key()))
                .update();
        if (stored == 0 && op.fenced()) {
            log.warn("Operation {} on {}: attempt {} no longer owns the key; its response was not stored", op.key(),
                    op.path(), op.attempt());
        }
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
        if (saved != null && !owns(op, saved)) {
            // ADR-S094 C1a: a retry took the key over; its record is not this attempt's to settle.
            log.warn("Operation {} on {}: attempt {} failed after losing the key to attempt {}", op.key(), op.path(),
                    op.attempt(), saved.attempt());
            return null;
        }
        if (recordsOutcome(op.path()) && httpException && errorStatus < 500
                && saved != null && !hasEvidence(op.organizationId(), saved)) {
            ObjectNode body = mapper.createObjectNode();
            JsonNode err = mapper.valueToTree(errorBody);
            if (err instanceof ObjectNode o) {
                body.setAll(o);
            } else {
                body.set("message", err);
            }
            body.put("safeToRetry", true);
            int cached = jdbc.sql("""
                            UPDATE "IdempotencyRecord" SET status = 'completed', "statusCode" = ?, "responseJson" = ?::jsonb,
                                   "completedAt" = now()
                            WHERE "organizationId" = ? AND key = ?""" + BusinessOutcome.owned(op))
                    .params(BusinessOutcome.ownedParams(op, errorStatus, mapper.writeValueAsString(body),
                            op.organizationId(), op.key()))
                    .update();
            if (cached == 0 && op.fenced()) {
                return null;
            }
            return new Replay(errorStatus, body);
        }
        log.warn("Operation {} retained for recovery after failure", op.key());
        return null;
    }

    /**
     * Gaps h-06 F7: forget a pending key whose handler failed without committing anything (every write transaction it
     * opened rolled back and no outcome was saved), so a retry with the same key runs the operation again instead of
     * answering {@code OPERATION_PENDING} until the key is abandoned. Only the record of the operation's own attempt,
     * and never one that holds commit evidence (ADR-S094 C1a).
     */
    public void release(BusinessOutcome.Operation op) {
        String owned = op.fenced() ? " AND r.attempt = ? AND r.\"ownerToken\" = ?" : "";
        int deleted = jdbc.sql("""
                        DELETE FROM "IdempotencyRecord" r WHERE r."organizationId" = ? AND r.key = ?
                          AND r.status = 'pending' AND r."responseJson" = '{}'::jsonb AND r."committedAttempt" IS NULL
                          AND NOT EXISTS (SELECT 1 FROM "IdempotencyEvidence" e
                                          WHERE e."organizationId" = r."organizationId" AND e.key = r.key)""" + owned)
                .params(BusinessOutcome.ownedParams(op, op.organizationId(), op.key()))
                .update();
        if (deleted > 0) {
            log.info("Operation {} on {} failed and committed nothing; key released for a retry", op.key(), op.path());
        }
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

    /** ADR-S083 S1: what a key currently holds, for the sync ledger's pre-checks. */
    public record KeyState(String requestHash, String status, Instant createdAt) {}

    /** The state of {@code key} in the current organization, if it was ever used. */
    public Optional<KeyState> peek(String key) {
        String organizationId = TenantContext.optionalOrganizationId();
        if (organizationId == null) {
            return Optional.empty();
        }
        return find(organizationId, key).map(r -> new KeyState(r.requestHash(), r.status(), r.createdAt()));
    }

    /**
     * ADR-S083 S1: forget a sync op's pending key that saved no outcome and is older than {@code olderThanMs}. The caller
     * has proved through the applied-op ledger that the earlier attempt committed nothing, so the same opId may run
     * again; the age guard keeps an attempt that is still in flight from being run twice. ADR-S094 C1a: never a record
     * holding commit evidence or locked right now (its attempt is committing); an attempt still running when its record
     * goes can no longer commit, because its fence finds no record of its own.
     */
    public boolean releaseStale(String key, long olderThanMs) {
        String organizationId = TenantContext.optionalOrganizationId();
        if (organizationId == null) {
            return false;
        }
        return jdbc.sql("""
                        DELETE FROM "IdempotencyRecord" WHERE id = (
                          SELECT r.id FROM "IdempotencyRecord" r WHERE r."organizationId" = ? AND r.key = ?
                            AND r.status IN ('pending', 'indeterminate') AND r."responseJson" = '{}'::jsonb
                            AND r."committedAttempt" IS NULL AND r."createdAt" < now() - make_interval(secs => ?)
                            AND NOT EXISTS (SELECT 1 FROM "IdempotencyEvidence" e
                                            WHERE e."organizationId" = r."organizationId" AND e.key = r.key)
                          FOR UPDATE OF r SKIP LOCKED)""")
                .params(organizationId, key, olderThanMs / 1000.0)
                .update() > 0;
    }

    private record Row(String key, String requestHash, String status, int statusCode, JsonNode responseJson,
            Instant createdAt, int attempt, String ownerToken, Integer committedAttempt) {}

    private Optional<Row> find(String organizationId, String key) {
        return jdbc.sql("""
                        SELECT key, "requestHash", status, "statusCode", "responseJson"::text AS "responseJson",
                               "createdAt", attempt, "ownerToken", "committedAttempt"
                        FROM "IdempotencyRecord" WHERE "organizationId" = ? AND key = ?""")
                .params(organizationId, key)
                .query((rs, n) -> {
                    Timestamp created = rs.getTimestamp("createdAt");
                    String json = rs.getString("responseJson");
                    int committed = rs.getInt("committedAttempt");
                    boolean noCommit = rs.wasNull();
                    return new Row(rs.getString("key"), rs.getString("requestHash"), rs.getString("status"),
                            rs.getInt("statusCode"), json == null ? null : mapper.readTree(json), created.toInstant(),
                            rs.getInt("attempt"), rs.getString("ownerToken"), noCommit ? null : committed);
                })
                .optional();
    }

    /** True when a pending record of {@code path} with no outcome provably committed nothing. */
    static boolean recordsOutcome(String path) {
        String p = stripQuery(path);
        return OUTCOME_ROUTES.matcher(p).find() || EVIDENCE_ROUTES.matcher(p).find();
    }

    /** True when the route's write transactions must stamp commit evidence on the record (R2, P1-3). */
    static boolean needsCommitEvidence(String path) {
        return EVIDENCE_ROUTES.matcher(stripQuery(path)).find();
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
