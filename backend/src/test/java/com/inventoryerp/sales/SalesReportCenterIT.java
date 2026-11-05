package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Sales report center (ADR-S057): every catalog report runs with the shared filter and returns the common shape;
 * the revenue reports agree with each other for one customer (gross − discount − returns = net); aging, targets,
 * conversion, exports, validation and the permission gate.
 */
class SalesReportCenterIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final LocalDate today = LocalDate.now();
    String product;
    String customer;
    String invoice;
    String salesperson;

    @Autowired
    AccountResolverService accounts;

    Map<String, String> money() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    @BeforeAll
    void fixtures() {
        salesperson = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, salesperson), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> accounts.ensureByCode("1060", "Mobile Money Wallet", "mobile_money",
                        "mobile_money")));
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 1000,"
                + " 600, false, now())").params(product, demoOrg, "RC-" + sfx, "Aggregate 20mm " + sfx).update();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"salespersonId\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, ?, now())").params(customer, demoOrg,
                        "RC-" + sfx, "Quarry Buyer " + sfx, salesperson).update();

        // 10 × 1000 at 10 % discount = 9000 net; 2000 paid; 1 unit credited back (1000).
        invoice = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today.toString(), "dueDate",
                today.plusDays(30).toString(), "lines", List.of(Map.of("productId", product, "quantity", 10, "unitPrice",
                        1000, "discountPercent", 10)))).get("id").asString();
        ok(200, "POST", "/invoices/" + invoice + "/post", null);
        Res pay = call("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today.toString(), "amount", 2000,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", invoice, "amount", 2000))), money());
        assertThat(pay.status()).as(pay.raw().body()).isLessThan(300);
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today.toString(),
                "reversedDocumentId", invoice, "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice",
                        1000)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        // An older invoice, 45 days overdue.
        String old = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today.minusDays(75)
                .toString(), "dueDate", today.minusDays(45).toString(), "lines", List.of(Map.of("productId", product,
                        "quantity", 2, "unitPrice", 1000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + old + "/post", null);

        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "commitmentDate", today.minusDays(3)
                .toString(), "lines", List.of(Map.of("productId", product, "quantity", 5)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        String q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", product, "quantity", 3)))).get("id").asString();
        as("POST", "/sales/quotations/" + q + "/send", Map.of());

        jdbc.sql("INSERT INTO \"SalesTarget\" (id, \"organizationId\", month, \"salespersonId\", amount) VALUES (?, ?, ?,"
                + " ?, 16000)").params(UUID.randomUUID().toString(), demoOrg, today.withDayOfMonth(1), salesperson)
                .update();
        jdbc.sql("INSERT INTO \"Deal\" (id, \"organizationId\", name, amount, status, probability, \"ownerId\","
                + " \"partnerId\", \"expectedClose\", \"updatedAt\") VALUES (?, ?, ?, 50000, 'open', 40, ?, ?, ?, now())")
                .params(UUID.randomUUID().toString(), demoOrg, "Road project " + sfx, salesperson, customer,
                        today.plusMonths(1)).update();
        jdbc.sql("INSERT INTO \"Deal\" (id, \"organizationId\", name, amount, status, \"ownerId\", \"partnerId\","
                + " \"lostAt\", \"lostNote\", \"updatedAt\") VALUES (?, ?, ?, 7000, 'lost', ?, ?, now(), 'price', now())")
                .params(UUID.randomUUID().toString(), demoOrg, "Lost bid " + sfx, salesperson, customer).update();
        jdbc.sql("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, \"createdById\", \"partnerId\","
                + " \"updatedAt\") VALUES (?, ?, 'call', 'Follow up', ?, ?, now())").params(UUID.randomUUID().toString(),
                        demoOrg, salesperson, customer).update();
    }

    String period() {
        return "from=" + today.minusMonths(6).withDayOfMonth(1) + "&to=" + today;
    }

    JsonNode run(String key, String query) {
        return ok(200, "GET", "/sales/report-center/run/" + key + "?" + query, null);
    }

    static JsonNode kpi(JsonNode report, String key) {
        for (JsonNode k : report.get("kpis")) {
            if (key.equals(k.get("key").asString())) {
                return k.get("value");
            }
        }
        throw new AssertionError("no KPI " + key + " in " + report.get("key"));
    }

    static JsonNode row(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.path(field).asString(null))) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("every catalog report runs (unfiltered and filtered) and returns the common shape")
    void everyReportRuns() {
        JsonNode cat = ok(200, "GET", "/sales/report-center/catalog", null);
        assertThat(cat.get("categories").size()).isEqualTo(12);
        assertThat(cat.get("count").asInt()).isGreaterThanOrEqualTo(140);
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cat.get("categories")) {
            assertThat(c.get("reports").size()).as(c.get("key").asString()).isGreaterThan(0);
            for (JsonNode r : c.get("reports")) {
                String key = r.get("key").asString();
                for (String q : List.of(period(), period() + "&partnerId=" + customer + "&salespersonId="
                        + salesperson + "&productId=" + product)) {
                    Res res = as("GET", "/sales/report-center/run/" + key + "?" + q, null);
                    if (res.status() != 200) {
                        failures.add(key + " → " + res.status() + " " + res.raw().body());
                        continue;
                    }
                    JsonNode b = res.body();
                    if (!b.get("columns").isArray() || !b.get("rows").isArray() || !b.get("kpis").isArray()
                            || b.get("chart").get("kind") == null) {
                        failures.add(key + " → bad shape");
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("revenue reports agree: gross − discount − returns = net; summary, period, product and customer")
    void revenueAgrees() {
        String q = period() + "&partnerId=" + customer;
        JsonNode s = run("sales-summary", q);
        assertThat(kpi(s, "gross").asDouble()).isEqualTo(12000);
        assertThat(kpi(s, "discount").asDouble()).isEqualTo(1000);
        assertThat(kpi(s, "returns").asDouble()).isEqualTo(1000);
        assertThat(kpi(s, "revenue").asDouble()).isEqualTo(10000);
        assertThat(kpi(s, "documents").asInt()).isEqualTo(2);
        JsonNode monthly = run("monthly-sales", q);
        assertThat(monthly.at("/totals/revenue").asDouble()).isEqualTo(10000);
        JsonNode gp = run("gp-product", q);
        JsonNode p = row(gp.get("rows"), "k0", product);
        assertThat(p.get("revenue").asDouble()).isEqualTo(10000);
        assertThat(p.get("cost").asDouble()).isEqualTo(6600);
        assertThat(p.get("margin").asDouble()).isEqualTo(3400);
        JsonNode top = run("top-customers", period() + "&limit=500");
        assertThat(row(top.get("rows"), "k0", customer).get("revenue").asDouble()).isEqualTo(10000);
        JsonNode disc = run("high-discounts", q + "&threshold=5");
        assertThat(disc.get("rows").size()).isEqualTo(1);
        assertThat(disc.get("rows").get(0).get("_route").asString()).isEqualTo("/invoices/" + invoice);
        JsonNode matrix = run("sales-matrix", q + "&dimension=customer&groupBy=month");
        assertThat(matrix.at("/totals/total").asDouble()).isEqualTo(10000);
        assertThat(run("customer-history", q).get("rows").size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("aging, overdue orders, target vs actual, pipeline, export and validation")
    void operational() {
        JsonNode aging = run("invoice-aging", "partnerId=" + customer);
        JsonNode r = aging.get("rows").get(0);
        assertThat(r.get("current").asDouble()).isEqualTo(6000);
        assertThat(r.get("d31_60").asDouble()).isEqualTo(2000);
        assertThat(run("overdue-orders", "partnerId=" + customer).get("rows").size()).isEqualTo(1);
        JsonNode t = run("target-achievement", "from=" + today.withDayOfMonth(1) + "&to=" + today + "&salespersonId="
                + salesperson);
        assertThat(t.at("/totals/target").asDouble()).isGreaterThanOrEqualTo(16000);
        assertThat(run("sales-pipeline", "partnerId=" + customer).at("/totals/weighted").asDouble())
                .isEqualTo(20000);
        assertThat(run("lost-opportunities", period() + "&partnerId=" + customer).get("rows").size()).isEqualTo(1);
        assertThat(run("quotation-register", period() + "&partnerId=" + customer).get("rows").size()).isEqualTo(1);
        assertThat(ok(200, "GET", "/sales/report-center/dashboard?" + period(), null).get("kpis").size())
                .isEqualTo(10);
        assertThat(ok(200, "GET", "/sales/report-center/lookups", null).get("channels").size()).isEqualTo(4);

        HttpResponse<byte[]> x = download("/sales/report-center/export/customer-summary?" + period());
        assertThat(x.statusCode()).isEqualTo(200);
        assertThat(x.headers().firstValue("Content-Disposition").orElse("")).contains(".xlsx");
        assertThat(x.body().length).isGreaterThan(1000);
        HttpResponse<byte[]> csv = download("/sales/report-center/export/sales-summary?format=csv&" + period());
        assertThat(new String(csv.body(), java.nio.charset.StandardCharsets.UTF_8)).contains("Net sales");

        assertThat(as("GET", "/sales/report-center/run/nope", null).status()).isEqualTo(404);
        assertThat(as("GET", "/sales/report-center/run/sales-summary?planet=mars", null).status()).isEqualTo(400);
        assertThat(as("GET", "/sales/report-center/run/sales-summary?from=2026-02-01&to=2026-01-01", null).status())
                .isEqualTo(400);
        assertThat(as("GET", "/sales/report-center/run/sales-comparison?dimension=planet", null).status())
                .isEqualTo(400);
        assertThat(as("GET", "/sales/report-center/run/top-products?measure=weight", null).status()).isEqualTo(400);
        assertThat(as("GET", "/sales/report-center/export/sales-summary?format=doc", null).status()).isEqualTo(400);
        assertThat(call("GET", "/sales/report-center/run/sales-summary", null, weakToken()).status()).isEqualTo(403);
    }

    HttpResponse<byte[]> download(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers
                            .ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
