package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockReservationService.Reserve;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.inventory.domain.StockItem;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.BomService.ExplodedBom;
import com.inventoryerp.manufacturing.application.BomService.ExplodedMaterial;
import com.inventoryerp.manufacturing.application.MfgCommands.CancelOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOutputInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.MaterialInput;
import com.inventoryerp.manufacturing.application.MfgCommands.OutputInput;
import com.inventoryerp.manufacturing.application.MfgCommands.RecordQc;
import com.inventoryerp.manufacturing.application.MfgCommands.StartMaterialInput;
import com.inventoryerp.manufacturing.application.MfgCommands.StartOrder;
import com.inventoryerp.manufacturing.domain.ProductionCostComponent;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOutput;
import com.inventoryerp.manufacturing.domain.ProductionQcCheck;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.pos.domain.MenuProduct;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production order engine (port of {@code production.service.ts}):
 * create → confirm (soft reservations) → start (consume leg, Dr WIP / Cr Stock Valuation) → complete (output leg,
 * Dr Stock Valuation / Cr WIP, overhead absorbed first) → optional QC → cancel / reverse. With overhead = 0 the WIP
 * debit at start equals the credit at complete, so a completed order leaves WIP at exactly zero.
 *
 * <p>Nest runs the approval gate, reservations and the QC quarantine location outside its transactions; those parts
 * run in their own committed transactions here too.
 */
@Service
public class ProductionService {

    private static final Logger log = LoggerFactory.getLogger(ProductionService.class);
    static final String SOURCE = "production_order";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final java.time.format.DateTimeFormatter ISO_MS = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final StockReservationService reservations;
    private final UomConversionService uom;
    private final PosStockLocation posStockLocation;
    private final BomService boms;
    private final QualityService quality;
    private final CostingService costing;
    private final StandardCostService standards;
    private final TransactionTemplate newTx;

