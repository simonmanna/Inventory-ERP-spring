package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIN-3: the generic tax return pack lays the tax summary out as numbered boxes. */
class TaxPackIT extends ApiTestSupport {

    BigDecimal box(JsonNode pack, String label) {
        for (JsonNode b : pack.get("boxes")) {
            if (label.equals(b.get("label").asString())) {
                return new BigDecimal(b.get("amount").asString());
            }
        }
        throw new AssertionError("no box " + label);
    }

    @Test
    @DisplayName("a posted standard-rated sale moves sales and output tax by its net and tax, and net tax follows")
    void boxes() {
        String today = LocalDate.now().toString();
        String window = "?from=" + today + "&to=" + LocalDate.now().plusDays(1);
        JsonNode before = ok(200, "GET", "/reports/tax/pack" + window, null);
        assertThat(before.get("boxes")).hasSize(13);

        String account = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2200'")
                .param(demoOrg).query(String.class).single();
        String tax = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"vatCategory\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 18, ?, 'standard', now())")
                .params(tax, demoOrg, "Pack VAT " + tax, "P" + tax.substring(0, 6), account).update();
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(customer, demoOrg, "C-" + customer.substring(0, 8), "Pack Customer " + customer).update();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'Haulage', 'service', 100, 0,"
                        + " false, now())")
                .params(product, demoOrg, "PK-" + product.substring(0, 8)).update();
        Res draft = as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", 2, "unitPrice", 100, "taxId", tax))));
        assertThat(draft.status()).as(draft.raw().body()).isBetween(200, 201);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res post = call("POST", "/invoices/" + draft.body().get("id").asString() + "/post", null, h);
        assertThat(post.status()).as(post.raw().body()).isBetween(200, 201);

        JsonNode after = ok(200, "GET", "/reports/tax/pack" + window, null);
        assertThat(box(after, "Standard-rated sales").subtract(box(before, "Standard-rated sales")))
                .isEqualByComparingTo("200");
        assertThat(box(after, "Output tax").subtract(box(before, "Output tax"))).isEqualByComparingTo("36");
        assertThat(box(after, "Net tax payable (refundable if negative)")
                .subtract(box(before, "Net tax payable (refundable if negative)"))).isEqualByComparingTo("36");
        assertThat(after.has("glDifference")).isTrue();
    }
}
