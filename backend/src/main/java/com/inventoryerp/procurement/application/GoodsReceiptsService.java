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
    public Map<String, Object> createAdhoc(Map<String, Object> input) {
        if (lines(input).isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        Map<String, Object> draft = createDraft(input);
        if (!TenantContext.has("goods_receipt:post")) {
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
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        String receiptNumber = sequence.next("grn:" + year, "GRN-" + year + "-", 5);
        Map<String, Object> grn = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Object receivedAt = input.get("receivedAt");
            boolean hasReceivedAt = ProcurementSql.truthy(receivedAt);
            List<Object> params = new ArrayList<>(List.of(id, orgId, receiptNumber));
            params.add(stringOrNull(input.get("purchaseOrderId")));
            params.add(stringOrNull(input.get("partnerId")));
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
                // ADR-S058: the PO line's variant wins; a free receipt line names its own.
                String variantId = poLine != null && poLine.get("variantId") != null ? (String) poLine.get("variantId")
                        : stringOrNull(ln.get("variantId"));
                ProductVariantService.VariantRef variant = variants.resolve(stringOrNull(ln.get("productId")), variantId);
                LineUnit unit = purchaseOrders.receiptUnit(poLine, stringOrNull(ln.get("productId")),
                        stringOrNull(ln.get("uomId")));
                created.add(SqlRows.one(GoodsReceiptLine.class, jdbc.sql("INSERT INTO \"GoodsReceiptLine\" (id,"
                                + " \"organizationId\", \"goodsReceiptId\", \"purchaseOrderLineId\", \"productId\","
                                + " description, quantity, \"unitCost\", \"batchNumber\", \"expiryDate\", notes, \"lineNumber\","
                                + " \"uomId\", \"uomFactor\", \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, stringOrNull(ln.get("purchaseOrderLineId")),
                                stringOrNull(ln.get("productId")), requiredString(ln.get("description")),
                                requiredDecimal(ln.get("quantity")),
                                ln.get("unitCost") == null ? BigDecimal.ZERO : requiredDecimal(ln.get("unitCost")),
                                stringOrNull(ln.get("batchNumber")),
                                ProcurementSql.truthy(expiry) ? SqlRows.ts(EntityPatch.toInstant(String.valueOf(expiry)))
                                        : null,
                                stringOrNull(ln.get("notes")), ++idx, unit.uomId(), unit.factor(),
                                variant == null ? null : variant.id())));
            }
            row.put("lines", created);
            return row;
        });
        audit.record(AuditInput.of("GoodsReceiptNote", (String) grn.get("id"), "create", null,
                Obj.of("receiptNumber", receiptNumber, "lines", lines.size(), "status", "draft")));
        return grn;
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
            Map<String, Map<String, Object>> poLineById = new HashMap<>();
            if (grn.get("purchaseOrderId") != null) {
                List<PurchaseOrdersService.ReceiptLine> applied = new ArrayList<>();
                for (Map<String, Object> l : updatedLines) {
                    applied.add(new PurchaseOrdersService.ReceiptLine((String) l.get("purchaseOrderLineId"),
                            (String) l.get("productId"), Money.dec(l.get("quantity")),
                            (String) l.get("description")));
                }
                purchaseOrders.applyReceiptToPO((String) grn.get("purchaseOrderId"), applied);
                po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(grn.get("purchaseOrderId"), orgId));
                if (po != null) {
                    for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                            + " \"purchaseOrderId\" = ?").param(po.get("id")))) {
                        poLineById.put((String) l.get("id"), l);
                    }
                }
            }

            Instant date = grn.get("receivedAt") != null ? (Instant) grn.get("receivedAt")
                    : Instant.now().truncatedTo(ChronoUnit.MILLIS);
            BigDecimal receiptNet = Money.ZERO;
            BigDecimal receiptTax = Money.ZERO;
            // P0-5: a PO-backed receipt is priced in the PO currency; stock / GRNI / AP carry the functional value.
            BigDecimal rate = po != null ? purchaseOrders.functionalRate(po) : BigDecimal.ONE;
            for (Map<String, Object> ln : updatedLines) {
                if (ln.get("productId") == null || ((String) ln.get("productId")).isEmpty()) {
                    continue;
                }
                Map<String, Object> product = purchaseOrders.product((String) ln.get("productId"), orgId);
                if (product == null || !PurchaseOrdersService.stocked(product)) {
                    continue;
                }
                BigDecimal unitCost = Money.dec(ln.get("unitCost"));
                StockMove move = StockMove.of((String) ln.get("productId"), (String) grn.get("warehouseId"),
                                Money.dec(ln.get("quantity"))).variant((String) ln.get("variantId"))
                        .unitCost(unitCost.signum() > 0 ? unitCost.multiply(rate) : Money.dec(product.get("costPrice")))
                        .uom(PurchaseOrdersService.stockUnit(new LineUnit((String) ln.get("uomId"),
                                ln.get("uomFactor") == null ? null : Money.dec(ln.get("uomFactor"))), product),
                                ln.get("uomFactor") == null ? null : Money.dec(ln.get("uomFactor")))
                        .batch((String) ln.get("batchNumber"))
                        .expiry(ln.get("expiryDate") == null ? null : ln.get("expiryDate").toString())
                        .reference("GRN " + grn.get("receiptNumber"));
                Received received = stock.receiveForDocument(move, GlReceiptContext.grni("goods_receipt", id, date));
                BigDecimal lineNet = Money.dec(received.actualValue());
                receiptNet = receiptNet.add(lineNet);
                if (po != null) {
                    Map<String, Object> poLine = ln.get("purchaseOrderLineId") == null ? null
                            : poLineById.get((String) ln.get("purchaseOrderLineId"));
                    BigDecimal taxRate = poLine != null ? Money.dec(poLine.get("taxRate")) : Money.ZERO;
                    receiptTax = receiptTax.add(Money.div(lineNet.multiply(taxRate), BigDecimal.valueOf(100)));
                }
            }
            // Voucher only on the simple PO flow; ad-hoc receipts leave GRNI open for a later vendor bill.
            if (po != null) {
                stockPosting.postReceiptVoucher((String) po.get("partnerId"), receiptNet, receiptTax, date,
                        "goods_receipt", id, "Goods received " + grn.get("receiptNumber") + " · PO " + po.get("orderNumber"),
                        null);
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
        long matched = jdbc.sql("SELECT count(*) FROM \"VendorBillReceiptMatch\" WHERE \"organizationId\" = ?"
                        + " AND \"goodsReceiptLineId\" = ANY(?::text[])")
                .params(orgId, lineIds.toArray(String[]::new)).query(Long.class).single();
        if (matched > 0) {
            throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " is matched to a vendor bill —"
                    + " reverse the bill or raise a debit note instead");
        }
        Object poId = grn.get("purchaseOrderId");
        if (poId != null) {
            long links = jdbc.sql("SELECT count(*) FROM \"VendorBillLink\" WHERE \"organizationId\" = ?"
                    + " AND \"purchaseOrderId\" = ?").params(orgId, poId).query(Long.class).single();
            long payments = jdbc.sql("SELECT count(*) FROM \"PurchasePayment\" WHERE \"organizationId\" = ?"
                    + " AND \"purchaseOrderId\" = ?").params(orgId, poId).query(Long.class).single();
            if (links > 0 || payments > 0) {
                throw ApiException.badRequest("The purchase order for " + grn.get("receiptNumber") + " already has "
                        + (links > 0 ? "a vendor bill" : "payments") + " — return the goods with a debit note instead");
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
            boolean hasStock = grnLines.stream().anyMatch(l -> l.get("productId") != null
                    && !((String) l.get("productId")).isEmpty());
            if (hasStock) {
                reversals.reverseLedgerSource(new LedgerSource("goods_receipt", id, "goods_receipt_reversal", id,
                        List.of("goods_receipt"), "Reversal of GRN " + grn.get("receiptNumber") + ": " + why, null));
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
                        jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"receivedQuantity\" = GREATEST(0, \"receivedQuantity\""
                                        + " - ?), version = version + 1 WHERE id = ? AND \"organizationId\" = ?")
                                .params(ln.get("quantity"), poLineId, orgId).update();
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
        for (Map<String, Object> g : data) {
            g.put("lines", SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM \"GoodsReceiptLine\" WHERE"
                    + " \"goodsReceiptId\" = ?").param(g.get("id"))));
            g.put("order", g.get("purchaseOrderId") == null ? null : SqlRows.one(jdbc.sql("SELECT \"orderNumber\" FROM"
                    + " \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?").params(g.get("purchaseOrderId"), orgId)));
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
