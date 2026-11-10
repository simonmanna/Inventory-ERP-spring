package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.workflow.MilestoneService;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.pos.application.PosLines.Mod;
import com.inventoryerp.pos.application.PosLines.Resolved;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operational Order aggregate (port of {@code order/pos-orders.service.ts}): draft/open lifecycle, item editing
 * with the F10 signature diff, per-item kitchen flow, table/waiter assignment. No GL / stock / cash effect.
 * Inputs are JS-shaped maps ({@code OrderLineDto} etc.) produced by the controllers.
 */
@Service
public class PosOrdersService {

    private static final Logger log = LoggerFactory.getLogger(PosOrdersService.class);

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final SequenceService sequence;
    private final DocumentBuilderService builder;
    private final PosVariantService variants;
    private final PosAccompanimentService accompaniments;
    private final PosModifiersService modifiers;
    private final PosKdsService kds;
    private final PosOverridesService overrides;
    private final PosReceiptsService receipts;
    private final WorkflowService workflows;
    private final MilestoneService milestones;
    private final PricingPolicy pricing;
    private final OrderMutationPolicy mutationPolicy;
    private final KitchenWaste kitchenWaste;
    private final TransactionTemplate tx;
    private final Map<String, String> actorNameCache = new ConcurrentHashMap<>();

    public PosOrdersService(PosDb db, AuditService audit, EventBus events, SequenceService sequence,
            DocumentBuilderService builder, PosVariantService variants, PosAccompanimentService accompaniments,
            PosModifiersService modifiers, PosKdsService kds, PosOverridesService overrides, PosReceiptsService receipts,
            WorkflowService workflows, MilestoneService milestones, PricingPolicy pricing,
            OrderMutationPolicy mutationPolicy, KitchenWaste kitchenWaste, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.events = events;
        this.sequence = sequence;
        this.builder = builder;
        this.variants = variants;
        this.accompaniments = accompaniments;
        this.modifiers = modifiers;
        this.kds = kds;
        this.overrides = overrides;
        this.receipts = receipts;
        this.workflows = workflows;
        this.milestones = milestones;
        this.pricing = pricing;
        this.mutationPolicy = mutationPolicy;
        this.kitchenWaste = kitchenWaste;
        this.tx = new TransactionTemplate(txm);
    }

    // ─── quote ───

    /** Price a cart without saving it; {@code authority} says what the discount rules make of it. */
    public Map<String, Object> quote(Map<String, Object> input) {
        List<Map<String, Object>> lines = maps(input.get("lines"));
        validateLines(lines, false);
        List<Resolved> resolved = resolveLines(lines);
        Totals totals = prepare(PricingPolicy.discountedLines(preparedInputs(resolved), input));
        PricingPolicy.Verdict authority = pricing.evaluate(pricingLines(resolved), input);
        List<Map<String, Object>> base = new ArrayList<>();
        resolved.forEach(r -> base.add(r.toMap()));
        return Obj.of("baseLines", base, "pricingVersion", 1, "subtotal", PosSql.js(totals.subtotal()),
                "discountTotal", PosSql.js(totals.discountTotal()), "taxAmount", PosSql.js(totals.taxAmount()),
                "total", PosSql.js(totals.total()), "lines", totals.prepared(), "authority", authority.toMap());
    }

    /** Resolved lines + their totals, for the checkout / tab paths (quote without serialization). */
    public record Quote(List<Resolved> baseLines, double total) {}

    public Quote quoteRaw(Map<String, Object> input) {
        List<Map<String, Object>> lines = maps(input.get("lines"));
        validateLines(lines, false);
        List<Resolved> resolved = resolveLines(lines);
        Totals totals = prepare(PricingPolicy.discountedLines(preparedInputs(resolved), input));
        return new Quote(resolved, totals.total().doubleValue());
    }

    static List<Map<String, Object>> pricingLines(List<Resolved> resolved) {
        List<Map<String, Object>> out = new ArrayList<>();
        resolved.forEach(r -> out.add(r.pricing()));
        return out;
    }

    private static List<Map<String, Object>> preparedInputs(List<Resolved> resolved) {
        List<Map<String, Object>> out = new ArrayList<>();
        resolved.forEach(r -> out.add(r.preparedInput()));
        return out;
    }

    private Totals prepare(List<Map<String, Object>> inputs) {
        return builder.prepareLines(PosLines.lineInputs(inputs));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : PosSql.list(v)) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    // ─── queries ───

    private List<Map<String, Object>> itemsWithModifiers(String orderId) {
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", orderId);
        PosModifiers.attach(jdbc, items, OrderItemModifier.class, "OrderItemModifier", "orderItemId");
        return items;
    }

    /** Order with live items (+ modifiers) and its invoice. */
    public Map<String, Object> getOrder(String orderId) {
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        order.put("items", itemsWithModifiers(orderId));
        order.put("invoice", order.get("invoiceId") == null ? null
                : db.first(Invoice.class, "id = ?", order.get("invoiceId")));
        return order;
    }

    public List<Map<String, Object>> getMilestones(String orderId) {
        getOrder(orderId);
        return milestones.forEntity("order", orderId);
    }

    /** The current open (un-billed) order on a table, or {@code null}. */
    public Map<String, Object> getOpenOrderForTable(String tableId) {
        Map<String, Object> order = db.first(Order.class, "\"organizationId\" = ? AND \"tableId\" = ? AND status::text IN "
                + TableStatus.HELD_SQL + " AND \"invoiceId\" IS NULL ORDER BY \"openedAt\" DESC", org(), tableId);
        if (order != null) {
            order.put("items", itemsWithModifiers((String) order.get("id")));
        }
        return order;
    }

