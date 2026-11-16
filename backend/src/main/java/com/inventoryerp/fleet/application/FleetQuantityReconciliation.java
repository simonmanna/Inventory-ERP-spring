package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.LoadLineQuantities.Buckets;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase A A3 (plan §10): operational reconciliation of delivery lines, whatever their source. It recomputes the
 * projections only — a drifted {@code billableQty} is repaired (it is a projection of the buckets) — and records every
 * broken rule as a {@code FleetReconciliationFinding} (scope {@code operational}). Tickets, POD rows, stock moves,
 * journal entries and invoices are never changed; problems in them are flagged for people to act on. A finding stays
 * open while runs still detect it and is resolved by the first run that does not.
 *
 * <p>Rules per delivered line: the invariant; no negative bucket; weight claims within the ceiling; every POD shortage,
 * damaged quantity and excess covered by a claim (or a legacy model-1 claim); stock issued for the dispatched quantity
 * and received back for the returned quantity; recorded history totals equal to the current facts.
 */
@Service
public class FleetQuantityReconciliation {

    static final String SCOPE = "operational";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FleetDb db;
    private final LoadLineQuantityService quantities;

    public FleetQuantityReconciliation(FleetDb db, LoadLineQuantityService quantities) {
        this.db = db;
        this.quantities = quantities;
    }

    /** One finding of a run. */
    public record Finding(String rule, String fingerprint, Map<String, Object> line, BigDecimal expected,
            BigDecimal actual, String message) {}

