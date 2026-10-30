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
 * Phase S10 of ADR-S011: dashboard, analysis by dimension, customer profitability, forecast, the read-only advisor and
 * the customer portal (magic-link token, customer-scoped reads, quotation acceptance, payment, complaints, revoke).
 */
class SalesInsightsIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String product;

    @Autowired
    AccountResolverService accounts;

    @BeforeAll
    void fixtures() {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> accounts.ensureByCode("1060", "Mobile Money Wallet", "mobile_money", "mobile_money")));
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 1000,"
                + " 600, false, now())").params(product, demoOrg, "IN-" + sfx, "Blocks 6in " + sfx).update();
    }

    String customer(String email) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, true, true, now())").params(id, demoOrg, "IC-" + UUID.randomUUID()
                        .toString().substring(0, 8), "Builder " + sfx, email).update();
        return id;
    }

    String postedInvoice(String partner, int qty, int price, String issueDate) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", issueDate, "lines", List.of(
                Map.of("productId", product, "quantity", qty, "unitPrice", price)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
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
    @DisplayName("dashboard, analysis by product / customer, profitability, forecast")
    void analytics() {
        String c = customer(null);
        postedInvoice(c, 10, 1000, today);
        JsonNode dash = ok(200, "GET", "/sales/analytics/dashboard", null);
        assertThat(dash.get("chart").size()).isEqualTo(12);
        assertThat(dash.get("revenueThisMonth").asDouble()).isGreaterThanOrEqualTo(10000);
        String month = today.substring(0, 7) + "-01";
        JsonNode byProduct = ok(200, "GET", "/sales/analytics/analysis?groupBy=product&from=" + month, null);
        JsonNode p = row(byProduct.get("rows"), "key", product);
        assertThat(p.get("revenue").asDouble()).isEqualTo(10000);
        assertThat(p.get("cost").asDouble()).isEqualTo(6000);
        assertThat(p.get("marginPercent").asDouble()).isEqualTo(40);
        assertThat(p.get("label").asString()).contains("Blocks 6in");
        assertThat(row(ok(200, "GET", "/sales/analytics/analysis?groupBy=customer&from=" + month, null).get("rows"),
                "key", c).get("revenue").asDouble()).isEqualTo(10000);
        assertThat(as("GET", "/sales/analytics/analysis?groupBy=planet", null).status()).isEqualTo(400);
        JsonNode prof = row(ok(200, "GET", "/sales/analytics/profitability?from=" + month, null).get("rows"),
                "partnerId", c);
        assertThat(prof.get("grossMargin").asDouble()).isEqualTo(4000);
        assertThat(prof.get("netContribution").asDouble()).isEqualTo(4000);

        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "commitmentDate", LocalDate.now().plusMonths(1)
                .withDayOfMonth(10).toString(), "lines", List.of(Map.of("productId", product, "quantity", 5)))).get("id")
                .asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode fc = ok(200, "GET", "/sales/analytics/forecast?months=3", null);
        assertThat(fc.get("months").size()).isEqualTo(3);
        assertThat(fc.get("months").get(1).get("openOrders").asDouble()).isGreaterThanOrEqualTo(5000);
        assertThat(fc.get("months").get(1).get("expected").asDouble()).isGreaterThanOrEqualTo(5000);
    }

    @Test
    @DisplayName("presentation: period KPIs vs previous period, categories, trend, validation")
    void presentation() {
        String c = customer(null);
        LocalDate to = LocalDate.now();
        // Large amounts keep the customer / product in the top-N lists when other tests share the database.
        postedInvoice(c, 4, 50_000_000, to.toString());
        postedInvoice(c, 2, 50_000_000, to.minusDays(7).toString());
        String from = to.minusDays(6).toString();
        JsonNode deck = ok(200, "GET", "/sales/analytics/presentation?from=" + from + "&to=" + to, null);
        assertThat(deck.at("/period/days").asInt()).isEqualTo(7);
        assertThat(deck.at("/previousPeriod/to").asString()).isEqualTo(to.minusDays(7).toString());
        assertThat(deck.at("/kpis/revenue/current").asDouble()).isGreaterThanOrEqualTo(200_000_000);
        assertThat(deck.at("/kpis/revenue/previous").asDouble()).isGreaterThanOrEqualTo(100_000_000);
        assertThat(deck.get("trend").get("buckets").size()).isEqualTo(6);
        assertThat(deck.get("trend").get("buckets").get(5).get("to").asString()).isEqualTo(to.toString());
        boolean found = false;
        for (JsonNode cat : deck.get("categories")) {
            found |= row(cat.get("products"), "key", product) != null;
        }
        assertThat(found).isTrue();
        assertThat(row(deck.get("topCustomers"), "partnerId", c).get("revenue").asDouble()).isEqualTo(200_000_000);
        assertThat(deck.at("/receivables/aging").size()).isEqualTo(5);
        assertThat(deck.at("/forecast/months").size()).isEqualTo(3);
        assertThat(ok(200, "GET", "/sales/analytics/presentation?from=2025-01-01&to=2025-12-31", null).at(
                "/trend/granularity").asString()).isEqualTo("month");
        assertThat(as("GET", "/sales/analytics/presentation?from=" + to + "&to=" + from, null).status()).isEqualTo(400);
        assertThat(as("GET", "/sales/analytics/presentation?from=2020-01-01&to=2025-01-01", null).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("advisor: negative margin and churn risk, read-only")
    void advisor() {
        String cheap = customer(null);
        String inv = postedInvoice(cheap, 4, 500, today);
        String gone = customer(null);
        postedInvoice(gone, 10, 1000, LocalDate.now().minusDays(120).toString());
        JsonNode adv = ok(200, "GET", "/sales/advisor", null);
        JsonNode neg = null;
        JsonNode churn = null;
        for (JsonNode a : adv) {
            if ("negative_margin".equals(a.get("kind").asString()) && inv.equals(a.get("entityId").asString())) {
                neg = a;
            }
            if ("churn_risk".equals(a.get("kind").asString()) && gone.equals(a.get("entityId").asString())) {
                churn = a;
            }
        }
        assertThat(neg).isNotNull();
        assertThat(neg.get("detail").asString()).contains("revenue 2000, cost 2400");
        assertThat(churn).isNotNull();
        assertThat(churn.get("title").asString()).contains("100% less");
    }

    @Test
    @DisplayName("portal: token scope, quotation accept, orders, invoices, mobile-money payment, complaint, revoke")
    void portal() throws Exception {
        String c = customer("buyer-" + sfx.toLowerCase() + "@demo.test");
        String other = customer(null);
        JsonNode access = ok(201, "POST", "/sales/portal/access", Map.of("partnerId", c, "days", 7, "baseUrl",
                "https://erp.example.test"));
        String token = access.get("token").asString();
        assertThat(access.get("url").asString()).startsWith("https://erp.example.test/portal?token=");
        Map<String, String> h = Map.of("X-Portal-Token", token);
        assertThat(call("GET", "/portal/me", null, Map.of()).status()).isEqualTo(401);
        assertThat(call("GET", "/portal/me", null, Map.of("X-Portal-Token", "nope")).status()).isEqualTo(401);
        assertThat(call("GET", "/portal/me", null, h).body().get("id").asString()).isEqualTo(c);

        String mine = ok(201, "POST", "/sales/quotations", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                product, "quantity", 3, "unitPrice", 1000)))).get("id").asString();
        String theirs = ok(201, "POST", "/sales/quotations", Map.of("partnerId", other, "lines", List.of(Map.of(
                "productId", product, "quantity", 3, "unitPrice", 1000)))).get("id").asString();
        jdbc.sql("UPDATE \"SalesQuotation\" SET status = 'sent', \"validUntil\" = CURRENT_DATE + 10 WHERE id IN (?, ?)")
                .params(mine, theirs).update();
        assertThat(call("GET", "/portal/quotations", null, h).body().size()).isEqualTo(1);
        assertThat(call("GET", "/portal/quotations/" + theirs, null, h).status()).isEqualTo(404);
        assertThat(call("POST", "/portal/quotations/" + theirs + "/accept", null, h).status()).isEqualTo(404);
        JsonNode accepted = call("POST", "/portal/quotations/" + mine + "/accept", null, h).body();
        assertThat(accepted.get("status").asString()).isEqualTo("accepted");
        assertThat(accepted.has("approvalReasons")).isFalse();

        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId", product,
                "quantity", 2)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        assertThat(call("GET", "/portal/orders", null, h).body().size()).isEqualTo(1);
        assertThat(call("GET", "/portal/orders/" + so, null, h).body().get("lines").size()).isEqualTo(1);

        String inv = postedInvoice(c, 2, 1000, today);
        postedInvoice(other, 1, 1000, today);
        assertThat(call("GET", "/portal/invoices", null, h).body().size()).isEqualTo(1);
        JsonNode pay = call("POST", "/portal/invoices/" + inv + "/pay", Map.of("provider", "mock", "phone",
                "+256771234567"), h).body();
        assertThat(pay.get("status").asString()).isEqualTo("pending");
        assertThat(call("POST", "/portal/invoices/" + inv + "/pay", Map.of("provider", "mock", "phone", "+256771234567"),
                h).body().get("id").asString()).isEqualTo(pay.get("id").asString());
        JsonNode paid = call("GET", "/portal/payments/" + pay.get("id").asString(), null, h).body();
        assertThat(paid.path("status").asString(null)).as(paid.toString()).isEqualTo("succeeded");
        assertThat(ok(200, "GET", "/invoices/" + inv, null).get("amountResidual").asDouble()).isZero();

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/portal/statement.pdf")).header("X-Portal-Token", token).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4)).isEqualTo("%PDF");

        assertThat(call("POST", "/portal/complaints", Map.of("category", "quality", "description", "Cracked blocks",
                "orderId", so), h).status()).isEqualTo(201);
        assertThat(call("GET", "/portal/complaints", null, h).body().size()).isEqualTo(1);

        ok(200, "POST", "/sales/portal/access/" + access.get("id").asString() + "/revoke", null);
        assertThat(call("GET", "/portal/me", null, h).status()).isEqualTo(401);
        assertThat(ok(200, "GET", "/sales/portal/access?partnerId=" + c, null).get(0).get("revokedAt").isNull()).isFalse();
    }
}
