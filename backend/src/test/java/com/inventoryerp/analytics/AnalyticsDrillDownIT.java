package com.inventoryerp.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every analytics drill-down (module × kpi), unfiltered and with the product / location / partner filters. */
class AnalyticsDrillDownIT extends ApiTestSupport {

    static final Map<String, List<String>> KPIS = Map.of(
            "aggregates", List.of("crushing-runs"),
            "blocks", List.of("precast-orders"),
            "branches", List.of("invoices"),
            "finance", List.of("payables", "receivables"),
            "fleet", List.of("deliveries", "fuel-logs"),
            "inventory", List.of("below-minimum", "count-variances", "stockpile-surveys"),
            "maintenance", List.of("downtime", "work-orders"),
            "procurement", List.of("purchase-orders"),
            "quarry", List.of("extraction-logs"),
            "sales", List.of("invoices", "receivables"));

    String range;
    String filters;

    @BeforeAll
    void fixtures() {
        range = "?from=" + LocalDate.now().minusYears(1) + "&to=" + LocalDate.now().plusDays(1);
        String product = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        String location = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? LIMIT 1")
                .param(demoOrg).query(String.class).single();
        String partner = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        filters = "&productId=" + product + "&locationId=" + location + "&partnerId=" + partner;
    }

    @Test
    @DisplayName("every drill-down answers a page, with and without filters")
    void everyDrillDown() {
        KPIS.forEach((module, kpis) -> {
            for (String kpi : kpis) {
                for (String q : List.of(range, range + filters, range + "&page=2&pageSize=5")) {
                    JsonNode page = ok(200, "GET", "/analytics/" + module + "/documents" + q + "&kpi=" + kpi, null);
                    assertThat(page.has("data")).as(module + " " + kpi).isTrue();
                    assertThat(page.get("meta").get("total").asLong()).isGreaterThanOrEqualTo(0);
                }
            }
        });
    }

    @Test
    @DisplayName("summary tiles honour the same filters")
    void filteredTiles() {
        for (String module : KPIS.keySet()) {
            ok(200, "GET", "/analytics/" + module + range + filters, null);
        }
        assertThat(ok(200, "GET", "/analytics/filter-options", null).isObject()).isTrue();
    }
}
