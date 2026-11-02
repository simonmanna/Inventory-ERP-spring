package com.inventoryerp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.reporting.application.FormulaEvaluator;
import com.inventoryerp.reporting.application.ReportPeriods;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-0 pure logic: period presets, comparative periods and statement formulas. */
class ReportingUnitTest {

    static final ReportDefinition RANGE = new ReportDefinition("t.range", ReportCategory.FINANCIAL_STATEMENTS, "R",
            null, "FINANCIAL", "report:accounting", List.of(FilterKey.PERIOD, FilterKey.COMPARE), true, null, null);
    static final ReportDefinition POINT = new ReportDefinition("t.point", ReportCategory.FINANCIAL_STATEMENTS, "P",
            null, "FINANCIAL", "report:accounting", List.of(FilterKey.AS_OF, FilterKey.COMPARE), true, null, null);
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    static ReportRequest req(String period, String from, String to, String asOf) {
        return new ReportRequest(period, from, to, asOf, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    @Test
    @DisplayName("presets resolve against the business date; default is this month")
    void presets() {
        assertThat(ReportPeriods.current(RANGE, req(null, null, null, null), TODAY))
                .isEqualTo(new ReportPeriod("CURRENT", "Nov 2026", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11,
                        30)));
        ReportPeriod q = ReportPeriods.current(RANGE, req("LAST_QUARTER", null, null, null), TODAY);
        assertThat(q.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(q.to()).isEqualTo(LocalDate.of(2026, 9, 30));
        ReportPeriod y = ReportPeriods.current(RANGE, req("THIS_YEAR", null, null, null), TODAY);
        assertThat(y.label()).isEqualTo("FY 2026");
        assertThat(ReportPeriods.current(POINT, req("LAST_MONTH", null, null, null), TODAY))
                .isEqualTo(new ReportPeriod("CURRENT", "As of 31 Oct 2026", null, LocalDate.of(2026, 10, 31)));
        assertThat(ReportPeriods.current(POINT, req("THIS_MONTH", null, null, null), TODAY).to()).isEqualTo(TODAY);
        assertThat(ReportPeriods.current(POINT, req(null, null, null, "2026-06-15"), TODAY).to())
                .isEqualTo(LocalDate.of(2026, 6, 15));
        assertThatThrownBy(() -> ReportPeriods.current(RANGE, req("NEXT_DECADE", null, null, null), TODAY))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReportPeriods.current(RANGE, req(null, "2026-05-02", "2026-05-01", null), TODAY))
                .hasMessage("from must be on or before to");
        assertThatThrownBy(() -> ReportPeriods.current(RANGE, req(null, "May", null, null), TODAY))
                .hasMessage("Invalid `from` date: May");
    }

    @Test
    @DisplayName("prior period keeps whole months; prior year keeps month ends; point-in-time shifts the date")
    void comparatives() {
        ReportPeriod q3 = new ReportPeriod("CURRENT", "x", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        List<ReportPeriod> c = ReportPeriods.comparatives(q3, List.of("PRIOR_PERIOD", "PRIOR_YEAR", "VARIANCE"));
        assertThat(c).extracting(ReportPeriod::key).containsExactly("PRIOR_PERIOD", "PRIOR_YEAR");
        assertThat(c.get(0).from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(c.get(0).to()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(c.get(1).from()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(c.get(1).to()).isEqualTo(LocalDate.of(2025, 9, 30));

        ReportPeriod days = new ReportPeriod("CURRENT", "x", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 19));
        ReportPeriod prior = ReportPeriods.comparatives(days, List.of("PRIOR_PERIOD")).getFirst();
        assertThat(prior.from()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(prior.to()).isEqualTo(LocalDate.of(2026, 3, 9));

        ReportPeriod feb = new ReportPeriod("CURRENT", "x", null, LocalDate.of(2028, 2, 29));
        assertThat(ReportPeriods.comparatives(feb, List.of("PRIOR_YEAR")).getFirst().to())
                .isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(ReportPeriods.comparatives(feb, List.of("PRIOR_PERIOD")).getFirst().to())
                .isEqualTo(LocalDate.of(2028, 1, 31));
        assertThatThrownBy(() -> ReportPeriods.comparatives(q3, List.of("BUDGET")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("formulas: precedence, parentheses, unary minus, blank on division by zero, references")
    void formulas() {
        Map<String, BigDecimal> v = Map.of("GP", new BigDecimal("400"), "NET_REV", new BigDecimal("1000"), "DEP",
                new BigDecimal("-50"), "ZERO", BigDecimal.ZERO);
        assertThat(FormulaEvaluator.evaluate("GP / NET_REV * 100", v::get)).isEqualByComparingTo("40");
        assertThat(FormulaEvaluator.evaluate("GP - DEP", v::get)).isEqualByComparingTo("450");
        assertThat(FormulaEvaluator.evaluate("-(GP + DEP) * 2", v::get)).isEqualByComparingTo("-700");
        assertThat(FormulaEvaluator.evaluate("gp + 1.5", v::get)).isEqualByComparingTo("401.5");
        assertThat(FormulaEvaluator.evaluate("GP / ZERO", v::get)).isNull();
        assertThat(FormulaEvaluator.evaluate("MISSING + GP", c -> v.get(c))).isEqualByComparingTo("400");
        assertThat(FormulaEvaluator.references("A + B * (C - A)")).containsExactly("A", "B", "C");
        assertThatThrownBy(() -> FormulaEvaluator.evaluate("GP +", v::get)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FormulaEvaluator.evaluate("GP $ 2", v::get)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FormulaEvaluator.evaluate("(GP", v::get)).hasMessageContaining("missing )");
        assertThatThrownBy(() -> FormulaEvaluator.validate("GP + X", v)).hasMessageContaining("unknown line X");
    }
}
