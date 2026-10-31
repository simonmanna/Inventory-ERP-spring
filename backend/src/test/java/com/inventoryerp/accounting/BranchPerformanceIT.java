package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.web.BranchPerformanceController;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** BR-2: branch comparison and its consolidated total. */
class BranchPerformanceIT extends ApiTestSupport {

    @Test
    @DisplayName("one row per branch, stock by the location's branch, total adds up")
    void comparison() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String branch = ok(201, "POST", "/branches", Map.of("code", "BP-" + s, "name", "Perf " + s)).get("id").asString();
        String yard = ok(201, "POST", "/inventory/locations", Map.of("code", "BPY-" + s, "name", "Perf yard " + s,
                "type", "warehouse", "branchId", branch)).get("id").asString();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 10,"
                + " now())").params(product, demoOrg, "BPP-" + s, "Perf product " + s).update();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                + " \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, 5, 10, now())")
                .params(UUID.randomUUID().toString(), demoOrg, product, yard).update();
        JsonNode r = ok(200, "GET", "/reports/branches/comparison", null);
        Map<String, JsonNode> rows = new HashMap<>();
        r.get("rows").forEach(x -> rows.put(x.get("branchId").asString(), x));
        assertThat(rows.get(branch).get("stockValue").decimalValue()).isEqualByComparingTo("50");
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (JsonNode x : r.get("rows")) {
            sum = sum.add(x.get("stockValue").decimalValue());
        }
        assertThat(r.get("total").get("stockValue").decimalValue()).isEqualByComparingTo(sum);
    }

    @Test
    @DisplayName("authorization matrix")
    void authorization() {
        assertThat(assertAuthorizationMatrix(BranchPerformanceController.class)).isEqualTo(1);
    }
}
