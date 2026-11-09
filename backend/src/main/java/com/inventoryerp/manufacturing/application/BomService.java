package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.BomLineInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.UpdateBom;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.BomLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/**
 * Bills of materials (port of {@code bom.service.ts}): versioned recipes per output product, the pure explosion math,
 * activation behind the approval engine (entityType {@code bom_version}), BOM resolution for production orders and
 * the multi-level requirement explosion used by shortage checks.
 */
@Service
public class BomService {

    /** A BOM line as needed by the explosion math (pure, DB-free). */
    public record ExplodableLine(String componentProductId, String componentVariantId, BigDecimal quantity,
            String uomId, BigDecimal scrapPct, Integer sequence) {}

    /** A BOM as needed by the explosion math. */
    public record ExplodableBom(BigDecimal outputQuantity, BigDecimal expectedYieldPct, List<ExplodableLine> lines) {}

    /** One exploded material requirement. {@code uomId} is carried verbatim (converted later, inside issue). */
    public record ExplodedMaterial(String componentProductId, String componentVariantId, BigDecimal qtyPlanned,
            String uomId, int sequence) {}

    /** {@code expectedOutputQty} = planned × expected yield %. */
    public record ExplodedBom(BigDecimal expectedOutputQty, List<ExplodedMaterial> materials) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PlmGate plmGate;
    private final MfgDb db;
    private final SequenceService seq;
    private final ApprovalsService approvals;
    private final TransactionTemplate newTx;

