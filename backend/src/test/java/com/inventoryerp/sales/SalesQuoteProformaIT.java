package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesQuotationService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** SG-3 and SG-4: quotation fields and output, expiry, lost reasons; proforma from quote or order, never posted. */
class SalesQuoteProformaIT extends ApiTestSupport {

    @Autowired
    SalesQuotationService quotations;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String customer;
    String branch;
    String term;

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "G3P-" + sfx, "name", "SG3 item " + sfx, "productType",
                "service", "salesPrice", 100)).get("id").asString();
        branch = ok(201, "POST", "/branches", Map.of("code", "G3B-" + sfx, "name", "SG3 B")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'g3@example.test', true, now())").params(customer, demoOrg, "G3C-" + sfx, "SG3 cust "
                + sfx).update();
        term = jdbc.sql("SELECT id FROM \"PaymentTerm\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg).query(String.class)
                .optional().orElse(null);
    }

    JsonNode quote(Map<String, Object> extra) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of("productId", product,
                "quantity", 2, "unitPrice", 100), Map.of("description", "Transport charge", "quantity", 1, "unitPrice", 50))));
        b.putAll(extra);
        return ok(201, "POST", "/sales/quotations", b);
    }

    String approved(JsonNode q) {
        String id = q.get("id").asString();
        ok(200, "POST", "/sales/quotations/" + id + "/submit", null);
        return id;
    }

    @Test
    @DisplayName("a quotation converts to an order keeping branch, terms, address, rate, UoM and variant fields")
    void convertKeepsFields() {
        Map<String, Object> extra = new LinkedHashMap<>(Map.of("branchId", branch, "deliveryAddress", "Plot 9", "exchangeRate", 3,
                "internalNotes", "secret margin note"));
        if (term != null) {
            extra.put("paymentTermId", term);
        }
        JsonNode q = quote(extra);
        assertThat(q.get("total").asDouble()).isEqualTo(250);
        String id = approved(q);
        String so = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of("target", "sales_order")).get("convertedId")
                .asString();
        JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(o.get("branchId").asString()).isEqualTo(branch);
        assertThat(o.get("deliveryAddress").asString()).isEqualTo("Plot 9");
        assertThat(o.get("exchangeRate").asDouble()).isEqualTo(3);
        assertThat(o.get("lines").size()).as("the charge is a service line").isEqualTo(2);
        if (term != null) {
            assertThat(o.get("paymentTermId").asString()).isEqualTo(term);
        }
    }

    @Test
    @DisplayName("the expiry job expires overdue quotations and alerts the owner once")
    void expiry() {
        String id = quote(Map.of("validUntil", LocalDate.now().minusDays(1).toString())).get("id").asString();
        int n = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("expired");
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        String number = ok(200, "GET", "/sales/quotations/" + id, null).get("number").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE category = 'sales_quotation_expired' AND title LIKE ?")
                .param("%" + number + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a declined quotation records a catalog reason; an unknown reason is refused")
    void lostReason() {
        JsonNode reasons = ok(200, "GET", "/sales/lost-reasons", null);
        assertThat(reasons.size()).isGreaterThan(3);
        String reason = reasons.get(0).get("id").asString();
        String id = approved(quote(Map.of()));
        assertThat(as("POST", "/sales/quotations/" + id + "/decline", Map.of("lostReasonId", "nope")).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/quotations/" + id + "/decline", Map.of("lostReasonId", reason)).get("lostReasonId")
                .asString()).isEqualTo(reason);
    }

    @Test
    @DisplayName("quotation PDF renders and email sends it, marking the quotation sent")
    void output() {
        String id = approved(quote(Map.of("internalNotes", "do not print")));
        assertThat(ok(200, "POST", "/sales/quotations/" + id + "/email", Map.of()).get("sentTo").asString())
                .isEqualTo("g3@example.test");
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("sent");
    }

    @Test
    @DisplayName("proforma from a quote and an order: listed, opened, advance applied, no journal or receivable")
    void proforma() {
        ok(200, "PUT", "/sales/settings", Map.of("proformaBankDetails", "ACME Bank 123", "proformaMobileDetails", "MoMo 777",
                "proformaAdvancePercent", 30));
        long journals = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        String fromQuote = ok(200, "POST", "/sales/quotations/" + approved(quote(Map.of())) + "/convert", Map.of("target",
                "proforma")).get("convertedId").asString();
        JsonNode pq = ok(200, "GET", "/sales/proformas/" + fromQuote, null);
        assertThat(pq.get("advanceAmount").asDouble()).isEqualTo(75);
        assertThat(pq.get("bankDetails").asString()).isEqualTo("ACME Bank 123");

        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "branchId", branch, "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", 200)))).get("id").asString();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res made = call("POST", "/sales/orders/" + so + "/proforma", Map.of("advanceAmount", 50), h);
        assertThat(made.status()).isEqualTo(200);
        String pf = made.body().get("id").asString();
        assertThat(made.body().get("advanceAmount").asDouble()).isEqualTo(50);
        assertThat(made.body().get("branchId").asString()).isEqualTo(branch);
        JsonNode list = ok(200, "GET", "/sales/proformas?partnerId=" + customer, null);
        assertThat(list.get("data").size()).isGreaterThanOrEqualTo(2);
        assertThat(as("POST", "/invoices/" + pf + "/post", null).status()).as("cannot post").isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg).query(Long.class)
                .single()).as("no GL").isEqualTo(journals);
        assertThat(ok(200, "GET", "/reports/ar/customers/" + customer + "/statement", null).get("closingBalance").asDouble())
                .as("no receivable").isZero();
        Map<String, String> h2 = new LinkedHashMap<>(h);
        h2.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/orders/" + so + "/proforma", Map.of("advanceAmount", 99999), h2).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/proformas/" + pf + "/email", Map.of()).get("ok").asBoolean()).isTrue();
    }
}
