package com.inventoryerp.accounting.fx3;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.FxRateFetchService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.audit.AuditService;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** FX-7 (report, drill-down, close checklist item) and FX-8 (rate fetch, manual override, missing rates). */
class FxReportingIT extends AccountingFixtures {

    @Autowired
    CurrencyService currencies;

    @Autowired
    AuditService audit;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("FX-7: a receipt at 3,800 on an invoice at 3,700 shows as realised +10,000 by partner, with drill-down")
    void report() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        rate(b, ccy, base, LocalDate.now().minusDays(3).toString(), "3800");
        String bank = okAs(b, 201, "POST", "/accounts/cash-flow", Map.of("code", "12" + sfx(), "name", "FX7 bank",
                "accountType", "bank", "currencyId", ccy)).get("id").asString();
        String customer = okAs(b, 201, "POST", "/partners", Map.of("code", "R-" + sfx(), "name", "FX7 customer",
                "isCustomer", true)).get("id").asString();
        String service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FX7 svc', 'service', 100, 0,"
                + " false, now())").params(service, b.org(), "F7-" + sfx()).update();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(),
                "currencyId", ccy, "exchangeRate", 3700, "lines", List.of(Map.of("productId", service, "quantity", 1,
                        "unitPrice", 100)))).get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", customer);
        body.put("paymentDate", today());
        body.put("amount", 100);
        body.put("paymentMethod", "bank");
        body.put("accountId", bank);
        body.put("currencyCode", ccy);
        body.put("exchangeRate", 3800);
        body.put("allocations", List.of(Map.of("documentId", inv, "amount", 100)));
        Res pay = moneyAs(b, "POST", "/payments", body);
        assertThat(pay.status()).as(pay.raw().body()).isIn(200, 201);

        JsonNode report = okAs(b, 200, "GET", "/fx-treasury/report?groupBy=partner", null);
        assertThat(new BigDecimal(report.get("realised").asString())).isEqualByComparingTo("10000");
        JsonNode row = null;
        for (JsonNode r : report.get("rows")) {
            if (customer.equals(r.get("key").asString())) {
                row = r;
            }
        }
        assertThat(row).as(report.toString()).isNotNull();
        assertThat(row.get("label").asString()).isEqualTo("FX7 customer");
        assertThat(new BigDecimal(row.get("realised").asString())).isEqualByComparingTo("10000");
        JsonNode byCurrency = okAs(b, 200, "GET", "/fx-treasury/report?groupBy=currency", null);
        assertThat(byCurrency.get("rows").get(0).get("key").asString()).isEqualTo(ccy);
        assertThat(asAnyStatus(b, "/fx-treasury/report?groupBy=colour")).isEqualTo(400);

        JsonNode lines = okAs(b, 200, "GET", "/fx-treasury/report/lines?kind=realised&partnerId=" + customer, null);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("sourceType").asString()).isEqualTo("payment");
        assertThat(lines.get(0).get("allocations").get(0).get("documentId").asString()).isEqualTo(inv);

        String period = jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"startDate\" <= now()"
                + " AND \"endDate\" >= now() - interval '1 day' AND \"deletedAt\" IS NULL ORDER BY \"startDate\" DESC LIMIT 1")
                .param(b.org()).query(String.class).optional().orElse(null);
        if (period != null) {
            JsonNode checklist = okAs(b, 200, "GET", "/fiscal-periods/" + period + "/close-checklist", null);
            assertThat(checklist.toString()).contains("fx_revaluation");
        }
    }

    int asAnyStatus(Books b, String path) {
        return as(b, "GET", path, null).status();
    }

    @Test
    @DisplayName("FX-8: fetch stores the inverse provider rate, keeps a manual rate, lists missing rates; off by default")
    void rateFetch() throws Exception {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String a = testCurrency();
        String m = testCurrency();
        String gone = testCurrency();
        rate(b, a, base, LocalDate.now().minusDays(20).toString(), "3000");
        rate(b, m, base, LocalDate.now().minusDays(20).toString(), "10");
        rate(b, gone, base, LocalDate.now().minusDays(20).toString(), "5");
        // a manual market rate for m today: the fetch must not replace it
        okAs(b, 201, "POST", "/currencies/rates", Map.of("fromCode", m, "toCode", base, "asOf", today(), "rate", 11,
                "rateType", "market"));
        JsonNode missing = okAs(b, 200, "GET", "/currencies/rates/missing?maxAgeDays=3", null);
        assertThat(missing.toString()).contains(a).contains(gone).doesNotContain("\"" + m + "\"");

        assertThat(okAs(b, 200, "GET", "/currencies/rates/fetch-status", null).get("configured").asBoolean())
                .as("off by default").isFalse();
        assertThat(moneyAs(b, "POST", "/currencies/rates/fetch", Map.of()).status()).isEqualTo(400);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String payload = "{\"base\":\"" + base + "\",\"rates\":{\"" + a + "\":0.00025,\"" + m + "\":0.08}}";
        server.createContext("/latest/" + base, ex -> {
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            FxRateFetchService fetch = new FxRateFetchService(jdbc, currencies, audit, txm, json, true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/latest/{base}", "test-provider", "market");
            Map<String, Object> result = inTenant(b.org(), b.ownerId(), fetch::fetchForCurrentOrg);
            assertThat(result.toString()).contains(a).contains("skipped=[" + m + "]").contains("unavailable=[" + gone
                    + "]");
            BigDecimal stored = jdbc.sql("SELECT rate FROM \"CurrencyRate\" WHERE \"organizationId\" = ? AND \"fromCode\" ="
                    + " ? AND \"rateType\" = 'market' AND source = 'test-provider'").params(b.org(), a)
                    .query(BigDecimal.class).single();
            assertThat(stored).as("1 / 0.00025").isEqualByComparingTo("4000");
            BigDecimal manual = jdbc.sql("SELECT rate FROM \"CurrencyRate\" WHERE \"organizationId\" = ? AND \"fromCode\" ="
                    + " ? AND \"rateType\" = 'market' ORDER BY \"asOf\" DESC LIMIT 1").params(b.org(), m)
                    .query(BigDecimal.class).single();
            assertThat(manual).as("manual override kept").isEqualByComparingTo("11");
        } finally {
            server.stop(0);
        }
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
