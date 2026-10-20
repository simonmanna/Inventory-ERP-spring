package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.PosGlReconciliationService;
import com.inventoryerp.accounting.application.PosGlReconciliationService.Inputs;
import com.inventoryerp.accounting.application.PosGlReconciliationService.Section;
import com.inventoryerp.accounting.application.PostingMath;
import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.SessionReconciliation;
import com.inventoryerp.accounting.application.TenderAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure ports of {@code accounting-invariant.spec.ts}, {@code pos-gl-reconciliation.service.spec.ts},
 * {@code session-reconciliation.spec.ts}, the tender-category fence of {@code pos-payment-method.service.spec.ts}
 * and the {@code currency.service.spec.ts} placeholder.
 */
class AccountingSpecsTest {

    static BigDecimal d(Object v) {
        return new BigDecimal(v.toString());
    }

    @Nested
    @DisplayName("accounting invariants (D5-2)")
    class Invariants {

        record Raw(String accountId, BigDecimal debit, BigDecimal credit) {}

        /** Seeded LCG identical to the Jest helper so batches are deterministic. */
        List<Raw> randomBatch(long seed) {
            long[] s = {seed & 0xFFFFFFFFL};
            java.util.function.DoubleSupplier rand = () -> {
                s[0] = (s[0] * 1664525L + 1013904223L) & 0xFFFFFFFFL;
                return (double) (s[0] & 0xfffffff) / 0xfffffff;
            };
            String[] accounts = {"cash", "ar", "ap", "revenue", "expense", "equity", "cogs", "tax"};
            List<Raw> lines = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String acct = accounts[(int) Math.floor(rand.getAsDouble() * accounts.length)];
                boolean debit = rand.getAsDouble() < 0.5;
                BigDecimal amt = new BigDecimal(Double.toString(Math.round(rand.getAsDouble() * 10000) / 100.0));
                lines.add(debit ? new Raw(acct, amt, BigDecimal.ZERO) : new Raw(acct, BigDecimal.ZERO, amt));
            }
            return lines;
        }

        List<NormalizedLine> forceBalance(List<Raw> raw) {
            List<NormalizedLine> norm = new ArrayList<>(PostingMath.normalizeLines(raw.stream()
                    .map(r -> new PostingRequest.Line(r.accountId(), r.debit().toString(), r.credit().toString(), null, null, null,
                            null, null))
                    .toList(), BigDecimal.ONE));
            PostingMath.Totals t = PostingMath.totals(norm);
            BigDecimal diff = t.debit().subtract(t.credit());
            if (diff.signum() != 0) {
                BigDecimal dr = diff.signum() > 0 ? BigDecimal.ZERO : diff.abs();
                BigDecimal cr = diff.signum() > 0 ? diff.abs() : BigDecimal.ZERO;
                norm.add(new NormalizedLine("rounding", null, null, null, null, null, dr, cr, dr, cr));
            }
            return norm;
        }

