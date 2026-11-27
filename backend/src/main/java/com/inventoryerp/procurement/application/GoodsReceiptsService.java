package com.inventoryerp.procurement.application;

import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.domain.GoodsReceiptLine;
import com.inventoryerp.procurement.domain.GoodsReceiptNote;
import com.inventoryerp.procurement.domain.PurchaseOrder;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Goods receipt notes (port of {@code goods-receipts.service.ts}): inert draft capture, the single audited
 * draft→posted transition (approval gate, product check, atomic claim, PO advance, stock + GRNI, PO voucher and
 * cash settlement), ad-hoc capture+post, posted reversal, list and detail.
 */
@Service
public class GoodsReceiptsService {

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PurchaseOrdersService purchaseOrders;
    private final ReceivingControls receiving;
    private final StockReversalService reversals;
    private final TransactionTemplate tx;
    private final ProductVariantService variants;

    public GoodsReceiptsService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ApprovalsService approvals, StockService stock, StockPostingService stockPosting,
            PurchaseOrdersService purchaseOrders, StockReversalService reversals, PlatformTransactionManager txm,
            ReceivingControls receiving, ProductVariantService variants) {
        this.variants = variants;
        this.jdbc = jdbc;
        this.receiving = receiving;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.approvals = approvals;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.purchaseOrders = purchaseOrders;
        this.reversals = reversals;
        this.tx = new TransactionTemplate(txm);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lines(Map<String, Object> input) {
        Object v = input == null ? null : input.get("lines");
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        if (v instanceof String s) {
            // `lines?.length` of a string is its length; Prisma then rejects the nested create.
            if (s.isEmpty()) {
                return List.of();
            }
            throw new IllegalStateException("Invalid nested create for GoodsReceiptLine");
        }
        if (v == null) {
            return List.of();
        }
        throw new IllegalStateException("Invalid nested create for GoodsReceiptLine");
    }

    /**
     * Capture + post in one call for ad-hoc stock-ins: {@link #createDraft} then {@link #post}. Without
     * {@code goods_receipt:post}, or while approval is required, only the draft is created.
     */
    private com.inventoryerp.qualitycontrol.application.QcGate qcGate;

    private com.inventoryerp.invoicing.application.BillReceiptMatcherService billMatcher;

    /** Purchase readiness H1: absorbs stock a bill already auto-received; optional for plain constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBillMatcher(com.inventoryerp.invoicing.application.BillReceiptMatcherService billMatcher) {
        this.billMatcher = billMatcher;
    }

    /** Quality-control gate (ADR-S087); optional for plain constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQcGate(com.inventoryerp.qualitycontrol.application.QcGate qcGate) {
        this.qcGate = qcGate;
    }

    /** A receipt as the quality gate sees it: lines in base units at the receipt's warehouse, from its supplier. */
    static com.inventoryerp.qualitycontrol.application.QcSourceDoc qcDoc(Map<String, Object> grn, List<Map<String, Object>> lines) {
        List<com.inventoryerp.qualitycontrol.application.QcSourceDoc.Line> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") == null) {
                continue;
            }
            BigDecimal factor = l.get("uomFactor") == null ? BigDecimal.ONE : Money.dec(l.get("uomFactor"));
            out.add(new com.inventoryerp.qualitycontrol.application.QcSourceDoc.Line((String) l.get("id"), (String) l.get("productId"),
                    (String) l.get("variantId"), Money.dec(l.get("quantity")).multiply(factor),
                    (String) l.get("batchNumber")));
        }
        return new com.inventoryerp.qualitycontrol.application.QcSourceDoc("goods_receipt", (String) grn.get("id"),
                (String) grn.get("receiptNumber"), "receipt", null, null, null, (String) grn.get("partnerId"),
                (String) grn.get("warehouseId"), out);
    }

    public Map<String, Object> createAdhoc(Map<String, Object> input) {
        if (lines(input).isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        Map<String, Object> draft = createDraft(input);
        if (!TenantContext.has("goods_receipt:post")) {
            return draft;
        }
        // ADR-S087: checks required before posting (pre_receipt control points) keep the receipt a draft.
        if (qcGate != null && Boolean.TRUE.equals(tx.execute(s -> qcGate.isBlocked("goods_receipt",
                (String) draft.get("id"))))) {
            draft.put("qualityChecksPending", true);
            return draft;
        }
        Map<String, Object> gate = approvals.checkOrRequestApproval("goods_receipt", (String) draft.get("id"),
                approvalSnapshot(draft));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            return draft;
        }
        return post((String) draft.get("id"));
    }

    /** Approval snapshot: {@code amount} is the receipt's money value. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> approvalSnapshot(Map<String, Object> grn) {
        BigDecimal amount = Money.ZERO;
        for (Map<String, Object> l : (List<Map<String, Object>>) grn.get("lines")) {
            amount = amount.add(Money.dec(l.get("quantity")).multiply(Money.dec(l.get("unitCost"))));
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("amount", JsNumbers.js(amount));
        s.put("receiptNumber", grn.get("receiptNumber"));
        s.put("partnerId", grn.get("partnerId"));
        s.put("purchaseOrderId", grn.get("purchaseOrderId"));
        s.put("createdBy", user());
        return s;
    }

    /** Capture a delivery note as an inert DRAFT receipt (no stock, no GL, no PO advance). */
    public Map<String, Object> createDraft(Map<String, Object> input) {
        String orgId = org();
        List<Object> lines = lines(input);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        Object warehouseId = input.get("warehouseId");
        if (!ProcurementSql.truthy(warehouseId)) {
            throw ApiException.badRequest("Warehouse required");
        }
        long wh = jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?")
                .params(String.valueOf(warehouseId), orgId).query(Long.class).single();
        if (wh == 0) {
            throw ApiException.notFound("Warehouse not found");
        }
        // P0-6: referenced documents must belong to this organisation.
        assertOwned("PurchaseOrder", input.get("purchaseOrderId"), "Purchase order not found", orgId);
        assertOwned("Partner", input.get("partnerId"), "Supplier not found", orgId);
        assertOwned("Branch", input.get("branchId"), "Branch not found", orgId);
        String partnerId = receiptSupplier(stringOrNull(input.get("purchaseOrderId")),
                stringOrNull(input.get("partnerId")), orgId);
        // Gap fix h03-F9: the lines are checked before anything is written (a negative quantity used to lower the
        // order's received quantity, or end in a 500 on a stock line). Gap fix h03-F8: a line of the order is valued
        // at the order price.
        String purchaseOrderId = stringOrNull(input.get("purchaseOrderId"));
        OrderLines orderLines = purchaseOrderId == null || purchaseOrderId.isEmpty() ? null
                : orderLines(purchaseOrderId, orgId);
        for (Object o : lines) {
            checkLine(o);
        }
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        String receiptNumber = sequence.next("grn:" + year, "GRN-" + year + "-", 5);
        Map<String, Object> grn = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Object receivedAt = input.get("receivedAt");
            boolean hasReceivedAt = ProcurementSql.truthy(receivedAt);
            List<Object> params = new ArrayList<>(List.of(id, orgId, receiptNumber));
            params.add(stringOrNull(input.get("purchaseOrderId")));
            params.add(partnerId);
            params.add(stringOrNull(input.get("branchId")));
            params.add(String.valueOf(warehouseId));
            if (hasReceivedAt) {
                params.add(SqlRows.ts(EntityPatch.toInstant(String.valueOf(receivedAt))));
            }
            params.add(stringOrNull(input.get("notes")));
            params.add(user());
            Map<String, Object> row = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("INSERT INTO \"GoodsReceiptNote\""
                            + " (id, \"organizationId\", \"receiptNumber\", \"purchaseOrderId\", \"partnerId\", \"branchId\","
                            + " \"warehouseId\", \"receivedAt\", status, notes, \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, " + (hasReceivedAt ? "?" : NOW) + ", 'draft', ?, ?, " + NOW
                            + ", " + NOW + ") RETURNING *")
                    .params(params));
            List<Map<String, Object>> created = new ArrayList<>();
            int idx = 0;
            for (Object o : lines) {
                if (!(o instanceof Map<?, ?> ln)) {
                    throw new IllegalStateException("Invalid GoodsReceiptLine data");
                }
                Object expiry = ln.get("expiryDate");
                String poLineId = stringOrNull(ln.get("purchaseOrderLineId"));
                Map<String, Object> poLine = poLineId == null ? null : SqlRows.one(jdbc.sql("SELECT \"unitOfMeasureId\","
                        + " \"uomFactor\", \"variantId\" FROM \"PurchaseOrderLine\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(poLineId, orgId));
                // Purchase readiness (2026-11-27): a product-only line of a PO receipt advances the order's only line of
                // its product (as posting resolves it); it is stored on the line so its variant, price and input VAT
                // come from that order line. The line keeps the unit it was counted in (M-4).
                Map<String, Object> resolvedLine = orderLines == null ? null
                        : orderLines.resolve(poLineId, stringOrNull(ln.get("productId")));
                String lineId = poLineId == null && resolvedLine != null ? (String) resolvedLine.get("id") : poLineId;
                Map<String, Object> variantSource = poLine != null ? poLine : resolvedLine;
                // ADR-S058: the PO line's variant wins; a free receipt line names its own. Purchase readiness: a
                // different variant than the one ordered is refused instead of silently replaced.
                String askedVariant = stringOrNull(ln.get("variantId"));
                if (variantSource != null && variantSource.get("variantId") != null && askedVariant != null
                        && !askedVariant.isEmpty() && !askedVariant.equals(variantSource.get("variantId"))) {
                    throw ApiException.badRequest("Line \"" + ln.get("description") + "\" names a different variant"
                            + " than the purchase order line it receives; receive the ordered variant or add the other"
                            + " variant to the order first");
                }
                String variantId = variantSource != null && variantSource.get("variantId") != null
                        ? (String) variantSource.get("variantId") : askedVariant;
                ProductVariantService.VariantRef variant = variants.resolve(stringOrNull(ln.get("productId")), variantId);
                LineUnit unit = purchaseOrders.receiptUnit(poLine, stringOrNull(ln.get("productId")),
                        stringOrNull(ln.get("uomId")));
                BigDecimal unitCost = ln.get("unitCost") == null ? null : requiredDecimal(ln.get("unitCost"));
                Map<String, Object> ordered = orderLines == null ? null
                        : orderLines.resolve(poLineId, stringOrNull(ln.get("productId")));
                if (ordered != null) {
                    unitCost = PurchaseOrdersService.receiptCost(ln.get("description"), unitCost,
                            PurchaseOrdersService.orderPriceInLineUnit(ordered, unit.factor()));
                }
                created.add(SqlRows.one(GoodsReceiptLine.class, jdbc.sql("INSERT INTO \"GoodsReceiptLine\" (id,"
                                + " \"organizationId\", \"goodsReceiptId\", \"purchaseOrderLineId\", \"productId\","
                                + " description, quantity, \"unitCost\", \"batchNumber\", \"expiryDate\", notes, \"lineNumber\","
                                + " \"uomId\", \"uomFactor\", \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, lineId,
                                stringOrNull(ln.get("productId")), requiredString(ln.get("description")),
                                requiredDecimal(ln.get("quantity")),
                                unitCost == null ? BigDecimal.ZERO : unitCost,
                                stringOrNull(ln.get("batchNumber")),
                                ProcurementSql.truthy(expiry) ? SqlRows.ts(EntityPatch.toInstant(String.valueOf(expiry)))
                                        : null,
                                stringOrNull(ln.get("notes")), ++idx, unit.uomId(), unit.factor(),
                                variant == null ? null : variant.id())));
            }
            row.put("lines", created);
            if (qcGate != null) {
                qcGate.sync(qcDoc(row, created));
            }
            return row;
        });
        audit.record(AuditInput.of("GoodsReceiptNote", (String) grn.get("id"), "create", null,
                Obj.of("receiptNumber", receiptNumber, "lines", lines.size(), "status", "draft")));
        return grn;
    }

    /**
     * Blocker fix 2026-11-17 (B1, Nest deviation): a receipt of a purchase order belongs to the order's supplier. The
     * bill matcher pairs bill lines with receipts of the bill's supplier only, so a receipt saved without one was never
     * matched and the supplier bill received and charged the goods a second time. An omitted supplier is taken from the
     * order; a different one is refused. Gap fix h03 residual (a): a receipt without an order must name its supplier
     * (the web asked for one, the API did not).
     */
    private String receiptSupplier(String purchaseOrderId, String partnerId, String orgId) {
        if (purchaseOrderId == null || purchaseOrderId.isEmpty()) {
            if (partnerId == null || partnerId.isEmpty()) {
                throw ApiException.badRequest("A goods receipt without a purchase order needs a supplier; select the"
                        + " supplier who delivered the goods");
            }
            return partnerId;
        }
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT \"partnerId\" FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(purchaseOrderId, orgId));
        String poSupplier = po == null ? null : (String) po.get("partnerId");
        if (partnerId == null || partnerId.isEmpty()) {
            return poSupplier;
        }
        if (poSupplier != null && !poSupplier.equals(partnerId)) {
            throw ApiException.badRequest("The supplier must be the purchase order's supplier");
        }
        return partnerId;
    }

    private void assertOwned(String table, Object id, String message, String orgId) {
        if (id instanceof String s && !s.isEmpty() && jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(s, orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound(message);
        }
    }

    private static String stringOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalStateException("Invalid value for a String field: " + v);
    }

    private static String requiredString(Object v) {
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalStateException("Argument `description` is missing or not a String.");
    }

    private static BigDecimal requiredDecimal(Object v) {
        if (v instanceof Number || v instanceof String) {
            return Money.dec(v);
        }
        throw new IllegalStateException("Invalid Decimal value: " + v);
    }

    /** A JSON number or numeric string, else {@code null}. */
    private static BigDecimal number(Object v) {
        if (v instanceof Number || v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Gap fix h03-F9: a receipt line is an object with a description, a quantity above zero and, when given, a unit
     * cost that is a number and not negative.
     */
    private static void checkLine(Object o) {
        if (!(o instanceof Map<?, ?> ln)) {
            throw ApiException.badRequest("Each receipt line must be an object with a description and a quantity");
        }
        if (!(ln.get("description") instanceof String description)) {
            throw ApiException.badRequest("Each receipt line needs a description");
        }
        BigDecimal quantity = number(ln.get("quantity"));
        if (quantity == null || quantity.signum() <= 0) {
            throw ApiException.badRequest("Receipt line \"" + description + "\": quantity must be a number greater"
                    + " than zero");
        }
        if (ln.get("unitCost") != null) {
            BigDecimal unitCost = number(ln.get("unitCost"));
            if (unitCost == null || unitCost.signum() < 0) {
                throw ApiException.badRequest("Receipt line \"" + description + "\": unit cost must be a number that is"
                        + " not negative");
            }
        }
    }

    /** An order's lines by id, and per product (a receipt line may name only its product). */
    private record OrderLines(Map<String, Map<String, Object>> byId, Map<String, List<Map<String, Object>>> byProduct) {

        /** The order line a receipt line advances: the one it names, else the order's only line of its product. */
        Map<String, Object> resolve(String purchaseOrderLineId, String productId) {
            if (purchaseOrderLineId != null && !purchaseOrderLineId.isEmpty()) {
                return byId.get(purchaseOrderLineId);
            }
            List<Map<String, Object>> candidates = productId == null ? List.of()
                    : byProduct.getOrDefault(productId, List.of());
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
    }

    private OrderLines orderLines(String purchaseOrderId, String orgId) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byProduct = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                + " \"purchaseOrderId\" = ? AND \"organizationId\" = ? ORDER BY \"lineNumber\", id")
                .params(purchaseOrderId, orgId))) {
            byId.put((String) l.get("id"), l);
            if (l.get("productId") != null && !((String) l.get("productId")).isEmpty()) {
                byProduct.computeIfAbsent((String) l.get("productId"), k -> new ArrayList<>()).add(l);
            }
        }
        return new OrderLines(byId, byProduct);
    }

    /** The single audited draft→posted transition. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String id) {
        String orgId = org();
        Map<String, Object> grn = findWithLines(id, orgId);
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if (!"draft".equals(grn.get("status"))) {
            throw ApiException.badRequest("Only draft receipts can be posted");
        }
        List<Map<String, Object>> approvalReqs = SqlRows.list(jdbc.sql("SELECT id, status::text AS status FROM"
                        + " \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = 'goods_receipt'"
                        + " AND \"entityId\" = ? AND status IN ('pending', 'rejected') ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(orgId, id));
        if (!approvalReqs.isEmpty()) {
            Map<String, Object> approvalReq = approvalReqs.getFirst();
            if ("pending".equals(approvalReq.get("status"))) {
                throw ApiException.badRequest("Goods receipt is pending approval (request " + approvalReq.get("id")
                        + ") and cannot be posted yet");
            }
            if ("rejected".equals(approvalReq.get("status"))) {
                throw ApiException.badRequest("Goods receipt was rejected and cannot be posted");
            }
        }
        // Enforce the goods_receipt approval policy here too: a plain draft never requested approval.
        Map<String, Object> gate = approvals.checkOrRequestApproval("goods_receipt", id, approvalSnapshot(grn));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Goods receipt requires approval before posting (request "
                    + gate.get("requestId") + ")");
        }
        // Every product line must resolve now.
        Set<String> productIds = new LinkedHashSet<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) grn.get("lines")) {
            if (l.get("productId") != null && !((String) l.get("productId")).isEmpty()) {
                productIds.add((String) l.get("productId"));
            }
        }
        if (!productIds.isEmpty()) {
            List<String> found = jdbc.sql("SELECT id FROM \"Product\" WHERE id = ANY(?::text[]) AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(productIds.toArray(String[]::new), orgId).query(String.class)
                    .list();
            List<String> missing = productIds.stream().filter(p -> !found.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw ApiException.notFound("Product(s) not found: " + String.join(", ", missing));
            }
        }
        // ADR-PROCUREMENT-001 W6: weighbridge evidence for bulk material (policy; off by default).
        receiving.requireEvidence(id, (String) grn.get("branchId"), ((List<Map<String, Object>>) grn.get("lines"))
                .stream().map(l -> new ReceivingControls.Line((String) l.get("id"), (String) l.get("productId"),
                        (String) l.get("description"))).toList());

        Map<String, Object> result = tx.execute(st -> {
            // ADR-S087: blocking quality checks of the receipt hold the post (before any write).
            if (qcGate != null) {
                qcGate.assertComplete("goods_receipt", id, List.of());
            }
            // Atomic draft→posted claim: only one concurrent post can match.
            int claim = jdbc.sql("UPDATE \"GoodsReceiptNote\" SET status = 'posted', \"postedAt\" = " + NOW + ","
                            + " \"postedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                            + " AND status = 'draft' AND \"deletedAt\" IS NULL")
                    .params(user(), id, orgId).update();
            if (claim == 0) {
                throw ApiException.conflict("Goods receipt was already posted by another request");
            }
            Map<String, Object> updated = findWithLines(id, orgId);
            List<Map<String, Object>> updatedLines = (List<Map<String, Object>>) updated.get("lines");

            Map<String, Object> po = null;
            OrderLines orderLines = null;
            // IPR LK-1: the supplier lock before any purchase-order row, the order a bill post takes (supplier → PO
            // line → PO), so a receipt and a bill on the same order cannot deadlock. Taken again below (re-entrant).
            if (billMatcher != null) {
                String supplierFirst = updated.get("partnerId") != null ? (String) updated.get("partnerId")
                        : grn.get("purchaseOrderId") == null ? null : jdbc.sql("SELECT \"partnerId\" FROM"
                                + " \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?")
                                .params(grn.get("purchaseOrderId"), orgId).query(String.class).optional().orElse(null);
                billMatcher.lockSupplier(orgId, supplierFirst);
            }
            if (grn.get("purchaseOrderId") != null) {
                List<PurchaseOrdersService.ReceiptLine> applied = new ArrayList<>();
                for (Map<String, Object> l : updatedLines) {
                    applied.add(new PurchaseOrdersService.ReceiptLine((String) l.get("purchaseOrderLineId"),
                            (String) l.get("productId"), Money.dec(l.get("quantity")),
                            (String) l.get("description"), l.get("uomFactor") == null ? null
                                    : Money.dec(l.get("uomFactor"))));
                }
                purchaseOrders.applyReceiptToPO((String) grn.get("purchaseOrderId"), applied);
                po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(grn.get("purchaseOrderId"), orgId));
                if (po != null && updated.get("partnerId") == null && po.get("partnerId") != null) {
                    // B1: a draft saved before the fix carries no supplier; the bill matcher needs the order's.
                    jdbc.sql("UPDATE \"GoodsReceiptNote\" SET \"partnerId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                            .params(po.get("partnerId"), id, orgId).update();
                }
                if (po != null) {
                    orderLines = orderLines((String) po.get("id"), orgId);
                    // Gap fix h03-F8: a line of the order is valued at the order price (a draft saved before the fix,
                    // or before a price change on the order, is re-priced here).
                    for (Map<String, Object> l : updatedLines) {
                        Map<String, Object> ordered = orderLines.resolve((String) l.get("purchaseOrderLineId"),
                                (String) l.get("productId"));
                        if (ordered == null) {
                            continue;
                        }
                        if (l.get("purchaseOrderLineId") == null) {
                            // Purchase readiness (2026-11-27): a product-only line names the order line it advanced.
                            jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"purchaseOrderLineId\" = ? WHERE id = ? AND"
                                    + " \"organizationId\" = ?").params(ordered.get("id"), l.get("id"), orgId).update();
                            l.put("purchaseOrderLineId", ordered.get("id"));
                        }
                        BigDecimal price = PurchaseOrdersService.orderPriceInLineUnit(ordered,
                                l.get("uomFactor") == null ? null : Money.dec(l.get("uomFactor")));
                        if (price.compareTo(Money.dec(l.get("unitCost"))) != 0) {
                            jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"unitCost\" = ? WHERE id = ? AND"
                                    + " \"organizationId\" = ?").params(price, l.get("id"), orgId).update();
                            l.put("unitCost", price);
                        }
                    }
                }
            }

            Instant date = grn.get("receivedAt") != null ? (Instant) grn.get("receivedAt")
                    : Instant.now().truncatedTo(ChronoUnit.MILLIS);
            // ADR-S088 limit 2 (IPR 1b): a back-office receipt is not backdated past inventory.maxBackdateDays
            // (offline device receipts are exempt); the closed-period rule stays the engine's period check.
            stock.assertWithinBackdateLimit(date, "Goods receipt");
            Instant effectiveAt = StockService.documentEffectiveAt(date);
            BigDecimal receiptNet = Money.ZERO;
            BigDecimal receiptTax = Money.ZERO;
            // P0-5: a PO-backed receipt is priced in the PO currency; stock / GRNI / AP carry the functional value.
            // FX-2 G: valued at the receipt-date rate, recorded on the receipt
            BigDecimal rate = po != null ? purchaseOrders.receiptRate(po, id, date) : BigDecimal.ONE;
            // Purchase readiness H1: stock a posted bill of this supplier already received itself (auto-receive) is
            // absorbed by this receipt — matched to that bill, not stocked or vouchered a second time.
            String supplierId = updated.get("partnerId") != null ? (String) updated.get("partnerId")
                    : po != null ? (String) po.get("partnerId") : null;
            if (billMatcher != null) {
                billMatcher.lockSupplier(orgId, supplierId);
            }
            for (Map<String, Object> ln : StockMove.inLockOrder(updatedLines)) { // audit S-2: engine lock order
                if (ln.get("productId") == null || ((String) ln.get("productId")).isEmpty()) {
                    continue;
                }
                Map<String, Object> product = purchaseOrders.product((String) ln.get("productId"), orgId);
                if (product == null || !PurchaseOrdersService.stocked(product)) {
                    continue;
                }
                BigDecimal absorbed = billMatcher == null ? Money.ZERO
                        : billMatcher.absorbAutoReceived(orgId, supplierId, ln);
                BigDecimal stockQty = Money.dec(ln.get("quantity")).subtract(absorbed);
                if (stockQty.signum() <= 0) {
                    continue;
                }
                BigDecimal unitCost = Money.dec(ln.get("unitCost"));
                Map<String, Object> poLine = orderLines == null ? null
                        : orderLines.resolve((String) ln.get("purchaseOrderLineId"), (String) ln.get("productId"));
                // An order line is valued at its (possibly zero) order price, as the PO receive route does; a line
                // without one and without a cost enters at the current cost (IPR C-3 / GL-6, owner decision D3:
                // location average, then last receipt, then cost price; it took the cost price).
                StockMove move = StockMove.of((String) ln.get("productId"), (String) grn.get("warehouseId"),
                                stockQty).variant((String) ln.get("variantId"))
                        .unitCost(poLine != null || unitCost.signum() > 0 ? unitCost.multiply(rate) : null)
                        .uom(PurchaseOrdersService.stockUnit(new LineUnit((String) ln.get("uomId"),
                                ln.get("uomFactor") == null ? null : Money.dec(ln.get("uomFactor"))), product),
                                ln.get("uomFactor") == null ? null : Money.dec(ln.get("uomFactor")))
                        .batch((String) ln.get("batchNumber"))
                        .expiry(ln.get("expiryDate") == null ? null : ln.get("expiryDate").toString())
                        .reference("GRN " + grn.get("receiptNumber")).effectiveAt(effectiveAt)
                        .sourceLine((String) ln.get("id")); // IPR 1d: the receipt line on its ledger row
                Received received = stock.receiveForDocument(move, GlReceiptContext.grni("goods_receipt", id, date));
                BigDecimal lineNet = Money.dec(received.actualValue());
                receiptNet = receiptNet.add(lineNet);
                if (po != null) {
                    // Purchase readiness (2026-11-27): the order line resolved as for quantity and price, so a
                    // product-only line of a PO receipt carries its input VAT too.
                    Map<String, Object> taxLine = poLine;
                    BigDecimal taxRate = taxLine != null ? Money.dec(taxLine.get("taxRate")) : Money.ZERO;
                    receiptTax = receiptTax.add(Money.div(lineNet.multiply(taxRate), BigDecimal.valueOf(100)));
                }
            }
            // ADR-S087 §7: quarantine and post-receipt checks commit or roll back with the receipt.
            if (qcGate != null) {
                qcGate.onCompleted(qcDoc(updated, updatedLines));
            }
            // Voucher only on the simple PO flow; ad-hoc receipts leave GRNI open for a later vendor bill.
            if (po != null) {
                stockPosting.postReceiptVoucher((String) po.get("partnerId"), receiptNet, receiptTax, date,
                        "goods_receipt", id, "Goods received " + grn.get("receiptNumber") + " · PO " + po.get("orderNumber"),
                        null, (String) po.get("currencyCode"), rate);
                if ("cash".equals(po.get("paymentType"))) {
                    purchaseOrders.settleCashReceipt((String) po.get("id"), receiptNet.add(receiptTax), id,
                            (String) grn.get("receiptNumber"), date);
                }
            }
            return updated;
        });
        receiving.afterPost(id);

        audit.record(AuditInput.of("GoodsReceiptNote", (String) result.get("id"), "post", null,
                Obj.of("receiptNumber", grn.get("receiptNumber"), "status", "posted")));
        events.publish("goods_receipt.posted", Obj.of("organizationId", orgId, "receiptId", result.get("id"),
                "receiptNumber", grn.get("receiptNumber")));
        return result;
    }

    private Map<String, Object> findWithLines(String id, String orgId) {
        Map<String, Object> grn = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("SELECT * FROM \"GoodsReceiptNote\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (grn != null) {
            grn.put("lines", SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM \"GoodsReceiptLine\" WHERE"
                    + " \"goodsReceiptId\" = ?").param(id)));
        }
        return grn;
    }

    /**
     * Posted reversal: returns the received quantities out of stock, mirrors the receipt's journals and rolls the
     * PO back. Refused once a vendor bill matched the receipt, the PO has a bill or payments, or landed cost posted.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reverse(String id, String reason) {
        String orgId = org();
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to reverse a goods receipt");
        }
        String why = reason.trim();
        Map<String, Object> grn = findWithLines(id, orgId);
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if ("reversed".equals(grn.get("status")) || grn.get("reversedAt") != null) {
            throw ApiException.conflict("Goods receipt " + grn.get("receiptNumber") + " is already reversed");
        }
        if (!"posted".equals(grn.get("status"))) {
            throw ApiException.badRequest("Only posted goods receipts can be reversed");
        }
        long landed = jdbc.sql("SELECT count(*) FROM \"LandedCost\" WHERE \"organizationId\" = ? AND status = 'posted'"
                + " AND (\"goodsReceiptId\" = ? OR id IN (SELECT \"landedCostId\" FROM \"LandedCostReceipt\""
                + " WHERE \"organizationId\" = ? AND \"goodsReceiptId\" = ?))").params(orgId, id, orgId, id)
                .query(Long.class).single();
        if (landed > 0) {
            throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " has posted landed cost — the"
                    + " capitalised freight/duty would be stranded; return the goods with a debit note instead");
        }
        List<Map<String, Object>> grnLines = (List<Map<String, Object>>) grn.get("lines");
        List<String> lineIds = grnLines.stream().map(l -> (String) l.get("id")).toList();
        // Fast refusals before any lock; the transaction checks again under locks (h03-F5).
        String blockedNow = reversalBlocker(grn, lineIds, orgId);
        if (blockedNow != null) {
            throw ApiException.badRequest(blockedNow);
        }
        Object poId = grn.get("purchaseOrderId");
        if (poId != null) {
            String orderBlocked = orderBlocker(grn, poId, orgId);
            if (orderBlocked != null) {
                throw ApiException.badRequest(orderBlocked);
            }
        }

        return tx.execute(st -> {
            // P1-6: lock the receipt, then re-check landed cost inside the transaction so a concurrent landed-cost
            // post cannot slip in between the check and the reversal.
            jdbc.sql("SELECT id FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(id, orgId).query().listOfRows();
            long landedNow = jdbc.sql("SELECT count(*) FROM \"LandedCost\" WHERE \"organizationId\" = ? AND status IN"
                    + " ('posted', 'approved') AND (\"goodsReceiptId\" = ? OR id IN (SELECT \"landedCostId\" FROM"
                    + " \"LandedCostReceipt\" WHERE \"organizationId\" = ? AND \"goodsReceiptId\" = ?))")
                    .params(orgId, id, orgId, id).query(Long.class).single();
            if (landedNow > 0) {
                throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " has posted landed cost —"
                        + " the capitalised freight/duty would be stranded; return the goods with a debit note instead");
            }
            // Gap fix h03-F5: the bill, return, claim, accrual and order checks run again under locks — the receipt
            // lines (the bill matcher's lock), then the order (bill post's and pay's lock) — so a bill, payment or
            // return that committed after the checks above is seen.
            jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? AND \"organizationId\" = ?"
                    + " ORDER BY \"lineNumber\", id FOR UPDATE").params(id, orgId).query().listOfRows();
            String blocked = reversalBlocker(grn, lineIds, orgId);
            if (blocked != null) {
                throw ApiException.badRequest(blocked);
            }
            if (poId != null) {
                jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(poId, orgId).query().listOfRows();
                String orderBlocked = orderBlocker(grn, poId, orgId);
                if (orderBlocked != null) {
                    throw ApiException.badRequest(orderBlocked);
                }
            }
            // B7 (post-parity): draft landed costs are cancelled only once the reversal is certain.
            jdbc.sql("UPDATE \"LandedCost\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE"
                    + " \"organizationId\" = ? AND \"goodsReceiptId\" = ? AND status = 'draft'").params(orgId, id).update();
            int claim = jdbc.sql("UPDATE \"GoodsReceiptNote\" SET status = 'reversed', \"reversedAt\" = " + NOW + ","
                            + " \"reversedById\" = ?, \"reversalReason\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                            + " AND \"organizationId\" = ? AND status = 'posted' AND \"reversedAt\" IS NULL"
                            + " AND \"deletedAt\" IS NULL")
                    .params(user(), why, id, orgId).update();
            if (claim == 0) {
                throw ApiException.conflict("Goods receipt was reversed concurrently");
            }
            String label = "Receipt " + grn.get("receiptNumber") + " cannot be reversed";
            // Purchase readiness H9b (2026-11-27): quality control is unwound with the receipt — refused while a decided
            // check holds goods in quarantine; pending post-receipt checks give their quarantined goods back to the
            // warehouse (the stock-out below takes them from there) and pending checks are cancelled.
            if (qcGate != null) {
                qcGate.unwindForReversal("goods_receipt", id, label, "Goods receipt reversed: " + why);
            }
            // H9b: a pending incoming inspection of the receipt is cancelled (it would otherwise raise a quality claim
            // for goods that went back, and keep holding the supplier bill).
            for (Map<String, Object> insp : SqlRows.list(jdbc.sql("SELECT id FROM \"IncomingInspection\" WHERE"
                    + " \"organizationId\" = ? AND \"goodsReceiptId\" = ? AND status = 'pending' ORDER BY id FOR UPDATE")
                    .params(orgId, id))) {
                jdbc.sql("UPDATE \"IncomingInspection\" SET status = 'cancelled', \"cancelReason\" = ?, \"cancelledBy\" = ?,"
                                + " \"cancelledAt\" = " + NOW + ", version = version + 1, \"updatedAt\" = " + NOW
                                + " WHERE id = ? AND \"organizationId\" = ?")
                        .params("Goods receipt reversed: " + why, user(), insp.get("id"), orgId).update();
                audit.recordInTx(AuditInput.of("IncomingInspection", (String) insp.get("id"), "update",
                        Obj.of("status", "pending"), Obj.of("status", "cancelled", "reason", "goods receipt reversed")));
            }
            boolean hasStock = grnLines.stream().anyMatch(l -> l.get("productId") != null
                    && !((String) l.get("productId")).isEmpty());
            if (hasStock) {
                // Purchase readiness H9a (2026-11-27): only goods still on hand at the receipt location (in their lot /
                // serial units, not reserved) can go back, whatever inventory.allowNegativeStock says.
                reversals.reverseLedgerSource(new LedgerSource("goods_receipt", id, "goods_receipt_reversal", id,
                        List.of("goods_receipt"), "Reversal of GRN " + grn.get("receiptNumber") + ": " + why, null,
                        label, "Return the remaining goods to the supplier with a debit note instead."));
            }
            if (poId != null) {
                Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, status::text AS status FROM \"PurchaseOrder\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL FOR UPDATE").params(poId, orgId));
                if (po != null) {
                    List<Map<String, Object>> poLines = SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                            + " \"purchaseOrderId\" = ?").param(poId));
                    Map<String, List<Map<String, Object>>> byProduct = new HashMap<>();
                    for (Map<String, Object> l : poLines) {
                        if (l.get("productId") != null && !((String) l.get("productId")).isEmpty()) {
                            byProduct.computeIfAbsent((String) l.get("productId"), k -> new ArrayList<>()).add(l);
                        }
                    }
                    for (Map<String, Object> ln : grnLines) {
                        String poLineId = (String) ln.get("purchaseOrderLineId");
                        if (poLineId == null && ln.get("productId") != null
                                && byProduct.getOrDefault((String) ln.get("productId"), List.of()).size() == 1) {
                            poLineId = (String) byProduct.get((String) ln.get("productId")).getFirst().get("id");
                        }
                        if (poLineId == null) {
                            continue;
                        }
                        // Gap fix h03-F5: posting added the quantity in the order line's unit; take back the same.
                        String advanced = poLineId;
                        Map<String, Object> poLine = poLines.stream().filter(l -> advanced.equals(l.get("id")))
                                .findFirst().orElse(null);
                        BigDecimal back = PurchaseOrdersService.inPoUnits(new PurchaseOrdersService.ReceiptLine(poLineId,
                                (String) ln.get("productId"), Money.dec(ln.get("quantity")), (String) ln.get("description"),
                                ln.get("uomFactor") == null ? null : Money.dec(ln.get("uomFactor"))), poLine);
                        jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"receivedQuantity\" = GREATEST(0, \"receivedQuantity\""
                                        + " - ?), version = version + 1 WHERE id = ? AND \"organizationId\" = ?")
                                .params(back, poLineId, orgId).update();
                    }
                    boolean anyReceived = false;
                    boolean allReceived = true;
                    for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT quantity, \"receivedQuantity\" FROM"
                            + " \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ?")
                            .params(poId, orgId))) {
                        BigDecimal received = Money.dec(l.get("receivedQuantity"));
                        if (received.signum() > 0) {
                            anyReceived = true;
                        }
                        if (received.compareTo(Money.dec(l.get("quantity"))) < 0) {
                            allReceived = false;
                        }
                    }
                    // P1-3: only the receipt statuses are recomputed; a cancelled / closed order stays that way.
                    if (List.of("active", "partially_received", "received").contains(po.get("status"))) {
                        String status = allReceived ? "received" : anyReceived ? "partially_received" : "active";
                        jdbc.sql("UPDATE \"PurchaseOrder\" SET status = ?::\"PurchaseOrderStatus\", version = version + 1,"
                                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                                        + " AND \"deletedAt\" IS NULL")
                                .params(status, poId, orgId).update();
                    }
                    purchaseOrders.commitmentsChanged((String) poId);
                }
            }
            audit.recordInTx(AuditInput.of("GoodsReceiptNote", id, "update", null, Obj.of("status", "reversed",
                    "reason", why, "receiptNumber", grn.get("receiptNumber"))));
            return findWithLines(id, orgId);
        });
    }

    /**
     * Gap fix h03-F5: why a posted receipt cannot be reversed, or {@code null}. A bill matched it; goods of it went back
     * on a debit note; a supplier claim names it (an open one would settle against goods that are gone, a settled one
     * already moved the payable, GRNI or stock); a landed-cost accrual is on it.
     */
    private String reversalBlocker(Map<String, Object> grn, List<String> lineIds, String orgId) {
        Object number = grn.get("receiptNumber");
        String[] ids = lineIds.toArray(String[]::new);
        long matched = jdbc.sql("SELECT count(*) FROM \"VendorBillReceiptMatch\" WHERE \"organizationId\" = ?"
                + " AND \"goodsReceiptLineId\" = ANY(?::text[])").params(orgId, ids).query(Long.class).single();
        if (matched > 0) {
            return "Goods receipt " + number + " is matched to a vendor bill — reverse the bill or raise a debit note"
                    + " instead";
        }
        String note = jdbc.sql("SELECT n.\"noteNumber\" FROM \"DebitNoteReceiptMatch\" m JOIN \"DebitNote\" n ON n.id ="
                        + " m.\"debitNoteId\" WHERE m.\"organizationId\" = ? AND m.\"goodsReceiptLineId\" = ANY(?::text[])"
                        + " AND m.\"releasedAt\" IS NULL ORDER BY m.\"createdAt\" LIMIT 1")
                .params(orgId, ids).query(String.class).optional().orElse(null);
        if (note != null) {
            return "Goods receipt " + number + " has goods returned to the supplier on debit note " + note
                    + " — reverse the debit note first";
        }
        Map<String, Object> claim = SqlRows.one(jdbc.sql("SELECT c.\"claimNumber\", c.status FROM \"SupplierClaim\" c"
                + " WHERE c.\"organizationId\" = ? AND c.status NOT IN ('rejected', 'cancelled') AND (c.\"goodsReceiptId\""
                + " = ? OR EXISTS (SELECT 1 FROM \"SupplierClaimLine\" cl WHERE cl.\"claimId\" = c.id AND"
                + " cl.\"goodsReceiptId\" = ?)) ORDER BY c.\"createdAt\" LIMIT 1")
                .params(orgId, grn.get("id"), grn.get("id")));
        if (claim != null) {
            return "settled".equals(claim.get("status"))
                    ? "Goods receipt " + number + " has settled supplier claim " + claim.get("claimNumber")
                            + " — return the goods with a debit note instead"
                    : "Goods receipt " + number + " has open supplier claim " + claim.get("claimNumber")
                            + " — cancel or reject the claim first";
        }
        String accrual = jdbc.sql("SELECT number FROM \"LandedCostAccrual\" WHERE \"organizationId\" = ? AND"
                        + " \"goodsReceiptId\" = ? AND status <> 'reversed' ORDER BY \"createdAt\" LIMIT 1")
                .params(orgId, grn.get("id")).query(String.class).optional().orElse(null);
        if (accrual != null) {
            return "Goods receipt " + number + " has landed-cost accrual " + accrual + " — reverse the accrual first";
        }
        return null;
    }

    /** Why the receipt's order stops the reversal (a bill or payments on it), or {@code null}. */
    private String orderBlocker(Map<String, Object> grn, Object poId, String orgId) {
        long links = jdbc.sql("SELECT count(*) FROM \"VendorBillLink\" WHERE \"organizationId\" = ?"
                + " AND \"purchaseOrderId\" = ?").params(orgId, poId).query(Long.class).single();
        long payments = jdbc.sql("SELECT count(*) FROM \"PurchasePayment\" WHERE \"organizationId\" = ?"
                + " AND \"purchaseOrderId\" = ?").params(orgId, poId).query(Long.class).single();
        if (links > 0 || payments > 0) {
            return "The purchase order for " + grn.get("receiptNumber") + " already has "
                    + (links > 0 ? "a vendor bill" : "payments") + " — return the goods with a debit note instead";
        }
        return null;
    }

    /** Paged receipts (latest received first) with lines and the order number. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String pageRaw, String pageSizeRaw, String search, String status) {
        String orgId = org();
        int page = Math.max(1, JsNumbers.orDefault(pageRaw, 1));
        int pageSize = Math.min(1000, Math.max(1, JsNumbers.orDefault(pageSizeRaw, 25)));
        List<Object> params = new ArrayList<>(List.of(orgId));
        StringBuilder where = new StringBuilder(" g.\"organizationId\" = ? AND g.\"deletedAt\" IS NULL");
        if (status != null && !status.isEmpty()) {
            where.append(" AND g.status = ?::\"GoodsReceiptStatus\"");
            params.add(status);
        }
        if (search != null && !search.isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(search) + "%";
            where.append(" AND (g.\"receiptNumber\" ILIKE ? OR g.notes ILIKE ? OR EXISTS (SELECT 1 FROM \"PurchaseOrder\""
                    + " o WHERE o.id = g.\"purchaseOrderId\" AND o.\"orderNumber\" ILIKE ?))");
            params.addAll(List.of(like, like, like));
        }
        List<Map<String, Object>> data = SqlRows.list(GoodsReceiptNote.class, jdbc.sql("SELECT g.* FROM"
                + " \"GoodsReceiptNote\" g WHERE" + where + " ORDER BY g.\"receivedAt\" DESC LIMIT " + pageSize
                + " OFFSET " + (long) (page - 1) * pageSize).params(params));
        // Gap fix h03-F10: lines and order numbers load once for the page (they were two queries per receipt).
        Map<String, List<Map<String, Object>>> linesByReceipt = new HashMap<>();
        Map<String, String> orderNumbers = new HashMap<>();
        if (!data.isEmpty()) {
            String[] receiptIds = data.stream().map(g -> (String) g.get("id")).toArray(String[]::new);
            for (Map<String, Object> l : SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM"
                    + " \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ANY(?::text[]) AND \"organizationId\" = ?"
                    + " ORDER BY \"goodsReceiptId\", \"lineNumber\", id").params(receiptIds, orgId))) {
                linesByReceipt.computeIfAbsent((String) l.get("goodsReceiptId"), k -> new ArrayList<>()).add(l);
            }
            String[] orderIds = data.stream().map(g -> (String) g.get("purchaseOrderId")).filter(o -> o != null)
                    .distinct().toArray(String[]::new);
            if (orderIds.length > 0) {
                for (Map<String, Object> o : SqlRows.list(jdbc.sql("SELECT id, \"orderNumber\" FROM \"PurchaseOrder\""
                        + " WHERE id = ANY(?::text[]) AND \"organizationId\" = ?").params(orderIds, orgId))) {
                    orderNumbers.put((String) o.get("id"), (String) o.get("orderNumber"));
                }
            }
        }
        for (Map<String, Object> g : data) {
            g.put("lines", linesByReceipt.getOrDefault((String) g.get("id"), new ArrayList<>()));
            Object orderId = g.get("purchaseOrderId");
            g.put("order", orderId == null || !orderNumbers.containsKey(orderId) ? null
                    : new LinkedHashMap<>(Map.of("orderNumber", orderNumbers.get(orderId))));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" g WHERE" + where).params(params)
                .query(Long.class).single();
        return Obj.of("data", data, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    /**
     * Cancel a DRAFT receipt that has no stock / GL effect yet (additive API, no route; used by the fleet weighbridge
     * when a ticket that pre-filled the draft is voided). Posted receipts are reversed with {@link #reverse} instead.
     * Idempotent: an already cancelled draft is returned unchanged.
     */
    public Map<String, Object> cancelDraft(String id, String reason) {
        String orgId = org();
        Map<String, Object> grn = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("SELECT * FROM \"GoodsReceiptNote\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL FOR UPDATE").params(id, orgId));
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if ("cancelled".equals(String.valueOf(grn.get("status")))) {
            return grn;
        }
        if (!"draft".equals(String.valueOf(grn.get("status")))) {
            throw ApiException.badRequest("Only draft goods receipts can be cancelled (" + grn.get("receiptNumber")
                    + " is " + grn.get("status") + ")");
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (qcGate != null && org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            qcGate.cancelFor("goods_receipt", id, List.of(), why);
        }
        Map<String, Object> after = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("UPDATE \"GoodsReceiptNote\" SET"
                + " status = 'cancelled', notes = CASE WHEN ?::text IS NULL THEN notes ELSE concat_ws(E'\\n', notes,"
                + " 'Cancelled: ' || ?::text) END, \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? RETURNING *").params(why, why, user(), id, orgId));
        audit.recordInTx(AuditInput.of("GoodsReceiptNote", id, "cancel", Obj.of("status", "draft"),
                Obj.of("status", "cancelled", "reason", why)));
        return after;
    }

    /** Receipt with ordered lines (+ product), order, warehouse and partner; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        String orgId = org();
        Map<String, Object> grn = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("SELECT * FROM \"GoodsReceiptNote\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (grn == null) {
            return null;
        }
        List<Map<String, Object>> lines = SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM"
                + " \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? ORDER BY \"lineNumber\" ASC").param(id));
        grn.put("lines", lines);
        grn.put("order", grn.get("purchaseOrderId") == null ? null : SqlRows.one(PurchaseOrder.class,
                jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(grn.get("purchaseOrderId"), orgId)));
        grn.put("warehouse", grn.get("warehouseId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, code, name FROM"
                + " \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?").params(grn.get("warehouseId"), orgId)));
        List<String> productIds = lines.stream().map(l -> (String) l.get("productId"))
                .filter(p -> p != null && !p.isEmpty()).toList();
        Map<String, Map<String, Object>> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Product\" WHERE"
                    + " id = ANY(?::text[]) AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(productIds.toArray(String[]::new), orgId))) {
                products.put((String) p.get("id"), p);
            }
        }
        for (Map<String, Object> l : lines) {
            String pid = (String) l.get("productId");
            l.put("product", pid != null && !pid.isEmpty() ? products.get(pid) : null);
        }
        Object partnerId = grn.get("partnerId");
        grn.put("partner", partnerId == null || ((String) partnerId).isEmpty() ? null : SqlRows.one(jdbc.sql("SELECT id,"
                + " name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(partnerId, orgId)));
        return grn;
    }
}