    public List<Map<String, Object>> list(String status, String tableId, String cashSessionId) {
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isEmpty()) {
            where.append(" AND status = ?::\"OrderStatus\"");
            params.add(OrderStatusUtil.toCanonical(status));
        }
        if (tableId != null && !tableId.isEmpty()) {
            where.append(" AND \"tableId\" = ?");
            params.add(tableId);
        }
        if (cashSessionId != null && !cashSessionId.isEmpty()) {
            where.append(" AND \"cashSessionId\" = ?");
            params.add(cashSessionId);
        }
        List<Map<String, Object>> rows = SqlRows.list(Order.class, jdbc.sql("SELECT * FROM \"Order\" WHERE " + where
                + " ORDER BY \"openedAt\" DESC LIMIT 200").params(params));
        for (Map<String, Object> o : rows) {
            o.put("items", db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false ORDER BY \"lineNumber\" ASC",
                    o.get("id")));
        }
        return rows;
    }

    /** Live open-orders feed ({@code { count, rows }}). */
    public Map<String, Object> listOpenOrders(String orderType, String cashSessionId, String branchId, String search) {
        String orgId = org();
        StringBuilder where = new StringBuilder("o.\"organizationId\" = ? AND " + TableStatus.HELD_ORDER_WHERE);
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (orderType != null && !orderType.isEmpty()) {
            where.append(" AND o.\"orderType\" = ?::\"OrderType\"");
            params.add(orderType);
        }
        if (cashSessionId != null && !cashSessionId.isEmpty()) {
            where.append(" AND o.\"cashSessionId\" = ?");
            params.add(cashSessionId);
        }
        if (branchId != null && !branchId.isEmpty()) {
            where.append(" AND o.\"branchId\" = ?");
            params.add(branchId);
        }
        if (search != null && !search.isEmpty()) {
            where.append(" AND o.\"orderNumber\" ILIKE ?");
            params.add(PosSql.contains(search));
        }
        List<Map<String, Object>> orders = SqlRows.list(Order.class, jdbc.sql("SELECT o.* FROM \"Order\" o WHERE " + where
                + " ORDER BY o.\"openedAt\" DESC LIMIT 200").params(params));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> tableNames = new HashMap<>();
        Map<String, String> waiterNames = new HashMap<>();
        Map<String, String> partnerNames = new HashMap<>();
        for (Map<String, Object> o : orders) {
            String tid = (String) o.get("tableId");
            String wid = (String) o.get("waiterId");
            String pid = (String) o.get("partnerId");
            if (tid != null && !tableNames.containsKey(tid)) {
                tableNames.put(tid, jdbc.sql("SELECT name FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(tid, orgId).query(String.class).optional().orElse(null));
            }
            if (wid != null && !waiterNames.containsKey(wid)) {
                Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"User\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(wid, orgId));
                waiterNames.put(wid, u == null ? null : u.get("firstName")
                        + (PosSql.truthy(u.get("lastName")) ? " " + u.get("lastName") : ""));
            }
            if (pid != null && !partnerNames.containsKey(pid)) {
                partnerNames.put(pid, jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(pid, orgId).query(String.class).optional().orElse(null));
            }
            Map<String, Object> r = Obj.of("id", o.get("id"), "orderNumber", o.get("orderNumber"),
                    "orderType", o.get("orderType"), "status", o.get("status"),
                    "legacyStatus", OrderStatusUtil.toLegacy((String) o.get("status")),
                    "openedAt", o.get("openedAt") != null ? o.get("openedAt") : o.get("createdAt"), "tableId", tid,
                    "tableName", tid == null ? null : tableNames.get(tid), "waiterId", wid,
                    "waiterName", wid == null ? null : waiterNames.get(wid), "partnerId", pid,
                    "customerName", pid == null ? null : partnerNames.get(pid), "guestCount", o.get("guestCount"),
                    "totalAmount", PosSql.js(o.get("totalAmount")));
            rows.add(r);
        }
        return Obj.of("count", rows.size(), "rows", rows);
    }

    // ─── lifecycle ───

    private String opKey() {
        BusinessOutcome.Operation op = BusinessOutcome.current();
        return op == null ? null : op.key();
    }

    /** Create a new order (number assigned immediately; items optional). */
    public Map<String, Object> createOrder(Map<String, Object> dto) {
        String orgId = org();
        String partnerId = PosSql.truthy(dto.get("partnerId")) ? (String) dto.get("partnerId") : ensureWalkInCustomer(orgId);
        boolean bypassRequired = PosSql.truthy(dto.get("overrideById")) && assertOverride((String) dto.get("overrideById"));
        List<Map<String, Object>> lines = maps(dto.get("lines"));
        if (!lines.isEmpty()) {
            validateLines(lines, bypassRequired);
        }
        List<Resolved> resolved = lines.isEmpty() ? List.of() : resolveLines(lines);
        String orderType = dto.get("orderType") == null ? "dine_in" : dto.get("orderType").toString();
        String tableId = (String) dto.get("tableId");
        return tx.execute(st -> {
            if ("dine_in".equals(orderType) && PosSql.truthy(tableId)) {
                jdbc.sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(tableId, orgId).query().listOfRows();
                Map<String, Object> held = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\" FROM \"Order\" WHERE"
                        + " \"organizationId\" = ? AND \"tableId\" = ? AND \"orderType\" = 'dine_in' AND \"invoiceId\" IS NULL"
                        + " AND status::text IN " + TableStatus.HELD_SQL + " LIMIT 1").params(orgId, tableId));
                if (held != null) {
                    throw ApiException.conflict("Table already has an open order (" + held.get("orderNumber") + ")");
                }
            }
            Object cashSessionId = dto.get("cashSessionId");
            if (PosSql.truthy(cashSessionId)) {
                jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(cashSessionId, orgId).query().listOfRows();
                String status = jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(cashSessionId, orgId).query(String.class).optional().orElse(null);
                if (!"open".equals(status)) {
                    throw ApiException.badRequest("Select an open register session before creating an order");
                }
            }
            String orderNumber = nextOrderNumber();
            Map<String, Object> order = db.insert(Order.class, Obj.of("organizationId", orgId, "orderNumber", orderNumber,
                    "clientOperationKey", opKey(), "orderType", orderType, "status", "confirmed", "tableId", tableId,
                    "partnerId", partnerId, "waiterId", dto.get("waiterId") != null ? dto.get("waiterId") : user(),
                    "branchId", dto.get("branchId"), "cashSessionId", cashSessionId, "guestCount", intOrNull(dto.get("guestCount")),
                    "notes", dto.get("notes"), "createdBy", user()));
            String id = (String) order.get("id");
            if (!resolved.isEmpty()) {
                writeItems(id, resolved, false, false, dto);
            }
            TableStatus.recompute(jdbc, orgId, tableId);
            Map<String, Object> fresh = reload(id);
            BusinessOutcome.record(jdbc, Obj.of("orderId", id, "orderNumber", orderNumber), false);
            events.publish(DomainEvents.POS_ORDER_CREATED, Obj.of("organizationId", orgId, "orderId", id,
                    "orderNumber", orderNumber, "tableId", tableId));
            audit.recordInTx(AuditInput.of("Order", id, "create", null, Obj.of("orderNumber", orderNumber,
                    "tableId", tableId, "lineCount", resolved.size())));
            return fresh;
        });
    }

    private static Integer intOrNull(Object v) {
        return v == null ? null : (int) num(v);
    }

    /** Create an order from already-priced lines (split bills); joins the caller's transaction when there is one. */
    public Map<String, Object> createOrderFromResolved(String orderType, String tableId, String sourceDocumentType,
            String partnerIdIn, String cashSessionId, String branchId, Integer guestCount, List<Resolved> resolved) {
        String orgId = org();
        String partnerId = partnerIdIn != null ? partnerIdIn : ensureWalkInCustomer(orgId);
        return tx.execute(st -> {
            String orderNumber = nextOrderNumber();
            Map<String, Object> order = db.insert(Order.class, Obj.of("organizationId", orgId, "orderNumber", orderNumber,
                    "orderType", orderType == null ? "dine_in" : orderType, "status", "confirmed", "tableId", tableId,
                    "sourceDocumentType", sourceDocumentType, "partnerId", partnerId, "waiterId", user(),
                    "branchId", branchId, "cashSessionId", cashSessionId, "guestCount", guestCount, "createdBy", user()));
            String id = (String) order.get("id");
            if (!resolved.isEmpty()) {
                writeItems(id, resolved, false, false, Map.of());
            }
            TableStatus.recompute(jdbc, orgId, tableId);
            Map<String, Object> fresh = reload(id);
            events.publish(DomainEvents.POS_ORDER_CREATED, Obj.of("organizationId", orgId, "orderId", id,
                    "orderNumber", orderNumber, "tableId", tableId));
            return fresh;
        });
    }

    /** Auto-save: replace the order's item set with exactly these lines. */
    public Map<String, Object> saveItems(String orderId, Map<String, Object> dto) {
        String orgId = org();
        boolean bypassRequired = PosSql.truthy(dto.get("overrideById")) && assertOverride((String) dto.get("overrideById"));
        List<Map<String, Object>> lines = maps(dto.get("lines"));
        if (!lines.isEmpty()) {
            validateLines(lines, bypassRequired);
        }
        List<Resolved> resolved = lines.isEmpty() ? List.of() : resolveLines(lines);
        Map<String, Object> saved = tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            assertEditable(order);
            assertVersion(order, dto.get("expectedVersion"));
            writeItems(orderId, resolved, true, false, dto);
            Object guestCount = dto.get("guestCount");
            Object partnerId = dto.get("partnerId");
            if (guestCount != null || PosSql.truthy(partnerId)) {
                Map<String, Object> data = new LinkedHashMap<>();
                if (guestCount != null) {
                    data.put("guestCount", intOrNull(guestCount));
                }
                if (PosSql.truthy(partnerId)) {
                    data.put("partnerId", partnerId);
                }
                db.update(Order.class, data, "id = ? AND \"organizationId\" = ?", orderId, orgId);
            }
            TableStatus.recompute(jdbc, orgId, (String) order.get("tableId"));
            Map<String, Object> fresh = reload(orderId);
            events.publish(DomainEvents.POS_ORDER_UPDATED, Obj.of("organizationId", orgId, "orderId", orderId,
                    "version", fresh.get("version")));
            return fresh;
        });
        autoSendRoutedLines(orderId);
        return saved;
    }

    /** Best-effort auto-send of lines pinned to a prep station. */
    public Map<String, Object> autoSendRoutedLines(String orderId) {
        try {
            return fireKitchen(orderId, null, true);
        } catch (RuntimeException e) {
            log.warn("auto-send to KDS failed for order {}: {}", orderId, e.getMessage());
            return null;
        }
    }

    /** Append a round of items. */
    public Map<String, Object> addItems(String orderId, Map<String, Object> dto) {
        String orgId = org();
        boolean bypassRequired = PosSql.truthy(dto.get("overrideById")) && assertOverride((String) dto.get("overrideById"));
        List<Map<String, Object>> lines = maps(dto.get("lines"));
        validateLines(lines, bypassRequired);
        List<Resolved> resolved = resolveLines(lines);
        Map<String, Object> result = tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            assertEditable(order);
            Map<String, Object> opts = new LinkedHashMap<>();
            if (dto.containsKey("transactionDiscountPercent")) {
                opts.put("transactionDiscountPercent", dto.get("transactionDiscountPercent"));
            }
            writeItems(orderId, resolved, false, true, opts);
            if (dto.get("guestCount") != null) {
                db.update(Order.class, Obj.of("guestCount", intOrNull(dto.get("guestCount"))),
                        "id = ? AND \"organizationId\" = ?", orderId, orgId);
            }
            TableStatus.recompute(jdbc, orgId, (String) order.get("tableId"));
            return reload(orderId);
        });
        events.publish(DomainEvents.POS_ORDER_UPDATED, Obj.of("organizationId", orgId, "orderId", orderId,
                "version", result.get("version")));
        if (Boolean.TRUE.equals(dto.get("sendToKitchen"))) {
            try {
                fireKitchen(orderId, null, false);
            } catch (RuntimeException e) {
                log.warn("addItems fire-kitchen failed: {}", e.getMessage());
            }
        } else {
            autoSendRoutedLines(orderId);
        }
        return result;
    }

    /** Cancel the whole order (un-billed only; fired food needs void authority). */
    public Map<String, Object> cancelOrder(String orderId, String reason, Object expectedVersion, String overrideById,
            String overridePin) {
        String orgId = org();
        List<OrderMutationPolicy.FiredLine>[] wasted = new List[] {List.of()};
        Map<String, Object> cancelled = tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            if (order.get("invoiceId") != null) {
                throw ApiException.conflict("Order already billed — refund/void the invoice instead");
            }
            if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
                return order;
            }
            if (expectedVersion != null) {
                assertVersion(order, expectedVersion);
            }
            List<OrderMutationPolicy.FiredLine> firedLost = mutationPolicy.assertOrderCancellationAllowed(jdbc, orderId,
                    reason, overrideById, overridePin);
            workflows.transition("order", orderId, "cancel", Obj.of("reason", reason), order);
            Map<String, Object> updated = db.update(Order.class, Obj.of("cancelReason", reason,
                    "version", PosDb.raw("version + 1")), "id = ? AND \"organizationId\" = ?", orderId, orgId);
            jdbc.sql("UPDATE \"PosTableOrder\" SET \"closedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                    + " WHERE \"orderId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?").params(orderId, orgId).update();
            TableStatus.recompute(jdbc, orgId, (String) order.get("tableId"));
            events.publish(DomainEvents.POS_ORDER_CANCELLED, Obj.of("organizationId", orgId, "orderId", orderId,
                    "reason", reason));
            List<Map<String, Object>> fired = new ArrayList<>();
            for (OrderMutationPolicy.FiredLine f : firedLost) {
                fired.add(Obj.of("description", f.description(), "quantity", PosSql.js(f.quantity()),
                        "firedQuantity", PosSql.js(f.kitchenPrintedQty())));
            }
            audit.recordInTx(AuditInput.of("Order", orderId, "cancel", null, Obj.of("reason", reason,
                    "approvedById", overrideById, "firedItems", fired)));
            wasted[0] = firedLost;
            return updated;
        });
        if (!wasted[0].isEmpty()) {
            List<KitchenWaste.Line> lines = new ArrayList<>();
            for (OrderMutationPolicy.FiredLine f : wasted[0]) {
                lines.add(new KitchenWaste.Line(f.id(), Math.min(f.quantity(), f.kitchenPrintedQty())));
            }
            kitchenWaste.record(orgId, orderId, lines, reason == null ? "Order cancelled" : reason, overrideById);
        }
        return cancelled;
    }

    /** Void one line (whole or partial). The only way a fired item may leave an open order. */
    public Map<String, Object> voidItem(String orderId, String itemId, Map<String, Object> dto) {
        String orgId = org();
        String reason = dto.get("reason") == null ? null : dto.get("reason").toString().trim();
        if (reason == null || reason.isEmpty()) {
            throw ApiException.badRequest("A reason is required to void an item");
        }
        String overrideById = (String) dto.get("overrideById");
        Object[] outcome = tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            assertEditable(order);
            Map<String, Object> row = db.first(OrderItem.class, "id = ? AND \"orderId\" = ? AND \"organizationId\" = ?",
                    itemId, orderId, orgId);
            if (row == null) {
                throw ApiException.notFound("Order item not found");
            }
            if (Boolean.TRUE.equals(row.get("cancelled"))) {
                throw ApiException.conflict("That item has already been voided");
            }
            double current = num(row.get("quantity"));
            double asked = dto.get("quantity") == null ? current : num(dto.get("quantity"));
            if (current > 0 && (!Double.isFinite(asked) || asked <= 0 || asked > current + 0.000001)) {
                throw ApiException.badRequest("Void quantity must be between 0 and " + JsFormat.str(current));
            }
            boolean whole = current <= 0 || asked >= current - 0.000001;
            double firedQty = num(row.get("kitchenPrintedQty"));
            if (firedQty > 0) {
                if (!PosSql.truthy(overrideById)) {
                    throw ApiException.forbidden(
                            "This item was already sent to the kitchen — a manager approval and PIN are required to void it");
                }
                overrides.verifyOperationApproval(overrideById, (String) dto.get("overridePin"), "void");
            }
            double remaining = current - asked;
            if (whole) {
                db.update(OrderItem.class, Obj.of("cancelled", true, "cancelledAt", PosDb.raw(NOW), "cancelReason", reason,
                        "voidedBy", user(), "voidApprovedBy", overrideById, "voidedQty", PosSql.decOf(current)),
                        "id = ?", row.get("id"));
            } else {
                db.update(OrderItem.class, Obj.of("quantity", PosSql.decOf(remaining),
                        "kitchenPrintedQty", row.get("kitchenPrintedQty") == null ? null
                                : PosSql.decOf(Math.min(firedQty, remaining)),
                        "kotPrintedQty", PosSql.decOf(Math.min(num(row.get("kotPrintedQty")), remaining)),
                        "voidedQty", PosSql.decOf(num(row.get("voidedQty")) + asked), "cancelReason", reason,
                        "voidedBy", user(), "voidApprovedBy", overrideById), "id = ?", row.get("id"));
            }
            recomputeOrderTotals(orderId, Map.of());
            TableStatus.recompute(jdbc, orgId, (String) order.get("tableId"));
            audit.recordInTx(AuditInput.of("OrderItem", (String) row.get("id"), "cancel", null, Obj.of("kind", "item_void",
                    "orderId", orderId, "orderNumber", order.get("orderNumber"), "description", row.get("description"),
                    "voidedQuantity", PosSql.js(asked), "remainingQuantity", whole ? 0 : PosSql.js(remaining),
                    "whole", whole, "reason", reason, "approvedById", overrideById, "hadBeenFired", firedQty > 0,
                    "firedQuantity", PosSql.js(firedQty), "unitPrice", PosSql.jsString(row.get("unitPrice")))));
            return new Object[] {row, asked, whole, firedQty};
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) outcome[0];
        double asked = (double) outcome[1];
        boolean whole = (boolean) outcome[2];
        double firedQty = (double) outcome[3];
        if (firedQty > 0) {
            kitchenWaste.record(orgId, orderId, List.of(new KitchenWaste.Line((String) row.get("id"),
                    Math.min(asked, firedQty))), reason, overrideById);
            try {
                kds.cancelOrderItemTickets(orderId, (String) row.get("id"), whole ? null : asked, "Voided: " + reason);
            } catch (RuntimeException e) {
                log.warn("KDS cancel failed for voided item {}: {}", row.get("id"), e.getMessage());
            }
        }
        events.publish(DomainEvents.POS_ORDER_ITEM_VOIDED, Obj.of("organizationId", orgId, "orderId", orderId,
                "orderItemId", row.get("id"), "description", row.get("description"), "quantity", PosSql.js(asked),
                "whole", whole, "reason", reason, "approvedById", overrideById));
        return getOrder(orderId);
    }

    /** Reopen a cancelled (un-billed) order. */
    public Map<String, Object> reopenOrder(String orderId) {
        return tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            if (order.get("invoiceId") != null) {
                throw ApiException.conflict("Billed orders cannot be reopened");
            }
            if (!"cancelled".equals(order.get("status"))) {
                throw ApiException.badRequest("Only a cancelled order can be reopened");
            }
            workflows.transition("order", orderId, "reopen", null, order);
            Map<String, Object> updated = db.update(Order.class, Obj.of("cancelledAt", null, "cancelReason", null,
                    "cancelledBy", null, "version", PosDb.raw("version + 1")), "id = ? AND \"organizationId\" = ?",
                    orderId, org());
            TableStatus.recompute(jdbc, org(), (String) order.get("tableId"));
            return updated;
        });
    }

    /** Move an open order to another table. */
    public Map<String, Object> moveTable(String orderId, String targetTableId) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            assertEditable(order);
            String status = jdbc.sql("SELECT status::text FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(targetTableId, orgId).query(String.class).optional().orElse(null);
            if (status == null) {
                throw ApiException.notFound("Target table not found");
            }
            if ("out_of_service".equals(status)) {
                throw ApiException.conflict("Target table is out of service");
            }
            // Post-parity (owner-approved 2026-11-10): the same one-open-tab rule as opening an order, under the table
            // lock. Nest moved the order onto an occupied table, which then held two tabs and showed only the newest.
            if (!targetTableId.equals(order.get("tableId"))) {
                jdbc.sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(targetTableId, orgId).query().listOfRows();
                Map<String, Object> held = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\" FROM \"Order\" WHERE"
                        + " \"organizationId\" = ? AND \"tableId\" = ? AND \"invoiceId\" IS NULL AND id <> ?"
                        + " AND status::text IN " + TableStatus.HELD_SQL + " LIMIT 1").params(orgId, targetTableId, orderId));
                if (held != null) {
                    throw ApiException.conflict("Table already has an open order (" + held.get("orderNumber") + ")");
                }
            }
            String sourceTableId = (String) order.get("tableId");
            Map<String, Object> updated = db.update(Order.class, Obj.of("tableId", targetTableId,
                    "version", PosDb.raw("version + 1")), "id = ? AND \"organizationId\" = ?", orderId, orgId);
            TableStatus.recompute(jdbc, orgId, sourceTableId);
            TableStatus.recompute(jdbc, orgId, targetTableId);
            audit.recordInTx(AuditInput.of("Order", orderId, "transfer", null, Obj.of("from", sourceTableId,
                    "to", targetTableId)));
            return updated;
        });
    }

    /** Merge a source order's items into this one; the source is superseded. */
    public Map<String, Object> mergeOrders(String targetOrderId, String sourceOrderId) {
        if (targetOrderId.equals(sourceOrderId)) {
            throw ApiException.badRequest("Cannot merge an order into itself");
        }
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> target = lockOrder(targetOrderId);
            Map<String, Object> source = lockOrder(sourceOrderId);
            assertEditable(target);
            assertEditable(source);
            List<Resolved> merged = new ArrayList<>();
            for (Map<String, Object> it : itemsWithModifiers(targetOrderId)) {
                merged.add(PosLines.itemToResolved(it));
            }
            for (Map<String, Object> it : itemsWithModifiers(sourceOrderId)) {
                merged.add(PosLines.itemToResolved(it));
            }
            writeItems(targetOrderId, merged, true, false, Map.of());
            workflows.transition("order", sourceOrderId, "supersede", Obj.of("mergedInto", targetOrderId), source);
            db.update(Order.class, Obj.of("cancelReason", "Merged into " + target.get("orderNumber"),
                    "version", PosDb.raw("version + 1")), "id = ? AND \"organizationId\" = ?", sourceOrderId, orgId);
            TableStatus.recompute(jdbc, orgId, (String) source.get("tableId"));
            audit.recordInTx(AuditInput.of("Order", targetOrderId, "merge", null, Obj.of("sourceOrderId", sourceOrderId)));
            return reload(targetOrderId);
        });
    }

    // ─── kitchen ───

    /** Fire un-sent quantities (delta) to the KDS, one ticket per station; prints the paper KOT unless auto-send. */
    public Map<String, Object> fireKitchen(String orderId, Double course, boolean onlyRouted) {
        String orgId = org();
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        Map<String, String> stationCache = new HashMap<>();
        Map<String, Integer> prepCache = new HashMap<>();
        Object[] result = tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, orgId)
                    .query().listOfRows();
            List<Map<String, Object>> items = itemsWithModifiers(orderId);
            List<Object[]> deltas = new ArrayList<>();
            for (Map<String, Object> it : items) {
                if (!PosSql.truthy(it.get("productId")) && !PosSql.truthy(it.get("menuItemId"))
                        && !PosSql.truthy(it.get("comboId"))) {
                    continue;
                }
                if (course != null && it.get("course") != null && num(it.get("course")) != course) {
                    continue;
                }
                if (onlyRouted && explicitStationFor(it, stationCache).isEmpty()) {
                    continue;
                }
                double delta = num(it.get("quantity")) - num(it.get("kitchenPrintedQty"));
                if (delta > 0) {
                    deltas.add(new Object[] {it, delta});
                }
            }
            if (deltas.isEmpty()) {
                return new Object[] {List.of(), deltas};
            }
            List<Map<String, Object>> kdsItems = new ArrayList<>();
            for (Object[] d : deltas) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) d[0];
                List<Map<String, Object>> mods = new ArrayList<>();
                for (Object o : PosSql.list(item.get("modifiers"))) {
                    Map<String, Object> m = PosSql.map(o);
                    mods.add(Obj.of("name", m.get("name"), "kitchenPrintName", m.get("kitchenPrintName"),
                            "priceDelta", PosSql.js(m.get("priceDelta"))));
                }
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("orderItemId", item.get("id"));
                k.put("productId", item.get("productId") != null ? item.get("productId")
                        : item.get("menuItemId") != null ? item.get("menuItemId") : item.get("comboId"));
                k.put("productName", item.get("description"));
                k.put("quantity", PosSql.js((double) d[1]));
                k.put("modifiers", mods);
                k.put("notes", item.get("note"));
                k.put("station", stationForOrderItem(item, stationCache));
                if (item.get("variantName") != null) {
                    k.put("variantName", item.get("variantName"));
                }
                k.put("accompanimentNames", PosSql.strings(item.get("accompanimentNames")));
                k.put("prepTime", prepTimeForItem(item, prepCache));
                k.put("course", item.get("course"));
                kdsItems.add(k);
            }
            List<String> ticketIds = kds.createTicketsForSale(null, orderId, (String) order.get("orderNumber"),
                    order.get("orderType") == null ? null : order.get("orderType").toString(), null, kdsItems, true);
            for (Object[] d : deltas) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) d[0];
                db.update(OrderItem.class, Obj.of("kitchenPrintedQty", item.get("quantity"), "kitchenStatus", "sent",
                        "kitchenPrintCount", PosDb.raw("\"kitchenPrintCount\" + 1"), "kitchenLastPrintedAt", PosDb.raw(NOW),
                        "lastKitchenPrintedById", user()), "id = ?", item.get("id"));
            }
            return new Object[] {ticketIds, deltas};
        });
        @SuppressWarnings("unchecked")
        List<String> ticketIds = (List<String>) result[0];
        @SuppressWarnings("unchecked")
        List<Object[]> deltas = (List<Object[]>) result[1];
        if (deltas.isEmpty()) {
            return Obj.of("ticketIds", List.of(), "count", 0, "message", "No new items to send");
        }
        if ("confirmed".equals(order.get("status")) || "open".equals(order.get("status"))) {
            workflows.transition("order", orderId, "start_fulfillment", null, order);
        }
        audit.record(AuditInput.of("Order", orderId, "update", null, Obj.of("kind", "fire_kitchen",
                "tickets", ticketIds.size())));
        if (onlyRouted) {
            return Obj.of("ticketIds", ticketIds, "count", ticketIds.size());
        }
        Map<String, Object> paper = receipts.printKotDelta(orderId, user());
        Map<String, Object> out = Obj.of("ticketIds", ticketIds, "count", ticketIds.size(), "paperKot", paper.get("backend"));
        if (paper.get("kotNumber") != null) {
            out.put("kotNumber", paper.get("kotNumber"));
        }
        return out;
    }

    public Map<String, Object> fireKitchen(String orderId) {
        return fireKitchen(orderId, null, false);
    }

    // ─── internals ───

    private Map<String, Object> actorStamp() {
        String userId = user();
        if (userId == null) {
            return Obj.of("punchedById", null, "punchedByName", null);
        }
        String cached = actorNameCache.get(userId);
        if (cached != null) {
            return Obj.of("punchedById", userId, "punchedByName", cached);
        }
        String name = null;
        try {
            Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"User\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(userId, org()));
            if (u != null) {
                String n = (u.get("firstName") + (PosSql.truthy(u.get("lastName")) ? " " + u.get("lastName") : "")).trim();
                name = n.isEmpty() ? null : n;
            }
        } catch (RuntimeException e) {
            name = null;
        }
        if (name != null) {
            actorNameCache.put(userId, name);
        }
        return Obj.of("punchedById", userId, "punchedByName", name);
    }

    String nextOrderNumber() {
        String ymd = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString().replace("-", "");
        return sequence.next("order:" + ymd, "ORD-" + ymd + "-", 6);
    }

    private String stationForOrderItem(Map<String, Object> it, Map<String, String> cache) {
        String override = explicitStationFor(it, cache);
        if (!override.isEmpty()) {
            return override;
        }
        String orgId = org();
        if (PosSql.truthy(it.get("comboId"))) {
            String key = "c:" + it.get("comboId");
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            Map<String, Object> combo = modifiers.getCombo((String) it.get("comboId"));
            List<String> stations = new ArrayList<>();
            if (combo != null) {
                for (Object o : PosSql.list(combo.get("items"))) {
                    String s = jdbc.sql("SELECT station FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL").params(PosSql.map(o).get("productId"), orgId)
                            .query(String.class).optional().orElse(null);
                    if (s != null && !s.isEmpty()) {
                        stations.add(s);
                    }
                }
            }
            String station = stations.isEmpty() ? defaultStationCode(cache) : pickPrimaryStation(stations);
            cache.put(key, station);
            return station;
        }
        if (PosSql.truthy(it.get("menuItemId"))) {
            String key = "m:" + it.get("menuItemId");
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            List<String> stations = new ArrayList<>();
            for (String s : jdbc.sql("SELECT p.station FROM \"MenuProduct\" mp LEFT JOIN \"Product\" p ON p.id = mp.\"productId\""
                    + " WHERE mp.\"menuItemId\" = ? AND mp.\"organizationId\" = ?").params(it.get("menuItemId"), orgId)
                    .query(String.class).list()) {
                if (s != null && !s.isEmpty()) {
                    stations.add(s);
                }
            }
            String st = stations.isEmpty() ? defaultStationCode(cache) : pickPrimaryStation(stations);
            cache.put(key, st);
            return st;
        }
        if (PosSql.truthy(it.get("productId"))) {
            String key = "p:" + it.get("productId");
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            String s = jdbc.sql("SELECT station FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(it.get("productId"), orgId).query(String.class).optional().orElse(null);
            String st = s != null && !s.isEmpty() ? s : defaultStationCode(cache);
            cache.put(key, st);
            return st;
        }
        return defaultStationCode(cache);
    }

    private String explicitStationFor(Map<String, Object> it, Map<String, String> cache) {
        if (!PosSql.truthy(it.get("menuItemId"))) {
            return "";
        }
        String key = "mo:" + it.get("menuItemId");
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String code = jdbc.sql("SELECT \"stationCode\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(it.get("menuItemId"), org())
                .query(String.class).optional().orElse(null);
        String c = code == null ? "" : code;
        cache.put(key, c);
        return c;
    }

    private String defaultStationCode(Map<String, String> cache) {
        String cached = cache.get("__default__");
        if (cached != null) {
            return cached;
        }
        String code = jdbc.sql("SELECT code FROM \"KitchenStation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"isDefault\" = true LIMIT 1").param(org()).query(String.class).optional().orElse("cafe");
        cache.put("__default__", code);
        return code;
    }

    static String pickPrimaryStation(List<String> stations) {
        if (stations.isEmpty()) {
            return "cafe";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : stations) {
            counts.merge(s, 1, Integer::sum);
        }
        String best = stations.getFirst();
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private Integer prepTimeForItem(Map<String, Object> it, Map<String, Integer> cache) {
        if (!PosSql.truthy(it.get("menuItemId"))) {
            return null;
        }
        String id = (String) it.get("menuItemId");
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        Integer t = jdbc.sql("SELECT \"preparationTime\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, org()).query(Integer.class).optional().orElse(null);
        cache.put(id, t);
        return t;
    }

    /** Variant + accompaniment + modifier rules before pricing. */
    private void validateLines(List<Map<String, Object>> lines, boolean bypassRequired) {
        for (Map<String, Object> ln : lines) {
            String menuItemId = (String) ln.get("menuItemId");
            if (PosSql.truthy(ln.get("variantId")) && PosSql.truthy(menuItemId)) {
                variants.validateVariant(menuItemId, (String) ln.get("variantId"));
            }
            if (PosSql.truthy(menuItemId)) {
                accompaniments.validateSelections(menuItemId, PosSql.strings(ln.get("accompanimentOptionIds")),
                        bypassRequired);
            }
        }
        List<Map<String, Object>> noCombo = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (!PosSql.truthy(l.get("comboId"))) {
                noCombo.add(l);
            }
        }
        modifiers.validateSelections(noCombo, bypassRequired);
    }

    /** The override user exists and holds {@code pos:override}. */
    private boolean assertOverride(String overrideById) {
        BusinessOutcome.Operation op = BusinessOutcome.current();
        String approved = op == null ? null : op.approvedById();
        if (!overrideById.equals(approved) && !overrideById.equals(user())) {
            throw ApiException.forbidden("An authenticated manager approval is required");
        }
        long exists = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(overrideById, org()).query(Long.class).single();
        if (exists == 0) {
            throw ApiException.notFound("Override user not found");
        }
        List<String> perms = jdbc.sql("SELECT DISTINCT p FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\","
                + " unnest(r.permissions) p WHERE ur.\"B\" = ?").param(overrideById).query(String.class).list();
        if (!perms.contains("pos:override")) {
            throw ApiException.forbidden("User does not have pos:override permission");
        }
        return true;
    }

    /** Fold variants / accompaniments / modifiers into unitPrice and expand combos. */
    List<Resolved> resolveLines(List<Map<String, Object>> inputLines) {
        String orgId = org();
        Map<String, String> skuMap = resolveSkus(inputLines);
        List<Resolved> lines = new ArrayList<>();
        for (Map<String, Object> l : inputLines) {
            double qty = l.get("quantity") == null ? Double.NaN : num(l.get("quantity"));
            if (!Double.isFinite(qty)) {
                throw ApiException.badRequest("Sale quantity must be a number");
            }
            if (qty < 0) {
                throw ApiException.badRequest("Sale quantities cannot be negative");
            }
            double discountPercent = l.get("discountPercent") == null ? 0 : num(l.get("discountPercent"));
            String discountType = l.get("discountType") == null ? null : l.get("discountType").toString();
            Double discountAmount = l.get("discountAmount") == null ? null : num(l.get("discountAmount"));
            String discountReason = (String) l.get("discountReason");
            Double course = l.get("course") == null ? null : num(l.get("course"));
            if (PosSql.truthy(l.get("comboId"))) {
                Map<String, Object> combo = modifiers.getCombo((String) l.get("comboId"));
                if (combo == null || PosSql.list(combo.get("items")).isEmpty()) {
                    throw ApiException.badRequest("This combo is unavailable or has no components");
                }
                lines.add(new Resolved(null, null, (String) combo.get("id"), (String) combo.get("name"), qty,
                        num(combo.get("price")), null, discountPercent, discountType, discountAmount, discountReason,
                        (String) l.get("note"), false, List.of(), null, null, List.of(), List.of(), course, true, false));
                continue;
            }
            String sku = l.get("sku") == null ? "" : l.get("sku").toString().toLowerCase();
            String productId = l.get("productId") != null ? (String) l.get("productId") : skuMap.get(sku);
            Map<String, Object> product = productId == null ? null : db.first(Product.class, "id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND \"isActive\" = true", productId, orgId);
            Map<String, Object> menuItem = l.get("menuItemId") == null ? null : db.first(MenuItem.class,
                    "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isAvailable\" = true",
                    l.get("menuItemId"), orgId);
            String menuItemId = menuItem != null ? (String) l.get("menuItemId") : null;
            Map<String, Object> catalog = menuItem != null ? menuItem : product;
            if (catalog == null) {
                throw ApiException.badRequest("A sale line must identify an available catalog item");
            }
            List<String> modIds = new ArrayList<>();
            for (Object o : PosSql.list(l.get("modifiers"))) {
                modIds.add((String) PosSql.map(o).get("modifierId"));
            }
            List<PosModifiersService.Resolved> resolvedMods = modIds.isEmpty() ? List.of()
                    : modifiers.resolveSelectedModifiers(menuItemId, (String) l.get("productId"), modIds);
            double modifierDelta = 0;
            for (PosModifiersService.Resolved m : resolvedMods) {
                modifierDelta += m.priceDelta();
            }
            String variantName = null;
            double variantPrice = 0;
            boolean hasVariant = false;
            String variantId = (String) l.get("variantId");
            if (PosSql.truthy(variantId) && menuItemId != null) {
                Map<String, Object> v = variants.validateVariant(menuItemId, variantId);
                variantName = (String) v.get("name");
                variantPrice = num(v.get("price"));
                hasVariant = true;
            }
            double accompanimentImpact = 0;
            List<String> accompanimentNames = new ArrayList<>();
            List<String> accIds = PosSql.strings(l.get("accompanimentOptionIds"));
            if (!accIds.isEmpty() && menuItemId != null) {
                PosAccompanimentService.Selection r = accompaniments.validateSelections(menuItemId, accIds, true);
                accompanimentImpact = r.priceImpact();
                accompanimentNames = new ArrayList<>(r.names());
            }
            if (PosSql.truthy(variantId) && productId != null && menuItemId == null) {
                Map<String, Object> variant = SqlRows.one(jdbc.sql("SELECT name, \"salesPrice\" FROM \"ProductVariant\""
                        + " WHERE id = ? AND \"productId\" = ? AND \"organizationId\" = ? AND \"isActive\" = true"
                        + " AND \"deletedAt\" IS NULL LIMIT 1").params(variantId, productId, orgId));
                if (variant == null) {
                    throw ApiException.badRequest("Variant does not belong to this product");
                }
                variantName = (String) variant.get("name");
                if (variant.get("salesPrice") != null) {
                    variantPrice = num(variant.get("salesPrice"));
                    hasVariant = true;
                }
            }
            Object catalogPrice = menuItemId != null ? catalog.get("basePrice") : catalog.get("salesPrice");
            if (!hasVariant && catalogPrice == null) {
                throw ApiException.badRequest("Configure a catalog selling price before selling this item");
            }
            double baseUnitPrice = hasVariant ? variantPrice : num(catalogPrice);
            double finalUnitPrice = baseUnitPrice + accompanimentImpact + modifierDelta;
            if (!Double.isFinite(finalUnitPrice) || finalUnitPrice < 0) {
                throw ApiException.badRequest("Catalog price must be finite and non-negative");
            }
            String taxId = (String) catalog.get("taxId");
            Boolean taxRowInclusive = taxId == null ? null : ReadMemo.get(() -> jdbc.sql("SELECT \"isInclusive\" FROM"
                    + " \"Tax\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(taxId, orgId)
                    .query(Boolean.class).optional().orElse(null), "Tax.isInclusive", orgId, taxId);
            // Post-parity (owner-approved 2026-11-10): the catalog tax decides inclusivity. Nest let the request's
            // taxInclusive override it, so a crafted line could sell an 18%-exclusive item at the net price.
            boolean taxInclusive = taxRowInclusive != null ? taxRowInclusive
                    : l.get("taxInclusive") instanceof Boolean b ? b : Boolean.TRUE.equals(catalog.get("taxInclusive"));
            List<String> noteParts = new ArrayList<>();
            if (PosSql.truthy(l.get("note"))) {
                noteParts.add(l.get("note").toString());
            }
            accompanimentNames.forEach(n -> noteParts.add("+ " + n));
            resolvedMods.forEach(m -> noteParts.add("+ " + m.name()));
            List<Mod> mods = new ArrayList<>();
            resolvedMods.forEach(m -> mods.add(new Mod(m.modifierId(), m.name(), m.kitchenPrintName(), m.priceDelta())));
            lines.add(new Resolved(productId, menuItemId, null, (String) l.get("description"), qty, finalUnitPrice, taxId,
                    discountPercent, discountType, discountAmount, discountReason,
                    noteParts.isEmpty() ? null : String.join(" | ", noteParts), taxInclusive, mods,
                    PosSql.truthy(variantId) ? variantId : null, variantName, accompanimentNames, accIds, course, false,
                    true));
        }
        return lines;
    }

    /** Table merge: fold an open tab's items into another open tab (caller's transaction). */
    public void absorbOrderInTx(String targetOrderId, String sourceOrderId, String reason) {
        String orgId = org();
        Map<String, Object> source = db.first(Order.class, "id = ? AND \"organizationId\" = ?", sourceOrderId, orgId);
        if (source == null || source.get("invoiceId") != null || "closed".equals(source.get("status"))
                || "cancelled".equals(source.get("status"))) {
            throw ApiException.conflict("Only an open, unbilled order can be merged");
        }
        if (num(source.get("transactionDiscountPercent")) > 0 || num(source.get("transactionDiscountAmount")) > 0) {
            throw ApiException.conflict("Remove the order discount on the merged table before merging");
        }
        if (jdbc.sql("SELECT count(*) FROM \"SplitBill\" WHERE \"organizationId\" = ? AND \"sourceOrderId\" = ?")
                .params(orgId, sourceOrderId).query(Long.class).single() > 0) {
            throw ApiException.conflict("This table has split bills in progress — settle or clear them before merging");
        }
        Integer last = jdbc.sql("SELECT max(\"lineNumber\") FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ?")
                .params(targetOrderId, orgId).query(Integer.class).optional().orElse(null);
        int lineNumber = last == null ? 0 : last;
        for (String id : jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY \"lineNumber\" ASC").params(sourceOrderId, orgId).query(String.class).list()) {
            db.update(OrderItem.class, Obj.of("orderId", targetOrderId, "lineNumber", ++lineNumber), "id = ?", id);
        }
        jdbc.sql("UPDATE \"KitchenTicket\" SET \"orderId\" = ?, \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ?").params(targetOrderId, orgId, sourceOrderId).update();
        db.update(Order.class, Obj.of("status", "cancelled", "cancelledAt", PosDb.raw(NOW), "cancelReason", reason,
                "subtotal", 0, "discountTotal", 0, "taxAmount", 0, "totalAmount", 0, "version", PosDb.raw("version + 1")),
                "id = ? AND \"organizationId\" = ?", sourceOrderId, orgId);
        recomputeOrderTotals(targetOrderId, Map.of());
    }

    /** Refresh the header snapshot from the active items (order discount re-applied). */
    void recomputeOrderTotals(String orderId, Map<String, Object> pricingIn) {
        String orgId = org();
        Map<String, Object> header = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        List<Resolved> lines = new ArrayList<>();
        for (Map<String, Object> it : itemsWithModifiers(orderId)) {
            lines.add(PosLines.itemToResolved(it));
        }
        Map<String, Object> applied = new LinkedHashMap<>();
        if (!lines.isEmpty()) {
            applied.put("transactionDiscountType", pricingIn.get("transactionDiscountType") != null
                    ? pricingIn.get("transactionDiscountType")
                    : header.get("transactionDiscountType") != null ? header.get("transactionDiscountType") : "percentage");
            applied.put("transactionDiscountPercent", pricingIn.get("transactionDiscountPercent") != null
                    ? pricingIn.get("transactionDiscountPercent") : num(header.get("transactionDiscountPercent")));
            applied.put("transactionDiscountAmount", pricingIn.get("transactionDiscountAmount") != null
                    ? pricingIn.get("transactionDiscountAmount") : num(header.get("transactionDiscountAmount")));
            applied.put("discountReason", pricingIn.get("discountReason") != null ? pricingIn.get("discountReason")
                    : header.get("discountReason"));
        } else {
            applied.put("transactionDiscountType", "percentage");
            applied.put("transactionDiscountPercent", 0);
            applied.put("transactionDiscountAmount", 0);
            applied.put("discountReason", null);
        }
        Totals discounted = prepare(PricingPolicy.discountedLines(preparedInputs(lines), applied));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subtotal", discounted.subtotal());
        data.put("taxAmount", discounted.taxAmount());
        data.put("totalAmount", discounted.total());
        data.put("discountTotal", discounted.discountTotal());
        data.put("transactionDiscountType", applied.get("transactionDiscountType"));
        data.put("transactionDiscountPercent", PosSql.dec(applied.get("transactionDiscountPercent")));
        data.put("transactionDiscountAmount", PosSql.dec(applied.get("transactionDiscountAmount")));
        data.put("discountReason", applied.get("discountReason"));
        data.put("version", PosDb.raw("version + 1"));
        db.update(Order.class, data, "id = ? AND \"organizationId\" = ?", orderId, orgId);
    }

    /**
     * Persist resolved lines with the F10 one-to-one signature diff; dropped rows are soft-cancelled, fired rows may
     * not leave this way. {@code opts} carries the pricing fields ({@code transactionDiscount*}, {@code discountReason}).
     */
    private void writeItems(String orderId, List<Resolved> resolved, boolean replace, boolean append,
            Map<String, Object> opts) {
        String orgId = org();
        TableStatus.lockFloorShared(jdbc, orgId);
        List<Map<String, Object>> existing = replace || append ? itemsWithModifiers(orderId) : List.of();
        List<Resolved> all = new ArrayList<>();
        if (append) {
            for (Map<String, Object> it : existing) {
                all.add(PosLines.itemToResolved(it));
            }
        }
        all.addAll(resolved);
        Map<String, Deque<Map<String, Object>>> queue = new LinkedHashMap<>();
        for (Map<String, Object> row : existing) {
            queue.computeIfAbsent(PosLines.rowSignature(row), k -> new ArrayDeque<>()).add(row);
        }
        Totals totals = prepare(preparedInputs(all));
        Map<String, Object> stamp = actorStamp();
        List<Object[]> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Resolved src = all.get(i);
            Deque<Map<String, Object>> q = queue.get(PosLines.resolvedSignature(src));
            Map<String, Object> row = q == null ? null : q.pollFirst();
            pairs.add(new Object[] {src, totals.prepared().get(i), row});
        }
        List<Map<String, Object>> removed = new ArrayList<>();
        queue.values().forEach(removed::addAll);
        List<Map<String, Object>> cut = new ArrayList<>();
        for (Object[] p : pairs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) p[2];
            PreparedLine prepared = (PreparedLine) p[1];
            if (row != null && num(row.get("kitchenPrintedQty")) > 0
                    && prepared.quantity().doubleValue() < num(row.get("kitchenPrintedQty")) - 0.000001) {
                cut.add(row);
            }
        }
        OrderMutationPolicy.assertNoFiredItemLoss(removed, cut);
        for (Object[] p : pairs) {
            Resolved src = (Resolved) p[0];
            PreparedLine prepared = (PreparedLine) p[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) p[2];
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("productId", prepared.productId());
            data.put("menuItemId", prepared.menuItemId());
            data.put("comboId", src.comboId());
            if (prepared.variantId() != null) {
                data.put("variantId", prepared.variantId());
            }
            if (prepared.variantName() != null) {
                data.put("variantName", prepared.variantName());
            }
            data.put("description", prepared.description());
            data.put("quantity", prepared.quantity());
            data.put("unitPrice", prepared.unitPrice());
            data.put("discountPercent", prepared.discountPercent());
            data.put("discountType", prepared.discountType() == null ? "percentage" : prepared.discountType());
            data.put("discountAmount", prepared.discountAmount() == null ? BigDecimal.ZERO : prepared.discountAmount());
            data.put("discountReason", prepared.discountReason());
            data.put("taxId", prepared.taxId());
            data.put("taxInclusive", prepared.taxInclusive());
            data.put("note", src.note());
            data.put("accompanimentNames", src.accompanimentNames());
            data.put("accompanimentOptionIds", src.accompanimentOptionIds());
            data.put("course", src.course() == null ? null : (int) Math.round(src.course()));
            data.put("lineNumber", prepared.lineNumber());
            String itemId;
            if (row != null) {
                db.update(OrderItem.class, data, "id = ?", row.get("id"));
                itemId = (String) row.get("id");
                jdbc.sql("DELETE FROM \"OrderItemModifier\" WHERE \"orderItemId\" = ?").param(itemId).update();
            } else {
                Map<String, Object> create = new LinkedHashMap<>();
                create.put("organizationId", orgId);
                create.put("orderId", orderId);
                create.putAll(data);
                create.put("punchedById", stamp.get("punchedById"));
                create.put("punchedByName", stamp.get("punchedByName"));
                create.put("kitchenStatus", "pending");
                create.put("kitchenPrintCount", 0);
                itemId = (String) db.insert(OrderItem.class, create).get("id");
            }
            for (Mod m : src.modifiers()) {
                db.insert(OrderItemModifier.class, Obj.of("organizationId", orgId, "orderItemId", itemId,
                        "modifierId", m.modifierId(), "name", m.name(), "kitchenPrintName", m.kitchenPrintName(),
                        "priceDelta", PosSql.decOf(m.priceDelta())));
            }
        }
        for (Map<String, Object> row : removed) {
            db.update(OrderItem.class, Obj.of("cancelled", true, "cancelledAt", PosDb.raw(NOW),
                    "cancelReason", "Removed from the order before it was sent to the kitchen", "voidedBy", user(),
                    "voidedQty", row.get("quantity")), "id = ?", row.get("id"));
        }
        if (replace || append) {
            List<Map<String, Object>> added = new ArrayList<>();
            List<Map<String, Object>> requantified = new ArrayList<>();
            for (Object[] p : pairs) {
                PreparedLine prepared = (PreparedLine) p[1];
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) p[2];
                if (row == null) {
                    added.add(Obj.of("description", prepared.description(),
                            "quantity", PosSql.js(prepared.quantity())));
                } else if (Math.abs(prepared.quantity().doubleValue() - num(row.get("quantity"))) > 0.000001) {
                    requantified.add(Obj.of("description", prepared.description(), "from", PosSql.js(row.get("quantity")),
                            "to", PosSql.js(prepared.quantity())));
                }
            }
            List<Map<String, Object>> dropped = new ArrayList<>();
            removed.forEach(r -> dropped.add(Obj.of("description", r.get("description"), "quantity",
                    PosSql.js(r.get("quantity")))));
            if (!added.isEmpty() || !requantified.isEmpty() || !dropped.isEmpty()) {
                audit.recordInTx(AuditInput.of("Order", orderId, "update", null, Obj.of("kind", "items_saved",
                        "added", added, "requantified", requantified, "removed", dropped)));
            }
        }
        Map<String, Object> pricingOpts = new LinkedHashMap<>();
        if (!all.isEmpty()) {
            for (String k : List.of("transactionDiscountType", "transactionDiscountPercent", "transactionDiscountAmount",
                    "discountReason")) {
                if (opts.get(k) != null) {
                    pricingOpts.put(k, opts.get(k));
                }
            }
        } else {
            pricingOpts.put("transactionDiscountType", "percentage");
            pricingOpts.put("transactionDiscountPercent", 0);
            pricingOpts.put("transactionDiscountAmount", 0);
        }
        recomputeOrderTotals(orderId, pricingOpts);
    }

    /** Order + live items (+ modifiers); no tenant predicate (Nest {@code where: { id }} inside the scoped tx). */
    private Map<String, Object> reload(String orderId) {
        Map<String, Object> o = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (o != null) {
            o.put("items", itemsWithModifiers(orderId));
        }
        return o;
    }

    /** Lock the order (and its register session, which must still be open). */
    Map<String, Object> lockOrder(String orderId) {
        String orgId = org();
        String sessionId = jdbc.sql("SELECT \"cashSessionId\" FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?")
                .params(orderId, orgId).query(String.class).optional().orElse(null);
        if (sessionId != null) {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(sessionId, orgId).query().listOfRows();
            String status = jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(sessionId, orgId).query(String.class).optional().orElse(null);
            if (!"open".equals(status)) {
                throw ApiException.conflict("The order belongs to a closed register session");
            }
        }
        jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, orgId)
                .query().listOfRows();
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        return order;
    }

    static void assertEditable(Map<String, Object> order) {
        if (order.get("invoiceId") != null) {
            throw ApiException.conflict("Order is already billed and cannot be edited");
        }
        if ("cancelled".equals(order.get("status"))) {
            throw ApiException.conflict("Order is cancelled");
        }
        if ("closed".equals(order.get("status"))) {
            throw ApiException.conflict("Order is closed");
        }
    }

    static void assertVersion(Map<String, Object> order, Object expected) {
        if (expected == null) {
            throw ApiException.conflict("An order version is required before replacing saved items");
        }
        int version = PosSql.intOf(order.get("version"));
        if (version != num(expected)) {
            throw ApiException.conflict("Order was modified by someone else (expected v" + JsFormat.str(num(expected))
                    + ", found v" + version + "). Reload and retry.");
        }
    }

    private Map<String, String> resolveSkus(List<Map<String, Object>> lines) {
        LinkedHashSet<String> skus = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            if (!PosSql.truthy(l.get("productId")) && !PosSql.truthy(l.get("menuItemId")) && PosSql.truthy(l.get("sku"))) {
                skus.add(l.get("sku").toString().toLowerCase());
            }
        }
        Map<String, String> map = new HashMap<>();
        if (skus.isEmpty()) {
            return map;
        }
        List<String> list = new ArrayList<>(skus);
        List<Object> params = new ArrayList<>(List.of(org()));
        params.addAll(list);
        params.addAll(list);
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, sku, code FROM \"Product\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"isActive\" = true AND (lower(sku) IN (" + PosSql.marks(list.size())
                + ") OR lower(code) IN (" + PosSql.marks(list.size()) + "))").params(params))) {
            if (p.get("sku") != null) {
                map.put(p.get("sku").toString().toLowerCase(), (String) p.get("id"));
            }
            if (p.get("code") != null) {
                map.put(p.get("code").toString().toLowerCase(), (String) p.get("id"));
            }
        }
        return map;
    }

    /** The WALKIN partner (created on first use). */
    public String ensureWalkInCustomer(String orgId) {
        String id = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND code = 'WALKIN' AND \"deletedAt\" IS NULL"
                + " LIMIT 1").param(orgId).query(String.class).optional().orElse(null);
        if (id != null) {
            return id;
        }
        return (String) db.insert(com.inventoryerp.core.domain.Partner.class, Obj.of("organizationId", orgId,
                "code", "WALKIN", "name", "Walk-in Customer", "isCustomer", true, "isCompany", false)).get("id");
    }
}
