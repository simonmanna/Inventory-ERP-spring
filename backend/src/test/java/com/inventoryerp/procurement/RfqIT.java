package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.RfqController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PROC-1: RFQ life cycle, quotation comparison in base currency, award to a purchase order. */
class RfqIT extends ApiTestSupport {

    String warehouse;
    String cheap;
    String dear;
    String uninvited;

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(id, demoOrg, "RQ-" + suffix(), name + " " + suffix()).update();
        return id;
    }

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "RQ-" + suffix(), "name", "RFQ " + suffix(),
                "type", "warehouse")).get("id").asString();
        cheap = supplier("Cheap");
        dear = supplier("Dear");
        uninvited = supplier("Uninvited");
    }

    JsonNode draft() {
        return ok(200, "POST", "/procurement/rfqs", Map.of("title", "Spare parts", "currencyCode", "UGX", "lines",
                List.of(Map.of("description", "Jaw plate", "quantity", 2), Map.of("description", "Conveyor belt",
                        "quantity", 10)), "supplierIds", List.of(cheap, dear)));
    }

    JsonNode sent() {
        return ok(200, "POST", "/procurement/rfqs/" + draft().get("id").asString() + "/send", null);
    }

    JsonNode quote(JsonNode rfq, String partner, Object first, Object second, Map<String, Object> extra) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        lines.add(Map.of("rfqLineId", rfq.get("lines").get(0).get("id").asString(), "unitPrice", first));
        if (second != null) {
            lines.add(Map.of("rfqLineId", rfq.get("lines").get(1).get("id").asString(), "unitPrice", second));
        }
        Map<String, Object> body = new LinkedHashMap<>(extra);
        body.put("partnerId", partner);
        body.put("lines", lines);
        return ok(200, "POST", "/procurement/rfqs/" + rfq.get("id").asString() + "/quotations", body);
    }

    Res award(String rfqId, String quotationId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("quotationId", quotationId, "warehouseId",
                warehouse), h);
    }

    String quotationOf(JsonNode rfq, String partner) {
        for (JsonNode q : rfq.get("quotations")) {
            if (partner.equals(q.get("partnerId").asString())) {
                return q.get("id").asString();
            }
        }
        throw new AssertionError("no quotation");
    }

    @Test
    @DisplayName("life cycle: a draft edits, send needs a supplier, a sent RFQ is frozen, cancel ends it")
    void lifeCycle() {
        JsonNode noSuppliers = ok(200, "POST", "/procurement/rfqs", Map.of("title", "Empty", "currencyCode", "UGX",
                "lines",
                List.of(Map.of("description", "Fuel filter", "quantity", 1))));
        assertThat(noSuppliers.get("rfqNumber").asString()).startsWith("RFQ-");
        assertThat(noSuppliers.get("status").asString()).isEqualTo("draft");
        String id = noSuppliers.get("id").asString();
        assertThat(as("POST", "/procurement/rfqs/" + id + "/send", null).status()).isEqualTo(400);

        JsonNode edited = ok(200, "PATCH", "/procurement/rfqs/" + id, Map.of("title", "Filters", "currencyCode", "UGX",
                "lines", List.of(Map.of("description", "Fuel filter", "quantity", 3)), "supplierIds", List.of(cheap)));
        assertThat(edited.get("title").asString()).isEqualTo("Filters");
        assertThat(edited.get("suppliers")).hasSize(1);

        assertThat(ok(200, "POST", "/procurement/rfqs/" + id + "/send",
                null).get("status").asString()).isEqualTo("sent");
        assertThat(as("PATCH", "/procurement/rfqs/" + id, Map.of("title", "Late", "currencyCode", "UGX", "lines",
                List.of(Map.of("description", "X", "quantity", 1)))).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/procurement/rfqs/" + id + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(as("POST", "/procurement/rfqs/" + id + "/cancel", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("comparison: base currency, cheapest per line, and an incomplete or expired quote is not recommended")
    void comparison() {
        JsonNode rfq = sent();
        String id = rfq.get("id").asString();
        // 100 / 50 at rate 2 is 200 / 100 in base: total 2*200 + 10*100 = 1400.
        JsonNode one = quote(rfq, dear, 100, 50, Map.of("currencyCode", "USD", "exchangeRate", 2));
        quote(rfq, cheap, 120, 90, Map.of());
        JsonNode cmp = ok(200, "GET", "/procurement/rfqs/" + id + "/comparison", null);

        String cheapQuote = null;
        String dearQuote = null;
        for (JsonNode q : cmp.get("quotations")) {
            if (cheap.equals(q.get("partnerId").asString())) {
                cheapQuote = q.get("quotationId").asString();
                assertThat(new BigDecimal(q.get("baseTotal").asString())).isEqualByComparingTo("1140");
            } else {
                dearQuote = q.get("quotationId").asString();
                assertThat(new BigDecimal(q.get("baseTotal").asString())).isEqualByComparingTo("1400");
            }
        }
        assertThat(cmp.get("recommendedQuotationId").asString()).isEqualTo(cheapQuote);
        for (JsonNode cell : cmp.get("lines").get(0).get("quotes")) {
            assertThat(cell.get("cheapest").asBoolean())
                    .isEqualTo(cheapQuote.equals(cell.get("quotationId").asString()));
        }
        assertThat(one.get("quotations")).hasSize(1);

        // Recording again replaces: the cheap supplier drops a line, so only the dear quote is complete.
        quote(rfq, cheap, 120, null, Map.of());
        JsonNode partial = ok(200, "GET", "/procurement/rfqs/" + id + "/comparison", null);
        assertThat(partial.get("quotations")).hasSize(2);
        assertThat(partial.get("recommendedQuotationId").asString()).isEqualTo(dearQuote);

        quote(rfq, dear, 100, 50, Map.of("validUntil", LocalDate.now().minusDays(1).toString()));
        assertThat(ok(200, "GET", "/procurement/rfqs/" + id + "/comparison",
                null).get("recommendedQuotationId").isNull())
                .isTrue();
    }

    @Test
    @DisplayName("a quote needs an invited supplier and lines of this RFQ")
    void quoteValidation() {
        JsonNode rfq = sent();
        String path = "/procurement/rfqs/" + rfq.get("id").asString() + "/quotations";
        String line = rfq.get("lines").get(0).get("id").asString();
        assertThat(as("POST", path, Map.of("partnerId", uninvited, "lines", List.of(Map.of("rfqLineId", line,
                "unitPrice",
                5)))).status()).isEqualTo(400);
        assertThat(as("POST", path, Map.of("partnerId", cheap, "lines", List.of(Map.of("rfqLineId",
                UUID.randomUUID().toString(), "unitPrice", 5)))).status()).isEqualTo(400);
        assertThat(as("POST", path, Map.of("partnerId", cheap, "lines", List.of(Map.of("rfqLineId", line, "unitPrice",
                5),
                Map.of("rfqLineId", line, "unitPrice", 6)))).status()).isEqualTo(400);
        assertThat(as("POST", "/procurement/rfqs/" + draft().get("id").asString() + "/quotations", Map.of("partnerId",
                cheap, "lines", List.of(Map.of("rfqLineId", line, "unitPrice", 5)))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("award raises a PO at the quoted prices, closes the RFQ and needs an Idempotency-Key")
    void awardRaisesPurchaseOrder() {
        JsonNode rfq = sent();
        String id = rfq.get("id").asString();
        JsonNode quoted = quote(rfq, cheap, 120, 90, Map.of("leadTimeDays", 7, "paymentTerms", "Net 30"));
        String quotation = quotationOf(quoted, cheap);

        Res noKey = as("POST", "/procurement/rfqs/" + id + "/award", Map.of("quotationId", quotation, "warehouseId",
                warehouse));
        assertThat(noKey.status()).isEqualTo(400);

        Res awarded = award(id, quotation);
        assertThat(awarded.status()).as(awarded.raw().body()).isEqualTo(200);
        assertThat(awarded.body().get("status").asString()).isEqualTo("awarded");
        String po = awarded.body().get("purchaseOrderId").asString();

        JsonNode order = ok(200, "GET", "/procurement/purchase-orders/" + po, null);
        assertThat(order.get("partnerId").asString()).isEqualTo(cheap);
        assertThat(order.get("subtotal").asString()).isEqualTo("1140");
        assertThat(order.get("terms").asString()).isEqualTo("Net 30");
        assertThat(order.get("lines")).hasSize(2);

        assertThat(award(id, quotation).status()).isEqualTo(400);
        assertThat(as("POST", "/procurement/rfqs/" + id + "/cancel", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("an expired quotation cannot be awarded")
    void expiredAward() {
        JsonNode rfq = sent();
        JsonNode quoted = quote(rfq, cheap, 1, 1, Map.of("validUntil", LocalDate.now().minusDays(1).toString()));
        assertThat(award(rfq.get("id").asString(), quotationOf(quoted, cheap)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("tenant isolation and authorization")
    void isolation() {
        String id = draft().get("id").asString();
        assertThat(call("GET", "/procurement/rfqs/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/procurement/rfqs/" + id + "/send", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/rfqs", null, otherToken).body().get("data")).isEmpty();
        assertThat(assertAuthorizationMatrix(RfqController.class)).isEqualTo(9);
    }
}
