package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.invoicing.application.TaxCalculationService;
import com.inventoryerp.invoicing.application.TaxCalculationService.LineTaxResult;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxAmount;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxLike;
import com.inventoryerp.kernel.common.JsNumbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Port of {@code tax-calculation.service.spec.ts}, plus per-tax inclusive / compound back-out (R13). */
@DisplayName("TaxCalculationService")
class TaxCalculationServiceTest {

    final TaxCalculationService svc = new TaxCalculationService();

    static BigDecimal d(Object v) {
        return new BigDecimal(v.toString());
    }

    static String s(BigDecimal v) {
        return JsNumbers.dec(v);
    }

    static TaxLike tax(String id, int rate, boolean inclusive, boolean compound, String type) {
        return new TaxLike(id, d(rate), inclusive, compound, type);
    }

    @Test
    @DisplayName("adds an exclusive tax on top of the net")
    void exclusive() {
        LineTaxResult r = svc.computeLine(d(100), List.of(tax("t", 18, false, false, null)), null);
        assertThat(s(r.net())).isEqualTo("100");
        assertThat(s(r.taxTotal())).isEqualTo("18");
        assertThat(s(r.gross())).isEqualTo("118");
    }

    @Test
    @DisplayName("extracts the net from an inclusive price")
    void inclusive() {
        LineTaxResult r = svc.computeLine(d(118), List.of(tax("t", 18, true, false, null)), null);
        assertThat(s(r.net())).isEqualTo("100");
        assertThat(s(r.taxTotal())).isEqualTo("18");
        assertThat(s(r.gross())).isEqualTo("118");
    }

    @Test
    @DisplayName("rounds inclusive VAT once to the currency scale and keeps the shelf price as gross")
    void inclusiveScale() {
        LineTaxResult r = svc.computeLine(d(20000), List.of(tax("t", 18, true, false, null)), 0);
        assertThat(s(r.taxTotal())).isEqualTo("3051");
        assertThat(s(r.net())).isEqualTo("16949");
        assertThat(s(r.gross())).isEqualTo("20000");
    }

    @Test
    @DisplayName("rounds exclusive VAT to the currency scale and adds it on top")
    void exclusiveScale() {
        LineTaxResult r = svc.computeLine(d(9999), List.of(tax("t", 18, false, false, null)), 0);
        assertThat(s(r.taxTotal())).isEqualTo("1800");
        assertThat(s(r.gross())).isEqualTo("11799");
    }

    @Test
    @DisplayName("returns zero tax when there are no taxes")
    void none() {
        LineTaxResult r = svc.computeLine(d(100), List.of(), null);
        assertThat(s(r.taxTotal())).isEqualTo("0");
        assertThat(s(r.gross())).isEqualTo("100");
    }

    @Test
    @DisplayName("stacks a compound tax on top of a prior tax")
    void compound() {
        LineTaxResult r = svc.computeLine(d(100), List.of(tax("a", 10, false, false, null),
                tax("b", 10, false, true, null)), null);
        assertThat(s(r.taxTotal())).isEqualTo("21");
    }

