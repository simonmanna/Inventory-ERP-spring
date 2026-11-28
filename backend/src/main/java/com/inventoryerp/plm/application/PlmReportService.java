package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLM reports (ADR-S086 §8): engineering change analytics over a period, the stock exposure of products being phased
 * out or obsolete, and the stock on hand by the revision it was built on. Money columns follow the inventory rule:
 * they are only shown to users who may read inventory valuation.
 */
@Service
public class PlmReportService {

    static final List<String> GROUPS = List.of("month", "type", "stage", "owner", "priority");

    /** Purchase orders still to receive (with lines short of their quantity). */
    private static final String OPEN_PO = " NOT IN ('received', 'closed', 'cancelled')";

    private final PlmDb db;
    private final PlmSupport plm;
    private final InventoryValuationService valuation;

    public PlmReportService(PlmDb db, PlmSupport plm, InventoryValuationService valuation) {
        this.db = db;
        this.plm = plm;
        this.valuation = valuation;
    }

    static boolean seesCost() {
        return TenantContext.has(Permissions.INVENTORY_VALUATION__READ);
    }

    // ---- engineering change analytics ------------------------------------------------------------------------------

    /**
     * Changes created in {@code [from, to]} (org calendar days; default the last 90 days) with their outcome, the
     * throughput and cycle times of the changes applied in the period, first-pass rate, rebases, first-article results
     * and the revaluation the applied changes posted; grouped by month, type, stage, owner or priority.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> changes(String fromText, String toText, String changeTypeId, String groupBy) {
        LocalDate to = PlmSupport.date(toText);
        to = to == null ? plm.orgToday() : to;
        LocalDate from = PlmSupport.date(fromText);
        from = from == null ? to.minusDays(89) : from;
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must not be after to.");
        }
        String group = groupBy == null || groupBy.isBlank() ? "month" : groupBy;
        if (!GROUPS.contains(group)) {
            throw ApiException.badRequest("groupBy must be one of " + String.join(", ", GROUPS) + ".");
        }
        String type = PlmSupport.blank(changeTypeId);
        ZoneId zone = plm.orgZone();
        Map<String, Object> p = new HashMap<>();
        p.put("org", PlmDb.org());
        p.put("start", Timestamp.from(from.atStartOfDay(zone).toInstant()));
        p.put("end", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        p.put("zone", zone.getId());
        String typeFilter = "";
        if (type != null) {
            p.put("type", type);
            typeFilter = " AND e.\"changeTypeId\" = :type";
        }
        String created = " e.\"organizationId\" = :org AND e.\"createdAt\" >= :start AND e.\"createdAt\" < :end"
                + typeFilter;
        String applied = " e.\"organizationId\" = :org AND e.status = 'applied' AND e.\"appliedAt\" >= :start AND"
                + " e.\"appliedAt\" < :end" + typeFilter;

        Map<String, Object> createdTotals = SqlRows.one(db.jdbc().sql("SELECT count(*) AS created, count(*) FILTER"
                + " (WHERE e.status IN ('draft', 'approved')) AS open, count(*) FILTER (WHERE e.status = 'applied') AS"
                + " applied, count(*) FILTER (WHERE e.status = 'rejected') AS rejected, count(*) FILTER (WHERE"
                + " e.\"kanbanState\" = 'blocked' AND e.status IN ('draft', 'approved')) AS blocked FROM"
                + " \"EngineeringChange\" e WHERE" + created).params(p));
        Map<String, Object> throughput = SqlRows.one(db.jdbc().sql("SELECT count(*) AS applied,"
                + " round(avg(extract(epoch FROM (e.\"appliedAt\" - e.\"createdAt\")) / 86400)::numeric, 1) AS"
                + " \"avgCycleDays\", round((percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM"
                + " (e.\"appliedAt\" - e.\"createdAt\")) / 86400))::numeric, 1) AS \"p90CycleDays\","
                + " round((avg(extract(epoch FROM (e.\"approvedAt\" - e.\"createdAt\")) / 86400) FILTER (WHERE"
                + " e.\"approvedAt\" IS NOT NULL))::numeric, 1) AS \"avgApprovalDays\", count(*) FILTER (WHERE NOT EXISTS"
                + " (SELECT 1 FROM \"PlmChangeApproval\" a WHERE a.\"changeId\" = e.id AND a.decision = 'rejected') AND"
                + " NOT EXISTS (SELECT 1 FROM \"PlmChangeActivity\" x WHERE x.\"changeId\" = e.id AND x.kind ="
                + " 'rebased')) AS \"firstPass\", count(*) FILTER (WHERE e.\"productChangesApplied\" IS NOT NULL) AS"
                + " \"productDataChanges\", count(*) FILTER (WHERE e.\"standardCostId\" IS NOT NULL) AS"
                + " \"standardsCreated\", count(*) FILTER (WHERE e.\"revaluationJournalId\" IS NOT NULL) AS"
                + " revaluations, coalesce(sum((e.\"costResult\" ->> 'revaluationAmount')::numeric), 0) AS"
                + " \"revaluationAmount\" FROM \"EngineeringChange\" e WHERE" + applied).params(p));
        long appliedCount = ((Number) throughput.get("applied")).longValue();
        long firstPass = ((Number) throughput.get("firstPass")).longValue();
        throughput.put("firstPassRate", appliedCount == 0 ? null : BigDecimal.valueOf(firstPass * 100)
                .divide(BigDecimal.valueOf(appliedCount), 1, RoundingMode.HALF_UP));
        if (!seesCost()) {
            throughput.put("revaluationAmount", null);
        }
        long rebases = db.jdbc().sql("SELECT count(*) FROM \"PlmChangeActivity\" x JOIN \"EngineeringChange\" e ON"
                + " e.id = x.\"changeId\" WHERE x.kind = 'rebased' AND x.\"createdAt\" >= :start AND x.\"createdAt\" <"
                + " :end AND e.\"organizationId\" = :org" + typeFilter).params(p).query(Long.class).single();
        throughput.put("rebases", rebases);

        Map<String, Object> fai = new LinkedHashMap<>();
        for (String s : List.of("pending", "open", "no_plan", "passed", "failed", "cancelled")) {
            fai.put(s, 0L);
        }
        db.jdbc().sql("SELECT e.\"faiStatus\", count(*) FROM \"EngineeringChange\" e WHERE" + applied
                + " AND e.\"faiStatus\" IS NOT NULL GROUP BY e.\"faiStatus\"").params(p)
                .query((rs, i) -> fai.put(rs.getString(1), rs.getLong(2))).list();

        Map<String, Object> byType = new LinkedHashMap<>();
        db.jdbc().sql("SELECT e.type, count(*) FROM \"EngineeringChange\" e WHERE" + created + " GROUP BY e.type"
                + " ORDER BY e.type").params(p).query((rs, i) -> byType.put(rs.getString(1), rs.getLong(2))).list();

        String key;
        String label;
        String join = "";
        switch (group) {
            case "type" -> {
                key = "e.\"changeTypeId\"";
                label = "t.name";
                join = " LEFT JOIN \"PlmChangeType\" t ON t.id = e.\"changeTypeId\"";
            }
            case "stage" -> {
                key = "e.\"stageId\"";
                label = "s.name";
                join = " LEFT JOIN \"PlmChangeStage\" s ON s.id = e.\"stageId\"";
            }
            case "owner" -> {
                key = "e.\"ownerId\"";
                label = "coalesce(nullif(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email)";
                join = " LEFT JOIN \"User\" u ON u.id = e.\"ownerId\"";
            }
            case "priority" -> {
                key = "e.priority::text";
                label = "e.priority::text";
            }
            default -> {
                key = "to_char((e.\"createdAt\" AT TIME ZONE 'UTC') AT TIME ZONE :zone, 'YYYY-MM')";
                label = key;
            }
        }
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT " + key + " AS key, max(" + label
                + ") AS label, count(*) AS created, count(*) FILTER (WHERE e.status IN ('draft', 'approved')) AS open,"
                + " count(*) FILTER (WHERE e.status = 'applied') AS applied, count(*) FILTER (WHERE e.status ="
                + " 'rejected') AS rejected, round((avg(extract(epoch FROM (e.\"appliedAt\" - e.\"createdAt\")) / 86400)"
                + " FILTER (WHERE e.status = 'applied'))::numeric, 1) AS \"avgCycleDays\" FROM \"EngineeringChange\" e"
                + join + " WHERE" + created + " GROUP BY 1 ORDER BY 1 NULLS LAST").params(p));

        return Obj.of("from", from.toString(), "to", to.toString(), "groupBy", group, "changeTypeId", type,
                "created", createdTotals, "applied", throughput, "firstArticle", fai, "byType", byType, "rows", rows,
                "costVisible", seesCost());
    }

    // ---- phase-out / obsolete stock --------------------------------------------------------------------------------

    /**
     * Products being phased out or obsolete (or one of them, {@code state}): stock on hand and its value, open purchase
     * orders still to receive (buying what is being retired), open sales demand that can run the stock down, the active
     * BOMs that still use the product as a component, and the replacement.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> obsoleteStock(String state) {
        List<String> states = state == null || state.isBlank() ? List.of("phase_out", "obsolete") : List.of(state);
        if (!List.of("phase_out", "obsolete").containsAll(states)) {
            throw ApiException.badRequest("state must be phase_out or obsolete.");
        }
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT i.id AS \"itemId\", i.\"productId\","
                + " i.\"lifecycleState\", i.revision, i.\"replacementProductId\", p.code, p.name, r.code AS"
                + " \"replacementCode\", r.name AS \"replacementName\", (SELECT max(ev.\"createdAt\") FROM"
                + " \"PlmLifecycleEvent\" ev WHERE ev.\"itemId\" = i.id AND ev.\"toState\" = i.\"lifecycleState\") AS"
                + " since, coalesce((SELECT sum(s.quantity) FROM \"StockItem\" s WHERE s.\"organizationId\" ="
                + " i.\"organizationId\" AND s.\"productId\" = i.\"productId\"), 0) AS \"onHand\", coalesce((SELECT"
                + " sum((l.quantity - l.\"receivedQuantity\") * coalesce(l.\"uomFactor\", 1)) FROM \"PurchaseOrderLine\" l"
                + " JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" WHERE l.\"organizationId\" ="
                + " i.\"organizationId\" AND l.\"productId\" = i.\"productId\" AND o.status::text" + OPEN_PO
                + " AND l.quantity > l.\"receivedQuantity\"), 0) AS \"openPurchaseQty\", (SELECT count(DISTINCT o.id)"
                + " FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" WHERE"
                + " l.\"organizationId\" = i.\"organizationId\" AND l.\"productId\" = i.\"productId\" AND o.status::text"
                + OPEN_PO + " AND l.quantity > l.\"receivedQuantity\") AS \"openPurchaseOrders\","
                + " coalesce((SELECT sum((l.quantity - l.\"qtyCancelled\" - l.\"qtyDelivered\") * l.\"uomFactor\") FROM"
                + " \"SalesOrderLine\" l JOIN \"SalesOrder\" o ON o.id = l.\"orderId\" WHERE l.\"organizationId\" ="
                + " i.\"organizationId\" AND l.\"productId\" = i.\"productId\" AND o.status IN ('confirmed'"
                + ") AND l.quantity - l.\"qtyCancelled\" - l.\"qtyDelivered\" > 0), 0) AS"
                + " \"openSalesQty\", (SELECT count(DISTINCT b.id) FROM \"BomLine\" bl JOIN \"Bom\" b ON b.id ="
                + " bl.\"bomId\" WHERE bl.\"organizationId\" = i.\"organizationId\" AND bl.\"componentProductId\" ="
                + " i.\"productId\" AND b.status::text = 'active') AS \"usedInActiveBoms\" FROM \"PlmItem\" i JOIN"
                + " \"Product\" p ON p.id = i.\"productId\" LEFT JOIN \"Product\" r ON r.id = i.\"replacementProductId\""
                + " WHERE i.\"organizationId\" = ? AND i.\"lifecycleState\" = ANY(?::text[]) ORDER BY"
                + " i.\"lifecycleState\" DESC, p.code").params(PlmDb.org(), states.toArray(String[]::new)));

        boolean cost = seesCost();
        Map<String, BigDecimal> values = new HashMap<>();
        if (cost && !rows.isEmpty()) {
            for (Map<String, Object> v : valuation.currentValueByQuant(null, rows.stream()
                    .map(r -> str(r, "productId")).toList())) {
                values.merge(str(v, "product_id"), SqlRows.dec(v, "value"), BigDecimal::add);
            }
        }
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        long withStock = 0;
        long buying = 0;
        long inBoms = 0;
        Map<String, Object> byState = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            BigDecimal qty = SqlRows.dec(r, "onHand");
            BigDecimal value = cost ? values.getOrDefault(str(r, "productId"), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP) : null;
            r.put("value", value);
            totalQty = totalQty.add(qty);
            if (value != null) {
                totalValue = totalValue.add(value);
            }
            withStock += qty.signum() > 0 ? 1 : 0;
            buying += SqlRows.dec(r, "openPurchaseQty").signum() > 0 ? 1 : 0;
            inBoms += ((Number) r.get("usedInActiveBoms")).longValue() > 0 ? 1 : 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) byState.computeIfAbsent(str(r, "lifecycleState"),
                    k -> Obj.of("products", 0L, "onHand", BigDecimal.ZERO, "value", cost ? BigDecimal.ZERO : null));
            s.put("products", (Long) s.get("products") + 1);
            s.put("onHand", ((BigDecimal) s.get("onHand")).add(qty));
            if (value != null) {
                s.put("value", ((BigDecimal) s.get("value")).add(value));
            }
        }
        return Obj.of("rows", rows, "byState", byState, "totals", Obj.of("products", rows.size(), "withStock",
                withStock, "onHand", totalQty, "value", cost ? totalValue : null, "stillPurchased", buying,
                "usedInActiveBoms", inBoms), "costVisible", cost);
    }

    // ---- stock by revision -----------------------------------------------------------------------------------------

    /**
     * The stock on hand of PLM products (or one, {@code productId}) by the revision it was built on: batches whose
     * production order was recorded as-built are exact; the rest of the stock is attributed to the most recent
     * as-built orders first (an estimate: stock without batches cannot be told apart); what is left is of unknown
     * revision (bought, or made before PLM tracked it).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stockByRevision(String productId) {
        String only = PlmSupport.blank(productId);
        List<Map<String, Object>> items = SqlRows.list(db.jdbc().sql("SELECT i.id AS \"itemId\", i.\"productId\","
                + " i.revision AS \"currentRevision\", i.\"lifecycleState\", p.code, p.name, coalesce((SELECT"
                + " sum(s.quantity) FROM \"StockItem\" s WHERE s.\"organizationId\" = i.\"organizationId\" AND"
                + " s.\"productId\" = i.\"productId\"), 0) AS \"onHand\" FROM \"PlmItem\" i JOIN \"Product\" p ON p.id ="
                + " i.\"productId\" WHERE i.\"organizationId\" = ?" + (only == null ? " AND EXISTS (SELECT 1 FROM"
                        + " \"PlmAsBuilt\" ab WHERE ab.\"organizationId\" = i.\"organizationId\" AND ab.\"productId\" ="
                        + " i.\"productId\")" : " AND i.\"productId\" = ?") + " ORDER BY p.code")
                .params(only == null ? new Object[] {PlmDb.org()} : new Object[] {PlmDb.org(), only}));
        if (only != null && items.isEmpty()) {
            throw ApiException.notFound("PLM item not found");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            out.add(byRevision(item));
        }
        return Obj.of("rows", out);
    }

    private Map<String, Object> byRevision(Map<String, Object> item) {
        String productId = str(item, "productId");
        BigDecimal onHand = SqlRows.dec(item, "onHand").max(BigDecimal.ZERO);
        List<Map<String, Object>> built = SqlRows.list(db.jdbc().sql("SELECT ab.\"orderId\", ab.\"orderCode\","
                + " ab.\"batchNumber\", ab.quantity, ab.revision, ab.\"revisionId\", ab.\"completedAt\", r.status AS"
                + " \"revisionStatus\" FROM \"PlmAsBuilt\" ab LEFT JOIN \"PlmRevision\" r ON r.id = ab.\"revisionId\""
                + " WHERE ab.\"organizationId\" = ? AND ab.\"productId\" = ? ORDER BY ab.\"completedAt\" DESC NULLS LAST,"
                + " ab.id").params(PlmDb.org(), productId));
        Map<String, BigDecimal> batches = new LinkedHashMap<>();
        db.jdbc().sql("SELECT \"batchNumber\", sum(quantity) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND quantity > 0 GROUP BY \"batchNumber\" ORDER BY \"batchNumber\"")
                .params(PlmDb.org(), productId).query((rs, i) -> batches.put(rs.getString(1), rs.getBigDecimal(2)))
                .list();

        Map<String, Map<String, Object>> revisions = new LinkedHashMap<>();
        List<Map<String, Object>> batchRows = new ArrayList<>();
        BigDecimal exact = BigDecimal.ZERO;
        BigDecimal unmatched = BigDecimal.ZERO; // batches no as-built order made (bought, or made before PLM)
        for (Map.Entry<String, BigDecimal> b : batches.entrySet()) {
            Map<String, Object> source = built.stream().filter(x -> b.getKey().equals(x.get("batchNumber")))
                    .findFirst().orElse(null);
            BigDecimal qty = b.getValue().min(onHand.subtract(exact).subtract(unmatched)).max(BigDecimal.ZERO);
            batchRows.add(Obj.of("batchNumber", b.getKey(), "quantity", b.getValue(), "revision", source == null ? null
                    : source.get("revision"), "orderCode", source == null ? null : source.get("orderCode")));
            if (source == null) {
                unmatched = unmatched.add(qty);
            } else if (qty.signum() > 0) {
                revision(revisions, source).merge("exact", qty, (a, c) -> ((BigDecimal) a).add((BigDecimal) c));
                exact = exact.add(qty);
            }
        }
        BigDecimal remaining = onHand.subtract(exact).subtract(unmatched);
        BigDecimal estimated = BigDecimal.ZERO;
        for (Map<String, Object> b : built) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (b.get("batchNumber") != null && !batches.isEmpty()) {
                continue; // batch-tracked: its batch was counted above, or is gone (sold or consumed)
            }
            BigDecimal qty = SqlRows.dec(b, "quantity").min(remaining);
            if (qty.signum() <= 0) {
                continue;
            }
            revision(revisions, b).merge("estimated", qty, (a, c) -> ((BigDecimal) a).add((BigDecimal) c));
            estimated = estimated.add(qty);
            remaining = remaining.subtract(qty);
        }
        List<Map<String, Object>> revs = new ArrayList<>(revisions.values());
        revs.forEach(r -> r.put("quantity", ((BigDecimal) r.get("exact")).add((BigDecimal) r.get("estimated"))));
        return Obj.of("itemId", item.get("itemId"), "productId", productId, "code", item.get("code"), "name",
                item.get("name"), "lifecycleState", item.get("lifecycleState"), "currentRevision",
                item.get("currentRevision"), "onHand", onHand, "revisions", revs, "exact", exact, "estimated", estimated,
                "unknown", onHand.subtract(exact).subtract(estimated).max(BigDecimal.ZERO), "batches", batchRows,
                "ordersBuilt", built.size());
    }

    private static Map<String, Object> revision(Map<String, Map<String, Object>> revisions, Map<String, Object> b) {
        String rev = b.get("revision") == null ? "—" : str(b, "revision");
        return revisions.computeIfAbsent(rev, k -> Obj.of("revision", b.get("revision"), "revisionId",
                b.get("revisionId"), "status", b.get("revisionStatus"), "exact", BigDecimal.ZERO, "estimated",
                BigDecimal.ZERO));
    }
}
