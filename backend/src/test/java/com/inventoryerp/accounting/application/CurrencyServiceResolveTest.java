package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Rate resolution (ADR-S054 D-PC1): a document's stored 1 is its "not stated" default, while a rate stated on the
 * request itself is honoured even when it is exactly 1 (null = not stated).
 */
class CurrencyServiceResolveTest {

    static final Instant DAY = Instant.parse("2026-11-01T10:00:00Z");

    /** No database: the table always answers 3,700. */
    static CurrencyService withTable() {
        return new CurrencyService(null, null) {
            @Override
            public BigDecimal findRate(String fromCode, String toCode, Instant asOf) {
                return new BigDecimal("3700");
            }
        };
    }

    @Test
    void statedRateOfOneIsHonoured() {
        CurrencyService.Resolution r = withTable().resolveStated("USD", "UGX", BigDecimal.ONE, DAY);
        assertThat(r.rate()).isEqualByComparingTo("1");
        assertThat(r.source()).isEqualTo("document");
    }

    @Test
    void notStatedFallsBackToTheTable() {
        CurrencyService.Resolution r = withTable().resolveStated("USD", "UGX", null, DAY);
        assertThat(r.rate()).isEqualByComparingTo("3700");
        assertThat(r.source()).isEqualTo("table");
    }

    @Test
    void statedRateOtherThanOneWinsInBothModes() {
        assertThat(withTable().resolveStated("USD", "UGX", "3650", DAY).rate()).isEqualByComparingTo("3650");
        assertThat(withTable().resolve("USD", "UGX", "3650", DAY).rate()).isEqualByComparingTo("3650");
    }

    @Test
    void documentDefaultOfOneStillMeansNotStated() {
        CurrencyService.Resolution r = withTable().resolve("USD", "UGX", BigDecimal.ONE, DAY);
        assertThat(r.rate()).isEqualByComparingTo("3700");
        assertThat(r.source()).isEqualTo("table");
    }

    @Test
    void nonPositiveStatedRateRefused() {
        assertThatThrownBy(() -> withTable().resolveStated("USD", "UGX", BigDecimal.ZERO, DAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("exchangeRate must be greater than zero");
    }
}
