package com.inventoryerp.sync.application;

import com.inventoryerp.accounting.application.CashRegisterService;
import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.accounting.domain.CashRegister;
import com.inventoryerp.communication.domain.Conversation;
import com.inventoryerp.communication.domain.ConversationParticipant;
import com.inventoryerp.core.application.ProductService;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.inventory.application.DirectStockService;
import com.inventoryerp.inventory.application.InventoryCountService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.application.KitchenWaste;
import com.inventoryerp.pos.application.PosCashDrawerService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosOverridesService;
import com.inventoryerp.pos.application.PosReservationsService;
import com.inventoryerp.pos.application.PosService;
import com.inventoryerp.pos.application.PosSettingsService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.pos.domain.AccompanimentGroup;
import com.inventoryerp.pos.domain.AccompanimentOption;
import com.inventoryerp.pos.domain.MenuCategory;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.MenuItemAccompanimentGroup;
import com.inventoryerp.pos.domain.MenuItemModifierGroup;
import com.inventoryerp.pos.domain.MenuItemVariant;
import com.inventoryerp.pos.domain.Modifier;
import com.inventoryerp.pos.domain.ModifierGroup;
import com.inventoryerp.pos.domain.PosTable;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Batch op processor for offline devices (port of {@code sync-push.service.ts}). Ops run in {@code deviceSeq} order,
 * each inside a tenant context whose identity is the verified actor (with that actor's current permissions) and
 * under {@link IdempotencyService#executeWithKey} keyed by {@code opId}, calling the same services as the online
 * routes. A failed op is dead-lettered; processing continues, but ops that reference a failed op's client id fail
 * fast with 424. Owner rule: never block sales, never lose them.
 */
@Service
public class SyncPushService {

    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    /** One pushed op ({@code SyncOpDto}). */
    public record Op(String opId, int deviceSeq, String type, String actorUserId, String occurredAt,
            Map<String, Object> payload, List<String> originChain, String supersedesOpId) {
        public Op(String opId, int deviceSeq, String type, String actorUserId, String occurredAt,
                Map<String, Object> payload) {
            this(opId, deviceSeq, type, actorUserId, occurredAt, payload, null, null);
        }
    }

    /**
     * The pushing device ({@code RequestDevice}). {@code strictSeq} (protocol 2+, ADR-S083 O6): the client never
     * re-keys an op, so a device sequence number already used by another opId of this device is refused
     * ({@code SEQ_REUSED}). {@code relay} (ADR-S083 O7): a branch node relaying its tills' ops — each op must name a till
     * of the same branch in {@code originChain}, and a {@code hold:<code>} hop the branch added is dead-lettered here.
     */
    public record Device(String id, String organizationId, String branchId, Instant cutoff, String cutoffCode,
            boolean strictSeq, boolean relay) {
        public Device(String id, String organizationId, String branchId) {
            this(id, organizationId, branchId, null, null, false, false);
        }

        public Device(String id, String organizationId, String branchId, Instant cutoff, String cutoffCode) {
            this(id, organizationId, branchId, cutoff, cutoffCode, false, false);
        }

        public Device(String id, String organizationId, String branchId, Instant cutoff, String cutoffCode,
                boolean strictSeq) {
            this(id, organizationId, branchId, cutoff, cutoffCode, strictSeq, false);
        }
    }

    /** Codes a branch node may ask HQ to hold an op under ({@code hold:<code>} in originChain). */
    static final Set<String> BRANCH_HOLDS = Set.of("DEVICE_REVOKED", "DEVICE_TOKEN_EXPIRED", "SEQ_REUSED");

    private final PosDb db;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final IdempotencyService idempotency;
    private final PosService pos;
    private final PosSettingsService posSettings;
    private final PosInvoiceService billing;
    private final PosReservationsService reservations;
    private final CashSessionService cashSessions;
    private final CashRegisterService cashRegisters;
    private final ProductService products;
    private final DirectStockService directStock;
    private final SyncStockService stockOps;
    private final InventoryCountService counts;
    private final ExpensesService expenses;
    private final PosOverridesService overrides;
    private final KitchenWaste kitchenWaste;
    private final PosCashDrawerService cashDrawer;
    private final AuditService audit;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    /** ADR-S083 C1: the one transaction an {@link SyncOpCatalog.Boundary#OP_TRANSACTION} handler runs in. */
    private final org.springframework.transaction.support.TransactionTemplate opTx;
    private SyncLedger ledger;

    private SyncSalesService salesOps;

    /** ADR-S083 O5 field-sales ops (setter injection keeps the long constructor stable). */
    @org.springframework.beans.factory.annotation.Autowired
    void setSalesOps(SyncSalesService salesOps) {
        this.salesOps = salesOps;
    }

    /** ADR-S083 C1 ledger (setter injection keeps the long constructor stable for existing wiring). */
    @org.springframework.beans.factory.annotation.Autowired
    void setLedger(SyncLedger ledger) {
        this.ledger = ledger;
    }

    /** Name of the per-op transaction ({@link SyncOpCatalog.Boundary#OP_TRANSACTION}). */
    public static final String OP_TX = "erp.sync.op";

    /** Quarantine codes: ops a revoked or expired device delivered after its cut-off. */
    public static final java.util.Set<String> QUARANTINE_CODES = java.util.Set.of("DEVICE_REVOKED",
            "DEVICE_TOKEN_EXPIRED");

    /** Age after which a pending key with no ledger row is treated as an abandoned attempt (the money rule). */
    static final long STALE_ATTEMPT_MS = 120_000;

    public SyncPushService(PosDb db, JsonMapper mapper, IdempotencyService idempotency, PosService pos,
            PosSettingsService posSettings, PosInvoiceService billing, PosReservationsService reservations,
            CashSessionService cashSessions, CashRegisterService cashRegisters, ProductService products,
            DirectStockService directStock, InventoryCountService counts, ExpensesService expenses,
            org.springframework.transaction.PlatformTransactionManager txm, PosOverridesService overrides,
            KitchenWaste kitchenWaste, AuditService audit, PosCashDrawerService cashDrawer, SyncStockService stockOps) {
        this.stockOps = stockOps;
        this.cashDrawer = cashDrawer;
        this.overrides = overrides;
        this.kitchenWaste = kitchenWaste;
        this.audit = audit;
        this.db = db;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
        this.opTx = new org.springframework.transaction.support.TransactionTemplate(txm);
        this.opTx.setName(OP_TX);
        this.jdbc = db.jdbc();
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.pos = pos;
        this.posSettings = posSettings;
        this.billing = billing;
        this.reservations = reservations;
        this.cashSessions = cashSessions;
        this.cashRegisters = cashRegisters;
        this.products = products;
        this.directStock = directStock;
        this.counts = counts;
        this.expenses = expenses;
    }

    /** A business failure inside an op handler ({@code new HttpException(message, status)}). */
    static ApiException http(String message, int status) {
        return new ApiException(HttpStatus.valueOf(status), message);
    }

    private SyncMetrics metrics;

    /** ADR-S083 S9: push counters (optional, so unit tests can build the service without a registry). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(SyncMetrics metrics) {
        this.metrics = metrics;
    }

    public Map<String, Object> push(Device device, List<Op> opsIn) {
        List<Op> ops = new ArrayList<>(opsIn);
        ops.sort(Comparator.comparingInt(Op::deviceSeq));
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, String> clientIdMap = new HashMap<>();
        Set<String> failedClientIds = new LinkedHashSet<>();
        Map<String, List<String>> actorOk = new HashMap<>();
        int lastAppliedSeq = 0;
        for (Op op : ops) {
            Map<String, Object> result = applyOp(device, op, clientIdMap, failedClientIds, actorOk);
            results.add(result);
            if (metrics != null) {
                metrics.pushed(result.get("status"));
            }
            if (!"failed".equals(result.get("status"))) {
                lastAppliedSeq = op.deviceSeq();
            }
        }
        try {
            jdbc.sql("UPDATE \"PosDevice\" SET \"lastPushSeq\" = ?, \"updatedAt\" = " + PosSql.NOW + " WHERE id = ?"
                    + " AND \"lastPushSeq\" < ?").params(lastAppliedSeq, device.id(), lastAppliedSeq).update();
        } catch (RuntimeException e) {
            log.warn("lastPushSeq update failed: {}", e.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("lastPushSeq", lastAppliedSeq);
        out.put("serverTime", JsonConfig.toJsIso(Instant.now()));
        return out;
    }

    /** The actor's current role permissions when they are an active user of the device's org; else null. */
    private List<String> actorMayAct(Device device, String actorUserId, Map<String, List<String>> cache) {
        if (actorUserId == null || actorUserId.isEmpty()) {
            return null;
        }
        if (cache.containsKey(actorUserId)) {
            return cache.get(actorUserId);
        }
        boolean exists = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\""
                + " = true AND \"deletedAt\" IS NULL").params(actorUserId, device.organizationId()).query(Long.class)
                .single() > 0;
        List<String> permissions = null;
        if (exists) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT r.permissions FROM \"Role\" r JOIN \"_UserRoles\" ur"
                    + " ON ur.\"A\" = r.id WHERE ur.\"B\" = ?").param(actorUserId))) {
                PosSql.list(r.get("permissions")).forEach(p -> set.add(String.valueOf(p)));
            }
            permissions = new ArrayList<>(set);
        }
        cache.put(actorUserId, permissions);
        return permissions;
    }

    private Map<String, Object> failed(Op op, int status, String error) {
        return failed(op, status, error, failureCode(op, status, null), retryable(status, null));
    }

    private Map<String, Object> failed(Op op, int status, String error, String code, boolean retryable) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("status", "failed");
        r.put("httpStatus", status);
        r.put("error", error);
        // ADR-S083 S1 (additive): a machine code, and whether the device may retry the SAME opId.
        r.put("code", code);
        r.put("retryable", retryable);
        return r;
    }

    /** ADR-S083 S1/S5 failure code. */
    static String failureCode(Op op, int status, String apiCode) {
        if (apiCode != null) {
            return apiCode;
        }
        if (status == 424) {
            return "DEPENDENCY_FAILED";
        }
        if (status >= 500) {
            return "SERVER_ERROR";
        }
        if ("stock.count".equals(op.type()) && status == 409) {
            return "COUNT_CONFLICT"; // definitive: an open draft count must be finished or cancelled by a person
        }
        if (status == 403) {
            return "PERMISSION_DENIED";
        }
        return "BUSINESS_RULE";
    }

    /** Transient failures and dependency failures may be retried with the same opId; business rejections may not. */
    static boolean retryable(int status, String code) {
        return status >= 500 || status == 424 || "OPERATION_PENDING".equals(code) || "OPERATION_INCOMPLETE".equals(code);
    }

    private Map<String, Object> applyOp(Device device, Op op, Map<String, String> clientIdMap,
            Set<String> failedClientIds, Map<String, List<String>> actorOk) {
        Map<String, Object> payloadIn = op.payload() == null ? Map.of() : op.payload();
        Object clientId = payloadIn.get("clientId");
        Runnable markFailed = () -> {
            if (PosSql.truthy(clientId)) {
                failedClientIds.add(String.valueOf(clientId));
            }
        };
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("type", op.type());
        hashed.put("payload", op.payload());
        // Hash of the payload as sent (key order as received): what idempotency records written before O0 carry.
        String legacyHash = idempotency.hashPayload(hashed);
        // ADR-S083 C1: the ledger hash is key-order independent. A stored dead-letter payload comes back from jsonb
        // with its keys reordered; a supervisor retry or recovery must still be recognised as the same op.
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", op.type());
        canonical.put("payload", canonical(op.payload()));
        String payloadHash = idempotency.hashPayload(canonical);
        String[] keyHash = {payloadHash};
        // ADR-S083 S2 (O0 correction 2): the cut-off is decided by server time only. Every push a revoked or expired
        // device makes arrives after its cut-off (receivedAt > revokedAt), so nothing new is applied, whatever
        // occurredAt the device claims. An op the server already applied replays its stored result (no new effect);
        // anything else is quarantined for supervisor recovery (SyncDeadLetterService#recover).
        String holdCode = null;
        String holdWhy = null;
        int holdStatus = 403;
        if (device.cutoff() != null) {
            holdCode = device.cutoffCode();
            holdWhy = ("DEVICE_REVOKED".equals(device.cutoffCode()) ? "This device was revoked at "
                    : "This device's token expired at ") + JsonConfig.toJsIso(device.cutoff())
                    + "; operations received after that are held for supervisor review";
        } else if (device.relay()) {
            // ADR-S083 O7: a branch relays only its own tills' ops, and holds what it already knows HQ must not apply.
            String origin = originDevice(op);
            String marker = holdMarker(op);
            if (origin == null || !originOfBranch(device, origin)) {
                holdCode = "ORIGIN_NOT_ALLOWED";
                holdWhy = "A relayed operation must name a till of the relaying branch in originChain (device:<id>)";
            } else if (marker != null) {
                holdCode = marker;
                holdStatus = "SEQ_REUSED".equals(marker) ? 409 : 403;
                holdWhy = switch (marker) {
                    case "DEVICE_REVOKED" -> "Till " + origin + " was revoked before the branch received this"
                            + " operation; it is held for supervisor review";
                    case "DEVICE_TOKEN_EXPIRED" -> "Till " + origin + "'s token had expired when the branch received"
                            + " this operation; it is held for supervisor review";
                    default -> "Till " + origin + " reused a device sequence for another operation (detected at the"
                            + " branch); a rejected operation is corrected with a new operation (supersedesOpId)";
                };
            }
        }
        if (holdCode != null) {
            SyncLedger.Entry seen = ledger.find(device.organizationId(), op.opId());
            if (seen != null && seen.applied() && seen.payloadHash().equals(payloadHash)) {
                Map<String, Object> body = asMap(seen.result());
                remember(clientId, body, clientIdMap);
                return success(op, true, seen.httpStatus() == null ? 201 : seen.httpStatus(), body);
            }
            deadLetter(device, op, holdWhy, holdStatus, holdCode, false);
            markFailed.run();
            return failed(op, holdStatus, holdWhy, holdCode, false);
        }
        List<String> permissions = actorMayAct(device, op.actorUserId(), actorOk);
        if (permissions == null) {
            String why = "Unknown or inactive actorUserId " + op.actorUserId() + " for this device's organization";
            deadLetter(device, op, why, 403, "ACTOR_NOT_ALLOWED", false);
            markFailed.run();
            return failed(op, 403, why, "ACTOR_NOT_ALLOWED", false);
        }
        String brokenDep = referencedClientIds(payloadIn).stream().filter(failedClientIds::contains).findFirst()
                .orElse(null);
        if (brokenDep != null) {
            String why = "Depends on failed op (clientId " + brokenDep + ")";
            deadLetter(device, op, why, 424, "DEPENDENCY_FAILED", true);
            markFailed.run();
            return failed(op, 424, why);
        }
        List<String> chain = new ArrayList<>(op.originChain() == null ? List.of() : op.originChain());
        if (device.id() != null && !chain.contains("device:" + device.id())) {
            chain.add("device:" + device.id());
        }
        chain.add("server:" + ledger.serverId());
        SyncLedger.Context ctx = new SyncLedger.Context(device.organizationId(), op.opId(), payloadHash, op.type(),
                device.id(), op.deviceSeq(), chain, op.supersedesOpId());
        TenantStore store = TenantStore.user(device.organizationId(), op.actorUserId());
        store.setPermissions(permissions);
        // ADR-S083 C1/S1: the ledger decides first. Same opId + different payload is never applied.
        SyncLedger.Entry entry = ledger.find(device.organizationId(), op.opId());
        if (entry != null) {
            if (!entry.payloadHash().equals(payloadHash)) {
                String why = "Operation id " + op.opId() + " was already used for a different operation";
                deadLetter(device, op, why, 409, "OP_ID_REUSED", false);
                markFailed.run();
                return failed(op, 409, why, "OP_ID_REUSED", false);
            }
            if (!entry.applied()) {
                String why = "Operation " + op.opId() + " committed on the server but its result was not recorded; it"
                        + " is listed for reconciliation and will not run again";
                deadLetter(device, op, why, 409, "OPERATION_INCOMPLETE", false);
                markFailed.run();
                return failed(op, 409, why, "OPERATION_INCOMPLETE", true);
            }
            Map<String, Object> body = asMap(entry.result());
            remember(clientId, body, clientIdMap);
            afterApplied(device, op); // a dead letter left by a crash after commit is closed by the replay
            return success(op, true, entry.httpStatus() == null ? 201 : entry.httpStatus(), body);
        }
        // ADR-S083 O6: a protocol-2 client mints one opId per deviceSeq and never re-keys a rejected op (a correction
        // is a new op with a new sequence and supersedesOpId). The same sequence under another opId is a re-keyed or
        // duplicated op: refused, never applied. Protocol-1 clients (older Android builds) still re-key, so not them.
        if (device.strictSeq() && device.id() != null && seqUsedByAnotherOp(device, op)) {
            String why = "Device sequence " + op.deviceSeq() + " was already used by another operation of this device;"
                    + " a rejected operation is corrected with a new operation (supersedesOpId), never re-sent under a"
                    + " new id";
            deadLetter(device, op, why, 409, "SEQ_REUSED", false);
            markFailed.run();
            return failed(op, 409, why, "SEQ_REUSED", false);
        }
        // No ledger row: nothing committed. A key an earlier attempt left pending is released once it is stale, so
        // the same opId runs again (never a new key); a fresh one is an attempt still in flight.
        Map<String, Object> pre = TenantContext.run(store, () -> {
            var key = idempotency.peek(op.opId()).orElse(null);
            if (key == null) {
                return null;
            }
            if (!key.requestHash().equals(payloadHash)) {
                if (!key.requestHash().equals(legacyHash)) {
                    return Map.of("code", "OP_ID_REUSED");
                }
                keyHash[0] = legacyHash; // a record from before O0: keep its hash so it still replays
            }
            if (("pending".equals(key.status()) || "indeterminate".equals(key.status()))
                    && !idempotency.releaseStale(op.opId(), STALE_ATTEMPT_MS)) {
                return Map.of("code", "OPERATION_PENDING");
            }
            return null;
        });
        if (pre != null && "OP_ID_REUSED".equals(pre.get("code"))) {
            String why = "Operation id " + op.opId() + " was already used for a different operation";
            deadLetter(device, op, why, 409, "OP_ID_REUSED", false);
            markFailed.run();
            return failed(op, 409, why, "OP_ID_REUSED", false);
        }
        if (pre != null) {
            markFailed.run();
            return failed(op, 409, "This operation is still being processed; retry with the same operation id",
                    "OPERATION_PENDING", true);
        }
        try {
            IdempotencyService.Result outcome = TenantContext.run(store, () -> idempotency.executeWithKey(op.opId(),
                    keyHash[0], "SYNC", op.type(), mapper.valueToTree(payloadIn),
                    () -> ledger.run(ctx, () -> new IdempotencyService.Result(false, 201, runInBoundary(device, op,
                            clientIdMap)))));
            Map<String, Object> body = asMap(outcome.body());
            ledger.complete(ctx, outcome.statusCode(), body);
            remember(clientId, body, clientIdMap);
            afterApplied(device, op);
            acceptSeq(device, op);
            return success(op, outcome.replayed(), outcome.statusCode(), body);
        } catch (RuntimeException err) {
            int status = err instanceof ApiException a ? a.status().value() : 500;
            String message = errorMessage(err);
            String apiCode = apiCode(err);
            markFailed.run();
            if ("OPERATION_PENDING".equals(apiCode)
                    || status == 409 && message != null && message.contains("is being processed concurrently")) {
                // Another attempt of this same opId holds the key: never a dead letter, retry the same opId.
                return failed(op, 409, message, "OPERATION_PENDING", true);
            }
            // Owner decision 2026-11-25 (ADR-S086 §6): a sale the PLM lifecycle refused keeps its own code, so a
            // supervisor can retry it once the product may be sold. Other business codes keep their mapping.
            String ownCode = com.inventoryerp.core.application.ProductLifecycleGuard.BLOCKED_CODE.equals(apiCode)
                    ? apiCode : null;
            String code = failureCode(op, status, ctx.anchored() ? "PARTIAL_COMMIT" : ownCode);
            log.warn("sync op {} ({}) failed [{}]: {}", op.opId(), op.type(), code, message);
            if (!ctx.anchored()) {
                // Nothing committed: free the key now, so a supervisor retry or a corrected resend may run it again.
                TenantContext.run(store, () -> idempotency.releaseStale(op.opId(), 0));
            }
            boolean retry = !ctx.anchored() && retryable(status, code);
            deadLetter(device, op, message, status, code, retry);
            return failed(op, status, message, code, retry);
        }
    }

    /** Maps with their keys sorted, at any depth (a key-order independent form for hashing). */
    @SuppressWarnings("unchecked")
    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), canonical(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(SyncPushService::canonical).toList();
        }
        return value;
    }

    /** Test hook: runs after an op's business commit, before its ledger row is completed (crash simulation). */
    static volatile Runnable afterRunHook;

    /**
     * ADR-S083 C1: run the handler inside the boundary its catalog entry names. An unclassified type is refused, so
     * no handler can run without durable duplicate protection.
     */
    private Map<String, Object> runInBoundary(Device device, Op op, Map<String, String> clientIdMap) {
        SyncOpCatalog.Entry entry = SyncOpCatalog.of(op.type());
        if (entry == null) {
            throw http("Unsupported sync op type: " + op.type(), 400);
        }
        if ("stock.count".equals(op.type())) {
            // IPR review 1 (N1, count spec section 5): the sale stock postings of the counted location are drained
            // before the op's transaction opens (each job commits on its own; claimed, so safe to repeat), never on a
            // second connection inside it. Its commits do not anchor the op's ledger row.
            Object loc = op.payload() == null ? null : op.payload().get("locationId");
            if (loc instanceof String locationId && !locationId.isBlank()) {
                ledger.outside(() -> {
                    counts.drainSales(locationId);
                    return null;
                });
            }
        }
        Map<String, Object> out = entry.boundary() == SyncOpCatalog.Boundary.OP_TRANSACTION
                ? opTx.execute(st -> {
                    Map<String, Object> r = runOp(device, op, clientIdMap);
                    ledger.stage(r);
                    return r;
                })
                : runOp(device, op, clientIdMap);
        Runnable hook = afterRunHook;
        if (hook != null) {
            hook.run();
        }
        return out;
    }

    /** Server-maintained sequence state: the highest deviceSeq applied for this device. */
    /** Whether another opId of this device already reached the server (applied or dead-lettered) with this sequence. */
    private boolean seqUsedByAnotherOp(Device device, Op op) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"SyncAppliedOp\" WHERE \"organizationId\" = ? AND \"deviceId\" = ?"
                + " AND \"deviceSeq\" = ? AND \"opId\" <> ?) OR EXISTS (SELECT 1 FROM \"SyncOpDeadLetter\" WHERE"
                + " \"organizationId\" = ? AND \"deviceId\" = ? AND \"deviceSeq\" = ? AND \"opId\" <> ?)")
                .params(device.organizationId(), device.id(), op.deviceSeq(), op.opId(), device.organizationId(),
                        device.id(), op.deviceSeq(), op.opId())
                .query(Boolean.class).single();
    }

    private void acceptSeq(Device device, Op op) {
        if (device.id() == null) {
            return;
        }
        try {
            jdbc.sql("UPDATE \"PosDevice\" SET \"lastAcceptedSeq\" = ? WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"lastAcceptedSeq\" < ?").params(op.deviceSeq(), device.id(), device.organizationId(),
                            op.deviceSeq()).update();
        } catch (RuntimeException e) {
            log.warn("lastAcceptedSeq update failed: {}", e.toString());
        }
    }

    private void remember(Object clientId, Map<String, Object> body, Map<String, String> clientIdMap) {
        if (PosSql.truthy(clientId) && body != null && PosSql.truthy(body.get("id"))) {
            clientIdMap.put(String.valueOf(clientId), String.valueOf(body.get("id")));
        }
    }

    private static Map<String, Object> success(Op op, boolean replayed, int status, Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("status", replayed ? "replayed" : "applied");
        r.put("httpStatus", status);
        r.put("error", null);
        if (body != null && body.get("mapping") != null) {
            r.put("mapping", body.get("mapping"));
        }
        if (body != null && body.get("finalNumbers") != null) {
            r.put("finalNumbers", body.get("finalNumbers"));
        }
        // ADR-S083 O4 (additive): what became of warehouse work — posted, held for a supervisor, or waiting.
        if (body != null && body.get("outcome") != null) {
            r.put("outcome", body.get("outcome"));
        }
        return r;
    }

    /** The {@code code} member of an API error body ({@code OPERATION_PENDING}), if any. */
    static String apiCode(RuntimeException err) {
        if (err instanceof ApiException a && a.hasBody()) {
            Object body = a.body();
            Object c = body instanceof Map<?, ?> map ? map.get("code")
                    : body instanceof JsonNode n && n.get("code") != null ? n.get("code").asString() : null;
            return c == null ? null : String.valueOf(c);
        }
        return null;
    }

    /**
     * ADR-S083 S5: an op that finally applied closes its own dead letter ({@code retried}); a correction op closes the
     * dead letter it supersedes ({@code resolved}, linked by {@code resolvedByOpId}). Both are audited.
     */
    private void afterApplied(Device device, Op op) {
        try {
            int retried = jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'retried', \"resolvedAt\" = " + PosSql.NOW
                    + ", \"resolutionNote\" = 'Applied on retry with the same operation id' WHERE \"organizationId\" = ?"
                    + " AND \"opId\" = ? AND status IN ('open', 'investigating')")
                    .params(device.organizationId(), op.opId()).update();
            if (retried > 0) {
                auditAs(device, op.actorUserId(), "SyncOpDeadLetter", op.opId(), Obj.of("status", "retried",
                        "opId", op.opId()));
            }
            if (PosSql.truthy(op.supersedesOpId())) {
                int resolved = jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'resolved', \"resolvedAt\" = "
                        + PosSql.NOW + ", \"resolvedById\" = ?, \"resolvedByOpId\" = ?, \"resolutionNote\" = ?"
                        + " WHERE \"organizationId\" = ? AND \"opId\" = ? AND status IN ('open', 'investigating')")
                        .params(op.actorUserId(), op.opId(), "Corrected by operation " + op.opId(),
                                device.organizationId(), op.supersedesOpId()).update();
                if (resolved > 0) {
                    auditAs(device, op.actorUserId(), "SyncOpDeadLetter", op.supersedesOpId(), Obj.of("status",
                            "resolved", "resolvedByOpId", op.opId()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("dead-letter follow-up for {} failed: {}", op.opId(), e.toString());
        }
    }

    private void auditAs(Device device, String actor, String entity, String id, Map<String, Object> data) {
        TenantContext.run(TenantStore.user(device.organizationId(), actor), () -> {
            audit.record(AuditInput.of(entity, id, "update", null, data));
            return null;
        });
    }

    /**
     * ADR-S083 S5 supervisor retry of a dead-lettered op: the stored op runs again under its ORIGINAL opId. Only for
     * failures marked retryable; the device's revocation cut-off is the supervisor's call, so it is not re-applied.
     */
    public Map<String, Object> retry(Device device, Op op) {
        return applyWithoutCutoff(device, op);
    }

    /**
     * ADR-S083 S2 supervisor recovery of a quarantined op: the stored op runs once under its original opId, with the
     * actor's current permissions re-checked. The sequence and evidence checks are made by the caller.
     */
    public Map<String, Object> recover(Device device, Op op) {
        return applyWithoutCutoff(device, op);
    }

    private Map<String, Object> applyWithoutCutoff(Device device, Op op) {
        Map<String, Object> result = applyOp(new Device(device.id(), device.organizationId(), device.branchId()), op,
                new HashMap<>(), new LinkedHashSet<>(), new HashMap<>());
        return result;
    }

    /** Nest {@code HttpException.message}: the string response, or the response object's {@code message}. */
    static String errorMessage(RuntimeException err) {
        if (err instanceof ApiException a && a.hasBody()) {
            Object body = a.body();
            Object m = body instanceof Map<?, ?> map ? map.get("message")
                    : body instanceof JsonNode n && n.get("message") != null ? n.get("message").asString() : null;
            if (m instanceof String s) {
                return s;
            }
            if (m != null) {
                return String.valueOf(m);
            }
        }
        return err.getMessage() == null ? "unknown error" : err.getMessage();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (body instanceof JsonNode n) {
            return n.isObject() ? mapper.convertValue(n, Map.class) : null;
        }
        return mapper.convertValue(body, Map.class);
    }

    /** The till a branch-relayed op came from: the first {@code device:<id>} hop of its originChain. */
    static String originDevice(Op op) {
        for (String hop : op.originChain() == null ? List.<String>of() : op.originChain()) {
            if (hop != null && hop.startsWith("device:") && hop.length() > 7) {
                return hop.substring(7);
            }
        }
        return null;
    }

    /** A {@code hold:<code>} hop a branch added for a code HQ knows ({@link #BRANCH_HOLDS}), else null. */
    static String holdMarker(Op op) {
        for (String hop : op.originChain() == null ? List.<String>of() : op.originChain()) {
            if (hop != null && hop.startsWith("hold:") && BRANCH_HOLDS.contains(hop.substring(5))) {
                return hop.substring(5);
            }
        }
        return null;
    }

    private boolean originOfBranch(Device relay, String origin) {
        return !origin.equals(relay.id()) && jdbc.sql("SELECT count(*) FROM \"PosDevice\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"branchId\" IS NOT DISTINCT FROM ? AND platform <> 'branch'")
                .params(origin, relay.organizationId(), relay.branchId()).query(Long.class).single() > 0;
    }

    /** Client ids this op depends on (references created by earlier ops). */
    static List<String> referencedClientIds(Map<String, Object> payload) {
        List<String> refs = new ArrayList<>();
        Object session = payload.get("cashSessionId") != null ? payload.get("cashSessionId") : payload.get("sessionId");
        for (Object ref : new Object[] {session, payload.get("partnerId"), payload.get("invoiceId"),
            payload.get("reservationId"), payload.get("transferId")}) {
            if (ref instanceof String s && !s.isEmpty()) {
                refs.add(s);
            }
        }
        return refs;
    }

    /**
     * ADR-S083 O2: swap client-minted ids for server ids created earlier in the batch, else by an op this device had
     * applied in an EARLIER batch (the applied-op ledger keeps each result). Without the ledger fallback an op retried
     * in a later batch than the op that created its reference — a sale after the offline shift-open it belongs to —
     * could never resolve it. The client never rewrites a sent payload (its hash must stay stable), so the server
     * resolves. A reference matches the creating op's {@code opId} (result {@code id}) or a {@code mapping} key
     * (e.g. {@code cash_session.open}'s clientId); same organization and device only.
     */
    Map<String, Object> resolveRefsDurable(Device device, Map<String, Object> payload, Map<String, String> clientIdMap) {
        Map<String, Object> out = resolveRefs(payload, clientIdMap);
        if (device.id() == null) {
            return out;
        }
        for (String key : REF_KEYS) {
            if (!(out.get(key) instanceof String s) || s.isEmpty() || clientIdMap.containsValue(s)) {
                continue;
            }
            // 1. The creating op's own id (web client: clientId == opId) — unique index on (organizationId, opId).
            String id = jdbc.sql("SELECT COALESCE(\"resultJson\"->'mapping'->>?, \"resultJson\"->>'id') FROM \"SyncAppliedOp\""
                    + " WHERE \"organizationId\" = ? AND \"opId\" = ? AND \"deviceId\" = ? AND state = 'applied'")
                    .params(s, device.organizationId(), s, device.id()).query(String.class).optional().orElse(null);
            if (id == null || id.isEmpty()) {
                // 2. A shift-open's clientId (Android mints it apart from the opId) — partial index (V595).
                id = jdbc.sql("SELECT \"resultJson\"->'mapping'->>? FROM \"SyncAppliedOp\" WHERE \"organizationId\" = ?"
                        + " AND \"deviceId\" = ? AND \"opType\" = 'cash_session.open' AND state = 'applied'"
                        + " AND jsonb_exists(\"resultJson\"->'mapping', ?) LIMIT 1")
                        .params(s, device.organizationId(), device.id(), s).query(String.class).optional().orElse(null);
            }
            if (id != null && !id.isEmpty()) {
                out.put(key, id);
            }
        }
        return out;
    }

    static final List<String> REF_KEYS = List.of("cashSessionId", "sessionId", "invoiceId", "reservationId",
            "transferId"); // transferId: ADR-S083 O4 (a receive behind its own device's dispatch)

    /** Swap client-minted ids for server ids created earlier in the batch. */
    static Map<String, Object> resolveRefs(Map<String, Object> payload, Map<String, String> clientIdMap) {
        Map<String, Object> out = new LinkedHashMap<>(payload);
        for (String key : REF_KEYS) {
            Object v = out.get(key);
            if (v instanceof String s && clientIdMap.containsKey(s)) {
                out.put(key, clientIdMap.get(s));
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : v instanceof String s ? s : PosSql.jsString(v);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : PosSql.dec(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /**
     * IPR SW-7 / ADR-S088 (owner decision P15-1): the op's device time is accepted and flagged, never refused — a time
     * too far ahead is replaced by the server receive time ({@code clockSkew}), an old one is kept ({@code lateSync}).
     * Both raise a report-only discrepancy row. The op then runs with its (clamped) device time bound as the effective
     * time of every stock move it makes (IPR M-1). Duplicate protection by op id is unchanged.
     */
    private Map<String, Object> runOp(Device device, Op received, Map<String, String> clientIdMap) {
        OccurredAt.Resolved occ = OccurredAt.resolveFlagged(received.occurredAt());
        Op op = occ != null && occ.clockSkew() ? new Op(received.opId(), received.deviceSeq(), received.type(),
                received.actorUserId(), com.inventoryerp.kernel.web.JsonConfig.toJsIso(occ.at()), received.payload(),
                received.originChain(), received.supersedesOpId()) : received;
        if (occ != null && occ.flagged()) {
            // IPR review 1: report-only, so its commit never anchors the op's ledger row (a service op that then
            // fails must stay retryable).
            ledger.outside(() -> {
                stockOps.flagTime(stockCtx(device, received), occ);
                return null;
            });
        }
        return com.inventoryerp.kernel.common.EffectiveTime.offline(occ == null ? null : occ.at(),
                () -> runOpBody(device, op, clientIdMap));
    }

    private Map<String, Object> runOpBody(Device device, Op op, Map<String, String> clientIdMap) {
        Map<String, Object> payload = resolveRefsDurable(device, op.payload() == null ? Map.of() : op.payload(),
                clientIdMap);
        String occurredAt = op.occurredAt();
        Map<String, Object> m = new LinkedHashMap<>();
        switch (op.type()) {
            case "cash_session.open" -> {
                Map<String, Object> session = cashSessions.open(new CashSessionService.OpenInput(
                        str(payload.get("openingSourceAccountId")), obj(payload.get("openingAccounts")),
                        str(payload.get("cashRegisterId")), decimal(payload.get("openingFloat")), str(payload.get("notes")),
                        obj(payload.get("openingDenomination")), null, occurredAt, null, null, null, null));
                String sessionId = (String) session.get("id");
                if (PosSql.truthy(payload.get("clientId"))) {
                    clientIdMap.put(String.valueOf(payload.get("clientId")), sessionId);
                }
                stamp("CashSession", sessionId, device.id(), null);
                m.put("id", sessionId);
                m.put("mapping", Map.of(PosSql.truthy(payload.get("clientId")) ? String.valueOf(payload.get("clientId"))
                        : "sessionId", sessionId));
            }
            case "cash_session.close" -> {
                if (PosSql.truthy(payload.get("sessionId"))) {
                    // Claimed, per-job/per-line keyed work: safe to run again, so it never anchors the ledger row.
                    ledger.outside(() -> {
                        drainStockPostings(String.valueOf(payload.get("sessionId")));
                        return null;
                    });
                }
                Map<String, Object> closed = cashSessions.close(new CashSessionService.CloseInput(
                        obj(payload.get("closingAccounts")), obj(payload.get("uncountedAccounts")), null,
                        decimal(payload.get("closingCounted")), str(payload.get("notes")), str(payload.get("varianceReason")),
                        str(payload.get("varianceStatus")), null, str(payload.get("approvedById")),
                        str(payload.get("approverEmail")), str(payload.get("managerPin")),
                        obj(payload.get("closingDenomination")), occurredAt, str(payload.get("sessionId")),
                        str(payload.get("syncOverrideReason"))), false);
                m.put("id", closed == null ? null : closed.get("id"));
            }
            case "cash_session.movement" -> {
                Map<String, Object> movement = cashSessions.recordMovement(str(payload.get("sessionId")),
                        new CashSessionService.MovementInput(str(payload.get("counterpartAccountId")), null,
                                str(payload.get("movementType")), decimal(payload.get("amount")), str(payload.get("reason")),
                                occurredAt, str(payload.get("approvedById")), str(payload.get("approverEmail")),
                                str(payload.get("managerPin"))));
                m.put("id", movement.get("id"));
            }
            case "cash_drawer.open" -> {
                // LP2b: a drawer opening made at the till (a no-sale opening needs the actor's own open shift).
                Map<String, Object> opening = cashDrawer.record(new PosCashDrawerService.OpeningInput(
                        str(payload.get("sessionId")), str(payload.get("cashRegisterId")), str(payload.get("reason")),
                        str(payload.get("note")), str(payload.get("referenceType")), str(payload.get("referenceId")),
                        occurredAt));
                m.put("id", opening.get("id"));
            }
            case "sale.checkout" -> {
                Map<String, Object> checkout = new LinkedHashMap<>(payload);
                checkout.remove("clientId");
                Object provisional = checkout.remove("provisionalNumber");
                checkout.put("occurredAt", occurredAt);
                // Decision 1 (ADR-S072): the sale happened offline — keep it; an unconvertible unit holds its posting.
                Map<String, Object> res = com.inventoryerp.kernel.common.OfflineReplay.run(() -> pos.checkout(checkout));
                stampSale(res, device.id(), str(provisional));
                m.put("id", res.get("invoiceId"));
                m.put("mapping", mapOf("invoiceId", res.get("invoiceId"), "orderId", res.get("orderId"),
                        "receiptId", res.get("receiptId") == null ? "" : res.get("receiptId")));
                m.put("finalNumbers", mapOf("invoiceNumber", res.get("invoiceNumber"), "orderNumber", res.get("orderNumber")));
            }
            case "tab.settle" -> {
                Map<String, Object> settle = new LinkedHashMap<>(payload);
                settle.remove("clientId");
                Object provisional = settle.remove("provisionalNumber");
                Object tableId = settle.remove("tableId");
                settle.put("occurredAt", occurredAt);
                Map<String, Object> res = pos.settleTab(str(tableId), settle);
                stampSale(res, device.id(), str(provisional));
                m.put("id", res.get("invoiceId"));
                m.put("mapping", mapOf("invoiceId", res.get("invoiceId")));
                m.put("finalNumbers", mapOf("invoiceNumber", res.get("invoiceNumber") == null ? ""
                        : res.get("invoiceNumber")));
            }
            case "sale.refund", "sale.void" -> {
                boolean isVoid = "sale.void".equals(op.type());
                if (!PosSql.truthy(payload.get("invoiceId"))) {
                    throw http(op.type() + " requires an invoiceId", 400);
                }
                String reason = isVoid ? "VOID: " + (payload.get("reason") == null ? "" : str(payload.get("reason")))
                        : str(payload.get("reason"));
                List<Map<String, Object>> lines = null;
                if (!isVoid && payload.get("lines") instanceof List<?> l && !l.isEmpty()) {
                    lines = new ArrayList<>();
                    for (Object o : l) {
                        lines.add(lineByNumber(String.valueOf(payload.get("invoiceId")), PosSql.map(o)));
                    }
                }
                String invoiceId = String.valueOf(payload.get("invoiceId"));
                String disposition = offlineStockDisposition(payload.get("stockDisposition"));
                boolean holdStock = false;
                if ("restock".equals(disposition)) {
                    // R1 (P1-3): the sale may have synced in this same push with its stock still queued; post it first
                    // (claimed, per-job/per-line keyed work: safe to repeat, so it never anchors the ledger row). A
                    // posting that still cannot complete holds the restock instead of dead-lettering the refund.
                    holdStock = ledger.outside(() -> drainSaleStockPostings(invoiceId));
                }
                Map<String, Object> res = holdStock
                        ? billing.refundWithHeldStock(invoiceId, reason, str(payload.get("overrideById")),
                                str(payload.get("overridePin")), str(payload.get("cashSessionId")), lines)
                        : billing.refund(invoiceId, reason, str(payload.get("overrideById")),
                                str(payload.get("overridePin")), disposition, str(payload.get("cashSessionId")), lines);
                if (holdStock) {
                    stockOps.holdRefundRestock(stockCtx(device, op), invoiceId, res == null ? null
                            : (String) res.get("refundId"), lines);
                }
                Object invId = res != null && res.get("invoiceId") != null ? res.get("invoiceId") : invoiceId;
                m.put("id", invId);
                m.put("mapping", mapOf("invoiceId", invId));
            }
            case "reservation.create" -> {
                Map<String, Object> dto = new LinkedHashMap<>(payload);
                dto.remove("clientId");
                Map<String, Object> r = reservations.create(dto);
                m.put("id", r.get("id"));
                m.put("mapping", mapOf("reservationId", r.get("id")));
            }
            case "reservation.seat", "reservation.cancel", "reservation.noShow" -> {
                if (!PosSql.truthy(payload.get("reservationId"))) {
                    throw http(op.type() + " requires a reservationId", 400);
                }
                String rid = String.valueOf(payload.get("reservationId"));
                Map<String, Object> r = switch (op.type()) {
                    case "reservation.seat" -> reservations.seat(rid, str(payload.get("orderId")));
                    case "reservation.cancel" -> reservations.cancel(rid);
                    default -> reservations.markNoShow(rid);
                };
                m.put("id", r.get("id"));
                m.put("mapping", mapOf("reservationId", r.get("id")));
            }
            case "customer.upsert" -> {
                Object idRaw = payload.get("id") != null ? payload.get("id") : payload.get("clientId");
                String id = idRaw == null ? "" : String.valueOf(idRaw);
                if (id.isEmpty()) {
                    throw http("customer.upsert requires an id", 400);
                }
                if (!(payload.get("name") instanceof String name) || name.isEmpty()) {
                    throw http("customer.upsert requires a name", 400);
                }
                String partner = upsertPartner(device, id, payload);
                m.put("id", partner);
                m.put("mapping", mapOf("customerId", partner));
            }
            case "customer.delete" -> {
                String id = payload.get("id") == null ? "" : String.valueOf(payload.get("id"));
                if (id.isEmpty()) {
                    throw http("customer.delete requires an id", 400);
                }
                scopedUpdate(Partner.class, id, mapOf("deletedAt", PosDb.raw(PosSql.NOW)), "\"isCustomer\" = true");
                m.put("id", id);
            }
            case "setting.set" -> {
                String key = payload.get("key") == null ? "" : String.valueOf(payload.get("key"));
                Object value = payload.get("value");
                if (!"pos.mode".equals(key)) {
                    throw http("Device may not set setting '" + key + "'", 400);
                }
                if (!"cafe".equals(value) && !"retail".equals(value)) {
                    throw http("Invalid pos.mode value: " + (value == null ? "undefined" : PosSql.jsString(value)), 400);
                }
                posSettings.updatePosSettings(mapOf("posMode", value));
                m.put("id", key);
            }
            case "menuCategory.upsert" -> {
                String id = requireId(payload);
                thinUpsert(MenuCategory.class, id, mapOf("name", payload.get("name"), "parentId", payload.get("parentId"),
                        "image", payload.get("image"), "icon", payload.get("icon"),
                        "displayOrder", nz(payload.get("displayOrder"), 0), "isActive", nz(payload.get("isActive"), true)));
                m.put("id", id);
                m.put("mapping", mapOf("menuCategoryId", id));
            }
            case "menuCategory.delete", "modifierGroup.delete", "accompanimentGroup.delete", "tax.delete" -> {
                String id = requireId(payload);
                Class<?> entity = switch (op.type()) {
                    case "menuCategory.delete" -> MenuCategory.class;
                    case "modifierGroup.delete" -> ModifierGroup.class;
                    case "accompanimentGroup.delete" -> AccompanimentGroup.class;
                    default -> Tax.class;
                };
                scopedUpdate(entity, id, mapOf("deletedAt", PosDb.raw(PosSql.NOW), "isActive", false), null);
                m.put("id", id);
            }
            case "menuItem.upsert" -> m.putAll(upsertMenuItem(payload));
            case "menuItem.delete" -> {
                String id = requireId(payload);
                scopedUpdate(MenuItem.class, id, mapOf("isAvailable", false), null);
                m.put("id", id);
            }
            case "modifierGroup.upsert" -> m.putAll(upsertModifierGroup(payload));
            case "accompanimentGroup.upsert" -> m.putAll(upsertAccompanimentGroup(payload));
            case "tax.upsert" -> {
                String id = requireId(payload);
                Map<String, Object> data = mapOf("name", payload.get("name"), "rate", nz(payload.get("rate"), 0),
                        "isInclusive", nz(payload.get("isInclusive"), false), "isActive", nz(payload.get("isActive"), true));
                if (payload.containsKey("code")) {
                    data.put("code", payload.get("code"));
                }
                thinUpsert(Tax.class, id, data);
                m.put("id", id);
                m.put("mapping", mapOf("taxId", id));
            }
            case "productCategory.upsert" -> {
                String id = requireId(payload);
                thinUpsert(ProductCategory.class, id, mapOf("name", payload.get("name"), "parentId", payload.get("parentId")));
                m.put("id", id);
                m.put("mapping", mapOf("productCategoryId", id));
            }
            case "productCategory.delete" -> {
                String id = requireId(payload);
                scopedUpdate(ProductCategory.class, id, mapOf("deletedAt", PosDb.raw(PosSql.NOW)), null);
                m.put("id", id);
            }
            case "posTable.upsert" -> {
                String id = requireId(payload);
                Map<String, Object> data = mapOf("name", payload.get("name") != null ? payload.get("name")
                        : str(nz(payload.get("number"), "")), "number", (int) PosSql.num(nz(payload.get("number"), 0)),
                        "sortOrder", nz(payload.get("sortOrder"), 0));
                if (payload.containsKey("seats")) {
                    data.put("seats", (int) PosSql.num(payload.get("seats")));
                }
                if (payload.containsKey("zone")) {
                    data.put("zone", payload.get("zone"));
                }
                data.put("active", nz(payload.get("active"), true));
                thinUpsert(PosTable.class, id, data);
                m.put("id", id);
                m.put("mapping", mapOf("posTableId", id));
            }
            case "posTable.delete" -> {
                String id = requireId(payload);
                scopedUpdate(PosTable.class, id, mapOf("active", false), null);
                m.put("id", id);
            }
            case "product.upsert" -> {
                String id = requireId(payload);
                boolean exists = scopedFind(Product.class, id) != null;
                Set<String> keys = new LinkedHashSet<>(List.of("name", "sku", "barcode", "description", "categoryId",
                        "taxId", "salesPrice", "costPrice", "image", "isActive"));
                String productType = PosSql.truthy(payload.get("productType")) ? str(payload.get("productType")) : null;
                if (productType != null) {
                    keys.add("productType");
                }
                ProductInput fields = new ProductInput(exists ? null : id,
                        exists ? null : str(payload.get("code") != null ? payload.get("code") : deriveCode("P", id)),
                        str(payload.get("name")), str(payload.get("sku")), str(payload.get("barcode")),
                        str(payload.get("description")), str(payload.get("categoryId")), str(payload.get("taxId")),
                        decimal(payload.get("salesPrice")), decimal(payload.get("costPrice")), str(payload.get("image")),
                        payload.get("isActive") == null ? Boolean.TRUE : (Boolean) payload.get("isActive"), productType);
                if (!exists) {
                    keys.add("id");
                    keys.add("code");
                }
                EntityPatch.withPresentKeys(keys, () -> exists ? products.update(id, fields, null)
                        : products.create(fields, null));
                m.put("id", id);
                m.put("mapping", mapOf("productId", id));
            }
            case "product.delete" -> {
                String id = requireId(payload);
                products.remove(id);
                m.put("id", id);
            }
            case "cashRegister.upsert" -> {
                String id = requireId(payload);
                Map<String, Object> existing = scopedFind(CashRegister.class, id);
                if (existing != null) {
                    scopedUpdate(CashRegister.class, id, mapOf("name", nz(payload.get("name"), existing.get("name"))), null);
                } else {
                    cashRegisters.create(mapOf("id", id, "code", payload.get("code") != null ? payload.get("code")
                            : deriveCode("REG", id), "name", nz(payload.get("name"), "Register")));
                }
                m.put("id", id);
                m.put("mapping", mapOf("cashRegisterId", id));
            }
            case "cashRegister.delete" -> {
                String id = requireId(payload);
                cashRegisters.remove(id);
                m.put("id", id);
            }
            case "staff.pinChange" -> {
                String userId = PosSql.truthy(payload.get("userId")) ? String.valueOf(payload.get("userId")) : "";
                String newPinHash = PosSql.truthy(payload.get("newPinHash")) ? String.valueOf(payload.get("newPinHash")) : "";
                if (userId.isEmpty() || newPinHash.isEmpty()) {
                    throw http("staff.pinChange requires userId and newPinHash", 400);
                }
                String actorId = op.actorUserId() == null ? "" : op.actorUserId();
                if (!actorId.isEmpty() && !actorId.equals(userId)) {
                    throw http("staff.pinChange may only change the actor's own PIN", 403);
                }
                int updated = db.updateMany(User.class, mapOf("pinHash", newPinHash), "id = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL", userId, TenantContext.organizationId());
                if (updated == 0) {
                    throw http("User not found in this organization", 404);
                }
                m.put("id", userId);
            }
            case "message.send" -> m.putAll(sendMessage(op, payload, occurredAt));
            case "message.markRead" -> {
                String conversationId = payload.get("conversationId") == null ? "" : str(payload.get("conversationId"));
                String messageId = PosSql.truthy(payload.get("messageId")) ? str(payload.get("messageId")) : "";
                if (conversationId.isEmpty() || messageId.isEmpty()) {
                    throw http("message.markRead requires conversationId and messageId", 400);
                }
                db.updateMany(ConversationParticipant.class, mapOf("lastReadMessageId", messageId),
                        "\"organizationId\" = ? AND \"conversationId\" = ? AND \"userId\" = ? AND \"participantType\" = 'user'",
                        TenantContext.organizationId(), conversationId, op.actorUserId());
                m.put("id", conversationId);
            }
            case "stock.in", "stock.out" -> {
                List<DirectStockService.Line> items = new ArrayList<>();
                for (Object o : PosSql.list(payload.get("items"))) {
                    Map<String, Object> it = PosSql.map(o);
                    // IPR C-3 / GL-6 (D3): a device cannot give the reason and permission an explicit 0 needs, so
                    // an offline stock-in at 0 is taken as no cost given (the current cost), never refused.
                    BigDecimal unitCost = decimal(it.get("unitCost"));
                    if (unitCost != null && unitCost.signum() == 0) {
                        unitCost = null;
                    }
                    items.add(new DirectStockService.Line(str(it.get("productId")), str(it.get("variantId")),
                            decimal(it.get("quantity")), unitCost, str(it.get("batchNumber")),
                            str(it.get("expiryDate")), str(it.get("distStrategy")), str(it.get("notes"))));
                }
                DirectStockService.Request req = new DirectStockService.Request(str(payload.get("locationId")),
                        payload.get("responsibleById") != null ? str(payload.get("responsibleById")) : op.actorUserId(),
                        str(payload.get("approvedById")), str(payload.get("approverPin")), items, str(payload.get("notes")));
                Map<String, Object> doc = "stock.in".equals(op.type()) ? directStock.directIn(req) : directStock.directOut(req);
                Object docId = doc == null ? null : doc.get("id");
                if ("stock.out".equals(op.type())) {
                    // ADR-S083 S4: an offline issue that takes the location below zero is flagged, not refused.
                    List<Map<String, Object>> lines = new ArrayList<>();
                    PosSql.list(payload.get("items")).forEach(o -> lines.add(PosSql.map(o)));
                    stockOps.flagNegativeLines(stockCtx(device, op), str(payload.get("locationId")), lines,
                            "direct_stock_out", doc == null ? null : str(doc.get("code")));
                }
                m.put("id", docId);
                m.put("mapping", mapOf("stockDocId", docId == null ? "" : String.valueOf(docId)));
            }
            // Audit S-5: opening the count, entering the figures and submitting are one transaction, so a refused
            // count leaves no open count behind.
            case "stock.count" -> m.putAll(tx.execute(st -> applyStockCount(stockCtx(device, op), payload,
                    op.occurredAt())));
            // ADR-S083 O4 (S4): warehouse work recorded offline.
            case "transfer.dispatch" -> m.putAll(stockOps.dispatch(stockCtx(device, op), payload));
            case "transfer.receive" -> m.putAll(stockOps.receive(stockCtx(device, op), payload));
            case "stock.adjust" -> m.putAll(stockOps.adjust(stockCtx(device, op), payload));
            case "grn.receive" -> m.putAll(stockOps.receiveGoods(stockCtx(device, op), payload));
            case "salesOrder.create" -> m.putAll(salesOps.order(stockCtx(device, op), payload));
            case "quote.create" -> m.putAll(salesOps.quote(stockCtx(device, op), payload));
            case "payment.receive" -> m.putAll(salesOps.payment(stockCtx(device, op), payload));
            case "kitchen.void" -> m.putAll(kitchenVoid(device, op, payload));
            case "expense.create" -> {
                Map<String, Object> e = expenses.create(new ExpensesService.CreateInput(str(payload.get("title")),
                        str(payload.get("description")), decimal(payload.get("amount")), str(payload.get("categoryId")),
                        payload.get("expenseDate") != null ? str(payload.get("expenseDate")) : occurredAt,
                        str(payload.get("notes")), str(payload.get("supplierId")), str(payload.get("paymentType")),
                        str(payload.get("paymentMethod")), str(payload.get("accountId")),
                        str(payload.get("paymentReference"))));
                Object id = e == null ? null : e.get("id");
                m.put("id", id);
                m.put("mapping", mapOf("expenseId", id == null ? "" : String.valueOf(id),
                        "expenseCode", e == null || e.get("expenseCode") == null ? "" : String.valueOf(e.get("expenseCode"))));
            }
            default -> throw http("Unsupported sync op type: " + op.type(), 400);
        }
        return m;
    }

    /** Fields a device may author on a product ({@code product.upsert}). */
    public record ProductInput(String id, String code, String name, String sku, String barcode, String description,
            String categoryId, String taxId, BigDecimal salesPrice, BigDecimal costPrice, String image, Boolean isActive,
            String productType) {}

    /** JS {@code new Date(iso)} for a validated ISO string. */
    static Instant jsDate(String iso) {
        return EntityPatch.toInstant(iso);
    }

    /**
     * Phase I6 (additive): a device never learns server invoice-line ids, so a partial refund names its lines by
     * {@code lineNumber} (1-based position in the sale it sent; the invoice keeps that order). A line that already
     * carries a {@code lineId} is passed through unchanged; an unknown number leaves it unresolved, so the refund
     * answers "Refund line is not on this invoice".
     */
    private Map<String, Object> lineByNumber(String invoiceId, Map<String, Object> line) {
        if (PosSql.truthy(line.get("lineId")) || line.get("lineNumber") == null) {
            return line;
        }
        Map<String, Object> out = new LinkedHashMap<>(line);
        out.remove("lineNumber");
        out.put("lineId", jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ? AND \"organizationId\" = ? AND"
                + " \"lineNumber\" = ? LIMIT 1").params(invoiceId, TenantContext.organizationId(),
                        PosSql.dec(line.get("lineNumber")).intValue()).query(String.class).optional().orElse(null));
        return out;
    }

    /**
     * Phase I6: a line taken off a device-held tab after its kitchen ticket printed. The tab never reached the server
     * (it is sent as one {@code sale.checkout} when settled), so there is no order line to void: the manager approval
     * is re-verified as for {@code DELETE /pos/orders/:id/items/:itemId} and the started food is issued as kitchen
     * waste at the shift's register location ({@link KitchenWaste#recordDeviceVoid}).
     */
    private Map<String, Object> kitchenVoid(Device device, Op op, Map<String, Object> payload) {
        String reason = payload.get("reason") == null ? null : str(payload.get("reason")).trim();
        if (reason == null || reason.isEmpty()) {
            throw ApiException.badRequest("A reason is required to void an item");
        }
        String overrideById = str(payload.get("overrideById"));
        if (!PosSql.truthy(overrideById)) {
            throw ApiException.forbidden(
                    "This item was already sent to the kitchen — a manager approval and PIN are required to void it");
        }
        overrides.verifyOperationApproval(overrideById, str(payload.get("overridePin")), "void");
        List<KitchenWaste.DeviceLine> lines = new ArrayList<>();
        for (Object o : PosSql.list(payload.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            double qty = l.get("quantity") == null ? 0 : PosSql.num(l.get("quantity"));
            if (!PosSql.truthy(l.get("menuItemId")) && !PosSql.truthy(l.get("productId")) || !(qty > 0)) {
                throw http("kitchen.void lines need a menuItemId or productId and a positive quantity", 400);
            }
            lines.add(new KitchenWaste.DeviceLine(str(l.get("menuItemId")), str(l.get("productId")),
                    str(l.get("variantId")), qty, str(l.get("description"))));
        }
        if (lines.isEmpty()) {
            throw http("kitchen.void requires lines", 400);
        }
        String orgId = TenantContext.organizationId();
        int wasted = kitchenWaste.recordDeviceVoid(orgId, str(payload.get("cashSessionId")), "device:" + op.opId(),
                str(payload.get("tableLabel")), lines, reason, overrideById);
        List<Map<String, Object>> voided = new ArrayList<>();
        lines.forEach(l -> voided.add(mapOf("description", l.description(), "menuItemId", l.menuItemId(), "productId",
                l.productId(), "variantId", l.variantId(), "quantity", PosSql.js(l.quantity()))));
        audit.record(AuditInput.of("PosDevice", device.id(), "cancel", null, mapOf("kind", "item_void", "source",
                "device_tab", "tableLabel", payload.get("tableLabel"), "reason", reason, "approvedById", overrideById,
                "hadBeenFired", true, "lines", voided, "wastedLines", wasted)));
        return mapOf("id", op.opId(), "mapping", mapOf("kitchenVoidId", op.opId()));
    }

    static String offlineStockDisposition(Object value) {
        return "waste".equals(value) || "no_return".equals(value) || "restock".equals(value) ? (String) value : "restock";
    }

    private static Object nz(Object a, Object b) {
        return a != null ? a : b;
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String requireId(Map<String, Object> payload) {
        String id = PosSql.truthy(payload.get("id")) ? String.valueOf(payload.get("id")) : "";
        if (id.isEmpty()) {
            throw http("Master-data op requires an id", 400);
        }
        return id;
    }

    static String deriveCode(String prefix, String id) {
        String hex = id.replace("-", "");
        return prefix + "-" + hex.substring(0, Math.min(10, hex.length())).toUpperCase();
    }

    // ─── tenant-scoped thin writes (Prisma client through the tenancy extension) ───

    private String where(Class<?> entity, String extra) {
        return TenancyScope.predicate(entity, null) + " AND id = ?" + (extra == null ? "" : " AND " + extra);
    }

    private Object[] args(Class<?> entity, String id) {
        return TenancyScope.orgScoped(entity) ? new Object[] {TenantContext.organizationId(), id} : new Object[] {id};
    }

    private Map<String, Object> scopedFind(Class<?> entity, String id) {
        return db.first(entity, where(entity, null), args(entity, id));
    }

    private int scopedUpdate(Class<?> entity, String id, Map<String, Object> data, String extra) {
        return db.updateMany(entity, data, where(entity, extra), args(entity, id));
    }

    private void thinUpsert(Class<?> entity, String id, Map<String, Object> data) {
        if (scopedFind(entity, id) != null) {
            scopedUpdate(entity, id, data, null);
        } else {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("id", id);
            if (TenancyScope.orgScoped(entity)) {
                create.put("organizationId", TenantContext.organizationId());
            }
            data.forEach((k, v) -> {
                if (v != null) {
                    create.put(k, v);
                }
            });
            db.insert(entity, create);
        }
    }

    private Map<String, Object> upsertMenuItem(Map<String, Object> payload) {
        String id = requireId(payload);
        Map<String, Object> data = mapOf("code", payload.get("code"), "name", payload.get("name"),
                "description", payload.get("description"), "categoryId", payload.get("categoryId"),
                "basePrice", payload.get("basePrice"), "taxId", payload.get("taxId"), "image", payload.get("image"),
                "isAvailable", nz(payload.get("isAvailable"), true), "displayOrder", nz(payload.get("displayOrder"), 0));
        if (payload.containsKey("isInventoryTracked")) {
            data.put("isInventoryTracked", PosSql.truthy(payload.get("isInventoryTracked")));
        }
        thinUpsert(MenuItem.class, id, data);
        if (payload.get("variants") instanceof List<?> variants) {
            List<String> keep = new ArrayList<>();
            for (Object v : variants) {
                Object vid = PosSql.map(v).get("id");
                if (PosSql.truthy(vid)) {
                    keep.add(String.valueOf(vid));
                }
            }
            retire(MenuItemVariant.class, "menuItemId", id, keep, mapOf("deletedAt", PosDb.raw(PosSql.NOW), "isActive", false));
            for (Object o : variants) {
                Map<String, Object> v = PosSql.map(o);
                if (!PosSql.truthy(v.get("id"))) {
                    continue;
                }
                thinUpsert(MenuItemVariant.class, String.valueOf(v.get("id")), mapOf("menuItemId", id, "name", v.get("name"),
                        "price", nz(v.get("price"), 0), "sortOrder", nz(v.get("sortOrder"), 0), "isActive", true,
                        "deletedAt", null));
            }
        }
        if (payload.get("modifierGroupIds") instanceof List<?> l) {
            reconcileGroupJoin(MenuItemModifierGroup.class, "modifierGroupId", id, l);
        }
        if (payload.get("accompanimentGroupIds") instanceof List<?> l) {
            reconcileGroupJoin(MenuItemAccompanimentGroup.class, "accompanimentGroupId", id, l);
        }
        return mapOf("id", id, "mapping", mapOf("menuItemId", id));
    }

    /** Soft-retire children of {@code parent} whose id is not in {@code keep} (all of them when keep is empty). */
    private void retire(Class<?> entity, String fk, String parent, List<String> keep, Map<String, Object> data) {
        StringBuilder w = new StringBuilder(TenancyScope.predicate(entity, null)).append(" AND \"").append(fk)
                .append("\" = ?");
        List<Object> params = new ArrayList<>();
        if (TenancyScope.orgScoped(entity)) {
            params.add(TenantContext.organizationId());
        }
        params.add(parent);
        if (!keep.isEmpty()) {
            w.append(" AND id NOT IN (").append(PosSql.marks(keep.size())).append(')');
            params.addAll(keep);
        }
        db.updateMany(entity, data, w.toString(), params.toArray());
    }

    private void reconcileGroupJoin(Class<?> entity, String fkField, String menuItemId, List<?> entries) {
        List<String[]> norm = new ArrayList<>();
        for (Object e : entries) {
            if (e instanceof String s) {
                norm.add(new String[] {s, "0"});
            } else {
                Map<String, Object> m = PosSql.map(e);
                Object id = m.get(fkField) != null ? m.get(fkField) : m.get("id");
                norm.add(new String[] {id == null ? "" : String.valueOf(id), PosSql.jsString(nz(m.get("sortOrder"), 0))});
            }
        }
        List<String> ids = norm.stream().map(n -> n[0]).filter(s -> !s.isEmpty()).toList();
        StringBuilder w = new StringBuilder(TenancyScope.predicate(entity, null)).append(" AND \"menuItemId\" = ?");
        List<Object> params = new ArrayList<>();
        if (TenancyScope.orgScoped(entity)) {
            params.add(TenantContext.organizationId());
        }
        params.add(menuItemId);
        if (!ids.isEmpty()) {
            w.append(" AND \"").append(fkField).append("\" NOT IN (").append(PosSql.marks(ids.size())).append(')');
            params.addAll(ids);
        }
        db.updateMany(entity, mapOf("deletedAt", PosDb.raw(PosSql.NOW)), w.toString(), params.toArray());
        for (String[] e : norm) {
            if (e[0].isEmpty()) {
                continue;
            }
            int sortOrder = (int) PosSql.num(e[1]);
            String pred = TenancyScope.predicate(entity, null) + " AND \"menuItemId\" = ? AND \"" + fkField + "\" = ?";
            Object[] p = TenancyScope.orgScoped(entity) ? new Object[] {TenantContext.organizationId(), menuItemId, e[0]}
                    : new Object[] {menuItemId, e[0]};
            Map<String, Object> existing = db.first(entity, pred, p);
            if (existing != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("deletedAt", null);
                data.put("sortOrder", sortOrder);
                db.updateMany(entity, data, where(entity, null), args(entity, (String) existing.get("id")));
            } else {
                db.insert(entity, mapOf("organizationId", TenantContext.organizationId(), "menuItemId", menuItemId,
                        fkField, e[0], "sortOrder", sortOrder));
            }
        }
    }

    private Map<String, Object> upsertModifierGroup(Map<String, Object> payload) {
        String id = requireId(payload);
        thinUpsert(ModifierGroup.class, id, mapOf("name", payload.get("name"), "groupType", nz(payload.get("groupType"),
                "ADD_ON"), "minSelect", nz(payload.get("minSelect"), 0), "maxSelect", nz(payload.get("maxSelect"), 1),
                "sortOrder", nz(payload.get("sortOrder"), 0), "isActive", nz(payload.get("isActive"), true)));
        if (payload.get("modifiers") instanceof List<?> modifiers) {
            List<String> keep = new ArrayList<>();
            modifiers.forEach(o -> {
                Object mid = PosSql.map(o).get("id");
                if (PosSql.truthy(mid)) {
                    keep.add(String.valueOf(mid));
                }
            });
            retire(Modifier.class, "groupId", id, keep, mapOf("deletedAt", PosDb.raw(PosSql.NOW), "isActive", false));
            for (Object o : modifiers) {
                Map<String, Object> mo = PosSql.map(o);
                if (!PosSql.truthy(mo.get("id"))) {
                    continue;
                }
                thinUpsert(Modifier.class, String.valueOf(mo.get("id")), mapOf("groupId", id, "name", mo.get("name"),
                        "kitchenPrintName", mo.get("kitchenPrintName"), "priceDelta", nz(mo.get("priceDelta"), 0),
                        "isDefault", nz(mo.get("isDefault"), false), "sortOrder", nz(mo.get("sortOrder"), 0),
                        "isActive", true, "deletedAt", null));
            }
        }
        return mapOf("id", id, "mapping", mapOf("modifierGroupId", id));
    }

    private Map<String, Object> upsertAccompanimentGroup(Map<String, Object> payload) {
        String id = requireId(payload);
        thinUpsert(AccompanimentGroup.class, id, mapOf("name", payload.get("name"),
                "isRequired", nz(payload.get("isRequired"), true), "minSelect", nz(payload.get("minSelect"), 1),
                "maxSelect", nz(payload.get("maxSelect"), 1), "sortOrder", nz(payload.get("sortOrder"), 0),
                "isActive", nz(payload.get("isActive"), true)));
        if (payload.get("options") instanceof List<?> options) {
            List<String> keep = new ArrayList<>();
            options.forEach(o -> {
                Object oid = PosSql.map(o).get("id");
                if (PosSql.truthy(oid)) {
                    keep.add(String.valueOf(oid));
                }
            });
            retire(AccompanimentOption.class, "groupId", id, keep, mapOf("deletedAt", PosDb.raw(PosSql.NOW),
                    "isActive", false));
            for (Object o : options) {
                Map<String, Object> op = PosSql.map(o);
                if (!PosSql.truthy(op.get("id"))) {
                    continue;
                }
                thinUpsert(AccompanimentOption.class, String.valueOf(op.get("id")), mapOf("groupId", id,
                        "name", op.get("name"), "priceImpact", nz(op.get("priceImpact"), 0),
                        "isDefault", nz(op.get("isDefault"), false), "sortOrder", nz(op.get("sortOrder"), 0),
                        "isActive", true, "deletedAt", null));
            }
        }
        return mapOf("id", id, "mapping", mapOf("accompanimentGroupId", id));
    }

    /** Create-or-update of a POS customer under the client-minted id (loyalty in customFields). */
    private String upsertPartner(Device device, String id, Map<String, Object> payload) {
        Map<String, Object> existing = scopedFind(Partner.class, id);
        Map<String, Object> loyalty = new LinkedHashMap<>();
        if (payload.get("loyaltyPoints") instanceof Number n) {
            loyalty.put("loyaltyPoints", n);
        }
        if (existing != null) {
            Map<String, Object> custom = new LinkedHashMap<>();
            Object current = existing.get("customFields");
            if (current instanceof Map<?, ?> cm) {
                cm.forEach((k, v) -> custom.put(String.valueOf(k), v));
            }
            custom.putAll(loyalty);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", payload.get("name"));
            data.put("phone", payload.get("phone"));
            data.put("email", payload.get("email"));
            data.put("notes", payload.get("note"));
            data.put("isCustomer", true);
            data.put("customFields", PosDb.json(custom));
            scopedUpdate(Partner.class, id, data, null);
            return id;
        }
        String idHex = id.replace("-", "").toUpperCase();
        for (int len : new int[] {8, 16}) {
            // ON CONFLICT instead of catching a duplicate key: the op runs in one transaction (ADR-S083 C1).
            int inserted = jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, phone, email, notes,"
                    + " \"isCustomer\", \"isCompany\", \"customFields\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                    + " true, false, ?::jsonb, " + PosSql.NOW + ", " + PosSql.NOW + ") ON CONFLICT DO NOTHING")
                    .params(id, device.organizationId(), "POS-" + idHex.substring(0, Math.min(len, idHex.length())),
                            str(payload.get("name")), str(payload.get("phone")), str(payload.get("email")),
                            str(payload.get("note")), com.inventoryerp.shared.web.EntityJson.toJson(loyalty))
                    .update();
            if (inserted > 0 || scopedFind(Partner.class, id) != null) {
                return id;
            }
        }
        throw http("Could not allocate a unique code for customer " + id, 409);
    }

    private Map<String, Object> sendMessage(Op op, Map<String, Object> payload, String occurredAt) {
        Object rawId = payload.get("clientId") != null ? payload.get("clientId") : payload.get("id");
        String messageId = rawId == null ? "" : String.valueOf(rawId);
        String conversationId = payload.get("conversationId") == null ? "" : String.valueOf(payload.get("conversationId"));
        String body = payload.get("body") == null ? "" : str(payload.get("body")).trim();
        if (messageId.isEmpty()) {
            throw http("message.send requires a clientId", 400);
        }
        if (conversationId.isEmpty() || body.isEmpty()) {
            throw http("message.send requires conversationId and body", 400);
        }
        Map<String, Object> conv = scopedFind(Conversation.class, conversationId);
        if (conv == null) {
            throw http("Conversation not found", 404);
        }
        if (!Boolean.TRUE.equals(conv.get("syncToDevices"))) {
            throw http("Conversation is not device-visible", 403);
        }
        // ON CONFLICT instead of catching a duplicate key: the op runs in one transaction (ADR-S083 C1), and a failed
        // statement would abort it.
        Map<String, Object> created = SqlRows.one(jdbc.sql("INSERT INTO \"Message\" (id, \"organizationId\","
                + " \"conversationId\", \"senderType\", \"senderUserId\", \"contentType\", body, \"occurredAt\")"
                + " VALUES (?, ?, ?, 'user', ?, 'text', ?, ?) ON CONFLICT DO NOTHING RETURNING id, seq").params(messageId,
                TenantContext.organizationId(), conversationId, op.actorUserId(), body,
                SqlRows.ts(jsDate(occurredAt))));
        if (created == null) {
            String existing = jdbc.sql("SELECT id FROM \"Message\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(messageId, TenantContext.organizationId()).query(String.class)
                    .optional().orElse(null);
            if (existing != null) {
                return mapOf("id", existing, "mapping", mapOf("messageId", existing));
            }
            throw new org.springframework.dao.DuplicateKeyException("Message id " + messageId + " is already taken");
        }
        scopedUpdate(Conversation.class, conversationId, new LinkedHashMap<>(), null);
        return mapOf("id", created.get("id"), "mapping", mapOf("messageId", created.get("id")),
                "syncSequence", String.valueOf(created.get("seq")));
    }

    /** Open (or resume) a spot count over the counted products, enter the device's figures and submit. */
    private static SyncStockService.OpContext stockCtx(Device device, Op op) {
        return new SyncStockService.OpContext(op.opId(), op.type(), device.id(), device.branchId(), op.actorUserId(),
                op.occurredAt());
    }

    private Map<String, Object> applyStockCount(SyncStockService.OpContext ctx, Map<String, Object> payload,
            String occurredAt) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (payload.get("lines") instanceof List<?> l) {
            l.forEach(o -> lines.add(PosSql.map(o)));
        }
        if (!PosSql.truthy(payload.get("locationId")) || lines.isEmpty()) {
            throw http("stock.count requires locationId and lines", 400);
        }
        LinkedHashSet<String> productIds = new LinkedHashSet<>();
        lines.forEach(l -> productIds.add(String.valueOf(l.get("productId"))));
        // IPR review 1 (N5): another type of count is open on the location (S4: one open draft per location). The
        // device's count is a physical fact, so the op is applied and held for staff (report row with the counted
        // lines), never failed and never a second draft.
        Map<String, Object> blocking = counts.blockingDraft(str(payload.get("locationId")), "spot");
        if (blocking != null) {
            String exceptionId = stockOps.holdCount(ctx, str(payload.get("locationId")), lines,
                    String.valueOf(blocking.get("countCode")), String.valueOf(blocking.get("countType")));
            return mapOf("id", exceptionId, "held", true, "mapping", mapOf("countHeldId", exceptionId));
        }
        Map<String, Object> session = counts.start(str(payload.get("locationId")), "spot", str(payload.get("notes")), null,
                null, new ArrayList<>(productIds), null);
        // Phase I2 (audit V-4): a device counts a product's total, or one variant of a product with variants (the
        // session expands such a product into one line per active variant). Key = product + variant ("" = none).
        Map<String, Map<String, Object>> lineByCell = new HashMap<>();
        for (Object o : PosSql.list(session.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            // A resumed screen count split per lot (lotLine) cannot take a device total.
            if (!Boolean.TRUE.equals(l.get("lotLine"))) {
                lineByCell.put(countCell(l.get("productId"), l.get("variantId")), l);
            }
        }
        for (Map<String, Object> l : lines) {
            if (!PosSql.truthy(l.get("variantId"))) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT name, \"hasVariants\" FROM \"Product\" WHERE"
                        + " id = ? AND \"organizationId\" = ?").params(String.valueOf(l.get("productId")),
                                TenantContext.organizationId()));
                if (product != null && Boolean.TRUE.equals(product.get("hasVariants"))) {
                    throw http("Choose a variant for " + product.get("name"), 400);
                }
            }
        }
        long missing = lines.stream().filter(l -> !lineByCell.containsKey(countCell(l.get("productId"),
                l.get("variantId")))).count();
        if (missing > 0) {
            throw http("An open spot count on this location does not cover " + missing + " counted product(s) — finish or"
                    + " cancel it in the back office, then retry", 409);
        }
        List<InventoryCountService.DraftLine> draft = new ArrayList<>();
        java.time.Instant at = OccurredAt.resolve(occurredAt);
        for (Map<String, Object> l : lines) {
            String reason = l.get("reason") instanceof String r && !r.trim().isEmpty() ? r.trim()
                    : payload.get("reason") instanceof String pr && !pr.trim().isEmpty() ? pr.trim() : "Device count";
            // ADR-S083 S4 (deviation, owner-approved): the variance is counted − on-hand when the device counted.
            // IPR X-1c (ADR-S088): the line's cut-off is the device time, so the count carries forward exactly the
            // movements that happened after it (by effective time), and a sale made before it but synced later is a
            // late transaction of this count (count spec section 4), not a second loss.
            BigDecimal counted = PosSql.dec(l.get("countedQty"));
            String lineId = String.valueOf(lineByCell.get(countCell(l.get("productId"), l.get("variantId"))).get("id"));
            draft.add(at == null ? new InventoryCountService.DraftLine(lineId, counted, reason)
                    : InventoryCountService.DraftLine.countedAt(lineId, counted, reason, at));
        }
        String sessionId = String.valueOf(session.get("id"));
        counts.saveDraft(sessionId, draft, null, null);
        counts.submit(sessionId, null, null);
        return mapOf("id", session.get("id"), "mapping", mapOf("countId", sessionId,
                "countCode", session.get("countCode") == null ? "" : String.valueOf(session.get("countCode"))));
    }

    private static String countCell(Object productId, Object variantId) {
        return productId + "::" + (PosSql.truthy(variantId) ? String.valueOf(variantId) : "");
    }

    /** Process the not-yet-done stock-posting jobs of one shift's invoices before its close. */
    private void drainStockPostings(String cashSessionId) {
        // T-1 (ADR-S072): an order-timed job without its invoice yet belongs to the shift through its order.
        List<String> jobs = jdbc.sql("SELECT j.id FROM \"StockPostingJob\" j LEFT JOIN \"Invoice\" i ON i.id = j.\"invoiceId\""
                + " LEFT JOIN \"Order\" o ON o.id = j.\"orderId\" AND j.\"invoiceId\" IS NULL WHERE j.\"organizationId\" = ?"
                + " AND (i.\"cashSessionId\" = ? OR o.\"cashSessionId\" = ?) AND j.status::text <> 'done'")
                .params(TenantContext.organizationId(), cashSessionId, cashSessionId).query(String.class).list();
        for (String job : jobs) {
            try {
                billing.processStockPostingJob(job);
            } catch (RuntimeException e) {
                log.warn("stock posting {} before close failed: {}", job, e.toString());
            }
        }
    }

    /**
     * R1 (P1-3): process the not-yet-done stock-posting jobs of one sale (its invoice's and its order's) before a
     * restocking refund. Returns true when one is still not done afterwards (failed, held or claimed elsewhere).
     */
    private boolean drainSaleStockPostings(String invoiceId) {
        for (String job : billing.pendingStockPostingJobs(invoiceId)) {
            try {
                billing.processStockPostingJob(job);
            } catch (RuntimeException e) {
                log.warn("stock posting {} before refund failed: {}", job, e.toString());
            }
        }
        return !billing.pendingStockPostingJobs(invoiceId).isEmpty();
    }

    /** Which device captured the sale + the number it printed offline (metadata; never fails the sale). */
    private void stampSale(Map<String, Object> res, String deviceId, String provisionalNumber) {
        stamp("Invoice", (String) res.get("invoiceId"), deviceId, provisionalNumber);
        stamp("Order", (String) res.get("orderId"), deviceId, provisionalNumber);
        stamp("Receipt", (String) res.get("receiptId"), deviceId, provisionalNumber);
    }

    private void stamp(String table, String id, String deviceId, String provisionalNumber) {
        if (id == null || id.isEmpty()) {
            return;
        }
        try {
            String set = "\"deviceId\" = ?" + (PosSql.truthy(provisionalNumber) ? ", \"provisionalNumber\" = ?" : "");
            List<Object> params = new ArrayList<>(List.of(deviceId));
            if (PosSql.truthy(provisionalNumber)) {
                params.add(provisionalNumber);
            }
            params.add(id);
            jdbc.sql("UPDATE \"" + table + "\" SET " + set + ", \"updatedAt\" = " + PosSql.NOW + " WHERE id = ?")
                    .params(params).update();
        } catch (RuntimeException e) {
            log.warn("stamp {} failed: {}", table, e.toString());
        }
    }

    /** Credential fields never stored in a dead letter (it is listed by {@code GET /sync/dead-letters}). */
    private static final java.util.Set<String> SECRET_KEYS = java.util.Set.of("managerPin", "incomingPin", "overridePin",
            "pin", "password", "approvalToken", "approverPin");

    /**
     * The payload with PINs, passwords and approval tokens replaced by {@code "[redacted]"}, at any depth.
     * Post-parity hardening (owner-approved 2026-11-10): Nest stored the raw payload, manager PIN included.
     */
    @SuppressWarnings("unchecked")
    static Object withoutSecrets(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), SECRET_KEYS.contains(String.valueOf(k)) && v != null
                    ? "[redacted]" : withoutSecrets(v)));
            return out;
        }
        if (value instanceof java.util.List<?> l) {
            return l.stream().map(SyncPushService::withoutSecrets).toList();
        }
        return value;
    }

    private void deadLetter(Device device, Op op, String error, int httpStatus) {
        deadLetter(device, op, error, httpStatus, failureCode(op, httpStatus, null), retryable(httpStatus, null));
    }

    private void deadLetter(Device device, Op op, String error, int httpStatus, String code, boolean retryable) {
        try {
            jdbc.sql("INSERT INTO \"SyncOpDeadLetter\" (id, \"organizationId\", \"deviceId\", \"opId\", \"deviceSeq\","
                    + " \"opType\", \"actorUserId\", \"occurredAt\", payload, error, \"httpStatus\", \"failureCode\","
                    + " retryable, \"branchId\", attempts, \"lastAttemptAt\", \"createdAt\", \"receivedAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 1, " + PosSql.NOW + ", " + PosSql.NOW + ", "
                    + PosSql.NOW + ")"
                    + " ON CONFLICT (\"organizationId\", \"opId\") DO UPDATE SET error = EXCLUDED.error,"
                    + " \"httpStatus\" = EXCLUDED.\"httpStatus\", \"failureCode\" = EXCLUDED.\"failureCode\","
                    + " retryable = EXCLUDED.retryable, attempts = \"SyncOpDeadLetter\".attempts + 1,"
                    + " \"lastAttemptAt\" = EXCLUDED.\"lastAttemptAt\"")
                    .params(java.util.UUID.randomUUID().toString(), device.organizationId(), device.id(), op.opId(),
                            op.deviceSeq(), op.type(), op.actorUserId(),
                            op.occurredAt() == null ? null : SqlRows.ts(jsDate(op.occurredAt())),
                            com.inventoryerp.shared.web.EntityJson.toJson(withoutSecrets(op.payload())), error, httpStatus,
                            code, retryable, device.branchId())
                    .update();
        } catch (RuntimeException e) {
            log.error("FAILED TO DEAD-LETTER sync op {}: {}", op.opId(), e.toString());
        }
    }
}
