package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase A A3 (plan §3): the quantity buckets of one delivery (load) line. Pure; no database.
 *
 * <p>Physical facts: dispatched D, delivered on the POD Δ (null before the POD), returned to the yard R, damaged G.
 * Claims ({@code FleetDeliveryDiscrepancy}) move quantity between buckets by their status:
 *
 * <ul>
 *   <li>E = max(0, min(Δ, D − R − G)); POD shortage S = max(0, D − R − G − Δ); excess X = max(0, Δ + R + G − D);
 *   <li>weight claims: pending Wp, confirmed Wc, 0 ≤ Wp + Wc ≤ E (a breach is a finding and is clamped, confirmed
 *       first);
 *   <li>POD-shortage claims: confirmed S_conf (≤ S); the rest of S is open (pending, or dismissed and awaiting a POD
 *       correction); a reconciled part was added to Δ by the correction itself;
 *   <li>accepted = E − Wp − Wc; pending disputed = S_open + Wp; confirmed shortage = S_conf + Wc.
 * </ul>
 *
 * <p>Invariant: D = accepted + R + G + confirmed shortage + pending disputed (excess stands outside it). Model-1 claims
 * (decided before A3) keep their old meaning: a rejected shortage was billed, so its open quantity counts as accepted.
 */
public final class LoadLineQuantities {

    /** A claim on the line; {@code qty} is its claim quantity (differenceQty), {@code reconciled} the corrected part. */
    public record Claim(String id, String kind, String status, BigDecimal qty, BigDecimal reconciled, int model,
            boolean withdrawn) {

        /** Open quantity: claim − reconciled, never below 0. */
        public BigDecimal open() {
            return nz(qty).subtract(nz(reconciled)).max(BigDecimal.ZERO);
        }

        /** {@code pending}, {@code confirmed}, {@code dismissed}, {@code reconciled} or {@code withdrawn}. */
        public String decision() {
            return decision(status, withdrawn);
        }

        /** The claim decision label of a stored status ("rejected" is never shown). */
        public static String decision(String status, boolean withdrawn) {
            if (withdrawn) {
                return "withdrawn";
            }
            return switch (status == null ? "pending" : status) {
                case "approved" -> "confirmed";
                case "rejected" -> "dismissed";
                case "reconciled" -> "reconciled";
                default -> "pending";
            };
        }
    }

    /** Facts of the line: D, Δ (null before the POD), R, G and its claims. */
    public record Facts(BigDecimal dispatched, BigDecimal delivered, BigDecimal returned, BigDecimal damaged,
            List<Claim> claims) {}

    /**
     * The buckets. Before the POD only {@code inTransit} (D − R) is set. {@code legacy} = the line carries a model-1
     * claim (its billable quantity keeps the pre-A3 meaning). {@code findings} = broken rules (never negative buckets).
     */
    public record Buckets(boolean delivered, BigDecimal dispatched, BigDecimal deliveredQty, BigDecimal eligible,
            BigDecimal accepted, BigDecimal returned, BigDecimal damaged, BigDecimal pendingDisputed,
            BigDecimal confirmedShortage, BigDecimal excess, BigDecimal podShortage, BigDecimal shortageOpen,
            BigDecimal shortageConfirmed, BigDecimal weightPending, BigDecimal weightConfirmed, BigDecimal inTransit,
            boolean legacy, List<String> findings) {

        /** {@code shortage} as Sales showed it before A3: pending + confirmed. */
        public BigDecimal shortage() {
            return pendingDisputed.add(confirmedShortage);
        }

        /** D − (accepted + R + G + confirmed + pending); 0 when the invariant holds. */
        public BigDecimal invariantGap() {
            if (!delivered) {
                return BigDecimal.ZERO;
            }
            return dispatched.subtract(accepted.add(returned).add(damaged).add(confirmedShortage).add(pendingDisputed));
        }
    }

    private LoadLineQuantities() {}

    /** The buckets of one line. */
    public static Buckets compute(Facts f) {
        BigDecimal d = nz(f.dispatched()).max(BigDecimal.ZERO);
        BigDecimal r = nz(f.returned()).max(BigDecimal.ZERO);
        BigDecimal g = nz(f.damaged()).max(BigDecimal.ZERO);
        List<Claim> claims = f.claims() == null ? List.of() : f.claims();
        boolean legacy = claims.stream().anyMatch(c -> c.model() == 1);
        List<String> findings = new ArrayList<>();
        if (f.delivered() == null) {
            BigDecimal z = BigDecimal.ZERO;
            return new Buckets(false, d, null, z, z, r, g, z, z, z, z, z, z, z, z, d.subtract(r).max(z), legacy,
                    findings);
        }
        BigDecimal delta = f.delivered().max(BigDecimal.ZERO);
        BigDecimal net = d.subtract(r).subtract(g);
        if (net.signum() < 0) {
            findings.add("returned_damaged_exceed_dispatched");
        }
        BigDecimal e = delta.min(net).max(BigDecimal.ZERO);
        BigDecimal s = net.subtract(delta).max(BigDecimal.ZERO);
        BigDecimal x = delta.add(r).add(g).subtract(d).max(BigDecimal.ZERO);

        BigDecimal wp = BigDecimal.ZERO;
        BigDecimal wc = BigDecimal.ZERO;
        BigDecimal sConf = BigDecimal.ZERO;
        BigDecimal legacyBilled = BigDecimal.ZERO;
        for (Claim c : claims) {
            if (c.withdrawn()) {
                continue;
            }
            String st = c.status() == null ? "pending" : c.status();
            switch (c.kind() == null ? "" : c.kind()) {
                case "weight" -> {
                    if ("pending".equals(st)) {
                        wp = wp.add(c.open());
                    } else if ("approved".equals(st)) {
                        wc = wc.add(c.open());
                    }
                }
                case "shortage" -> {
                    if ("approved".equals(st)) {
                        sConf = sConf.add(c.open());
                    } else if ("rejected".equals(st) && c.model() == 1) {
                        legacyBilled = legacyBilled.add(c.open());
                    }
                }
                default -> {
                    // returned / damaged / excess claims document a physical fact; they move no quantity.
                }
            }
        }
        if (sConf.compareTo(s) > 0) {
            findings.add("confirmed_shortage_exceeds_pod_shortage");
            sConf = s;
        }
        BigDecimal billedLegacy = legacyBilled.min(s.subtract(sConf));
        BigDecimal sOpen = s.subtract(sConf).subtract(billedLegacy);
        if (wp.add(wc).compareTo(e) > 0) {
            findings.add("weight_claims_exceed_eligible");
            wc = wc.min(e);
            wp = wp.min(e.subtract(wc));
        }
        BigDecimal accepted = e.subtract(wp).subtract(wc).add(billedLegacy);
        BigDecimal pending = sOpen.add(wp);
        BigDecimal confirmed = sConf.add(wc);
        Buckets b = new Buckets(true, d, delta, e, accepted, r, g, pending, confirmed, x, s, sOpen, sConf, wp, wc,
                BigDecimal.ZERO, legacy, findings);
        if (net.signum() >= 0 && b.invariantGap().signum() != 0) {
            findings.add("invariant_broken");
        }
        return b;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
