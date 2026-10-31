package com.inventoryerp.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsViewTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);

    private static AnalyticsView view(AnalyticsFilter f, String[] branches) {
        return new AnalyticsView(f, DAY, DAY, branches, null);
    }

    @Test
    void filterReadsTheQueryStringAndTreatsBlankAsAbsent() {
        AnalyticsFilter f = AnalyticsFilter.of(Map.of("from", "2026-10-01T00:00:00Z", "to", "2026-10-31", "branchId",
                " ", "partnerId", "p1"));
        assertThat(f.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(f.to()).isEqualTo(DAY);
        assertThat(f.branchId()).isNull();
        assertThat(f.partnerId()).isEqualTo("p1");
    }

    @Test
    void badDateIsABadRequest() {
        assertThatThrownBy(() -> AnalyticsFilter.of(Map.of("from", "not-a-date"))).isInstanceOf(ApiException.class);
    }

    @Test
    void branchClauseAppearsOnlyWhenLimited() {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        view(AnalyticsFilter.of(Map.of()), null).branch(where, params, "x");
        assertThat(where).isEmpty();
        view(AnalyticsFilter.of(Map.of()), new String[] {}).branch(where, params, "x");
        assertThat(where.toString()).isEqualTo(" AND x = ANY(?::text[])");
        assertThat(params).hasSize(1);
    }

    @Test
    void ledgerEndCoversTheLastDay() {
        assertThat(view(AnalyticsFilter.of(Map.of()), null).ledgerTo()).isEqualTo("2026-10-31T23:59:59.999Z");
    }

    @Test
    void onlyFiltersWithAValueAreReportedAsIgnored() {
        AnalyticsFilter f = AnalyticsFilter.of(Map.of("productId", "p", "branchId", "b"));
        assertThat(view(f, null).ignored(List.of("date", "branch"))).containsExactly("product");
        assertThat(view(f, null).ignored(List.of("date", "branch", "product"))).isEmpty();
    }
}
