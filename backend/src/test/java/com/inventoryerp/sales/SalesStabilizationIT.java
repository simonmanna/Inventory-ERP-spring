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

    @Test
    @DisplayName("#6 the AR statement the customer tab reads includes a back-office invoice; the POS one never did")
    void customerStatementSource() {
        String c = customer();
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", today(), "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        assertThat(ok(200, "GET", "/pos/customers/" + c + "/statement", null).get("outstanding").asDouble()).isZero();
        JsonNode ar = ok(200, "GET", "/reports/ar/customers/" + c + "/statement", null);
        assertThat(ar.get("closingBalance").asDouble()).isEqualTo(100);
        assertThat(ar.get("lines").size()).isEqualTo(1);
        assertThat(ok(200, "GET", "/credit/partners/" + c + "/exposure", null).get("components").get("receivables")
                .asDouble()).isEqualTo(100);
    }

    @Test
    @DisplayName("#7 a foreign-currency order bills in its currency; its credit note follows; reports show base currency")
    void foreignCurrencyChain() {
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        Map<String, Object> currency = jdbc.sql("SELECT id, code FROM \"Currency\" WHERE code <> ? ORDER BY code LIMIT 1")
                .param(base).query().singleRow();
        String c = customer();
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "currencyCode", currency.get("code"),
                "exchangeRate", 4, "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDispatched\" = 1, \"qtyDelivered\" = 1, \"qtyAccepted\" = 1,"
                + " \"qtyBillable\" = 1 WHERE \"orderId\" = ?").param(so).update();
        String inv = money("POST", "/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        Map<String, Object> doc = jdbc.sql("SELECT \"currencyId\", \"exchangeRate\", \"totalAmount\" FROM \"Document\""
                + " WHERE id = ?").param(inv).query().singleRow();
        assertThat(doc.get("currencyId")).isEqualTo(currency.get("id"));
        assertThat((java.math.BigDecimal) doc.get("exchangeRate")).isEqualByComparingTo("4");
        assertThat((java.math.BigDecimal) doc.get("totalAmount")).isEqualByComparingTo("100");
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        assertThat(ok(200, "GET", "/reports/ar/customers/" + c + "/statement", null).get("closingBalance").asDouble())
                .as("AR in base currency").isEqualTo(400);

        String period = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now() + "&partnerId=" + c;
        JsonNode register = ok(200, "GET", "/sales/report-center/run/invoice-register?" + period, null);
        assertThat(register.get("totals").get("totalAmount").asDouble()).as("invoice register").isEqualTo(400);
        assertThat(register.get("totals").get("amountResidual").asDouble()).isEqualTo(400);
        JsonNode summary = ok(200, "GET", "/sales/report-center/run/customer-summary?" + period, null);
        assertThat(summary.get("rows").get(0).get("revenue").asDouble()).as("revenue fact").isEqualTo(400);
        JsonNode balances = ok(200, "GET", "/sales/report-center/run/outstanding-balances?partnerId=" + c, null);
        assertThat(balances.get("rows").get(0).get("invoices").asDouble()).as("outstanding balance").isEqualTo(400);

        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", c, "issueDate", today(), "reversedDocumentId",
                inv, "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        Map<String, Object> credit = jdbc.sql("SELECT \"currencyId\", \"exchangeRate\" FROM \"Document\" WHERE id = ?")
                .param(cn).query().singleRow();
        assertThat(credit.get("currencyId")).as("credit note follows its invoice").isEqualTo(currency.get("id"));
        assertThat((java.math.BigDecimal) credit.get("exchangeRate")).isEqualByComparingTo("4");
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(ok(200, "GET", "/reports/ar/customers/" + c + "/statement", null).get("closingBalance").asDouble())
                .as("fully credited in base currency").isZero();
    }

    String branch() {
        return ok(201, "POST", "/branches", Map.of("code", "SG0B-" + sfx(), "name", "SG0 branch " + sfx())).get("id")
                .asString();
    }

    List<String> searchTypes(String token, String q) {
        Res r = call("GET", "/search?q=" + q, null, token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        List<String> out = new java.util.ArrayList<>();
        r.body().get("data").forEach(h -> out.add(h.get("type").asString() + ":" + h.get("id").asString()));
        return out;
    }

    @Test
    @DisplayName("#8 search finds orders and quotations, and respects permissions and branch scope")
    void searchScope() {
        String c = customer();
        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", product,
                "quantity", 1))));
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 1))));
        assertThat(searchTypes(adminToken, so.get("number").asString())).contains("sales_order:" + so.get("id").asString());
        assertThat(searchTypes(adminToken, q.get("number").asString())).contains("quotation:" + q.get("id").asString());

        String mine = branch();
        String other = branch();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "SG0 search " + sfx(),
                        new String[] {"invoice:read"}).update();
        String email = "sg0-" + sfx() + "@demo.test";
        String user = createUser(demoOrg, email, "Search@12345", role);
        jdbc.sql("INSERT INTO \"UserDataScope\" (id, \"organizationId\", \"userId\", \"branchId\") VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, user, mine).update();
        String token = login("DEMO", email, "Search@12345");
        String inMine = invoiceIn(c, mine);
        String inOther = invoiceIn(c, other);
        String numberMine = number(inMine);
        assertThat(searchTypes(token, numberMine)).contains("invoice:" + inMine);
        assertThat(searchTypes(token, number(inOther))).doesNotContain("invoice:" + inOther);
        assertThat(searchTypes(token, so.get("number").asString())).as("no order permission").isEmpty();
        String partnerCode = jdbc.sql("SELECT code FROM \"Partner\" WHERE id = ?").param(c).query(String.class).single();
        assertThat(searchTypes(adminToken, partnerCode)).contains("partner:" + c);
        assertThat(searchTypes(token, partnerCode)).as("no partner permission").isEmpty();
    }

    String invoiceIn(String partner, String branch) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", today(), "lines", List.of(Map
                .of("productId", product, "quantity", 1, "unitPrice", 10)))).get("id").asString();
        jdbc.sql("UPDATE \"Document\" SET \"branchId\" = ? WHERE id = ?").params(branch, inv).update();
        return inv;
    }

    String number(String documentId) {
        return jdbc.sql("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ?").param(documentId).query(String.class)
                .single();
    }

    @Test
    @DisplayName("#9 a quotation keeps branch, terms, address and line unit, and hands them to the order")
    void quotationKeepsFields() {
        String c = customer();
        String b = branch();
        String term = ok(201, "POST", "/payment-terms", Map.of("code", "SG0T" + sfx(), "name", "Net 45", "netDays", 45))
                .get("id").asString();
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" LIMIT 1").query(String.class).single();
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ? WHERE id = ?").params(uom, product).update();
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "branchId", b, "paymentTermId", term,
                "deliveryAddress", "Gate 3", "lines", List.of(Map.of("productId", product, "quantity", 1, "uomId", uom))));
        assertThat(q.get("branchId").asString()).isEqualTo(b);
        assertThat(q.get("paymentTermId").asString()).isEqualTo(term);
        assertThat(q.get("deliveryAddress").asString()).isEqualTo("Gate 3");
        assertThat(q.get("lines").get(0).get("uomId").asString()).isEqualTo(uom);
        String id = q.get("id").asString();
        JsonNode edited = ok(200, "PUT", "/sales/quotations/" + id, Map.of("deliveryAddress", "Gate 4"));
        assertThat(edited.get("deliveryAddress").asString()).isEqualTo("Gate 4");
        ok(200, "POST", "/sales/quotations/" + id + "/submit", null);
        String so = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of()).get("convertedId").asString();
        JsonNode order = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(order.get("branchId").asString()).isEqualTo(b);
        assertThat(order.get("paymentTermId").asString()).isEqualTo(term);
        assertThat(order.get("deliveryAddress").asString()).isEqualTo("Gate 4");
        assertThat(order.get("lines").get(0).get("uomId").asString()).isEqualTo(uom);
    }

    @Test
    @DisplayName("#10 a branch's default price list prices quotation and order lines of that branch")
    void branchPriceOnDocuments() {
        String c = customer();
        String b = branch();
        String list = ok(201, "POST", "/sales/pricelists", Map.of("code", "SG0P" + sfx(), "name", "SG0 branch list "
                + sfx(), "type", "contractor")).get("id").asString();
        ok(201, "POST", "/sales/pricelists/" + list + "/items", Map.of("scope", "product", "productId", product, "compute",
                "fixed", "value", 55));
        jdbc.sql("UPDATE \"Pricelist\" SET \"branchId\" = ?, \"isDefault\" = true WHERE id = ?").params(b, list).update();
        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "branchId", b, "lines", List.of(Map.of(
                "productId", product, "quantity", 1))));
        assertThat(so.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(55);
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "branchId", b, "lines", List.of(Map.of(
                "productId", product, "quantity", 1))));
        assertThat(q.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(55);
        JsonNode plain = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 1))));
        assertThat(plain.get("lines").get(0).get("unitPrice").asDouble()).as("other branches keep the list price")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("#14 an inactive customer cannot get new quotations or orders, nor confirm a draft")
    void inactiveCustomer() {
        String c = customer();
        String draft = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", product,
                "quantity", 1)))).get("id").asString();
        jdbc.sql("UPDATE \"Partner\" SET status = 'inactive' WHERE id = ?").param(c).update();
        Map<String, Object> order = Map.of("partnerId", c, "lines", List.of(Map.of("productId", product, "quantity", 1)));
        assertThat(as("POST", "/sales/orders", order).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/quotations", order).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/orders/" + draft + "/confirm", null).status()).isEqualTo(400);
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
