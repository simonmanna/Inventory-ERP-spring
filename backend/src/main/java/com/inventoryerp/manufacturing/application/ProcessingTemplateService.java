package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.BomLineInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import com.inventoryerp.manufacturing.application.ProcessingCommands.RouteBody;
import com.inventoryerp.manufacturing.application.ProcessingCommands.RouteStepInput;
import com.inventoryerp.manufacturing.application.ProcessingCommands.TemplateBody;
import com.inventoryerp.manufacturing.application.ProcessingCommands.TemplateInputLine;
import com.inventoryerp.manufacturing.application.ProcessingCommands.TemplateOutputLine;
import com.inventoryerp.manufacturing.application.StageCommands.AllocationRuleInput;
import com.inventoryerp.manufacturing.application.StageCommands.BomStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.CoProductInput;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.ProcessingRoute;
import com.inventoryerp.manufacturing.domain.ProcessingRouteStep;
import com.inventoryerp.manufacturing.domain.ProcessingTemplate;
import com.inventoryerp.manufacturing.domain.ProcessingTemplateInput;
import com.inventoryerp.manufacturing.domain.ProcessingTemplateOutput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processing templates and routes (ADR-S060). A template is a versioned recipe of one step: drafts are edited;
 * activation builds a system-managed BOM and a one-operation routing and archives the previous version; an active
 * version is never edited, so a posted job always keeps the recipe it ran on. A route is guidance only.
 */