    @Test
    @DisplayName("does NOT fold a withholding tax into output tax / gross")
    void withholding() {
        LineTaxResult r = svc.computeLine(d(100), List.of(tax("wht", 6, false, false, "withholding")), null);
        assertThat(s(r.net())).isEqualTo("100");
        assertThat(s(r.taxTotal())).isEqualTo("0");
        assertThat(s(r.gross())).isEqualTo("100");
        assertThat(s(r.withholdingTotal())).isEqualTo("6");
        assertThat(r.withholdingBreakdown()).hasSize(1);
        TaxAmount wht = r.withholdingBreakdown().getFirst();
        assertThat(wht.taxId()).isEqualTo("wht");
        assertThat(wht.amount()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("computes VAT and withholding on one line independently")
    void vatAndWithholding() {
        LineTaxResult r = svc.computeLine(d(100), List.of(tax("vat", 18, false, false, "vat"),
                tax("wht", 6, false, false, "withholding")), null);
        assertThat(s(r.taxTotal())).isEqualTo("18");
        assertThat(s(r.gross())).isEqualTo("118");
        assertThat(s(r.withholdingTotal())).isEqualTo("6");
    }

    // ---- R13: inclusive is per tax, and backing an inclusive tax out respects compounding ----

    static TaxLike tx(String id, String rate, boolean inclusive, boolean compound) {
        return new TaxLike(id, d(rate), inclusive, compound, null);
    }

    /** One table row: input amount, taxes in order, currency scale; expected net, per-tax amounts and gross. */
    record Row(String name, String amount, List<TaxLike> taxes, Integer scale, String net, List<String> amounts,
            String gross) {}

    static List<Row> table() {
        return List.of(
                new Row("simple exclusive", "100", List.of(tx("vat", "18", false, false)), 2, "100", List.of("18"),
                        "118"),
                new Row("simple inclusive", "118", List.of(tx("vat", "18", true, false)), 2, "100", List.of("18"),
                        "118"),
                new Row("inclusive, currency without decimals", "20000", List.of(tx("vat", "18", true, false)), 0,
                        "16949", List.of("3051"), "20000"),
                new Row("non-compound inclusive group", "115", List.of(tx("a", "10", true, false),
                        tx("b", "5", true, false)), 2, "100", List.of("10", "5"), "115"),
                new Row("compound exclusive", "100", List.of(tx("a", "10", false, false),
                        tx("b", "10", false, true)), 2, "100", List.of("10", "11"), "121"),
                // was: 121 / 1.20 -> net 99.83, taxes 10.08 + 11.09
                new Row("compound inclusive", "121", List.of(tx("a", "10", true, false),
                        tx("b", "10", true, true)), 2, "100", List.of("10", "11"), "121"),
                new Row("compound inclusive, legacy 6 dp", "121", List.of(tx("a", "10", true, false),
                        tx("b", "10", true, true)), null, "100", List.of("10", "11"), "121"),
                // was: the exclusive levy was backed out of the shelf price too (118 / 1.20)
                new Row("inclusive VAT + exclusive levy", "118", List.of(tx("vat", "18", true, false),
                        tx("levy", "2", false, false)), 2, "100", List.of("18", "2"), "120"),
                new Row("exclusive excise, then compound inclusive VAT", "119.80", List.of(
                        tx("excise", "10", false, false), tx("vat", "18", true, true)), 2, "100",
                        List.of("10", "19.80"), "129.80"),
                new Row("inclusive excise, then compound exclusive VAT", "110", List.of(
                        tx("excise", "10", true, false), tx("vat", "18", false, true)), 2, "100",
                        List.of("10", "19.80"), "129.80"),
                // 100 / 1.155 = 86.580087 -> 8.66, (86.580087 + 8.66) x 5 % = 4.76; net absorbs the rounding
                new Row("compound inclusive with rounding", "100.00", List.of(tx("a", "10", true, false),
                        tx("b", "5", true, true)), 2, "86.58", List.of("8.66", "4.76"), "100"));
    }

    @TestFactory
    @DisplayName("computes mixed inclusive / exclusive and compound taxes per tax")
    Stream<DynamicTest> table_driven() {
        return table().stream().map(row -> DynamicTest.dynamicTest(row.name(), () -> {
            LineTaxResult r = svc.computeLine(d(row.amount()), row.taxes(), row.scale());
            assertThat(r.net()).as("net").isEqualByComparingTo(row.net());
            assertThat(r.breakdown()).as("breakdown").hasSize(row.amounts().size());
            for (int i = 0; i < row.amounts().size(); i++) {
                assertThat(r.breakdown().get(i).taxId()).isEqualTo(row.taxes().get(i).id());
                assertThat(r.breakdown().get(i).amount()).as(row.taxes().get(i).id())
                        .isEqualByComparingTo(row.amounts().get(i));
            }
            assertThat(r.gross()).as("gross").isEqualByComparingTo(row.gross());
            assertThat(r.gross()).isEqualByComparingTo(r.net().add(r.taxTotal()));
        }));
    }

    @Test
    @DisplayName("with a currency scale the inclusive shelf price is reproduced exactly: net + inclusive taxes")
    void inclusiveShelfPriceExact() {
        List<TaxLike> taxes = List.of(tx("excise", "12.5", true, false), tx("vat", "18", true, true),
                tx("levy", "1.5", false, false));
        for (String amount : List.of("0.01", "9.99", "100", "1234.57", "99999.99")) {
            LineTaxResult r = svc.computeLine(d(amount), taxes, 2);
            BigDecimal inclusive = r.breakdown().get(0).amount().add(r.breakdown().get(1).amount());
            assertThat(r.net().add(inclusive)).as(amount).isEqualByComparingTo(amount);
            assertThat(r.gross()).as(amount).isEqualByComparingTo(d(amount).add(r.breakdown().get(2).amount()));
        }
    }

    @Test
    @DisplayName("round trip: backing the inclusive taxes out of net + inclusive taxes gives the net back")
    void roundTrip() {
        List<List<TaxLike>> configs = List.of(
                List.of(tx("a", "18", true, false)),
                List.of(tx("a", "10", true, false), tx("b", "10", true, true)),
                List.of(tx("a", "10", false, false), tx("b", "18", true, true)),
                List.of(tx("a", "10", true, false), tx("b", "18", false, true)),
                List.of(tx("a", "5", true, false), tx("b", "2", false, false), tx("c", "16", true, true)),
                List.of(tx("a", "16", true, false), tx("b", "10", false, true), tx("c", "5", true, true)));
        for (List<TaxLike> config : configs) {
            List<TaxLike> exclusive = new ArrayList<>();
            config.forEach(t -> exclusive.add(new TaxLike(t.id(), t.rate(), false, t.isCompound(), t.type())));
            for (String net : List.of("100", "250.5", "9999", "0.4")) {
                // Forward: every tax on top of the net; the shelf price holds the net and the inclusive taxes.
                LineTaxResult forward = svc.computeLine(d(net), exclusive, null);
                BigDecimal shelf = d(net);
                for (int i = 0; i < config.size(); i++) {
                    if (config.get(i).isInclusive()) {
                        shelf = shelf.add(forward.breakdown().get(i).amount());
                    }
                }
                LineTaxResult back = svc.computeLine(shelf, config, null);
                String label = config + " @ " + net;
                assertThat(back.net()).as(label).isEqualByComparingTo(net);
                assertThat(back.gross()).as(label).isEqualByComparingTo(forward.gross());
                for (int i = 0; i < config.size(); i++) {
                    assertThat(back.breakdown().get(i).amount()).as(label)
                            .isEqualByComparingTo(forward.breakdown().get(i).amount());
                }
            }
        }
    }

    @Test
    @DisplayName("withholding is computed on the net backed out of an inclusive price")
    void withholdingOnInclusiveNet() {
        LineTaxResult r = svc.computeLine(d(118), List.of(tax("vat", 18, true, false, "vat"),
                tax("wht", 6, false, false, "withholding")), 2);
        assertThat(r.net()).isEqualByComparingTo("100");
        assertThat(r.taxTotal()).isEqualByComparingTo("18");
        assertThat(r.gross()).isEqualByComparingTo("118");
        assertThat(r.withholdingTotal()).isEqualByComparingTo("6");
    }
}
