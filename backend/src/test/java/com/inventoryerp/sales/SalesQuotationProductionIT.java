package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesQuotationService;
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
import tools.jackson.databind.JsonNode;

/**
 * Quotations production readiness: optional products, stable line ids, expiry follow-up (drafts never expire, the
 * reminder, the expiring filter), per-currency totals, email compose, preview pricing, the CRM decline reason, the
 * sent timeline entry, conversions keeping the header, duplicate, templates, and the action after online acceptance.
 */
class SalesQuotationProductionIT extends ApiTestSupport {

    @Autowired
    SalesQuotationService quotations;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String extra;
    String customer;
    String term;

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "QP-" + sfx, "name", "QP item " + sfx, "productType",
                "service", "salesPrice", 100)).get("id").asString();
        extra = ok(201, "POST", "/products", Map.of("code", "QPX-" + sfx, "name", "QP extra " + sfx, "productType",
                "service", "salesPrice", 50)).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'qp@example.test', true, now())").params(customer, demoOrg, "QPC-" + sfx,
                        "QP cust " + sfx).update();
        term = jdbc.sql("SELECT id FROM \"PaymentTerm\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(null);
    }

    Map<String, Object> line(String productId, int qty, int price) {
        return new LinkedHashMap<>(Map.of("productId", productId, "quantity", qty, "unitPrice", price));
    }

    Map<String, Object> optional(String productId, int qty, int price) {
        Map<String, Object> l = line(productId, qty, price);
        l.put("isOptional", true);
        return l;
    }

    JsonNode quote(String base, Map<String, Object> extraFields, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("partnerId", customer, "lines", lines));
        b.putAll(extraFields);
        return ok(201, "POST", base, b);
    }

    String approved(JsonNode q) {
        String id = q.get("id").asString();
        JsonNode s = ok(200, "POST", "/sales/quotations/" + id + "/submit", null);
        if ("pending_approval".equals(s.get("status").asString())) {
            ok(200, "POST", "/sales/quotations/" + id + "/approve", null);
        }
        return id;
    }

    String optionalLineId(JsonNode q) {
        for (JsonNode l : q.get("lines")) {
            if (l.get("isOptional").asBoolean()) {
                return l.get("id").asString();
            }
        }
        throw new AssertionError("no optional line");
    }

    long notifications(String category, String number) {
        return jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE category = ? AND title LIKE ?").params(category,
                "%" + number + "%").query(Long.class).single();
    }

    @Test
    @DisplayName("optional products: priced, out of the totals, counted when the customer takes them; untaken ones"
            + " never reach the order")
    void optionalProducts() {
        JsonNode q = quote("/sales/quotations", Map.of(), List.of(line(product, 2, 100), optional(extra, 1, 50)));
        assertThat(q.get("total").asDouble()).as("optional line not counted").isEqualTo(200);
        String opt = optionalLineId(q);
        String id = approved(q);

        assertThat(as("POST", "/sales/quotations/" + id + "/accept", Map.of("optionalLineIds", List.of(UUID.randomUUID()
                .toString()))).body().get("message").asString()).isEqualTo(
                        "optionalLineIds must be optional lines of this quotation");
        JsonNode accepted = ok(200, "POST", "/sales/quotations/" + id + "/accept", Map.of("optionalLineIds", List.of(opt)));
        assertThat(accepted.get("status").asString()).isEqualTo("accepted");
        assertThat(accepted.get("total").asDouble()).as("the taken option now counts").isEqualTo(250);
        String so = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of("target", "sales_order"))
                .get("convertedId").asString();
        assertThat(ok(200, "GET", "/sales/orders/" + so, null).get("lines").size()).isEqualTo(2);

        // Not taken: accepted without it, the order has only the main line.
        String other = approved(quote("/sales/quotations", Map.of(), List.of(line(product, 2, 100), optional(extra, 1,
                50))));
        assertThat(ok(200, "POST", "/sales/quotations/" + other + "/accept", Map.of()).get("total").asDouble())
                .isEqualTo(200);
        String so2 = ok(200, "POST", "/sales/quotations/" + other + "/convert", Map.of("target", "sales_order"))
                .get("convertedId").asString();
        JsonNode order = ok(200, "GET", "/sales/orders/" + so2, null);
        assertThat(order.get("lines").size()).isEqualTo(1);
        assertThat(order.get("total").asDouble()).isEqualTo(200);

        // A quotation of optional lines only cannot be submitted.
        String onlyOptional = quote("/sales/quotations", Map.of(), List.of(optional(extra, 1, 50))).get("id").asString();
        assertThat(as("POST", "/sales/quotations/" + onlyOptional + "/submit", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("line ids stay stable across saves; a new or foreign id gets a fresh one")
    void stableLineIds() {
        JsonNode q = quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100), line(extra, 1, 50)));
        String id = q.get("id").asString();
        String first = q.get("lines").get(0).get("id").asString();
        String second = q.get("lines").get(1).get("id").asString();
        String foreign = UUID.randomUUID().toString();
        Map<String, Object> l1 = line(product, 3, 100);
        l1.put("id", first);
        Map<String, Object> l2 = line(extra, 1, 50);
        l2.put("id", foreign);
        JsonNode saved = ok(200, "PUT", "/sales/quotations/" + id, Map.of("lines", List.of(l1, l2, line(extra, 2, 50))));
        JsonNode lines = saved.get("lines");
        assertThat(lines.get(0).get("id").asString()).isEqualTo(first);
        assertThat(lines.get(0).get("quantity").asDouble()).isEqualTo(3);
        assertThat(lines.get(1).get("id").asString()).isNotIn(foreign, first, second);
        assertThat(lines.get(2).get("id").asString()).isNotIn(first, lines.get(1).get("id").asString());
        assertThat(saved.get("total").asDouble()).isEqualTo(450);
    }

    @Test
    @DisplayName("the editor's save body: cleared contact / site / agreement, a rate, an order discount on counted lines")
    void editorSaveBody() {
        JsonNode q = quote("/sales/quotations", Map.of(), List.of(line(product, 2, 100), optional(extra, 1, 50)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", customer);
        body.put("contactId", null);
        body.put("siteId", null);
        body.put("agreementId", null);
        body.put("exchangeRate", 1);
        body.put("orderDiscountPercent", 10);
        body.put("orderDiscountAmount", null);
        Map<String, Object> l1 = line(product, 2, 100);
        l1.put("id", q.get("lines").get(0).get("id").asString());
        l1.put("isOptional", false);
        Map<String, Object> l2 = optional(extra, 1, 50);
        l2.put("id", optionalLineId(q));
        body.put("lines", List.of(l1, l2));
        JsonNode saved = ok(200, "PUT", "/sales/quotations/" + q.get("id").asString(), body);
        assertThat(saved.get("total").asDouble()).as("10% off the counted line only").isEqualTo(180);
        assertThat(saved.get("orderDiscountPercent").asDouble()).isEqualTo(10);
        assertThat(saved.get("lines").get(1).get("isOptional").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a draft past its date is not expired, but cannot be submitted until it has a new date")
    void draftsDoNotExpire() {
        String id = quote("/sales/quotations", Map.of("validUntil", LocalDate.now().minusDays(2).toString()), List.of(
                line(product, 1, 100))).get("id").asString();
        String number = ok(200, "GET", "/sales/quotations/" + id, null).get("number").asString();
        ok(200, "GET", "/sales/quotations?q=" + number, null);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        JsonNode d = ok(200, "GET", "/sales/quotations/" + id, null);
        assertThat(d.get("status").asString()).isEqualTo("draft");
        assertThat(d.get("validityDaysLeft").asInt()).isNegative();
        assertThat(as("POST", "/sales/quotations/" + id + "/submit", null).body().get("message").asString())
                .isEqualTo("The expiration date has passed; set a new one before submitting");
        ok(200, "PUT", "/sales/quotations/" + id, Map.of("validUntil", LocalDate.now().plusDays(10).toString()));
        assertThat(ok(200, "POST", "/sales/quotations/" + id + "/submit", null).get("status").asString())
                .isIn("approved", "pending_approval");
    }

    @Test
    @DisplayName("an open offer past its date expires; send and accept are refused")
    void openOfferExpires() {
        String id = approved(quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100))));
        jdbc.sql("UPDATE \"SalesQuotation\" SET \"validUntil\" = CURRENT_DATE - 1 WHERE id = ?").param(id).update();
        assertThat(as("POST", "/sales/quotations/" + id + "/send", null).body().get("message").asString())
                .isEqualTo("Quotation has expired; revise it first");
        assertThat(as("POST", "/sales/quotations/" + id + "/accept", Map.of()).status()).isEqualTo(400);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("expired");
    }

    @Test
    @DisplayName("expiring soon: filtered, a validity countdown, one reminder per validity date")
    void expiringFollowUp() {
        String id = approved(quote("/sales/quotations", Map.of("validUntil", LocalDate.now().plusDays(2).toString()),
                List.of(line(product, 1, 100))));
        JsonNode q = ok(200, "GET", "/sales/quotations/" + id, null);
        String number = q.get("number").asString();
        assertThat(q.get("validityDaysLeft").asInt()).isEqualTo(2);
        JsonNode soon = ok(200, "GET", "/sales/quotations?expiringWithin=7&q=" + number, null).get("data");
        assertThat(soon.size()).isEqualTo(1);
        assertThat(soon.get(0).get("validityDaysLeft").asInt()).isEqualTo(2);
        assertThat(ok(200, "GET", "/sales/quotations?expiringWithin=1&q=" + number, null).get("data").size()).isZero();

        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::remindExpiring);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::remindExpiring);
        assertThat(notifications("sales_quotation_expiring", number)).as("reminded once").isEqualTo(1);
    }

    @Test
    @DisplayName("list totals are given per currency, never added across currencies")
    void totalsPerCurrency() {
        String base = jdbc.sql("SELECT COALESCE(\"currencyCode\", 'UGX') FROM \"Organization\" WHERE id = ?")
                .param(demoOrg).query(String.class).optional().orElse("UGX");
        String foreign = jdbc.sql("SELECT code FROM \"Currency\" WHERE code <> ? ORDER BY code LIMIT 1").param(base)
                .query(String.class).optional().orElse(null);
        quote("/sales/quotations", Map.of("notes", "cur " + sfx), List.of(line(product, 1, 100)));
        if (foreign != null) {
            quote("/sales/quotations", Map.of("notes", "cur " + sfx, "currencyCode", foreign, "exchangeRate", 4), List.of(
                    line(product, 1, 30)));
        }
        JsonNode totals = ok(200, "GET", "/sales/quotations?q=QP%20cust%20" + sfx, null).get("totals");
        JsonNode by = totals.get("byCurrency");
        assertThat(by.isArray()).isTrue();
        double sum = 0;
        List<String> codes = new ArrayList<>();
        for (JsonNode c : by) {
            sum += c.get("total").asDouble();
            codes.add(c.get("currencyCode").asString());
        }
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(sum).isEqualTo(totals.get("total").asDouble());
    }

    @Test
    @DisplayName("email compose: recipient, cc, subject and message; bad cc refused; marks the quotation sent")
    void emailCompose() {
        String id = approved(quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100))));
        assertThat(as("POST", "/sales/quotations/" + id + "/email", Map.of("cc", List.of("not-an-address"))).body()
                .get("message").asString()).isEqualTo("Not an email address: not-an-address");
        JsonNode sent = ok(200, "POST", "/sales/quotations/" + id + "/email", Map.of("to", "buyer@example.test", "cc",
                List.of("boss@example.test"), "subject", "Our offer " + sfx, "body", "Hello\n\nSee attached."));
        assertThat(sent.get("sentTo").asString()).isEqualTo("buyer@example.test");
        assertThat(sent.get("cc").get(0).asString()).isEqualTo("boss@example.test");
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("sent");

        String defaults = approved(quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100))));
        assertThat(ok(200, "POST", "/sales/quotations/" + defaults + "/email", Map.of()).get("sentTo").asString())
                .as("the customer's address").isEqualTo("qp@example.test");
    }

    @Test
    @DisplayName("preview prices are the saved prices, on both API families")
    void previewPrices() {
        Map<String, Object> body = Map.of("partnerId", customer, "lines", List.of(Map.of("productId", product, "quantity",
                2)));
        JsonNode sales = ok(200, "POST", "/sales/quotations/resolve-prices", body).get("lines").get(0);
        JsonNode crm = ok(200, "POST", "/crm/quotations/resolve-prices", body).get("lines").get(0);
        assertThat(sales.get("unitPrice").asDouble()).isEqualTo(crm.get("unitPrice").asDouble());
        JsonNode saved = quote("/sales/quotations", Map.of(), List.of(new LinkedHashMap<>(Map.of("productId", product,
                "quantity", 2))));
        assertThat(saved.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(sales.get("unitPrice").asDouble());
        assertThat(as("POST", "/sales/quotations/resolve-prices", Map.of("lines", List.of())).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("CRM decline keeps the lost reason; Mark as sent leaves a timeline entry on the opportunity")
    void crmDeclineAndTimeline() {
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "QP deal " + sfx, "partnerId", customer,
                "amount", 100)).get("id").asString();
        Map<String, Object> withDeal = Map.of("dealId", deal);
        String sentQuote = approved(quote("/crm/quotations", withDeal, List.of(line(product, 1, 100))));
        String number = ok(200, "POST", "/crm/quotations/" + sentQuote + "/send", null).get("number").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(deal,
                "Quotation " + number + " sent").query(Long.class).single()).isEqualTo(1);

        String reason = ok(200, "GET", "/sales/lost-reasons", null).get(0).get("id").asString();
        String declined = approved(quote("/crm/quotations", Map.of(), List.of(line(product, 1, 100))));
        assertThat(ok(200, "POST", "/crm/quotations/" + declined + "/decline", Map.of("reason", "Too dear",
                "lostReasonId", reason)).get("lostReasonId").asString()).isEqualTo(reason);
    }

    @Test
    @DisplayName("a proforma from a quotation keeps its payment term, delivery, currency and rate")
    void proformaKeepsHeader() {
        String delivery = LocalDate.now().plusDays(9).toString();
        Map<String, Object> header = new LinkedHashMap<>(Map.of("deliveryAddress", "Plot 77 " + sfx, "commitmentDate",
                delivery, "exchangeRate", 1));
        if (term != null) {
            header.put("paymentTermId", term);
        }
        String id = approved(quote("/sales/quotations", header, List.of(line(product, 1, 100))));
        String pf = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of("target", "proforma"))
                .get("convertedId").asString();
        JsonNode p = ok(200, "GET", "/sales/proformas/" + pf, null);
        assertThat(p.toString()).contains("Plot 77 " + sfx);
        if (term != null) {
            assertThat(p.get("paymentTermId").asString()).isEqualTo(term);
        }
    }

    @Test
    @DisplayName("duplicate makes a new draft with the same lines; the original is untouched")
    void duplicate() {
        JsonNode q = quote("/sales/quotations", Map.of("notes", "dup " + sfx), List.of(line(product, 2, 100), optional(
                extra, 1, 50)));
        String id = approved(q);
        JsonNode copy = ok(201, "POST", "/sales/quotations/" + id + "/duplicate", null);
        assertThat(copy.get("id").asString()).isNotEqualTo(id);
        assertThat(copy.get("number").asString()).isNotEqualTo(q.get("number").asString());
        assertThat(copy.get("status").asString()).isEqualTo("draft");
        assertThat(copy.get("total").asDouble()).isEqualTo(200);
        assertThat(copy.get("lines").size()).isEqualTo(2);
        assertThat(copy.get("notes").asString()).isEqualTo("dup " + sfx);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("approved");
    }

    @Test
    @DisplayName("templates: saved from a quotation, unique by name, tenant-isolated, soft-deleted")
    void templates() {
        String id = quote("/sales/quotations", Map.of("terms", "Terms " + sfx), List.of(line(product, 2, 100), line(extra,
                1, 50))).get("id").asString();
        String name = "Tpl " + sfx;
        JsonNode t = ok(201, "POST", "/sales/quotation-templates", Map.of("name", name, "quotationId", id));
        String tid = t.get("id").asString();
        assertThat(t.get("lineCount").asInt()).isEqualTo(2);
        assertThat(t.get("terms").asString()).isEqualTo("Terms " + sfx);
        assertThat(t.get("lines").size()).isEqualTo(2);
        assertThat(as("POST", "/sales/quotation-templates", Map.of("name", name.toUpperCase(), "quotationId", id))
                .status()).isEqualTo(409);
        assertThat(as("POST", "/sales/quotation-templates", Map.of("name", " ", "quotationId", id)).status())
                .isEqualTo(400);
        boolean listed = false;
        for (JsonNode x : ok(200, "GET", "/sales/quotation-templates", null)) {
            listed |= tid.equals(x.get("id").asString());
        }
        assertThat(listed).isTrue();
        assertThat(call("GET", "/sales/quotation-templates/" + tid, null, otherToken).status()).as("other tenant")
                .isEqualTo(404);
        ok(200, "DELETE", "/sales/quotation-templates/" + tid, null);
        assertThat(as("GET", "/sales/quotation-templates/" + tid, null).status()).isEqualTo(404);
        // the name is free again
        ok(201, "POST", "/sales/quotation-templates", Map.of("name", name, "quotationId", id));
    }

    @Test
    @DisplayName("after online acceptance: the sales order or the proforma is created; the customer sees the advance"
            + " to pay; the owner is told")
    void onlineAcceptAction() {
        try {
            ok(200, "PUT", "/sales/settings", Map.of("quoteOnlineAcceptAction", "sales_order"));
            JsonNode q = quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100), optional(extra, 1, 50)));
            String opt = optionalLineId(q);
            String id = approved(q);
            String t = token(ok(201, "POST", "/sales/quotations/" + id + "/share-link", null));
            Res accepted = call("POST", "/public/sales/quotes/" + t + "/accept", Map.of("signerName", "Online Buyer",
                    "optionalLineIds", List.of(opt)), new LinkedHashMap<>());
            assertThat(accepted.status()).as(accepted.raw().body()).isEqualTo(200);
            assertThat(accepted.body().get("convertedType").asString()).isEqualTo("sales_order");
            assertThat(accepted.body().has("convertedId")).as("no internal ids to the public").isFalse();
            JsonNode after = ok(200, "GET", "/sales/quotations/" + id, null);
            assertThat(after.get("status").asString()).isEqualTo("converted");
            JsonNode order = ok(200, "GET", "/sales/orders/" + after.get("convertedId").asString(), null);
            assertThat(order.get("total").asDouble()).as("with the option taken online").isEqualTo(150);
            assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE payload->>'quotationId' = ? AND"
                    + " payload->>'kind' = 'sales.quote_link.converted'").param(id).query(Long.class).single())
                    .isEqualTo(1);

            ok(200, "PUT", "/sales/settings", Map.of("quoteOnlineAcceptAction", "proforma", "proformaAdvancePercent", 40,
                    "proformaBankDetails", "QP Bank " + sfx));
            String id2 = approved(quote("/sales/quotations", Map.of(), List.of(line(product, 1, 100))));
            String t2 = token(ok(201, "POST", "/sales/quotations/" + id2 + "/share-link", null));
            JsonNode view = call("POST", "/public/sales/quotes/" + t2 + "/accept", Map.of("signerName", "Online Buyer"),
                    new LinkedHashMap<>()).body();
            assertThat(view.get("convertedType").asString()).isEqualTo("proforma");
            JsonNode pay = view.get("payment");
            assertThat(pay.get("advanceAmount").asDouble()).isEqualTo(40);
            assertThat(pay.get("bankDetails").asString()).isEqualTo("QP Bank " + sfx);
            assertThat(call("GET", "/public/sales/quotes/" + t2, null, new LinkedHashMap<>()).body().get("payment")
                    .get("number").asString()).isEqualTo(pay.get("number").asString());
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("quoteOnlineAcceptAction", "none"));
        }
    }

    @Test
    @DisplayName("settings: the new quotation keys validate and show in the CRM lookups")
    void settings() {
        try {
            assertThat(as("PUT", "/sales/settings", Map.of("quoteOnlineAcceptAction", "invoice")).status())
                    .isEqualTo(400);
            assertThat(as("PUT", "/sales/settings", Map.of("quoteExpiryReminderDays", 91)).status()).isEqualTo(400);
            ok(200, "PUT", "/sales/settings", Map.of("quoteExpiryReminderDays", 5, "quoteEmailSubject", "Offer {number}"));
            JsonNode s = ok(200, "GET", "/crm/config/lookups", null).get("settings");
            assertThat(s.get("quoteExpiryReminderDays").asInt()).isEqualTo(5);
            assertThat(s.get("quoteEmailSubject").asString()).isEqualTo("Offer {number}");
            assertThat(s.get("quoteOnlineAcceptAction").asString()).isEqualTo("none");
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("quoteExpiryReminderDays", 3, "quoteEmailSubject", ""));
        }
    }

    static String token(JsonNode created) {
        return created.get("path").asString().substring("/q/".length());
    }
}
