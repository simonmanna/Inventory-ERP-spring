package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.LoadLineQuantities.Buckets;
import com.inventoryerp.fleet.application.LoadLineQuantities.Claim;
import com.inventoryerp.fleet.application.LoadLineQuantities.Facts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase A A3 (plan §3, §15): quantity buckets of a delivery line — invariant, non-negativity, claim states. */
class LoadLineQuantitiesTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Claim claim(String kind, String status, String qty) {
        return new Claim(kind + status, kind, status, d(qty), BigDecimal.ZERO, 2, false);
    }

    static Buckets of(String dispatched, String delivered, String returned, String damaged, Claim... claims) {
        return LoadLineQuantities.compute(new Facts(d(dispatched), delivered == null ? null : d(delivered),
                d(returned), d(damaged), List.of(claims)));
    }

    @Test
    void reviewersExample30Is25Plus2Plus1Plus1Plus1() {
        // D 30, Δ 26, R 2, G 1 → S 1 (confirmed); a 1 t weight claim is pending.
        Buckets b = of("30", "26", "2", "1", claim("shortage", "approved", "1"), claim("weight", "pending", "1"));
        assertThat(b.accepted()).isEqualByComparingTo("25");
        assertThat(b.returned()).isEqualByComparingTo("2");
        assertThat(b.damaged()).isEqualByComparingTo("1");
        assertThat(b.confirmedShortage()).isEqualByComparingTo("1");
        assertThat(b.pendingDisputed()).isEqualByComparingTo("1");
        assertThat(b.invariantGap()).isEqualByComparingTo("0");
        assertThat(b.findings()).isEmpty();
    }

    @Test
    void weightClaimsAreRefusedAboveTheCeiling() {
        // 30 dispatched, 30 delivered, 5 returned, 5 damaged: E = 20, X = 10; 25 t of weight disputes is a finding and
        // is clamped at the ceiling of 20.
        Buckets b = of("30", "30", "5", "5", claim("weight", "pending", "25"));
        assertThat(b.eligible()).isEqualByComparingTo("20");
        assertThat(b.excess()).isEqualByComparingTo("10");
        assertThat(b.weightPending()).isEqualByComparingTo("20");
        assertThat(b.accepted()).isEqualByComparingTo("0");
        assertThat(b.findings()).contains("weight_claims_exceed_eligible");
        assertThat(b.invariantGap()).isEqualByComparingTo("0");
    }

    @Test
    void partialReconciliationThenConfirmation() {
        // D 30, Δ corrected 28 → 29 (1 reconciled on a 2 t claim), the remaining 1 confirmed.
        Claim c = new Claim("c", "shortage", "approved", d("2"), d("1"), 2, false);
        Buckets b = of("30", "29", "0", "0", c);
        assertThat(b.accepted()).isEqualByComparingTo("29");
        assertThat(b.confirmedShortage()).isEqualByComparingTo("1");
        assertThat(b.pendingDisputed()).isEqualByComparingTo("0");
    }

    @Test
    void dismissedPodShortageStaysPendingDisputed() {
        Buckets b = of("30", "28", "0", "0", claim("shortage", "rejected", "2"));
        assertThat(b.accepted()).isEqualByComparingTo("28");
        assertThat(b.pendingDisputed()).as("POD correction required").isEqualByComparingTo("2");
        assertThat(b.confirmedShortage()).isEqualByComparingTo("0");
    }

    @Test
    void dismissedWeightClaimRestoresAccepted() {
        Buckets b = of("30", "30", "0", "0", claim("weight", "rejected", "2"));
        assertThat(b.accepted()).isEqualByComparingTo("30");
        assertThat(b.pendingDisputed()).isEqualByComparingTo("0");
    }

    @Test
    void legacyRejectedShortageWasBilled() {
        Claim legacy = new Claim("l", "shortage", "rejected", d("2"), BigDecimal.ZERO, 1, false);
        Buckets b = of("30", "28", "0", "0", legacy);
        assertThat(b.legacy()).isTrue();
        assertThat(b.accepted()).as("model 1: rejected = quantity billed").isEqualByComparingTo("30");
        assertThat(b.pendingDisputed()).isEqualByComparingTo("0");
        assertThat(b.invariantGap()).isEqualByComparingTo("0");
    }

    @Test
    void beforeThePodOnlyInTransit() {
        Buckets b = of("30", null, "0", "0");
        assertThat(b.delivered()).isFalse();
        assertThat(b.accepted()).isEqualByComparingTo("0");
        assertThat(b.inTransit()).isEqualByComparingTo("30");
    }

    @Test
    void withdrawnClaimsMoveNothing() {
        Claim w = new Claim("w", "weight", "rejected", d("3"), BigDecimal.ZERO, 2, true);
        assertThat(of("10", "10", "0", "0", w).accepted()).isEqualByComparingTo("10");
    }

    @Test
    void invariantAndNonNegativityOverRandomFacts() {
        Random rnd = new Random(7);
        String[] statuses = {"pending", "approved", "rejected", "reconciled"};
        String[] kinds = {"shortage", "weight", "damaged", "returned", "excess"};
        for (int i = 0; i < 5000; i++) {
            BigDecimal dsp = BigDecimal.valueOf(rnd.nextInt(50_000), 3);
            BigDecimal r = BigDecimal.valueOf(rnd.nextInt(dsp.movePointRight(3).intValue() + 1), 3);
            BigDecimal g = BigDecimal.valueOf(rnd.nextInt(dsp.subtract(r).movePointRight(3).intValue() + 1), 3);
            BigDecimal delta = BigDecimal.valueOf(rnd.nextInt(60_000), 3);
            List<Claim> claims = new ArrayList<>();
            for (int k = rnd.nextInt(4); k > 0; k--) {
                BigDecimal q = BigDecimal.valueOf(rnd.nextInt(20_000), 3);
                claims.add(new Claim("c" + k, kinds[rnd.nextInt(kinds.length)], statuses[rnd.nextInt(statuses.length)],
                        q, BigDecimal.valueOf(rnd.nextInt(q.movePointRight(3).intValue() + 1), 3), 1 + rnd.nextInt(2),
                        rnd.nextInt(10) == 0));
            }
            Buckets b = LoadLineQuantities.compute(new Facts(dsp, delta, r, g, claims));
            assertThat(b.invariantGap()).as("facts %s %s %s %s %s", dsp, delta, r, g, claims).isEqualByComparingTo("0");
            for (BigDecimal v : List.of(b.accepted(), b.pendingDisputed(), b.confirmedShortage(), b.excess(),
                    b.returned(), b.damaged())) {
                assertThat(v.signum()).isGreaterThanOrEqualTo(0);
            }
            assertThat(b.findings()).doesNotContain("invariant_broken");
        }
    }
}
