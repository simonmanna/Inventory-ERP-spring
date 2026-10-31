package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PROC-4: the supplier scorecard from real orders and receipts, plus the data behind the Purchases report tab. */
class SupplierPerformanceIT extends ApiTestSupport {

    String warehouse;
    String supplier;
    String product;

    // Fresh supplier and product per test: the scorecard counts every order of the supplier.
    @BeforeEach
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "SP-" + suffix(), "name", "Scorecard "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(supplier, demoOrg, "SP-" + suffix(), "Scorecard Supplier " + suffix()).update();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, "
                        + "'stockable', true,"
                        + " 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(product, demoOrg, "SP-" + suffix(), "Cement " + suffix()).update();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String order(int qty, int price, LocalDate expected) {
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "expectedDeliveryDate", expected.toString(), "lines",
                List.of(Map.of("productId", product, "description", "Cement", "quantity", qty, "unitPrice", price))))
                .get("id").asString();
    }

    void receive(String poId, int qty, int cost) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines",
                List.of(Map.of("productId", product, "description", "Cement", "quantity", qty, "unitCost", cost))), h);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
    }

    @Test
    @DisplayName("scorecard: on-time, fill rate, quality from inspection lots, price variance from three-way match")
    void scorecard() {
        LocalDate today = LocalDate.now();
        String onTime = order(10, 100, today.plusDays(5));
        receive(onTime, 10, 100);
        String late = order(10, 100, today.minusDays(2));
        receive(late, 5, 100);

        String lotPass = UUID.randomUUID().toString();
        String lotFail = UUID.randomUUID().toString();
        for (String[] lot : new String[][] {{lotPass, "pass"}, {lotFail, "fail"}}) {
            jdbc.sql("INSERT INTO \"QualityInspectionLot\" (id, \"organizationId\", \"lotNumber\", stage, "
                    + "\"productId\","
                            + " quantity, \"supplierPartnerId\", result) VALUES (?, ?, ?, 'incoming', ?, 1, ?, ?)")
                    .params(lot[0], demoOrg, "LOT-" + suffix(), product, supplier, lot[1]).update();
        }
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(onTime)
                .query(String.class).single();
        jdbc.sql("INSERT INTO \"ThreeWayMatch\" (id, \"organizationId\", \"purchaseOrderId\", \"purchaseOrderLineId\","
                        + " \"orderedQuantity\", \"billedQuantity\", \"orderedUnitPrice\", \"billedUnitPrice\", "
                        + "\"priceVariance\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, 10, 10, 100, 102, 2, now())")
                .params(UUID.randomUUID().toString(), demoOrg, onTime, poLine).update();

        JsonNode report = ok(200, "GET", "/procurement/reports/supplier-performance?partnerId=" + supplier, null);
        assertThat(report.get("rows")).hasSize(1);
        JsonNode row = report.get("rows").get(0);
        assertThat(row.get("orders").asInt()).isEqualTo(2);
        assertThat(row.get("receipts").asInt()).isEqualTo(2);
        assertThat(row.get("spend").asString()).isEqualTo("2000");
        assertThat(row.get("onTimeRate").asDouble()).isEqualTo(50.0);
        assertThat(row.get("avgDaysLate").asDouble()).isEqualTo(1.0);
        assertThat(row.get("fillRate").asDouble()).isEqualTo(75.0);
        assertThat(row.get("qualityRate").asDouble()).isEqualTo(50.0);
        assertThat(row.get("priceVarianceRate").asDouble()).isEqualTo(2.0);
        // (50*40 + 50*30 + 75*20 + 80*10) / 100
        assertThat(row.get("score").asDouble()).isEqualTo(58.0);

        assertThat(as("GET", "/procurement/reports/supplier-performance?from=2026-02-01&to=2026-01-01", null).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("Purchases report data: orders in the date range with their totals, none outside it")
    void purchasesReport() {
        LocalDate today = LocalDate.now();
        String po = order(4, 25, today.plusDays(1));
        JsonNode inRange = ok(200, "GET", "/procurement/purchase-orders?partnerId=" + supplier
                + "&pageSize=200&dateFrom="
                + today + "&dateTo=" + today, null);
        JsonNode found = null;
        for (JsonNode row : inRange.get("data")) {
            if (po.equals(row.get("id").asString())) {
                found = row;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.get("totalAmount").asString()).isEqualTo("100");
        JsonNode outside = ok(200, "GET", "/procurement/purchase-orders?partnerId=" + supplier + "&dateFrom=2001-01-01"
                + "&dateTo=2001-01-31", null);
        assertThat(outside.get("data")).isEmpty();
    }

    @Test
    @DisplayName("tenant isolation: another organization's scorecard does not list this supplier")
    void tenantIsolation() {
        order(1, 10, LocalDate.now().plusDays(1));
        Res other = call("GET", "/procurement/reports/supplier-performance?partnerId=" + supplier, null, otherToken);
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.body().get("rows")).isEmpty();
    }
}
