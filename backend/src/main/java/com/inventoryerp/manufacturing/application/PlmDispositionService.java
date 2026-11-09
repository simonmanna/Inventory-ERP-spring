package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StockDocService;
import com.inventoryerp.inventory.application.StockDocService.DocInput;
import com.inventoryerp.inventory.application.StockDocService.DocLine;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.MfgCommands.CancelOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.procurement.application.DebitNotesService;
import com.inventoryerp.procurement.application.DebitNotesService.CreateInput;
import com.inventoryerp.procurement.application.DebitNotesService.LineInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Stock and WIP dispositions of a BOM change order (ADR-S063 §3.1).
 *
 * <p><b>Affected stock</b> = on-hand stock (outside QC quarantine) of the components the new version removes and that
 * no other active BOM uses. Components still used elsewhere are kept and reported. Dispositions raise <b>draft</b>
 * inventory / procurement documents, so inventory's own approval, attribution and posting rules apply:
 * <ul>
 *   <li>{@code use_up}: the switch-over waits until the affected stock is consumed (see {@link #useUpPending});</li>
 *   <li>{@code scrap}: one stock adjustment per location counting the components to zero (reason {@code other});</li>
 *   <li>{@code rework}: one transfer per location into the QC quarantine location;</li>
 *   <li>{@code return_to_supplier}: one inbound {@code returned_goods} debit note per preferred supplier × location,
 *       at the location's running average cost (components without a supplier are reported);</li>
 *   <li>{@code none}: nothing.</li>
 * </ul>
 * <b>Open orders</b> on the old version:
 * <ul>
 *   <li>{@code continue}: they finish on the old version;</li>
 *   <li>{@code update}: draft / confirmed orders are cancelled and re-created on the new version (same quantity,
 *       locations, schedule, batch; request, plan and demand pegs carried over; confirmed again when they were);</li>
 *   <li>{@code rework}: as {@code update}, and in-progress orders get {@code qcRequired} so their output lands on
 *       QC hold for the existing rework flow.</li>
 * </ul>
 * Multi-stage and multi-level orders are never re-created automatically: they are reported as kept.
 */
@Service
public class PlmDispositionService {

    private static final Set<String> NOT_STARTED = Set.of("draft", "confirmed");

    private final MfgDb db;
    private final PlmSupport plm;
    private final EngineeringService engineering;
    private final ProductionService production;
    private final StockDocService stockDocs;
    private final DebitNotesService debitNotes;

    public PlmDispositionService(MfgDb db, PlmSupport plm, EngineeringService engineering,
            ProductionService production, StockDocService stockDocs, DebitNotesService debitNotes) {
        this.db = db;
        this.plm = plm;
        this.engineering = engineering;
        this.production = production;
        this.stockDocs = stockDocs;
        this.debitNotes = debitNotes;
    }

    /** One affected stock row: component × location. */
    record Affected(String productId, String code, String name, String locationId, String locationName,
            BigDecimal quantity, BigDecimal unitCost) {}

    // ================================================================== planning

    /** Affected stock rows plus the removed components kept because something else still uses them. */
    @SuppressWarnings("unchecked")
    Map<String, Object> affected(Map<String, Object> eco) {
        List<Affected> rows = new ArrayList<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        if (!"bom".equals(eco.get("type")) || eco.get("bomId") == null || eco.get("newBomId") == null
                || !bomsExist(eco)) {
            return Obj.of("rows", rows, "kept", kept);
        }
        String oldBom = str(eco, "bomId");
        Map<String, Object> diff = engineering.compare(oldBom, str(eco, "newBomId"));
        Set<String> removed = new HashSet<>();
        ((List<Map<String, Object>>) diff.get("removed")).forEach(l -> removed.add(str(l, "componentProductId")));
        Map<String, Map<String, Object>> products = plm.products(removed);
        String quarantine = quarantineId();
        for (String c : removed) {
            long uses = db.jdbc().sql("SELECT count(DISTINCT b.id) FROM \"BomLine\" l JOIN \"Bom\" b ON b.id ="
                    + " l.\"bomId\" WHERE b.\"organizationId\" = ? AND l.\"componentProductId\" = ? AND"
                    + " b.status::text = 'active' AND b.\"deletedAt\" IS NULL AND b.id <> ?")
                    .params(MfgDb.org(), c, oldBom).query(Long.class).single();
            if (uses > 0) {
                kept.add(Obj.of("productId", c, "product", products.get(c), "reason", "used by " + uses
                        + " other active BOM(s)"));
                continue;
            }
            for (Map<String, Object> s : SqlRows.list(db.jdbc().sql("SELECT s.\"locationId\", l.name AS"
                    + " \"locationName\", sum(s.quantity) AS quantity, max(s.\"runningAverageCost\") AS cost,"
                    + " max(p.\"costPrice\") AS \"costPrice\" FROM \"StockItem\" s JOIN \"InventoryLocation\" l ON"
                    + " l.id = s.\"locationId\" JOIN \"Product\" p ON p.id = s.\"productId\" WHERE"
                    + " s.\"organizationId\" = ? AND s.\"productId\" = ? AND s.quantity > 0 AND l.type::text <>"
                    + " 'transit' AND l.id <> ? GROUP BY s.\"locationId\", l.name ORDER BY l.name")
                    .params(MfgDb.org(), c, quarantine == null ? "" : quarantine))) {
                BigDecimal cost = dec(s, "cost");
                if (cost == null || cost.signum() == 0) {
                    cost = dec(s, "costPrice");
                }
                Map<String, Object> p = products.get(c);
                rows.add(new Affected(c, p == null ? c : str(p, "code"), p == null ? c : str(p, "name"),
                        str(s, "locationId"), str(s, "locationName"), dec(s, "quantity"),
                        cost == null ? BigDecimal.ZERO : cost));
            }
        }
        return Obj.of("rows", rows, "kept", kept);
    }

    /** Removed components whose stock still waits to be used up (empty unless the ECO uses {@code use_up}). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> useUpPending(Map<String, Object> eco) {
        if (!"use_up".equals(eco.get("stockDisposition"))) {
            return List.of();
        }
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Affected a : (List<Affected>) affected(eco).get("rows")) {
            byProduct.merge(a.productId(), a.quantity(), BigDecimal::add);
            labels.put(a.productId(), a.code() + " " + a.name());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byProduct.forEach((p, q) -> out.add(Obj.of("productId", p, "label", labels.get(p), "onHand", q)));
        return out;
    }

    /** What apply will do, for the impact view. */
    @SuppressWarnings("unchecked")
    Map<String, Object> plan(Map<String, Object> eco) {
        Map<String, Object> affected = affected(eco);
        List<Affected> rows = (List<Affected>) affected.get("rows");
        String stock = str(eco, "stockDisposition");
        String action = switch (stock) {
            case "scrap" -> "stock adjustment to zero (draft)";
            case "rework" -> "transfer to QC quarantine (draft)";
            case "return_to_supplier" -> "supplier return (draft debit note)";
            case "use_up" -> "switch over once used up";
            default -> "no action";
        };
        List<Map<String, Object>> stockRows = new ArrayList<>();
        for (Affected a : rows) {
            Map<String, Object> r = Obj.of("productId", a.productId(), "product", Obj.of("id", a.productId(), "code",
                    a.code(), "name", a.name()), "locationId", a.locationId(), "locationName", a.locationName(),
                    "quantity", a.quantity(), "unitCost", a.unitCost(), "action", action);
            if ("return_to_supplier".equals(stock)) {
                Map<String, Object> supplier = supplier(a.productId());
                r.put("supplier", supplier);
                if (supplier == null) {
                    r.put("action", "no supplier: not returned");
                }
            }
            stockRows.add(r);
        }
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> o : openOrders(eco)) {
            orders.add(Obj.of("id", o.get("id"), "orderCode", o.get("orderCode"), "status", o.get("status"),
                    "plannedQty", o.get("plannedQty"), "action", orderAction(eco, o)));
        }
        return Obj.of("stockDisposition", stock, "wipDisposition", eco.get("wipDisposition"), "stock", stockRows,
                "keptComponents", affected.get("kept"), "orders", orders, "useUpPending", useUpPending(eco));
    }

    // ================================================================== execution

    /** Run the dispositions of an ECO being applied (after its new BOM was activated); returns the result record. */
    @SuppressWarnings("unchecked")
    Map<String, Object> execute(Map<String, Object> eco) {
        String number = str(eco, "number");
        Map<String, Object> affected = affected(eco);
        List<Affected> rows = (List<Affected>) affected.get("rows");
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>((List<Map<String, Object>>) affected.get("kept"));
        String stock = str(eco, "stockDisposition");
        switch (stock) {
            case "scrap" -> byLocation(rows).forEach((loc, lines) -> {
                Map<String, Object> adj = stockDocs.createAdjustment(new DocInput(loc, null, null, null, null, null,
                        null, "other", "Scrap of components removed by " + number, lines.stream().map(a -> new DocLine(
                                a.productId(), null, null, null, null, null, null, BigDecimal.ZERO, null)).toList()),
                        true);
                documents.add(Obj.of("kind", "stock_adjustment", "id", adj.get("id"), "code", adj.get("adjCode"),
                        "locationId", loc, "lines", lines.size()));
            });
            case "rework" -> {
                String quarantine = production.quarantineLocation();
                byLocation(rows).forEach((loc, lines) -> {
                    Map<String, Object> trf = stockDocs.createTransfer(new DocInput(null, loc, quarantine, "immediate",
                            null, null, null, null, "Hold for rework: components removed by " + number, lines.stream()
                                    .map(a -> new DocLine(a.productId(), null, null, null, null, "FEFO", null, null,
                                            a.quantity())).toList()));
                    documents.add(Obj.of("kind", "stock_transfer", "id", trf.get("id"), "code",
                            trf.get("transferCode"), "locationId", loc, "lines", lines.size()));
                });
            }
            case "return_to_supplier" -> {
                Map<String, Map<String, List<Affected>>> bySupplier = new LinkedHashMap<>();
                for (Affected a : rows) {
                    Map<String, Object> supplier = supplier(a.productId());
                    if (supplier == null) {
                        skipped.add(Obj.of("productId", a.productId(), "product", Obj.of("id", a.productId(), "code",
                                a.code(), "name", a.name()), "reason", "no supplier on file"));
                        continue;
                    }
                    bySupplier.computeIfAbsent(str(supplier, "id"), k -> new LinkedHashMap<>())
                            .computeIfAbsent(a.locationId(), k -> new ArrayList<>()).add(a);
                }
                bySupplier.forEach((partner, locations) -> locations.forEach((loc, lines) -> {
                    Map<String, Object> note = debitNotes.create(new CreateInput("inbound", partner, null,
                            "returned_goods", "Components removed by " + number, null, null, null,
                            "Return of components made obsolete by " + number, loc, lines.stream().map(a ->
                                    new LineInput(a.productId(), a.code() + " " + a.name(), a.quantity(),
                                            a.unitCost(), null, null)).toList()));
                    documents.add(Obj.of("kind", "debit_note", "id", note.get("id"), "code", note.get("noteNumber"),
                            "locationId", loc, "partnerId", partner, "lines", lines.size()));
                }));
            }
            case "use_up" -> rows.forEach(a -> skipped.add(Obj.of("productId", a.productId(), "product", Obj.of("id",
                    a.productId(), "code", a.code(), "name", a.name()), "reason", "switched over with " + a.quantity()
                            + " still on hand at " + a.locationName())));
            default -> {
                // none: stock stays where it is
            }
        }

        List<Map<String, Object>> replaced = new ArrayList<>();
        List<Map<String, Object>> flagged = new ArrayList<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> o : openOrders(eco)) {
            String action = orderAction(eco, o);
            switch (action) {
                case "replace" -> replaced.add(replace(eco, o));
                case "flag_rework" -> {
                    String note = number + ": output must be reworked to the new revision.";
                    db.update(ProductionOrder.class, str(o, "id"), Obj.of("qcRequired", true, "notes",
                            o.get("notes") == null ? note : o.get("notes") + "\n" + note));
                    flagged.add(Obj.of("id", o.get("id"), "orderCode", o.get("orderCode"), "status", o.get("status")));
                }
                default -> kept.add(Obj.of("id", o.get("id"), "orderCode", o.get("orderCode"), "status",
                        o.get("status"), "reason", action));
            }
        }
        return Obj.of("stockDisposition", stock, "documents", documents, "skipped", skipped, "wipDisposition",
                eco.get("wipDisposition"), "replacedOrders", replaced, "flaggedOrders", flagged, "keptOrders", kept);
    }

    /** One-line summary of an execution result for the activity log. */
    @SuppressWarnings("unchecked")
    static String summary(Map<String, Object> result) {
        List<String> parts = new ArrayList<>();
        List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("documents");
        if (!docs.isEmpty()) {
            parts.add("Raised " + String.join(", ", docs.stream().map(d -> str(d, "code")).toList()) + ".");
        }
        List<Map<String, Object>> replaced = (List<Map<String, Object>>) result.get("replacedOrders");
        if (!replaced.isEmpty()) {
            parts.add("Replaced " + String.join(", ", replaced.stream().map(r -> r.get("fromCode") + " → "
                    + r.get("toCode")).toList()) + ".");
        }
        List<Map<String, Object>> flagged = (List<Map<String, Object>>) result.get("flaggedOrders");
        if (!flagged.isEmpty()) {
            parts.add("Flagged for rework " + String.join(", ", flagged.stream().map(f -> str(f, "orderCode"))
                    .toList()) + ".");
        }
        return String.join(" ", parts);
    }

    // ================================================================== internals

    private Map<String, Object> replace(Map<String, Object> eco, Map<String, Object> old) {
        String number = str(eco, "number");
        String oldId = str(old, "id");
        boolean wasConfirmed = "confirmed".equals(old.get("status"));
        production.cancelOrder(oldId, new CancelOrder("Replaced under " + number));
        Object scheduled = old.get("scheduledFor");
        String notes = (old.get("notes") == null ? "" : old.get("notes") + "\n") + "Replaces " + old.get("orderCode")
                + " (" + number + ").";
        Map<String, Object> created = production.createOrder(new CreateOrder(str(eco, "newBomId"), null, null,
                str(old, "locationId"), str(old, "outputLocationId"), null, dec(old, "plannedQty"),
                scheduled == null ? null : scheduled.toString().substring(0, 10), str(old, "batchNumber"), null, null,
                notes, null, null));
        String newId = str(created, "id");
        Map<String, Object> links = new LinkedHashMap<>();
        if (old.get("requestId") != null) {
            links.put("requestId", old.get("requestId"));
        }
        if (old.get("planId") != null) {
            links.put("planId", old.get("planId"));
        }
        if (!links.isEmpty()) {
            db.update(ProductionOrder.class, newId, links);
        }
        db.jdbc().sql("UPDATE \"ProductionDemandPeg\" SET \"productionOrderId\" = ? WHERE \"organizationId\" = ?"
                + " AND \"productionOrderId\" = ?").params(newId, MfgDb.org(), oldId).update();
        db.jdbc().sql("UPDATE \"ProductionPlanLine\" SET \"generatedOrderId\" = ? WHERE \"organizationId\" = ?"
                + " AND \"generatedOrderId\" = ?").params(newId, MfgDb.org(), oldId).update();
        if (wasConfirmed) {
            production.confirmOrder(newId);
        }
        return Obj.of("fromId", oldId, "fromCode", old.get("orderCode"), "toId", newId, "toCode",
                created.get("orderCode"), "status", wasConfirmed ? "confirmed" : "draft");
    }

    /** {@code replace}, {@code flag_rework}, or why the order is kept. */
    private String orderAction(Map<String, Object> eco, Map<String, Object> o) {
        String wip = str(eco, "wipDisposition");
        if ("continue".equals(wip)) {
            return "continues on the old version";
        }
        String id = str(o, "id");
        if (NOT_STARTED.contains(str(o, "status"))) {
            if (exists("SELECT count(*) FROM \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ?",
                    id)) {
                return "multi-stage order: re-plan it by hand";
            }
            if (exists("SELECT count(*) FROM \"ProductionOrderPeg\" WHERE \"organizationId\" = ? AND (\"parentOrderId\""
                    + " = ? OR \"childOrderId\" = ?)", id, id) || o.get("parentOrderId") != null) {
                return "multi-level order: re-plan it by hand";
            }
            return "replace";
        }
        if ("rework".equals(wip) && "in_progress".equals(str(o, "status"))) {
            return "flag_rework";
        }
        return "already started: finishes on the old version";
    }

    private List<Map<String, Object>> openOrders(Map<String, Object> eco) {
        if (!"bom".equals(eco.get("type")) || eco.get("bomId") == null) {
            return List.of();
        }
        return SqlRows.list(db.jdbc().sql("SELECT id, \"orderCode\", status::text AS status, \"plannedQty\","
                + " \"locationId\", \"outputLocationId\", \"scheduledFor\", \"batchNumber\", notes, \"requestId\","
                + " \"planId\", \"parentOrderId\" FROM \"ProductionOrder\" WHERE \"organizationId\" = ? AND \"bomId\" = ?"
                + " AND status::text IN ('draft', 'confirmed', 'in_progress', 'qc_hold') ORDER BY \"createdAt\"")
                .params(MfgDb.org(), eco.get("bomId")));
    }

    /** The preferred (else most recently updated) supplier of the product, {@code {id, name}}, or null. */
    private Map<String, Object> supplier(String productId) {
        return SqlRows.one(db.jdbc().sql("SELECT p.id, p.name FROM \"SupplierProduct\" sp JOIN \"Partner\" p ON p.id ="
                + " sp.\"partnerId\" WHERE sp.\"organizationId\" = ? AND sp.\"productId\" = ? ORDER BY sp.preferred DESC,"
                + " sp.\"updatedAt\" DESC LIMIT 1").params(MfgDb.org(), productId));
    }

    private static Map<String, List<Affected>> byLocation(List<Affected> rows) {
        Map<String, List<Affected>> m = new LinkedHashMap<>();
        rows.forEach(a -> m.computeIfAbsent(a.locationId(), k -> new ArrayList<>()).add(a));
        return m;
    }

    private String quarantineId() {
        return db.jdbc().sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND code = 'QC-QUARANTINE' LIMIT 1").param(MfgDb.org()).query(String.class).optional().orElse(null);
    }

    private boolean bomsExist(Map<String, Object> eco) {
        return db.jdbc().sql("SELECT count(*) FROM \"Bom\" WHERE \"organizationId\" = ? AND id IN (?, ?) AND"
                + " \"deletedAt\" IS NULL").params(MfgDb.org(), eco.get("bomId"), eco.get("newBomId"))
                .query(Long.class).single() == 2;
    }

    private boolean exists(String sql, Object... params) {
        List<Object> all = new ArrayList<>(List.of(MfgDb.org()));
        all.addAll(List.of(params));
        return db.jdbc().sql(sql).params(all).query(Long.class).single() > 0;
    }
}