    public ProductionService(MfgDb db, SequenceService seq, AuditService audit, ApprovalsService approvals,
            StockService stock, StockPostingService stockPosting, StockReservationService reservations,
            UomConversionService uom, PosStockLocation posStockLocation, BomService boms,
            QualityService quality, CostingService costing, StandardCostService standards,
            PlatformTransactionManager txm) {
        this.standards = standards;
        this.quality = quality;
        this.costing = costing;
        this.db = db;
        this.seq = seq;
        this.audit = audit;
        this.approvals = approvals;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.reservations = reservations;
        this.uom = uom;
        this.posStockLocation = posStockLocation;
        this.boms = boms;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record MaterialPlan(String productId, String variantId, BigDecimal qtyPlanned, String uomId,
            String distStrategy, String batchNumber, int sequence) {}

    // ================================================================== create

    /** Create a DRAFT order from a BOM (named, default or sole active) or ad hoc from material lines. */
    @Transactional
    public Map<String, Object> createOrder(CreateOrder dto) {
        boolean adHoc = !has(dto.bomId()) && has(dto.outputProductId())
                && dto.materials() != null && !dto.materials().isEmpty();

        String bomId = null;
        Integer bomVersion = null;
        String outputProductId;
        String outputVariantId;
        String outputUomId;
        List<MaterialPlan> materials = new ArrayList<>();
        BigDecimal expectedOutputQty;
        BigDecimal plannedQty;
        BigDecimal overheadForOrder = BigDecimal.ZERO;
        String defLocationId = null;
        String defOutputLocationId = null;
        Integer shelfLifeDays = null;
        Boolean qcRequired = null;

        if (adHoc) {
            outputProductId = dto.outputProductId();
            outputVariantId = dto.outputVariantId();
            outputUomId = null;
            if (dto.plannedQty() == null) {
                throw ApiException.badRequest("plannedQty is required for an ad-hoc production order.");
            }
            plannedQty = dto.plannedQty();
            expectedOutputQty = plannedQty;
            for (int i = 0; i < dto.materials().size(); i++) {
                MaterialInput m = dto.materials().get(i);
                materials.add(new MaterialPlan(m.productId(), m.variantId(), m.quantity(), m.uomId(),
                        m.distStrategy() != null ? m.distStrategy() : "FEFO", m.batchNumber(), i));
            }
        } else {
            if (!has(dto.bomId()) && !has(dto.outputProductId())) {
                throw ApiException.badRequest("Provide a bomId or an outputProductId.");
            }
            Map<String, Object> bom = boms.resolveForOrder(dto.outputProductId() != null ? dto.outputProductId() : "",
                    dto.outputVariantId(), dto.bomId());
            bomId = str(bom, "id");
            bomVersion = (Integer) bom.get("version");
            outputProductId = str(bom, "outputProductId");
            outputVariantId = str(bom, "outputVariantId");
            outputUomId = str(bom, "outputUomId");
            defLocationId = str(bom, "defaultLocationId");
            defOutputLocationId = str(bom, "defaultOutputLocationId");
            shelfLifeDays = (Integer) bom.get("shelfLifeDays");
            qcRequired = (Boolean) bom.get("qcRequired");
            if (dto.plannedQty() != null) {
                plannedQty = dto.plannedQty();
            } else if (dto.runs() != null) {
                plannedQty = BomService.mul(dec(bom, "outputQuantity"), dto.runs());
            } else {
                throw ApiException.badRequest("Provide runs or plannedQty.");
            }
            ExplodedBom exploded = BomService.explode(BomService.explodable(bom), plannedQty);
            expectedOutputQty = exploded.expectedOutputQty();
            // BOM overhead is per run (per outputQuantity) — scale it to the order.
            overheadForOrder = BomService.mul(dec(bom, "overheadCost"),
                    Money.div(plannedQty, dec(bom, "outputQuantity")));
            for (ExplodedMaterial m : exploded.materials()) {
                materials.add(new MaterialPlan(m.componentProductId(), m.componentVariantId(), m.qtyPlanned(),
                        m.uomId(), "FEFO", null, m.sequence()));
            }
        }

        String locationId = dto.locationId() != null ? dto.locationId() : defLocationId;
        if (!has(locationId)) {
            throw ApiException.badRequest("No production location: pass locationId or set the BOM default.");
        }
        requireLocation(locationId);
        String outputLocationId = dto.outputLocationId() != null ? dto.outputLocationId()
                : defOutputLocationId != null ? defOutputLocationId : resolvePosWarehouse();

        // ADR-S006 A7: unknown references answer 404 instead of a foreign-key 500.
        getProduct(outputProductId);
        for (MaterialPlan m : materials) {
            getProduct(m.productId());
        }
        if (dto.outputs() != null) {
            dto.outputs().forEach(o -> getProduct(o.productId()));
        }
        requireLocation(outputLocationId);

        // Shelf-life → default expiry (overridable at complete).
        Instant mfgDate = date(dto.mfgDate());
        Instant expiryDate = date(dto.expiryDate());
        if (expiryDate == null && shelfLifeDays != null) {
            Instant base = mfgDate != null ? mfgDate : Instant.now();
            expiryDate = base.plusMillis(shelfLifeDays * 86_400_000L);
        }

        List<String> ids = new ArrayList<>(List.of(outputProductId));
        materials.forEach(m -> ids.add(m.productId()));
        if (dto.outputs() != null) {
            dto.outputs().forEach(o -> ids.add(o.productId()));
        }
        Map<String, String> names = productNames(ids);
        String orderCode = seq.next("production_order", "MO-", 5);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode);
        data.put("bomId", bomId);
        data.put("bomVersion", bomVersion);
        data.put("qcRequired", qcRequired != null ? qcRequired : false);
        data.put("outputProductId", outputProductId);
        data.put("outputVariantId", outputVariantId);
        data.put("locationId", locationId);
        data.put("outputLocationId", outputLocationId);
        data.put("status", "draft");
        data.put("plannedQty", plannedQty);
        data.put("overheadCost", overheadForOrder);
        data.put("uomId", outputUomId);
        data.put("scheduledFor", date(dto.scheduledFor()));
        data.put("batchNumber", dto.batchNumber());
        data.put("expiryDate", expiryDate);
        data.put("mfgDate", mfgDate);
        data.put("notes", dto.notes());
        data.put("performedById", TenantContext.userId());
        data.put("createdBy", TenantContext.userId());
        // ADR-S006 D: the BOM's current standard cost, for variance analysis at completion.
        data.put("standardCostId", costing.currentStandardId(bomId));
        Map<String, Object> created = db.insert(ProductionOrder.class, data);
        String id = str(created, "id");

        for (MaterialPlan m : materials) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", id);
            row.put("productId", m.productId());
            row.put("variantId", m.variantId());
            row.put("productName", names.getOrDefault(m.productId(), "Material"));
            row.put("qtyPlanned", m.qtyPlanned());
            row.put("uomId", m.uomId());
            row.put("distStrategy", m.distStrategy());
            row.put("batchNumber", m.batchNumber());
            row.put("sequence", m.sequence());
            db.insert(ProductionMaterial.class, row);
        }
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("orderId", id);
        main.put("kind", "output");
        main.put("productId", outputProductId);
        main.put("variantId", outputVariantId);
        main.put("productName", names.getOrDefault(outputProductId, "Output"));
        main.put("qtyExpected", expectedOutputQty);
        main.put("uomId", outputUomId);
        db.insert(ProductionOutput.class, main);
        if (dto.outputs() != null) {
            for (OutputInput o : dto.outputs()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", id);
                row.put("kind", o.kind() != null ? o.kind() : "byproduct");
                row.put("productId", o.productId());
                row.put("variantId", o.variantId());
                row.put("productName", names.getOrDefault(o.productId(), "Byproduct"));
                row.put("qtyExpected", o.qtyExpected());
                row.put("uomId", o.uomId());
                row.put("costAllocationPct", o.costAllocationPct() != null ? o.costAllocationPct() : BigDecimal.ZERO);
                db.insert(ProductionOutput.class, row);
            }
        }
        Map<String, Object> out = withLines(created, true, true);

