package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ProductionRequestService.RaisedLine;
import com.inventoryerp.procurement.domain.PurchaseRequest;
import com.inventoryerp.procurement.domain.PurchaseRequestLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MRP-lite (port of {@code mrp.service.ts}): wholesale documents and min-stock breaches raise production requests;
 * exploding open production orders' material needs against on-hand + open POs emits a DRAFT purchase request for the
 * shortfall. Only ever creates draft documents for humans to act on.
 */
@Service
public class MrpService {

    private final MfgDb db;
    private final SequenceService seq;
    private final BomService boms;
    private final ProductionRequestService requests;

    public MrpService(MfgDb db, SequenceService seq, BomService boms, ProductionRequestService requests) {
        this.db = db;
        this.seq = seq;
        this.boms = boms;
        this.requests = requests;
    }

    /** One request line per document product that has an active BOM (bought products are skipped). */
    @Transactional
    public Map<String, Object> raiseFromDocument(String documentId) {
        Map<String, Object> doc = SqlRows.one(db.jdbc().sql("SELECT id, \"documentNumber\", \"dueDate\" FROM \"Document\""
                + " WHERE id = ? AND \"organizationId\" = ? LIMIT 1").params(documentId, MfgDb.org()));
        if (doc == null) {
            throw com.inventoryerp.shared.web.ApiException.notFound("Document not found");
        }
        Instant dueDate = doc.get("dueDate") instanceof Instant i ? i : null;
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT \"productId\", quantity FROM \"DocumentLine\""
                + " WHERE \"documentId\" = ? AND \"organizationId\" = ?").params(documentId, MfgDb.org()));
        List<RaisedLine> reqLines = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") == null) {
                continue;
            }
            Map<String, Object> bom = boms.findActive(str(l, "productId"), "");
            if (bom == null) {
                continue;
            }
            reqLines.add(RaisedLine.of(str(l, "productId"), dec(l, "quantity"), str(bom, "id"), dueDate));
        }
        if (reqLines.isEmpty()) {
            return Obj.of("raised", false, "reason", "No manufactured products on this document.");
        }
        Map<String, Object> req = requests.raiseRequest("document", str(doc, "id"), dueDate,
                "From " + doc.get("documentNumber"), reqLines);
        return Obj.of("raised", true, "request", req);
    }

    /** A production request topping every below-minimum finished good (with an active BOM) back up to max/min. */
    @Transactional
    public Map<String, Object> scanMinStock() {
        List<Map<String, Object>> products = SqlRows.list(db.jdbc().sql("SELECT id, name, \"minQuantity\","
                + " \"maxQuantity\" FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"manufacturingRole\" = 'finished' AND \"minQuantity\" > 0 AND \"isActive\" = true")
                .param(MfgDb.org()));
        List<RaisedLine> lines = new ArrayList<>();
        for (Map<String, Object> p : products) {
            Map<String, Object> bom = boms.findActive(str(p, "id"), "");
            if (bom == null) {
                continue;
            }
            BigDecimal onHand = onHand(str(p, "id"));
            BigDecimal min = dec(p, "minQuantity");
            if (onHand.compareTo(min) >= 0) {
                continue;
            }
            BigDecimal target = dec(p, "maxQuantity").compareTo(min) > 0 ? dec(p, "maxQuantity") : min;
            BigDecimal qty = target.subtract(onHand);
            if (qty.signum() <= 0) {
                continue;
            }
            lines.add(RaisedLine.of(str(p, "id"), qty, str(bom, "id"), null));
        }
        if (lines.isEmpty()) {
            return Obj.of("raised", false, "reason", "Nothing below minimum.");
        }
        Map<String, Object> req = requests.raiseRequest("min_stock", "scan", null, null, lines);
        return Obj.of("raised", true, "request", req, "lineCount", lines.size());
    }

    /**
     * Net the material requirement of open (draft/confirmed) production orders against on-hand + open purchase-order
     * quantities and raise a DRAFT purchase request for the shortfall.
     */
    @Transactional
    public Map<String, Object> runMrp() {
        List<Map<String, Object>> orders = SqlRows.list(db.jdbc().sql("SELECT id, \"scheduledFor\" FROM"
                + " \"ProductionOrder\" WHERE \"organizationId\" = ? AND status IN ('draft', 'confirmed')")
                .param(MfgDb.org()));
        Map<String, BigDecimal> required = new LinkedHashMap<>();
        Instant earliest = null;
        for (Map<String, Object> o : orders) {
            if (o.get("scheduledFor") instanceof Instant s && (earliest == null || s.isBefore(earliest))) {
                earliest = s;
            }
            for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT \"productId\", \"qtyPlanned\","
                    + " \"qtyConsumed\", \"uomId\" FROM \"ProductionMaterial\" WHERE \"orderId\" = ?").param(o.get("id")))) {
                BigDecimal need = dec(m, "qtyPlanned").subtract(dec(m, "qtyConsumed"));
                if (need.signum() <= 0) {
                    continue;
                }
                required.merge(str(m, "productId"), need, BigDecimal::add);
            }
        }
        if (required.isEmpty()) {
            return Obj.of("raised", false, "reason", "No open material requirements.");
        }

        Map<String, BigDecimal> openPo = openPurchaseQuantities(new ArrayList<>(required.keySet()));
        List<Map<String, Object>> shortLines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : required.entrySet()) {
            String productId = e.getKey();
            BigDecimal available = onHand(productId).add(openPo.getOrDefault(productId, BigDecimal.ZERO));
            BigDecimal shortQty = e.getValue().subtract(available);
            if (shortQty.signum() <= 0) {
                continue;
            }
            Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT \"purchaseUomId\", \"uomId\" FROM"
                    + " \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1")
                    .params(productId, MfgDb.org()));
            Object uomId = product == null ? null
                    : product.get("purchaseUomId") != null ? product.get("purchaseUomId") : product.get("uomId");
            shortLines.add(Obj.of("productId", productId, "description", "MRP shortfall", "quantity", shortQty,
                    "unitOfMeasureId", uomId));
        }
        if (shortLines.isEmpty()) {
            return Obj.of("raised", false, "reason", "On-hand + open POs cover all requirements.");
        }

        String requestNumber = seq.next("purchase_request", "MRPPR-", 5);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNumber", requestNumber);
        data.put("requestedById", TenantContext.userId());
        data.put("description", "Auto-generated from production MRP");
        data.put("neededBy", earliest);
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> pr = db.insert(PurchaseRequest.class, data);
        for (int i = 0; i < shortLines.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>(shortLines.get(i));
            line.put("purchaseRequestId", pr.get("id"));
            line.put("lineNumber", i);
            db.insert(PurchaseRequestLine.class, line);
        }
        pr.put("lines", db.children(PurchaseRequestLine.class, "purchaseRequestId", pr.get("id"), null));
        return Obj.of("raised", true, "purchaseRequest", pr, "lineCount", shortLines.size());
    }

    /** {@code stockItem.aggregate({_sum: {quantity}})} for a product across every location. */
    private BigDecimal onHand(String productId) {
        BigDecimal sum = db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?").params(MfgDb.org(), productId).query(BigDecimal.class).single();
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /** Sum of not-yet-received purchase-order quantities per product. */
    private Map<String, BigDecimal> openPurchaseQuantities(List<String> productIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", l.quantity FROM"
                        + " \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\""
                        + " WHERE l.\"organizationId\" = :org AND l.\"productId\" IN (:ids)"
                        + " AND o.status IN ('draft', 'approved', 'active', 'sent', 'partially_received')")
                .param("org", MfgDb.org()).param("ids", productIds))) {
            if (l.get("productId") == null) {
                continue;
            }
            out.merge(str(l, "productId"), dec(l, "quantity"), BigDecimal::add);
        }
        return out;
    }
}
