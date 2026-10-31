package com.inventoryerp.orders.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.core.application.PriceListService;
import com.inventoryerp.core.application.PriceListService.ListPrice;
import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import com.inventoryerp.invoicing.application.CreditControlService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosOrdersService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.pos.application.TableStatus;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generic back-office Orders (port of {@code orders/orders.service.ts}): a plain operational order document over the
 * shared {@code Order} aggregate, no terminal coupling (no tables/KDS/cash sessions/merge-split). Pricing runs through
 * the DocumentBuilder tax engine so menu-item and product lines price identically; numbering, cancel/reopen and billing
 * reuse the POS spines. Line inputs are JS-shaped maps ({@code OrderLineInputDto}). Every save passes the customer's
 * credit check (SALES-1); the internal key {@code creditApproved} on {@link #createOrder} is set only by a caller that
 * already put the sale through an approval (it cannot arrive over HTTP: the request record has no such field).
 *
 * <p>SALES-2: with a {@value #APPROVAL_ENTITY} workflow an order needs approval before it is billed here. The request
 * is raised on create, or by the first billing attempt, and an approval stops counting once the order's total goes
 * above what was approved. Only this service's billing is gated: POS routes are frozen.
 */
@Service
public class OrdersService {

    public static final String APPROVAL_ENTITY = "sales_order";

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final SequenceService sequence;
    private final DocumentBuilderService builder;
    private final WorkflowService workflows;
    private final PosInvoiceService billing;
    private final PosOrdersService posOrders;
    private final ApprovalsService approvals;
    private final UomConversionService units;
    private final CreditControlService credit;
    private final BranchScopeService scopes;
    private final PriceListService priceLists;
    private final TransactionTemplate tx;

    public OrdersService(PosDb db, AuditService audit, EventBus events, SequenceService sequence,
            DocumentBuilderService builder, WorkflowService workflows, PosInvoiceService billing,
            PosOrdersService posOrders, ApprovalsService approvals, UomConversionService units,
            CreditControlService credit, BranchScopeService scopes, PriceListService priceLists,
            PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.events = events;
        this.sequence = sequence;
        this.builder = builder;
        this.workflows = workflows;
        this.billing = billing;
        this.posOrders = posOrders;
        this.approvals = approvals;
        this.units = units;
        this.credit = credit;
        this.scopes = scopes;
        this.priceLists = priceLists;
        this.tx = new TransactionTemplate(txm);
    }

    // ─── line-source configuration ───

    private Map<String, Object> moduleConfig(String orgId, String moduleName) {
        Map<String, Object> mod = SqlRows.one(jdbc.sql("SELECT config FROM \"OrganizationModule\" WHERE"
                + " \"organizationId\" = ? AND \"moduleName\" = ?").params(orgId, moduleName));
        if (mod == null) {
            return null;
        }
        Object cfg = PosSql.java(mod.get("config"));
        return cfg instanceof Map<?, ?> ? new LinkedHashMap<>(PosSql.map(cfg)) : null;
    }

    /** Line source for the Orders pages: explicit config wins, {@code auto} follows the POS {@code posMode}. */
    public Map<String, Object> getOrderSettings() {
        String orgId = org();
        Map<String, Object> ordersCfg = moduleConfig(orgId, "orders");
        Map<String, Object> posCfg = moduleConfig(orgId, "pos");
        Object ls = ordersCfg == null ? null : ordersCfg.get("lineSource");
        Object lineSource = ls == null ? "auto" : ls;
        Object pm = posCfg == null ? null : posCfg.get("posMode");
        String posMode = PosSql.jsString(pm == null ? "cafe" : pm);
        Object resolved = !"auto".equals(lineSource) ? lineSource
                : "retail".equals(posMode) || "rental".equals(posMode) ? "products" : "menu";
        return Obj.of("lineSource", lineSource, "resolved", resolved, "posMode", posMode);
    }

    public Map<String, Object> updateOrderSettings(Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = moduleConfig(orgId, "orders");
        Map<String, Object> config = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        config.putAll(dto);
        jdbc.sql("INSERT INTO \"OrganizationModule\" (id, \"organizationId\", \"moduleName\", \"isActive\", config,"
                        + " \"enabledAt\") VALUES (?, ?, 'orders', true, ?::jsonb, " + NOW + ") ON CONFLICT"
                        + " (\"organizationId\", \"moduleName\") DO UPDATE SET config = EXCLUDED.config")
                .params(UUID.randomUUID().toString(), orgId, EntityJson.toJson(config)).update();
        Object lineSource = dto.get("lineSource");
        if (PosSql.truthy(lineSource)) {
            jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", scope, \"scopeType\", \"scopeId\", key, value,"
                            + " \"createdAt\", \"updatedAt\") VALUES (?, ?, 'organization', 'organization', '',"
                            + " 'orders.lineSource', ?::jsonb, " + NOW + ", " + NOW + ") ON CONFLICT (\"organizationId\","
                            + " \"scopeType\", \"scopeId\", key) DO UPDATE SET value = EXCLUDED.value, \"updatedAt\" = "
                            + NOW)
                    .params(UUID.randomUUID().toString(), orgId, EntityJson.toJson(lineSource)).update();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object value = lineSource != null ? lineSource : config.get("lineSource");
        if (value != null) {
            out.put("lineSource", value);
        }
        return out;
    }

    // ─── queries ───

    /** List filter ({@code GET /orders} query). */
    public record ListFilter(int page, int pageSize, String search, String status, String orderType,
            String transactionKind, String dateFrom, String dateTo) {}

    /** Paginated list in the invoicing CRUD shape {@code { rows, total, meta }}. */
    public Map<String, Object> list(ListFilter f) {
        String orgId = org();
        int page = Math.max(1, f.page() == 0 ? 1 : f.page());
        int pageSize = Math.min(200, Math.max(1, f.pageSize() == 0 ? 25 : f.pageSize()));
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (PosSql.truthy(f.status())) {
            where.append(" AND status::text = ?");
            params.add(f.status());
        }
        if (PosSql.truthy(f.orderType())) {
            where.append(" AND \"orderType\"::text = ?");
            params.add(f.orderType());
        }
        if (PosSql.truthy(f.transactionKind())) {
            where.append(" AND \"transactionKind\" = ?");
            params.add(f.transactionKind());
        }
        if (PosSql.truthy(f.dateFrom())) {
            where.append(" AND \"openedAt\" >= ?");
            params.add(SqlRows.ts(jsDate(f.dateFrom())));
        }
        if (PosSql.truthy(f.dateTo())) {
            Instant end = jsDate(f.dateTo()).atOffset(ZoneOffset.UTC).toLocalDate().atTime(23, 59, 59, 999_000_000)
                    .toInstant(ZoneOffset.UTC);
            where.append(" AND \"openedAt\" <= ?");
            params.add(SqlRows.ts(end));
        }
        DataScope scope = scopes.scope();
        if (scope.restricted()) {
            if (scope.branchIds().isEmpty()) {
                where.append(" AND false");
            } else {
                where.append(" AND \"branchId\" IN (").append(PosSql.marks(scope.branchIds().size())).append(')');
                params.addAll(scope.branchIds());
            }
        }
        if (f.search() != null && !f.search().trim().isEmpty()) {
            String search = f.search().trim();
            List<String> pIds = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND name ILIKE ?").params(orgId, PosSql.contains(search)).query(String.class).list();
            where.append(" AND (\"orderNumber\" ILIKE ?");
            params.add(PosSql.contains(search));
            if (!pIds.isEmpty()) {
                where.append(" OR \"partnerId\" IN (").append(PosSql.marks(pIds.size())).append(')');
                params.addAll(pIds);
            }
            where.append(')');
        }
        Object[] p = params.toArray();
        List<Map<String, Object>> rows = db.list(Order.class, where + " ORDER BY \"openedAt\" DESC LIMIT " + pageSize
                + " OFFSET " + (long) (page - 1) * pageSize, p);
        long total = db.count(Order.class, where.toString(), p);
        Map<String, String> nameById = partnerNames(rows.stream().map(o -> (String) o.get("partnerId")).toList());
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> o : rows) {
            String partnerId = (String) o.get("partnerId");
            data.add(Obj.of("id", o.get("id"), "orderNumber", o.get("orderNumber"), "status", o.get("status"),
                    "orderType", o.get("orderType"), "transactionKind", o.get("transactionKind"),
                    "partnerId", partnerId, "partnerName", partnerId == null ? null : nameById.get(partnerId),
                    "openedAt", o.get("openedAt"), "totalAmount", PosSql.js(o.get("totalAmount")),
                    "invoiceId", o.get("invoiceId")));
        }
        return Obj.of("rows", data, "total", total, "meta", Obj.of("page", page, "pageSize", pageSize,
                "total", total, "totalPages", Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    /** JS {@code new Date(s)}: date-only = UTC midnight, date-time without offset = server local (UTC). */
    private static Instant jsDate(String s) {
        Instant i = EntityPatch.toInstant(s);
        if (i == null) {
            throw new IllegalStateException("Invalid value for argument `gte`: Provided Date object is invalid.");
        }
        return i;
    }

    public Map<String, Object> getOrder(String orderId) {
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (order == null || !scopes.scope().allowsBranch((String) order.get("branchId"))) {
            throw ApiException.notFound("Order not found");
        }
        withItemsAndInvoice(order);
        return decorate(order);
    }

    // ─── mutations ───

    public Map<String, Object> createOrder(Map<String, Object> dto) {
        String orgId = org();
        List<Line> resolved = new ArrayList<>();
        for (Map<String, Object> l : maps(dto.get("lines"))) {
            resolved.add(Line.of(l));
        }
        Map<String, Object> order = tx.execute(st -> {
            String orderNumber = nextOrderNumber();
            String partnerId = dto.get("partnerId") != null ? (String) dto.get("partnerId")
                    : posOrders.ensureWalkInCustomer(orgId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.put("orderNumber", orderNumber);
            data.put("orderType", dto.get("orderType") != null ? dto.get("orderType") : "takeaway");
            data.put("status", "confirmed");
            data.put("transactionKind", dto.get("transactionKind") != null ? dto.get("transactionKind") : "sale");
            data.put("partnerId", partnerId);
            data.put("branchId", scopes.documentBranch((String) dto.get("branchId")));
            data.put("guestCount", dto.get("guestCount") == null ? null : PosSql.intOf(dto.get("guestCount")));
            data.put("notes", dto.get("notes"));
            data.put("paymentTermId", dto.get("paymentTermId"));
            data.put("paymentMethod", dto.get("paymentMethod"));
            data.put("openedAt", PosSql.truthy(dto.get("openedAt")) ? PosSql.date((String) dto.get("openedAt")) : null);
            data.put("createdBy", user());
            Map<String, Object> created = db.insert(Order.class, data);
            String id = (String) created.get("id");
            if (!resolved.isEmpty()) {
                writeItems(id, resolved, false, false);
            }
            Map<String, Object> fresh = reload(id);
            if (!Boolean.TRUE.equals(dto.get("creditApproved"))) {
                assertCredit(fresh);
            }
            events.publish(DomainEvents.POS_ORDER_CREATED, Obj.of("organizationId", orgId, "orderId", id,
                    "orderNumber", orderNumber));
            audit.recordInTx(AuditInput.of("Order", id, "create", null, Obj.of("orderNumber", orderNumber,
                    "lineCount", resolved.size())));
            return decorate(fresh);
        });
        if (!resolved.isEmpty()) {
            approvals.checkOrRequestApprovalOfAmount(APPROVAL_ENTITY, (String) order.get("id"), approvalSnapshot(order));
        }
        return order;
    }

    public Map<String, Object> updateHeader(String orderId, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        if (existing == null || !scopes.scope().allowsBranch((String) existing.get("branchId"))) {
            throw ApiException.notFound("Order not found");
        }
        assertEditable(existing);
        Map<String, Object> checked = new LinkedHashMap<>(existing);
        for (String k : List.of("partnerId", "paymentMethod")) {
            if (dto.containsKey(k)) {
                checked.put(k, dto.get(k));
            }
        }
        assertCredit(checked);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderType", dto.get("orderType") != null ? dto.get("orderType") : existing.get("orderType"));
        for (String k : List.of("partnerId", "guestCount", "notes", "paymentTermId", "paymentMethod")) {
            Object v = dto.containsKey(k) ? dto.get(k) : existing.get(k);
            data.put(k, "guestCount".equals(k) && v != null ? PosSql.intOf(v) : v);
        }
        if (PosSql.truthy(dto.get("openedAt"))) {
            data.put("openedAt", PosSql.date((String) dto.get("openedAt")));
        }
        data.put("updatedBy", user());
        Map<String, Object> updated = db.update(Order.class, data, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        audit.record(AuditInput.of("Order", orderId, "update", null, new LinkedHashMap<>(dto)));
        return decorate(updated);
    }

    /** Replace the whole item set (auto-save semantics). */
    public Map<String, Object> saveItems(String orderId, Map<String, Object> dto) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
            if (order == null || !scopes.scope().allowsBranch((String) order.get("branchId"))) {
                throw ApiException.notFound("Order not found");
            }
            assertEditable(order);
            assertVersion(order, dto.get("expectedVersion"));
            List<Line> lines = new ArrayList<>();
            for (Map<String, Object> l : maps(dto.get("lines"))) {
                lines.add(Line.of(l));
            }
            writeItems(orderId, lines, true, false);
            Map<String, Object> fresh = reload(orderId);
            assertCredit(fresh);
            events.publish(DomainEvents.POS_ORDER_UPDATED, Obj.of("organizationId", orgId, "orderId", orderId,
                    "version", fresh.get("version")));
            audit.recordInTx(AuditInput.of("Order", orderId, "update", null, Obj.of("kind", "save_items",
                    "lineCount", PosSql.list(fresh.get("items")).size())));
            return decorate(fresh);
        });
    }

    /** Append lines to the existing item set. */
    public Map<String, Object> addItems(String orderId, Map<String, Object> dto) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
            if (order == null || !scopes.scope().allowsBranch((String) order.get("branchId"))) {
                throw ApiException.notFound("Order not found");
            }
            assertEditable(order);
            List<Line> lines = new ArrayList<>();
            for (Map<String, Object> l : maps(dto.get("lines"))) {
                lines.add(Line.of(l));
            }
            writeItems(orderId, lines, false, true);
            Map<String, Object> fresh = reload(orderId);
            assertCredit(fresh);
            events.publish(DomainEvents.POS_ORDER_UPDATED, Obj.of("organizationId", orgId, "orderId", orderId,
                    "version", fresh.get("version")));
            return decorate(fresh);
        });
    }

    /** Cancel the whole order (only while un-billed). */
    public Map<String, Object> cancelOrder(String orderId, String reason) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> order = lockOrder(orderId);
            if (order.get("invoiceId") != null) {
                throw ApiException.conflict("Order already billed — refund/void the invoice instead");
            }
            if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
                return order;
            }
            workflows.transition("order", orderId, "cancel", Obj.of("reason", reason), order);
            Map<String, Object> updated = db.update(Order.class, Obj.of("cancelReason", reason,
                    "version", PosDb.raw("version + 1")), "id = ? AND \"organizationId\" = ?", orderId, orgId);
            Map<String, Object> payload = Obj.of("organizationId", orgId, "orderId", orderId);
            if (reason != null) {
                payload.put("reason", reason);
            }
            events.publish(DomainEvents.POS_ORDER_CANCELLED, payload);
            return decorate(updated);
        });
    }

    /** Reopen a cancelled order (un-billed only). */
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
            return decorate(updated);
        });
    }

    /** Bill the order through the shared spine: deduct stock, post AR. */
    public Map<String, Object> generateInvoice(String orderId, Map<String, Object> dto) {
        if (scopes.scope().restricted()) {
            getOrder(orderId);
        }
        assertApproved(orderId);
        return billing.generateInvoice(orderId, dto);
    }

    // ─── approval ───

    private static Map<String, Object> approvalSnapshot(Map<String, Object> order) {
        return Obj.of("amount", order.get("totalAmount"), "orderNumber", order.get("orderNumber"), "partnerId",
                order.get("partnerId"), "branchId", order.get("branchId"), "createdBy", order.get("createdBy"));
    }

    /** Stops billing while approval is pending. An order billing will refuse anyway gets its own error from there. */
    private void assertApproved(String orderId) {
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (order == null || order.get("invoiceId") != null || "cancelled".equals(order.get("status"))
                || "closed".equals(order.get("status"))) {
            return;
        }
        Map<String, Object> gate = approvals.checkOrRequestApprovalOfAmount(APPROVAL_ENTITY, orderId,
                approvalSnapshot(order));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before invoicing " + order.get("orderNumber")
                    + ". Pending approval request " + gate.get("requestId") + ".");
        }
    }

    /** The latest approval request of an order with its decisions; {@code null} when none was needed. */
    public Map<String, Object> approval(String orderId) {
        requireOrder(orderId);
        return approvals.latestRequest(APPROVAL_ENTITY, orderId);
    }

    /** Raise the approval for the order as it stands now, for example after editing its lines. */
    public Map<String, Object> requestApproval(String orderId) {
        Map<String, Object> order = requireOrder(orderId);
        assertEditable(order);
        approvals.checkOrRequestApprovalOfAmount(APPROVAL_ENTITY, orderId, approvalSnapshot(order));
        return approvals.latestRequest(APPROVAL_ENTITY, orderId);
    }

    private Map<String, Object> requireOrder(String orderId) {
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        return order;
    }

    // ─── internals ───

    /** A cart line ready for the tax engine (menu OR product source); {@code unitPrice} null = engine resolves. */
    private static final class Line {
        String productId;
        String menuItemId;
        String description;
        BigDecimal quantity;
        BigDecimal unitPrice;
        String taxId;
        BigDecimal discountPercent;
        String discountType;
        BigDecimal discountAmount;
        String discountReason;
        String note;
        Boolean taxInclusive;
        boolean carried;
        String punchedById;
        String punchedByName;
        /** UOM-1: the unit typed on the line, the quantity and price as typed, and sales units per typed unit. */
        String uomId;
        BigDecimal entryQuantity;
        BigDecimal entryUnitPrice;
        BigDecimal entryFactor;

        static Line of(Map<String, Object> l) {
            Line r = new Line();
            r.productId = (String) l.get("productId");
            r.menuItemId = (String) l.get("menuItemId");
            r.description = l.get("description") == null ? "" : (String) l.get("description");
            r.quantity = PosSql.decOrNull(l.get("quantity"));
            r.unitPrice = PosSql.decOrNull(l.get("unitPrice"));
            r.taxId = (String) l.get("taxId");
            r.discountPercent = l.get("discountPercent") == null ? BigDecimal.ZERO : PosSql.dec(l.get("discountPercent"));
            r.discountType = (String) l.get("discountType");
            r.discountAmount = PosSql.decOrNull(l.get("discountAmount"));
            r.discountReason = (String) l.get("discountReason");
            r.note = (String) l.get("note");
            r.taxInclusive = (Boolean) l.get("taxInclusive");
            r.uomId = (String) l.get("uomId");
            return r;
        }

        static Line ofItem(Map<String, Object> it) {
            Line r = new Line();
            r.productId = (String) it.get("productId");
            r.menuItemId = (String) it.get("menuItemId");
            r.description = (String) it.get("description");
            r.quantity = PosSql.viaJs(PosSql.dec(it.get("quantity")));
            r.unitPrice = PosSql.viaJs(PosSql.dec(it.get("unitPrice")));
            r.taxId = (String) it.get("taxId");
            r.discountPercent = PosSql.viaJs(PosSql.dec(it.get("discountPercent")));
            r.discountType = (String) it.get("discountType");
            r.discountAmount = PosSql.viaJs(PosSql.dec(it.get("discountAmount")));
            r.discountReason = (String) it.get("discountReason");
            r.note = (String) it.get("note");
            r.taxInclusive = (Boolean) it.get("taxInclusive");
            r.carried = true;
            r.punchedById = (String) it.get("punchedById");
            r.punchedByName = (String) it.get("punchedByName");
            return r;
        }

        LineInput input() {
            return new LineInput(productId, menuItemId, null, null, null, description, quantity, unitPrice,
                    discountPercent, discountType, discountAmount, discountReason, "manual", taxId, null, taxInclusive);
        }
    }

    /** ORD-YYYYMMDD-NNNNNN on the shared sequence key — unique across POS orders. */
    private String nextOrderNumber() {
        String ymd = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString().replace("-", "");
        return sequence.next("order:" + ymd, "ORD-" + ymd + "-", 6);
    }

    /** Fill missing name / price / tax of bare loose refs from the catalog before the tax engine runs. */
    private void resolveCatalog(List<Line> lines) {
        String orgId = org();
        LinkedHashSet<String> menuIds = new LinkedHashSet<>();
        LinkedHashSet<String> productIds = new LinkedHashSet<>();
        for (Line l : lines) {
            if (PosSql.truthy(l.menuItemId)) {
                menuIds.add(l.menuItemId);
            }
            if (PosSql.truthy(l.productId)) {
                productIds.add(l.productId);
            }
        }
        if (!menuIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(menuIds);
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT id, name, \"basePrice\", \"taxId\" FROM"
                    + " \"MenuItem\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND id IN ("
                    + PosSql.marks(menuIds.size()) + ")").params(params))) {
                byId.put((String) m.get("id"), m);
            }
            for (Line l : lines) {
                Map<String, Object> m = l.menuItemId == null ? null : byId.get(l.menuItemId);
                if (m == null) {
                    continue;
                }
                if (!PosSql.truthy(l.description)) {
                    l.description = m.get("name") == null ? "" : (String) m.get("name");
                }
                if (l.unitPrice == null) {
                    l.unitPrice = m.get("basePrice") == null ? BigDecimal.ZERO : PosSql.viaJs(PosSql.dec(m.get("basePrice")));
                }
                if (!PosSql.truthy(l.taxId) && m.get("taxId") != null) {
                    l.taxId = (String) m.get("taxId");
                }
            }
        }
        if (!productIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(productIds);
            Map<String, String> names = new HashMap<>();
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Product\" WHERE \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND id IN (" + PosSql.marks(productIds.size()) + ")").params(params))) {
                names.put((String) p.get("id"), (String) p.get("name"));
            }
            for (Line l : lines) {
                if (l.productId != null && names.containsKey(l.productId) && !PosSql.truthy(l.description)) {
                    String n = names.get(l.productId);
                    l.description = n == null ? "" : n;
                }
            }
        }
    }

    /** SALES-3: a product line that came with no price takes the customer's price list, in the line's unit. */
    private void applyPriceLists(String orderId, List<Line> lines) {
        if (lines.stream().noneMatch(l -> !l.carried && l.unitPrice == null && PosSql.truthy(l.productId))) {
            return;
        }
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        for (Line l : lines) {
            if (l.carried || l.unitPrice != null || !PosSql.truthy(l.productId)) {
                continue;
            }
            ListPrice p = priceLists.resolve(new PriceQuery((String) order.get("partnerId"), l.productId,
                    l.quantity, l.uomId, (String) order.get("branchId"), null, null));
            if (p != null) {
                l.unitPrice = p.unitPrice();
                if (l.discountPercent.signum() == 0) {
                    l.discountPercent = p.discountPercent();
                }
            }
        }
    }

    /**
     * UOM-1: a line in another unit of the product is stored in the product's sales unit, because billing, stock and
     * delivery all read {@code OrderItem.quantity} that way. Price and quantity convert so the line value is the same;
     * what was typed is kept in {@code OrderLineEntryUnit}. A line in the sales unit passes through untouched.
     */
    private void applyEntryUnits(List<Line> lines) {
        for (Line l : lines) {
            if (l.carried || !PosSql.truthy(l.uomId) || !PosSql.truthy(l.productId) || l.quantity == null) {
                continue;
            }
            LineUnit entered = units.resolveLineUnit(l.productId, l.uomId, UnitRole.SALES);
            LineUnit sold = units.resolveLineUnit(l.productId, null, UnitRole.SALES);
            if (entered.factor() == null || sold.factor() == null || entered.uomId().equals(sold.uomId())) {
                l.uomId = null;
                continue;
            }
            BigDecimal ratio = entered.factor().divide(sold.factor(), UomConversionService.DECIMAL_JS);
            BigDecimal price = l.unitPrice;
            if (price == null) {
                BigDecimal list = jdbc.sql("SELECT \"salesPrice\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(l.productId, org()).query(BigDecimal.class).optional().orElse(null);
                price = units.defaultUnitPrice(l.productId, entered, UnitRole.SALES, list);
            }
            l.entryQuantity = l.quantity;
            l.entryUnitPrice = price == null ? BigDecimal.ZERO : price;
            l.entryFactor = ratio;
            l.quantity = l.quantity.multiply(ratio, UomConversionService.DECIMAL_JS).setScale(6, RoundingMode.HALF_UP);
            if (price != null) {
                l.unitPrice = price.divide(ratio, 6, RoundingMode.HALF_UP);
            }
        }
    }

    /** Who writes these lines (denormalized name, like {@code PosOrdersService.actorStamp}). */
    private String[] actorStamp() {
        String userId = user();
        if (userId == null) {
            return new String[] {null, null};
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
        return new String[] {userId, name};
    }

    /** Persist lines as OrderItems (replace/append rebuild the whole set) and recompute the header totals. */
    private void writeItems(String orderId, List<Line> resolved, boolean replace, boolean append) {
        String orgId = org();
        TableStatus.lockFloorShared(jdbc, orgId);
        List<Line> all = new ArrayList<>();
        if (append) {
            Map<Integer, Map<String, Object>> typed = new HashMap<>();
            for (Map<String, Object> e : SqlRows.list(jdbc.sql("SELECT * FROM \"OrderLineEntryUnit\" WHERE \"orderId\" = ?"
                    + " AND \"organizationId\" = ?").params(orderId, orgId))) {
                typed.put(PosSql.intOf(e.get("lineNumber")), e);
            }
            for (Map<String, Object> it : db.list(OrderItem.class, "\"orderId\" = ? AND \"organizationId\" = ?"
                    + " AND cancelled = false ORDER BY \"lineNumber\" ASC", orderId, orgId)) {
                Line carried = Line.ofItem(it);
                Map<String, Object> e = typed.get(PosSql.intOf(it.get("lineNumber")));
                if (e != null) {
                    carried.uomId = (String) e.get("uomId");
                    carried.entryQuantity = PosSql.dec(e.get("quantity"));
                    carried.entryUnitPrice = PosSql.dec(e.get("unitPrice"));
                    carried.entryFactor = PosSql.dec(e.get("factor"));
                }
                all.add(carried);
            }
        }
        all.addAll(resolved);
        resolveCatalog(all);
        applyPriceLists(orderId, all);
        applyEntryUnits(all);
        List<LineInput> inputs = new ArrayList<>();
        all.forEach(l -> inputs.add(l.input()));
        Totals totals = builder.prepareLines(inputs);
        if (replace || append) {
            jdbc.sql("DELETE FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ?").params(orderId, orgId)
                    .update();
            jdbc.sql("DELETE FROM \"OrderLineEntryUnit\" WHERE \"orderId\" = ? AND \"organizationId\" = ?")
                    .params(orderId, orgId).update();
        }
        String[] stamp = actorStamp();
        for (int i = 0; i < totals.prepared().size(); i++) {
            PreparedLine p = totals.prepared().get(i);
            Line src = i < all.size() ? all.get(i) : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.put("orderId", orderId);
            data.put("productId", p.productId());
            data.put("menuItemId", p.menuItemId());
            data.put("description", p.description());
            data.put("quantity", p.quantity());
            data.put("unitPrice", p.unitPrice());
            data.put("discountPercent", p.discountPercent());
            data.put("discountType", p.discountType() == null ? "percentage" : p.discountType());
            data.put("discountAmount", p.discountAmount() == null ? BigDecimal.ZERO : p.discountAmount());
            data.put("discountReason", p.discountReason());
            data.put("taxId", p.taxId());
            data.put("taxInclusive", p.taxInclusive());
            data.put("note", src == null ? null : src.note);
            data.put("lineNumber", p.lineNumber());
            boolean carried = src != null && src.carried;
            data.put("punchedById", carried && src.punchedById != null ? src.punchedById : stamp[0]);
            data.put("punchedByName", carried && src.punchedByName != null ? src.punchedByName : stamp[1]);
            db.insert(OrderItem.class, data);
            if (src != null && src.entryFactor != null) {
                jdbc.sql("INSERT INTO \"OrderLineEntryUnit\" (id, \"organizationId\", \"orderId\", \"lineNumber\","
                        + " \"uomId\", quantity, \"unitPrice\", factor) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), orgId, orderId, p.lineNumber(), src.uomId,
                                src.entryQuantity, src.entryUnitPrice, src.entryFactor)
                        .update();
            }
        }
        db.update(Order.class, Obj.of("subtotal", totals.subtotal(), "discountTotal", totals.discountTotal(),
                "taxAmount", totals.taxAmount(), "totalAmount", totals.total(), "version", PosDb.raw("version + 1")),
                "id = ? AND \"organizationId\" = ?", orderId, orgId);
    }

    private void withItemsAndInvoice(Map<String, Object> order) {
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", order.get("id"));
        Map<Integer, Map<String, Object>> typed = new HashMap<>();
        for (Map<String, Object> e : SqlRows.list(jdbc.sql("SELECT e.\"lineNumber\", e.\"uomId\", e.quantity,"
                + " e.\"unitPrice\", u.code FROM \"OrderLineEntryUnit\" e JOIN \"UnitOfMeasure\" u ON u.id = e.\"uomId\""
                + " WHERE e.\"orderId\" = ? AND e.\"organizationId\" = ?").params(order.get("id"), org()))) {
            typed.put(PosSql.intOf(e.get("lineNumber")), e);
        }
        for (Map<String, Object> it : items) {
            Map<String, Object> e = typed.get(PosSql.intOf(it.get("lineNumber")));
            if (e != null) {
                it.put("entryUomId", e.get("uomId"));
                it.put("entryUomCode", e.get("code"));
                it.put("entryQuantity", PosSql.js(e.get("quantity")));
                it.put("entryUnitPrice", PosSql.js(e.get("unitPrice")));
            }
            it.put("modifiers", SqlRows.list(OrderItemModifier.class, jdbc.sql("SELECT * FROM \"OrderItemModifier\""
                    + " WHERE \"orderItemId\" = ? ORDER BY \"createdAt\", id").param(it.get("id"))));
        }
        order.put("items", items);
        order.put("invoice", order.get("invoiceId") == null ? null
                : db.first(Invoice.class, "id = ?", order.get("invoiceId")));
    }

    private Map<String, Object> reload(String orderId) {
        Map<String, Object> o = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, org());
        if (o != null) {
            withItemsAndInvoice(o);
        }
        return o;
    }

    private Map<String, Object> lockOrder(String orderId) {
        String orgId = org();
        jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, orgId)
                .query().listOfRows();
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        if (order == null || !scopes.scope().allowsBranch((String) order.get("branchId"))) {
            throw ApiException.notFound("Order not found");
        }
        return order;
    }

    /** SALES-1: this order's total on top of everything else the customer owes or has on order. */
    private void assertCredit(Map<String, Object> order) {
        if (!"sale".equals(String.valueOf(order.get("transactionKind")))) {
            return;
        }
        Object method = order.get("paymentMethod");
        credit.assertCanSell("pos_credit_sale", (String) order.get("partnerId"), PosSql.dec(order.get("totalAmount")),
                "order", (String) order.get("id"), method == null ? null : String.valueOf(method),
                (String) order.get("branchId"));
    }

    private static void assertEditable(Map<String, Object> order) {
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

    private static void assertVersion(Map<String, Object> order, Object expected) {
        if (expected == null) {
            return;
        }
        int version = PosSql.intOf(order.get("version"));
        if (version != PosSql.num(expected)) {
            throw ApiException.conflict("Order was modified by someone else (expected v" + PosSql.jsString(expected)
                    + ", found v" + version + "). Reload and retry.");
        }
    }

    private Map<String, String> partnerNames(List<String> ids) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        ids.stream().filter(PosSql::truthy).forEach(unique::add);
        Map<String, String> out = new HashMap<>();
        if (unique.isEmpty()) {
            return out;
        }
        List<Object> params = new ArrayList<>(List.of(org()));
        params.addAll(unique);
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND id IN (" + PosSql.marks(unique.size()) + ")").params(params))) {
            out.put((String) p.get("id"), (String) p.get("name"));
        }
        return out;
    }

    /**
     * Attach partner/waiter/table names and render decimals as JS numbers. Nest resolves all three ids against the
     * partner table, so waiter/table names are normally {@code null} — kept for parity.
     */
    private Map<String, Object> decorate(Map<String, Object> order) {
        Map<String, Object> o = new LinkedHashMap<>(order);
        List<String> ids = new ArrayList<>();
        for (String k : List.of("partnerId", "waiterId", "tableId")) {
            if (PosSql.truthy(o.get(k))) {
                ids.add((String) o.get(k));
            }
        }
        Map<String, String> nameById = partnerNames(ids);
        o.put("partnerName", o.get("partnerId") == null ? null : nameById.get(o.get("partnerId")));
        o.put("waiterName", o.get("waiterId") == null ? null : nameById.get(o.get("waiterId")));
        o.put("tableName", o.get("tableId") == null ? null : nameById.get(o.get("tableId")));
        for (String k : List.of("subtotal", "discountTotal", "taxAmount", "totalAmount")) {
            o.put(k, PosSql.js(o.get(k)));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object x : PosSql.list(o.get("items"))) {
            Map<String, Object> it = new LinkedHashMap<>(PosSql.map(x));
            for (String k : List.of("quantity", "unitPrice", "discountPercent", "discountAmount")) {
                it.put(k, PosSql.js(it.get(k)));
            }
            items.add(it);
        }
        o.put("items", items);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : PosSql.list(v)) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }
}