        // Fixed BOM overhead becomes a cost component up front; labour/machine ones come from work orders.
        if (overheadForOrder.signum() > 0) {
            db.insert(ProductionCostComponent.class, Obj.of("orderId", id, "kind", "overhead", "amount",
                    overheadForOrder, "sourceType", SOURCE, "sourceId", orderCode, "notes", "BOM overhead"));
        }
        return out;
    }

    // ================================================================== confirm

    /** Soft ATP reservation of every material; a reservation failure never blocks the confirm. */
    @Transactional
    public Map<String, Object> confirmOrder(String id) {
        Map<String, Object> order = getOrThrow(id, true, false);
        if ("confirmed".equals(order.get("status"))) {
            return order;
        }
        if (!"draft".equals(order.get("status"))) {
            throw ApiException.badRequest("Cannot confirm a " + order.get("status") + " order.");
        }
        for (Map<String, Object> m : materials(order)) {
            try {
                newTx.executeWithoutResult(s -> reservations.reserve(new Reserve(str(m, "productId"),
                        str(m, "variantId"), str(order, "locationId"), dec(m, "qtyPlanned"), SOURCE, id,
                        str(order, "orderCode"))));
            } catch (RuntimeException e) {
                log.warn("reserve failed for {} / {}: {}", order.get("orderCode"), m.get("productId"), e.getMessage());
            }
        }
        return withLines(db.update(ProductionOrder.class, id, Obj.of("status", "confirmed", "updatedBy",
                TenantContext.userId())), true, true);
    }

    // ================================================================== start (consume leg)

    /** Consume the materials into WIP (Dr WIP / Cr Stock Valuation) behind the approval gate. */
    @Transactional
    public Map<String, Object> startOrder(String id, StartOrder dto) {
        StartOrder d = dto != null ? dto : new StartOrder(null, null, null);
        Map<String, Object> pre = getOrThrow(id, true, false);
        refuseIfMultiStage(pre, false);
        if (!"confirmed".equals(pre.get("status")) || pre.get("consumedAt") != null) {
            throw ApiException.badRequest("Order " + pre.get("orderCode") + " is not in a startable state (status "
                    + pre.get("status") + ").");
        }
        BigDecimal estValue = estimateMaterialValue(pre);
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(estValue), "lines", materials(pre).size(),
                "orderCode", pre.get("orderCode"));
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval(SOURCE, id, snapshot));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before starting " + pre.get("orderCode")
                    + ". Pending request " + gate.get("requestId") + ".");
        }
        Map<String, StartMaterialInput> overrides = new HashMap<>();
        if (d.materials() != null) {
            d.materials().forEach(m -> overrides.put(m.lineId(), m));
        }
        Instant date = d.date() != null ? EntityPatch.toInstant(d.date()) : Instant.now();

        lock(id);
        Map<String, Object> order = getOrNull(id, true, false);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        if (!"confirmed".equals(order.get("status")) || order.get("consumedAt") != null) {
            throw ApiException.badRequest("Order already started.");
        }
        String code = str(order, "orderCode");
        BigDecimal materialCost = BigDecimal.ZERO;
        for (Map<String, Object> line : materials(order)) {
            StartMaterialInput ov = overrides.get(str(line, "id"));
            BigDecimal qty = ov != null ? ov.qtyConsumed() : dec(line, "qtyPlanned");
            if (qty.signum() <= 0) {
                continue;
            }
            String batchNumber = ov != null && ov.batchNumber() != null ? ov.batchNumber() : str(line, "batchNumber");
            String productId = str(line, "productId");
            Map<String, Object> substitute = null;
            if (ov != null && ov.substituteProductId() != null && !ov.substituteProductId().equals(productId)) {
                // ADR-S006 D: an approved BOM substitute, ratio units per component unit.
                substitute = costing.substituteFor(str(order, "bomId"), productId, ov.substituteProductId());
                if (substitute == null) {
                    throw ApiException.badRequest("Product " + ov.substituteProductId()
                            + " is not an approved substitute for " + line.get("productName") + ".");
                }
                productId = ov.substituteProductId();
                qty = BomService.mul(qty, dec(substitute, "ratio"));
            }
            StockMove move = StockMove.of(productId, str(order, "locationId"), qty)
                    .variant(substitute != null ? null : str(line, "variantId"))
                    .uom(substitute != null ? null : str(line, "uomId")).moveType("production_consume")
                    .strategy(line.get("distStrategy") != null ? str(line, "distStrategy") : "FEFO")
                    .batch(batchNumber).date(date).source(SOURCE, code).notes(code);
            Issued res = stock.issue(move);
            materialCost = materialCost.add(res.totalValue());
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("qtyConsumed", qty);
            upd.put("unitCost", res.unitCost());
            upd.put("totalCost", res.totalValue());
            upd.put("ledgerCode", res.ledgerCode());
            upd.put("batchNumber", batchNumber);
            if (substitute != null) {
                upd.put("productId", productId);
                upd.put("variantId", null);
                upd.put("uomId", null);
                upd.put("productName", productNames(List.of(productId)).getOrDefault(productId, "Material"));
            }
            db.update(ProductionMaterial.class, str(line, "id"), upd);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "in_progress");
        data.put("startedAt", date);
        data.put("consumedAt", Instant.now());
        data.put("materialCost", materialCost);
        data.put("notes", d.notes() != null ? d.notes() : order.get("notes"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, id, data), true, true);
        audit.recordInTx(AuditInput.of("ProductionOrder", id, "issue", null,
                Obj.of("orderCode", code, "materialCost", JsNumbers.dec(materialCost))));
        return updated;
    }

    // ================================================================== complete (output leg)

    /**
     * Receive the outputs and clear WIP (Dr Stock Valuation / Cr WIP): byproducts absorb their % of the cost pool
     * first, the main output the rest divided by ACTUAL produced; a zero yield writes WIP off to scrap expense. A
     * QC-required order lands in quarantine ({@code qc_hold}).
     */
    @Transactional
    public Map<String, Object> completeOrder(String id, CompleteOrder dto) {
        Map<String, Object> pre = getOrThrow(id, false, true);
        refuseIfMultiStage(pre, false);
        if (!"in_progress".equals(pre.get("status")) || pre.get("postedAt") != null) {
            throw ApiException.badRequest("Order " + pre.get("orderCode") + " is not completable (status "
                    + pre.get("status") + ").");
        }
        Map<String, Object> outProduct = getProduct(str(pre, "outputProductId"));
        Instant date = dto.date() != null ? EntityPatch.toInstant(dto.date()) : Instant.now();
        Map<String, CompleteOutputInput> outputOverrides = new HashMap<>();
        if (dto.outputs() != null) {
            dto.outputs().forEach(o -> outputOverrides.put(o.lineId(), o));
        }
        String quarantineId = Boolean.TRUE.equals(pre.get("qcRequired")) ? resolveQuarantineLocation() : null;

        lock(id);
        Map<String, Object> order = getOrNull(id, false, true);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        if (!"in_progress".equals(order.get("status")) || order.get("postedAt") != null) {
            throw ApiException.badRequest("Order already completed.");
        }
        String code = str(order, "orderCode");
        BigDecimal materialCost = dec(order, "materialCost");
        BigDecimal overhead = dec(order, "overheadCost");

        // Zero-yield batch: materials are in WIP and gone — expense it (Dr Stock Adj Expense / Cr WIP).
        if (dto.qtyProduced() == null || dto.qtyProduced().signum() <= 0) {
            stockPosting.postProductionScrap(materialCost, date, SOURCE, code, "Production scrap · " + code);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "completed");
            data.put("producedQty", BigDecimal.ZERO);
            data.put("scrapQty", order.get("plannedQty"));
            data.put("outputUnitCost", BigDecimal.ZERO);
            data.put("totalCost", materialCost);
            data.put("completedAt", date);
            data.put("postedAt", Instant.now());
            data.put("notes", dto.notes() != null ? dto.notes() : order.get("notes"));
            data.put("updatedBy", TenantContext.userId());
            Map<String, Object> scrapped = withLines(db.update(ProductionOrder.class, id, data), true, true);
            reservations.release(SOURCE, id);
            audit.recordInTx(AuditInput.of("ProductionOrder", id, "post", null,
                    Obj.of("orderCode", code, "scrapped", JsNumbers.dec(materialCost))));
            return scrapped;
        }

        String receiveLocationId = quarantineId != null ? quarantineId
                : order.get("outputLocationId") != null ? str(order, "outputLocationId") : str(order, "locationId");
        BigDecimal pool = materialCost.add(overhead);
        BigDecimal byproductCredit = BigDecimal.ZERO;

        // Absorb overhead into WIP so the output credit (which includes it) still nets WIP to zero.
        if (overhead.signum() > 0) {
            stockPosting.postOverheadAbsorption(overhead, date, SOURCE, code, "Overhead · " + code);
        }

        List<Map<String, Object>> outputs = outputs(order);
        Map<String, Object> mainLine = outputs.stream().filter(o -> "output".equals(o.get("kind"))).findFirst()
                .orElse(null);

        // 1) Byproducts absorb their % of the pool first.
        for (Map<String, Object> bp : outputs) {
            if (!"byproduct".equals(bp.get("kind"))) {
                continue;
            }
            CompleteOutputInput ov = outputOverrides.get(str(bp, "id"));
            BigDecimal qtyProduced = ov != null ? ov.qtyProduced() : dec(bp, "qtyExpected");
            BigDecimal value = Money.div(BomService.mul(pool, dec(bp, "costAllocationPct")), HUNDRED);
            if (qtyProduced.signum() <= 0 || value.signum() <= 0) {
                continue;
            }
            Map<String, Object> bpProduct = getProduct(str(bp, "productId"));
            BigDecimal qtyBase = uom.toBase(qtyProduced, str(bp, "uomId"), str(bpProduct, "id"),
                    str(bpProduct, "uomId"), true);
            BigDecimal unitCost = qtyBase.signum() > 0 ? Money.div(value, qtyBase) : BigDecimal.ZERO;
            receiveOutput(str(bp, "productId"), str(bp, "variantId"), receiveLocationId, qtyBase, unitCost,
                    ov != null && ov.batchNumber() != null ? ov.batchNumber() : str(order, "batchNumber"),
                    ov != null && ov.expiryDate() != null ? ov.expiryDate() : iso(order.get("expiryDate")),
                    iso(order.get("mfgDate")), code, date, str(bp, "id"), value);
            pool = pool.subtract(value);
            byproductCredit = byproductCredit.add(value);
        }

        // 2) Main output absorbs the remaining pool; dividing by ACTUAL produced absorbs normal scrap.
        BigDecimal qtyProducedBase = uom.toBase(dto.qtyProduced(),
                dto.uomId() != null ? dto.uomId() : str(order, "uomId"), str(outProduct, "id"),
                str(outProduct, "uomId"), true);
        if (qtyProducedBase.signum() <= 0) {
            throw ApiException.badRequest("Produced quantity resolves to 0 in base units.");
        }
        // ADR-S008: a standard-governed output is received at its standard; the rest of WIP becomes variances.
        BigDecimal standardUnit = standards.activeStandard(str(order, "outputProductId")).orElse(null);
        BigDecimal outputUnitCost = standardUnit != null ? standardUnit : Money.div(pool, qtyProducedBase);
        BigDecimal outputValue = standardUnit != null
                ? standardUnit.multiply(qtyProducedBase).setScale(6, java.math.RoundingMode.HALF_UP) : pool;
        String mainBatch = receiveOutput(str(order, "outputProductId"), str(order, "outputVariantId"),
                receiveLocationId, qtyProducedBase, outputUnitCost,
                dto.batchNumber() != null ? dto.batchNumber() : str(order, "batchNumber"),
                dto.expiryDate() != null ? dto.expiryDate() : iso(order.get("expiryDate")),
                dto.mfgDate() != null ? dto.mfgDate() : iso(order.get("mfgDate")), code, date,
                mainLine == null ? null : str(mainLine, "id"), outputValue);

        BigDecimal expectedQty = mainLine != null ? dec(mainLine, "qtyExpected") : dec(order, "plannedQty");
        BigDecimal yieldVariance = BomService.mul(expectedQty.subtract(dto.qtyProduced()), outputUnitCost);
        BigDecimal totalCost = materialCost.add(overhead);
        boolean qc = Boolean.TRUE.equals(order.get("qcRequired"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", qc ? "qc_hold" : "completed");
        data.put("producedQty", dto.qtyProduced());
        data.put("scrapQty", dto.scrapQty() != null ? dto.scrapQty() : order.get("scrapQty"));
        data.put("byproductCredit", byproductCredit);
        data.put("totalCost", totalCost);
        data.put("outputUnitCost", outputUnitCost);
        data.put("yieldVariance", yieldVariance);
        data.put("batchNumber", mainBatch != null ? mainBatch : order.get("batchNumber"));
        data.put("completedAt", qc ? null : date);
        data.put("postedAt", Instant.now());
        data.put("notes", dto.notes() != null ? dto.notes() : order.get("notes"));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, id, data), true, true);

        db.insert(ProductionCostComponent.class, Obj.of("orderId", id, "kind", "material", "amount", materialCost,
                "sourceType", SOURCE, "sourceId", code));
        // Released after the fact (not consumed): the consume leg already moved real stock.
        reservations.release(SOURCE, id);
        costing.recordVariances(id);
        if (standardUnit != null) {
            String varianceJe = standards.postProductionVariances(id, code, str(order, "outputProductId"),
                    pool.subtract(outputValue), qtyProducedBase, outputValue, stockPosting.wipAccount(), date);
            if (varianceJe != null) {
                db.jdbc().sql("UPDATE \"ProductionOrder\" SET \"varianceJournalEntryId\" = ? WHERE id = ?")
                        .params(varianceJe, id).update();
                updated.put("varianceJournalEntryId", varianceJe);
            }
        }
        audit.recordInTx(AuditInput.of("ProductionOrder", id, "receive", null, Obj.of("orderCode", code,
                "producedQty", JsNumbers.dec(dto.qtyProduced()), "outputUnitCost", JsNumbers.dec(outputUnitCost))));
        return updated;
    }

    // ================================================================== QC

    /** Clear a qc_hold order: passed units leave quarantine for the sale location, failed units are wasted. */
    @Transactional
    public Map<String, Object> recordQc(String id, RecordQc dto) {
        Map<String, Object> pre = getOrThrow(id, false, false);
        refuseIfMultiStage(pre, false);
        if (!"qc_hold".equals(pre.get("status"))) {
            throw ApiException.badRequest("Order " + pre.get("orderCode") + " is not awaiting QC (status "
                    + pre.get("status") + ").");
        }
        // ADR-S006 C: reason codes from the scrap-reason master; checklist against the order's QC plan.
        quality.validateReason(dto.wasteCategory());
        quality.validateChecklist(pre, dto.checklist());
        String quarantineId = resolveQuarantineLocation();
        String destinationId = pre.get("outputLocationId") != null ? str(pre, "outputLocationId")
                : str(pre, "locationId");
        Instant date = Instant.now();
        BigDecimal passed = dto.passedQty();
        BigDecimal failed = dto.failedQty() != null ? dto.failedQty() : BigDecimal.ZERO;
        BigDecimal rework = dto.reworkQty() != null ? dto.reworkQty() : BigDecimal.ZERO;

        lock(id);
        Map<String, Object> order = db.byId(ProductionOrder.class, id);
        if (order == null || !"qc_hold".equals(order.get("status"))) {
            throw ApiException.badRequest("Order is not awaiting QC.");
        }
        String code = str(order, "orderCode");
        if (passed.signum() > 0) {
            stock.transfer(StockMove.transfer(str(order, "outputProductId"), quarantineId, destinationId, passed)
                    .variant(str(order, "outputVariantId")).source("production_qc", code).notes("QC pass " + code));
        }
        if (failed.signum() > 0) {
            stock.issue(StockMove.of(str(order, "outputProductId"), quarantineId, failed)
                    .variant(str(order, "outputVariantId")).moveType("waste").source("production_qc", code)
                    .notes("QC fail " + code + " · " + (dto.wasteCategory() != null ? dto.wasteCategory()
                            : "qc_rejection")));
        }
        String status = has(dto.wasteCategory()) && failed.signum() > 0 && passed.signum() <= 0 ? "failed"
                : failed.signum() > 0 ? "rework" : "passed";
        Map<String, Object> qc = new LinkedHashMap<>();
        qc.put("orderId", id);
        qc.put("status", status);
        qc.put("checklist", new MfgDb.Json(dto.checklist() != null ? dto.checklist() : List.of()));
        qc.put("passedQty", passed);
        qc.put("failedQty", failed);
        qc.put("reworkQty", rework);
        qc.put("wasteCategory", dto.wasteCategory());
        qc.put("inspectorId", TenantContext.userId());
        qc.put("notes", dto.notes());
        qc.put("decidedAt", date);
        db.insert(ProductionQcCheck.class, qc);

        // Rework keeps the order in quarantine; otherwise it's done.
        boolean done = rework.signum() <= 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", done ? "completed" : "qc_hold");
        data.put("completedAt", done ? date : null);
        data.put("scrapQty", dec(order, "scrapQty").add(failed));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, id, data), true, true);
        audit.recordInTx(AuditInput.of("ProductionOrder", id, "reconcile", null, Obj.of("orderCode", code,
                "passed", JsNumbers.dec(passed), "failed", JsNumbers.dec(failed))));
        return updated;
    }

    // ================================================================== cancel

    /** Cancel a draft/confirmed order (flip) or an in-progress one (materials back to stock, WIP cleared). */
    @Transactional
    public Map<String, Object> cancelOrder(String id, CancelOrder dto) {
        String reason = dto != null ? dto.reason() : null;
        Map<String, Object> pre = getOrThrow(id, true, false);
        refuseIfMultiStage(pre, true);
        if ("completed".equals(pre.get("status"))) {
            throw ApiException.badRequest("A completed order cannot be cancelled — reverse it (Phase 3).");
        }
        if ("cancelled".equals(pre.get("status"))) {
            return pre;
        }
        try {
            newTx.executeWithoutResult(s -> reservations.release(SOURCE, id));
        } catch (RuntimeException ignored) {
            // Nest: .catch(() => undefined)
        }
        if (pre.get("consumedAt") == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "cancelled");
            data.put("cancelledAt", Instant.now());
            data.put("cancelReason", reason);
            data.put("updatedBy", TenantContext.userId());
            return withLines(db.update(ProductionOrder.class, id, data), true, true);
        }

        Instant date = Instant.now();
        lock(id);
        Map<String, Object> order = getOrNull(id, true, false);
        if (order == null || "cancelled".equals(order.get("status"))) {
            return order;
        }
        if (order.get("postedAt") != null) {
            throw ApiException.badRequest("Order already completed.");
        }
        String code = str(order, "orderCode");
        returnMaterials(order, "production_order_cancel", date, "Production cancel · " + code);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "cancelled");
        data.put("cancelledAt", date);
        data.put("cancelReason", reason);
        data.put("materialCost", BigDecimal.ZERO);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, id, data), true, true);
        audit.recordInTx(AuditInput.of("ProductionOrder", id, "cancel", null, Obj.of("orderCode", code, "reason",
                reason)));
        return updated;
    }

    // ================================================================== reverse

    /** Undo a completed order: finished goods (still fully on hand) back into WIP, materials back to stock. */
    @Transactional
    public Map<String, Object> reverseOrder(String id, CancelOrder dto) {
        String reason = dto != null ? dto.reason() : null;
        Map<String, Object> pre = getOrThrow(id, true, false);
        refuseIfMultiStage(pre, false);
        // ADR-S006 A5: a QC-held order can be reversed too (its finished goods sit in quarantine).
        boolean held = "qc_hold".equals(pre.get("status"));
        if ((!"completed".equals(pre.get("status")) && !held) || pre.get("postedAt") == null) {
            throw ApiException.badRequest("Only a completed order can be reversed (status " + pre.get("status") + ").");
        }
        if (pre.get("reversedAt") != null) {
            throw ApiException.badRequest("Order already reversed.");
        }
        if (dec(pre, "producedQty").signum() <= 0) {
            throw ApiException.badRequest("A scrapped (zero-yield) order has no output to reverse.");
        }
        String fgLocationId = held ? resolveQuarantineLocation()
                : pre.get("outputLocationId") != null ? str(pre, "outputLocationId") : str(pre, "locationId");
        BigDecimal fgQty = db.jdbc().sql("SELECT quantity FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? LIMIT 1")
                .params(MfgDb.org(), pre.get("outputProductId"),
                        pre.get("outputVariantId") != null ? pre.get("outputVariantId") : "", fgLocationId)
                .query(BigDecimal.class).optional().orElse(null);
        if (fgQty == null || fgQty.compareTo(dec(pre, "producedQty")) < 0) {
            throw ApiException.badRequest(
                    "Cannot reverse: finished goods are no longer fully in stock (sold or moved).");
        }
        Instant date = Instant.now();
        BigDecimal fgValue = BomService.mul(dec(pre, "outputUnitCost"), dec(pre, "producedQty"));

        lock(id);
        Map<String, Object> order = getOrNull(id, true, false);
        if (order == null || order.get("reversedAt") != null) {
            throw ApiException.badRequest("Order already reversed.");
        }
        String code = str(order, "orderCode");
        // 1) Finished goods out, value back into WIP (Dr WIP / Cr Stock Valuation).
        stock.issue(StockMove.of(str(order, "outputProductId"), fgLocationId, dec(order, "producedQty"))
                .variant(str(order, "outputVariantId")).skipGl().source("production_reversal", code));
        stockPosting.postProductionConsume(str(order, "outputProductId"), fgValue, date, "production_reversal", code,
                "Production reversal · " + code);
        // 2) Materials back to their source location, clearing WIP (Dr Stock Valuation / Cr WIP).
        returnMaterials(order, "production_reversal", date, "Production reversal · " + code);
        // 3) ADR-S008: standard-cost variances of the order are reversed with it.
        String varianceJe = db.jdbc().sql("SELECT \"varianceJournalEntryId\" FROM \"ProductionOrder\" WHERE id = ?")
                .param(id).query(String.class).optional().orElse(null);
        if (varianceJe != null) {
            standards.reverseJournal(varianceJe, "Reversal of production variances · " + code);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "cancelled");
        data.put("reversedAt", date);
        data.put("reversalReason", reason);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, id, data), true, true);
        audit.recordInTx(AuditInput.of("ProductionOrder", id, "cancel", null, Obj.of("orderCode", code, "reversed",
                JsNumbers.dec(fgValue))));
        return updated;
    }

    // ================================================================== menu item, list, get

    /**
     * One-click convergence: a POS menu item whose single recipe line is the finished good (qty 1), so selling it
     * relieves the finished good. {@code basePrice} follows the POS minor-unit convention (×100).
     */
    @Transactional
    public Map<String, Object> createMenuItemForProduct(String productId) {
        Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT id, name, \"salesPrice\", \"taxId\""
                        + " FROM \"Product\" WHERE id = ? AND " + MfgDb.scope(Product.class, null) + " LIMIT 1")
                .params(productId, MfgDb.org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", product.get("name"));
        data.put("basePrice", BomService.mul(dec(product, "salesPrice"), HUNDRED));
        data.put("isInventoryTracked", true);
        data.put("taxId", product.get("taxId"));
        Map<String, Object> item = db.insert(MenuItem.class, data);
        db.insert(MenuProduct.class, Obj.of("menuItemId", item.get("id"), "productId", productId, "quantity",
                BigDecimal.ONE));
        item.put("ingredients", db.children(MenuProduct.class, "menuItemId", item.get("id"), null));
        return item;
    }

    /** Newest first, each with materials and outputs. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String outputProductId, Integer limit, Integer offset) {
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            extra.append(" AND status = ?::\"ProductionOrderStatus\"");
            params.add(status);
        }
        if (outputProductId != null) {
            extra.append(" AND \"outputProductId\" = ?");
            params.add(outputProductId);
        }
        extra.append(" ORDER BY \"createdAt\" DESC").append(MfgDb.page(limit, offset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : db.list(ProductionOrder.class, extra.toString(), params.toArray())) {
            out.add(withLines(o, true, true));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return getOrThrow(id, true, true);
    }

    // ================================================================== internals

    /**
     * One output line: real stock via {@code receive} (no GL — receiveForDocument would credit GRNI), then the
     * explicit WIP-clearing entry. Returns the batch number recorded.
     */
    private String receiveOutput(String productId, String variantId, String locationId, BigDecimal quantityBase,
            BigDecimal unitCost, String batchNumber, String expiryDate, String mfgDate, String orderCode, Instant date,
            String lineId, BigDecimal totalValue) {
        StockMove move = StockMove.of(productId, locationId, quantityBase).variant(variantId).unitCost(unitCost)
                .moveType("production_output").batch(batchNumber).expiry(expiryDate).source(SOURCE, orderCode);
        move.mfgDate = mfgDate;
        Received res = stock.receive(move);
        if (totalValue.signum() > 0) {
            stockPosting.postProductionOutput(productId, totalValue, date, SOURCE, orderCode,
                    "Production output · " + orderCode);
        }
        if (lineId != null) {
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("qtyProduced", quantityBase);
            upd.put("unitCost", unitCost);
            upd.put("totalCost", totalValue);
            upd.put("batchNumber", batchNumber);
            upd.put("ledgerCode", res.ledgerCode());
            // ADR-S006 A6: link the output line to the lot it created (forward/backward genealogy).
            upd.put("batchId", db.jdbc().sql("SELECT \"batchId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                    + " AND \"ledgerCode\" = ? LIMIT 1").params(MfgDb.org(), res.ledgerCode()).query(String.class)
                    .optional().orElse(null));
            db.update(ProductionOutput.class, lineId, upd);
        }
        return batchNumber;
    }

    /** Receive every consumed material back at its consumed cost and clear its WIP (Dr Stock Val / Cr WIP). */
    private void returnMaterials(Map<String, Object> order, String sourceType, Instant date, String description) {
        String code = str(order, "orderCode");
        for (Map<String, Object> line : materials(order)) {
            BigDecimal qty = dec(line, "qtyConsumed");
            if (qty.signum() <= 0) {
                continue;
            }
            stock.receive(StockMove.of(str(line, "productId"), str(order, "locationId"), qty)
                    .variant(str(line, "variantId")).unitCost(dec(line, "unitCost")).moveType("receipt")
                    .source(sourceType, code));
            stockPosting.postProductionOutput(str(line, "productId"), dec(line, "totalCost"), date, sourceType, code,
                    description);
        }
    }

    /**
     * ADR-S011: a multi-stage order (one with {@code ProductionOrderStage} rows) runs through the stage routes; the
     * single-leg start / complete / QC / reverse refuse it, cancel only while no stage posting exists.
     */
    private void refuseIfMultiStage(Map<String, Object> order, boolean onlyWithPostings) {
        String table = onlyWithPostings ? "ProductionStagePosting" : "ProductionOrderStage";
        boolean staged = db.jdbc().sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ?" + (onlyWithPostings ? " AND \"reversedAt\" IS NULL" : ""))
                .params(MfgDb.org(), order.get("id")).query(Long.class).single() > 0;
        if (staged) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is a multi-stage order; use its stage"
                    + " routes" + (onlyWithPostings ? " (reverse its stage postings to cancel it)." : "."));
        }
    }

    /** Serialise concurrent start/complete/cancel of one order (double-tap guard). */
    private void lock(String orderId) {
        String key = "production:" + MfgDb.org() + ":" + orderId;
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param(key).query().listOfRows();
    }

    private BigDecimal estimateMaterialValue(Map<String, Object> order) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> m : materials(order)) {
            BigDecimal rac = db.jdbc().sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?"
                            + " LIMIT 1")
                    .params(MfgDb.org(), m.get("productId"), m.get("variantId") != null ? m.get("variantId") : "",
                            order.get("locationId"))
                    .query(BigDecimal.class).optional().orElse(null);
            if (rac != null) {
                total = total.add(BomService.mul(dec(m, "qtyPlanned"), rac));
            }
        }
        return total;
    }

    /** Where finished goods land so the till can sell them — the resolver POS itself uses. */
    private String resolvePosWarehouse() {
        String wh = posStockLocation.resolve(MfgDb.org(), null);
        if (wh == null) {
            throw ApiException.badRequest("No active warehouse for finished goods. Create one first.");
        }
        return wh;
    }

    /** The QC quarantine location id (created on first use). */
    String quarantineLocation() {
        return resolveQuarantineLocation();
    }

    /** The virtual QC quarantine location, auto-created (and committed) once per org. */
    private String resolveQuarantineLocation() {
        return newTx.execute(s -> {
            String existing = db.jdbc().sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL AND code = 'QC-QUARANTINE' LIMIT 1")
                    .param(MfgDb.org()).query(String.class).optional().orElse(null);
            if (existing != null) {
                return existing;
            }
            return str(db.insert(InventoryLocation.class, Obj.of("code", "QC-QUARANTINE", "name", "QC Quarantine",
                    "type", "virtual", "isActive", true, "createdBy", TenantContext.userId())), "id");
        });
    }

    /** 404 {@code Location not found} unless the location is live in this organization. */
    void requireLocation(String id) {
        boolean ok = db.jdbc().sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, MfgDb.org()).query(Long.class).single() > 0;
        if (!ok) {
            throw ApiException.notFound("Location not found");
        }
    }

    Map<String, Object> getProduct(String id) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, \"uomId\" FROM \"Product\" WHERE id = ? AND "
                + MfgDb.scope(Product.class, null) + " LIMIT 1").params(id, MfgDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product " + id + " not found");
        }
        return p;
    }

    private Map<String, String> productNames(List<String> ids) {
        Map<String, String> out = new HashMap<>();
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        if (unique.isEmpty()) {
            return out;
        }
        db.jdbc().sql("SELECT id, name FROM \"Product\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                        + " AND \"deletedAt\" IS NULL")
                .param("ids", unique).param("org", MfgDb.org()).query()
                .listOfRows().forEach(r -> out.put(String.valueOf(r.get("id")), String.valueOf(r.get("name"))));
        return out;
    }

    private Map<String, Object> getOrNull(String id, boolean materials, boolean outputs) {
        Map<String, Object> order = db.byId(ProductionOrder.class, id);
        return order == null ? null : withLines(order, materials, outputs);
    }

    private Map<String, Object> getOrThrow(String id, boolean materials, boolean outputs) {
        Map<String, Object> order = getOrNull(id, materials, outputs);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return order;
    }

    /** {@code include: {materials: true, outputs: true}} (no orderBy, as in Nest). */
    Map<String, Object> withLines(Map<String, Object> order, boolean materials, boolean outputs) {
        if (materials) {
            order.put("materials", db.children(ProductionMaterial.class, "orderId", order.get("id"), null));
        }
        if (outputs) {
            order.put("outputs", db.children(ProductionOutput.class, "orderId", order.get("id"), null));
        }
        return order;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> materials(Map<String, Object> order) {
        return (List<Map<String, Object>>) order.get("materials");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outputs(Map<String, Object> order) {
        return (List<Map<String, Object>>) order.get("outputs");
    }

    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    private static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }

    /** JS {@code Date#toISOString()}: millisecond precision, {@code Z}. */
    private static String iso(Object instant) {
        return instant instanceof Instant i ? ISO_MS.format(i) : null;
    }
}
