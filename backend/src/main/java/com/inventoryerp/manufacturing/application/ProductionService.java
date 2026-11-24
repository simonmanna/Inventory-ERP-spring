package com.inventoryerp.manufacturing.application;

import com.inventoryerp.core.application.ProductVariantService;
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
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReservationService.Reserve;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.VirtualLocationService;
import com.inventoryerp.inventory.domain.StockItem;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.ControlledActionService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
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
import com.inventoryerp.manufacturing.spi.ProductionLifecycleGate;
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
import org.springframework.beans.factory.ObjectProvider;

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

    private final ObjectProvider<ProductionLifecycleGate> lifecycleGate;
    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final ControlledActionService controlled;
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
    private final EventBus events;

    private final ProductVariantService variants;

    private com.inventoryerp.kernel.settings.SettingResolverService settings;

    private VirtualLocationService virtualLocations;

    /** Setter-injected like {@link #setSettings}: system locations are created through the inventory API (R2). */
    @org.springframework.beans.factory.annotation.Autowired
    void setVirtualLocations(VirtualLocationService virtualLocations) {
        this.virtualLocations = virtualLocations;
    }

    /** Optional (setter) so the many existing constructions stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(com.inventoryerp.kernel.settings.SettingResolverService settings) {
        this.settings = settings;
    }

    /**
     * Review 2026-11-17 (ACR-19): the organisation requires materials on hand before production consumes them (single-step
     * orders here, multi-stage stage issues in {@link StageExecutionService}).
     */
    boolean materialsMustBeOnHand() {
        return settings != null && settings.resolveBool("costing.production.requireMaterialsOnHand",
                com.inventoryerp.kernel.settings.SettingResolverService.SettingContext.EMPTY);
    }

    public ProductionService(MfgDb db, SequenceService seq, AuditService audit, ApprovalsService approvals,
            ControlledActionService controlled, StockService stock, StockPostingService stockPosting,
            StockReservationService reservations,
            UomConversionService uom, PosStockLocation posStockLocation, BomService boms,
            QualityService quality, CostingService costing, StandardCostService standards,
            PlatformTransactionManager txm, ObjectProvider<ProductionLifecycleGate> lifecycleGate, EventBus events,
            ProductVariantService variants) {
        this.events = events;
        this.variants = variants;
        this.lifecycleGate = lifecycleGate;
        this.standards = standards;
        this.quality = quality;
        this.costing = costing;
        this.db = db;
        this.seq = seq;
        this.audit = audit;
        this.approvals = approvals;
        this.controlled = controlled;
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

        // Phase I2 (D2, audit V-9): the output receives new stock and the materials are planned per variant, so each
        // product with variants names an active variant of its own (also when the BOM was made before one was archived).
        variants.resolve(outputProductId, outputVariantId);
        for (MaterialPlan m : materials) {
            variants.resolve(m.productId(), m.variantId());
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
        lifecycleGate.ifAvailable(g -> g.requireProducible(outputProductId)); // PLM lifecycle gate (ADR-S086)
        for (MaterialPlan m : materials) {
            getProduct(m.productId());
        }
        if (dto.outputs() != null) {
            dto.outputs().forEach(o -> getProduct(o.productId()));
            // Integrity fix 2026-11-24 (deviation: Nest took any %): by-products take their share of the cost pool
            // before the main output; more than 100 % in total left the main output at a negative cost.
            BigDecimal byproductPct = dto.outputs().stream()
                    .filter(o -> o.kind() == null || "byproduct".equals(o.kind()))
                    .map(o -> o.costAllocationPct() != null ? o.costAllocationPct() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (byproductPct.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("By-product cost allocation totals " + JsNumbers.dec(byproductPct)
                        + "%; it cannot exceed 100%.");
            }
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
                // Review M-3: reservations count base units (the stock they hold back); the material line is in its
                // BOM unit.
                // The conversion runs in its own transaction: a failure stays soft.
                String productId = str(m, "productId");
                BigDecimal base = newTx.execute(s -> uom.toBase(dec(m, "qtyPlanned"), str(m, "uomId"), productId,
                        productUnit(productId), true));
                // The reservation itself joins this transaction under a savepoint: it takes the quant's advisory lock
                // (hardening R5b), which this transaction may already hold from a stock move earlier in it (the demo
                // seeds confirm orders after receiving their materials); a separate transaction would wait for it
                // forever. reserveSoft does not mark this transaction rollback-only, so a failure stays soft.
                Savepoints.run(db.jdbc(), () -> reservations.reserveSoft(new Reserve(productId, str(m, "variantId"),
                        str(order, "locationId"), base, SOURCE, id, str(order, "orderCode"))));
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
                // Integrity fix 2026-11-24 (MC-13): the ratio is substitute base units per component BASE unit and
                // the substitute is issued in its base unit; the line quantity is in the BOM line's unit, so it is
                // converted to the component's base unit first (a 500 g line no longer issued 500 kg).
                BigDecimal componentBase = uom.toBase(qty, str(line, "uomId"), productId, productUnit(productId), true);
                productId = ov.substituteProductId();
                qty = BomService.mul(componentBase, dec(substitute, "ratio"));
            }
            StockMove move = StockMove.of(productId, str(order, "locationId"), qty)
                    .variant(substitute != null ? null : str(line, "variantId"))
                    .uom(substitute != null ? null : str(line, "uomId")).moveType("production_consume")
                    .strategy(line.get("distStrategy") != null ? str(line, "distStrategy") : "FEFO")
                    .batch(batchNumber).date(date).source(SOURCE, code).notes(code)
                    .requireAvailable(materialsMustBeOnHand());
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
        // Review M-3: the materials are consumed now, so their reservations end here (not at completion, which kept
        // holding back stock that had already left).
        reservations.consume(SOURCE, id);
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
        if (dto.qtyProduced() == null || dto.qtyProduced().signum() <= 0) {
            // Integrity fix 2026-11-24 (MC-1): the loss written off includes the order's overhead.
            authorizeLossWriteOff(pre, dec(pre, "materialCost").add(dec(pre, "overheadCost")));
        }

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
            // Integrity fix 2026-11-24 (MC-1; deviation: Nest wrote off the materials only and never absorbed the
            // order's overhead, so the period's absorbed-overhead credit and the loss both missed it): absorb the
            // overhead into WIP as a producing order does, then write off materials + overhead.
            absorbOverhead(id, code, overhead, date);
            BigDecimal loss = materialCost.add(overhead);
            stockPosting.postProductionScrap(loss, date, SOURCE, code, "Production scrap · " + code);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "completed");
            data.put("producedQty", BigDecimal.ZERO);
            data.put("scrapQty", order.get("plannedQty"));
            data.put("outputUnitCost", BigDecimal.ZERO);
            data.put("totalCost", loss);
            data.put("completedAt", date);
            data.put("postedAt", Instant.now());
            data.put("notes", dto.notes() != null ? dto.notes() : order.get("notes"));
            data.put("updatedBy", TenantContext.userId());
            Map<String, Object> scrapped = withLines(db.update(ProductionOrder.class, id, data), true, true);
            reservations.release(SOURCE, id);
            audit.recordInTx(AuditInput.of("ProductionOrder", id, "post", null,
                    Obj.of("orderCode", code, "scrapped", JsNumbers.dec(loss))));
            orderEvent("manufacturing.order.completed", scrapped, null);
            return scrapped;
        }

        String receiveLocationId = quarantineId != null ? quarantineId
                : order.get("outputLocationId") != null ? str(order, "outputLocationId") : str(order, "locationId");
        BigDecimal pool = materialCost.add(overhead);
        BigDecimal byproductCredit = BigDecimal.ZERO;

        // Absorb overhead into WIP so the output credit (which includes it) still nets WIP to zero.
        absorbOverhead(id, code, overhead, date);

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
        if (pool.signum() < 0) {
            // Integrity fix 2026-11-24: orders created before the 100 % cap.
            throw ApiException.badRequest("By-products of " + code + " take more than the order's cost ("
                    + JsNumbers.dec(byproductCredit) + " of " + JsNumbers.dec(materialCost.add(overhead))
                    + "); correct their cost allocation %.");
        }

        // 1b) Review 2026-11-17 (rework costing): an abnormal rework — a defect that is not part of the normal process —
        // brings the units back at the cost they already carried; the extra material and conversion it took is a loss
        // of the period (IAS 2.16(a)), not stock value (Dr Stock Adjustment Expense / Cr WIP). A normal rework keeps its
        // cost in the units.
        BigDecimal reworkLoss = BigDecimal.ZERO;
        if ("rework".equals(order.get("orderKind")) && "abnormal".equals(order.get("reworkTreatment"))) {
            BigDecimal carried = db.jdbc().sql("SELECT coalesce(sum(\"totalCost\"), 0) FROM \"ProductionMaterial\" WHERE"
                    + " \"organizationId\" = ? AND \"orderId\" = ? AND \"productId\" = ?").params(MfgDb.org(), id,
                            order.get("outputProductId")).query(BigDecimal.class).single();
            reworkLoss = pool.subtract(carried).max(BigDecimal.ZERO);
            if (reworkLoss.signum() > 0) {
                stockPosting.postProductionScrap(reworkLoss, date, SOURCE, code, "Abnormal rework · " + code);
                pool = pool.subtract(reworkLoss);
            }
        }

        // 2) Main output absorbs the remaining pool; dividing by ACTUAL produced absorbs normal scrap.
        BigDecimal qtyProducedBase = uom.toBase(dto.qtyProduced(),
                dto.uomId() != null ? dto.uomId() : str(order, "uomId"), str(outProduct, "id"),
                str(outProduct, "uomId"), true);
        if (qtyProducedBase.signum() <= 0) {
            throw ApiException.badRequest("Produced quantity resolves to 0 in base units.");
        }
        // ADR-S008: a standard-governed output is received at its standard; the rest of WIP becomes variances. R3: a
        // STANDARD output without an active standard is governed by its cost price.
        BigDecimal standardUnit = standards.governingStandard(str(order, "outputProductId")).orElse(null);
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
        // Review N3: expected and produced compared in base units (outputUnitCost is per base unit).
        BigDecimal expectedBase = uom.toBase(expectedQty, mainLine != null && mainLine.get("uomId") != null
                ? str(mainLine, "uomId") : str(order, "uomId"), str(outProduct, "id"), str(outProduct, "uomId"), true);
        BigDecimal yieldVariance = BomService.mul(expectedBase.subtract(qtyProducedBase), outputUnitCost);
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
        data.put("reworkLoss", reworkLoss);
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
        orderEvent("manufacturing.order.completed", updated, null);
        return updated;
    }

    /**
     * Sensitive pair: writing off a production loss (zero yield, or units failed at QC) needs an approval when a
     * {@code production_loss_writeoff} workflow exists, and whoever raised the order may not clear it alone.
     */
    private void authorizeLossWriteOff(Map<String, Object> order, BigDecimal value) {
        ControlledActionService.Outcome outcome = controlled.authorize(new ControlledActionService.Action(
                SegregationOfDutiesService.PRODUCTION_LOSS_WRITE_OFF, "ProductionOrder", str(order, "id"), value,
                null, null, java.util.Arrays.asList(str(order, "createdBy"), str(order, "updatedBy")),
                Obj.of("orderCode", order.get("orderCode"))));
        if (!outcome.allowed()) {
            throw ApiException.badRequest("Approval required before writing off the loss on " + order.get("orderCode")
                    + ". Pending request " + outcome.approvalRequestId() + ".");
        }
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
        if (failed.signum() > 0) {
            authorizeLossWriteOff(pre, failed.multiply(dec(pre, "outputUnitCost")));
        }

        lock(id);
        Map<String, Object> order = db.byId(ProductionOrder.class, id);
        if (order == null || !"qc_hold".equals(order.get("status"))) {
            throw ApiException.badRequest("Order is not awaiting QC.");
        }
        String code = str(order, "orderCode");
        // Integrity fix 2026-11-24 (MC-7; deviation: Nest took any quantities): a decision covers at most what this
        // order still holds in quarantine (base units — the output was received, and QC moves stock, in base units).
        BigDecimal decided = passed.add(failed).add(rework);
        BigDecimal held = producedOnHand(code, str(order, "outputProductId"), str(order, "outputVariantId"),
                quarantineId);
        if (decided.compareTo(held) > 0) {
            throw ApiException.badRequest("QC quantities (passed + failed + rework = " + JsNumbers.dec(decided)
                    + ") exceed the " + JsNumbers.dec(held) + " of order " + code + " still in quarantine.");
        }
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
        if (done) {
            orderEvent("manufacturing.order.completed", updated, null);
        }
        return updated;
    }

    // ================================================================== cancel

    /** Cancel a draft/confirmed order (flip) or an in-progress one (materials back to stock, WIP cleared). */
    @Transactional
    public Map<String, Object> cancelOrder(String id, CancelOrder dto) {
        String reason = dto != null ? dto.reason() : null;
        // Integrity fix 2026-11-24 (deviation: Nest flipped without a lock): lock and read once, so a start that
        // consumed the materials in between cannot be flipped to cancelled with its materials stranded in WIP.
        lock(id);
        Map<String, Object> pre = getOrThrow(id, true, false);
        refuseIfMultiStage(pre, true);
        if ("completed".equals(pre.get("status"))) {
            throw ApiException.badRequest("A completed order cannot be cancelled — reverse it (Phase 3).");
        }
        if ("cancelled".equals(pre.get("status"))) {
            return pre;
        }
        // Integrity fix 2026-11-24 (MC-8; deviation: the release ran in its own committed transaction before the
        // cancel was known to succeed — a later failure, e.g. inside a PLM ECO apply, left the order confirmed with its
        // reservations gone): released in this transaction, under a savepoint so a failure is still ignored (Nest:
        // .catch(() => undefined)).
        try {
            Savepoints.run(db.jdbc(), () -> reservations.release(SOURCE, id));
        } catch (RuntimeException e) {
            log.warn("reservation release failed for {}: {}", pre.get("orderCode"), e.getMessage());
        }
        if (pre.get("consumedAt") == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "cancelled");
            data.put("cancelledAt", Instant.now());
            data.put("cancelReason", reason);
            data.put("updatedBy", TenantContext.userId());
            Map<String, Object> flipped = withLines(db.update(ProductionOrder.class, id, data), true, true);
            orderEvent("manufacturing.order.cancelled", flipped, reason);
            return flipped;
        }

        Instant date = Instant.now();
        Map<String, Object> order = pre;
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
        orderEvent("manufacturing.order.cancelled", updated, reason);
        return updated;
    }

    /**
     * Notification routing (ADR-S065). {@code EventBus} writes after the transaction ends, even on rollback; the router
     * re-reads the order's status before telling anyone.
     */
    private void orderEvent(String event, Map<String, Object> order, String reason) {
        events.publish(event, Obj.of("organizationId", order.get("organizationId"), "orderId", order.get("id"),
                "orderCode", order.get("orderCode"), "status", order.get("status"), "producedQty",
                order.get("producedQty") == null ? null : String.valueOf(order.get("producedQty")), "reason", reason));
    }

    // ================================================================== reverse

    /** Undo a completed order: finished goods (still fully on hand) back into WIP, materials back to stock. */
    @Transactional
    public Map<String, Object> reverseOrder(String id, CancelOrder dto) {
        String reason = dto != null ? dto.reason() : null;
        // Integrity fix 2026-11-24: lock before the checks, so they read what the reversal will act on.
        lock(id);
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
            // Integrity fix 2026-11-24 (MC-1; deviation: Nest refused): a zero-yield order reverses too.
            return reverseZeroYield(pre, reason);
        }
        String fgLocationId = held ? resolveQuarantineLocation()
                : pre.get("outputLocationId") != null ? str(pre, "outputLocationId") : str(pre, "locationId");
        // Review N3: producedQty is in the order's unit; the finished goods were received in base units.
        Map<String, Object> mainOut = mainOutput(pre);
        BigDecimal fgBase = producedBase(pre, mainOut);
        BigDecimal fgQty = producedOnHand(str(pre, "orderCode"), str(pre, "outputProductId"),
                str(pre, "outputVariantId"), fgLocationId);
        if (fgQty == null || fgQty.compareTo(fgBase) < 0) {
            throw ApiException.badRequest(
                    "Cannot reverse: finished goods are no longer fully in stock (sold or moved).");
        }
        // Review N8: by-products received with the output go back out too, at the value they took out of WIP.
        List<Map<String, Object>> byproducts = new ArrayList<>();
        for (Map<String, Object> bp : db.children(ProductionOutput.class, "orderId", id, null)) {
            if (!"byproduct".equals(bp.get("kind")) || bp.get("ledgerCode") == null) {
                continue;
            }
            BigDecimal bpBase = ledgerQty(str(bp, "ledgerCode"), true);
            if (bpBase == null) {
                continue;
            }
            BigDecimal onHand = producedOnHand(str(pre, "orderCode"), str(bp, "productId"), str(bp, "variantId"),
                    fgLocationId);
            if (onHand == null || onHand.compareTo(bpBase) < 0) {
                throw ApiException.badRequest(
                        "Cannot reverse: by-products are no longer fully in stock (sold or moved).");
            }
            bp.put("qtyBase", bpBase);
            byproducts.add(bp);
        }
        Instant date = Instant.now();
        // The value the receipt credited out of WIP (outputUnitCost × qty can drift by rounding when it is a quotient).
        BigDecimal fgValue = mainOut != null && mainOut.get("totalCost") != null && dec(mainOut, "totalCost").signum() > 0
                ? dec(mainOut, "totalCost") : BomService.mul(dec(pre, "outputUnitCost"), fgBase);

        Map<String, Object> order = pre;
        String code = str(order, "orderCode");
        // 1) Finished goods out, value back into WIP (Dr WIP / Cr Stock Valuation).
        // Audit S-4 (deviation from Nest): the GL credits stock with the value the engine issued (the ledger's), WIP
        // gets the output's value back, the difference is a stock adjustment gain / loss.
        // Hardening R5b (P2-2): the produced lot goes back first (then other lots, FEFO, for any part of it that left).
        BigDecimal fgIssued = stock.issue(StockMove.of(str(order, "outputProductId"), fgLocationId, fgBase)
                .variant(str(order, "outputVariantId")).skipGl().source("production_reversal", code)
                .prefer(producedLots(code, str(order, "outputProductId"), str(order, "outputVariantId"), fgLocationId),
                        null)).totalValue();
        stockPosting.postProductionReversal(str(order, "outputProductId"), fgIssued, fgValue, date,
                "production_reversal", code, "Production reversal · " + code);
        // 1b) By-products out, their value back into WIP (Dr WIP / Cr Stock Valuation).
        for (Map<String, Object> bp : byproducts) {
            BigDecimal bpIssued = stock.issue(StockMove.of(str(bp, "productId"), fgLocationId, dec(bp, "qtyBase"))
                    .variant(str(bp, "variantId")).skipGl().source("production_reversal", code)
                    .prefer(producedLots(code, str(bp, "productId"), str(bp, "variantId"), fgLocationId), null))
                    .totalValue();
            BigDecimal bpValue = bp.get("totalCost") == null ? BigDecimal.ZERO : dec(bp, "totalCost");
            stockPosting.postProductionReversal(str(bp, "productId"), bpIssued, bpValue, date, "production_reversal",
                    code, "Production reversal (by-product) · " + code);
        }
        // 2) Materials back to their source location, clearing WIP (Dr Stock Valuation / Cr WIP).
        returnMaterials(order, "production_reversal", date, "Production reversal · " + code);
        // 2b) Review 2026-11-17 (ACR-5; deviation: Nest left it): the overhead the complete leg absorbed into WIP leaves
        // it again (Dr Overhead Absorbed / Cr WIP). Step 1 put the full output value — materials plus overhead — back
        // into WIP and step 2 took only the materials out, so WIP kept a debit equal to the overhead and the period's
        // absorbed-overhead credit stayed as if the order had produced.
        BigDecimal overhead = dec(order, "overheadCost");
        reverseOverheadAbsorption(code, overhead, date);
        // 2c) Review 2026-11-17 (rework costing): the loss an abnormal rework expensed goes back into WIP with it
        // (Dr WIP / Cr Stock Adjustment Expense); steps 2 and 2b took the whole rework cost out of WIP.
        BigDecimal reworkLoss = dec(order, "reworkLoss");
        if (reworkLoss.signum() > 0) {
            stockPosting.postProductionScrapReversal(reworkLoss, date, "production_reversal", code,
                    "Abnormal rework loss reversed · " + code);
        }
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

    /**
     * Hardening R5b (P2-2): the lots this order's output receipts created for a product, as they stand at
     * {@code locationId}: the receipt's own lot when it is still there, else (a QC-held order whose output moved to
     * quarantine) the lots there with the produced batch numbers. Empty for products without lots (the output receipt
     * names no serial units, so none are pinned).
     */
    /**
     * Integrity fix 2026-11-24 (deviation: Nest compared the product's whole stock at the location): how much of what
     * this order produced is still at {@code locationId} — the least of the stock there, the order's own net movements
     * there (its output receipts and its QC transfers / write-offs; not its consumption, which a rework order draws from
     * the same product) and, for lot-tracked products, what is left in its lots.
     * Other stock of the same product (another order's output in the same quarantine, say) no longer lets a reversal
     * pass after part of this output was released, wasted at QC, sold or moved.
     */
    private BigDecimal producedOnHand(String orderCode, String productId, String variantId, String locationId) {
        BigDecimal here = db.jdbc().sql("SELECT quantity FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? LIMIT 1")
                .params(MfgDb.org(), productId, variantId != null ? variantId : "", locationId)
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        BigDecimal own = db.jdbc().sql("SELECT coalesce(sum(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                        + " AND \"locationId\" = ? AND \"referenceId\" = ? AND ((\"referenceType\" = ?"
                        + " AND type = 'production_output') OR \"referenceType\" = 'production_qc')")
                .params(MfgDb.org(), productId, variantId, locationId, orderCode, SOURCE).query(BigDecimal.class)
                .single();
        BigDecimal left = here.min(own);
        boolean lotted = db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                        + " AND \"referenceType\" = ? AND \"referenceId\" = ? AND type = 'production_output'"
                        + " AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"batchId\" IS NOT NULL")
                .params(MfgDb.org(), SOURCE, orderCode, productId, variantId).query(Long.class).single() > 0;
        if (!lotted) {
            return left;
        }
        List<String> lots = producedLots(orderCode, productId, variantId, locationId);
        BigDecimal inLots = lots.isEmpty() ? BigDecimal.ZERO : db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM"
                        + " \"InventoryBatch\" WHERE \"organizationId\" = ? AND id = ANY(?::text[]) AND \"locationId\" = ?")
                .params(MfgDb.org(), lots.toArray(String[]::new), locationId).query(BigDecimal.class).single();
        return left.min(inLots);
    }

    private List<String> producedLots(String orderCode, String productId, String variantId, String locationId) {
        List<Map<String, Object>> produced = db.jdbc().sql("SELECT b.id, b.\"locationId\", b.\"batchNumber\" FROM"
                        + " \"InventoryLedger\" l JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"referenceType\" = ? AND l.\"referenceId\" = ? AND l.type = 'production_output'"
                        + " AND l.\"productId\" = ? AND l.\"variantId\" IS NOT DISTINCT FROM ? ORDER BY l.\"createdAt\"")
                .params(MfgDb.org(), SOURCE, orderCode, productId, variantId).query().listOfRows();
        List<String> here = produced.stream().filter(b -> locationId.equals(b.get("locationId")))
                .map(b -> (String) b.get("id")).toList();
        if (!here.isEmpty() || produced.isEmpty()) {
            return here;
        }
        return db.jdbc().sql("SELECT id FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND \"batchNumber\" = ANY(?::text[])"
                        + " AND quantity > 0")
                .params(MfgDb.org(), productId, variantId, locationId,
                        produced.stream().map(b -> (String) b.get("batchNumber")).distinct().toArray(String[]::new))
                .query(String.class).list();
    }

    // ================================================================== overhead absorption (MC-1 / MC-3)

    private static final String RATED_OVERHEAD = "Overhead (work-centre rate) · ";

    /**
     * Integrity fix 2026-11-24 (MC-3; deviation: Nest credited all of a plain order's overhead to overhead absorbed):
     * Dr WIP for the order's overhead. The part absorbed at a work-centre rate that names a credit account
     * ({@code WorkCenterCostRate.creditAccountId}, as the multi-stage path credits it) credits that account in one
     * journal; the rest (BOM overhead, resource labour, rates without an account) credits overhead absorbed.
     */
    private void absorbOverhead(String orderId, String code, BigDecimal overhead, Instant date) {
        if (overhead.signum() <= 0) {
            return;
        }
        List<Map<String, Object>> rated = SqlRows.list(db.jdbc().sql("SELECT r.\"creditAccountId\" AS account,"
                + " sum(a.amount) AS amount FROM \"ProductionCostAbsorption\" a JOIN \"WorkCenterCostRate\" r ON"
                + " r.\"organizationId\" = a.\"organizationId\" AND r.\"workCenterId\" = a.\"workCenterId\""
                + " WHERE a.\"organizationId\" = ? AND a.\"orderId\" = ? AND a.\"stageId\" IS NULL"
                + " AND r.\"creditAccountId\" IS NOT NULL GROUP BY 1 ORDER BY 1").params(MfgDb.org(), orderId));
        BigDecimal ratedTotal = BigDecimal.ZERO;
        List<com.inventoryerp.accounting.application.PostingRequest.Line> credits = new ArrayList<>();
        String label = RATED_OVERHEAD + code;
        for (Map<String, Object> r : rated) {
            BigDecimal amount = dec(r, "amount").min(overhead.subtract(ratedTotal));
            if (amount.signum() <= 0) {
                continue;
            }
            credits.add(com.inventoryerp.accounting.application.PostingRequest.Line.credit(str(r, "account"),
                    JsNumbers.dec(amount), label));
            ratedTotal = ratedTotal.add(amount);
        }
        if (!credits.isEmpty()) {
            List<com.inventoryerp.accounting.application.PostingRequest.Line> lines = new ArrayList<>();
            lines.add(com.inventoryerp.accounting.application.PostingRequest.Line.debit(stockPosting.wipAccount(),
                    JsNumbers.dec(ratedTotal), label));
            lines.addAll(credits);
            posting.post(com.inventoryerp.accounting.application.PostingRequest.of("INV", date, label, SOURCE, code,
                    null, lines));
        }
        stockPosting.postOverheadAbsorption(overhead.subtract(ratedTotal), date, SOURCE, code, "Overhead · " + code);
    }

    /** Mirror of {@link #absorbOverhead}: the rated journal is reversed, the rest leaves overhead absorbed again. */
    private void reverseOverheadAbsorption(String code, BigDecimal overhead, Instant date) {
        if (overhead.signum() <= 0) {
            return;
        }
        String je = db.jdbc().sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = ?"
                + " AND \"sourceId\" = ? AND description = ? AND status = 'posted' AND \"reversalOfId\" IS NULL"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").params(MfgDb.org(), SOURCE, code, RATED_OVERHEAD + code)
                .query(String.class).optional().orElse(null);
        BigDecimal rated = BigDecimal.ZERO;
        if (je != null) {
            rated = db.jdbc().sql("SELECT coalesce(sum(debit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                    .param(je).query(BigDecimal.class).single();
            posting.reverse(je, date, "Overhead absorption reversed · " + code);
        }
        stockPosting.postOverheadAbsorptionReversal(overhead.subtract(rated).max(BigDecimal.ZERO), date,
                "production_reversal", code, "Overhead absorption reversed · " + code);
    }

    /**
     * Integrity fix 2026-11-24 (MC-1): undo a zero-yield order — materials back to stock (Dr Stock Valuation / Cr WIP),
     * the loss written off back into WIP (Dr WIP / Cr Stock Adjustment Expense) and the overhead it absorbed out of it
     * again. Orders scrapped before the fix wrote off the materials only ({@code totalCost} = materials) and absorbed
     * no overhead; {@code totalCost − materialCost} is what was absorbed.
     */
    private Map<String, Object> reverseZeroYield(Map<String, Object> order, String reason) {
        Instant date = Instant.now();
        String code = str(order, "orderCode");
        BigDecimal written = order.get("totalCost") == null ? dec(order, "materialCost") : dec(order, "totalCost");
        BigDecimal absorbed = written.subtract(dec(order, "materialCost")).max(BigDecimal.ZERO);
        returnMaterials(order, "production_reversal", date, "Production reversal · " + code);
        stockPosting.postProductionScrapReversal(written, date, "production_reversal", code,
                "Production scrap reversed · " + code);
        reverseOverheadAbsorption(code, absorbed, date);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "cancelled");
        data.put("reversedAt", date);
        data.put("reversalReason", reason);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = withLines(db.update(ProductionOrder.class, str(order, "id"), data), true, true);
        audit.recordInTx(AuditInput.of("ProductionOrder", str(order, "id"), "cancel", null, Obj.of("orderCode", code,
                "reversed", JsNumbers.dec(written))));
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
            // Review N3: return what the consume move took out, in base units and at its cost (qtyConsumed is in
            // the material line's unit). The consume's ledger rows are found by the line's ledgerCode.
            BigDecimal base = ledgerQty(str(line, "ledgerCode"), false);
            if (base == null) {
                base = uom.toBase(qty, str(line, "uomId"), str(line, "productId"), productUnit(str(line, "productId")),
                        true);
            }
            BigDecimal unitCost = base.signum() > 0 && line.get("totalCost") != null
                    ? Money.div(dec(line, "totalCost"), base) : dec(line, "unitCost");
            if (ledgerQty(str(line, "ledgerCode"), false) != null) {
                // R1: back onto the lot, expiry, serial, location and cost the consume move took them from (what is
                // still not returned); the WIP clearance below stays the line's consumed cost.
                stockReturns.returnFromSource(new StockReversalService.ReturnFromSource(
                        StockReversalService.IssueSource.ledgerCodes(List.of(str(line, "ledgerCode"))), null,
                        sourceType, code, null, null, date, null, false, "receipt"));
            } else {
                stock.receive(StockMove.of(str(line, "productId"), str(order, "locationId"), base)
                        .variant(str(line, "variantId")).unitCost(unitCost).moveType("receipt")
                        .source(sourceType, code));
            }
            stockPosting.postProductionOutput(str(line, "productId"), dec(line, "totalCost"), date, sourceType, code,
                    description);
        }
    }

    private StockReversalService stockReturns;

    private com.inventoryerp.accounting.application.PostingService posting;

    /** Integrity fix 2026-11-24 (MC-3): overhead absorbed at a work-centre rate credits the rate's account. */
    @org.springframework.beans.factory.annotation.Autowired
    void setPosting(com.inventoryerp.accounting.application.PostingService posting) {
        this.posting = posting;
    }

    /** R1: consumed materials are returned against their consume move. */
    @org.springframework.beans.factory.annotation.Autowired
    void setStockReturns(StockReversalService stockReturns) {
        this.stockReturns = stockReturns;
    }

    /**
     * Base units of the main finished good an order received: the output line's {@code qtyProduced} (stored in base
     * units), else its receipt ledger rows, else {@code producedQty} converted from the order's unit.
     */
    private BigDecimal producedBase(Map<String, Object> order, Map<String, Object> main) {
        if (main != null) {
            BigDecimal fromLedger = ledgerQty(str(main, "ledgerCode"), true);
            if (fromLedger != null) {
                return fromLedger;
            }
            if (main.get("qtyProduced") != null && dec(main, "qtyProduced").signum() > 0) {
                return dec(main, "qtyProduced");
            }
        }
        String productId = str(order, "outputProductId");
        return uom.toBase(dec(order, "producedQty"), str(order, "uomId"), productId, productUnit(productId), true);
    }

    /** The order's main output line (kind {@code output}), or null for a legacy order without one. */
    private Map<String, Object> mainOutput(Map<String, Object> order) {
        List<Map<String, Object>> lines = order.get("outputs") != null ? outputs(order)
                : db.children(ProductionOutput.class, "orderId", order.get("id"), null);
        return lines.stream().filter(o -> "output".equals(o.get("kind"))).findFirst().orElse(null);
    }

    /** Σ |quantityChange| of one stock move's ledger rows (in or out); null when the code is unknown. */
    private BigDecimal ledgerQty(String ledgerCode, boolean incoming) {
        if (ledgerCode == null) {
            return null;
        }
        BigDecimal q = db.jdbc().sql("SELECT sum(abs(\"quantityChange\")) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"ledgerCode\" = ? AND \"quantityChange\" " + (incoming ? "> 0" : "< 0")).params(MfgDb.org(), ledgerCode)
                .query(BigDecimal.class).optional().orElse(null);
        return q == null || q.signum() == 0 ? null : q;
    }

    private String productUnit(String productId) {
        return db.jdbc().sql("SELECT \"uomId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, MfgDb.org()).query(String.class).optional().orElse(null);
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
        db.lockProductionOrder(orderId);
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

    /**
     * The virtual QC quarantine location, auto-created (and committed in its own transaction) once per org through the
     * inventory API — the same location quality lots use.
     */
    private String resolveQuarantineLocation() {
        return virtualLocations.ensure(VirtualLocationService.QC_QUARANTINE, "QC Quarantine");
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
