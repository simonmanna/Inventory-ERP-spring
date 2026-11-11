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
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosInvoiceService;
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
            Map<String, Object> payload) {}

    /** The authenticated device ({@code RequestDevice}). */
    public record Device(String id, String organizationId, String branchId) {}

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
    private final InventoryCountService counts;
    private final ExpensesService expenses;

    public SyncPushService(PosDb db, JsonMapper mapper, IdempotencyService idempotency, PosService pos,
            PosSettingsService posSettings, PosInvoiceService billing, PosReservationsService reservations,
            CashSessionService cashSessions, CashRegisterService cashRegisters, ProductService products,
            DirectStockService directStock, InventoryCountService counts, ExpensesService expenses) {
        this.db = db;
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
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("status", "failed");
        r.put("httpStatus", status);
        r.put("error", error);
        return r;
    }

    private Map<String, Object> applyOp(Device device, Op op, Map<String, String> clientIdMap,
            Set<String> failedClientIds, Map<String, List<String>> actorOk) {
        Map<String, Object> payloadIn = op.payload() == null ? Map.of() : op.payload();
        Object clientId = payloadIn.get("clientId");
        List<String> permissions = actorMayAct(device, op.actorUserId(), actorOk);
        if (permissions == null) {
            String why = "Unknown or inactive actorUserId " + op.actorUserId() + " for this device's organization";
            deadLetter(device, op, why, 403);
            if (PosSql.truthy(clientId)) {
                failedClientIds.add(String.valueOf(clientId));
            }
            return failed(op, 403, why);
        }
        String brokenDep = referencedClientIds(payloadIn).stream().filter(failedClientIds::contains).findFirst()
                .orElse(null);
        if (brokenDep != null) {
            String why = "Depends on failed op (clientId " + brokenDep + ")";
            deadLetter(device, op, why, 424);
            if (PosSql.truthy(clientId)) {
                failedClientIds.add(String.valueOf(clientId));
            }
            return failed(op, 424, why);
        }
        try {
            TenantStore store = TenantStore.user(device.organizationId(), op.actorUserId());
            store.setPermissions(permissions);
            Map<String, Object> hashed = new LinkedHashMap<>();
            hashed.put("type", op.type());
            hashed.put("payload", op.payload());
            IdempotencyService.Result outcome = TenantContext.run(store, () -> idempotency.executeWithKey(op.opId(),
                    idempotency.hashPayload(hashed), "SYNC", op.type(), mapper.valueToTree(payloadIn),
                    () -> new IdempotencyService.Result(false, 201, runOp(device, op, clientIdMap))));
            Map<String, Object> body = asMap(outcome.body());
            if (PosSql.truthy(clientId) && body != null && PosSql.truthy(body.get("id"))) {
                clientIdMap.put(String.valueOf(clientId), String.valueOf(body.get("id")));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("opId", op.opId());
            r.put("status", outcome.replayed() ? "replayed" : "applied");
            r.put("httpStatus", outcome.statusCode());
            r.put("error", null);
            if (body != null && body.get("mapping") != null) {
                r.put("mapping", body.get("mapping"));
            }
            if (body != null && body.get("finalNumbers") != null) {
                r.put("finalNumbers", body.get("finalNumbers"));
            }
            return r;
        } catch (RuntimeException err) {
            int status = err instanceof ApiException a ? a.status().value() : 500;
            String message = errorMessage(err);
            log.warn("sync op {} ({}) failed: {}", op.opId(), op.type(), message);
            deadLetter(device, op, message, status);
            if (PosSql.truthy(clientId)) {
                failedClientIds.add(String.valueOf(clientId));
            }
            return failed(op, status, message);
        }
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

    /** Client ids this op depends on (references created by earlier ops). */
    static List<String> referencedClientIds(Map<String, Object> payload) {
        List<String> refs = new ArrayList<>();
        Object session = payload.get("cashSessionId") != null ? payload.get("cashSessionId") : payload.get("sessionId");
        for (Object ref : new Object[] {session, payload.get("partnerId"), payload.get("invoiceId"),
            payload.get("reservationId")}) {
            if (ref instanceof String s && !s.isEmpty()) {
                refs.add(s);
            }
        }
        return refs;
    }

    /** Swap client-minted ids for server ids created earlier in the batch. */
    static Map<String, Object> resolveRefs(Map<String, Object> payload, Map<String, String> clientIdMap) {
        Map<String, Object> out = new LinkedHashMap<>(payload);
        for (String key : List.of("cashSessionId", "sessionId", "invoiceId", "reservationId")) {
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

    private Map<String, Object> runOp(Device device, Op op, Map<String, String> clientIdMap) {
        OccurredAt.resolve(op.occurredAt());
        Map<String, Object> payload = resolveRefs(op.payload() == null ? Map.of() : op.payload(), clientIdMap);
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
                    drainStockPostings(String.valueOf(payload.get("sessionId")));
                }
                Map<String, Object> closed = cashSessions.close(new CashSessionService.CloseInput(
                        obj(payload.get("closingAccounts")), obj(payload.get("uncountedAccounts")), null,
                        decimal(payload.get("closingCounted")), str(payload.get("notes")), str(payload.get("varianceReason")),
                        str(payload.get("varianceStatus")), null, str(payload.get("approvedById")),
                        str(payload.get("approverEmail")), str(payload.get("managerPin")),
                        obj(payload.get("closingDenomination")), occurredAt, str(payload.get("sessionId"))), false);
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
            case "sale.checkout" -> {
                Map<String, Object> checkout = new LinkedHashMap<>(payload);
                checkout.remove("clientId");
                Object provisional = checkout.remove("provisionalNumber");
                checkout.put("occurredAt", occurredAt);
                Map<String, Object> res = pos.checkout(checkout);
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
                        lines.add(PosSql.map(o));
                    }
                }
                Map<String, Object> res = billing.refund(String.valueOf(payload.get("invoiceId")), reason,
                        str(payload.get("overrideById")), str(payload.get("overridePin")),
                        offlineStockDisposition(payload.get("stockDisposition")), str(payload.get("cashSessionId")), lines);
                Object invId = res != null && res.get("invoiceId") != null ? res.get("invoiceId")
                        : String.valueOf(payload.get("invoiceId"));
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
                    items.add(new DirectStockService.Line(str(it.get("productId")), str(it.get("variantId")),
                            decimal(it.get("quantity")), decimal(it.get("unitCost")), str(it.get("batchNumber")),
                            str(it.get("expiryDate")), str(it.get("distStrategy")), str(it.get("notes"))));
                }
                DirectStockService.Request req = new DirectStockService.Request(str(payload.get("locationId")),
                        payload.get("responsibleById") != null ? str(payload.get("responsibleById")) : op.actorUserId(),
                        str(payload.get("approvedById")), str(payload.get("approverPin")), items, str(payload.get("notes")));
                Map<String, Object> doc = "stock.in".equals(op.type()) ? directStock.directIn(req) : directStock.directOut(req);
                Object docId = doc == null ? null : doc.get("id");
                m.put("id", docId);
                m.put("mapping", mapOf("stockDocId", docId == null ? "" : String.valueOf(docId)));
            }
            case "stock.count" -> m.putAll(applyStockCount(payload));
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
            try {
                db.insert(Partner.class, mapOf("id", id, "organizationId", device.organizationId(),
                        "code", "POS-" + idHex.substring(0, Math.min(len, idHex.length())), "name", payload.get("name"),
                        "phone", payload.get("phone"), "email", payload.get("email"), "notes", payload.get("note"),
                        "isCustomer", true, "isCompany", false, "customFields", PosDb.json(loyalty)));
                return id;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (scopedFind(Partner.class, id) != null) {
                    return id;
                }
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
        try {
            Map<String, Object> created = SqlRows.one(jdbc.sql("INSERT INTO \"Message\" (id, \"organizationId\","
                    + " \"conversationId\", \"senderType\", \"senderUserId\", \"contentType\", body, \"occurredAt\")"
                    + " VALUES (?, ?, ?, 'user', ?, 'text', ?, ?) RETURNING id, seq").params(messageId,
                    TenantContext.organizationId(), conversationId, op.actorUserId(), body,
                    SqlRows.ts(jsDate(occurredAt))));
            scopedUpdate(Conversation.class, conversationId, new LinkedHashMap<>(), null);
            return mapOf("id", created.get("id"), "mapping", mapOf("messageId", created.get("id")),
                    "syncSequence", String.valueOf(created.get("seq")));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            String existing = jdbc.sql("SELECT id FROM \"Message\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(messageId, TenantContext.organizationId()).query(String.class)
                    .optional().orElse(null);
            if (existing != null) {
                return mapOf("id", existing, "mapping", mapOf("messageId", existing));
            }
            throw e;
        }
    }

    /** Open (or resume) a spot count over the counted products, enter the device's figures and submit. */
    private Map<String, Object> applyStockCount(Map<String, Object> payload) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (payload.get("lines") instanceof List<?> l) {
            l.forEach(o -> lines.add(PosSql.map(o)));
        }
        if (!PosSql.truthy(payload.get("locationId")) || lines.isEmpty()) {
            throw http("stock.count requires locationId and lines", 400);
        }
        LinkedHashSet<String> productIds = new LinkedHashSet<>();
        lines.forEach(l -> productIds.add(String.valueOf(l.get("productId"))));
        Map<String, Object> session = counts.start(str(payload.get("locationId")), "spot", str(payload.get("notes")), null,
                null, new ArrayList<>(productIds), null);
        Map<String, Map<String, Object>> lineByProduct = new HashMap<>();
        for (Object o : PosSql.list(session.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            // A device counts a product's total: a resumed screen count split per lot (lotLine) cannot take it.
            if (l.get("variantId") == null && !Boolean.TRUE.equals(l.get("lotLine"))) {
                lineByProduct.put(String.valueOf(l.get("productId")), l);
            }
        }
        long missing = productIds.stream().filter(id -> !lineByProduct.containsKey(id)).count();
        if (missing > 0) {
            throw http("An open spot count on this location does not cover " + missing + " counted product(s) — finish or"
                    + " cancel it in the back office, then retry", 409);
        }
        List<InventoryCountService.DraftLine> draft = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            String reason = l.get("reason") instanceof String r && !r.trim().isEmpty() ? r.trim()
                    : payload.get("reason") instanceof String pr && !pr.trim().isEmpty() ? pr.trim() : "Device count";
            draft.add(new InventoryCountService.DraftLine(String.valueOf(lineByProduct.get(String.valueOf(l.get("productId")))
                    .get("id")), PosSql.dec(l.get("countedQty")), reason));
        }
        String sessionId = String.valueOf(session.get("id"));
        counts.saveDraft(sessionId, draft, null, null);
        counts.submit(sessionId, null, null);
        return mapOf("id", session.get("id"), "mapping", mapOf("countId", sessionId,
                "countCode", session.get("countCode") == null ? "" : String.valueOf(session.get("countCode"))));
    }

    /** Process the not-yet-done stock-posting jobs of one shift's invoices before its close. */
    private void drainStockPostings(String cashSessionId) {
        List<String> jobs = jdbc.sql("SELECT j.id FROM \"StockPostingJob\" j JOIN \"Invoice\" i ON i.id = j.\"invoiceId\""
                + " WHERE i.\"cashSessionId\" = ? AND i.\"organizationId\" = ? AND j.status::text <> 'done'")
                .params(cashSessionId, TenantContext.organizationId()).query(String.class).list();
        for (String job : jobs) {
            try {
                billing.processStockPostingJob(job);
            } catch (RuntimeException e) {
                log.warn("stock posting {} before close failed: {}", job, e.toString());
            }
        }
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
            "pin", "password", "approvalToken");

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
        try {
            jdbc.sql("INSERT INTO \"SyncOpDeadLetter\" (id, \"organizationId\", \"deviceId\", \"opId\", \"deviceSeq\","
                    + " \"opType\", \"actorUserId\", \"occurredAt\", payload, error, \"httpStatus\", \"createdAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, " + PosSql.NOW + ") ON CONFLICT (\"organizationId\","
                    + " \"opId\") DO UPDATE SET error = EXCLUDED.error, \"httpStatus\" = EXCLUDED.\"httpStatus\"")
                    .params(java.util.UUID.randomUUID().toString(), device.organizationId(), device.id(), op.opId(),
                            op.deviceSeq(), op.type(), op.actorUserId(),
                            op.occurredAt() == null ? null : SqlRows.ts(jsDate(op.occurredAt())),
                            com.inventoryerp.shared.web.EntityJson.toJson(withoutSecrets(op.payload())), error, httpStatus)
                    .update();
        } catch (RuntimeException e) {
            log.error("FAILED TO DEAD-LETTER sync op {}: {}", op.opId(), e.toString());
        }
    }
}
