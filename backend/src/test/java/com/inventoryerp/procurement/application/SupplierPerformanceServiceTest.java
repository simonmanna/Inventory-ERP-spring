package com.inventoryerp.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PROC-4: the scorecard is a weighted mean over the measures a supplier has data for. */
class SupplierPerformanceServiceTest {

    static Map<String, Object> row(String onTime, String quality, String fill, String price) {
        Map<String, Object> m = new HashMap<>();
        m.put("onTimeRate", onTime == null ? null : new BigDecimal(onTime));
        m.put("qualityRate", quality == null ? null : new BigDecimal(quality));
        m.put("fillRate", fill == null ? null : new BigDecimal(fill));
        m.put("priceVarianceRate", price == null ? null : new BigDecimal(price));
        return m;
    }

    @Test
    @DisplayName("all four measures: 40 / 30 / 20 / 10; 2 % over the ordered price scores 80")
    void weighted() {
        assertThat(SupplierPerformanceService.score(row("50", "100", "100", "2")))
                .isEqualByComparingTo("78.0");
    }

    @Test
    @DisplayName("a missing measure drops out of the weights; paying less than ordered is not a bonus")
    void missingAndFavourable() {
        assertThat(SupplierPerformanceService.score(row("80", null, null, "-5"))).isEqualByComparingTo("84.0");
    }

    @Test
    @DisplayName("no data, no score")
    void none() {
        assertThat(SupplierPerformanceService.score(row(null, null, null, null))).isNull();
    }
}
