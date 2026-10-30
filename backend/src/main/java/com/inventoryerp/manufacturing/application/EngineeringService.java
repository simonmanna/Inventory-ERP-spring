package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.ReviseBom;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.BomLine;
import com.inventoryerp.manufacturing.domain.BomLineSubstitute;
import com.inventoryerp.manufacturing.domain.EngineeringChange;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engineering changes (ADR-S006 package D): revise a BOM into its next draft version under an ECO, review it
 * (approve / reject), apply it (activation through the BOM approval gate) and compare any two versions.
 */
@Service
public class EngineeringService {

    private static final List<String> HEADER_FIELDS = List.of("name", "outputQuantity", "outputUomId",
            "expectedYieldPct", "overheadCost", "routingId", "defaultLocationId", "defaultOutputLocationId",
            "shelfLifeDays", "estimatedDurationMins", "qcRequired");

    private final MfgDb db;
    private final SequenceService seq;
    private final BomService boms;

    public EngineeringService(MfgDb db, SequenceService seq, BomService boms) {
        this.db = db;
        this.seq = seq;
        this.boms = boms;
    }

    /** Clone the BOM (lines + substitutes) into the next draft version and open an ECO for it. */
    @Transactional
    public Map<String, Object> revise(String bomId, ReviseBom dto) {
        Map<String, Object> src = boms.getOrThrow(bomId);
        String product = str(src, "outputProductId");
        String variantKey = str(src, "outputVariantKey");
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text")
                .param("bom:" + MfgDb.org() + ":" + product + ":" + variantKey).query().listOfRows();
        int version = db.jdbc().sql("SELECT coalesce(max(version), 0) FROM \"Bom\" WHERE \"organizationId\" = ?"
                + " AND \"outputProductId\" = ? AND \"outputVariantKey\" = ?").params(MfgDb.org(), product, variantKey)
                .query(Integer.class).single() + 1;
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("name", "outputProductId", "outputVariantId", "outputVariantKey", "outputQuantity",
                "outputUomId", "isDefault", "effectiveFrom", "effectiveTo", "expectedYieldPct", "overheadCost",
                "defaultLocationId", "defaultOutputLocationId", "shelfLifeDays", "estimatedDurationMins", "qcRequired",
                "routingId", "notes")) {
            data.put(f, src.get(f));
        }
        data.put("code", seq.next("bom", "BOM-", 5));
        data.put("version", version);
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> copy = db.insert(Bom.class, data);
        for (Object o : (List<?>) src.get("lines")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> l = (Map<String, Object>) o;
            Map<String, Object> line = new LinkedHashMap<>();
            for (String f : List.of("componentProductId", "componentVariantId", "quantity", "uomId", "scrapPct",
                    "sequence", "isOptional", "notes")) {
                line.put(f, l.get(f));
            }
            line.put("bomId", copy.get("id"));
            Map<String, Object> newLine = db.insert(BomLine.class, line);
            for (Map<String, Object> s : db.children(BomLineSubstitute.class, "bomLineId", l.get("id"), null)) {
                db.insert(BomLineSubstitute.class, Obj.of("bomLineId", newLine.get("id"), "productId",
                        s.get("productId"), "ratio", s.get("ratio"), "priority", s.get("priority"), "notes",
                        s.get("notes")));
            }
        }
        // ADR-S011: the revision keeps the BOM's co-products / by-products / waste and its allocation override.
        db.jdbc().sql("INSERT INTO \"BomCoProduct\" (id, \"organizationId\", \"bomId\", \"routingOperationId\","
                + " \"productId\", role, quantity, \"uomId\", valuation, \"nrvUnitValue\", \"marketValue\","
                + " \"weightPerUnit\", \"fixedPct\", stocked, \"locationId\", sequence, notes) SELECT"
                + " gen_random_uuid()::text, \"organizationId\", ?, \"routingOperationId\", \"productId\", role,"
                + " quantity, \"uomId\", valuation, \"nrvUnitValue\", \"marketValue\", \"weightPerUnit\","
                + " \"fixedPct\", stocked, \"locationId\", sequence, notes FROM \"BomCoProduct\""
                + " WHERE \"organizationId\" = ? AND \"bomId\" = ?").params(copy.get("id"), MfgDb.org(), bomId)
                .update();
        db.jdbc().sql("INSERT INTO \"MfgCostAllocationRule\" (id, \"organizationId\", \"bomId\", method,"
                + " \"mainWeightPerUnit\", \"mainMarketValue\", notes) SELECT gen_random_uuid()::text,"
                + " \"organizationId\", ?, method, \"mainWeightPerUnit\", \"mainMarketValue\", notes FROM"
                + " \"MfgCostAllocationRule\" WHERE \"organizationId\" = ? AND \"bomId\" = ?")
                .params(copy.get("id"), MfgDb.org(), bomId).update();
        Map<String, Object> eco = db.insert(EngineeringChange.class, Obj.of("number", seq.next("engineering_change",
                "ECO-", 5), "title", dto != null && dto.title() != null ? dto.title() : "Revision of " + src.get("code"),
                "reason", dto == null ? null : dto.reason(), "bomId", bomId, "newBomId", copy.get("id"), "status",
                "draft", "createdBy", TenantContext.userId()));
        return Obj.of("change", eco, "bom", boms.withLines(copy));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        return status != null ? db.list(EngineeringChange.class, " AND status = ? ORDER BY \"createdAt\" DESC", status)
                : db.list(EngineeringChange.class, " ORDER BY \"createdAt\" DESC");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> eco = require(id);
        eco.put("comparison", compare(str(eco, "bomId"), str(eco, "newBomId")));
        return eco;
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> eco = require(id);
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only a draft change can be approved (status " + eco.get("status") + ").");
        }
        return db.update(EngineeringChange.class, id, Obj.of("status", "approved", "approvedById",
                TenantContext.userId(), "approvedAt", Instant.now()));
    }

    @Transactional
    public Map<String, Object> reject(String id) {
        Map<String, Object> eco = require(id);
        if (!"draft".equals(eco.get("status")) && !"approved".equals(eco.get("status"))) {
            throw ApiException.badRequest("Cannot reject a " + eco.get("status") + " change.");
        }
        return db.update(EngineeringChange.class, id, Obj.of("status", "rejected"));
    }

    /** Apply an approved change: activate the new version (archiving the old one through the BOM rules). */
    @Transactional
    public Map<String, Object> apply(String id) {
        Map<String, Object> eco = require(id);
        if (!"approved".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only an approved change can be applied (status " + eco.get("status") + ").");
        }
        boms.activate(str(eco, "newBomId"));
        return db.update(EngineeringChange.class, id, Obj.of("status", "applied", "appliedAt", Instant.now()));
    }

    /** Header and line differences between two BOM versions (lines matched by component). */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(String fromId, String toId) {
        Map<String, Object> a = boms.getOrThrow(fromId);
        Map<String, Object> b = boms.getOrThrow(toId);
        List<Map<String, Object>> header = new ArrayList<>();
        for (String f : HEADER_FIELDS) {
            Object va = a.get(f);
            Object vb = b.get(f);
            if (!same(va, vb)) {
                header.add(Obj.of("field", f, "from", show(va), "to", show(vb)));
            }
        }
        Map<String, Map<String, Object>> la = byComponent((List<Map<String, Object>>) a.get("lines"));
        Map<String, Map<String, Object>> lb = byComponent((List<Map<String, Object>>) b.get("lines"));
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        lb.forEach((k, l) -> {
            if (!la.containsKey(k)) {
                added.add(l);
            }
        });
        la.forEach((k, l) -> {
            Map<String, Object> other = lb.get(k);
            if (other == null) {
                removed.add(l);
                return;
            }
            Map<String, Object> diff = new LinkedHashMap<>();
            for (String f : List.of("quantity", "uomId", "scrapPct", "isOptional")) {
                if (!same(l.get(f), other.get(f))) {
                    diff.put(f, Obj.of("from", show(l.get(f)), "to", show(other.get(f))));
                }
            }
            if (!diff.isEmpty()) {
                changed.add(Obj.of("componentProductId", k, "changes", diff));
            }
        });
        return Obj.of("from", Obj.of("id", fromId, "code", a.get("code"), "version", a.get("version")),
                "to", Obj.of("id", toId, "code", b.get("code"), "version", b.get("version")),
                "header", header, "added", added, "removed", removed, "changed", changed);
    }

    private static Map<String, Map<String, Object>> byComponent(List<Map<String, Object>> lines) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        lines.forEach(l -> m.put(str(l, "componentProductId") + (l.get("componentVariantId") != null
                ? ":" + l.get("componentVariantId") : ""), l));
        return m;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Object show(Object v) {
        return v instanceof BigDecimal d ? JsNumbers.dec(d) : v;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> eco = db.byId(EngineeringChange.class, id);
        if (eco == null) {
            throw ApiException.notFound("Engineering change not found");
        }
        return eco;
    }
}
