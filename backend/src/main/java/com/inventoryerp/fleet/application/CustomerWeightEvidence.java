package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DispatchCommands.CustomerWeightInput;
import com.inventoryerp.fleet.application.DispatchCommands.WeightShareInput;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Check;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Tolerance;
import com.inventoryerp.fleet.domain.FleetProofOfDelivery;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase A A2 (plan §7): customer weight evidence on a delivered trip. Compares the customer's net weight C with the
 * quarry weight Q (the feeding weighbridge ticket's net, else the manual gross − tare), freezes the comparison on the
 * proof of delivery and, when the customer is lighter by more than the tolerance, disputes the <b>full</b> variance
 * V = Q − C (rule 6: tolerance is an investigation trigger, not a deduction) as pending weight claims through the
 * discrepancy approval. A heavier customer weight is flagged only. Evidence never changes the ticket, the dispatched
 * quantity, stock or documents. Runs inside the caller's transaction with the delivery order and trip locked.
 */
@Component
class CustomerWeightEvidence {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String SUPERSEDED = "Superseded: the corrected customer weight no longer supports this claim";

    private final FleetDb db;
    private final FleetDiscrepancyService discrepancies;
    private final WeighbridgeStationService stations;
    private final WeighbridgeUnits units;
    private final AuditService audit;
    private final LoadLineHistory history;

    CustomerWeightEvidence(FleetDb db, FleetDiscrepancyService discrepancies, WeighbridgeStationService stations,
            WeighbridgeUnits units, AuditService audit, LoadLineHistory history) {
        this.db = db;
        this.discrepancies = discrepancies;
        this.stations = stations;
        this.units = units;
        this.audit = audit;
        this.history = history;
    }

    /** Line facts for the ceiling: D, Δ, R, G, E and the cap (line units), kg per unit (null = no factor). */
    record LineFacts(Map<String, Object> row, int lineNumber, BigDecimal eligible, BigDecimal cap,
            BigDecimal kgPerUnit, Map<String, Object> pendingClaim) {}

    /**
     * Record (or correct) the evidence; returns the frozen comparison plus the proposed / applied split. {@code trip}
     * is the locked trip row, {@code lines} its load lines.
     */
    Map<String, Object> record(Map<String, Object> trip, List<Map<String, Object>> lines, CustomerWeightInput in) {
        String tripId = (String) trip.get("id");
        Map<String, Object> pod = SqlRows.one(FleetProofOfDelivery.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetProofOfDelivery\" WHERE \"organizationId\" = ? AND \"loadId\" = ? FOR UPDATE")
                .params(FleetDb.org(), tripId));
        if (pod == null) {
            throw ApiException.badRequest("Record the proof of delivery of trip " + trip.get("code")
                    + " before the customer weight");
        }
        BigDecimal customerNet = customerNet(in);
        List<Map<String, Object>> claims = SqlRows.list(db.jdbc().sql("SELECT * FROM \"FleetDeliveryDiscrepancy\""
                + " WHERE \"organizationId\" = ? AND \"loadId\" = ? AND kind = 'weight' AND \"withdrawnAt\" IS NULL"
                + " ORDER BY \"createdAt\" FOR UPDATE")
                .params(FleetDb.org(), tripId));
        for (Map<String, Object> c : claims) {
            if (!"pending".equals(c.get("status"))) {
                throw ApiException.conflict("Weight claim " + c.get("code") + " is already " + ("approved".equals(
                        c.get("status")) ? "confirmed" : "dismissed") + "; the customer weight can no longer change it");
            }
        }
        Map<String, Map<String, Object>> pendingByLine = new HashMap<>();
        claims.forEach(c -> pendingByLine.put((String) c.get("loadLineId"), c));

        // Q and the tolerance T.
        BigDecimal quarry = null;
        String quarrySource = null;
        String ticketId = null;
        Tolerance tol = new Tolerance(BigDecimal.ZERO, BigDecimal.ZERO);
        List<LineFacts> facts = facts(lines, pendingByLine);
        String singleProduct = facts.size() == 1 ? (String) facts.getFirst().row().get("productId") : null;
        Map<String, Object> setting = singleProduct == null ? null : stations.productSetting(singleProduct);
        if ("weighbridge_ticket".equals(trip.get("weighSourceType")) && trip.get("weighSourceId") != null) {
            Map<String, Object> ticket = SqlRows.one(db.jdbc().sql("SELECT id, \"netKg\", \"stationId\" FROM"
                    + " \"WeighbridgeTicket\" WHERE id = ? AND \"organizationId\" = ?").params(trip.get("weighSourceId"),
                            FleetDb.org()));
            if (ticket != null && ticket.get("netKg") != null) {
                quarry = SqlRows.dec(ticket, "netKg");
                quarrySource = "ticket";
                ticketId = (String) ticket.get("id");
                Map<String, Object> station = stations.requireStation((String) ticket.get("stationId"), false);
                tol = WeighbridgeCalculator.effective(setting == null ? null : (BigDecimal) setting.get("tolerancePct"),
                        setting == null ? null : (BigDecimal) setting.get("toleranceKg"),
                        (BigDecimal) station.get("tolerancePct"), (BigDecimal) station.get("toleranceKg"));
            }
        }
        if (quarry == null && trip.get("manualGross") != null && trip.get("manualTare") != null) {
            quarry = SqlRows.dec(trip, "manualGross").subtract(SqlRows.dec(trip, "manualTare")).max(BigDecimal.ZERO);
            quarrySource = "manual";
            // No station: the product's own tolerance on a single-product truck, else none (any lighter weight is
            // investigated).
            tol = WeighbridgeCalculator.effective(setting == null ? null : (BigDecimal) setting.get("tolerancePct"),
                    setting == null ? null : (BigDecimal) setting.get("toleranceKg"), null, null);
        }

        String outcome;
        Check check = null;
        BigDecimal variance = null;
        if (quarry == null || quarry.signum() <= 0) {
            outcome = "no_comparison";
        } else {
            check = WeighbridgeCalculator.check(customerNet, quarry, tol);
            variance = check.varianceKg().negate(); // V = Q − C
            outcome = check.within() ? "within_tolerance" : variance.signum() < 0 ? "heavier" : "disputed";
        }

        List<Map<String, Object>> split = new ArrayList<>();
        BigDecimal unallocated = null;
        Map<String, BigDecimal[]> applied = new LinkedHashMap<>(); // lineId -> {qty, kg}
        if ("disputed".equals(outcome)) {
            WeightAllocation.Result proposal = WeightAllocation.propose(variance, facts.stream()
                    .map(f -> new WeightAllocation.Line((String) f.row().get("id"), f.lineNumber(), f.eligible(),
                            f.cap(), f.kgPerUnit())).toList());
            boolean automatic = facts.size() == 1 && facts.getFirst().kgPerUnit() != null;
            if (in.allocations() != null && !in.allocations().isEmpty()) {
                unallocated = confirmed(in.allocations(), facts, variance, applied);
            } else if (automatic) {
                for (WeightAllocation.Share s : proposal.shares()) {
                    applied.put(s.lineId(), new BigDecimal[] {s.qty(), s.kg()});
                }
                unallocated = proposal.unallocatedKg();
            } else {
                outcome = "awaiting_split";
                unallocated = variance.setScale(3, RoundingMode.HALF_UP);
            }
            for (int i = 0; i < facts.size(); i++) {
                LineFacts f = facts.get(i);
                WeightAllocation.Share s = proposal.shares().get(i);
                BigDecimal[] a = applied.get((String) f.row().get("id"));
                split.add(Obj.of("loadLineId", f.row().get("id"), "productId", f.row().get("productId"), "uomId",
                        f.row().get("uomId"), "eligibleQty", JsNumbers.dec(f.eligible()), "capQty",
                        JsNumbers.dec(f.cap()), "kgPerUnit", f.kgPerUnit() == null ? null : JsNumbers.dec(f.kgPerUnit()),
                        "proposedQty", JsNumbers.dec(s.qty()), "proposedKg", JsNumbers.dec(s.kg()), "capped", s.capped(),
                        "needsManual", s.needsManual(), "appliedQty", a == null ? null : JsNumbers.dec(a[0]),
                        "appliedKg", a == null ? null : JsNumbers.dec(a[1])));
            }
        }

        // Claims: raise / update / withdraw so that exactly the applied shares are pending.
        String note = "Customer weight " + JsNumbers.dec(customerNet) + " kg vs quarry " + (quarry == null ? "—"
                : JsNumbers.dec(quarry)) + " kg" + (in.ticketRef() == null ? "" : " (customer ticket " + in.ticketRef()
                        .trim() + ")");
        if (!"awaiting_split".equals(outcome)) {
            for (LineFacts f : facts) {
                String lineId = (String) f.row().get("id");
                BigDecimal[] a = applied.get(lineId);
                Map<String, Object> existing = f.pendingClaim();
                boolean share = a != null && a[0].signum() > 0;
                if (existing != null && share) {
                    discrepancies.updateWeightClaim((String) existing.get("id"), a[0], a[1], note);
                } else if (existing != null) {
                    discrepancies.withdrawWeightClaim((String) existing.get("id"), SUPERSEDED);
                } else if (share) {
                    discrepancies.raiseWeightClaim(trip, f.row(), a[0], a[1], note);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerGrossKg", in.grossKg());
        data.put("customerTareKg", in.tareKg());
        data.put("customerNetKg", customerNet);
        data.put("customerTicketRef", in.ticketRef() == null || in.ticketRef().isBlank() ? null : in.ticketRef().trim());
        data.put("customerWeighedAt", in.weighedAt());
        data.put("customerWeightNotes", FleetVehicleService.trim(in.notes()));
        data.put("customerWeightBy", TenantContext.userId());
        data.put("customerWeightAt", Instant.now());
        data.put("quarryNetKg", quarry);
        data.put("quarryWeightSource", quarrySource);
        data.put("quarryTicketId", ticketId);
        data.put("weightVarianceKg", variance);
        data.put("allowedVarianceKg", check == null ? null : check.allowedKg());
        data.put("weightWithinTolerance", check == null ? null : check.within());
        data.put("weightOutcome", outcome);
        data.put("unallocatedVarianceKg", unallocated);
        Map<String, Object> after = db.update(FleetProofOfDelivery.class, (String) pod.get("id"), data);
        audit.recordInTx(AuditInput.of("FleetProofOfDelivery", (String) pod.get("id"), "update", pod, after));
        for (LineFacts f : facts) {
            history.record(f.row(), "customer_weight_recorded", null, null, null, note + " — " + outcome, List.of(),
                    "pod", (String) pod.get("id"));
        }
        return Obj.of("outcome", outcome, "customerNetKg", JsNumbers.dec(customerNet), "quarryNetKg",
                quarry == null ? null : JsNumbers.dec(quarry), "quarryWeightSource", quarrySource, "varianceKg",
                variance == null ? null : JsNumbers.dec(variance), "allowedVarianceKg", check == null ? null
                        : JsNumbers.dec(check.allowedKg()), "withinTolerance", check == null ? null : check.within(),
                "unallocatedVarianceKg", unallocated == null ? null : JsNumbers.dec(unallocated), "lines", split);
    }

    private static BigDecimal customerNet(CustomerWeightInput in) {
        BigDecimal gross = in.grossKg();
        BigDecimal tare = in.tareKg();
        BigDecimal net = in.netKg();
        if (gross != null && tare != null) {
            if (gross.compareTo(tare) <= 0) {
                throw ApiException.badRequest("The customer's gross weight must be heavier than the tare");
            }
            BigDecimal computed = gross.subtract(tare);
            if (net != null && net.compareTo(computed) != 0) {
                throw ApiException.badRequest("The customer's net weight must equal gross − tare ("
                        + JsNumbers.dec(computed) + " kg)");
            }
            return computed;
        }
        if (net == null) {
            throw ApiException.badRequest("Give the customer's net weight, or its gross and tare weights");
        }
        if ((gross == null) != (tare == null)) {
            throw ApiException.badRequest("Give both the customer's gross and tare weights, or neither");
        }
        return net;
    }

    /** Lines that carried goods, with E = max(0, min(Δ, D − R − G)) and cap = E − the line's other weight claims. */
    private List<LineFacts> facts(List<Map<String, Object>> lines, Map<String, Map<String, Object>> pendingByLine) {
        List<LineFacts> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> l : lines) {
            n++;
            BigDecimal d = dec(l, "dispatchedQty");
            if (d.signum() <= 0) {
                continue;
            }
            BigDecimal eligible = dec(l, "deliveredQty").min(d.subtract(dec(l, "returnedQty"))
                    .subtract(dec(l, "damagedQty"))).max(BigDecimal.ZERO);
            Map<String, Object> pending = pendingByLine.get((String) l.get("id"));
            // Other weight claims on the line (pending or confirmed), the one being updated excluded.
            BigDecimal others = db.jdbc().sql("SELECT coalesce(sum(\"differenceQty\"), 0) FROM \"FleetDeliveryDiscrepancy\""
                    + " WHERE \"organizationId\" = ? AND \"loadLineId\" = ? AND kind = 'weight' AND status IN"
                    + " ('pending', 'approved') AND id <> ?").params(FleetDb.org(), l.get("id"),
                            pending == null ? "" : pending.get("id")).query(BigDecimal.class).single();
            BigDecimal cap = eligible.subtract(others).max(BigDecimal.ZERO);
            out.add(new LineFacts(l, n, eligible, cap, kgPerUnit(l), pending));
        }
        return out;
    }

    /** Kg per line unit: the line's tonnes per unit, else the product's weight conversion; null when neither. */
    private BigDecimal kgPerUnit(Map<String, Object> l) {
        if (l.get("tonnesPerUnit") != null && SqlRows.dec(l, "tonnesPerUnit").signum() > 0) {
            return SqlRows.dec(l, "tonnesPerUnit").multiply(THOUSAND);
        }
        if (l.get("uomId") == null) {
            return null;
        }
        try {
            BigDecimal k = units.kgPerUnit((String) l.get("productId"), (String) l.get("uomId"));
            return k == null || k.signum() <= 0 ? null : k;
        } catch (ApiException e) {
            return null;
        }
    }

    /** Validate a user-confirmed split (plan §7): 0 ≤ qty ≤ cap per line, Σ kg ≤ V; returns the unallocated kg. */
    private static BigDecimal confirmed(List<WeightShareInput> given, List<LineFacts> facts, BigDecimal variance,
            Map<String, BigDecimal[]> applied) {
        Map<String, LineFacts> byId = new HashMap<>();
        facts.forEach(f -> byId.put((String) f.row().get("id"), f));
        BigDecimal sumKg = BigDecimal.ZERO;
        for (WeightShareInput s : given) {
            LineFacts f = byId.get(s.loadLineId());
            if (f == null) {
                throw ApiException.badRequest("Trip line not found");
            }
            if (applied.containsKey(s.loadLineId())) {
                throw ApiException.badRequest("Each trip line can appear once in the weight split");
            }
            if (s.quantity().compareTo(f.cap()) > 0) {
                throw ApiException.badRequest("The weight claim on a line cannot exceed what is still eligible on it ("
                        + JsNumbers.dec(f.cap()) + ")");
            }
            BigDecimal kg;
            if (f.kgPerUnit() != null) {
                kg = WeightAllocation.kg(s.quantity(), f.kgPerUnit());
            } else if (s.kg() != null) {
                kg = s.kg().setScale(3, RoundingMode.HALF_UP);
            } else {
                throw ApiException.badRequest("Give the kg of the weight claim on a line whose unit has no weight"
                        + " conversion");
            }
            sumKg = sumKg.add(kg);
            applied.put(s.loadLineId(), new BigDecimal[] {s.quantity().setScale(3, RoundingMode.HALF_UP), kg});
        }
        BigDecimal v = variance.setScale(3, RoundingMode.HALF_UP);
        if (sumKg.compareTo(v) > 0) {
            throw ApiException.badRequest("The weight split (" + JsNumbers.dec(sumKg) + " kg) is more than the"
                    + " variance (" + JsNumbers.dec(v) + " kg)");
        }
        return v.subtract(sumKg);
    }

    private static BigDecimal dec(Map<String, Object> row, String key) {
        return row.get(key) == null ? BigDecimal.ZERO : SqlRows.dec(row, key);
    }
}