@Service
public class ProcessingTemplateService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MfgDb db;
    private final AuditService audit;
    private final BomService boms;
    private final RoutingService routings;
    private final StageSetupService stageSetup;

    public ProcessingTemplateService(MfgDb db, AuditService audit, BomService boms, RoutingService routings,
            StageSetupService stageSetup) {
        this.db = db;
        this.audit = audit;
        this.boms = boms;
        this.routings = routings;
        this.stageSetup = stageSetup;
    }

    // ================================================================== read

    /** Templates, newest version first; {@code inputProductId} narrows to those that can process a product. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String inputProductId) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder w = new StringBuilder();
        if (status != null && !status.isBlank()) {
            w.append(" AND t.status = ?");
            params.add(status);
        }
        if (inputProductId != null && !inputProductId.isBlank()) {
            w.append(" AND EXISTS (SELECT 1 FROM \"ProcessingTemplateInput\" i WHERE i.\"templateId\" = t.id AND"
                    + " i.\"productId\" = ? AND i.role = 'traceable')");
            params.add(inputProductId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : SqlRows.list(db.jdbc().sql("SELECT t.* FROM \"ProcessingTemplate\" t WHERE"
                + " t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL" + w + " ORDER BY t.code, t.version DESC")
                .params(params))) {
            out.add(withLines(t));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return withLines(require(id));
    }

    Map<String, Object> require(String id) {
        Map<String, Object> t = SqlRows.one(db.jdbc().sql("SELECT * FROM \"ProcessingTemplate\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, MfgDb.org()));
        if (t == null) {
            throw ApiException.notFound("Processing template not found");
        }
        return t;
    }

    Map<String, Object> withLines(Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>(t);
        out.put("inputs", lines("ProcessingTemplateInput", str(t, "id")));
        out.put("outputs", lines("ProcessingTemplateOutput", str(t, "id")));
        return out;
    }

    List<Map<String, Object>> lines(String table, String templateId) {
        return SqlRows.list(db.jdbc().sql("SELECT l.*, p.code AS \"productCode\", p.name AS \"productName\", p.\"uomId\","
                + " p.\"batchTracking\", u.name AS \"uomName\" FROM \"" + table + "\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE l.\"organizationId\" = ? AND l.\"templateId\" = ?"
                + " ORDER BY l.sequence").params(MfgDb.org(), templateId));
    }

    // ================================================================== write

    @Transactional
    public Map<String, Object> create(TemplateBody dto) {
        Integer existing = db.jdbc().sql("SELECT count(*)::int FROM \"ProcessingTemplate\" WHERE \"organizationId\" = ?"
                + " AND code = ? AND \"deletedAt\" IS NULL").params(MfgDb.org(), dto.code().trim())
                .query(Integer.class).single();
        if (existing > 0) {
            throw ApiException.badRequest("Template code " + dto.code().trim() + " already exists; create a new version of"
                    + " it instead");
        }
        return insert(dto, 1);
    }

    private Map<String, Object> insert(TemplateBody dto, int version) {
        validateDraft(dto);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", dto.code().trim());
        d.put("version", version);
        fields(d, dto);
        d.put("createdBy", TenantContext.userId());
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> t = db.insert(ProcessingTemplate.class, d);
        writeLines(str(t, "id"), dto);
        audit.recordInTx(AuditInput.of("ProcessingTemplate", str(t, "id"), "create", null, Obj.of("code", t.get("code"),
                "version", version)));
        return get(str(t, "id"));
    }

    private void fields(Map<String, Object> d, TemplateBody dto) {
        d.put("name", dto.name().trim());
        d.put("description", dto.description());
        d.put("workCenterId", dto.workCenterId());
        d.put("allocationMethod", dto.allocationMethod() == null ? "market_value" : dto.allocationMethod());
        d.put("yieldBasisUnit", dto.yieldBasisUnit() == null || dto.yieldBasisUnit().isBlank() ? "unit"
                : dto.yieldBasisUnit().trim());
        d.put("expectedYieldPct", dto.expectedYieldPct() == null ? HUNDRED : dto.expectedYieldPct());
        d.put("qcGate", Boolean.TRUE.equals(dto.qcGate()));
        d.put("qcCheckpointCode", dto.qcCheckpointCode());
        d.put("defaultInputLocationId", dto.defaultInputLocationId());
        d.put("defaultOutputLocationId", dto.defaultOutputLocationId());
        d.put("costRates", dto.costRates() == null ? null : new MfgDb.Json(dto.costRates()));
    }

    private void writeLines(String templateId, TemplateBody dto) {
        int seq = 0;
        for (TemplateInputLine l : dto.inputs()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("templateId", templateId);
            d.put("sequence", seq++);
            d.put("productId", l.productId());
            d.put("role", l.role() == null ? "traceable" : l.role());
            d.put("genealogyBasis", l.genealogyBasis() == null ? "mass" : l.genealogyBasis());
            d.put("fixedPct", l.fixedPct());
            d.put("basisFactor", l.basisFactor());
            db.insert(ProcessingTemplateInput.class, d);
        }
        seq = 0;
        for (TemplateOutputLine l : dto.outputs()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("templateId", templateId);
            d.put("sequence", seq++);
            d.put("productId", l.productId());
            d.put("role", l.role());
            d.put("expectedPct", l.expectedPct());
            d.put("valuation", l.valuation() == null ? "allocated" : l.valuation());
            d.put("nrvUnitValue", l.nrvUnitValue());
            d.put("marketValue", l.marketValue());
            d.put("basisFactor", l.basisFactor());
            d.put("requiredMeasures", l.requiredMeasures() == null ? null : new MfgDb.Json(l.requiredMeasures()));
            d.put("stocked", !Boolean.FALSE.equals(l.stocked()));
            d.put("locationId", l.locationId());
            db.insert(ProcessingTemplateOutput.class, d);
        }
    }

    /** Edit a draft. An active or archived version is immutable: copy it into a new version instead. */
    @Transactional
    public Map<String, Object> update(String id, TemplateBody dto) {
        Map<String, Object> t = require(id);
        draftOnly(t);
        validateDraft(dto);
        Map<String, Object> d = new LinkedHashMap<>();
        fields(d, dto);
        d.put("updatedBy", TenantContext.userId());
        db.update(ProcessingTemplate.class, id, d);
        db.jdbc().sql("DELETE FROM \"ProcessingTemplateInput\" WHERE \"templateId\" = ? AND \"organizationId\" = ?")
                .params(id, MfgDb.org()).update();
        db.jdbc().sql("DELETE FROM \"ProcessingTemplateOutput\" WHERE \"templateId\" = ? AND \"organizationId\" = ?")
                .params(id, MfgDb.org()).update();
        writeLines(id, dto);
        return get(id);
    }

    /** Copy any version into a new draft of the same code. */
    @Transactional
    public Map<String, Object> newVersion(String id) {
        Map<String, Object> t = require(id);
        Integer max = db.jdbc().sql("SELECT max(version) FROM \"ProcessingTemplate\" WHERE \"organizationId\" = ? AND"
                + " code = ?").params(MfgDb.org(), t.get("code")).query(Integer.class).single();
        Map<String, Object> d = new LinkedHashMap<>(t);
        List.of("id", "createdAt", "updatedAt", "bomId", "routingId", "activatedAt", "activatedBy", "deletedAt").forEach(d::remove);
        d.put("version", max + 1);
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        d.put("updatedBy", TenantContext.userId());
        if (d.get("costRates") != null) {
            d.put("costRates", new MfgDb.Json(d.get("costRates")));
        }
        Map<String, Object> copy = db.insert(ProcessingTemplate.class, d);
        for (Map<String, Object> l : db.children(ProcessingTemplateInput.class, "templateId", id, "sequence ASC")) {
            Map<String, Object> c = new LinkedHashMap<>(l);
            c.remove("id");
            c.remove("createdAt");
            c.put("templateId", copy.get("id"));
            db.insert(ProcessingTemplateInput.class, c);
        }
        for (Map<String, Object> l : db.children(ProcessingTemplateOutput.class, "templateId", id, "sequence ASC")) {
            Map<String, Object> c = new LinkedHashMap<>(l);
            c.remove("id");
            c.remove("createdAt");
            c.put("templateId", copy.get("id"));
            if (c.get("requiredMeasures") != null) {
                c.put("requiredMeasures", new MfgDb.Json(c.get("requiredMeasures")));
            }
            db.insert(ProcessingTemplateOutput.class, c);
        }
        return get(str(copy, "id"));
    }

    /** Activate a draft: strict validation, the system BOM and routing; the previous active version is archived. */
    @Transactional
    public Map<String, Object> activate(String id) {
        Map<String, Object> t = require(id);
        draftOnly(t);
        List<Map<String, Object>> inputs = db.children(ProcessingTemplateInput.class, "templateId", id, "sequence ASC");
        List<Map<String, Object>> outputs = db.children(ProcessingTemplateOutput.class, "templateId", id, "sequence ASC");
        validateActive(t, inputs, outputs);
        for (Map<String, Object> old : db.list(ProcessingTemplate.class, " AND code = ? AND status = 'active' AND"
                + " \"deletedAt\" IS NULL AND id <> ?", t.get("code"), id)) {
            db.update(ProcessingTemplate.class, str(old, "id"), Obj.of("status", "archived", "updatedBy",
                    TenantContext.userId()));
            if (old.get("bomId") != null) {
                db.update(Bom.class, str(old, "bomId"), Obj.of("status", "archived"));
            }
        }
        Map<String, Object> main = outputs.stream().filter(o -> "main".equals(o.get("role"))).findFirst().orElseThrow();
        String label = t.get("code") + " v" + t.get("version");
        Map<String, Object> routing = routings.create(new CreateRouting("Processing " + label + " (system)", null, null,
                List.of(new RoutingOperationInput(10, str(t, "name"), str(t, "workCenterId"), null, null, null, false,
                        null, null))));
        List<BomLineInput> bomLines = new ArrayList<>();
        int seq = 0;
        for (Map<String, Object> in : inputs) {
            bomLines.add(new BomLineInput(str(in, "productId"), null, "traceable".equals(in.get("role")) ? HUNDRED
                    : BigDecimal.ONE, null, BigDecimal.ZERO, seq++, false, str(in, "role")));
        }
        Map<String, Object> bom = boms.create(new CreateBom("Processing " + label + " (system)", str(main, "productId"),
                null, dec(main, "expectedPct"), null, HUNDRED, BigDecimal.ZERO, str(t, "defaultInputLocationId"),
                str(t, "defaultOutputLocationId"), null, null, false, false, null, null,
                "System-managed BOM of processing template " + label + ". Edit the template, not this BOM.", bomLines,
                str(routing, "id")));
        String bomId = str(bom, "id");
        boolean weight = "weight".equals(t.get("allocationMethod"));
        List<CoProductInput> co = new ArrayList<>();
        for (Map<String, Object> o : outputs) {
            if ("main".equals(o.get("role"))) {
                continue;
            }
            String role = "residual".equals(o.get("role")) ? "by_product" : str(o, "role");
            boolean stocked = !Boolean.FALSE.equals(o.get("stocked"));
            co.add(new CoProductInput(str(o, "productId"), role, null, dec(o, "expectedPct"), null, str(o, "valuation"),
                    decOrNull(o, "nrvUnitValue"), decOrNull(o, "marketValue"), weight ? BigDecimal.ONE : null,
                    null, stocked, stocked ? str(o, "locationId") : null, null));
        }
        stageSetup.saveBomSetup(bomId, new BomStageSetup(co, new AllocationRuleInput(str(t, "allocationMethod"),
                weight ? BigDecimal.ONE : null, decOrNull(main, "marketValue"), "Processing template " + label)));
        db.update(Bom.class, bomId, Obj.of("status", "active", "isDefault", false, "updatedBy", TenantContext.userId()));
        db.update(ProcessingTemplate.class, id, Obj.of("status", "active", "bomId", bomId, "routingId", routing.get("id"),
                "activatedAt", Instant.now(), "activatedBy", TenantContext.userId(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("ProcessingTemplate", id, "approve", null, Obj.of("code", t.get("code"),
                "version", t.get("version"), "bomId", bomId)));
        return get(id);
    }

    /** Archive an active version: no new jobs; posted jobs keep it. */
    @Transactional
    public Map<String, Object> archive(String id) {
        Map<String, Object> t = require(id);
        if (!"active".equals(t.get("status"))) {
            throw ApiException.badRequest("Only an active template can be archived");
        }
        db.update(ProcessingTemplate.class, id, Obj.of("status", "archived", "updatedBy", TenantContext.userId()));
        if (t.get("bomId") != null) {
            db.update(Bom.class, str(t, "bomId"), Obj.of("status", "archived"));
        }
        return get(id);
    }

    private static void draftOnly(Map<String, Object> t) {
        if (!"draft".equals(t.get("status"))) {
            throw ApiException.badRequest("Template " + t.get("code") + " v" + t.get("version") + " is " + t.get("status")
                    + " and cannot change; create a new version of it");
        }
    }

    // ================================================================== validation

    private void validateDraft(TemplateBody dto) {
        Set<String> seen = new HashSet<>();
        for (TemplateOutputLine o : dto.outputs()) {
            if (!seen.add(o.productId())) {
                throw ApiException.badRequest("A product can be only one output of a template");
            }
        }
    }

    private void validateActive(Map<String, Object> t, List<Map<String, Object>> inputs,
            List<Map<String, Object>> outputs) {
        if (outputs.stream().filter(o -> "main".equals(o.get("role"))).count() != 1) {
            throw ApiException.badRequest("A template needs exactly one main output");
        }
        Map<String, Object> main = outputs.stream().filter(o -> "main".equals(o.get("role"))).findFirst().orElseThrow();
        if (dec(main, "expectedPct").signum() <= 0) {
            throw ApiException.badRequest("The main output needs an expected share above 0");
        }
        List<Map<String, Object>> traceable = inputs.stream().filter(i -> "traceable".equals(i.get("role"))).toList();
        if (traceable.isEmpty()) {
            throw ApiException.badRequest("A template needs at least one traceable input");
        }
        if (traceable.stream().map(i -> str(i, "genealogyBasis")).distinct().count() > 1) {
            throw ApiException.badRequest("All traceable inputs of a template must use the same genealogy basis");
        }
        boolean mixed = false;
        for (Map<String, Object> l : inputs) {
            assertCostable(str(l, "productId"));
            mixed |= factorSet(l);
        }
        for (Map<String, Object> o : outputs) {
            Map<String, Object> p = product(str(o, "productId"));
            assertCostable(str(o, "productId"));
            mixed |= factorSet(o);
            if (Boolean.TRUE.equals(p.get("batchTracking")) && !"block".equals(String.valueOf(p.get("stockPolicy")))) {
                throw ApiException.badRequest("Lot-tracked output " + p.get("name") + " must block the issue when stock runs out."
                        + " Change this on the product, so every sale can name its lot.");
            }
        }
        if (mixed && "quantity".equals(t.get("allocationMethod"))) {
            throw ApiException.badRequest("A mixed-unit template must allocate by weight or market value; adding different"
                    + " units together is meaningless");
        }
    }

    private static boolean factorSet(Map<String, Object> line) {
        return line.get("basisFactor") != null && dec(line, "basisFactor").compareTo(BigDecimal.ONE) != 0;
    }

    private void assertCostable(String productId) {
        Map<String, Object> p = product(productId);
        if ("STANDARD".equals(String.valueOf(p.get("costingMethod")))) {
            throw ApiException.badRequest(p.get("name") + " is standard-costed; a processing chain needs actual cost");
        }
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            throw ApiException.badRequest(p.get("name") + " is serial-tracked; processing works on lots");
        }
    }

    private Map<String, Object> product(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, name, \"batchTracking\", \"serialTracking\","
                + " \"stockPolicy\"::text AS \"stockPolicy\", \"costingMethod\"::text AS \"costingMethod\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(productId, MfgDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    // ================================================================== routes

    @Transactional(readOnly = true)
    public List<Map<String, Object>> routes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list(ProcessingRoute.class, " AND \"deletedAt\" IS NULL ORDER BY code")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("steps", steps(str(r, "id")));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> steps(String routeId) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT s.*, t.id AS "templateId", t.name AS "templateName", t.version AS "templateVersion"
                FROM "ProcessingRouteStep" s
                LEFT JOIN "ProcessingTemplate" t ON t."organizationId" = s."organizationId" AND t.code = s."templateCode"
                     AND t.status = 'active' AND t."deletedAt" IS NULL
                WHERE s."organizationId" = ? AND s."routeId" = ? ORDER BY s.sequence""").params(MfgDb.org(), routeId));
    }

    @Transactional
    public Map<String, Object> saveRoute(String id, RouteBody dto) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", dto.code().trim());
        d.put("name", dto.name().trim());
        d.put("description", dto.description());
        d.put("isActive", !Boolean.FALSE.equals(dto.isActive()));
        Map<String, Object> r;
        if (id == null) {
            Integer existing = db.jdbc().sql("SELECT count(*)::int FROM \"ProcessingRoute\" WHERE \"organizationId\" = ?"
                    + " AND code = ? AND \"deletedAt\" IS NULL").params(MfgDb.org(), dto.code().trim())
                    .query(Integer.class).single();
            if (existing > 0) {
                throw ApiException.badRequest("Route code " + dto.code().trim() + " already exists");
            }
            d.put("createdBy", TenantContext.userId());
            r = db.insert(ProcessingRoute.class, d);
        } else {
            r = db.update(ProcessingRoute.class, id, d);
            db.jdbc().sql("DELETE FROM \"ProcessingRouteStep\" WHERE \"routeId\" = ? AND \"organizationId\" = ?")
                    .params(id, MfgDb.org()).update();
        }
        int seq = 10;
        for (RouteStepInput s : dto.steps()) {
            db.insert(ProcessingRouteStep.class, Obj.of("routeId", r.get("id"), "sequence", seq, "templateCode",
                    s.templateCode(), "notes", s.notes()));
            seq += 10;
        }
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("steps", steps(str(r, "id")));
        return out;
    }

    @Transactional
    public void deleteRoute(String id) {
        db.update(ProcessingRoute.class, id, Obj.of("deletedAt", Instant.now()));
    }

    private static BigDecimal decOrNull(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : dec(row, key);
    }
}
