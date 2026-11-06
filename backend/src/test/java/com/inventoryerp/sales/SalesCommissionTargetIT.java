package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesTargetService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** SG-11 and SG-12: commission spec examples, return, carry-forward, payout gate; targets, alerts, one revenue logic. */
class SalesCommissionTargetIT extends ApiTestSupport {

    @Autowired
    SalesTargetService targets;

    record Person(String id) {}

    String person() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg).query(String.class).single();
        return createUser(demoOrg, "sg11-" + UUID.randomUUID() + "@demo.test", "Sales@12345", role);
    }

    String product(int price, int cost) {
        return ok(201, "POST", "/products", Map.of("code", "G11-" + UUID.randomUUID(), "name", "SG11 " + UUID.randomUUID(), "productType", "service", "salesPrice", price, "costPrice", cost))
                .get("id").asString();
    }

    String customer(String salesperson) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"salespersonId\", \"updatedAt\") VALUES (?, ?, ?, ?, true, ?, now())")
                .params(id, demoOrg, "G11-" + UUID.randomUUID(), "SG11 " + UUID.randomUUID(), salesperson).update();
        return id;
    }

    String invoice(String partner, String product, double amount, int qty) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(), "lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", amount / qty))))
                .get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    String rule(String salesperson, String method, double rate, Map<String, Object> extra) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("name", "SG11 " + UUID.randomUUID(), "salespersonId", salesperson, "basis", "invoiced", "method", method, "rate", rate, "priority", 10));
        b.putAll(extra);
        return ok(201, "POST", "/sales/commissions/rules", b).get("id").asString();
    }

    double previewTotal(String sp) {
        JsonNode s = ok(200, "GET", "/sales/commissions/preview?salespersonId=" + sp, null).get("salespeople");
        for (JsonNode n : s) {
            if (sp.equals(n.get("salespersonId").asString())) {
                return n.get("total").asDouble();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("spec examples: 50M × 2% and 10M gross profit × 10% both give 1M")
    void specExamples() {
        String a = person();
        rule(a, "percent_revenue", 2, Map.of());
        invoice(customer(a), product(1000, 0), 50000000, 1);
        assertThat(previewTotal(a)).isEqualTo(1000000);
        String b = person();
        rule(b, "percent_margin", 10, Map.of());
        invoice(customer(b), product(30000000, 20000000), 30000000, 1);
        assertThat(previewTotal(b)).isEqualTo(1000000);
    }

    @Test
    @DisplayName("marginal tiers, product and customer rules, and splits")
    void tiersAndSplits() {
        String a = person();
        String p = product(100, 0);
        rule(a, "percent_revenue", 0, Map.of("productId", p, "tiers", List.of(Map.of("from", 0, "rate", 1), Map.of("from", 1000, "rate", 2))));
        invoice(customer(a), p, 3000, 1);
        assertThat(previewTotal(a)).as("1% of 1000 + 2% of 2000").isEqualTo(50);
        assertThat(as("POST", "/sales/commissions/rules", Map.of("name", "bad", "rate", 1, "tiers", List.of(Map.of("from", 5, "rate", 1)))).status()).isEqualTo(400);

        String lead = person();
        String helper = person();
        String c = customer(lead);
        rule(lead, "percent_revenue", 10, Map.of("partnerId", c, "splits", List.of(Map.of("salespersonId", helper, "percent", 25))));
        invoice(c, product(100, 0), 1000, 1);
        assertThat(previewTotal(lead)).isEqualTo(75);
        assertThat(previewTotal(helper)).isEqualTo(25);
    }

    @Test
    @DisplayName("collected basis puts the payment on the line; invoiced basis does not pay on an unpaid invoice")
    void collected() {
        String a = person();
        rule(a, "percent_revenue", 10, Map.of("basis", "paid"));
        String c = customer(a);
        String inv = invoice(c, product(100, 0), 1000, 1);
        assertThat(previewTotal(a)).as("nothing collected yet").isZero();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res pay = call("POST", "/payments", Map.of("partnerId", c, "paymentDate", LocalDate.now().toString(), "amount", 1000, "paymentMethod", "cash", "allocations",
                List.of(Map.of("documentId", inv, "amount", 1000))), h);
        assertThat(pay.status()).as(pay.raw().body()).isLessThan(300);
        JsonNode lines = ok(200, "GET", "/sales/commissions/preview?salespersonId=" + a, null).get("lines");
        assertThat(lines.get(0).get("amount").asDouble()).isEqualTo(100);
        assertThat(lines.get(0).get("paymentId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("a return reverses; a negative month carries forward; adjustments and disputes; no payout before approval")
    void statements() {
        String a = person();
        rule(a, "percent_revenue", 10, Map.of());
        String c = customer(a);
        String p = product(100, 0);
        invoice(c, p, 1000, 1);
        String month = LocalDate.now().toString().substring(0, 7);
        String st = ok(200, "POST", "/sales/commissions/statements", Map.of("month", month, "salespersonId", a)).get(0).get("id").asString();
        Res paidEarly = call("POST", "/sales/commissions/statements/" + st + "/paid", Map.of(), Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(paidEarly.status()).as("no payout before approval").isEqualTo(400);

        ok(200, "POST", "/sales/commissions/statements/" + st + "/adjustments", Map.of("amount", 5, "reason", "Bonus"));
        JsonNode adjusted = ok(200, "GET", "/sales/commissions/statements/" + st, null);
        assertThat(adjusted.get("total").asDouble()).isEqualTo(105);
        String dispute = ok(200, "POST", "/sales/commissions/statements/" + st + "/disputes", Map.of("reason", "Rate is wrong")).get("disputes").get(0).get("id").asString();
        assertThat(as("POST", "/sales/commissions/statements/" + st + "/approve", Map.of()).status()).as("open dispute").isEqualTo(400);
        ok(200, "POST", "/sales/commissions/disputes/" + dispute + "/resolve", Map.of("resolution", "Rate correct"));

        // A full credit note makes the month negative for a new statement next run.
        JsonNode cn = call("POST", "/sales/credit-notes", Map.of("partnerId", c, "type", "billing_error", "reason", "Return", "lines", List.of(Map.of("productId", p, "quantity", 3, "unitPrice", 1000))),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString())).body();
        assertThat(cn.get("id")).isNotNull();
        JsonNode regenerated = ok(200, "POST", "/sales/commissions/statements", Map.of("month", month, "salespersonId", a)).get(0);
        assertThat(regenerated.get("total").asDouble()).as("1000 − 3000 at 10% + 5").isEqualTo(-195);
        JsonNode approved = ok(200, "POST", "/sales/commissions/statements/" + st + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("payrollInputId").isNull()).isTrue();

        String next = LocalDate.now().plusMonths(1).toString().substring(0, 7);
        JsonNode carried = ok(200, "POST", "/sales/commissions/statements", Map.of("month", next, "salespersonId", a)).get(0);
        assertThat(carried.get("carriedIn").asDouble()).as("the negative carries").isEqualTo(-195);
        assertThat(carried.get("total").asDouble()).isEqualTo(-195);
    }

    @Test
    @DisplayName("Targets tab and report center agree; gross profit, new customers and a quarter period; alerts fire once")
    void targetsAndAlerts() {
        String a = person();
        String c = customer(a);
        invoice(c, product(100, 40), 1000, 10);
        String month = LocalDate.now().toString().substring(0, 7);
        ok(201, "POST", "/sales/targets", Map.of("month", month, "salespersonId", a, "amount", 1000));
        JsonNode tab = ok(200, "GET", "/sales/targets/achievement?month=" + month, null);
        JsonNode mine = null;
        for (JsonNode t : tab) {
            if (a.equals(t.path("salespersonId").asString())) {
                mine = t;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("actualAmount").asDouble()).isEqualTo(1000);
        JsonNode report = ok(200, "GET", "/sales/report-center/run/target-vs-actual?salespersonId=" + a + "&from=" + LocalDate.now().withDayOfMonth(1) + "&to=" + LocalDate.now(), null);
        assertThat(report.get("totals").get("actual").asDouble()).isEqualTo(mine.get("actualAmount").asDouble());

        ok(201, "POST", "/sales/targets", Map.of("periodStart", LocalDate.now().withDayOfMonth(1).toString(), "periodType", "quarter", "metric", "gross_profit", "salespersonId", a, "amount", 600));
        ok(201, "POST", "/sales/targets", Map.of("month", month, "metric", "new_customers", "salespersonId", a, "amount", 1));
        assertThat(as("POST", "/sales/targets", Map.of("month", month, "metric", "nope", "amount", 1)).status()).isEqualTo(400);
        JsonNode all = ok(200, "GET", "/sales/targets/achievement?month=" + month, null);
        for (JsonNode t : all) {
            if (a.equals(t.path("salespersonId").asString()) && "gross_profit".equals(t.path("metric").asString())) {
                assertThat(t.get("actualAmount").asDouble()).isEqualTo(600);
            }
            if (a.equals(t.path("salespersonId").asString()) && "new_customers".equals(t.path("metric").asString())) {
                assertThat(t.get("actualAmount").asDouble()).isEqualTo(1);
            }
        }
        int first = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> targets.runAlerts(LocalDate.now()));
        assertThat(first).isGreaterThan(0);
        int second = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> targets.runAlerts(LocalDate.now()));
        assertThat(second).as("alerts fire once").isZero();
        JsonNode kpis = ok(200, "GET", "/sales/analytics/kpis", null);
        assertThat(kpis.has("averageOrderValue")).isTrue();
        assertThat(kpis.has("lifetimeValue")).isTrue();
        assertThat(kpis.has("badDebt")).isTrue();
    }
}
