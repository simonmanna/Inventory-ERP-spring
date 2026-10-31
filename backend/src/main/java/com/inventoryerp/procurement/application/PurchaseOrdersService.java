package com.inventoryerp.procurement.application;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.defined;
import static com.inventoryerp.procurement.application.ProcurementSql.num;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.application.ProcurementBudgetService.Doc;
import com.inventoryerp.procurement.application.ProcurementBudgetService.DocLine;
import com.inventoryerp.procurement.domain.GoodsReceiptLine;
import com.inventoryerp.procurement.domain.GoodsReceiptNote;
import com.inventoryerp.procurement.domain.PurchaseOrder;
import com.inventoryerp.procurement.domain.PurchaseOrderLine;
import com.inventoryerp.procurement.domain.PurchasePayment;
import com.inventoryerp.procurement.domain.PurchaseRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase orders (port of {@code purchase-orders.service.ts}): register (approval gate → draft or active), receive
 * against the order (GRN + stock + GRNI voucher + cash settlement in one transaction), pay credit orders, update,
 * cancel, delete and activate. {@link #applyReceiptToPO} is the single writer of PO receipt status.
 */
@Service
public class PurchaseOrdersService {

    /** {@code CreatePODto}. */
    public record CreateInput(String partnerId, String branchId, String description, String expectedDeliveryDate,
            String currencyCode, BigDecimal exchangeRate, String paymentType, String notes, String terms,
            String warehouseId, String requestId, List<LineInput> lines, String departmentId) {}

    /** {@code POLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
            BigDecimal unitPrice, String taxId, BigDecimal taxRate, String notes) {}

    /** {@code ReceivePODto}. */
    public record ReceiveInput(String warehouseId, String receivedAt, String notes, List<ReceiveLineInput> lines) {}

    /** {@code ReceiveLineDto}. */
    public record ReceiveLineInput(String purchaseOrderLineId, String productId, String description,
            BigDecimal quantity, BigDecimal unitCost, String batchNumber, String expiryDate, String notes) {}

    /** {@code PayPODto}. */
    public record PayInput(BigDecimal amount, String paidAt, String reference, String notes, String method,
            String cashRegisterId) {}

    /** {@code UpdatePODto}. */
    public record UpdateInput(String description, String expectedDeliveryDate, String notes, String terms) {}

    /** A receipt line applied to a PO ({@link #applyReceiptToPO}). */
    record ReceiptLine(String purchaseOrderLineId, String productId, double quantity, String description) {}

    /** A receive line after PO-line resolution ({@code _resolvedLineId} / {@code _unitCost}). */
    private record Resolved(ReceiveLineInput line, String resolvedLineId, BigDecimal unitCost, LineUnit unit) {}

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final CashSessionService cashSession;
    private final ProcurementBudgetService budgets;
    private final ProcurementDelegationService delegations;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    private final UomConversionService units;

    public PurchaseOrdersService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ApprovalsService approvals, StockService stock, StockPostingService stockPosting,
            CashSessionService cashSession, PlatformTransactionManager txm, UomConversionService units,
            ProcurementBudgetService budgets, ProcurementDelegationService delegations) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.delegations = delegations;
        this.units = units;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.approvals = approvals;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.cashSession = cashSession;
        this.tx = new TransactionTemplate(txm);
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static int year() {
        return Instant.now().atOffset(ZoneOffset.UTC).getYear();
    }

    private static Instant nowMs() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // ── Step 1: Register Purchase ──────────────────────────────────────────

    /** Register a PO: draft pending approval when a purchase_order workflow applies, else active. */
    public Map<String, Object> create(CreateInput dto) {
        String orgId = org();
        if (dto.lines() == null || dto.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        if (dto.warehouseId() == null || dto.warehouseId().isEmpty()) {
            throw ApiException.badRequest("Warehouse is required");
        }
        if (!exists("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?",
                dto.warehouseId(), orgId)) {
            throw ApiException.notFound("Warehouse not found");
        }
        if (dto.partnerId() != null && !dto.partnerId().isEmpty()
                && !exists("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?", dto.partnerId(),
                        orgId)) {
            throw ApiException.notFound("Supplier not found");
        }
        int year = year();
        String orderNumber = sequence.next("po:" + year, "PO-" + year + "-", 5);

        double subtotal = 0;
        double taxAmount = 0;
        for (LineInput ln : dto.lines()) {
            double lineSubtotal = ln.quantity().doubleValue() * ln.unitPrice().doubleValue();
            double lineTax = lineSubtotal * ((ln.taxRate() == null ? 0 : ln.taxRate().doubleValue()) / 100);
            subtotal += lineSubtotal;
            taxAmount += lineTax;
        }
        double total = subtotal + taxAmount;
        double sub = subtotal;
        double taxes = taxAmount;

        // Line unit + conversion snapshot (ADR-S003): validated up front, frozen on the line.
        List<LineUnit> lineUnits = new ArrayList<>();
        for (LineInput ln : dto.lines()) {
            lineUnits.add(units.resolveLineUnit(ln.productId(), ln.unitOfMeasureId(), UnitRole.PURCHASE));
        }

        // Spend control (PROC-3): both in base currency excluding tax. The budget check runs after the insert, under
        // the budget row lock, and before the approval request, so a refused order leaves nothing behind.
        List<DocLine> baseLines = baseLines(dto);
        delegations.enforce(dto.branchId(), baseLines.stream().map(DocLine::amount).reduce(BigDecimal.ZERO,
                BigDecimal::add));

        Map<String, Object> order = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Map<String, Object> created = SqlRows.one(PurchaseOrder.class, jdbc.sql("INSERT INTO \"PurchaseOrder\" (id,"
                            + " \"organizationId\", \"orderNumber\", \"partnerId\", \"branchId\", description,"
                            + " \"orderDate\", \"expectedDeliveryDate\", \"currencyCode\", \"exchangeRate\", subtotal,"
                            + " \"taxAmount\", \"totalAmount\", status, \"paymentType\", \"paymentStatus\", notes, terms,"
                            + " \"requestId\", \"departmentId\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?,"
                            + " ?, ?, ?, " + NOW + ", ?, ?, ?, ?, ?, ?, 'draft', ?::\"PaymentType\","
                            + " ?::\"PaymentStatus\", ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(id, orgId, orderNumber, dto.partnerId(), dto.branchId(), dto.description(),
                            SqlRows.ts(ProcurementSql.date(dto.expectedDeliveryDate())),
                            dto.currencyCode() != null ? dto.currencyCode() : "USD",
                            dto.exchangeRate() != null ? dto.exchangeRate() : BigDecimal.ONE, num(sub), num(taxes),
                            num(total), dto.paymentType() != null ? dto.paymentType() : "cash",
                            "credit".equals(dto.paymentType()) ? "not_paid" : null, dto.notes(), dto.terms(),
                            dto.requestId(), dto.departmentId(), user()));
            List<Map<String, Object>> lines = new ArrayList<>();
            int idx = 0;
            for (LineInput ln : dto.lines()) {
                LineUnit unit = lineUnits.get(idx);
                lines.add(SqlRows.one(PurchaseOrderLine.class, jdbc.sql("INSERT INTO \"PurchaseOrderLine\" (id,"
                                + " \"organizationId\", \"purchaseOrderId\", \"productId\", description, quantity,"
                                + " \"unitOfMeasureId\", \"uomFactor\", \"unitPrice\", \"taxId\", \"taxRate\", subtotal,"
                                + " \"lineNumber\", notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                                unit.uomId(), unit.factor(), ln.unitPrice(), ln.taxId(),
                                ln.taxRate() != null ? ln.taxRate() : BigDecimal.ZERO,
                                num(ln.quantity().doubleValue() * ln.unitPrice().doubleValue()), ++idx, ln.notes())));
            }
            created.put("lines", lines);
            budgets.enforce(new Doc(ProcurementBudgetService.ORDER, id, dto.branchId(), dto.departmentId(), baseLines,
                    dto.requestId()));

            // Approval gate: an active policy keeps the PO in draft; none → auto-approve to active.
            Map<String, Object> approvalResult = newTx.execute(s -> approvals.requestApproval("purchase_order", id,
                    defined("amount", JsNumbers.js(total), "partnerId", dto.partnerId(), "orderNumber", orderNumber,
                            "createdBy", user(), "branchId", dto.branchId(), "departmentId", dto.departmentId()), null));
            if (approvalResult == null) {
                jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'active', \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId).update();
                created.put("status", "active");
            } else {
                audit.recordInTx(AuditInput.of("PurchaseOrder", id, "create", null, Obj.of("orderNumber", orderNumber,
                        "totalAmount", JsNumbers.js(total), "approvalRequestId", approvalResult.get("id"),
                        "status", "draft")));
            }
            return created;
        });

        audit.record(AuditInput.of("PurchaseOrder", (String) order.get("id"), "create", null,
                defined("orderNumber", orderNumber, "totalAmount", JsNumbers.js(total), "paymentType", dto.paymentType())));
        events.publish("purchase_order.created", defined("organizationId", orgId, "orderId", order.get("id"),
                "orderNumber", orderNumber, "partnerId", dto.partnerId()));
        return order;
    }

    /** Each line's value excluding tax, in base currency. */
    private static List<DocLine> baseLines(CreateInput dto) {
        BigDecimal rate = dto.exchangeRate() != null ? dto.exchangeRate() : BigDecimal.ONE;
        return dto.lines().stream().map(l -> new DocLine(l.productId(), l.quantity().multiply(l.unitPrice())
                .multiply(rate).setScale(6, RoundingMode.HALF_UP))).toList();
    }

    private boolean exists(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single() > 0;
    }

    // ── Listing & detail ───────────────────────────────────────────────────

    /** {@code GET /procurement/purchase-orders} filters. */
    public record ListQuery(String status, String paymentType, String paymentStatus, String partnerId, String search,
            int page, int pageSize, String dateFrom, String dateTo) {}

    /** Paged orders (latest order date first) with the supplier's id and name. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(ListQuery q) {
        int page = Math.max(1, q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        if (notBlank(q.status())) {
            where.append(" AND status = ?::\"PurchaseOrderStatus\"");
            params.add(q.status());
        }
        if (notBlank(q.paymentType())) {
            where.append(" AND \"paymentType\" = ?::\"PaymentType\"");
            params.add(q.paymentType());
        }
        if (notBlank(q.paymentStatus())) {
            where.append(" AND \"paymentStatus\" = ?::\"PaymentStatus\"");
            params.add(q.paymentStatus());
        }
        if (notBlank(q.partnerId())) {
            where.append(" AND \"partnerId\" = ?");
            params.add(q.partnerId());
        }
        if (notBlank(q.search())) {
            // Nest builds `contains` filters on the paymentType / paymentStatus / status enums, which Prisma rejects
            // at validation time: every search answers 500. Kept for parity (docs/spec/procurement.md).
            throw new IllegalStateException("Invalid prisma.purchaseOrder.findMany() invocation: enum fields do not"
                    + " support `contains`");
        }
        if (notBlank(q.dateFrom())) {
            where.append(" AND \"orderDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.dateFrom())));
        }
        if (notBlank(q.dateTo())) {
            where.append(" AND \"orderDate\" <= ?");
            params.add(SqlRows.ts(ProcurementSql.endOfLocalDay(EntityPatch.toInstant(q.dateTo()))));
        }
        List<Map<String, Object>> data = SqlRows.list(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\""
                + " WHERE" + where + " ORDER BY \"orderDate\" DESC LIMIT " + pageSize + " OFFSET "
                + (page - 1) * pageSize).params(params));
        for (Map<String, Object> po : data) {
            po.put("partner", SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ?")
                    .param(po.get("partnerId"))));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE" + where).params(params).query(Long.class)
                .single();
        return Obj.of("data", data, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    /** Order with lines, receipts (with lines), payments and the originating request; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (po == null) {
            return null;
        }
        po.put("lines", poLines(id));
        List<Map<String, Object>> receipts = SqlRows.list(GoodsReceiptNote.class, jdbc.sql("SELECT * FROM"
                + " \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ?").param(id));
        for (Map<String, Object> r : receipts) {
            r.put("lines", SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM \"GoodsReceiptLine\" WHERE"
                    + " \"goodsReceiptId\" = ?").param(r.get("id"))));
        }
        po.put("receipts", receipts);
        po.put("payments", SqlRows.list(PurchasePayment.class, jdbc.sql("SELECT * FROM \"PurchasePayment\" WHERE"
                + " \"purchaseOrderId\" = ?").param(id)));
        po.put("request", po.get("requestId") == null ? null : SqlRows.one(PurchaseRequest.class,
                jdbc.sql("SELECT * FROM \"PurchaseRequest\" WHERE id = ?").param(po.get("requestId"))));
        return po;
    }

    private List<Map<String, Object>> poLines(Object purchaseOrderId) {
        return SqlRows.list(PurchaseOrderLine.class, jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                + " \"purchaseOrderId\" = ?").param(purchaseOrderId));
    }

    // ── Step 2: Receive Products ───────────────────────────────────────────

    /**
     * Apply received lines to a PO inside the caller's transaction: hard over-receipt block, bump each PO line's
     * receivedQuantity and recompute the PO status, all under optimistic locks. Shared by {@link #receive} and the
     * standalone goods-receipt post so the two never diverge.
     */
    String applyReceiptToPO(String purchaseOrderId, List<ReceiptLine> lines) {
        String orgId = org();
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(purchaseOrderId, orgId));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        Object status = po.get("status");
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Cannot receive against a cancelled PO");
        }
        if ("received".equals(status)) {
            throw ApiException.badRequest("PO is fully received");
        }
        if (!List.of("active", "partially_received").contains(status)) {
            throw ApiException.badRequest("PO is in status \"" + status + "\" — cannot receive");
        }
        List<Map<String, Object>> poLineRows = poLines(purchaseOrderId);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byProduct = new HashMap<>();
        for (Map<String, Object> l : poLineRows) {
            byId.put((String) l.get("id"), l);
            if (l.get("productId") != null) {
                byProduct.computeIfAbsent((String) l.get("productId"), k -> new ArrayList<>()).add(l);
            }
        }
        // Aggregate by PO line: two receipt lines can map to one PO line, and each line is version-locked once.
        Map<String, Double> addByLine = new LinkedHashMap<>();
        for (ReceiptLine ln : lines) {
            String lineId = resolve(ln, byId, byProduct, po);
            if (lineId == null) {
                continue;
            }
            addByLine.merge(lineId, ln.quantity(), Double::sum);
        }
        // Over-receipt is a hard block (no tolerance) — check every affected line first.
        for (Map.Entry<String, Double> e : addByLine.entrySet()) {
            Map<String, Object> poLine = byId.get(e.getKey());
            double ordered = Money.dec(poLine.get("quantity")).doubleValue();
            double already = Money.dec(poLine.get("receivedQuantity")).doubleValue();
            double adding = e.getValue();
            if (already + adding > ordered) {
                throw ApiException.badRequest("Over-receiving PO line \"" + poLine.get("description") + "\": ordered "
                        + JsNumbers.toJsString(ordered) + ", already received " + JsNumbers.toJsString(already)
                        + ", trying to receive " + JsNumbers.toJsString(adding));
            }
        }
        for (Map.Entry<String, Double> e : addByLine.entrySet()) {
            Map<String, Object> poLine = byId.get(e.getKey());
            int updated = jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"receivedQuantity\" = \"receivedQuantity\" + ?,"
                            + " version = version + 1 WHERE id = ? AND version = ? AND \"organizationId\" = ?")
                    .params(num(e.getValue()), e.getKey(), poLine.get("version"), orgId).update();
            if (updated == 0) {
                throw ApiException.badRequest("PO line \"" + poLine.get("description")
                        + "\" was modified by another user. Please refresh and try again.");
            }
        }
        boolean fullyReceived = true;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT quantity, \"receivedQuantity\" FROM"
                + " \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ?")
                .params(purchaseOrderId, orgId))) {
            if (!(Money.dec(l.get("receivedQuantity")).doubleValue() >= Money.dec(l.get("quantity")).doubleValue())) {
                fullyReceived = false;
            }
        }
        String newStatus = fullyReceived ? "received" : "partially_received";
        int updatedPo = jdbc.sql("UPDATE \"PurchaseOrder\" SET status = ?::\"PurchaseOrderStatus\", version = version + 1,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND version = ?"
                        + " AND \"deletedAt\" IS NULL")
                .params(newStatus, purchaseOrderId, orgId, po.get("version")).update();
        if (updatedPo == 0) {
            throw ApiException.badRequest("Purchase order was modified by another user. Please refresh and try again.");
        }
        return newStatus;
    }

    /** Bind a receipt line to exactly one PO line (explicit id, else a product on a single PO line). */
    private static String resolve(ReceiptLine ln, Map<String, Map<String, Object>> byId,
            Map<String, List<Map<String, Object>>> byProduct, Map<String, Object> po) {
        if (ln.purchaseOrderLineId() == null && ln.productId() == null) {
            return null;
        }
        if (ln.purchaseOrderLineId() != null) {
            if (!byId.containsKey(ln.purchaseOrderLineId())) {
                throw ApiException.badRequest("PO line " + ln.purchaseOrderLineId() + " not found");
            }
            return ln.purchaseOrderLineId();
        }
        List<Map<String, Object>> candidates = byProduct.getOrDefault(ln.productId(), List.of());
        if (candidates.size() == 1) {
            return (String) candidates.getFirst().get("id");
        }
        if (candidates.size() > 1) {
            throw ApiException.badRequest("Receipt line \"" + (ln.description() != null ? ln.description() : ln.productId())
                    + "\" matches " + candidates.size() + " PO lines — specify purchaseOrderLineId");
        }
        throw ApiException.badRequest("Receipt line \"" + (ln.description() != null ? ln.description()
                : ln.productId() != null ? ln.productId() : "unknown") + "\" is not on PO " + po.get("orderNumber"));
    }

    /** Insert the draft GRN (+ lines) shared by the immediate-post and approval-draft paths. */
    private Map<String, Object> insertGrnDraft(String grnId, String orgId, String receiptNumber, Map<String, Object> po,
            ReceiveInput dto, List<Resolved> resolved) {
        Map<String, Object> grn = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("INSERT INTO \"GoodsReceiptNote\" (id,"
                        + " \"organizationId\", \"receiptNumber\", \"purchaseOrderId\", \"partnerId\", \"branchId\","
                        + " \"warehouseId\", \"receivedAt\", status, notes, \"createdBy\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, " + (dto.receivedAt() != null ? "?" : NOW) + ", 'draft', ?, ?, "
                        + NOW + ", " + NOW + ") RETURNING *")
                .params(grnParams(grnId, orgId, receiptNumber, po, dto)));
        List<Map<String, Object>> lines = new ArrayList<>();
        int idx = 0;
        for (Resolved r : resolved) {
            ReceiveLineInput rln = r.line();
            lines.add(SqlRows.one(GoodsReceiptLine.class, jdbc.sql("INSERT INTO \"GoodsReceiptLine\" (id,"
                            + " \"organizationId\", \"goodsReceiptId\", \"purchaseOrderLineId\", \"productId\", description,"
                            + " quantity, \"unitCost\", \"batchNumber\", \"expiryDate\", notes, \"lineNumber\", \"uomId\","
                            + " \"uomFactor\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                    .params(UUID.randomUUID().toString(), orgId, grnId, r.resolvedLineId(), rln.productId(),
                            rln.description(), rln.quantity(), r.unitCost() != null ? r.unitCost() : BigDecimal.ZERO,
                            rln.batchNumber(), SqlRows.ts(ProcurementSql.date(rln.expiryDate())), rln.notes(), ++idx,
                            r.unit().uomId(), r.unit().factor())));
        }
        grn.put("lines", lines);
        return grn;
    }

    private Object[] grnParams(String grnId, String orgId, String receiptNumber, Map<String, Object> po,
            ReceiveInput dto) {
        List<Object> p = new ArrayList<>(List.of(grnId, orgId, receiptNumber, po.get("id")));
        p.add(po.get("partnerId"));
        p.add(po.get("branchId"));
        p.add(dto.warehouseId());
        if (dto.receivedAt() != null) {
            p.add(SqlRows.ts(ProcurementSql.date(dto.receivedAt())));
        }
        p.add(dto.notes());
        p.add(user());
        return p.toArray();
    }

    /**
     * Cash purchase: settle exactly what one receipt vouchered to AP (Dr AP / Cr Cash) and record the drawer
     * pay-out. Shared by {@link #receive} and the goods-receipt post. Runs in the caller's transaction.
     */
    void settleCashReceipt(String purchaseOrderId, BigDecimal settleAmount, String grnId, String receiptNumber,
            Instant date) {
        String orgId = org();
        if (!(settleAmount.signum() > 0)) {
            return;
        }
        jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(purchaseOrderId, orgId).query().listOfRows();
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(purchaseOrderId, orgId));
        if (po == null || !"cash".equals(po.get("paymentType"))) {
            return;
        }
        BigDecimal newTotalPaid = Money.dec(po.get("totalPaid")).add(settleAmount);
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"totalPaid\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\", \"updatedAt\" = "
                        + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(newTotalPaid, newTotalPaid.compareTo(Money.dec(po.get("totalAmount"))) >= 0 ? "paid" : "partial",
                        purchaseOrderId, orgId)
                .update();
        jdbc.sql("INSERT INTO \"PurchasePayment\" (id, \"organizationId\", \"purchaseOrderId\", amount, \"paidAt\","
                        + " \"paidById\", reference, \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, " + NOW + ", ?, ?, "
                        + NOW + ", " + NOW + ")")
                .params(UUID.randomUUID().toString(), orgId, purchaseOrderId, settleAmount, user(),
                        "auto:GRN " + receiptNumber)
                .update();
        // GL: Dr AP / Cr Cash — relieve the payable the voucher just raised.
        Map<String, Object> session = cashSession.findOpen(null);
        if (session == null) {
            throw ApiException.badRequest("Select an open cashier register for this cash purchase");
        }
        Map<String, Object> paymentJournal = stockPosting.postPurchasePayment((String) session.get("id"),
                (String) po.get("partnerId"), settleAmount, "cash", date, "purchase_order", purchaseOrderId,
                "Cash purchase " + po.get("orderNumber") + " · GRN " + receiptNumber,
                "purchase_payment:goods_receipt:" + grnId);
        // Drawer artifact: the till's expected cash reflects the withdrawal. GL already done.
        cashSession.recordExternalPayOut((String) session.get("id"), settleAmount,
                "Cash purchase PO " + po.get("orderNumber"), (String) paymentJournal.get("id"));
    }

    /** Receive products against an active / partially received PO. */
    public Map<String, Object> receive(String id, ReceiveInput dto) {
        String orgId = org();
        if (dto.warehouseId() == null || dto.warehouseId().isEmpty()) {
            throw ApiException.badRequest("Warehouse is required for receiving stock");
        }
        if (dto.lines() == null || dto.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        if (!exists("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?",
                dto.warehouseId(), orgId)) {
            throw ApiException.notFound("Warehouse not found");
        }
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        Object status = po.get("status");
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Cannot receive against a cancelled PO");
        }
        if ("received".equals(status)) {
            throw ApiException.badRequest("PO is fully received");
        }
        if (!List.of("active", "partially_received").contains(status)) {
            throw ApiException.badRequest("PO is in status \"" + status + "\" — cannot receive");
        }
        Map<String, Map<String, Object>> poLines = new LinkedHashMap<>();
        Map<String, Map<String, Object>> poLinesByProduct = new HashMap<>();
        for (Map<String, Object> l : poLines(id)) {
            poLines.put((String) l.get("id"), l);
            if (l.get("productId") != null) {
                poLinesByProduct.put((String) l.get("productId"), l); // Map(entries): the last line wins
            }
        }
        // `_unitCost` falls back to the ordered price: receiving at 0 would never capitalise the inventory.
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiveLineInput rln : dto.lines()) {
            String lineId = null;
            if (rln.purchaseOrderLineId() != null) {
                if (!poLines.containsKey(rln.purchaseOrderLineId())) {
                    throw ApiException.badRequest("PO line " + rln.purchaseOrderLineId() + " not found");
                }
                lineId = rln.purchaseOrderLineId();
            } else if (rln.productId() != null && poLinesByProduct.containsKey(rln.productId())) {
                lineId = (String) poLinesByProduct.get(rln.productId()).get("id");
            }
            Map<String, Object> poLine = lineId == null ? null : poLines.get(lineId);
            BigDecimal unitCost = rln.unitCost() != null ? rln.unitCost()
                    : poLine != null ? num(Money.dec(poLine.get("unitPrice")).doubleValue()) : null;
            resolved.add(new Resolved(rln, lineId, unitCost, receiptUnit(poLine, rln.productId(), null)));
        }

        int year = year();
        String receiptNumber = sequence.next("grn:" + year, "GRN-" + year + "-", 5);
        String grnId = UUID.randomUUID().toString();
        BigDecimal amount = Money.ZERO;
        for (Resolved r : resolved) {
            amount = amount.add(r.line().quantity().multiply(r.unitCost() != null ? r.unitCost() : Money.ZERO));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("amount", JsNumbers.js(amount));
        snapshot.put("receiptNumber", receiptNumber);
        snapshot.put("partnerId", po.get("partnerId"));
        snapshot.put("purchaseOrderId", id);
        snapshot.put("createdBy", user());
        Map<String, Object> gate = approvals.checkOrRequestApproval("goods_receipt", grnId, snapshot);
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            Map<String, Object> draft = tx.execute(st -> insertGrnDraft(grnId, orgId, receiptNumber, po, dto, resolved));
            audit.record(AuditInput.of("GoodsReceiptNote", grnId, "create", null, Obj.of("receiptNumber", receiptNumber,
                    "status", "draft", "purchaseOrderId", id, "approvalRequestId", gate.get("requestId"))));
            return Obj.of("grn", draft, "status", po.get("status"), "approvalRequired", true, "approvalRequestId",
                    gate.get("requestId"));
        }

        Instant date = dto.receivedAt() != null ? ProcurementSql.date(dto.receivedAt()) : nowMs();
        Map<String, Object> result = tx.execute(st -> {
            // 1. GRN (draft)
            Map<String, Object> grn = insertGrnDraft(grnId, orgId, receiptNumber, po, dto, resolved);
            // 2. Advance the PO under optimistic locks (shared with the goods-receipt post).
            List<ReceiptLine> applied = new ArrayList<>();
            for (Resolved r : resolved) {
                applied.add(new ReceiptLine(r.resolvedLineId(), r.line().productId(), r.line().quantity().doubleValue(),
                        r.line().description()));
            }
            String newStatus = applyReceiptToPO(id, applied);
            // 3. Post the GRN
            jdbc.sql("UPDATE \"GoodsReceiptNote\" SET status = 'posted', \"postedAt\" = " + NOW + ", \"postedById\" = ?,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(user(), grnId, orgId).update();
            // 4. Stock-in per stockable line: Dr Stock Valuation / Cr GRNI, accumulating the capitalised value + tax.
            BigDecimal receiptNet = Money.ZERO;
            BigDecimal receiptTax = Money.ZERO;
            for (Resolved r : resolved) {
                ReceiveLineInput rln = r.line();
                if (rln.productId() == null) {
                    continue;
                }
                Map<String, Object> product = product(rln.productId(), orgId);
                if (product == null) {
                    continue;
                }
                BigDecimal unitCost = r.unitCost() != null ? r.unitCost()
                        : num(Money.dec(product.get("costPrice")).doubleValue());
                StockMove move = StockMove.of(rln.productId(), dto.warehouseId(), rln.quantity()).unitCost(unitCost)
                        .uom(stockUnit(r.unit(), product), r.unit().factor()).batch(rln.batchNumber())
                        .expiry(rln.expiryDate())
                        .reference("GRN " + receiptNumber);
                Received received = stock.receiveForDocument(move, GlReceiptContext.grni("goods_receipt", grnId, date));
                BigDecimal lineNet = Money.dec(received.totalValue());
                Map<String, Object> poLine = r.resolvedLineId() == null ? null : poLines.get(r.resolvedLineId());
                BigDecimal taxRate = poLine != null ? Money.dec(poLine.get("taxRate")) : Money.ZERO;
                receiptNet = receiptNet.add(lineNet);
                receiptTax = receiptTax.add(Money.div(lineNet.multiply(taxRate), BigDecimal.valueOf(100)));
            }
            // 4b. Voucher the receipt: Dr GRNI + Dr Input Tax / Cr AP (GRNI nets to zero).
            stockPosting.postReceiptVoucher((String) po.get("partnerId"), receiptNet, receiptTax, date, "goods_receipt",
                    grnId, "Goods received " + receiptNumber + " · PO " + po.get("orderNumber"),
                    "receipt_voucher:goods_receipt:" + grnId);
            // 7. Cash purchase: settle only what this receipt vouchered.
            if ("cash".equals(po.get("paymentType"))) {
                settleCashReceipt(id, receiptNet.add(receiptTax), grnId, receiptNumber, date);
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "receive", null, Obj.of("receiptNumber", receiptNumber,
                    "status", newStatus, "linesReceived", dto.lines().size())));
            return Obj.of("grn", grn, "status", newStatus);
        });
        events.publish("purchase_order.received", Obj.of("organizationId", orgId, "orderId", id, "receiptNumber",
                receiptNumber, "status", result.get("status")));
        return result;
    }

    /**
     * Unit snapshot for a receipt line (ADR-S003): a PO-backed line inherits the PO line's unit and factor so
     * receipt, PO progress and billing all count in the unit that was ordered. PO lines written before V5 carry
     * no factor and keep the legacy behaviour (null snapshot, converted with the product's purchase unit).
     * Lines without a PO line use {@code requestedUomId}, else the product's purchase unit.
     */
    LineUnit receiptUnit(Map<String, Object> poLine, String productId, String requestedUomId) {
        if (poLine != null) {
            Object factor = poLine.get("uomFactor");
            return factor == null ? new LineUnit(null, null)
                    : new LineUnit((String) poLine.get("unitOfMeasureId"), Money.dec(factor));
        }
        if (productId == null || productId.isEmpty()) {
            return new LineUnit(requestedUomId, null);
        }
        return units.resolveLineUnit(productId, requestedUomId, UnitRole.PURCHASE);
    }

    /** Unit handed to the stock engine: the snapshot unit, or the product's purchase unit for legacy lines. */
    static String stockUnit(LineUnit unit, Map<String, Object> product) {
        return unit.factor() != null ? unit.uomId() : (String) product.get("purchaseUomId");
    }

    /** Product row scoped like {@code tx.product.findFirst} (org + not deleted). */
    Map<String, Object> product(String productId, String orgId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(productId, orgId));
    }

    // ── Step 3: Register Payment (credit purchases only) ───────────────────

    /** Manual payment on a credit PO: Dr AP / Cr Cash|Bank (+ drawer pay-out for cash). */
    public Map<String, Object> pay(String id, PayInput dto) {
        String orgId = org();
        requireOwned(id);
        double[] amount = {0};
        Map<String, Object> result = tx.execute(st -> {
            // Lock the PO BEFORE reading its balance so concurrent full-balance payments serialise.
            jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(id, orgId).query().listOfRows();
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (!"credit".equals(po.get("paymentType"))) {
                throw ApiException.badRequest("Only credit purchases can accept manual payments");
            }
            if ("cancelled".equals(po.get("status"))) {
                throw ApiException.badRequest("Cannot pay a cancelled PO");
            }
            // Paid-to-date from the immutable payment rows, not the cached aggregate.
            BigDecimal paidSum = jdbc.sql("SELECT sum(amount) FROM \"PurchasePayment\" WHERE \"organizationId\" = ?"
                    + " AND \"purchaseOrderId\" = ?").params(orgId, id).query(BigDecimal.class).optional().orElse(null);
            BigDecimal paidToDate = Money.max(Money.dec(paidSum), Money.dec(po.get("totalPaid")));
            BigDecimal remaining = Money.dec(po.get("totalAmount")).subtract(paidToDate);
            BigDecimal payAmount = dto.amount() != null ? dto.amount() : remaining;
            if (payAmount.signum() <= 0) {
                throw ApiException.badRequest(remaining.signum() <= 0 ? "This purchase order is already fully paid"
                        : "Payment amount must be positive");
            }
            if (payAmount.compareTo(remaining) > 0) {
                throw ApiException.badRequest("Payment of " + JsNumbers.dec(payAmount) + " exceeds remaining balance of "
                        + JsNumbers.dec(remaining));
            }
            amount[0] = payAmount.doubleValue();
            BigDecimal amountDec = num(amount[0]);
            BigDecimal newTotalPaid = paidToDate.add(payAmount);
            String newPaymentStatus = newTotalPaid.compareTo(Money.dec(po.get("totalAmount"))) >= 0 ? "paid" : "partial";
            Instant paidAt = dto.paidAt() != null ? ProcurementSql.date(dto.paidAt()) : null;

            Map<String, Object> payment = SqlRows.one(PurchasePayment.class, jdbc.sql("INSERT INTO \"PurchasePayment\""
                            + " (id, \"organizationId\", \"purchaseOrderId\", amount, \"paidAt\", \"paidById\", reference,"
                            + " notes, \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, " + (paidAt != null ? "?" : NOW)
                            + ", ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(paymentParams(orgId, id, amountDec, paidAt, dto)));
            jdbc.sql("UPDATE \"PurchaseOrder\" SET \"totalPaid\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\","
                            + " version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL")
                    .params(newTotalPaid, newPaymentStatus, id, orgId).update();

            String method = dto.method() != null ? dto.method() : "bank";
            Map<String, Object> session = "cash".equals(method) ? cashSession.findOpen(dto.cashRegisterId()) : null;
            if ("cash".equals(method) && session == null) {
                throw ApiException.badRequest("Select an open register for this cash payment");
            }
            Map<String, Object> paymentJournal = stockPosting.postPurchasePayment(
                    session == null ? null : (String) session.get("id"), (String) po.get("partnerId"), amountDec, method,
                    paidAt != null ? paidAt : nowMs(), "purchase_order", id,
                    "Payment PO " + po.get("orderNumber") + (dto.reference() != null && !dto.reference().isEmpty()
                            ? " · " + dto.reference() : ""),
                    "purchase_payment:" + payment.get("id"));
            if ("cash".equals(method)) {
                cashSession.recordExternalPayOut((String) session.get("id"), amountDec,
                        "Payment PO " + po.get("orderNumber"), (String) paymentJournal.get("id"));
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "update", null, Obj.of("paymentStatus",
                    newPaymentStatus, "amountPaid", JsNumbers.js(amount[0]), "method", method)));
            return payment;
        });
        events.publish("purchase_order.paid", Obj.of("organizationId", orgId, "orderId", id, "amount",
                JsNumbers.js(amount[0])));
        return result;
    }

    private static Object[] paymentParams(String orgId, String id, BigDecimal amount, Instant paidAt, PayInput dto) {
        List<Object> p = new ArrayList<>(List.of(UUID.randomUUID().toString(), orgId, id, amount));
        if (paidAt != null) {
            p.add(SqlRows.ts(paidAt));
        }
        p.add(user());
        p.add(dto.reference());
        p.add(dto.notes());
        return p.toArray();
    }

    // ── Update / cancel / delete / activate ────────────────────────────────

    /** Edit an active PO's header (version-guarded). */
    public Map<String, Object> update(String id, UpdateInput dto) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (!"active".equals(po.get("status"))) {
                throw ApiException.badRequest("Only active POs can be updated");
            }
            StringBuilder set = new StringBuilder("\"expectedDeliveryDate\" = ?, version = version + 1, \"updatedAt\" = "
                    + NOW);
            List<Object> params = new ArrayList<>();
            params.add(SqlRows.ts(ProcurementSql.date(dto.expectedDeliveryDate())));
            Map<String, Object> newValues = new LinkedHashMap<>();
            setIfPresent(set, params, newValues, "description", dto.description());
            newValues.put("expectedDeliveryDate", dto.expectedDeliveryDate());
            setIfPresent(set, params, newValues, "notes", dto.notes());
            setIfPresent(set, params, newValues, "terms", dto.terms());
            params.addAll(List.of(id, orgId, po.get("version")));
            int updated = jdbc.sql("UPDATE \"PurchaseOrder\" SET " + set + " WHERE id = ? AND \"organizationId\" = ?"
                    + " AND version = ? AND \"deletedAt\" IS NULL").params(params).update();
            if (updated == 0) {
                throw ApiException.badRequest(
                        "Purchase order was modified by another user. Please refresh and try again.");
            }
            Map<String, Object> oldValues = new LinkedHashMap<>();
            oldValues.put("description", po.get("description"));
            oldValues.put("expectedDeliveryDate", po.get("expectedDeliveryDate"));
            oldValues.put("notes", po.get("notes"));
            oldValues.put("terms", po.get("terms"));
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "update", oldValues, newValues));
            return SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        });
    }

    /** Prisma skips {@code undefined} data keys; an explicit {@code null} clears the column. */
    private static void setIfPresent(StringBuilder set, List<Object> params, Map<String, Object> newValues, String col,
            String value) {
        if (EntityPatch.present(col, value)) {
            set.append(", ").append(col).append(" = ?");
            params.add(value);
            newValues.put(col, value);
        }
    }

    /** Cancel unless received / billed / closed / already cancelled. */
    public Map<String, Object> cancel(String id, String reason) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (List.of("received", "cancelled", "billed", "closed").contains(po.get("status"))) {
                throw ApiException.badRequest("Cannot cancel PO in status " + po.get("status"));
            }
            int updated = jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'cancelled', notes = ?, version = version + 1,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND version = ?"
                            + " AND \"deletedAt\" IS NULL")
                    .params(reason != null ? reason : po.get("notes"), id, orgId, po.get("version")).update();
            if (updated == 0) {
                throw ApiException.badRequest(
                        "Purchase order was modified by another user. Please refresh and try again.");
            }
            Map<String, Object> cancelled = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\""
                    + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "cancel", Obj.of("status", po.get("status")),
                    Obj.of("status", "cancelled", "reason", reason, "orderNumber", po.get("orderNumber"))));
            events.publish("purchase_order.cancelled", Obj.of("organizationId", orgId, "orderId", id, "reason",
                    reason != null ? reason : ""));
            return cancelled;
        });
    }

    /** Hard delete (not received / cancelled). */
    public Map<String, Object> remove(String id) {
        Map<String, Object> po = requireOwned(id);
        if ("received".equals(po.get("status")) || "cancelled".equals(po.get("status"))) {
            throw ApiException.badRequest("Cannot delete a received or cancelled PO");
        }
        tx.executeWithoutResult(st -> {
            int deleted = jdbc.sql("DELETE FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(id, org()).update();
            if (deleted == 0) {
                throw new IllegalStateException("No record was found for a delete.");
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "delete", Obj.of("orderNumber", po.get("orderNumber"),
                    "status", po.get("status"), "totalAmount", po.get("totalAmount")), null));
        });
        return Obj.of("ok", true);
    }

    /** Activate a draft PO once no approval request is pending. */
    public Map<String, Object> activate(String id) {
        Map<String, Object> po = requireOwned(id);
        if (!"draft".equals(po.get("status"))) {
            throw ApiException.badRequest("Only draft POs can be activated, current status: " + po.get("status"));
        }
        long pending = jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\""
                        + " = 'purchase_order' AND \"entityId\" = ? AND status = 'pending'")
                .params(org(), id).query(Long.class).single();
        if (pending > 0) {
            throw ApiException.forbidden(
                    "This purchase order is awaiting approval. Approve or reject the request first.");
        }
        Map<String, Object> updated = SqlRows.one(PurchaseOrder.class, jdbc.sql("UPDATE \"PurchaseOrder\" SET status ="
                        + " 'active', \"approvedAt\" = " + NOW + ", \"approvedById\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL RETURNING *")
                .params(user(), id, org()));
        if (updated == null) {
            throw new IllegalStateException("No record was found for an update.");
        }
        audit.record(AuditInput.of("PurchaseOrder", id, "approve", null, Obj.of("status", "active")));
        events.publish("purchase_order.activated", Obj.of("organizationId", org(), "orderId", id, "orderNumber",
                po.get("orderNumber")));
        return updated;
    }

    private Map<String, Object> requireOwned(String id) {
        Map<String, Object> order = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (order == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        return order;
    }

    /** Partner row for the debit-note / GRN readers (unfiltered include). */
    Map<String, Object> partner(Object partnerId) {
        return partnerId == null ? null : SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?")
                .param(partnerId));
    }
}
