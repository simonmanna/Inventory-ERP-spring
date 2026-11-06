package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-0: defects found by the Sales gap audit, each pinned by a test written before its fix. */
class SalesStabilizationIT extends ApiTestSupport {

    String product;

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String today() {
        return LocalDate.now().toString();
    }

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "SG0-" + sfx(), "name", "SG0 service " + sfx(),
                "productType", "service", "salesPrice", 100, "costPrice", 40)).get("id").asString();
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SG0C-" + sfx(), "SG0 cust " + sfx()).update();
        return id;
    }

    void namedList(String customer, int price) {
        ok(201, "POST", "/price-lists", Map.of("code", "SG0L-" + sfx(), "name", "SG0 list " + sfx(), "partnerIds",
                List.of(customer), "items", List.of(Map.of("productId", product, "unitPrice", price))));
    }

    JsonNode money(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call(method, path, body, h);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    @Test
    @DisplayName("#1 a sales order line priced from a named price list saves")
    void orderFromNamedList() {
        String c = customer();
        namedList(c, 70);
        JsonNode o = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", product,
                "quantity", 2))));
        JsonNode line = o.get("lines").get(0);
        assertThat(line.get("priceSource").asString()).isEqualTo("price_list");
        assertThat(line.get("unitPrice").asDouble()).isEqualTo(70);
    }

    @Test
    @DisplayName("#1 a quotation line priced from a named price list saves")
    void quotationFromNamedList() {
        String c = customer();
        namedList(c, 70);
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 2))));
        assertThat(q.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(70);
    }

    @Test
    @DisplayName("#2 a proforma converted from a quotation opens read-only and cannot be posted")
    void proformaOpens() {
        String c = customer();
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 3, "unitPrice", 90))));
        String qid = q.get("id").asString();
        ok(200, "POST", "/sales/quotations/" + qid + "/submit", null);
        String pf = ok(200, "POST", "/sales/quotations/" + qid + "/convert", Map.of("target", "proforma"))
                .get("convertedId").asString();
        JsonNode doc = ok(200, "GET", "/invoices/" + pf, null);
        assertThat(doc.get("documentType").asString()).isEqualTo("proforma_invoice");
        assertThat(doc.get("lines").size()).isEqualTo(1);
        assertThat(as("POST", "/invoices/" + pf + "/post", null).status()).isEqualTo(404);
        JsonNode list = ok(200, "GET", "/invoices?partnerId=" + c, null).get("data");
        assertThat(list.size()).as("proforma is not a tax invoice").isZero();
    }

    JsonNode listRow(String partner, String id, String filter) {
        for (JsonNode row : ok(200, "GET", "/invoices?partnerId=" + partner + filter, null).get("data")) {
            if (row.get("id").asString().equals(id)) {
                return row;
            }
        }
        return null;
    }

    void assertStatus(String c, String inv, String payment, String settlement) {
        JsonNode row = listRow(c, inv, "");
        assertThat(row).isNotNull();
        assertThat(row.get("paymentStatus").asString()).isEqualTo(payment);
        assertThat(row.get("settlementStatus").asString()).isEqualTo(settlement);
        assertThat(listRow(c, inv, "&paymentStatus=" + payment)).as("found by its payment filter").isNotNull();
        assertThat(listRow(c, inv, "&settlementStatus=" + settlement)).as("found by its settlement filter").isNotNull();
    }

    @Test
    @DisplayName("#3 back-office invoice list shows unpaid, partially paid and paid, and filters by them")
    void invoiceListStatus() {
        String c = customer();
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today(), "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        assertStatus(c, inv, "unpaid", "unsettled");
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        assertStatus(c, inv, "unpaid", "unsettled");
        money("POST", "/payments", Map.of("partnerId", c, "paymentDate", today(), "amount", 40, "paymentMethod", "bank",
                "allocations", List.of(Map.of("documentId", inv, "amount", 40))));
        assertStatus(c, inv, "partially_paid", "partially_settled");
        money("POST", "/payments", Map.of("partnerId", c, "paymentDate", today(), "amount", 60, "paymentMethod", "bank",
                "allocations", List.of(Map.of("documentId", inv, "amount", 60))));
        assertStatus(c, inv, "paid", "settled");
        assertThat(listRow(c, inv, "&paymentStatus=unpaid")).as("no longer unpaid").isNull();
    }
}