    public BomService(MfgDb db, SequenceService seq, ApprovalsService approvals, PlatformTransactionManager txm,
            PlmGate plmGate) {
        this.plmGate = plmGate;
        this.db = db;
        this.seq = seq;
        this.approvals = approvals;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================================================================== explosion math (pure)

    /** decimal.js {@code a.mul(b)}: 20 significant digits, half-up. */
    static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, Money.DECIMAL_JS);
    }

    /**
     * Scale a BOM to {@code plannedQty} output units: {@code scale = plannedQty / outputQuantity},
     * {@code line.planned = line.quantity × scale × (1 + scrapPct/100)}.
     */
    public static ExplodedBom explode(ExplodableBom bom, BigDecimal plannedQty) {
        BigDecimal output = bom.outputQuantity() == null ? BigDecimal.ZERO : bom.outputQuantity();
        if (output.signum() <= 0) {
            throw ApiException.badRequest("BOM outputQuantity must be > 0");
        }
        BigDecimal scale = Money.div(plannedQty, output);
        BigDecimal yieldPct = bom.expectedYieldPct() != null ? bom.expectedYieldPct() : HUNDRED;
        List<ExplodedMaterial> materials = new ArrayList<>();
        for (int i = 0; i < bom.lines().size(); i++) {
            ExplodableLine line = bom.lines().get(i);
            BigDecimal scrap = line.scrapPct() != null ? line.scrapPct() : BigDecimal.ZERO;
            BigDecimal scrapFactor = BigDecimal.ONE.add(Money.div(scrap, HUNDRED), Money.DECIMAL_JS);
            materials.add(new ExplodedMaterial(line.componentProductId(), line.componentVariantId(),
                    mul(mul(line.quantity(), scale), scrapFactor), line.uomId(),
                    line.sequence() != null ? line.sequence() : i));
        }
        return new ExplodedBom(Money.div(mul(plannedQty, yieldPct), HUNDRED), materials);
    }

    /** The explosion view of a BOM row rendered with its {@code lines}. */
    @SuppressWarnings("unchecked")
    static ExplodableBom explodable(Map<String, Object> bom) {
        List<ExplodableLine> lines = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) bom.get("lines")) {
            lines.add(new ExplodableLine(str(l, "componentProductId"), str(l, "componentVariantId"),
                    dec(l, "quantity"), str(l, "uomId"), l.get("scrapPct") == null ? null : dec(l, "scrapPct"),
                    (Integer) l.get("sequence")));
        }
        return new ExplodableBom(dec(bom, "outputQuantity"),
                bom.get("expectedYieldPct") == null ? null : dec(bom, "expectedYieldPct"), lines);
    }

    // ================================================================== CRUD

    /** New draft version for (product, variant); versions are per output line. */
    @Transactional
    public Map<String, Object> create(CreateBom dto) {
        String variantKey = dto.outputVariantId() != null ? dto.outputVariantId() : "";
        // ADR-S006 A7: references must exist; versions are allocated under a per-output lock.
        requireReferences(dto.outputProductId(), dto.lines(), dto.defaultLocationId(), dto.defaultOutputLocationId(),
                dto.routingId());
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text")
                .param("bom:" + MfgDb.org() + ":" + dto.outputProductId() + ":" + variantKey).query().listOfRows();
        Integer latest = db.jdbc().sql("SELECT version FROM \"Bom\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + " AND \"outputProductId\" = ? AND \"outputVariantKey\" = ? ORDER BY version DESC LIMIT 1")
                .params(MfgDb.org(), dto.outputProductId(), variantKey).query(Integer.class).optional().orElse(null);
        int version = (latest != null ? latest : 0) + 1;
        String code = seq.next("bom", "BOM-", 5);

        assertNoCycle(dto.outputProductId(), dto.lines().stream().map(BomLineInput::componentProductId).toList(), 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", dto.name());
        data.put("outputProductId", dto.outputProductId());
        data.put("outputVariantId", dto.outputVariantId());
        data.put("outputVariantKey", variantKey);
        data.put("outputQuantity", dto.outputQuantity());
        data.put("outputUomId", dto.outputUomId());
        data.put("version", version);
        data.put("status", "draft");
        data.put("isDefault", dto.isDefault() != null ? dto.isDefault() : false);
        data.put("expectedYieldPct", dto.expectedYieldPct() != null ? dto.expectedYieldPct() : HUNDRED);
        data.put("overheadCost", dto.overheadCost() != null ? dto.overheadCost() : BigDecimal.ZERO);
        data.put("defaultLocationId", dto.defaultLocationId());
        data.put("defaultOutputLocationId", dto.defaultOutputLocationId());
        data.put("shelfLifeDays", dto.shelfLifeDays());
        data.put("estimatedDurationMins", dto.estimatedDurationMins());
        data.put("qcRequired", dto.qcRequired() != null ? dto.qcRequired() : false);
        data.put("effectiveFrom", date(dto.effectiveFrom()));
        data.put("effectiveTo", date(dto.effectiveTo()));
        data.put("notes", dto.notes());
        data.put("routingId", dto.routingId());
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> bom = db.insert(Bom.class, data);
        String id = str(bom, "id");
        for (int i = 0; i < dto.lines().size(); i++) {
            Map<String, Object> line = lineData(dto.lines().get(i), i);
            line.put("bomId", id);
            db.insert(BomLine.class, line);
        }
        return withLines(bom);
    }

    /** Edit a DRAFT BOM; an active/archived version is immutable. {@code lines} replaces the whole line set. */
    @Transactional
    public Map<String, Object> update(String id, UpdateBom dto) {
        Map<String, Object> bom = getOrThrow(id);
        if (!"draft".equals(bom.get("status"))) {
            throw ApiException.badRequest("BOM " + bom.get("code") + " is " + bom.get("status")
                    + " and cannot be edited. Create a new version instead.");
        }
        requireReferences(null, dto.lines(), dto.defaultLocationId(), dto.defaultOutputLocationId(), dto.routingId());
        if (dto.lines() != null) {
            assertNoCycle(str(bom, "outputProductId"),
                    dto.lines().stream().map(BomLineInput::componentProductId).toList(), 0);
            db.jdbc().sql("DELETE FROM \"BomLine\" WHERE \"bomId\" = ? AND \"organizationId\" = ?")
                    .params(id, MfgDb.org()).update();
            for (int i = 0; i < dto.lines().size(); i++) {
                Map<String, Object> line = lineData(dto.lines().get(i), i);
                line.put("bomId", id);
                db.insert(BomLine.class, line);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "name", dto.name());
        putIf(data, "outputQuantity", dto.outputQuantity());
        putIf(data, "outputUomId", dto.outputUomId());
        putIf(data, "expectedYieldPct", dto.expectedYieldPct());
        putIf(data, "defaultLocationId", dto.defaultLocationId());
        putIf(data, "defaultOutputLocationId", dto.defaultOutputLocationId());
        putIf(data, "shelfLifeDays", dto.shelfLifeDays());
        putIf(data, "estimatedDurationMins", dto.estimatedDurationMins());
        putIf(data, "qcRequired", dto.qcRequired());
        putIf(data, "isDefault", dto.isDefault());
        putIf(data, "effectiveFrom", date(dto.effectiveFrom()));
        putIf(data, "effectiveTo", date(dto.effectiveTo()));
        putIf(data, "notes", dto.notes());
        putIf(data, "overheadCost", dto.overheadCost());
        putIf(data, "routingId", dto.routingId());
        data.put("updatedBy", TenantContext.userId());
        return withLines(db.update(Bom.class, id, data));
    }

    /**
     * Activate a draft BOM behind the approval engine. Archives the prior active version of the same output and,
     * when this one is the default, clears {@code isDefault} on its siblings.
     */
    @Transactional
    public Map<String, Object> activate(String id) {
        Map<String, Object> bom = getOrThrow(id);
        if ("active".equals(bom.get("status"))) {
            return bom;
        }
        if ("archived".equals(bom.get("status"))) {
            throw ApiException.badRequest("Cannot re-activate an archived BOM version.");
        }
        plmGate.requireActivatable(bom);
        Map<String, Object> snapshot = Obj.of("amount", 0, "code", bom.get("code"), "product",
                bom.get("outputProductId"), "version", bom.get("version"));
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval("bom_version", id, snapshot));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before activating BOM " + bom.get("code")
                    + ". Pending request " + gate.get("requestId") + ".");
        }
        db.updateWhere(Bom.class, Obj.of("status", "archived"), "\"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + " AND \"outputProductId\" = ? AND \"outputVariantKey\" = ? AND status = 'active' AND id <> ?",
                MfgDb.org(), bom.get("outputProductId"), bom.get("outputVariantKey"), id);
        if (Boolean.TRUE.equals(bom.get("isDefault"))) {
            db.updateWhere(Bom.class, Obj.of("isDefault", false), "\"organizationId\" = ? AND \"deletedAt\" IS NULL"
                            + " AND \"outputProductId\" = ? AND \"outputVariantKey\" = ? AND id <> ?",
                    MfgDb.org(), bom.get("outputProductId"), bom.get("outputVariantKey"), id);
        }
        return withLines(db.update(Bom.class, id, Obj.of("status", "active", "updatedBy", TenantContext.userId())));
    }

    /** Soft delete. */
    @Transactional
    public void remove(String id) {
        getOrThrow(id);
        db.update(Bom.class, id, Obj.of("deletedAt", java.time.Instant.now(), "isActive", false, "updatedBy",
                TenantContext.userId()));
    }

    /** Ordered by output product, newest version first; each with its lines. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String outputProductId, String status, Integer limit, Integer offset) {
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (outputProductId != null) {
            extra.append(" AND \"outputProductId\" = ?");
            params.add(outputProductId);
        }
        if (status != null && !status.isEmpty()) {
            extra.append(" AND status = ?::\"BomStatus\"");
            params.add(status);
        }
        extra.append(" ORDER BY \"outputProductId\" ASC, version DESC").append(MfgDb.page(limit, offset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : db.list(Bom.class, extra.toString(), params.toArray())) {
            out.add(withLines(b));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return getOrThrow(id);
    }

    // ================================================================== resolution

    /**
     * The BOM a production order uses: the named one (must be active), else the active default for the product,
     * else the newest active version; effective-dated BOMs outside their window are excluded.
     */
    Map<String, Object> resolveForOrder(String outputProductId, String variantId, String bomId) {
        if (bomId != null && !bomId.isEmpty()) {
            Map<String, Object> bom = getOrThrow(bomId);
            if (!"active".equals(bom.get("status"))) {
                throw ApiException.badRequest("BOM " + bom.get("code") + " is " + bom.get("status") + ", not active.");
            }
            return bom;
        }
        Map<String, Object> bom = findActive(outputProductId, variantId);
        if (bom == null) {
            throw ApiException.badRequest("No active BOM for this product. Create and activate one first.");
        }
        return bom;
    }

    /** Non-throwing active-BOM lookup. */
    Map<String, Object> findActive(String outputProductId, String variantId) {
        List<Map<String, Object>> rows = db.list(Bom.class, " AND \"outputProductId\" = ? AND \"outputVariantKey\" = ?"
                        + " AND status = 'active' AND (\"effectiveFrom\" IS NULL OR \"effectiveFrom\" <= ?)"
                        + " AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" >= ?)"
                        + " ORDER BY \"isDefault\" DESC, version DESC LIMIT 1",
                outputProductId, variantId != null ? variantId : "", MfgDb.param(java.time.Instant.now()),
                MfgDb.param(java.time.Instant.now()));
        return rows.isEmpty() ? null : withLines(rows.getFirst());
    }

    /**
     * Explode a product to its RAW material requirements, recursing through semi-finished components that have
     * their own active BOMs. Path-based cycle guard + depth cap. Insertion-ordered like the JS {@code Map}.
     */
    LinkedHashMap<String, BigDecimal> explodeRequirements(String outputProductId, String variantId, BigDecimal qty) {
        LinkedHashMap<String, BigDecimal> acc = new LinkedHashMap<>();
        explodeRequirements(outputProductId, variantId, qty, acc, 0, new HashSet<>());
        return acc;
    }

    private void explodeRequirements(String outputProductId, String variantId, BigDecimal qty,
            LinkedHashMap<String, BigDecimal> acc, int depth, Set<String> path) {
        if (depth > 10 || path.contains(outputProductId)) {
            acc.merge(outputProductId, qty, BigDecimal::add);
            return;
        }
        Map<String, Object> bom = findActive(outputProductId, variantId != null ? variantId : "");
        if (bom == null) {
            acc.merge(outputProductId, qty, BigDecimal::add);
            return;
        }
        path.add(outputProductId);
        for (ExplodedMaterial m : explode(explodable(bom), qty).materials()) {
            explodeRequirements(m.componentProductId(), m.componentVariantId(), m.qtyPlanned(), acc, depth + 1, path);
        }
        path.remove(outputProductId);
    }

    // ================================================================== internals

    private static Map<String, Object> lineData(BomLineInput l, int i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", MfgDb.org());
        m.put("componentProductId", l.componentProductId());
        m.put("componentVariantId", l.componentVariantId());
        m.put("quantity", l.quantity());
        m.put("uomId", l.uomId());
        m.put("scrapPct", l.scrapPct() != null ? l.scrapPct() : BigDecimal.ZERO);
        m.put("sequence", l.sequence() != null ? l.sequence() : i);
        m.put("isOptional", l.isOptional() != null ? l.isOptional() : false);
        m.put("notes", l.notes());
        return m;
    }

    /** {@code include: {lines: {orderBy: {sequence: 'asc'}}}}. */
    Map<String, Object> withLines(Map<String, Object> bom) {
        bom.put("lines", db.children(BomLine.class, "bomId", bom.get("id"), "sequence ASC"));
        return bom;
    }

    Map<String, Object> getOrThrow(String id) {
        Map<String, Object> bom = db.byId(Bom.class, id);
        if (bom == null) {
            throw ApiException.notFound("BOM not found");
        }
        return withLines(bom);
    }

    /** Reject a BOM whose components (transitively, via their active BOMs) reach back to the output product. */
    private void assertNoCycle(String outputProductId, List<String> componentProductIds, int depth) {
        if (depth > 10) {
            return;
        }
        if (componentProductIds.contains(outputProductId)) {
            throw ApiException.badRequest("Circular BOM: a component is (transitively) the output product.");
        }
        for (String componentId : componentProductIds) {
            List<Map<String, Object>> child = db.list(Bom.class, " AND \"outputProductId\" = ? AND status = 'active'"
                    + " LIMIT 1", componentId);
            if (child.isEmpty()) {
                continue;
            }
            List<String> next = db.children(BomLine.class, "bomId", child.getFirst().get("id"), null).stream()
                    .map(l -> str(l, "componentProductId")).toList();
            assertNoCycle(outputProductId, next, depth + 1);
        }
    }

    /** 404s for unknown products / locations / routing referenced by a BOM body (ADR-S006 A7). */
    private void requireReferences(String outputProductId, List<BomLineInput> lines, String locationId,
            String outputLocationId, String routingId) {
        List<String> products = new ArrayList<>();
        if (outputProductId != null) {
            products.add(outputProductId);
        }
        if (lines != null) {
            lines.forEach(l -> products.add(l.componentProductId()));
        }
        for (String p : new java.util.LinkedHashSet<>(products)) {
            if (db.jdbc().sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(p, MfgDb.org()).query(Long.class).single() == 0) {
                throw ApiException.notFound("Product " + p + " not found");
            }
        }
        for (String loc : java.util.Arrays.asList(locationId, outputLocationId)) {
            if (loc != null && db.jdbc().sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(loc, MfgDb.org())
                    .query(Long.class).single() == 0) {
                throw ApiException.notFound("Location not found");
            }
        }
        if (routingId != null && db.byId(com.inventoryerp.manufacturing.domain.Routing.class, routingId) == null) {
            throw ApiException.notFound("Routing not found");
        }
    }

    private static java.time.Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
