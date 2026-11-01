package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.JointCostAllocator.Share;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.application.StageCommands.IssueLine;
import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.application.StageCommands.StageIssue;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.ProductionCostComponent;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrderStage;
import com.inventoryerp.manufacturing.domain.ProductionOutput;
import com.inventoryerp.manufacturing.domain.ProductionStagePosting;
import com.inventoryerp.manufacturing.domain.ProductionStagePostingLine;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.spi.StageCheckpoint;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults;
import com.inventoryerp.manufacturing.spi.StageQcCheckpointHook;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The multi-stage production engine (ADR-S011, Phase 2.1). A production order with {@code ProductionOrderStage} rows
 * runs stage by stage instead of the single start / complete legs:
 *
 * <ul>
 *   <li><b>Issue</b> (manual stages, or ad hoc): materials of the stage, or the previous stage's intermediate from its
 *       stage location, into the stage's WIP (Dr WIP / Cr stock).</li>
 *   <li><b>Output</b> (partial or completing): backflush stages first issue their planned share; pending conversion
 *       cost (work-order labour of this and earlier stages, the BOM overhead at the last stage) is absorbed (Dr WIP /
 *       Cr overhead absorbed); {@link StageYieldCalculator} relieves the stage WIP and splits off abnormal scrap
 *       (Dr scrap expense / Cr WIP); {@link JointCostAllocator} values the main output, co-products, by-products and
 *       waste; each stocked output is received (Dr stock / Cr WIP). A non-final stage's intermediate lands in its stage
 *       location (or, when QC-gated, in QC quarantine until the gate passes); a pass-through stage moves its cost to
 *       the next stage in the WIP sub-ledger.</li>
 *   <li><b>Conversion</b>: labour / machine / overhead / utility applied to the stage (Dr WIP / Cr the given account,
 *       default overhead absorbed).</li>
 *   <li><b>QC gate</b>: later stages are blocked until the checkpoint passes ({@link StageCheckpointResults});
 *       {@link StageQcCheckpointHook} beans are told when a gated lot is waiting.</li>
 *   <li><b>Reversal</b>: last-in-first-out per order; stock and journals are mirrored through
 *       {@link StockReversalService} / {@link PostingService#reverse}.</li>
 * </ul>
 *
 * Every stock move and journal of a posting carries {@code sourceType = production_stage}, {@code sourceId = posting id};
 * stage, material, output and order totals are always recomputed from the non-reversed posting lines.
 */
@Service
public class StageExecutionService implements StageCheckpointResults {

    static final String SOURCE = "production_stage";
    static final String SOURCE_REVERSAL = "production_stage_reversal";
    static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Set<String> IN_ROLES = Set.of("material", "intermediate_in", "transfer_in", "conversion");
    private static final Set<String> OUT_ROLES = Set.of("main_output", "co_product", "by_product", "waste",
            "abnormal_scrap", "transfer_out");
    private static final Set<String> RECEIPT_ROLES = Set.of("main_output", "co_product", "by_product", "waste");

    private final MfgDb db;
    private final ProductionService production;
    private final StageSetupService setup;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final StockReversalService stockReversal;
    private final StockReservationService reservations;
    private final StandardCostService standards;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final UomConversionService uom;
    private final QualityService quality;
    private final AuditService audit;
    private final ObjectProvider<StageQcCheckpointHook> hooks;
    private final ObjectProvider<StageGateListener> gateListeners;

    public StageExecutionService(MfgDb db, ProductionService production, StageSetupService setup, StockService stock,
            StockPostingService stockPosting, StockReversalService stockReversal,
            StockReservationService reservations, StandardCostService standards, PostingService posting,
            AccountResolverService accounts, UomConversionService uom, QualityService quality, AuditService audit,
            ObjectProvider<StageQcCheckpointHook> hooks, ObjectProvider<StageGateListener> gateListeners) {
        this.db = db;
        this.production = production;
        this.setup = setup;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.stockReversal = stockReversal;
        this.reservations = reservations;
        this.standards = standards;
        this.posting = posting;
        this.accounts = accounts;
        this.uom = uom;
        this.quality = quality;
        this.audit = audit;
        this.hooks = hooks;
        this.gateListeners = gateListeners;
    }

    // ================================================================== initialize

    /**
     * Turn a draft / confirmed (not started) order into a multi-stage order from its routing: one stage per operation
     * (set-up from {@code RoutingStageSetup}), materials assigned to the stage that consumes them, the BOM's
     * co-products / by-products / waste added as order outputs. Idempotent: an order with stages returns them.
     */
    @Transactional
    public Map<String, Object> initialize(String orderId, InitializeStages dto) {
        lockOrder(orderId);
        Map<String, Object> order = order(orderId);
        if (!stages(orderId).isEmpty()) {
            return view(orderId);
        }
        if (!"draft".equals(order.get("status")) && !"confirmed".equals(order.get("status"))
                || order.get("consumedAt") != null) {
            throw ApiException.badRequest("Only a draft or confirmed order that has not started can be staged (order "
                    + order.get("orderCode") + " is " + order.get("status") + ").");
        }
        Map<String, Object> routing = routingFor(order);
        List<Map<String, Object>> ops = routing == null ? List.of() : setup.operations(str(routing, "id"));
        if (ops.isEmpty()) {
            throw ApiException.badRequest("No routing with operations for this order. Create one first.");
        }
        Map<String, Object> outProduct = production.getProduct(str(order, "outputProductId"));
        List<Map<String, Object>> outputs = db.children(ProductionOutput.class, "orderId", orderId, null);
        Map<String, Object> mainLine = outputs.stream().filter(o -> "output".equals(o.get("kind"))).findFirst()
                .orElse(null);
        BigDecimal mainExpected = mainLine != null ? dec(mainLine, "qtyExpected") : dec(order, "plannedQty");
        BigDecimal mainExpectedBase = uom.toBase(mainExpected, str(order, "uomId"), str(outProduct, "id"),
                str(outProduct, "uomId"), true);
        BigDecimal planned = dec(order, "plannedQty");
        String lastOpId = str(ops.getLast(), "id");

        Map<String, String> materialStage = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            Map<String, Object> op = ops.get(i);
            Map<String, Object> s = setup.setupFor(str(op, "id"));
            boolean last = i == ops.size() - 1;
            boolean gate = s != null && Boolean.TRUE.equals(s.get("qcGate"))
                    || last && Boolean.TRUE.equals(order.get("qcRequired"));
            String intermediate = last || s == null ? null : str(s, "outputProductId");
            if (intermediate != null) {
                production.getProduct(intermediate);
            }
            BigDecimal perUnit = s == null ? BigDecimal.ONE : dec(s, "outputPerUnit");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", orderId);
            row.put("routingOperationId", op.get("id"));
            row.put("sequence", i + 1);
            row.put("name", op.get("name"));
            row.put("workCenterId", op.get("workCenterId"));
            row.put("issueMode", s == null ? "backflush" : s.get("issueMode"));
            row.put("outputProductId", last ? order.get("outputProductId") : intermediate);
            row.put("stageLocationId", last ? null
                    : s != null && s.get("stageLocationId") != null ? s.get("stageLocationId") : order.get("locationId"));
            row.put("plannedOutputQty", last ? mainExpectedBase : BomService.mul(planned, perUnit));
            row.put("expectedYieldPct", s == null ? HUNDRED : s.get("expectedYieldPct"));
            row.put("isLast", last);
            row.put("qcGate", gate);
            row.put("qcCheckpointCode", s == null ? null : s.get("qcCheckpointCode"));
            row.put("minCuringDays", s == null || s.get("minCuringDays") == null ? 0 : s.get("minCuringDays"));
            row.put("status", "pending");
            row.put("qcStatus", gate ? "pending" : "not_required");
            db.insert(ProductionOrderStage.class, row);
            if (s != null) {
                for (String pid : stringList(s.get("materialProductIds"))) {
                    materialStage.putIfAbsent(pid, str(op, "id"));
                }
            }
        }
        String firstOpId = str(ops.getFirst(), "id");
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, null)) {
            db.update(ProductionMaterial.class, str(m, "id"), Obj.of("operationId",
                    materialStage.getOrDefault(str(m, "productId"), firstOpId)));
        }
        // Main output (and legacy extra outputs) belong to the last stage.
        for (Map<String, Object> o : outputs) {
            String role = o == mainLine ? "main" : "output".equals(o.get("kind")) ? "co_product" : "by_product";
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("outputRole", role);
            upd.put("operationId", lastOpId);
            if (o == mainLine) {
                upd.put("qtyExpected", mainExpectedBase);
                upd.put("uomId", null);
            }
            db.update(ProductionOutput.class, str(o, "id"), upd);
        }
        // BOM co-products / by-products / waste, scaled to the order like BOM lines.
        if (order.get("bomId") != null) {
            Map<String, Object> bom = db.byId(Bom.class, str(order, "bomId"));
            BigDecimal scale = com.inventoryerp.kernel.common.Money.div(planned, dec(bom, "outputQuantity"));
            Set<String> opIds = new java.util.HashSet<>();
            ops.forEach(op -> opIds.add(str(op, "id")));
            for (Map<String, Object> c : setup.coProducts(str(order, "bomId"))) {
                Map<String, Object> p = production.getProduct(str(c, "productId"));
                BigDecimal qty = uom.toBase(BomService.mul(dec(c, "quantity"), scale), str(c, "uomId"), str(p, "id"),
                        str(p, "uomId"), true);
                String op = c.get("routingOperationId") != null && opIds.contains(str(c, "routingOperationId"))
                        ? str(c, "routingOperationId") : lastOpId;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", orderId);
                row.put("operationId", op);
                row.put("kind", "co_product".equals(c.get("role")) ? "output" : "byproduct");
                row.put("productId", c.get("productId"));
                row.put("productName", c.get("productName"));
                row.put("qtyExpected", qty);
                row.put("costAllocationPct", BigDecimal.ZERO);
                row.put("outputRole", c.get("role"));
                db.insert(ProductionOutput.class, row);
            }
        }
        audit.recordInTx(AuditInput.of("ProductionOrder", orderId, "update", null, Obj.of("orderCode",
                order.get("orderCode"), "stages", ops.size(), "routingId", routing.get("id"))));
        return view(orderId);
    }

    // ================================================================== read

    /** True when the order runs on stages. */
    public boolean isMultiStage(String orderId) {
        return db.jdbc().sql("SELECT count(*) FROM \"ProductionOrderStage\" WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ?").params(MfgDb.org(), orderId).query(Long.class).single() > 0;
    }

    /** The order with its stages: WIP per stage, materials, outputs, gate state, postings and lines. */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String orderId) {
        Map<String, Object> order = production.get(orderId);
        List<Map<String, Object>> stages = stages(orderId);
        List<Map<String, Object>> lines = activeLines(orderId);
        List<Map<String, Object>> materials = db.children(ProductionMaterial.class, "orderId", orderId, "sequence ASC");
        List<Map<String, Object>> outputs = db.children(ProductionOutput.class, "orderId", orderId, null);
        List<Map<String, Object>> postings = SqlRows.list(ProductionStagePosting.class, db.jdbc().sql("SELECT * FROM"
                + " \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"orderId\" = ? ORDER BY \"createdAt\","
                + " code").params(MfgDb.org(), orderId));
        List<Map<String, Object>> allLines = SqlRows.list(ProductionStagePostingLine.class, db.jdbc().sql("SELECT l.*"
                + " FROM \"ProductionStagePostingLine\" l JOIN \"ProductionStagePosting\" p ON p.id = l.\"postingId\""
                + " WHERE l.\"organizationId\" = ? AND p.\"orderId\" = ? ORDER BY l.\"createdAt\"")
                .params(MfgDb.org(), orderId));
        BigDecimal totalWip = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> s = new LinkedHashMap<>(stages.get(i));
            String op = str(s, "routingOperationId");
            BigDecimal wip = wip(s);
            totalWip = totalWip.add(wip);
            s.put("wipValue", wip);
            s.put("materials", materials.stream().filter(m -> op != null && op.equals(m.get("operationId"))).toList());
            s.put("outputs", outputs.stream().filter(o -> op != null && op.equals(o.get("operationId"))).toList());
            s.put("intermediateAvailable", i + 1 < stages.size() ? intermediateBalance(stages.get(i), stages.get(i + 1),
                    lines) : BigDecimal.ZERO);
            s.put("blockedBy", gateBlock(stages, stages.get(i)));
            List<Map<String, Object>> ps = new ArrayList<>();
            for (Map<String, Object> p : postings) {
                if (s.get("id").equals(p.get("stageId"))) {
                    Map<String, Object> pr = new LinkedHashMap<>(p);
                    pr.put("lines", allLines.stream().filter(l -> p.get("id").equals(l.get("postingId"))).toList());
                    ps.add(pr);
                }
            }
            s.put("postings", ps);
            out.add(s);
        }
        order.put("stages", out);
        order.put("wipValue", totalWip);
        order.put("multiStage", !stages.isEmpty());
        return order;
    }

    // ================================================================== issue

    /** Manual issue into a stage: stage materials and / or the previous stage's intermediate (never below zero). */
    @Transactional
    public Map<String, Object> issue(String stageId, StageIssue dto) {
        Ctx c = open(stageId);
        Instant date = date(dto.date());
        Map<String, Object> post = newPosting(c, "issue", date, dto.notes());
        String pid = str(post, "id");
        List<Map<String, Object>> materials = stageMaterials(c);
        for (IssueLine l : dto.lines()) {
            if (l.materialId() != null) {
                Map<String, Object> m = materials.stream().filter(x -> l.materialId().equals(x.get("id"))).findFirst()
                        .orElseThrow(() -> ApiException.badRequest("Material line " + l.materialId()
                                + " does not belong to stage " + c.stage.get("sequence") + "."));
                issueMaterial(c, pid, m, l.quantity(), l.batchNumber(), date, true);
            } else if (l.productId() != null && c.prev != null && l.productId().equals(c.prev.get("outputProductId"))) {
                issueIntermediate(c, pid, l.quantity(), date);
            } else {
                throw ApiException.badRequest("Issue a stage material (materialId) or the previous stage's"
                        + " intermediate product.");
            }
        }
        return finish(c, post, "issue");
    }

    // ================================================================== output

    /**
     * Record stage output (partial, or completing the stage): backflush, absorb conversion, relieve WIP with yield /
     * abnormal scrap, allocate joint cost over main + co-/by-products + waste, receive the stocked outputs.
     */
    @Transactional
    public Map<String, Object> output(String stageId, StageOutput dto) {
        Ctx c = open(stageId);
        quality.validateReason(dto.scrapReasonCode());
        boolean completes = Boolean.TRUE.equals(dto.complete());
        if (completes) {
            for (Map<String, Object> e : c.stages) {
                if (num(e) < num(c.stage) && !"completed".equals(e.get("status"))) {
                    throw ApiException.badRequest("Complete stage " + e.get("sequence") + " · " + e.get("name")
                            + " first.");
                }
            }
        }
        BigDecimal good = dto.qtyGood();
        BigDecimal scrap = dto.qtyScrap() != null ? dto.qtyScrap() : BigDecimal.ZERO;
        Instant date = date(dto.date());
        String op = str(c.stage, "routingOperationId");
        List<Map<String, Object>> stageOutputs = db.children(ProductionOutput.class, "orderId", c.orderId, null).stream()
                .filter(o -> op != null && op.equals(o.get("operationId")) && !"main".equals(o.get("outputRole")))
                .toList();
        Map<String, OutputQty> given = new HashMap<>();
        if (dto.outputs() != null) {
            for (OutputQty q : dto.outputs()) {
                if (stageOutputs.stream().noneMatch(o -> q.outputId().equals(o.get("id")))) {
                    throw ApiException.badRequest("Output line " + q.outputId() + " does not belong to stage "
                            + c.stage.get("sequence") + ".");
                }
                given.put(q.outputId(), q);
            }
        }
        BigDecimal planned = dec(c.stage, "plannedOutputQty");
        Map<String, BigDecimal> otherQty = new LinkedHashMap<>();
        BigDecimal othersExpected = BigDecimal.ZERO;
        BigDecimal others = BigDecimal.ZERO;
        for (Map<String, Object> o : stageOutputs) {
            BigDecimal q = given.containsKey(str(o, "id")) ? given.get(str(o, "id")).quantity()
                    : planned.signum() > 0 ? scale(dec(o, "qtyExpected").multiply(good).divide(planned, 10,
                            RoundingMode.HALF_UP)) : BigDecimal.ZERO;
            otherQty.put(str(o, "id"), q);
            others = others.add(q);
            othersExpected = othersExpected.add(dec(o, "qtyExpected"));
        }
        BigDecimal processedNow = good.add(others).add(scrap);
        if (processedNow.signum() == 0 && !completes) {
            throw ApiException.badRequest("Record a good or scrap quantity, or complete the stage.");
        }
        Map<String, Object> post = newPosting(c, "output", date, dto.notes());
        String pid = str(post, "id");
        BigDecimal yieldPct = dec(c.stage, "expectedYieldPct");
        BigDecimal plannedGross = planned.add(othersExpected).multiply(HUNDRED).divide(yieldPct, 10,
                RoundingMode.HALF_UP);
        BigDecimal ratio = plannedGross.signum() > 0 ? processedNow.divide(plannedGross, 10, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        // 1) Backflush the planned share of the stage's materials and of the previous stage's intermediate.
        BigDecimal direct = BigDecimal.ZERO;
        if ("backflush".equals(c.stage.get("issueMode"))) {
            for (Map<String, Object> m : stageMaterials(c)) {
                BigDecimal remaining = dec(m, "qtyPlanned").subtract(dec(m, "qtyConsumed"));
                BigDecimal qty = completes ? remaining : scale(dec(m, "qtyPlanned").multiply(ratio)).min(remaining);
                if (qty.signum() > 0) {
                    direct = direct.add(issueMaterial(c, pid, m, qty, null, date, false));
                }
            }
            if (c.prev != null && c.prev.get("outputProductId") != null) {
                BigDecimal balance = intermediateBalance(c.prev, c.stage, activeLines(c.orderId));
                BigDecimal qty = completes ? balance
                        : scale(dec(c.prev, "plannedOutputQty").multiply(ratio)).min(balance);
                if (qty.signum() <= 0 && !completes && consumedIntermediate(c).signum() == 0) {
                    throw ApiException.badRequest("Stage " + c.prev.get("sequence") + " · " + c.prev.get("name")
                            + " has no released output for stage " + c.stage.get("sequence") + ".");
                }
                if (qty.signum() > 0) {
                    direct = direct.add(issueIntermediate(c, pid, qty, date));
                }
            }
        }
        // 2) Absorb pending conversion cost (work orders of this and earlier stages; BOM overhead at the last stage).
        absorbPendingConversion(c, pid, date);

        // 3) Relieve the stage WIP: yield, abnormal scrap.
        BigDecimal pool = wip(stageTotals(str(c.stage, "id"), activeLines(c.orderId)));
        StageYieldCalculator.Result y = StageYieldCalculator.compute(new StageYieldCalculator.Input(pool, direct,
                good.add(others), scrap, planned.add(othersExpected), dec(c.stage, "qtyProcessed"), yieldPct,
                completes), SCALE);
        if (y.abnormalValue().signum() > 0 || y.abnormalQty().signum() > 0) {
            if (y.abnormalValue().signum() > 0) {
                stockPosting.postProductionScrap(y.abnormalValue(), date, SOURCE, pid, "Stage abnormal scrap · "
                        + post.get("code"));
            }
            line(pid, c.stage, "abnormal_scrap", null, y.abnormalQty(), y.abnormalValue(), null, null, null, null,
                    null, dto.scrapReasonCode());
        }

        // 4) Joint cost over the outputs of this posting.
        Map<String, Object> rule = setup.effectiveRule(str(c.order, "bomId"));
        List<Share> shares = new ArrayList<>();
        String mainProduct = str(c.stage, "outputProductId");
        BigDecimal mainMarket = SetupValues.dec(rule, "mainMarketValue");
        if (mainMarket == null && mainProduct != null) {
            mainMarket = salesPrice(mainProduct);
        }
        shares.add(new Share("main", "main", good, "allocated", SetupValues.dec(rule, "mainWeightPerUnit"), mainMarket,
                null, null));
        Map<String, Map<String, Object>> config = new HashMap<>();
        Map<String, Map<String, Object>> bomCo = new HashMap<>();
        if (c.order.get("bomId") != null) {
            setup.coProducts(str(c.order, "bomId")).forEach(r -> bomCo.put(str(r, "productId"), r));
        }
        for (Map<String, Object> o : stageOutputs) {
            Map<String, Object> cp = bomCo.get(str(o, "productId"));
            config.put(str(o, "id"), cp);
            String valuation;
            BigDecimal fixed = null;
            if (cp != null) {
                valuation = str(cp, "valuation");
                fixed = SetupValues.dec(cp, "fixedPct");
            } else if (dec(o, "costAllocationPct").signum() > 0) {
                valuation = "carve_pct"; // legacy byproduct line with a % of the pool
                fixed = dec(o, "costAllocationPct");
            } else {
                valuation = "co_product".equals(o.get("outputRole")) ? "allocated" : "zero";
            }
            BigDecimal market = cp != null && cp.get("marketValue") != null ? dec(cp, "marketValue")
                    : salesPrice(str(o, "productId"));
            BigDecimal nrv = cp != null && cp.get("nrvUnitValue") != null ? dec(cp, "nrvUnitValue")
                    : salesPrice(str(o, "productId"));
            shares.add(new Share(str(o, "id"), str(o, "outputRole"), otherQty.get(str(o, "id")), valuation,
                    SetupValues.dec(cp, "weightPerUnit"), market, fixed, nrv));
        }
        String method = rule == null ? "quantity" : str(rule, "method");
        JointCostAllocator.Result alloc = JointCostAllocator.allocate(y.goodValue(), shares, method, SCALE);
        db.update(ProductionStagePosting.class, pid, Obj.of("qtyGood", good, "qtyScrap", scrap, "qtyAbnormalScrap",
                y.abnormalQty(), "completesStage", completes, "allocationMethod", alloc.method(), "scrapReasonCode",
                dto.scrapReasonCode()));

        // 5) Receive the outputs.
        BigDecimal mainValue = alloc.values().get("main");
        boolean gated = Boolean.TRUE.equals(c.stage.get("qcGate"));
        String batch = dto.batchNumber() != null ? dto.batchNumber() : str(c.order, "batchNumber");
        String receivedTo = null;
        if (good.signum() > 0 || mainValue.signum() > 0) {
            if (mainProduct == null) {
                // Pass-through stage: cost (and units) move to the next stage in the WIP sub-ledger, no journal.
                line(pid, c.stage, "transfer_out", null, good, mainValue, null, null, null, null, null, null);
                if (c.next != null) {
                    line(pid, c.next, "transfer_in", null, good, mainValue, null, null, null, null, null, null);
                }
            } else if (good.signum() > 0) {
                boolean last = Boolean.TRUE.equals(c.stage.get("isLast"));
                String dest = gated ? production.quarantineLocation()
                        : last ? outputLocation(c.order) : str(c.stage, "stageLocationId");
                Map<String, Object> mainLine = last ? mainOutputLine(c.orderId) : null;
                receive(c, pid, "main_output", mainProduct, last ? str(c.order, "outputVariantId") : null, dest, good,
                        mainValue, batch, last ? dto.expiryDate() : null, date,
                        mainLine == null ? null : str(mainLine, "id"));
                receivedTo = dest;
            } else {
                // No good units but value left (only co-products made): the residue goes with the abnormal loss.
                stockPosting.postProductionScrap(mainValue, date, SOURCE, pid, "Stage residue · " + post.get("code"));
                line(pid, c.stage, "abnormal_scrap", null, BigDecimal.ZERO, mainValue, null, null, null, null, null,
                        null);
            }
        }
        for (Map<String, Object> o : stageOutputs) {
            BigDecimal q = otherQty.get(str(o, "id"));
            BigDecimal v = alloc.values().get(str(o, "id"));
            if (q.signum() <= 0 && v.signum() == 0) {
                continue;
            }
            Map<String, Object> cp = config.get(str(o, "id"));
            String role = str(o, "outputRole");
            boolean stocked = cp == null || !Boolean.FALSE.equals(cp.get("stocked"));
            OutputQty gq = given.get(str(o, "id"));
            if (stocked && q.signum() > 0) {
                String dest = cp != null && cp.get("locationId") != null ? str(cp, "locationId") : outputLocation(c.order);
                receive(c, pid, role, str(o, "productId"), str(o, "variantId"), dest, q, v,
                        gq != null && gq.batchNumber() != null ? gq.batchNumber() : batch, null, date, str(o, "id"));
            } else {
                if (v.signum() > 0) {
                    stockPosting.postProductionScrap(v, date, SOURCE, pid, "Unstocked output · " + post.get("code"));
                }
                line(pid, c.stage, role, str(o, "productId"), q, v, null, null, null, str(o, "id"), null, null);
            }
        }
        if (gated && good.signum() > 0 && mainProduct != null) {
            db.update(ProductionOrderStage.class, str(c.stage, "id"), Obj.of("qcStatus", "pending"));
            StageCheckpoint cp = new StageCheckpoint(MfgDb.org(), c.orderId, str(c.order, "orderCode"),
                    str(c.stage, "id"), num(c.stage), str(c.stage, "name"), str(c.stage, "qcCheckpointCode"),
                    mainProduct, good, receivedTo, pid, batch);
            hooks.orderedStream().forEach(h -> h.checkpointOpened(cp));
        }
        Map<String, Object> res = finish(c, post, "receive");
        res.put("posting", postingView(pid));
        res.put("yield", Obj.of("relieved", JsNumbers.dec(y.relieved()), "goodValue", JsNumbers.dec(y.goodValue()),
                "abnormalQty", JsNumbers.dec(y.abnormalQty()), "abnormalValue", JsNumbers.dec(y.abnormalValue()),
                "normalLossQty", JsNumbers.dec(y.normalLossQty()), "actualYieldPct",
                y.actualYieldPct() == null ? null : JsNumbers.dec(y.actualYieldPct())));
        return res;
    }

    // ================================================================== conversion cost

    /** Labour / machine / overhead / utility applied to a stage: Dr WIP / Cr the account (default overhead absorbed). */
    @Transactional
    public Map<String, Object> conversion(String stageId, StageConversion dto) {
        Ctx c = open(stageId);
        if ("completed".equals(c.stage.get("status"))) {
            throw ApiException.badRequest("Stage " + c.stage.get("sequence") + " is completed; its cost is closed.");
        }
        Instant date = date(dto.date());
        String kind = dto.kind() != null ? dto.kind() : "overhead";
        Map<String, Object> post = newPosting(c, "conversion", date, dto.notes());
        String pid = str(post, "id");
        String label = "Stage " + kind + " · " + post.get("code");
        if (dto.creditAccountId() != null) {
            String v = JsNumbers.dec(dto.amount());
            posting.post(PostingRequest.of("INV", date, label, SOURCE, pid, null, List.of(
                    Line.debit(stockPosting.wipAccount(), v, label), Line.credit(dto.creditAccountId(), v, label))));
        } else {
            stockPosting.postOverheadAbsorption(dto.amount(), date, SOURCE, pid, label);
        }
        line(pid, c.stage, "conversion", null, BigDecimal.ZERO, dto.amount(), null, null, null, null, "manual", kind);
        db.insert(ProductionCostComponent.class, Obj.of("orderId", c.orderId, "kind", kind, "amount", dto.amount(),
                "sourceType", SOURCE, "sourceId", pid, "notes", dto.notes() != null ? dto.notes() : label));
        return finish(c, post, "post");
    }

    // ================================================================== QC gate

    /** Manual (or 2.6 quality module) checkpoint decision: pass releases quarantined output, fail keeps it held. */
    @Override
    @Transactional
    public Map<String, Object> recordResult(String stageId, CheckpointDecision decision) {
        Map<String, Object> pre = stage(stageId);
        lockOrder(str(pre, "orderId"));
        Map<String, Object> s = lockStage(stageId);
        Map<String, Object> order = order(str(s, "orderId"));
        if (!Boolean.TRUE.equals(s.get("qcGate"))) {
            throw ApiException.badRequest("Stage " + s.get("sequence") + " has no QC checkpoint.");
        }
        if (!Set.of("confirmed", "in_progress", "qc_hold").contains(String.valueOf(order.get("status")))) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is " + order.get("status")
                    + "; its QC checkpoints are closed.");
        }
        BigDecimal held = dec(s, "qtyQuarantined");
        if (decision.passed() && "passed".equals(s.get("qcStatus")) && held.signum() <= 0) {
            return view(str(s, "orderId"));
        }
        if (decision.passed()) {
            // ADR-S014 (2.4): a pass before the minimum curing time is over waits (qcStatus curing) until it is due.
            Instant due = CuringHold.passDueAt(s, curingStart(s));
            if (due != null && Instant.now().isBefore(due)) {
                db.update(ProductionOrderStage.class, stageId, Obj.of("qcStatus", "curing", "qcPassDueAt", due,
                        "qcReference", decision.reference(), "qcNotes", decision.notes(), "qcDecidedAt", Instant.now(),
                        "qcDecidedBy", TenantContext.userId()));
                audit.recordInTx(AuditInput.of("ProductionOrderStage", stageId, "update", null, Obj.of("orderCode",
                        order.get("orderCode"), "curingUntil", due.toString(), "reference", decision.reference())));
                return view(str(s, "orderId"));
            }
        }
        if (!decision.passed()) {
            db.update(ProductionOrderStage.class, stageId, Obj.of("qcStatus", "failed", "qcPassDueAt", null, "qcReference",
                    decision.reference(), "qcNotes", decision.notes(), "qcDecidedAt", Instant.now(), "qcDecidedBy",
                    TenantContext.userId()));
            audit.recordInTx(AuditInput.of("ProductionOrderStage", stageId, "reject", null, Obj.of("orderCode",
                    order.get("orderCode"), "reference", decision.reference())));
            gateListeners.orderedStream().forEach(l -> l.gateDecided(stageId, "failed"));
            return view(str(s, "orderId"));
        }
        Ctx c = ctx(s, order);
        if (held.signum() > 0) {
            Map<String, Object> post = newPosting(c, "qc_release", Instant.now(), decision.notes());
            String pid = str(post, "id");
            String dest = Boolean.TRUE.equals(s.get("isLast")) ? outputLocation(order) : str(s, "stageLocationId");
            String quarantine = production.quarantineLocation();
            stock.transfer(StockMove.transfer(str(s, "outputProductId"), quarantine, dest, held)
                    .variant(Boolean.TRUE.equals(s.get("isLast")) ? str(order, "outputVariantId") : null)
                    .source(SOURCE, pid).notes("Stage QC pass · " + post.get("code")));
            line(pid, s, "qc_release", str(s, "outputProductId"), held, BigDecimal.ZERO, dest, null, null, null, null,
                    decision.reference());
        }
        db.update(ProductionOrderStage.class, stageId, Obj.of("qcStatus", "passed", "qcPassDueAt", null, "qcReference",
                decision.reference(), "qcNotes", decision.notes(), "qcDecidedAt", Instant.now(), "qcDecidedBy",
                TenantContext.userId()));
        refresh(str(s, "orderId"));
        audit.recordInTx(AuditInput.of("ProductionOrderStage", stageId, "approve", null, Obj.of("orderCode",
                order.get("orderCode"), "released", JsNumbers.dec(held), "reference", decision.reference())));
        gateListeners.orderedStream().forEach(l -> l.gateDecided(stageId, "passed"));
        return view(str(s, "orderId"));
    }

    /**
     * Start of the curing clock of a gated stage (ADR-S014): the latest output of the stage before it (the units
     * that entered curing), else the stage's own latest output; null when nothing was posted.
     */
    Instant curingStart(Map<String, Object> stage) {
        List<Map<String, Object>> all = stages(str(stage, "orderId"));
        String prevId = null;
        for (Map<String, Object> e : all) {
            if (num(e) < num(stage)) {
                prevId = str(e, "id");
            }
        }
        String sql = "SELECT max(\"postedAt\") FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ?"
                + " AND \"stageId\" = ? AND kind = 'output' AND \"reversedAt\" IS NULL";
        Instant start = prevId == null ? null : maxPostedAt(sql, prevId);
        return start != null ? start : maxPostedAt(sql, str(stage, "id"));
    }

    private Instant maxPostedAt(String sql, String stageId) {
        java.time.LocalDateTime at = db.jdbc().sql(sql).params(MfgDb.org(), stageId)
                .query(java.time.LocalDateTime.class).optional().orElse(null);
        return at == null ? null : at.toInstant(java.time.ZoneOffset.UTC);
    }

    // ================================================================== reversal

    /** Reverse a stage posting; only the latest open posting of its order can be reversed (last in, first out). */
    @Transactional
    public Map<String, Object> reversePosting(String postingId, String reason) {
        Map<String, Object> p = db.byId(ProductionStagePosting.class, postingId);
        if (p == null) {
            throw ApiException.notFound("Stage posting not found");
        }
        lockOrder(str(p, "orderId"));
        reverseOne(postingId, reason);
        refresh(str(p, "orderId"));
        return view(str(p, "orderId"));
    }

    /** Reverse every posting of a multi-stage order (LIFO) and cancel it. */
    @Transactional
    public Map<String, Object> reverseOrder(String orderId, String reason) {
        lockOrder(orderId);
        Map<String, Object> order = order(orderId);
        if (!isMultiStage(orderId)) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is not a multi-stage order.");
        }
        if (order.get("reversedAt") != null || "cancelled".equals(order.get("status"))) {
            throw ApiException.badRequest("Order already reversed.");
        }
        String latest;
        while ((latest = latestOpenPosting(orderId)) != null) {
            reverseOne(latest, reason);
        }
        refresh(orderId);
        db.update(ProductionOrder.class, orderId, Obj.of("status", "cancelled", "reversedAt", Instant.now(),
                "reversalReason", reason, "cancelledAt", Instant.now(), "updatedBy", TenantContext.userId()));
        db.jdbc().sql("UPDATE \"ProductionOrderStage\" SET status = 'cancelled', \"updatedAt\" = " + MfgDb.NOW
                + " WHERE \"organizationId\" = ? AND \"orderId\" = ?").params(MfgDb.org(), orderId).update();
        reservations.release(ProductionService.SOURCE, orderId);
        audit.recordInTx(AuditInput.of("ProductionOrder", orderId, "cancel", null, Obj.of("orderCode",
                order.get("orderCode"), "reason", reason, "stages", true)));
        return view(orderId);
    }

    private void reverseOne(String postingId, String reason) {
        Map<String, Object> p = db.byId(ProductionStagePosting.class, postingId);
        if (p.get("reversedAt") != null) {
            throw ApiException.badRequest("Stage posting " + p.get("code") + " is already reversed.");
        }
        String latest = latestOpenPosting(str(p, "orderId"));
        if (!postingId.equals(latest)) {
            throw ApiException.badRequest("Reverse the later postings of this order first (latest: "
                    + db.byId(ProductionStagePosting.class, latest).get("code") + ").");
        }
        String note = "Reversal of stage posting " + p.get("code") + (reason == null ? "" : ": " + reason);
        // Receipts must still be fully on hand where they landed.
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProductionStagePostingLine\" WHERE"
                + " \"organizationId\" = ? AND \"postingId\" = ? AND \"locationId\" IS NOT NULL AND quantity > 0")
                .params(MfgDb.org(), postingId))) {
            if (!RECEIPT_ROLES.contains(str(l, "role")) && !"qc_release".equals(l.get("role"))) {
                continue;
            }
            BigDecimal onHand = db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?")
                    .params(MfgDb.org(), l.get("productId"), l.get("locationId")).query(BigDecimal.class).single();
            if (onHand.compareTo(dec(l, "quantity")) < 0) {
                throw ApiException.badRequest("Cannot reverse " + p.get("code")
                        + ": its output is no longer fully in stock (consumed, sold or moved).");
            }
        }
        long ledgerRows = db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"referenceType\" = ? AND \"referenceId\" = ?").params(MfgDb.org(), SOURCE, postingId)
                .query(Long.class).single();
        if (ledgerRows > 0) {
            stockReversal.reverseLedgerSource(new LedgerSource(SOURCE, postingId, SOURCE_REVERSAL, postingId,
                    List.of(SOURCE), note, null));
        } else {
            for (String je : db.jdbc().sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                    + " AND \"sourceType\" = ? AND \"sourceId\" = ? AND status = 'posted' AND \"reversalOfId\" IS NULL")
                    .params(MfgDb.org(), SOURCE, postingId).query(String.class).list()) {
                posting.reverse(je, Instant.now(), note);
            }
        }
        db.update(ProductionStagePosting.class, postingId, Obj.of("reversedAt", Instant.now(), "reversalReason",
                reason, "reversedBy", TenantContext.userId()));
        if ("conversion".equals(p.get("kind"))) {
            db.jdbc().sql("DELETE FROM \"ProductionCostComponent\" WHERE \"organizationId\" = ? AND \"sourceType\" = ?"
                    + " AND \"sourceId\" = ?").params(MfgDb.org(), SOURCE, postingId).update();
        }
        if ("qc_release".equals(p.get("kind"))) {
            db.update(ProductionOrderStage.class, str(p, "stageId"), Obj.of("qcStatus", "pending"));
        }
        audit.recordInTx(AuditInput.of("ProductionStagePosting", postingId, "cancel", null, Obj.of("code",
                p.get("code"), "reason", reason)));
    }

    private String latestOpenPosting(String orderId) {
        return db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"orderId\" = ?"
                + " AND \"reversedAt\" IS NULL ORDER BY \"createdAt\" DESC, code DESC LIMIT 1")
                .params(MfgDb.org(), orderId).query(String.class).optional().orElse(null);
    }

    // ================================================================== posting internals

    /** Locked context of a stage posting: order + stages + neighbours, all checks done. */
    private record Ctx(String orderId, Map<String, Object> order, Map<String, Object> stage,
            List<Map<String, Object>> stages, Map<String, Object> prev, Map<String, Object> next) {}

    private Ctx open(String stageId) {
        Map<String, Object> pre = stage(stageId);
        lockOrder(str(pre, "orderId"));
        Map<String, Object> s = lockStage(stageId);
        Map<String, Object> order = order(str(s, "orderId"));
        String st = String.valueOf(order.get("status"));
        if ("draft".equals(st)) {
            throw ApiException.badRequest("Confirm order " + order.get("orderCode") + " before posting to its stages.");
        }
        if (!"confirmed".equals(st) && !"in_progress".equals(st)) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is " + st
                    + "; its stages are closed.");
        }
        if ("completed".equals(s.get("status")) || "cancelled".equals(s.get("status"))) {
            throw ApiException.badRequest("Stage " + s.get("sequence") + " · " + s.get("name") + " is "
                    + s.get("status") + ".");
        }
        Ctx c = ctx(s, order);
        String blocked = gateBlock(c.stages, s);
        if (blocked != null) {
            throw ApiException.badRequest(blocked);
        }
        return c;
    }

    private Ctx ctx(Map<String, Object> s, Map<String, Object> order) {
        List<Map<String, Object>> stages = stages(str(s, "orderId"));
        Map<String, Object> prev = null;
        Map<String, Object> next = null;
        for (int i = 0; i < stages.size(); i++) {
            if (s.get("id").equals(stages.get(i).get("id"))) {
                prev = i > 0 ? stages.get(i - 1) : null;
                next = i + 1 < stages.size() ? stages.get(i + 1) : null;
            }
        }
        return new Ctx(str(s, "orderId"), order, s, stages, prev, next);
    }

    /** Message when an earlier QC-gated stage has not passed, else null. */
    static String gateBlock(List<Map<String, Object>> stages, Map<String, Object> stage) {
        for (Map<String, Object> e : stages) {
            if (num(e) < num(stage) && Boolean.TRUE.equals(e.get("qcGate")) && !"passed".equals(e.get("qcStatus"))) {
                return "Stage " + e.get("sequence") + " · " + e.get("name") + " is held at its QC checkpoint ("
                        + e.get("qcStatus") + "); stage " + stage.get("sequence") + " cannot proceed.";
            }
        }
        return null;
    }

    private Map<String, Object> newPosting(Ctx c, String kind, Instant date, String notes) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ?").params(MfgDb.org(), c.orderId).query(Long.class).single();
        String code = c.order.get("orderCode") + "-S" + c.stage.get("sequence") + "-" + String.format("%03d", n + 1);
        return db.insert(ProductionStagePosting.class, Obj.of("orderId", c.orderId, "stageId", c.stage.get("id"),
                "code", code, "kind", kind, "postedAt", date, "notes", notes, "createdBy", TenantContext.userId()));
    }

    private Map<String, Object> finish(Ctx c, Map<String, Object> post, String action) {
        refresh(c.orderId);
        audit.recordInTx(AuditInput.of("ProductionStagePosting", str(post, "id"), action, null, Obj.of("code",
                post.get("code"), "orderCode", c.order.get("orderCode"), "stage", c.stage.get("sequence"))));
        Map<String, Object> after = order(c.orderId);
        if (("completed".equals(after.get("status")) || "qc_hold".equals(after.get("status")))
                && !Set.of("completed", "qc_hold").contains(String.valueOf(c.order.get("status")))) {
            // Released after the fact (not consumed): the stage legs already moved real stock.
            reservations.release(ProductionService.SOURCE, c.orderId);
        }
        return view(c.orderId);
    }

    private BigDecimal issueMaterial(Ctx c, String pid, Map<String, Object> m, BigDecimal qty, String batchNumber,
            Instant date, boolean strict) {
        String code = str(c.order, "orderCode");
        StockMove move = StockMove.of(str(m, "productId"), str(c.order, "locationId"), qty).variant(str(m, "variantId"))
                .uom(str(m, "uomId")).moveType("production_consume")
                .strategy(m.get("distStrategy") != null ? str(m, "distStrategy") : "FEFO")
                .batch(batchNumber != null ? batchNumber : str(m, "batchNumber")).date(date).source(SOURCE, pid)
                .notes(code).skipGl().requireAvailable(strict);
        Issued res = stock.issue(move);
        stockPosting.postProductionConsume(str(m, "productId"), res.totalValue(), date, SOURCE, pid,
                "Stage material · " + code);
        line(pid, c.stage, "material", str(m, "productId"), qty, res.totalValue(), str(c.order, "locationId"),
                res.ledgerCode(), str(m, "id"), null, null, null);
        return res.totalValue();
    }

    private BigDecimal issueIntermediate(Ctx c, String pid, BigDecimal qty, Instant date) {
        BigDecimal balance = intermediateBalance(c.prev, c.stage, activeLines(c.orderId));
        if (qty.compareTo(balance) > 0) {
            throw ApiException.badRequest("Only " + JsNumbers.dec(balance) + " of stage " + c.prev.get("sequence")
                    + "'s released output is available for stage " + c.stage.get("sequence") + ".");
        }
        String product = str(c.prev, "outputProductId");
        StockMove move = StockMove.of(product, str(c.prev, "stageLocationId"), qty).moveType("production_consume")
                .date(date).source(SOURCE, pid).notes(str(c.order, "orderCode")).skipGl().requireAvailable(true);
        Issued res = stock.issue(move);
        stockPosting.postProductionConsume(product, res.totalValue(), date, SOURCE, pid, "Stage intermediate in · "
                + c.order.get("orderCode"));
        line(pid, c.stage, "intermediate_in", product, qty, res.totalValue(), str(c.prev, "stageLocationId"),
                res.ledgerCode(), null, null, null, null);
        return res.totalValue();
    }

    /** One stocked output: receipt at the allocated value (standard-governed: at standard + yield variance). */
    private void receive(Ctx c, String pid, String role, String productId, String variantId, String locationId,
            BigDecimal qty, BigDecimal value, String batch, String expiry, Instant date, String outputId) {
        BigDecimal standard = standards.activeStandard(productId).orElse(null);
        BigDecimal stockValue = standard != null ? standard.multiply(qty).setScale(SCALE, RoundingMode.HALF_UP) : value;
        BigDecimal unit = standard != null ? standard
                : qty.signum() > 0 ? value.divide(qty, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        StockMove move = StockMove.of(productId, locationId, qty).variant(variantId).unitCost(unit)
                .moveType("production_output").batch(batch).expiry(expiry).source(SOURCE, pid).date(date);
        Received res = stock.receive(move);
        String label = "Stage output · " + c.order.get("orderCode");
        stockPosting.postProductionOutput(productId, stockValue, date, SOURCE, pid, label);
        BigDecimal variance = value.subtract(stockValue);
        if (variance.signum() != 0) {
            // One variance engine (ADR-S034): same accounts and register as single-leg orders.
            standards.postStageVariance(str(c.order, "id"), str(c.order, "orderCode"), productId, variance, qty,
                    stockValue, stockPosting.wipAccount(), date, SOURCE, pid, label);
        }
        line(pid, c.stage, role, productId, qty, value, locationId, res.ledgerCode(), null, outputId, null, null);
        if (outputId != null) {
            db.update(ProductionOutput.class, outputId, Obj.of("batchNumber", batch, "ledgerCode", res.ledgerCode(),
                    "batchId", db.jdbc().sql("SELECT \"batchId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                            + " AND \"ledgerCode\" = ? LIMIT 1").params(MfgDb.org(), res.ledgerCode())
                            .query(String.class).optional().orElse(null)));
        }
    }

    /** Work-order cost of this and earlier stages not yet absorbed; at the last stage also the BOM overhead. */
    private void absorbPendingConversion(Ctx c, String pid, Instant date) {
        List<String> ops = new ArrayList<>();
        for (Map<String, Object> s : c.stages) {
            if (num(s) <= num(c.stage) && s.get("routingOperationId") != null) {
                ops.add(str(s, "routingOperationId"));
            }
        }
        BigDecimal wo = ops.isEmpty() ? BigDecimal.ZERO : db.jdbc().sql("SELECT coalesce(sum(cc.amount), 0) FROM"
                + " \"ProductionCostComponent\" cc JOIN \"WorkOrder\" w ON w.id = cc.\"sourceId\""
                + " WHERE cc.\"organizationId\" = :org AND cc.\"orderId\" = :order AND cc.\"sourceType\" = 'work_order'"
                + " AND w.\"routingOperationId\" IN (:ops)").param("org", MfgDb.org()).param("order", c.orderId)
                .param("ops", ops).query(BigDecimal.class).single();
        BigDecimal woAbsorbed = absorbed(c.orderId, "work_order");
        BigDecimal pendingWo = wo.subtract(woAbsorbed);
        if (pendingWo.signum() > 0) {
            stockPosting.postOverheadAbsorption(pendingWo, date, SOURCE, pid, "Stage labour absorbed · "
                    + c.order.get("orderCode"));
            line(pid, c.stage, "conversion", null, BigDecimal.ZERO, pendingWo, null, null, null, null, "work_order",
                    null);
        }
        if (Boolean.TRUE.equals(c.stage.get("isLast"))) {
            BigDecimal bomOverhead = db.jdbc().sql("SELECT coalesce(sum(amount), 0) FROM \"ProductionCostComponent\""
                    + " WHERE \"organizationId\" = ? AND \"orderId\" = ? AND \"sourceType\" = ? AND kind = 'overhead'")
                    .params(MfgDb.org(), c.orderId, ProductionService.SOURCE).query(BigDecimal.class).single();
            BigDecimal pending = bomOverhead.subtract(absorbed(c.orderId, "bom_overhead"));
            if (pending.signum() > 0) {
                stockPosting.postOverheadAbsorption(pending, date, SOURCE, pid, "BOM overhead absorbed · "
                        + c.order.get("orderCode"));
                line(pid, c.stage, "conversion", null, BigDecimal.ZERO, pending, null, null, null, null,
                        "bom_overhead", null);
            }
        }
    }

    private BigDecimal absorbed(String orderId, String costSource) {
        return db.jdbc().sql("SELECT coalesce(sum(l.value), 0) FROM \"ProductionStagePostingLine\" l JOIN"
                + " \"ProductionStagePosting\" p ON p.id = l.\"postingId\" WHERE l.\"organizationId\" = ?"
                + " AND p.\"orderId\" = ? AND p.\"reversedAt\" IS NULL AND l.role = 'conversion' AND l.\"costSource\" = ?")
                .params(MfgDb.org(), orderId, costSource).query(BigDecimal.class).single();
    }

    private void line(String postingId, Map<String, Object> stage, String role, String productId, BigDecimal qty,
            BigDecimal value, String locationId, String ledgerCode, String materialId, String outputId,
            String costSource, String sourceRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("postingId", postingId);
        row.put("stageId", stage.get("id"));
        row.put("role", role);
        row.put("productId", productId);
        row.put("quantity", qty == null ? BigDecimal.ZERO : qty);
        row.put("unitCost", qty != null && qty.signum() > 0 ? value.divide(qty, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        row.put("value", value);
        row.put("locationId", locationId);
        row.put("ledgerCode", ledgerCode);
        row.put("materialId", materialId);
        row.put("outputId", outputId);
        row.put("costSource", costSource);
        row.put("sourceRef", sourceRef);
        db.insert(ProductionStagePostingLine.class, row);
    }

    // ================================================================== totals (always recomputed)

    /** Recompute stage, material, output and order totals and statuses from the non-reversed posting lines. */
    void refresh(String orderId) {
        List<Map<String, Object>> stages = stages(orderId);
        if (stages.isEmpty()) {
            return;
        }
        List<Map<String, Object>> lines = activeLines(orderId);
        List<Map<String, Object>> posts = SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProductionStagePosting\" WHERE"
                + " \"organizationId\" = ? AND \"orderId\" = ? AND \"reversedAt\" IS NULL").params(MfgDb.org(), orderId));
        String quarantine = null;
        Instant firstAt = null;
        Instant lastAt = null;
        for (Map<String, Object> p : posts) {
            Instant at = (Instant) p.get("postedAt");
            firstAt = firstAt == null || at.isBefore(firstAt) ? at : firstAt;
            lastAt = lastAt == null || at.isAfter(lastAt) ? at : lastAt;
        }
        BigDecimal producedQty = BigDecimal.ZERO;
        BigDecimal scrapQty = BigDecimal.ZERO;
        boolean lastDone = false;
        boolean lastHeld = false;
        for (Map<String, Object> s : stages) {
            String sid = str(s, "id");
            Map<String, Object> t = stageTotals(sid, lines);
            BigDecimal qtyScrap = BigDecimal.ZERO;
            boolean any = false;
            boolean completed = false;
            Instant started = null;
            Instant done = null;
            for (Map<String, Object> p : posts) {
                if (!sid.equals(p.get("stageId"))) {
                    continue;
                }
                any = true;
                qtyScrap = qtyScrap.add(dec(p, "qtyScrap"));
                Instant at = (Instant) p.get("postedAt");
                started = started == null || at.isBefore(started) ? at : started;
                if (Boolean.TRUE.equals(p.get("completesStage"))) {
                    completed = true;
                    done = at;
                }
            }
            BigDecimal good = BigDecimal.ZERO;
            BigDecimal joint = BigDecimal.ZERO;
            BigDecimal quarantined = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                if (!sid.equals(l.get("stageId"))) {
                    continue;
                }
                String role = str(l, "role");
                BigDecimal q = dec(l, "quantity");
                if ("main_output".equals(role) || "transfer_out".equals(role)) {
                    good = good.add(q);
                }
                if (RECEIPT_ROLES.contains(role) || "transfer_out".equals(role)) {
                    joint = joint.add(q);
                }
                if ("main_output".equals(role) && Boolean.TRUE.equals(s.get("qcGate"))) {
                    if (quarantine == null) {
                        quarantine = production.quarantineLocation();
                    }
                    if (quarantine.equals(l.get("locationId"))) {
                        quarantined = quarantined.add(q);
                    }
                }
                if ("qc_release".equals(role)) {
                    quarantined = quarantined.subtract(q);
                }
            }
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("materialCost", t.get("materialCost"));
            upd.put("transferredInCost", t.get("transferredInCost"));
            upd.put("conversionCost", t.get("conversionCost"));
            upd.put("outputCost", t.get("outputCost"));
            upd.put("scrapCost", t.get("scrapCost"));
            upd.put("qtyGood", good);
            upd.put("qtyScrap", qtyScrap);
            upd.put("qtyProcessed", joint.add(qtyScrap));
            upd.put("qtyQuarantined", quarantined.max(BigDecimal.ZERO));
            if (!"cancelled".equals(s.get("status"))) {
                upd.put("status", completed ? "completed" : any ? "in_progress" : "pending");
            }
            upd.put("startedAt", started);
            upd.put("completedAt", completed ? done : null);
            if (Boolean.TRUE.equals(s.get("qcGate")) && "passed".equals(s.get("qcStatus"))
                    && quarantined.signum() > 0) {
                upd.put("qcStatus", "pending");
            }
            db.update(ProductionOrderStage.class, sid, upd);
            scrapQty = scrapQty.add(qtyScrap);
            if (Boolean.TRUE.equals(s.get("isLast"))) {
                producedQty = good;
                lastDone = completed;
                boolean passed = "passed".equals(upd.getOrDefault("qcStatus", s.get("qcStatus")));
                lastHeld = Boolean.TRUE.equals(s.get("qcGate")) && (!passed || quarantined.signum() > 0);
            }
        }
        // Materials and outputs.
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, null)) {
            BigDecimal q = BigDecimal.ZERO;
            BigDecimal v = BigDecimal.ZERO;
            String ledger = null;
            for (Map<String, Object> l : lines) {
                if (m.get("id").equals(l.get("materialId"))) {
                    q = q.add(dec(l, "quantity"));
                    v = v.add(dec(l, "value"));
                    ledger = str(l, "ledgerCode");
                }
            }
            db.update(ProductionMaterial.class, str(m, "id"), Obj.of("qtyConsumed", q, "totalCost", v, "unitCost",
                    q.signum() > 0 ? v.divide(q, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO, "ledgerCode", ledger));
        }
        BigDecimal mainValue = BigDecimal.ZERO;
        BigDecimal mainQty = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Map<String, Object> o : db.children(ProductionOutput.class, "orderId", orderId, null)) {
            BigDecimal q = BigDecimal.ZERO;
            BigDecimal v = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                if (o.get("id").equals(l.get("outputId"))) {
                    q = q.add(dec(l, "quantity"));
                    v = v.add(dec(l, "value"));
                }
            }
            db.update(ProductionOutput.class, str(o, "id"), Obj.of("qtyProduced", q, "totalCost", v, "unitCost",
                    q.signum() > 0 ? v.divide(q, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO));
            if ("main".equals(o.get("outputRole"))) {
                mainValue = v;
                mainQty = q;
            } else if (o.get("outputRole") != null) {
                credit = credit.add(v);
            }
        }
        BigDecimal materialCost = BigDecimal.ZERO;
        BigDecimal conversion = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            if ("material".equals(l.get("role"))) {
                materialCost = materialCost.add(dec(l, "value"));
            } else if ("conversion".equals(l.get("role"))) {
                conversion = conversion.add(dec(l, "value"));
            }
        }
        Map<String, Object> order = order(orderId);
        if ("cancelled".equals(order.get("status"))) {
            return;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("materialCost", materialCost);
        o.put("totalCost", materialCost.add(conversion));
        o.put("producedQty", producedQty);
        o.put("scrapQty", scrapQty);
        o.put("byproductCredit", credit);
        o.put("outputUnitCost", mainQty.signum() > 0 ? mainValue.divide(mainQty, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        o.put("updatedBy", TenantContext.userId());
        if (lastDone) {
            o.put("status", lastHeld ? "qc_hold" : "completed");
            o.put("postedAt", order.get("postedAt") != null ? order.get("postedAt") : Instant.now());
            o.put("completedAt", lastHeld ? null : order.get("completedAt") != null ? order.get("completedAt") : lastAt);
        } else if (!posts.isEmpty()) {
            o.put("status", "in_progress");
            o.put("postedAt", null);
            o.put("completedAt", null);
            o.put("startedAt", firstAt);
            o.put("consumedAt", order.get("consumedAt") != null ? order.get("consumedAt") : Instant.now());
        } else {
            o.put("status", "confirmed");
            o.put("postedAt", null);
            o.put("completedAt", null);
            o.put("startedAt", null);
            o.put("consumedAt", null);
        }
        db.update(ProductionOrder.class, orderId, o);
    }

    /** Cost totals of one stage from the given (non-reversed) lines. */
    static Map<String, Object> stageTotals(String stageId, List<Map<String, Object>> lines) {
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal transferredIn = BigDecimal.ZERO;
        BigDecimal conversion = BigDecimal.ZERO;
        BigDecimal output = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            if (!stageId.equals(l.get("stageId"))) {
                continue;
            }
            BigDecimal v = dec(l, "value");
            switch (str(l, "role")) {
                case "material" -> material = material.add(v);
                case "intermediate_in", "transfer_in" -> transferredIn = transferredIn.add(v);
                case "conversion" -> conversion = conversion.add(v);
                case "abnormal_scrap" -> scrap = scrap.add(v);
                default -> {
                    if (OUT_ROLES.contains(str(l, "role"))) {
                        output = output.add(v);
                    }
                }
            }
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("materialCost", material);
        t.put("transferredInCost", transferredIn);
        t.put("conversionCost", conversion);
        t.put("outputCost", output);
        t.put("scrapCost", scrap);
        return t;
    }

    /** Stage WIP = material + transferred-in + conversion − outputs − abnormal scrap. */
    static BigDecimal wip(Map<String, Object> s) {
        return dec(s, "materialCost").add(dec(s, "transferredInCost")).add(dec(s, "conversionCost"))
                .subtract(dec(s, "outputCost")).subtract(dec(s, "scrapCost"));
    }

    /** This order's intermediate of {@code from} still available to {@code to}: released − consumed. */
    BigDecimal intermediateBalance(Map<String, Object> from, Map<String, Object> to, List<Map<String, Object>> lines) {
        if (from.get("outputProductId") == null || Boolean.TRUE.equals(from.get("isLast"))) {
            return BigDecimal.ZERO;
        }
        BigDecimal released = BigDecimal.ZERO;
        BigDecimal consumed = BigDecimal.ZERO;
        String dest = str(from, "stageLocationId");
        for (Map<String, Object> l : lines) {
            if (from.get("id").equals(l.get("stageId")) && dest != null && dest.equals(l.get("locationId"))
                    && ("main_output".equals(l.get("role")) || "qc_release".equals(l.get("role")))) {
                released = released.add(dec(l, "quantity"));
            }
            if (to.get("id").equals(l.get("stageId")) && "intermediate_in".equals(l.get("role"))) {
                consumed = consumed.add(dec(l, "quantity"));
            }
        }
        return released.subtract(consumed).max(BigDecimal.ZERO);
    }

    private BigDecimal consumedIntermediate(Ctx c) {
        BigDecimal q = BigDecimal.ZERO;
        for (Map<String, Object> l : activeLines(c.orderId)) {
            if (c.stage.get("id").equals(l.get("stageId")) && "intermediate_in".equals(l.get("role"))) {
                q = q.add(dec(l, "quantity"));
            }
        }
        return q;
    }

    /** Lines of the order's non-reversed postings. */
    List<Map<String, Object>> activeLines(String orderId) {
        return SqlRows.list(db.jdbc().sql("SELECT l.* FROM \"ProductionStagePostingLine\" l JOIN"
                + " \"ProductionStagePosting\" p ON p.id = l.\"postingId\" WHERE l.\"organizationId\" = ?"
                + " AND p.\"orderId\" = ? AND p.\"reversedAt\" IS NULL ORDER BY l.\"createdAt\"")
                .params(MfgDb.org(), orderId));
    }

    private Map<String, Object> postingView(String postingId) {
        Map<String, Object> p = db.byId(ProductionStagePosting.class, postingId);
        p.put("lines", db.children(ProductionStagePostingLine.class, "postingId", postingId, "\"createdAt\""));
        return p;
    }

    // ================================================================== lookups

    List<Map<String, Object>> stages(String orderId) {
        return db.list(ProductionOrderStage.class, " AND \"orderId\" = ? ORDER BY sequence ASC", orderId);
    }

    private Map<String, Object> stage(String id) {
        Map<String, Object> s = db.byId(ProductionOrderStage.class, id);
        if (s == null) {
            throw ApiException.notFound("Production stage not found");
        }
        return s;
    }

    private Map<String, Object> lockStage(String id) {
        return SqlRows.one(ProductionOrderStage.class, db.jdbc().sql("SELECT * FROM \"ProductionOrderStage\" WHERE"
                + " id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, MfgDb.org()));
    }

    private Map<String, Object> order(String id) {
        Map<String, Object> o = db.byId(ProductionOrder.class, id);
        if (o == null) {
            throw ApiException.notFound("Production order not found");
        }
        return o;
    }

    private List<Map<String, Object>> stageMaterials(Ctx c) {
        String op = str(c.stage, "routingOperationId");
        return db.children(ProductionMaterial.class, "orderId", c.orderId, "sequence ASC").stream()
                .filter(m -> op != null && op.equals(m.get("operationId"))).toList();
    }

    private Map<String, Object> mainOutputLine(String orderId) {
        return db.children(ProductionOutput.class, "orderId", orderId, null).stream()
                .filter(o -> "main".equals(o.get("outputRole"))).findFirst().orElse(null);
    }

    private Map<String, Object> routingFor(Map<String, Object> order) {
        String routingId = null;
        if (order.get("bomId") != null) {
            Map<String, Object> bom = db.byId(Bom.class, str(order, "bomId"));
            routingId = bom == null ? null : str(bom, "routingId");
        }
        Map<String, Object> routing = routingId != null ? db.byId(Routing.class, routingId) : null;
        if (routing == null && order.get("bomId") != null) {
            List<Map<String, Object>> rows = db.list(Routing.class, " AND (\"bomId\" = ? OR \"productId\" = ?)"
                    + " AND \"isActive\" = true ORDER BY \"createdAt\" LIMIT 1", order.get("bomId"),
                    order.get("outputProductId"));
            routing = rows.isEmpty() ? null : rows.getFirst();
        }
        return routing;
    }

    private static String outputLocation(Map<String, Object> order) {
        return order.get("outputLocationId") != null ? str(order, "outputLocationId") : str(order, "locationId");
    }

    private BigDecimal salesPrice(String productId) {
        return db.jdbc().sql("SELECT coalesce(\"salesPrice\", 0) FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, MfgDb.org()).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    /** Serialise every posting of one order with the legacy production legs (same advisory lock key). */
    private void lockOrder(String orderId) {
        String key = "production:" + MfgDb.org() + ":" + orderId;
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param(key).query().listOfRows();
    }

    private static int num(Map<String, Object> stage) {
        return ((Number) stage.get("sequence")).intValue();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? Instant.now() : EntityPatch.toInstant(iso);
    }

    /** Nullable decimals of set-up rows. */
    static final class SetupValues {
        private SetupValues() {}

        static BigDecimal dec(Map<String, Object> row, String key) {
            return row == null || row.get(key) == null ? null : SqlRows.dec(row, key);
        }
    }

    /** The strings of a JSON array column (a Jackson tree) or a list. */
    static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof tools.jackson.databind.JsonNode n && n.isArray()) {
            n.forEach(e -> out.add(e.asString()));
        } else if (v instanceof List<?> l) {
            l.forEach(e -> out.add(String.valueOf(e)));
        }
        return out;
    }
}
