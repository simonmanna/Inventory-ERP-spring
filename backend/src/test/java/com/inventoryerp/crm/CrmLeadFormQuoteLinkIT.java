package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmLeadFormService;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * CRM enhancements T3a (ADR-S084): website lead forms and the online quotation link. Both are public routes, so the
 * tests cover abuse controls, expiry and revocation, what the customer can see, and tenant isolation.
 */
class CrmLeadFormQuoteLinkIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    @Autowired
    CrmLeadFormService forms;
    String customer;
    String service;
    String adminId;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())").params(customer, demoOrg, "LF-" + sfx,
                        "Link Customer " + sfx).update();
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "LFS-" + sfx, "Link service " + sfx).update();
    }

    /** A submission body with a submit token issued {@code agoMs} ago for form {@code formId}. */
    Map<String, Object> signed(String formId, long agoMs, Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        body.put("_t", forms.issueToken(formId, System.currentTimeMillis() - agoMs));
        return body;
    }

    /** A public call: no bearer token, optional extra headers. */
    Res anon(String method, String path, Object body, Map<String, String> headers) {
        return call(method, path, body, new LinkedHashMap<>(headers));
    }

    HttpResponse<byte[]> raw(String method, String path, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).method(method,
                HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // ------------------------------------------------------------------ lead forms

    @Test
    @DisplayName("a website form turns a submission into a lead with the form's source and owner; abuse is refused")
    void leadForm() throws Exception {
        String source = jdbc.sql("SELECT id FROM \"CrmSource\" WHERE \"organizationId\" = ? ORDER BY name LIMIT 1")
                .param(demoOrg).query(String.class).single();
        assertThat(call("POST", "/crm/config/lead-forms", Map.of("name", "Bad", "allowedOrigins", List.of(
                "javascript:alert(1)")), adminToken).status()).isEqualTo(400);
        JsonNode form = ok(201, "POST", "/crm/config/lead-forms", Map.of("name", "Contact us " + sfx, "sourceId", source,
                "ownerId", adminId, "allowedOrigins", List.of("https://www.Example.com/"), "thankYouMessage",
                "Thanks " + sfx));
        String id = form.get("id").asString();
        String key = form.get("publicKey").asString();
        assertThat(key).startsWith("lf_");
        assertThat(form.get("allowedOrigins").get(0).asString()).isEqualTo("https://www.example.com");

        JsonNode info = anon("GET", "/public/crm/forms/" + key, null, Map.of()).body();
        assertThat(info.get("name").asString()).isEqualTo("Contact us " + sfx);
        assertThat(info.get("thankYouMessage").asString()).isEqualTo("Thanks " + sfx);
        assertThat(info.has("organizationId")).isFalse();

        assertThat(info.get("submitToken").asString()).as("the form page carries a submit token").contains(".");

        String path = "/public/crm/forms/" + key + "/submit";
        Map<String, String> site = Map.of("Origin", "https://www.example.com");
        assertThat(anon("POST", path, signed(id, 5000, "email", "a@b.test"), site).body().get("message").asString())
                .isEqualTo("Please enter your name");
        assertThat(anon("POST", path, signed(id, 5000, "name", "Ann"), site).body().get("message").asString())
                .isEqualTo("Please enter an email address or a phone number");
        assertThat(anon("POST", path, signed(id, 5000, "name", "Ann", "email", "not-an-email"), site).status())
                .isEqualTo(400);
        assertThat(anon("POST", path, signed(id, 5000, "name", "x".repeat(121), "email", "a@b.test"), site).status())
                .isEqualTo(400);
        assertThat(anon("POST", path, signed(id, 5000, "name", "Evil", "email", "e@evil.test"), Map.of("Origin",
                "https://evil.test")).status()).as("origin not allowed").isEqualTo(403);

        // production-readiness P0-5: no token, a forged one, another form's or an expired one is refused
        String reload = "This form has expired; reload the page and try again";
        assertThat(anon("POST", path, Map.of("name", "NoTok", "email", "n@b.test"), site).body().get("message")
                .asString()).isEqualTo(reload);
        Map<String, Object> forged = signed(id, 5000, "name", "Forged", "email", "f@b.test");
        forged.put("_t", "1700000000000.AAAA");
        assertThat(anon("POST", path, forged, site).body().get("message").asString()).isEqualTo(reload);
        assertThat(anon("POST", path, signed("another-form", 5000, "name", "X", "email", "x@b.test"), site).status())
                .as("a token is bound to its form").isEqualTo(400);
        assertThat(anon("POST", path, signed(id, 25 * 3_600_000L, "name", "Old", "email", "o@b.test"), site).status())
                .as("a day-old page").isEqualTo(400);
        // sent back faster than a person can type: answered like the honeypot, nothing stored
        Res fast = anon("POST", path, signed(id, 0, "name", "Fast " + sfx, "email", "fast@spam.test"), site);
        assertThat(fast.status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE \"organizationId\" = ? AND \"contactName\" = ?")
                .params(demoOrg, "Fast " + sfx).query(Long.class).single()).isZero();

        // honeypot: answered as success, nothing stored
        Res bot = anon("POST", path, Map.of("name", "Bot " + sfx, "email", "bot@spam.test", "_hp", "http://spam"), site);
        assertThat(bot.status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE \"organizationId\" = ? AND \"contactName\" = ?")
                .params(demoOrg, "Bot " + sfx).query(Long.class).single()).isZero();

        Res sent = anon("POST", path, signed(id, 5000, "name", "Grace " + sfx, "email", "grace-" + sfx.toLowerCase()
                + "@example.test", "company", "Grace Ltd " + sfx, "message", "Need 40 chairs", "pageUrl",
                "https://www.example.com/contact"), site);
        assertThat(sent.status()).isEqualTo(201);
        assertThat(sent.body().get("message").asString()).isEqualTo("Thanks " + sfx);
        assertThat(sent.body().has("id")).as("the lead id is not disclosed").isFalse();
        Map<String, Object> lead = jdbc.sql("SELECT kind, name, \"sourceId\", \"ownerId\", \"companyName\", notes,"
                + " \"createdById\" FROM \"Deal\" WHERE \"organizationId\" = ? AND \"contactName\" = ?").params(demoOrg,
                        "Grace " + sfx).query().singleRow();
        assertThat(lead.get("kind")).isEqualTo("lead");
        assertThat(lead.get("name")).isEqualTo("Website enquiry: Grace Ltd " + sfx);
        assertThat(lead.get("sourceId")).isEqualTo(source);
        assertThat(lead.get("ownerId")).isEqualTo(adminId);
        assertThat(lead.get("createdById")).isNull();
        assertThat((String) lead.get("notes")).contains("Need 40 chairs", "Contact us " + sfx,
                "https://www.example.com/contact");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'kind' ="
                + " 'crm.lead.web_form' AND body LIKE ?").params(adminId, "%Grace " + sfx + "%").query(Long.class)
                .single()).isEqualTo(1);
        // no Origin header (server-to-server) is accepted; the allow-list is a browser control
        assertThat(anon("POST", path, signed(id, 5000, "name", "Server " + sfx, "phone", "+256 700 123456"), Map.of())
                .status()).isEqualTo(201);
        assertThat(ok(200, "GET", "/crm/config/lead-forms", null).toString()).contains("\"submissions\":2");

        // the form's rolling 24-hour cap: past it the submission is refused (429) and nothing is stored
        ok(200, "PATCH", "/crm/config/lead-forms/" + id, Map.of("dailyLimit", 2));
        assertThat(call("PATCH", "/crm/config/lead-forms/" + id, Map.of("dailyLimit", 0), adminToken).status())
                .isEqualTo(400);
        Res capped = anon("POST", path, signed(id, 5000, "name", "Capped " + sfx, "email", "c@b.test"), site);
        assertThat(capped.status()).isEqualTo(429);
        assertThat(capped.body().get("message").asString()).startsWith("We have received a lot of enquiries today");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE \"organizationId\" = ? AND \"contactName\" = ?")
                .params(demoOrg, "Capped " + sfx).query(Long.class).single()).isZero();
        ok(200, "PATCH", "/crm/config/lead-forms/" + id, Map.of("dailyLimit", 200));

        // CORS preflight from any site is answered for the form routes only, without credentials
        HttpResponse<byte[]> pre = raw("OPTIONS", path, Map.of("Origin", "https://shop.example.org",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type"));
        assertThat(pre.headers().firstValue("Access-Control-Allow-Origin").orElse("")).isIn("*",
                "https://shop.example.org");
        assertThat(pre.headers().firstValue("Access-Control-Allow-Credentials").orElse("false")).isNotEqualTo("true");
        HttpResponse<byte[]> other = raw("OPTIONS", "/crm/leads", Map.of("Origin", "https://shop.example.org",
                "Access-Control-Request-Method", "GET"));
        assertThat(other.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        // other tenant cannot see or change it
        assertThat(call("GET", "/crm/config/lead-forms", null, otherToken).body().toString()).doesNotContain(id);
        assertThat(call("PATCH", "/crm/config/lead-forms/" + id, Map.of("isActive", false), otherToken).status())
                .isEqualTo(404);

        // a rotated key stops the old one; a switched-off form answers 404
        String newKey = ok(200, "POST", "/crm/config/lead-forms/" + id + "/rotate-key", null).get("publicKey").asString();
        assertThat(anon("GET", "/public/crm/forms/" + key, null, Map.of()).status()).isEqualTo(404);
        ok(200, "PATCH", "/crm/config/lead-forms/" + id, Map.of("isActive", false));
        assertThat(anon("POST", "/public/crm/forms/" + newKey + "/submit", signed(id, 5000, "name", "Late", "email",
                "late@example.test"), site).status()).isEqualTo(404);
        assertThat(anon("GET", "/public/crm/forms/nope", null, Map.of()).status()).isEqualTo(404);
        ok(200, "DELETE", "/crm/config/lead-forms/" + id, null);
    }

    // ------------------------------------------------------------------ quote links

    String approvedQuote(String deal) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", service, "quantity", 2, "unitPrice", 100)), "internalNotes", "secret margin " + sfx));
        if (deal != null) {
            body.put("dealId", deal);
        }
        String q = ok(201, "POST", "/crm/quotations", body).get("id").asString();
        JsonNode submitted = ok(200, "POST", "/crm/quotations/" + q + "/submit", null);
        if ("pending_approval".equals(submitted.get("status").asString())) {
            ok(200, "POST", "/crm/quotations/" + q + "/approve", null);
        }
        return q;
    }

    static String token(JsonNode created) {
        return created.get("path").asString().substring("/q/".length());
    }

    @Test
    @DisplayName("a shared quotation can be viewed, downloaded and accepted once; the owner and the timeline hear of it")
    void quoteLinkAccept() throws Exception {
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Online quote " + sfx, "partnerId", customer,
                "amount", 200)).get("id").asString();
        String q = approvedQuote(deal);
        String number = jdbc.sql("SELECT number FROM \"SalesQuotation\" WHERE id = ?").param(q).query(String.class)
                .single();
        assertThat(ok(200, "GET", "/sales/quotations/" + q + "/share-link", null).get("active").asBoolean()).isFalse();
        JsonNode created = ok(201, "POST", "/sales/quotations/" + q + "/share-link", null);
        assertThat(created.get("path").asString()).startsWith("/q/");
        String t = token(created);
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesQuoteLink\" WHERE \"tokenHash\" = ?").param(t)
                .query(Long.class).single()).as("the raw token is never stored").isZero();
        assertThat(call("GET", "/sales/quotations/" + q + "/share-link", null, otherToken).status()).as("other tenant")
                .isEqualTo(404);

        Res view = anon("GET", "/public/sales/quotes/" + t, null, Map.of());
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().get("number").asString()).isEqualTo(number);
        assertThat(view.body().get("canAnswer").asBoolean()).isTrue();
        assertThat(view.body().get("company").get("name").asString()).isNotBlank();
        assertThat(view.body().toString()).doesNotContain("secret margin").doesNotContain(adminId);
        assertThat(view.body().has("credit")).isFalse();
        assertThat(view.body().get("lines").get(0).has("stock")).isFalse();
        anon("GET", "/public/sales/quotes/" + t, null, Map.of());
        JsonNode status = ok(200, "GET", "/sales/quotations/" + q + "/share-link", null);
        assertThat(status.get("viewCount").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE payload->>'quotationId' = ? AND"
                + " payload->>'kind' = 'sales.quote_link.viewed'").param(q).query(Long.class).single())
                .as("owner told once").isEqualTo(1);

        HttpResponse<byte[]> pdf = raw("GET", "/public/sales/quotes/" + t + "/pdf", Map.of());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        assertThat(anon("POST", "/public/sales/quotes/" + t + "/accept", Map.of(), Map.of()).body().get("message")
                .asString()).isEqualTo("Type your full name to accept");
        Res accepted = anon("POST", "/public/sales/quotes/" + t + "/accept", Map.of("signerName", "Grace Buyer",
                "note", "PO to follow"), Map.of());
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("status").asString()).isEqualTo("accepted");
        assertThat(accepted.body().get("canAnswer").asBoolean()).isFalse();
        Map<String, Object> link = jdbc.sql("SELECT decision, \"signerName\", \"signerNote\", \"signerIp\","
                + " \"signerUserAgent\", revision, \"decidedRevision\", \"decisionSnapshot\"::text AS snap FROM"
                + " \"SalesQuoteLink\" WHERE \"quotationId\" = ? AND \"revokedAt\" IS NULL").param(q).query().singleRow();
        assertThat(link.get("decision")).isEqualTo("accepted");
        assertThat(link.get("signerName")).isEqualTo("Grace Buyer");
        assertThat(link.get("signerNote")).isEqualTo("PO to follow");
        assertThat(link.get("signerIp")).isNotNull();
        // production-readiness P0-4: the exact terms accepted are kept immutably with the revision and the client
        assertThat(link.get("signerUserAgent")).isNotNull();
        assertThat(link.get("decidedRevision")).isEqualTo(link.get("revision"));
        assertThat((String) link.get("snap")).contains(number).contains("\"lines\"").contains(service);
        Res again = anon("POST", "/public/sales/quotes/" + t + "/accept", Map.of("signerName", "Grace Buyer"), Map.of());
        assertThat(again.status()).as("the same answer again is an idempotent replay").isEqualTo(200);
        assertThat(again.body().get("status").asString()).isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT \"signerNote\" FROM \"SalesQuoteLink\" WHERE \"quotationId\" = ? AND"
                + " \"revokedAt\" IS NULL").param(q).query(String.class).single()).as("replay changes nothing")
                .isEqualTo("PO to follow");
        assertThat(anon("POST", "/public/sales/quotes/" + t + "/decline", Map.of(), Map.of()).status())
                .as("answered once").isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(deal,
                "Quotation " + number + " accepted online by Grace Buyer").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE payload->>'quotationId' = ? AND"
                + " payload->>'kind' = 'sales.quote_link.accepted'").param(q).query(Long.class).single()).isEqualTo(1);
        assertThat(call("POST", "/sales/quotations/" + q + "/share-link", null, adminToken).status())
                .as("accepted quotes are not re-shared").isEqualTo(400);
    }

    @Test
    @DisplayName("decline, revoke, expiry, revision and draft: each closes or refuses the link")
    void quoteLinkLifecycle() {
        String draft = ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        assertThat(call("POST", "/sales/quotations/" + draft + "/share-link", null, adminToken).body().get("message")
                .asString()).isEqualTo("Only approved or sent quotations can be shared");

        String q = approvedQuote(null);
        String t = token(ok(201, "POST", "/sales/quotations/" + q + "/share-link", null));
        Res declined = anon("POST", "/public/sales/quotes/" + t + "/decline", Map.of("reason", "Too expensive"),
                Map.of());
        assertThat(declined.body().get("status").asString()).isEqualTo("rejected");
        assertThat(jdbc.sql("SELECT \"rejectionReason\" FROM \"SalesQuotation\" WHERE id = ?").param(q)
                .query(String.class).single()).isEqualTo("Too expensive");

        String q2 = approvedQuote(null);
        String first = token(ok(201, "POST", "/sales/quotations/" + q2 + "/share-link", null));
        String second = token(ok(201, "POST", "/sales/quotations/" + q2 + "/share-link", null));
        assertThat(anon("GET", "/public/sales/quotes/" + first, null, Map.of()).status()).as("replaced").isEqualTo(404);
        assertThat(anon("GET", "/public/sales/quotes/" + second, null, Map.of()).status()).isEqualTo(200);

        jdbc.sql("UPDATE \"SalesQuoteLink\" SET \"expiresAt\" = now() - interval '1 minute' WHERE \"quotationId\" = ?"
                + " AND \"revokedAt\" IS NULL").param(q2).update();
        assertThat(anon("GET", "/public/sales/quotes/" + second, null, Map.of()).body().get("message").asString())
                .as("expired").isEqualTo("Quotation link not found");
        jdbc.sql("UPDATE \"SalesQuoteLink\" SET \"expiresAt\" = now() + interval '1 day' WHERE \"quotationId\" = ?"
                + " AND \"revokedAt\" IS NULL").param(q2).update();

        ok(200, "POST", "/crm/quotations/" + q2 + "/revise", null);
        Res revised = anon("GET", "/public/sales/quotes/" + second, null, Map.of());
        assertThat(revised.status()).isEqualTo(404);
        assertThat(revised.body().get("message").asString()).as("revise revokes the link (P0-4)").isEqualTo(
                "Quotation link not found");
        assertThat(anon("POST", "/public/sales/quotes/" + second + "/accept", Map.of("signerName", "Sneaky"),
                Map.of()).status()).as("no accepting a draft").isEqualTo(404);
        // production-readiness P0-4: the revision revoked the link; even un-revoked by hand, a link issued for the
        // old revision never answers the new one once it is approved again
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesQuoteLink\" WHERE \"quotationId\" = ? AND \"revokedAt\""
                + " IS NULL").param(q2).query(Long.class).single()).as("revise revokes the link").isZero();
        JsonNode resubmitted = ok(200, "POST", "/crm/quotations/" + q2 + "/submit", null);
        if ("pending_approval".equals(resubmitted.get("status").asString())) {
            ok(200, "POST", "/crm/quotations/" + q2 + "/approve", null);
        }
        jdbc.sql("UPDATE \"SalesQuoteLink\" SET \"revokedAt\" = NULL WHERE \"quotationId\" = ? AND \"tokenHash\" ="
                + " encode(sha256(convert_to(?, 'UTF8')), 'hex')").params(q2, second).update();
        assertThat(anon("GET", "/public/sales/quotes/" + second, null, Map.of()).body().get("message").asString())
                .isEqualTo("This quotation is being updated; ask us for a new link");
        assertThat(anon("POST", "/public/sales/quotes/" + second + "/accept", Map.of("signerName", "Sneaky"),
                Map.of()).status()).as("old revision's link").isEqualTo(404);
        assertThat(jdbc.sql("SELECT status FROM \"SalesQuotation\" WHERE id = ?").param(q2).query(String.class)
                .single()).isNotEqualTo("accepted");

        ok(200, "DELETE", "/sales/quotations/" + q2 + "/share-link", null);
        assertThat(anon("GET", "/public/sales/quotes/" + second, null, Map.of()).body().get("message").asString())
                .isEqualTo("Quotation link not found");
        assertThat(anon("GET", "/public/sales/quotes/short", null, Map.of()).status()).isEqualTo(404);
    }
}