        NormalizedLine line(Object debit, Object credit) {
            return new NormalizedLine("a", null, null, null, null, null, d(debit), d(credit), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Test
        void rejectsBothDebitAndCredit() {
            assertThat(PostingMath.validateLines(List.of(line(10, 10))).get(0).message()).containsIgnoringCase("both");
        }

        @Test
        void rejectsNeitherDebitNorCredit() {
            assertThat(PostingMath.validateLines(List.of(line(0, 0))).get(0).message()).containsIgnoringCase("must have");
        }

        @Test
        void rejectsNegativeAmounts() {
            assertThat(PostingMath.validateLines(List.of(line(-1, 0))).get(0).message()).containsIgnoringCase("negative");
        }

        @Test
        void normalizeKeepsASingleLine() {
            // PostingService enforces >= 2 lines; the math layer only normalizes.
            assertThat(PostingMath.normalizeLines(List.of(PostingRequest.Line.debit("a", 100, null)), BigDecimal.ONE)).hasSize(1);
        }

        @Test
        void forcedBalancedBatchesAreBalanced() {
            for (int seed = 0; seed < 25; seed++) {
                assertThat(PostingMath.isBalanced(forceBalance(randomBatch(seed)))).isTrue();
            }
        }

        @Test
        void totalsNeverNegative() {
            for (int seed = 0; seed < 25; seed++) {
                PostingMath.Totals t = PostingMath.totals(forceBalance(randomBatch(seed)));
                assertThat(t.debit().signum()).isGreaterThanOrEqualTo(0);
                assertThat(t.credit().signum()).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        void normalizationPreservesBalanceWithinEpsilon() {
            for (int seed = 0; seed < 25; seed++) {
                PostingMath.Totals t = PostingMath.totals(forceBalance(randomBatch(seed)));
                assertThat(t.debit().subtract(t.credit()).abs()).isLessThanOrEqualTo(new BigDecimal("0.0001"));
            }
        }

        @Test
        void decimalMathIsAssociative() {
            assertThat(d(100).add(d(50)).add(d(25)).toPlainString()).isEqualTo("175");
            assertThat(d(100).add(d(50).add(d(25))).toPlainString()).isEqualTo("175");
        }
    }

    @Nested
    @DisplayName("POS → GL reconciliation (R-1 refund split, D1-GL-002)")
    class PosGl {

        static final Map<String, Section> ACCOUNTS = Map.of(
                "a-revenue", new Section("revenue", "credit"), "a-tax", new Section("tax", "credit"),
                "rev", new Section("revenue", "credit"), "vat", new Section("current_liabilities", "credit"),
                "cogs", new Section("cogs", "debit"));

        /** [accountId, baseDebit, baseCredit] rows grouped like the SQL GROUP BY. */
        static Map<String, BigDecimal[]> gl(Object[]... rows) {
            Map<String, BigDecimal[]> out = new LinkedHashMap<>();
            for (Object[] r : rows) {
                BigDecimal[] cur = out.computeIfAbsent((String) r[0], k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                cur[0] = cur[0].add(d(r[1]));
                cur[1] = cur[1].add(d(r[2]));
            }
            return out;
        }

        static Map<String, Object> run(Object subtotal, Object tax, Object discount, long invoices, Object refundedRevenue,
                Object refundedTax, long refunds, Map<String, BigDecimal[]> gl, Set<String> taxAccounts, Object issues,
                Object returns) {
            return PosGlReconciliationService.compute(new Inputs(Instant.EPOCH, Instant.EPOCH, d(subtotal), d(tax), d(discount),
                    invoices, refunds, BigDecimal.ZERO, d(refundedRevenue), d(refundedTax), gl, ACCOUNTS, taxAccounts, d(issues),
                    d(returns), new HashMap<>(Map.of("done", 1L))));
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> part(Map<String, Object> r, String key) {
            return (Map<String, Object>) r.get(key);
        }

        @Test
        void zeroVarianceForTaxInclusiveSaleAndRefund() {
            Map<String, Object> r = run(100000, 18000, 0, 1, 100000, 18000, 1,
                    gl(new Object[] {"a-revenue", 0, 100000}, new Object[] {"a-tax", 0, 18000},
                            new Object[] {"a-revenue", 100000, 0}, new Object[] {"a-tax", 18000, 0}), Set.of(), 0, 0);
            assertThat(part(r, "pos").get("refundedRevenue")).isEqualTo("100000");
            assertThat(part(r, "pos").get("refundedTax")).isEqualTo("18000");
            assertThat(part(r, "variance")).containsEntry("revenueBalanced", true).containsEntry("taxBalanced", true)
                    .containsEntry("revenue", "0").containsEntry("tax", "0");
            assertThat(r.get("balanced")).isEqualTo(true);
        }

        @Test
        void detectsRealRevenueVariance() {
            Map<String, Object> r = run(100000, 0, 0, 1, 0, 0, 0, gl(), Set.of(), 0, 0);
            assertThat(part(r, "variance")).containsEntry("revenueBalanced", false).containsEntry("revenue", "-100000");
        }

        @Test
        void crossWindowRefundMirrorsNegativeGlWindow() {
            Map<String, Object> r = run(0, 0, 0, 0, 100000, 18000, 1,
                    gl(new Object[] {"a-revenue", 100000, 0}, new Object[] {"a-tax", 18000, 0}), Set.of(), 0, 0);
            assertThat(part(r, "pos")).containsEntry("expectedNetRevenue", "-100000").containsEntry("expectedNetTax", "-18000");
            assertThat(part(r, "variance")).containsEntry("revenueBalanced", true).containsEntry("taxBalanced", true);
        }

        @Test
        void partialRefundSplitsProportionally() {
            // Two refunded line fractions: 60000/10800 + 40000/7200.
            Map<String, Object> r = run(200000, 36000, 0, 1, d(60000).add(d(40000)), d(10800).add(d(7200)), 1,
                    gl(new Object[] {"a-revenue", 0, 200000}, new Object[] {"a-tax", 0, 36000},
                            new Object[] {"a-revenue", 100000, 0}, new Object[] {"a-tax", 18000, 0}), Set.of(), 0, 0);
            assertThat(part(r, "pos").get("refundedRevenue")).isEqualTo("100000");
            assertThat(part(r, "variance")).containsEntry("revenueBalanced", true).containsEntry("taxBalanced", true);
        }

        @Test
        void doesNotSubtractDiscountsTwice() {
            Map<String, Object> r = run(180, "32.4", 20, 1, 0, 0, 0, gl(new Object[] {"rev", 0, 180}), Set.of(), 0, 0);
            assertThat(part(r, "variance").get("revenue")).isEqualTo("0");
        }

        @Test
        void findsOutputVatThroughTaxRowAccount() {
            Map<String, Object> r = run(100, 18, 0, 1, 0, 0, 0,
                    gl(new Object[] {"rev", 0, 100}, new Object[] {"vat", 0, 18}), Set.of("vat"), 0, 0);
            assertThat(part(r, "gl").get("tax")).isEqualTo("18");
            assertThat(part(r, "variance").get("taxBalanced")).isEqualTo(true);
            assertThat(r.get("balanced")).isEqualTo(true);
        }

        @Test
        void netsRefundRestocksOutOfStockCogs() {
            Map<String, Object> r = run(0, 0, 0, 1, 0, 0, 0, gl(new Object[] {"cogs", 74720, 0}), Set.of(), 83800, 9080);
            assertThat(part(r, "inventory").get("cogsBalanced")).isEqualTo(true);
            assertThat(part(r, "pos").get("invoiceCount")).isEqualTo(1L);
            assertThat(part(r, "inventory").get("unpostedJobs")).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("sessionProviderExpectations")
    class ProviderExpectations {

        static final String MOMO = "acc-momo";
        static final String BANK = "acc-bank";
        static final JsonMapper JSON = JsonMapper.builder().build();

        static Map<String, Object> session(String openingAccountsJson) {
            Map<String, Object> s = new HashMap<>();
            s.put("id", "s1");
            if (openingAccountsJson != null) {
                s.put("openingAccounts", JSON.readTree(openingAccountsJson));
            }
            return s;
        }

        static Map<String, Object> row(Object... kv) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1] instanceof String s && s.matches("-?[\\d.]+") ? d(s) : kv[i + 1]);
            }
            return m;
        }

        @Test
        void ignoresEarlierShifts() {
            var out = SessionReconciliation.expectations(session("{}"), List.of(MOMO),
                    List.of(row("accountId", MOMO, "amount", "10000", "direction", "inbound")), List.of(), List.of());
            assertThat(out.get(MOMO)).containsEntry("opening", "0").containsEntry("openingKnown", false)
                    .containsEntry("receipts", "10000").containsEntry("expected", "10000");
        }

        @Test
        void addsRecordedOpeningObservation() {
            var out = SessionReconciliation.expectations(session("{\"" + MOMO + "\":{\"observed\":\"50000\",\"ledger\":\"50000\"}}"),
                    List.of(MOMO), List.of(row("accountId", MOMO, "amount", "10000", "direction", "inbound")), List.of(), List.of());
            assertThat(out.get(MOMO).get("expected")).isEqualTo("60000");
            BigDecimal[] legacy = SessionReconciliation.openingObservation(session("{\"" + MOMO + "\":20000}"), MOMO);
            assertThat(legacy[0]).isEqualByComparingTo("20000");
            assertThat(legacy[1].signum()).isPositive();
        }

        @Test
        void refundsAndSettlements() {
            var out = SessionReconciliation.expectations(session("{\"" + MOMO + "\":{\"observed\":\"50000\"}}"), List.of(MOMO, BANK),
                    List.of(row("accountId", MOMO, "amount", "10000", "direction", "inbound"),
                            row("accountId", MOMO, "amount", "2000", "direction", "outbound"),
                            row("accountId", BANK, "amount", "1000", "withholdingAmount", "60", "direction", "outbound")),
                    List.of(row("sourceAccountId", MOMO, "destinationAccountId", BANK, "grossAmount", "5000", "feeAmount", "100")),
                    List.of());
            assertThat(out.get(MOMO)).containsEntry("refunds", "2000").containsEntry("settledOut", "5000")
                    .containsEntry("expected", "53000");
            assertThat(out.get(BANK)).containsEntry("refunds", "940").containsEntry("settledIn", "4900")
                    .containsEntry("expected", "3960");
        }

        @Test
        void mirrorsDrawerMovementsAndSkipsPaymentLinked() {
            var out = SessionReconciliation.expectations(session(null), List.of(BANK), List.of(), List.of(),
                    List.of(row("counterpartAccountId", BANK, "movementType", "pay_out", "amount", "30000"),
                            row("counterpartAccountId", BANK, "movementType", "pay_in", "amount", "5000"),
                            row("counterpartAccountId", BANK, "movementType", "adjustment", "amount", "-1000"),
                            row("counterpartAccountId", BANK, "movementType", "sale", "amount", "999", "paymentId", "p1")));
            assertThat(out.get(BANK)).containsEntry("movementsIn", "31000").containsEntry("movementsOut", "5000")
                    .containsEntry("expected", "26000");
        }

        @Test
        void openingOnlyRowForUntouchedAccount() {
            var out = SessionReconciliation.expectations(session("{\"" + MOMO + "\":{\"observed\":\"20000\"}}"), List.of(MOMO),
                    List.of(), List.of(), List.of());
            assertThat(out.get(MOMO)).containsEntry("openingKnown", true).containsEntry("expected", "20000");
        }
    }

    @Test
    @DisplayName("every POS tender kind binds to a category the posting engine accepts")
    void everyKindHasAllowedCategories() {
        for (String kind : List.of("cash", "mobile_money", "card", "bank", "store_credit")) {
            assertThat(TenderAccount.ALLOWED_CATEGORIES_BY_METHOD.get(kind)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("currency smoke (Phase C placeholder)")
    void currencyPlaceholder() {
        assertThat(true).isTrue();
    }
}
