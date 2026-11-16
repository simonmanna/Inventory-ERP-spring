package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.LoadLineQuantities.Buckets;
import com.inventoryerp.fleet.application.LoadLineQuantities.Claim;
import com.inventoryerp.fleet.application.LoadLineQuantities.Facts;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase A A3 (plan §3): reads a delivery line's facts and claims, computes its buckets ({@link LoadLineQuantities})
 * and keeps the line's {@code billableQty} equal to the accepted bucket once the trip is delivered. Lines carrying a
 * model-1 claim (decided before A3) keep their pre-A3 billable quantity. The public read methods are the Fleet API the
 * Sales delivery views use.
 */
@Component
public class LoadLineQuantityService {

    /** Trip statuses after the proof of delivery (Δ is recorded). */
    static final List<String> DELIVERED = List.of("delivered", "closed", "returned");

    private static final String LINES = """
            SELECT ll.id, ll."loadId", ll."dispatchedQty", ll."deliveredQty", ll."returnedQty", ll."damagedQty",
                   ll."billableQty", ll."uomId", t.status AS "tripStatus"
              FROM "FleetLoadLine" ll JOIN "FleetLoad" t ON t.id = ll."loadId"
             WHERE ll."organizationId" = ?""";

    private final FleetDb db;

    public LoadLineQuantityService(FleetDb db) {
        this.db = db;
    }

    /** Buckets of the given load lines (missing ids are absent from the map). */
    @Transactional(readOnly = true)
    public Map<String, Buckets> forLines(Collection<String> lineIds) {
        if (lineIds == null || lineIds.isEmpty()) {
            return Map.of();
        }
        return compute(SqlRows.list(db.jdbc().sql(LINES + " AND ll.id = ANY(?::text[])").params(FleetDb.org(),
                lineIds.toArray(String[]::new))));
    }

    /** Buckets of every line of the given trips. */
    @Transactional(readOnly = true)
    public Map<String, Buckets> forLoads(Collection<String> loadIds) {
        if (loadIds == null || loadIds.isEmpty()) {
            return Map.of();
        }
        return compute(SqlRows.list(db.jdbc().sql(LINES + " AND ll.\"loadId\" = ANY(?::text[])").params(FleetDb.org(),
                loadIds.toArray(String[]::new))));
    }

    /** Buckets of one line, or null. */
    @Transactional(readOnly = true)
    public Buckets of(String lineId) {
        return forLines(List.of(lineId)).get(lineId);
    }

    /**
     * Recompute one line and write {@code billableQty} = accepted when the trip is delivered and the line follows the
     * A3 model. Called in the transaction of every change of the line's facts or claims.
     */
    Buckets refresh(String lineId) {
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(LINES + " AND ll.id = ?").params(FleetDb.org(),
                lineId));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        Buckets b = compute(rows).get(lineId);
        if (b.delivered() && !b.legacy()) {
            BigDecimal billable = row.get("billableQty") == null ? null : SqlRows.dec(row, "billableQty");
            if (billable == null || billable.compareTo(b.accepted()) != 0) {
                db.jdbc().sql("UPDATE \"FleetLoadLine\" SET \"billableQty\" = ? WHERE id = ? AND \"organizationId\" = ?")
                        .params(b.accepted(), lineId, FleetDb.org()).update();
            }
        }
        return b;
    }

    /** {@link #refresh} for every line of a trip. */
    void refreshLoad(String loadId) {
        for (String id : db.jdbc().sql("SELECT id FROM \"FleetLoadLine\" WHERE \"loadId\" = ? AND \"organizationId\" = ?")
                .params(loadId, FleetDb.org()).query(String.class).list()) {
            refresh(id);
        }
    }

    /** JSON view of the buckets (numbers as JS numbers). */
    public static Map<String, Object> view(Buckets b) {
        if (b == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivered", b.delivered());
        m.put("acceptedQty", JsNumbers.dec(b.accepted()));
        m.put("returnedQty", JsNumbers.dec(b.returned()));
        m.put("damagedQty", JsNumbers.dec(b.damaged()));
        m.put("pendingDisputedQty", JsNumbers.dec(b.pendingDisputed()));
        m.put("confirmedShortageQty", JsNumbers.dec(b.confirmedShortage()));
        m.put("excessQty", JsNumbers.dec(b.excess()));
        m.put("shortageQty", JsNumbers.dec(b.shortage()));
        m.put("weightPendingQty", JsNumbers.dec(b.weightPending()));
        m.put("weightConfirmedQty", JsNumbers.dec(b.weightConfirmed()));
        m.put("podShortageQty", JsNumbers.dec(b.podShortage()));
        m.put("inTransitQty", JsNumbers.dec(b.inTransit()));
        m.put("legacy", b.legacy());
        m.put("findings", b.findings());
        return m;
    }

    private Map<String, Buckets> compute(List<Map<String, Object>> lines) {
        Map<String, Buckets> out = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return out;
        }
        Map<String, List<Claim>> claims = new HashMap<>();
        String[] ids = lines.stream().map(l -> (String) l.get("id")).toArray(String[]::new);
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT id, \"loadLineId\", kind, status,"
                + " \"differenceQty\", \"reconciledQty\", \"resolutionModel\", \"withdrawnAt\" IS NOT NULL AS withdrawn"
                + " FROM \"FleetDeliveryDiscrepancy\" WHERE \"organizationId\" = ? AND \"loadLineId\" = ANY(?::text[])"
                + " ORDER BY \"createdAt\", id").params(FleetDb.org(), ids))) {
            claims.computeIfAbsent((String) c.get("loadLineId"), k -> new ArrayList<>()).add(claim(c));
        }
        for (Map<String, Object> l : lines) {
            boolean delivered = DELIVERED.contains(l.get("tripStatus"));
            out.put((String) l.get("id"), LoadLineQuantities.compute(new Facts(dec(l, "dispatchedQty"),
                    delivered ? dec(l, "deliveredQty") : null, dec(l, "returnedQty"), dec(l, "damagedQty"),
                    claims.getOrDefault((String) l.get("id"), List.of()))));
        }
        return out;
    }

    static Claim claim(Map<String, Object> c) {
        Object model = c.get("resolutionModel");
        return new Claim((String) c.get("id"), (String) c.get("kind"), (String) c.get("status"),
                dec(c, "differenceQty"), dec(c, "reconciledQty"), model == null ? 1 : ((Number) model).intValue(),
                Boolean.TRUE.equals(c.get("withdrawn")) || c.get("withdrawnAt") != null);
    }

    /** Adds {@code claimDecision} and {@code openQty} to a discrepancy row (API: never a bare "rejected"). */
    static Map<String, Object> decorate(Map<String, Object> x) {
        if (x == null) {
            return null;
        }
        Claim c = claim(x);
        x.put("claimDecision", c.decision());
        x.put("openQty", JsNumbers.dec(c.open()));
        return x;
    }

    static BigDecimal dec(Map<String, Object> row, String key) {
        return row.get(key) == null ? BigDecimal.ZERO : SqlRows.dec(row, key);
    }

    /** {@code {lineId: view}} for the given lines (Sales delivery views). */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> views(Collection<String> lineIds) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        forLines(lineIds).forEach((k, v) -> out.put(k, view(v)));
        return out;
    }
}
