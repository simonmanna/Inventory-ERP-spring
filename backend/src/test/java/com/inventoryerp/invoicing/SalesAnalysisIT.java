package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SALES-5: posted sales by product and customer, with filters. */
class SalesAnalysisIT extends ApiTestSupport {

    @Test
    @DisplayName("posted invoices count, drafts do not, grouping and customer filter work")
    void analysis() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String today = LocalDate.now().toString();
        String customer = ok(201, "POST", "/partners", Map.of("code", "SA-" + s, "name", "Analysis " + s, "isCustomer",
                true)).get("id").asString();
        String product = ok(201, "POST", "/products", Map.of("code", "SAP-" + s, "name", "Analysis svc " + s,
                "productType", "service", "salesPrice", 10, "costPrice", 0)).get("id").asString();
        JsonNode posted = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", 3, "unitPrice", 10))));
        ok(200, "POST", "/invoices/" + posted.get("id").asString() + "/post", null);
        ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", 7, "unitPrice", 10))));
        JsonNode r = ok(200, "GET", "/reports/ar/sales-analysis?groupBy=product&partnerId=" + customer, null);
        Map<String, JsonNode> rows = new HashMap<>();
        r.get("rows").forEach(x -> rows.put(x.get("key").asString(), x));
        assertThat(rows.get(product).get("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.get("totalAmount").decimalValue()).isEqualByComparingTo("30");
        for (String g : List.of("category", "branch", "customer", "salesperson", "month")) {
            assertThat(as("GET", "/reports/ar/sales-analysis?groupBy=" + g + "&from=" + today + "&to=" + today, null)
                    .status()).isEqualTo(200);
        }
        assertThat(as("GET", "/reports/ar/sales-analysis?groupBy=nope", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("another organization sees none of it")
    void tenantIsolation() {
        assertThat(call("GET", "/reports/ar/sales-analysis?groupBy=branch", null, otherToken).body().get("totalAmount")
                .decimalValue()).isEqualByComparingTo("0");
    }
}
