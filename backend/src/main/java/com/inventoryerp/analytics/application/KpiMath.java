package com.inventoryerp.analytics.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** The two ratios every KPI uses. A KPI with no denominator is null (shown as a dash), never zero. */
public final class KpiMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private KpiMath() {}

    /** {@code part / whole × 100}; null unless {@code whole} is positive. */
    public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() <= 0 ? null
                : part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    /** {@code value / per}; null unless {@code per} is positive. */
    public static BigDecimal rate(BigDecimal value, BigDecimal per) {
        return per == null || per.signum() <= 0 ? null : value.divide(per, 4, RoundingMode.HALF_UP);
    }

    /** Sum of a column over report rows; missing values count as zero. */
    public static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            s = s.add(SqlRows.dec(r, key));
        }
        return s;
    }
}
