package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.invoicing.application.TaxCalculationService;
import com.inventoryerp.invoicing.application.TaxCalculationService.LineTaxResult;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxAmount;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxLike;
import com.inventoryerp.kernel.common.JsNumbers;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code tax-calculation.service.spec.ts}. */
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
}
