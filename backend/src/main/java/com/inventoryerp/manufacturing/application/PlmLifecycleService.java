package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlmCommands.RegisterItem;
import com.inventoryerp.manufacturing.application.PlmCommands.Transition;
import com.inventoryerp.manufacturing.application.PlmCommands.UpdateItem;
import com.inventoryerp.manufacturing.domain.PlmItem;
import com.inventoryerp.manufacturing.domain.PlmLifecycleEvent;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
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
 * Product lifecycle (ADR-S063): register a product under PLM, move it through
 * concept → design → prototype → released → phase-out → obsolete, keep its revision and history, and show the
 * product cockpit (BOM versions, routings, changes, requests, documents, where-used).
 */
@Service
public class PlmLifecycleService {

    /** Allowed transitions (any other move is refused). */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "concept", Set.of("design", "obsolete"),
            "design", Set.of("concept", "prototype", "released", "obsolete"),
            "prototype", Set.of("design", "released", "obsolete"),
            "released", Set.of("phase_out", "obsolete"),
            "phase_out", Set.of("released", "obsolete"),
            "obsolete", Set.of("design"));

    private final MfgDb db;
    private final PlmSupport plm;
    private final CostingService costing;

    public PlmLifecycleService(MfgDb db, PlmSupport plm, CostingService costing) {
        this.db = db;
        this.plm = plm;
        this.costing = costing;
    }

    // ---- items -----------------------------------------------------------------------------------------------------

    /** Items with product, owner, active BOM and open change / document counts. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String state, String productId, String q) {
        StringBuilder sql = new StringBuilder("SELECT i.*, p.code AS \"productCode\", p.name AS \"productName\","
                + " p.\"manufacturingRole\" AS \"manufacturingRole\","
                + " (SELECT b.code || ' v' || b.version FROM \"Bom\" b WHERE b.\"organizationId\" = i.\"organizationId\""
                + "   AND b.\"outputProductId\" = i.\"productId\" AND b.status = 'active' AND b.\"deletedAt\" IS NULL"
                + "   ORDER BY b.\"isDefault\" DESC, b.version DESC LIMIT 1) AS \"activeBom\","
                + " (SELECT count(*) FROM \"EngineeringChange\" e WHERE e.\"organizationId\" = i.\"organizationId\""
                + "   AND e.\"productId\" = i.\"productId\" AND e.status IN ('draft', 'approved')) AS \"openChanges\","
                + " (SELECT count(*) FROM \"PlmDocument\" d WHERE d.\"organizationId\" = i.\"organizationId\""
                + "   AND d.\"productId\" = i.\"productId\" AND d.status = 'released' AND d.\"deletedAt\" IS NULL)"
                + "   AS \"releasedDocuments\""
                + " FROM \"PlmItem\" i JOIN \"Product\" p ON p.id = i.\"productId\" WHERE i.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (state != null && !state.isBlank()) {
            sql.append(" AND i.\"lifecycleState\" = ?");
            params.add(state);
        }
        if (productId != null && !productId.isBlank()) {
            sql.append(" AND i.\"productId\" = ?");
            params.add(productId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (p.code ILIKE ? OR p.name ILIKE ?)");
            params.add("%" + q.trim() + "%");
            params.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY p.code");
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(sql.toString()).params(params));
        Set<String> owners = new HashSet<>();
        rows.forEach(r -> owners.add(str(r, "ownerId")));
        Map<String, String> names = plm.userNames(owners);
        rows.forEach(r -> r.put("ownerName", names.get(str(r, "ownerId"))));
        return rows;
    }

    @Transactional
    public Map<String, Object> register(RegisterItem dto) {
        Map<String, Object> product = plm.product(dto.productId());
        if (byProduct(dto.productId()) != null) {
            throw ApiException.conflict("Product " + product.get("code") + " is already under lifecycle management.");
        }
        plm.requireUser(dto.ownerId(), "ownerId");
        String state = dto.lifecycleState() == null ? "design" : dto.lifecycleState();
        String revision = PlmSupport.blank(dto.revision()) == null ? "A" : dto.revision().trim();
        Map<String, Object> item = db.insert(PlmItem.class, Obj.of("productId", dto.productId(), "revision", revision,
                "lifecycleState", state, "ownerId", dto.ownerId(), "description", PlmSupport.blank(dto.description()),
                "notes", PlmSupport.blank(dto.notes()), "releasedAt", "released".equals(state) ? Instant.now() : null,
                "createdBy", TenantContext.userId()));
        history(str(item, "id"), "registered", null, state, null, revision, null, null);
        return item;
    }

    /**
     * Register every product with an active BOM that is not under PLM yet, as {@code released} revision A: the
     * quickest way to start using PLM on an existing catalogue. Returns how many were registered.
     */
    @Transactional
    public Map<String, Object> registerActive() {
        List<String> products = db.jdbc().sql("SELECT DISTINCT b.\"outputProductId\" FROM \"Bom\" b WHERE"
                + " b.\"organizationId\" = ? AND b.status = 'active' AND b.\"deletedAt\" IS NULL AND NOT EXISTS (SELECT 1"
                + " FROM \"PlmItem\" i WHERE i.\"organizationId\" = b.\"organizationId\""
                + " AND i.\"productId\" = b.\"outputProductId\")").param(MfgDb.org()).query(String.class).list();
        for (String p : products) {
            Map<String, Object> item = db.insert(PlmItem.class, Obj.of("productId", p, "revision", "A",
                    "lifecycleState", "released", "releasedAt", Instant.now(), "createdBy", TenantContext.userId()));
            history(str(item, "id"), "registered", null, "released", null, "A", null, "Registered from its active BOM.");
        }
        return Obj.of("registered", products.size());
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateItem dto) {
        Map<String, Object> item = require(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.ownerId() != null) {
            plm.requireUser(PlmSupport.blank(dto.ownerId()), "ownerId");
            data.put("ownerId", PlmSupport.blank(dto.ownerId()));
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.notes() != null) {
            data.put("notes", PlmSupport.blank(dto.notes()));
        }
        if (dto.replacementProductId() != null) {
            String r = PlmSupport.blank(dto.replacementProductId());
            if (r != null) {
                plm.product(r);
                if (r.equals(item.get("productId"))) {
                    throw ApiException.badRequest("A product cannot replace itself.");
                }
            }
            data.put("replacementProductId", r);
        }
        return data.isEmpty() ? item : db.update(PlmItem.class, id, data);
    }

    /** Direct lifecycle transition (also used when a product ECO is applied). */
    @Transactional
    public Map<String, Object> transition(String id, Transition dto) {
        return transition(require(id), dto.toState(), dto.note(), dto.replacementProductId(), null);
    }

    Map<String, Object> transition(Map<String, Object> item, String to, String note, String replacementProductId,
            String changeId) {
        String from = str(item, "lifecycleState");
        String productId = str(item, "productId");
        Map<String, Object> product = plm.product(productId);
        if (from.equals(to)) {
            throw ApiException.badRequest("Product " + product.get("code") + " is already " + human(to) + ".");
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.badRequest("Cannot move " + product.get("code") + " from " + human(from) + " to "
                    + human(to) + ".");
        }
        if ("released".equals(to)) {
            long boms = count("SELECT count(*) FROM \"Bom\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ?"
                    + " AND \"deletedAt\" IS NULL", productId);
            long active = count("SELECT count(*) FROM \"Bom\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND status = 'active'", productId);
            if (boms > 0 && active == 0) {
                throw ApiException.badRequest("Release needs an active BOM for " + product.get("code") + ".");
            }
        }
        if ("obsolete".equals(to)) {
            List<String> parents = db.jdbc().sql("SELECT DISTINCT b.code FROM \"BomLine\" l JOIN \"Bom\" b ON b.id ="
                    + " l.\"bomId\" WHERE b.\"organizationId\" = ? AND l.\"componentProductId\" = ? AND b.status ="
                    + " 'active' AND b.\"deletedAt\" IS NULL AND NOT EXISTS (SELECT 1 FROM \"PlmItem\" i WHERE"
                    + " i.\"organizationId\" = b.\"organizationId\" AND i.\"productId\" = b.\"outputProductId\" AND"
                    + " i.\"lifecycleState\" = 'obsolete') ORDER BY b.code").params(MfgDb.org(), productId)
                    .query(String.class).list();
            if (!parents.isEmpty()) {
                throw ApiException.badRequest(product.get("code") + " is still a component of active BOM(s): "
                        + String.join(", ", parents.subList(0, Math.min(5, parents.size())))
                        + (parents.size() > 5 ? " …" : "") + ". Change those BOMs first.");
            }
            List<String> orders = db.jdbc().sql("SELECT \"orderCode\" FROM \"ProductionOrder\" WHERE"
                    + " \"organizationId\" = ? AND \"outputProductId\" = ? AND status::text IN ('draft', 'confirmed',"
                    + " 'in_progress', 'qc_hold') ORDER BY \"orderCode\"").params(MfgDb.org(), productId)
                    .query(String.class).list();
            if (!orders.isEmpty()) {
                throw ApiException.badRequest(product.get("code") + " has open production orders: "
                        + String.join(", ", orders.subList(0, Math.min(5, orders.size())))
                        + (orders.size() > 5 ? " …" : "") + ".");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lifecycleState", to);
        if ("released".equals(to)) {
            data.put("releasedAt", Instant.now());
        }
        if ("obsolete".equals(to)) {
            data.put("obsoleteAt", Instant.now());
        }
        String replacement = PlmSupport.blank(replacementProductId);
        if (replacement != null) {
            plm.product(replacement);
            if (replacement.equals(productId)) {
                throw ApiException.badRequest("A product cannot replace itself.");
            }
            data.put("replacementProductId", replacement);
        }
        Map<String, Object> updated = db.update(PlmItem.class, str(item, "id"), data);
        history(str(item, "id"), "transition", from, to, null, null, changeId, PlmSupport.blank(note));
        plm.event(DomainEvents.PLM_LIFECYCLE_CHANGED, Obj.of("itemId", item.get("id"), "productId", productId,
                "fromState", from, "toState", to, "changeId", changeId));
        return updated;
    }

    /** Bump (or set) the revision of the product's item when it is under PLM; returns the new revision or null. */
    String bumpRevision(String productId, String explicit, String changeId, String note) {
        Map<String, Object> item = byProduct(productId);
        if (item == null) {
            return null;
        }
        String from = str(item, "revision");
        String to = PlmSupport.blank(explicit) != null ? explicit.trim() : PlmSupport.nextRevision(from);
        if (to.equals(from)) {
            return from;
        }
        db.update(PlmItem.class, str(item, "id"), Obj.of("revision", to));
        history(str(item, "id"), "revision", null, null, from, to, changeId, note);
        return to;
    }

    // ---- cockpit ---------------------------------------------------------------------------------------------------

    /** The product cockpit. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String id) {
        Map<String, Object> item = require(id);
        String productId = str(item, "productId");
        Map<String, Object> out = new LinkedHashMap<>(item);
        out.put("product", plm.product(productId));
        out.put("replacement", plm.productOrNull(str(item, "replacementProductId")));
        out.put("allowedTransitions", TRANSITIONS.getOrDefault(str(item, "lifecycleState"), Set.of()).stream()
                .sorted(java.util.Comparator.comparingInt(PlmSupport.STATES::indexOf)).toList());

        List<Map<String, Object>> history = db.list(PlmLifecycleEvent.class, " AND \"itemId\" = ?"
                + " ORDER BY \"createdAt\" DESC", id);
        Set<String> users = new HashSet<>();
        users.add(str(item, "ownerId"));
        history.forEach(h -> users.add(str(h, "userId")));

        List<Map<String, Object>> boms = SqlRows.list(db.jdbc().sql("SELECT id, code, name, version, status::text AS"
                + " status, \"isDefault\", \"routingId\", \"effectiveFrom\", \"effectiveTo\", \"updatedAt\" FROM \"Bom\""
                + " WHERE \"organizationId\" = ? AND \"outputProductId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY version DESC").params(MfgDb.org(), productId));
        List<Map<String, Object>> routings = SqlRows.list(db.jdbc().sql("SELECT r.id, r.name, r.\"isActive\","
                + " (SELECT count(*) FROM \"RoutingOperation\" o WHERE o.\"routingId\" = r.id) AS operations"
                + " FROM \"Routing\" r WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND (r.\"productId\" = ?"
                + " OR r.id IN (SELECT \"routingId\" FROM \"Bom\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ?"
                + " AND \"deletedAt\" IS NULL)) ORDER BY r.\"isActive\" DESC, r.name")
                .params(MfgDb.org(), productId, MfgDb.org(), productId));
        List<Map<String, Object>> changes = SqlRows.list(db.jdbc().sql("SELECT e.id, e.number, e.title, e.type,"
                + " e.status, e.priority, e.\"stageId\", s.name AS \"stageName\", e.\"newRevision\", e.\"appliedAt\","
                + " e.\"createdAt\" FROM \"EngineeringChange\" e LEFT JOIN \"PlmChangeStage\" s ON s.id = e.\"stageId\""
                + " WHERE e.\"organizationId\" = ? AND e.\"productId\" = ? ORDER BY e.\"createdAt\" DESC")
                .params(MfgDb.org(), productId));
        List<Map<String, Object>> requests = SqlRows.list(db.jdbc().sql("SELECT id, number, title, status, priority,"
                + " \"reasonCode\", \"changeId\", \"createdAt\" FROM \"PlmChangeRequest\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? ORDER BY \"createdAt\" DESC").params(MfgDb.org(), productId));
        List<Map<String, Object>> documents = SqlRows.list(db.jdbc().sql("SELECT id, number, version, revision, title,"
                + " \"docType\", status, \"fileId\", url, \"fileName\", \"releasedAt\" FROM \"PlmDocument\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"deletedAt\" IS NULL AND status IN ('draft',"
                + " 'released') ORDER BY number, version DESC").params(MfgDb.org(), productId));
        List<Map<String, Object>> openOrders = SqlRows.list(db.jdbc().sql("SELECT id, \"orderCode\", status::text AS"
                + " status, \"plannedQty\", \"bomVersion\" FROM \"ProductionOrder\" WHERE \"organizationId\" = ? AND"
                + " \"outputProductId\" = ? AND status::text IN ('draft', 'confirmed', 'in_progress', 'qc_hold')"
                + " ORDER BY \"createdAt\" DESC LIMIT 50").params(MfgDb.org(), productId));

        Map<String, String> names = plm.userNames(users);
        history.forEach(h -> h.put("userName", names.get(str(h, "userId"))));
        out.put("ownerName", names.get(str(item, "ownerId")));
        out.put("history", history);
        out.put("boms", boms);
        out.put("routings", routings);
        out.put("changes", changes);
        out.put("requests", requests);
        out.put("documents", documents);
        out.put("openOrders", openOrders);
        out.put("whereUsed", costing.whereUsed(productId));
        return out;
    }

    /** Dashboard figures. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        PlmSupport.STATES.forEach(s -> lifecycle.put(s, 0L));
        db.jdbc().sql("SELECT \"lifecycleState\", count(*) FROM \"PlmItem\" WHERE \"organizationId\" = ?"
                + " GROUP BY \"lifecycleState\"").param(MfgDb.org())
                .query((rs, i) -> lifecycle.put(rs.getString(1), rs.getLong(2))).list();
        Map<String, Object> changeStatus = new LinkedHashMap<>();
        for (String s : List.of("draft", "approved", "applied", "rejected")) {
            changeStatus.put(s, 0L);
        }
        db.jdbc().sql("SELECT status, count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? GROUP BY status")
                .param(MfgDb.org()).query((rs, i) -> changeStatus.put(rs.getString(1), rs.getLong(2))).list();
        List<Map<String, Object>> byStage = SqlRows.list(db.jdbc().sql("SELECT s.id, s.name, s.sequence, (SELECT"
                + " count(*) FROM \"EngineeringChange\" e WHERE e.\"organizationId\" = s.\"organizationId\" AND"
                + " e.\"stageId\" = s.id AND e.status IN ('draft', 'approved')) AS count FROM \"PlmChangeStage\" s"
                + " WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL ORDER BY s.sequence")
                .param(MfgDb.org()));
        Map<String, Object> requestStatus = new LinkedHashMap<>();
        for (String s : List.of("new", "accepted", "rejected", "converted")) {
            requestStatus.put(s, 0L);
        }
        db.jdbc().sql("SELECT status, count(*) FROM \"PlmChangeRequest\" WHERE \"organizationId\" = ? GROUP BY status")
                .param(MfgDb.org()).query((rs, i) -> requestStatus.put(rs.getString(1), rs.getLong(2))).list();
        long overdue = count("SELECT count(*) FROM \"PlmChangeRequest\" WHERE \"organizationId\" = ? AND status IN"
                + " ('new', 'accepted') AND \"dueDate\" < CURRENT_DATE", null);
        long appliedLast30 = count("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND status ="
                + " 'applied' AND \"appliedAt\" >= now() - interval '30 days'", null);
        Object avgCycle = db.jdbc().sql("SELECT round(avg(extract(epoch FROM (\"appliedAt\" - \"createdAt\")) / 86400)"
                + "::numeric, 1) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND status = 'applied' AND"
                + " \"appliedAt\" >= now() - interval '180 days'").param(MfgDb.org()).query().singleValue();
        Map<String, Object> docs = new LinkedHashMap<>();
        for (String s : List.of("draft", "released", "superseded", "obsolete")) {
            docs.put(s, 0L);
        }
        db.jdbc().sql("SELECT status, count(*) FROM \"PlmDocument\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL GROUP BY status").param(MfgDb.org())
                .query((rs, i) -> docs.put(rs.getString(1), rs.getLong(2))).list();
        List<Map<String, Object>> recent = SqlRows.list(db.jdbc().sql("SELECT a.id, a.kind, a.message, a.\"userId\","
                + " a.\"createdAt\", a.\"changeId\", e.number, e.title FROM \"PlmChangeActivity\" a JOIN"
                + " \"EngineeringChange\" e ON e.id = a.\"changeId\" WHERE a.\"organizationId\" = ?"
                + " ORDER BY a.\"createdAt\" DESC LIMIT 15").param(MfgDb.org()));
        Set<String> users = new HashSet<>();
        recent.forEach(r -> users.add(str(r, "userId")));
        Map<String, String> names = plm.userNames(users);
        recent.forEach(r -> r.put("userName", names.get(str(r, "userId"))));
        List<Map<String, Object>> awaiting = SqlRows.list(db.jdbc().sql("SELECT e.id, e.number, e.title, e.priority,"
                + " s.name AS \"stageName\", e.\"createdAt\" FROM \"EngineeringChange\" e JOIN \"PlmChangeStage\" s ON"
                + " s.id = e.\"stageId\" WHERE e.\"organizationId\" = ? AND e.status = 'draft' AND s.\"requiresApproval\""
                + " AND ((? AND NOT EXISTS (SELECT 1 FROM \"PlmChangeStageApprover\" a WHERE a.\"stageId\" = s.id)) OR EXISTS"
                + " (SELECT 1 FROM \"PlmChangeStageApprover\" a WHERE a.\"stageId\" = s.id AND a.\"userId\" = ?))"
                + " AND NOT EXISTS (SELECT 1 FROM \"PlmChangeApproval\" p WHERE p.\"changeId\" = e.id AND p.\"stageId\""
                + " = s.id AND p.\"userId\" = ? AND NOT p.voided) ORDER BY e.\"createdAt\" LIMIT 20")
                .params(MfgDb.org(), TenantContext.has(com.inventoryerp.kernel.security.Permissions.PLM__APPROVE),
                        TenantContext.userId(), TenantContext.userId()));
        return Obj.of("lifecycle", lifecycle, "items", lifecycle.values().stream().mapToLong(v -> (Long) v).sum(),
                "changes", Obj.of("byStatus", changeStatus, "byStage", byStage, "appliedLast30Days", appliedLast30,
                        "avgCycleDays", avgCycle),
                "requests", Obj.of("byStatus", requestStatus, "overdue", overdue),
                "documents", docs, "awaitingMyApproval", awaiting, "recentActivity", recent);
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    Map<String, Object> byProduct(String productId) {
        List<Map<String, Object>> rows = db.list(PlmItem.class, " AND \"productId\" = ?", productId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> require(String id) {
        Map<String, Object> item = db.byId(PlmItem.class, id);
        if (item == null) {
            throw ApiException.notFound("PLM item not found");
        }
        return item;
    }

    private void history(String itemId, String kind, String from, String to, String fromRev, String toRev,
            String changeId, String note) {
        db.insert(PlmLifecycleEvent.class, Obj.of("itemId", itemId, "kind", kind, "fromState", from, "toState", to,
                "fromRevision", fromRev, "toRevision", toRev, "changeId", changeId, "note", note, "userId",
                TenantContext.userId()));
    }

    private long count(String sql, String productId) {
        var spec = db.jdbc().sql(sql);
        return (productId == null ? spec.param(MfgDb.org()) : spec.params(MfgDb.org(), productId))
                .query(Long.class).single();
    }

    static String human(String state) {
        return state == null ? "" : state.replace('_', ' ');
    }
}
