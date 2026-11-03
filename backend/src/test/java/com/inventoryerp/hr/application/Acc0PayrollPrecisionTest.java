package com.inventoryerp.hr.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ACC-0 reproduction (finding B12): the legacy payroll path computes progressive tax in {@code double}. Lives in the
 * service's package because {@code computeProgressive} is package-private. Pure unit test, no Spring context.
 */
class Acc0PayrollPrecisionTest {

    @Test
    @DisplayName("[B12][owner:accounting][ACC-3] progressive tax on 0.30 at 10 % is exactly 0.03")
    void progressiveTaxIsExact() {
        double tax = HrPayrollService.computeProgressive(0.3, List.of(Map.of("fromAmount", 0, "rate", 0.1)));
        assertThat(new BigDecimal(Double.toString(tax))).as("raw progressive tax").isEqualByComparingTo("0.03");
    }

    @Test
    @DisplayName("[B12][owner:accounting][ACC-3] bracketed tax on 1,234,567.89 equals the decimal result")
    void bracketedTaxIsExact() {
        List<Map<String, Object>> brackets = List.of(
                Map.of("fromAmount", 0, "toAmount", 235000, "rate", 0),
                Map.of("fromAmount", 235000, "toAmount", 335000, "rate", 0.1),
                Map.of("fromAmount", 335000, "toAmount", 410000, "rate", 0.2),
                Map.of("fromAmount", 410000, "rate", 0.3));
        double tax = HrPayrollService.computeProgressive(1234567.89, brackets);
        // 100,000 × 0.1 + 75,000 × 0.2 + 824,567.89 × 0.3 = 10,000 + 15,000 + 247,370.367 = 272,370.367
        assertThat(new BigDecimal(Double.toString(tax))).as("raw bracketed tax").isEqualByComparingTo("272370.367");
    }
}
