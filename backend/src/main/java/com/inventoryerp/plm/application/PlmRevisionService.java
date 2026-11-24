package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.plm.domain.PlmAsBuilt;
import com.inventoryerp.plm.domain.PlmItem;
import com.inventoryerp.plm.domain.PlmRevision;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product versions (ADR-S086 §3). Every revision of a PLM product is kept with a snapshot of what it was — the
 * default active BOM with its lines, the routing operations, the released engineering documents, the product master
 * data and the unit cost — so two revisions can be compared and a production order can be traced to the revision it
 * produced (as-built). A revision code is never reused for the same product.
 */
@Service
public class PlmRevisionService {

    /** Product fields kept in a snapshot and compared between revisions. */
    static final List<String> PRODUCT_FIELDS = List.of("code", "name", "description", "barcode", "weight", "volume",
            "uomName", "categoryName", "image");

    private final PlmDb db;
    private final PlmSupport plm;
    private final StandardCostService standards;

    public PlmRevisionService(PlmDb db, PlmSupport plm, StandardCostService standards) {
        this.db = db;
        this.plm = plm;
        this.standards = standards;
    }

    // ================================================================== queries

    /** The revisions of a PLM item, newest first (the current one is captured first if it was never recorded). */
    @Transactional
    public List<Map<String, Object>> list(String itemId) {
        Map<String, Object> item = item(itemId);
        ensureCurrent(item);
        List<Map<String, Object>> rows = db.list(PlmRevision.class, " AND \"itemId\" = ? ORDER BY \"effectiveFrom\""
                + " DESC, \"createdAt\" DESC", itemId);
        Set<String> users = new HashSet<>();
        Set<String> changes = new HashSet<>();
        rows.forEach(r -> {
            users.add(str(r, "createdBy"));
            changes.add(str(r, "changeId"));
        });
        Map<String, String> names = plm.userNames(users);
        Map<String, String> numbers = changeNumbers(changes);
        Map<String, Long> built = new LinkedHashMap<>();
        db.jdbc().sql("SELECT \"revisionId\", count(*) FROM \"PlmAsBuilt\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"revisionId\" IS NOT NULL GROUP BY \"revisionId\"")
                .params(PlmDb.org(), item.get("productId")).query((rs, i) -> built.put(rs.getString(1), rs.getLong(2)))
                .list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = summary(r);
            row.put("createdByName", names.get(str(r, "createdBy")));
            row.put("changeNumber", numbers.get(str(r, "changeId")));
            row.put("ordersBuilt", built.getOrDefault(str(r, "id"), 0L));
            out.add(row);
        }
        return out;
    }

    /** One revision with its full snapshot. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = require(id);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("changeNumber", changeNumbers(java.util.Collections.singleton(str(r, "changeId")))
                .get(str(r, "changeId")));
        out.put("product", plm.productOrNull(str(r, "productId")));
        out.put("recorded", snapshotted(r));
        return out;
    }

    /**
     * What changed between two revisions of the same product: product data, BOM header and lines, routing
     * operations, released documents and unit cost.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(String fromId, String toId) {
        Map<String, Object> from = require(fromId);
        Map<String, Object> to = require(toId);
        if (!from.get("productId").equals(to.get("productId"))) {
            throw ApiException.badRequest("Both revisions must belong to the same product.");
        }
        Map<String, Object> a = snap(from);
        Map<String, Object> b = snap(to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", summary(from));
        out.put("to", summary(to));
        out.put("recorded", snapshotted(from) && snapshotted(to));

        Map<String, Object> pa = (Map<String, Object>) a.getOrDefault("product", Map.of());
        Map<String, Object> pb = (Map<String, Object>) b.getOrDefault("product", Map.of());
        List<Map<String, Object>> product = new ArrayList<>();
        for (String f : PRODUCT_FIELDS) {
            if (!BomRebase.sameValue(pa.get(f), pb.get(f))) {
                product.add(Obj.of("field", f, "from", pa.get(f), "to", pb.get(f)));
            }
        }
        out.put("product", product);
        out.put("bom", compareBoms((Map<String, Object>) a.get("bom"), (Map<String, Object>) b.get("bom")));
        out.put("routing", compareRoutings((Map<String, Object>) a.get("routing"), (Map<String, Object>) b.get("routing")));
        out.put("documents", compareDocuments((List<Map<String, Object>>) a.getOrDefault("documents", List.of()),
                (List<Map<String, Object>>) b.getOrDefault("documents", List.of())));
        BigDecimal ca = (BigDecimal) from.get("unitCost");
        BigDecimal cb = (BigDecimal) to.get("unitCost");
        out.put("cost", Obj.of("from", ca, "to", cb, "delta", ca == null || cb == null ? null : cb.subtract(ca)));
        return out;
    }

    /** As-built: the production orders of the item's product with the revision each produced, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> asBuilt(String itemId) {
        Map<String, Object> item = item(itemId);
        return db.list(PlmAsBuilt.class, " AND \"productId\" = ? ORDER BY \"completedAt\" DESC NULLS LAST,"
                + " \"createdAt\" DESC LIMIT 200", item.get("productId"));
    }

    // ================================================================== writes (used by the lifecycle / change services)

    /** Refuse a revision code the product already used: revisions are never reused. */
    void requireUnused(String productId, String revision) {
        Long used = db.jdbc().sql("SELECT count(*) FROM \"PlmRevision\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND revision = ?").params(PlmDb.org(), productId, revision).query(Long.class).single();
        if (used > 0) {
            Map<String, Object> p = plm.productOrNull(productId);
            throw ApiException.badRequest("Revision " + revision + " of " + (p == null ? productId : p.get("code"))
                    + " was already used; choose another revision.");
        }
    }

    /**
     * Record {@code revision} as the item's current revision: the previous current one is superseded now and the
     * new one gets a snapshot of the product as it is at this moment.
     */
    Map<String, Object> record(Map<String, Object> item, String revision, String changeId, String note) {
        String productId = str(item, "productId");
        lock(productId);
        Instant now = Instant.now();
        db.updateWhere(PlmRevision.class, Obj.of("status", "superseded", "effectiveTo", now),
                "\"organizationId\" = ? AND \"productId\" = ? AND status = 'current'", PlmDb.org(), productId);
        Map<String, Object> snapshot = capture(productId);
        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) snapshot.get("bom");
        @SuppressWarnings("unchecked")
        Map<String, Object> routing = (Map<String, Object>) snapshot.get("routing");
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) snapshot.get("cost");
        Map<String, Object> row = db.insert(PlmRevision.class, Obj.of("itemId", item.get("id"), "productId", productId,
                "revision", revision, "status", "current", "effectiveFrom", now, "changeId", changeId, "bomId",
                bom == null ? null : bom.get("id"), "bomVersion", bom == null ? null : bom.get("version"),
                "routingId", routing == null ? null : routing.get("id"), "unitCost", cost.get("unitCost"),
                "costSource", cost.get("source"), "snapshot", new PlmDb.Json(snapshot), "note", note, "createdBy",
                TenantContext.userId()));
        plm.event(DomainEvents.PLM_REVISION_CREATED, Obj.of("itemId", item.get("id"), "productId", productId,
                "revisionId", row.get("id"), "revision", revision, "changeId", changeId));
        return row;
    }

    /** The current revision row of the item, captured from the live data when it was never recorded. */
    Map<String, Object> ensureCurrent(Map<String, Object> item) {
        List<Map<String, Object>> current = db.list(PlmRevision.class, " AND \"productId\" = ? AND status = 'current'",
                item.get("productId"));
        if (!current.isEmpty() && current.getFirst().get("revision").equals(item.get("revision"))) {
            return current.getFirst();
        }
        String productId = str(item, "productId");
        lock(productId);
        current = db.list(PlmRevision.class, " AND \"productId\" = ? AND status = 'current'", productId);
        if (!current.isEmpty() && current.getFirst().get("revision").equals(item.get("revision"))) {
            return current.getFirst();
        }
        List<Map<String, Object>> same = db.list(PlmRevision.class, " AND \"productId\" = ? AND revision = ?",
                productId, item.get("revision"));
        if (!same.isEmpty()) {
            // The item went back to a revision recorded earlier without a snapshot: make it current again.
            db.updateWhere(PlmRevision.class, Obj.of("status", "superseded", "effectiveTo", Instant.now()),
                    "\"organizationId\" = ? AND \"productId\" = ? AND status = 'current'", PlmDb.org(), productId);
            return db.update(PlmRevision.class, str(same.getFirst(), "id"), Obj.of("status", "current",
                    "effectiveTo", null, "snapshot", new PlmDb.Json(capture(productId))));
        }
        Instant since = db.jdbc().sql("SELECT max(\"createdAt\") FROM \"PlmLifecycleEvent\" WHERE \"organizationId\" = ?"
                + " AND \"itemId\" = ? AND kind IN ('registered', 'revision')").params(PlmDb.org(), item.get("id"))
                .query(java.sql.Timestamp.class).optional().map(t -> (Instant) SqlRows.value(t))
                .orElse((Instant) item.get("createdAt"));
        Map<String, Object> snapshot = capture(productId);
        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) snapshot.get("bom");
        @SuppressWarnings("unchecked")
        Map<String, Object> routing = (Map<String, Object>) snapshot.get("routing");
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) snapshot.get("cost");
        db.updateWhere(PlmRevision.class, Obj.of("status", "superseded", "effectiveTo", since),
                "\"organizationId\" = ? AND \"productId\" = ? AND status = 'current'", PlmDb.org(), productId);
        return db.insert(PlmRevision.class, Obj.of("itemId", item.get("id"), "productId", productId, "revision",
                item.get("revision"), "status", "current", "effectiveFrom", since == null ? Instant.now() : since,
                "bomId", bom == null ? null : bom.get("id"), "bomVersion", bom == null ? null : bom.get("version"),
                "routingId", routing == null ? null : routing.get("id"), "unitCost", cost.get("unitCost"),
                "costSource", cost.get("source"), "snapshot", new PlmDb.Json(snapshot), "note",
                "Captured from the live data (the revision predates PLM versions)."));
    }

    /**
     * As-built (event handler, own transaction): store the revision a completed order of a PLM product produced —
     * the revision whose BOM the order used (preferring the one effective when the order was created), else the
     * revision current when the order was created. Idempotent per order.
     */
    @Transactional
    public void recordAsBuilt(String orderId) {
        Map<String, Object> order = SqlRows.one(db.jdbc().sql("SELECT id, \"orderCode\", status::text AS status,"
                + " \"outputProductId\", \"bomId\", \"bomVersion\", \"batchNumber\", \"producedQty\", \"completedAt\","
                + " \"createdAt\" FROM \"ProductionOrder\" WHERE id = ? AND \"organizationId\" = ?")
                .params(orderId, PlmDb.org()));
        if (order == null || !Set.of("completed", "qc_hold").contains(str(order, "status"))) {
            return;
        }
        Map<String, Object> item = itemOfProduct(str(order, "outputProductId"));
        if (item == null) {
            return;
        }
        ensureCurrent(item);
        Map<String, Object> rev = revisionOf(order);
        String matchedBy = rev == null ? "none" : (String) rev.remove("_matchedBy");
        db.jdbc().sql("INSERT INTO \"PlmAsBuilt\" (id, \"organizationId\", \"orderId\", \"orderCode\", \"productId\","
                + " \"revisionId\", revision, \"matchedBy\", \"bomId\", \"bomVersion\", \"batchNumber\", quantity,"
                + " \"completedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\","
                + " \"orderId\") DO UPDATE SET \"revisionId\" = EXCLUDED.\"revisionId\", revision = EXCLUDED.revision,"
                + " \"matchedBy\" = EXCLUDED.\"matchedBy\", \"batchNumber\" = EXCLUDED.\"batchNumber\", quantity ="
                + " EXCLUDED.quantity, \"completedAt\" = EXCLUDED.\"completedAt\"")
                .params(java.util.UUID.randomUUID().toString(), PlmDb.org(), orderId, order.get("orderCode"),
                        order.get("outputProductId"), rev == null ? null : rev.get("id"), rev == null ? null
                                : rev.get("revision"), matchedBy, order.get("bomId"), order.get("bomVersion"),
                        order.get("batchNumber"), order.get("producedQty") == null ? BigDecimal.ZERO
                                : order.get("producedQty"), SqlRows.ts((Instant) order.get("completedAt")))
                .update();
    }

    /** The revision a production order produced (with {@code _matchedBy}), or null. */
    Map<String, Object> revisionOf(Map<String, Object> order) {
        String productId = str(order, "outputProductId");
        Instant created = (Instant) order.get("createdAt");
        if (order.get("bomId") != null) {
            List<Map<String, Object>> byBom = db.list(PlmRevision.class, " AND \"productId\" = ? AND \"bomId\" = ?"
                    + " ORDER BY (\"effectiveFrom\" <= ? AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" > ?)) DESC,"
                    + " \"effectiveFrom\" DESC LIMIT 1", productId, order.get("bomId"), SqlRows.ts(created),
                    SqlRows.ts(created));
            if (!byBom.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>(byBom.getFirst());
                r.put("_matchedBy", "bom");
                return r;
            }
        }
        List<Map<String, Object>> byDate = db.list(PlmRevision.class, " AND \"productId\" = ? AND \"effectiveFrom\" <= ?"
                + " AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" > ?) ORDER BY \"effectiveFrom\" DESC LIMIT 1",
                productId, SqlRows.ts(created), SqlRows.ts(created));
        if (byDate.isEmpty()) {
            return null;
        }
        Map<String, Object> r = new LinkedHashMap<>(byDate.getFirst());
        r.put("_matchedBy", "date");
        return r;
    }

    // ================================================================== snapshot

    /** What the product is right now: master data, default active BOM and lines, routing, released documents, cost. */
    Map<String, Object> capture(String productId) {
        Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT p.id, p.code, p.name, p.description, p.barcode,"
                + " p.weight, p.volume, p.image, p.\"uomId\", u.name AS \"uomName\", p.\"categoryId\", c.name AS"
                + " \"categoryName\", p.\"costingMethod\", p.\"costPrice\" FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u"
                + " ON u.id = p.\"uomId\" LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.id = ? AND"
                + " p.\"organizationId\" = ?").params(productId, PlmDb.org()));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        if (product != null) {
            for (String f : List.of("code", "name", "description", "barcode", "weight", "volume", "image", "uomId",
                    "uomName", "categoryId", "categoryName", "costingMethod")) {
                data.put(f, product.get(f));
            }
        }
        out.put("product", data);
        Map<String, Object> bom = SqlRows.one(db.jdbc().sql("SELECT id, code, name, version, \"outputQuantity\","
                + " \"outputUomId\", \"expectedYieldPct\", \"overheadCost\", \"routingId\", \"outputVariantKey\" FROM \"Bom\""
                + " WHERE \"organizationId\" = ? AND \"outputProductId\" = ? AND status::text = 'active' AND \"deletedAt\""
                + " IS NULL ORDER BY \"isDefault\" DESC, (\"outputVariantKey\" = '') DESC, version DESC LIMIT 1")
                .params(PlmDb.org(), productId));
        if (bom != null) {
            bom.put("lines", SqlRows.list(db.jdbc().sql("SELECT l.\"componentProductId\", l.\"componentVariantId\","
                    + " l.quantity, l.\"uomId\", l.\"scrapPct\", l.sequence, l.\"isOptional\", l.notes, p.code AS"
                    + " \"componentCode\", p.name AS \"componentName\" FROM \"BomLine\" l LEFT JOIN \"Product\" p ON p.id ="
                    + " l.\"componentProductId\" WHERE l.\"bomId\" = ? AND l.\"organizationId\" = ? ORDER BY l.sequence,"
                    + " l.id").params(bom.get("id"), PlmDb.org())));
        }
        out.put("bom", bom);
        String routingId = bom != null && bom.get("routingId") != null ? str(bom, "routingId")
                : db.jdbc().sql("SELECT id FROM \"Routing\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"isActive\" AND \"deletedAt\" IS NULL ORDER BY \"updatedAt\" DESC LIMIT 1")
                        .params(PlmDb.org(), productId).query(String.class).optional().orElse(null);
        Map<String, Object> routing = null;
        if (routingId != null) {
            routing = SqlRows.one(db.jdbc().sql("SELECT id, name FROM \"Routing\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(routingId, PlmDb.org()));
            if (routing != null) {
                routing.put("operations", SqlRows.list(db.jdbc().sql("SELECT o.sequence, o.name, o.\"workCenterId\","
                        + " w.name AS \"workCenterName\", o.\"durationMins\", o.\"setupMins\", o.\"isSubcontracted\" FROM"
                        + " \"RoutingOperation\" o LEFT JOIN \"WorkCenter\" w ON w.id = o.\"workCenterId\" WHERE"
                        + " o.\"routingId\" = ? AND o.\"organizationId\" = ? ORDER BY o.sequence")
                        .params(routingId, PlmDb.org())));
            }
        }
        out.put("routing", routing);
        out.put("documents", SqlRows.list(db.jdbc().sql("SELECT id, number, version, revision, title, \"docType\" FROM"
                + " \"PlmDocument\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND status = 'released' AND"
                + " \"deletedAt\" IS NULL ORDER BY number").params(PlmDb.org(), productId)));
        Optional<BigDecimal> standard = standards.activeStandard(productId);
        out.put("cost", standard.isPresent() ? Obj.of("unitCost", standard.get(), "source", "standard")
                : Obj.of("unitCost", product == null ? null : product.get("costPrice"), "source",
                        product == null || product.get("costPrice") == null ? null : "cost_price"));
        return out;
    }

    // ================================================================== internals

    @SuppressWarnings("unchecked")
    private Map<String, Object> compareBoms(Map<String, Object> a, Map<String, Object> b) {
        if (a == null && b == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", a == null ? null : Obj.of("id", a.get("id"), "code", a.get("code"), "version", a.get("version")));
        out.put("to", b == null ? null : Obj.of("id", b.get("id"), "code", b.get("code"), "version", b.get("version")));
        List<Map<String, Object>> header = new ArrayList<>();
        for (String f : List.of("outputQuantity", "expectedYieldPct", "overheadCost", "routingId")) {
            Object x = a == null ? null : a.get(f);
            Object y = b == null ? null : b.get(f);
            if (!BomRebase.sameValue(x, y)) {
                header.add(Obj.of("field", f, "from", x, "to", y));
            }
        }
        out.put("header", header);
        Map<String, Map<String, Object>> la = BomRebase.keyed(a == null ? List.of()
                : (List<Map<String, Object>>) a.getOrDefault("lines", List.of()));
        Map<String, Map<String, Object>> lb = BomRebase.keyed(b == null ? List.of()
                : (List<Map<String, Object>>) b.getOrDefault("lines", List.of()));
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(la.keySet());
        keys.addAll(lb.keySet());
        for (String k : keys) {
            Map<String, Object> x = la.get(k);
            Map<String, Object> y = lb.get(k);
            if (x == null) {
                added.add(y);
            } else if (y == null) {
                removed.add(x);
            } else {
                List<Map<String, Object>> changes = BomRebase.lineChanges(x, y);
                if (!changes.isEmpty()) {
                    changed.add(Obj.of("componentProductId", y.get("componentProductId"), "componentCode",
                            y.get("componentCode"), "componentName", y.get("componentName"), "changes", changes));
                }
            }
        }
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compareRoutings(Map<String, Object> a, Map<String, Object> b) {
        if (a == null && b == null) {
            return null;
        }
        Map<String, Map<String, Object>> oa = new LinkedHashMap<>();
        Map<String, Map<String, Object>> ob = new LinkedHashMap<>();
        if (a != null) {
            ((List<Map<String, Object>>) a.getOrDefault("operations", List.of()))
                    .forEach(o -> oa.put(String.valueOf(o.get("sequence")), o));
        }
        if (b != null) {
            ((List<Map<String, Object>>) b.getOrDefault("operations", List.of()))
                    .forEach(o -> ob.put(String.valueOf(o.get("sequence")), o));
        }
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(oa.keySet());
        keys.addAll(ob.keySet());
        for (String k : keys) {
            Map<String, Object> x = oa.get(k);
            Map<String, Object> y = ob.get(k);
            if (x == null) {
                added.add(y);
            } else if (y == null) {
                removed.add(x);
            } else {
                List<Map<String, Object>> changes = new ArrayList<>();
                for (String f : List.of("name", "workCenterName", "durationMins", "setupMins", "isSubcontracted")) {
                    if (!BomRebase.sameValue(x.get(f), y.get(f))) {
                        changes.add(Obj.of("field", f, "from", x.get(f), "to", y.get(f)));
                    }
                }
                if (!changes.isEmpty()) {
                    changed.add(Obj.of("sequence", y.get("sequence"), "name", y.get("name"), "changes", changes));
                }
            }
        }
        return Obj.of("from", a == null ? null : Obj.of("id", a.get("id"), "name", a.get("name")), "to",
                b == null ? null : Obj.of("id", b.get("id"), "name", b.get("name")), "added", added, "removed", removed,
                "changed", changed);
    }

    private static Map<String, Object> compareDocuments(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        Map<String, Map<String, Object>> da = new LinkedHashMap<>();
        Map<String, Map<String, Object>> dbs = new LinkedHashMap<>();
        a.forEach(d -> da.put(str(d, "number"), d));
        b.forEach(d -> dbs.put(str(d, "number"), d));
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> revised = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(da.keySet());
        keys.addAll(dbs.keySet());
        for (String k : keys) {
            Map<String, Object> x = da.get(k);
            Map<String, Object> y = dbs.get(k);
            if (x == null) {
                added.add(y);
            } else if (y == null) {
                removed.add(x);
            } else if (!BomRebase.sameValue(x.get("version"), y.get("version"))
                    || !BomRebase.sameValue(x.get("revision"), y.get("revision"))) {
                revised.add(Obj.of("number", k, "title", y.get("title"), "from", Obj.of("version", x.get("version"),
                        "revision", x.get("revision")), "to", Obj.of("version", y.get("version"), "revision",
                                y.get("revision"))));
            }
        }
        return Obj.of("added", added, "removed", removed, "revised", revised);
    }

    /** The list view of a revision: everything but the snapshot, plus counts from it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        Map<String, Object> s = snap(r);
        out.remove("snapshot");
        Map<String, Object> bom = (Map<String, Object>) s.get("bom");
        Map<String, Object> routing = (Map<String, Object>) s.get("routing");
        out.put("bom", bom == null ? null : Obj.of("id", bom.get("id"), "code", bom.get("code"), "version",
                bom.get("version"), "lines", ((List<?>) bom.getOrDefault("lines", List.of())).size()));
        out.put("routing", routing == null ? null : Obj.of("id", routing.get("id"), "name", routing.get("name"),
                "operations", ((List<?>) routing.getOrDefault("operations", List.of())).size()));
        out.put("documents", ((List<?>) s.getOrDefault("documents", List.of())).size());
        out.put("recorded", snapshotted(r));
        return out;
    }

    private static Map<String, Object> snap(Map<String, Object> r) {
        return PlmSupport.object(r.get("snapshot"));
    }

    private static boolean snapshotted(Map<String, Object> r) {
        return !snap(r).isEmpty();
    }

    private Map<String, String> changeNumbers(Set<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> wanted = ids.stream().filter(java.util.Objects::nonNull).toList();
        if (wanted.isEmpty()) {
            return out;
        }
        db.jdbc().sql("SELECT id, number FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])")
                .params(PlmDb.org(), wanted.toArray(String[]::new))
                .query((rs, i) -> out.put(rs.getString(1), rs.getString(2))).list();
        return out;
    }

    private Map<String, Object> item(String id) {
        Map<String, Object> item = db.byId(PlmItem.class, id);
        if (item == null) {
            throw ApiException.notFound("PLM item not found");
        }
        return item;
    }

    private Map<String, Object> itemOfProduct(String productId) {
        List<Map<String, Object>> rows = db.list(PlmItem.class, " AND \"productId\" = ?", productId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> r = db.byId(PlmRevision.class, id);
        if (r == null) {
            throw ApiException.notFound("Revision not found");
        }
        return r;
    }

    private void lock(String productId) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("plm-rev:" + PlmDb.org() + ":"
                + productId).query().listOfRows();
    }
}