    /** Run the operational checks for the current organization; returns the run summary. */
    @Transactional
    public Map<String, Object> run() {
        String runId = UUID.randomUUID().toString();
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("""
                SELECT ll.id, ll."loadId", ll."deliveryOrderLineId", ll."productId", ll."dispatchedQty", ll."deliveredQty",
                       ll."returnedQty", ll."damagedQty", ll."billableQty", ll."stockLedgerCode", ll."returnLedgerCode",
                       t.code AS "tripCode", t."deliveryOrderId", t.status AS "tripStatus", o."stockMode",
                       COALESCE(p."trackInventory", false) AS tracked
                  FROM "FleetLoadLine" ll
                  JOIN "FleetLoad" t ON t.id = ll."loadId"
                  JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
                  LEFT JOIN "Product" p ON p.id = ll."productId"
                 WHERE ll."organizationId" = ? AND t.status IN ('delivered', 'closed', 'returned')
                 ORDER BY ll.id""").params(FleetDb.org()));
        List<Finding> found = new ArrayList<>();
        int repaired = 0;
        for (int i = 0; i < lines.size(); i += 500) {
            List<Map<String, Object>> chunk = lines.subList(i, Math.min(lines.size(), i + 500));
            List<String> ids = chunk.stream().map(l -> (String) l.get("id")).toList();
            Map<String, Buckets> buckets = quantities.forLines(ids);
            Map<String, List<Map<String, Object>>> claims = claims(ids);
            Map<String, Map<String, BigDecimal>> totals = historyTotals(ids);
            for (Map<String, Object> l : chunk) {
                String id = (String) l.get("id");
                Buckets b = buckets.get(id);
                if (b == null) {
                    continue;
                }
                check(l, b, claims.getOrDefault(id, List.of()), totals.get(id), found);
                if (!b.legacy() && b.delivered() && (l.get("billableQty") == null
                        || SqlRows.dec(l, "billableQty").compareTo(b.accepted()) != 0)) {
                    found.add(finding("billable_drift_repaired", l, b.accepted(), l.get("billableQty") == null ? null
                            : SqlRows.dec(l, "billableQty"), "Billable quantity drifted from the accepted bucket and"
                                    + " was recomputed"));
                    quantities.refresh(id);
                    repaired++;
                }
            }
        }
        int open = persist(runId, found);
        return Obj.of("runId", runId, "scope", SCOPE, "linesChecked", lines.size(), "findings", found.size(),
                "openFindings", open, "repaired", repaired);
    }

    private void check(Map<String, Object> l, Buckets b, List<Map<String, Object>> claims,
            Map<String, BigDecimal> totals, List<Finding> out) {
        for (String f : b.findings()) {
            out.add(finding(f, l, null, null, switch (f) {
                case "weight_claims_exceed_eligible" -> "Weight claims exceed the eligible quantity";
                case "confirmed_shortage_exceeds_pod_shortage" -> "Confirmed shortage exceeds the POD shortage";
                case "returned_damaged_exceed_dispatched" -> "Returned and damaged exceed the dispatched quantity";
                default -> "Quantity invariant broken: dispatched ≠ accepted + returned + damaged + confirmed + pending";
            }));
        }
        for (BigDecimal v : List.of(b.accepted(), b.pendingDisputed(), b.confirmedShortage(), b.returned(),
                b.damaged(), b.excess())) {
            if (v.signum() < 0) {
                out.add(finding("negative_bucket", l, BigDecimal.ZERO, v, "A quantity bucket is negative"));
                break;
            }
        }
        boolean legacy = claims.stream().anyMatch(c -> ((Number) c.get("resolutionModel")).intValue() == 1);
        if (b.podShortage().signum() > 0 && !legacy && claims.stream().noneMatch(c -> "shortage".equals(c.get("kind"))
                && c.get("withdrawnAt") == null)) {
            out.add(finding("pod_shortage_without_claim", l, b.podShortage(), BigDecimal.ZERO,
                    "POD shortage without a shortage claim"));
        }
        if (b.damaged().signum() > 0 && !legacy && claims.stream().noneMatch(c -> "damaged".equals(c.get("kind")))) {
            out.add(finding("damaged_without_claim", l, b.damaged(), BigDecimal.ZERO,
                    "Damaged quantity without a damaged claim"));
        }
        if (b.excess().signum() > 0 && claims.stream().noneMatch(c -> "excess".equals(c.get("kind")))) {
            out.add(finding("excess_without_claim", l, b.excess(), BigDecimal.ZERO, "Excess without an excess claim"));
        }
        boolean stock = "dispatch".equals(l.get("stockMode")) && Boolean.TRUE.equals(l.get("tracked"));
        if (stock && b.dispatched().signum() > 0 && l.get("stockLedgerCode") == null) {
            out.add(finding("issue_missing", l, b.dispatched(), BigDecimal.ZERO,
                    "Dispatched quantity has no stock issue"));
        }
        if (stock && b.returned().signum() > 0 && l.get("returnLedgerCode") == null
                && !"returned".equals(l.get("tripStatus"))) {
            out.add(finding("return_receipt_missing", l, b.returned(), BigDecimal.ZERO,
                    "Returned quantity has no stock receipt"));
        }
        if (totals != null) {
            compare(l, "delivered", b.deliveredQty(), totals, out);
            compare(l, "returned", b.returned(), totals, out);
            compare(l, "damaged", b.damaged(), totals, out);
        }
    }

    private void compare(Map<String, Object> l, String bucket, BigDecimal current, Map<String, BigDecimal> totals,
            List<Finding> out) {
        BigDecimal h = totals.getOrDefault(bucket, BigDecimal.ZERO);
        BigDecimal c = current == null ? BigDecimal.ZERO : current;
        if (h.compareTo(c) != 0) {
            out.add(new Finding("history_mismatch", SCOPE + "|history_mismatch|" + bucket + "|" + l.get("id"), l, c, h,
                    "History total of " + bucket + " (" + JsNumbers.dec(h) + ") differs from the line ("
                            + JsNumbers.dec(c) + ")"));
        }
    }

    private static Finding finding(String rule, Map<String, Object> l, BigDecimal expected, BigDecimal actual,
            String message) {
        return new Finding(rule, SCOPE + "|" + rule + "|" + l.get("id"), l, expected, actual, message);
    }

    private Map<String, List<Map<String, Object>>> claims(List<String> ids) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT \"loadLineId\", kind, status,"
                + " \"resolutionModel\", \"withdrawnAt\" FROM \"FleetDeliveryDiscrepancy\" WHERE \"organizationId\" = ?"
                + " AND \"loadLineId\" = ANY(?::text[])").params(FleetDb.org(), ids.toArray(String[]::new)))) {
            out.computeIfAbsent((String) c.get("loadLineId"), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    /** Per line with recorded history: Σ delta of the delivered / returned / damaged facts. */
    private Map<String, Map<String, BigDecimal>> historyTotals(List<String> ids) {
        Map<String, Map<String, BigDecimal>> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"loadLineId\", bucket, COALESCE(sum(\"deltaQty\")"
                + " FILTER (WHERE kind IN ('pod_recorded', 'pod_corrected', 'returned', 'damaged_recorded')), 0) AS total"
                + " FROM \"FleetLoadLineQuantityEvent\" WHERE \"organizationId\" = ? AND \"loadLineId\" = ANY(?::text[])"
                + " GROUP BY \"loadLineId\", bucket").params(FleetDb.org(), ids.toArray(String[]::new)))) {
            Map<String, BigDecimal> m = out.computeIfAbsent((String) r.get("loadLineId"), k -> new HashMap<>());
            if (r.get("bucket") != null) {
                m.put((String) r.get("bucket"), SqlRows.dec(r, "total"));
            }
        }
        // A line whose history only has dispatch rows (POD before A3) is not compared.
        out.entrySet().removeIf(e -> !e.getValue().containsKey("delivered"));
        return out;
    }

    /** Insert new findings, refresh the ones still open, resolve the ones this run no longer sees. */
    private int persist(String runId, List<Finding> found) {
        Set<String> seen = new HashSet<>();
        for (Finding f : found) {
            if (!seen.add(f.fingerprint())) {
                continue;
            }
            int touched = db.jdbc().sql("UPDATE \"FleetReconciliationFinding\" SET \"lastSeenAt\" = " + FleetDb.NOW
                    + ", \"runId\" = ?, actual = ?, expected = ? WHERE \"organizationId\" = ? AND fingerprint = ? AND"
                    + " status = 'open'").params(runId, f.actual(), f.expected(), FleetDb.org(), f.fingerprint()).update();
            if (touched == 0) {
                Map<String, Object> l = f.line();
                db.jdbc().sql("INSERT INTO \"FleetReconciliationFinding\" (id, \"organizationId\", \"runId\", scope, rule,"
                        + " fingerprint, \"deliveryCode\", \"loadId\", \"loadLineId\", \"deliveryOrderId\","
                        + " \"deliveryOrderLineId\", expected, actual, message, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?::jsonb)").params(UUID.randomUUID().toString(), FleetDb.org(), runId,
                                SCOPE, f.rule(), f.fingerprint(), l.get("tripCode"), l.get("loadId"), l.get("id"),
                                l.get("deliveryOrderId"), l.get("deliveryOrderLineId"), f.expected(), f.actual(),
                                f.message(), JSON.writeValueAsString(Obj.of("productId", l.get("productId"))))
                        .update();
            }
        }
        db.jdbc().sql("UPDATE \"FleetReconciliationFinding\" SET status = 'resolved', \"resolvedAt\" = " + FleetDb.NOW
                + ", resolution = 'Not detected by run ' || ? WHERE \"organizationId\" = ? AND scope = ? AND status ="
                + " 'open' AND \"runId\" <> ?").params(runId, FleetDb.org(), SCOPE, runId).update();
        return seen.size();
    }

    /** Findings (newest first), filtered by scope and status. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> findings(String scope, String status, Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 50 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (scope != null && !scope.isBlank()) {
            where.append(" AND scope = ?");
            params.add(scope);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetReconciliationFinding\"" + where).params(params)
                .query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql("SELECT * FROM \"FleetReconciliationFinding\"" + where
                + " ORDER BY \"detectedAt\" DESC, id LIMIT ? OFFSET ?").params(pp)), p, size, total);
    }
}
