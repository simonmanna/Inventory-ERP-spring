package com.inventoryerp.customization;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.customization.application.AutomationCatalog;
import com.inventoryerp.customization.application.AutomationEngine;
import com.inventoryerp.customization.web.AutomationRulesController;
import com.inventoryerp.customization.web.CustomFieldsController;
import com.inventoryerp.customization.web.PrintTemplatesController;
import com.inventoryerp.kernel.apiclients.ApiClientsController;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * PR-11 (ADR-S029): custom fields and objects, print templates (render, preview, versions), automation rules (event
 * run, idempotent redelivery, dry run), OAuth client-credentials API clients and the authorization matrix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomizationIT extends ApiTestSupport {

    @Autowired
    AutomationEngine engine;

    String org;
    String code;
    String token;
    String revenue;

    @BeforeAll
    void books() {
        code = "cz-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Custom Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        revenue = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.\"reportSection\"::text = 'revenue' AND a.\"isPostable\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
    }

    @Override
    protected JsonNode ok(int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String message(String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(r.raw().body()).isGreaterThanOrEqualTo(400);
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    String partner(String name) {
        return ok(201, "POST", "/partners", Map.of("code", "P-" + UUID.randomUUID().toString().substring(0, 6), "name",
                name, "isCustomer", true, "email", "buyer@example.com")).get("id").asString();
    }

    String postInvoice(String partnerId, int amount) {
        JsonNode draft = ok(200, "POST", "/invoices", Map.of("partnerId", partnerId, "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("accountId", revenue, "description",
                        "Aggregate <b>", "quantity", 2, "unitPrice", amount / 2))));
        String id = draft.get("id").asString();
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res post = call("POST", "/invoices/" + id + "/post", null, h);
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
        return id;
    }

    @Test
    @DisplayName("custom fields: definitions, validated values, type lock, deactivation")
    void customFields() {
        JsonNode tier = ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "partner", "key", "tier",
                "label", "Tier", "type", "select", "options", Map.of("values", List.of("gold", "silver"))));
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "partner", "key", "creditScore", "label",
                "Credit score", "type", "decimal", "required", true));
        assertThat(message("POST", "/custom-fields/definitions", Map.of("entity", "partner", "key", "tier", "label",
                "x", "type", "text"))).isEqualTo("A custom field with key 'tier' already exists on partner");
        assertThat(message("POST", "/custom-fields/definitions", Map.of("entity", "spaceship", "key", "x", "label",
                "x", "type", "text"))).isEqualTo("Unknown entity 'spaceship'");
        assertThat(message("POST", "/custom-fields/definitions", Map.of("entity", "partner", "key", "Bad Key",
                "label", "x", "type", "text"))).startsWith("key must start with a lowercase letter");
        assertThat(message("POST", "/custom-fields/definitions", Map.of("entity", "partner", "key", "pick", "label",
                "x", "type", "select"))).isEqualTo("options.values must list the choices of a select field");

        String p = partner("Field Partner");
        assertThat(message("PUT", "/custom-fields/values/partner/" + p, Map.of("values", Map.of("tier", "bronze"))))
                .isEqualTo("Tier must be one of: gold, silver; Credit score is required");
        assertThat(message("PUT", "/custom-fields/values/partner/" + p, Map.of("values", Map.of("nope", 1,
                "creditScore", "abc")))).contains("Unknown custom field 'nope'").contains("Credit score must be a number")
                .doesNotContain("is required");
        JsonNode v = ok(200, "PUT", "/custom-fields/values/partner/" + p, Map.of("values", Map.of("tier", "gold",
                "creditScore", "712.50")));
        assertThat(v.get("values").get("creditScore").asString()).isEqualTo("712.5");
        assertThat(v.get("fields")).hasSize(2);
        assertThat(call("PUT", "/custom-fields/values/partner/" + UUID.randomUUID(), Map.of("values", Map.of()), token)
                .status()).isEqualTo(404);
        // Other organizations cannot read or write this record's values.
        assertThat(call("GET", "/custom-fields/values/partner/" + p, null, otherToken).status()).isEqualTo(404);

        String tierId = tier.get("id").asString();
        assertThat(message("PATCH", "/custom-fields/definitions/" + tierId, Map.of("type", "text")))
                .isEqualTo("The type of a field with values cannot change");
        ok(200, "DELETE", "/custom-fields/definitions/" + tierId, null);
        assertThat(ok(200, "GET", "/custom-fields/values/partner/" + p, null).get("values").has("tier")).isFalse();
        assertThat(ok(200, "GET", "/custom-fields/definitions?entity=partner&includeInactive=true", null)).hasSize(2);
        assertThat(ok(200, "GET", "/custom-fields/entities", null).toString()).contains("\"entity\":\"partner\"");
    }

    @Test
    @DisplayName("custom objects: definition, validated records, search, filter, retitle, delete")
    void customObjects() {
        ok(201, "POST", "/custom-objects", Map.of("key", "site_visit", "label", "Site visit"));
        assertThat(message("POST", "/custom-objects", Map.of("key", "site_visit", "label", "x")))
                .isEqualTo("A custom object with key 'site_visit' already exists");
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "x_site_visit", "key", "site", "label", "Site",
                "type", "text", "required", true));
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "x_site_visit", "key", "visited", "label",
                "Visited on", "type", "date"));
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "x_site_visit", "key", "customer", "label",
                "Customer", "type", "reference", "options", Map.of("entity", "partner")));
        ok(200, "PATCH", "/custom-objects/site_visit", Map.of("displayField", "site"));
        assertThat(message("PATCH", "/custom-objects/site_visit", Map.of("displayField", "zzz")))
                .isEqualTo("displayField must be one of the object's fields");

        String p = partner("Visit Partner");
        assertThat(message("POST", "/custom-objects/site_visit/records", Map.of("values", Map.of("visited",
                "31-12-2026")))).isEqualTo("Visited on must be a date (YYYY-MM-DD); Site is required");
        assertThat(message("POST", "/custom-objects/site_visit/records", Map.of("values", Map.of("site", "Quarry",
                "customer", UUID.randomUUID().toString())))).isEqualTo("Customer refers to a record that does not"
                        + " exist");
        JsonNode r1 = ok(201, "POST", "/custom-objects/site_visit/records", Map.of("values", Map.of("site",
                "North quarry", "visited", "2026-11-01", "customer", p)));
        assertThat(r1.get("title").asString()).isEqualTo("North quarry");
        ok(201, "POST", "/custom-objects/site_visit/records", Map.of("values", Map.of("site", "South yard")));
        assertThat(ok(200, "GET", "/custom-objects/site_visit/records?search=north", null).get("data")).hasSize(1);
        assertThat(ok(200, "GET", "/custom-objects/site_visit/records?f.customer=" + p, null).get("data")).hasSize(1);
        assertThat(ok(200, "GET", "/custom-objects/site_visit/records", null).get("meta").get("total").asLong())
                .isEqualTo(2);
        String id = r1.get("id").asString();
        Map<String, Object> patch = new HashMap<>();
        patch.put("site", "North pit");
        patch.put("visited", null);
        JsonNode updated = ok(200, "PATCH", "/custom-objects/site_visit/records/" + id, Map.of("values", patch));
        assertThat(updated.get("values").has("visited")).isFalse();
        assertThat(updated.get("title").asString()).isEqualTo("North pit");
        ok(200, "DELETE", "/custom-objects/site_visit/records/" + id, null);
        assertThat(call("GET", "/custom-objects/site_visit/records/" + id, null, token).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/custom-objects/site_visit", null).get("fields")).hasSize(3);
        assertThat(call("GET", "/custom-objects/site_visit", null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("print templates: render HTML and PDF, preview, validation, versions, default")
    void printTemplates() {
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "sales_invoice", "key", "site", "label", "Site",
                "type", "text"));
        String content = "<h1>{{document.kindLabel}} {{document.number}}</h1><p>{{partner.name}}</p><table>"
                + "{{#lines}}<tr><td>{{no}}</td><td>{{description}}</td><td>{{total}}</td></tr>{{/lines}}</table>"
                + "<p>Total {{document.total}} {{document.currency}}</p><p>Site: {{custom.site}}</p>";
        assertThat(message("POST", "/print-templates", Map.of("documentKind", "sales_invoice", "name", "Bad",
                "content", "{{#lines}}x"))).isEqualTo("Unclosed section {{#lines}}");
        JsonNode t = ok(201, "POST", "/print-templates", Map.of("documentKind", "sales_invoice", "name", "Ours",
                "content", content, "css", "h1 { color: #333 }"));
        assertThat(t.get("isDefault").asBoolean()).isTrue();
        assertThat(t.get("version").asInt()).isEqualTo(1);

        String p = partner("Print & Partner");
        String invoice = postInvoice(p, 300);
        ok(200, "PUT", "/custom-fields/values/sales_invoice/" + invoice, Map.of("values", Map.of("site", "Gate 4")));
        HttpResponse<byte[]> htmlRes = rawGet("/print-templates/render/sales_invoice/" + invoice);
        String html = new String(htmlRes.body(), StandardCharsets.UTF_8);
        assertThat(htmlRes.statusCode()).as(html).isEqualTo(200);
        assertThat(html).contains("<h1>Invoice INV-").contains("Print &amp; Partner")
                .contains("Aggregate &lt;b&gt;").contains("300.00").contains("Site: Gate 4").contains("h1 { color: #333 }");

        HttpResponse<byte[]> pdf = rawGet("/print-templates/render/sales_invoice/" + invoice + "?format=pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        JsonNode preview = ok(200, "POST", "/print-templates/preview", Map.of("documentKind", "sales_invoice",
                "content", "{{partner.name}}|{{#lines}}{{description}};{{/lines}}"));
        assertThat(preview.get("html").asString()).contains("Sample Customer Ltd|Cement 50 kg;Delivery;");
        assertThat(ok(200, "GET", "/print-templates/fields/sales_invoice", null).toString()).contains("lines[].total")
                .contains("custom.site");

        String id = t.get("id").asString();
        ok(200, "PATCH", "/print-templates/" + id, Map.of("content", "<p>{{document.number}}</p>"));
        assertThat(ok(200, "GET", "/print-templates/" + id + "/versions", null)).hasSize(2);
        JsonNode restored = ok(200, "POST", "/print-templates/" + id + "/versions/1/restore", null);
        assertThat(restored.get("version").asInt()).isEqualTo(3);
        assertThat(restored.get("content").asString()).isEqualTo(content);

        JsonNode second = ok(201, "POST", "/print-templates", Map.of("documentKind", "sales_invoice", "name",
                "Second", "content", "x", "isDefault", true));
        assertThat(second.get("isDefault").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/print-templates/" + id, null).get("isDefault").asBoolean()).isFalse();
        assertThat(call("GET", "/print-templates/render/vendor_bill/" + invoice, null, token).status()).isEqualTo(404);
        assertThat(call("GET", "/print-templates/render/sales_invoice/" + invoice, null, otherToken).status())
                .isEqualTo(404);
    }

    HttpResponse<byte[]> rawGet(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("automation: conditions, actions, run log, idempotent redelivery, dry run")
    void automation() {
        ok(201, "POST", "/custom-fields/definitions", Map.of("entity", "sales_invoice", "key", "review", "label",
                "Review", "type", "text"));
        String adminRole = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(org).query(String.class).single();
        assertThat(message("POST", "/automation-rules", Map.of("name", "x", "trigger", "invoice.exploded", "actions",
                List.of(Map.of("type", "notify"))))).startsWith("trigger must be one of the following values:");
        assertThat(message("POST", "/automation-rules", Map.of("name", "x", "trigger", "invoice.posted", "actions",
                List.of(Map.of("type", "webhook", "url", "http://example.com")))))
                .isEqualTo("A webhook action needs an https:// url");
        assertThat(message("POST", "/automation-rules", Map.of("name", "x", "trigger", "invoice.posted", "actions",
                List.of(Map.of("type", "set_custom_field", "key", "nope")))))
                .isEqualTo("set_custom_field needs the key of an active custom field of sales_invoice");
        JsonNode rule = ok(201, "POST", "/automation-rules", Map.of("name", "Big invoices", "trigger",
                "invoice.posted", "conditions", List.of(Map.of("field", "document.totalRaw", "op", "gte", "value",
                        200)), "actions", List.of(
                                Map.of("type", "set_custom_field", "key", "review", "value",
                                        "Check {{document.number}}"),
                                Map.of("type", "notify", "roleId", adminRole, "title", "Big invoice {{document.number}}"),
                                Map.of("type", "create_task", "title", "Review {{document.number}} for {{partner.name}}",
                                        "dueInDays", 2),
                                Map.of("type", "email", "to", "{{partner.email}}", "subject", "Thanks", "body",
                                        "Total {{document.total}}"))));
        String ruleId = rule.get("id").asString();

        String p = partner("Auto Partner");
        String big = postInvoice(p, 400);
        String small = postInvoice(p, 50);
        drainOutboxFully();

        JsonNode runs = ok(200, "GET", "/automation-rules/" + ruleId + "/runs", null).get("data");
        JsonNode bigRun = null;
        JsonNode smallRun = null;
        for (JsonNode r : runs) {
            if (big.equals(r.get("recordId").asString())) {
                bigRun = r;
            } else if (small.equals(r.get("recordId").asString())) {
                smallRun = r;
            }
        }
        assertThat(bigRun).as(runs.toString()).isNotNull();
        assertThat(smallRun).as(runs.toString()).isNotNull();
        assertThat(bigRun.get("status").asString()).as(bigRun.toString()).isEqualTo("succeeded");
        assertThat(bigRun.get("results")).hasSize(4);
        assertThat(smallRun.get("matched").asBoolean()).isFalse();
        assertThat(smallRun.get("status").asString()).isEqualTo("skipped");
        String number = jdbc.sql("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ?").param(big)
                .query(String.class).single();
        assertThat(ok(200, "GET", "/custom-fields/values/sales_invoice/" + big, null).get("values").get("review")
                .asString()).isEqualTo("Check " + number);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title = ?")
                .params(org, "Big invoice " + number).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Task\" WHERE \"organizationId\" = ? AND title = ?")
                .params(org, "Review " + number + " for Auto Partner").query(Long.class).single()).isEqualTo(1);
        long matchedRuns = jdbc.sql("SELECT count(*) FROM \"AutomationRun\" WHERE \"ruleId\" = ? AND matched").param(ruleId)
                .query(Long.class).single();
        assertThat(ok(200, "GET", "/automation-rules/" + ruleId, null).get("runCount").asLong()).isEqualTo(matchedRuns);

        // Redelivered event: no second run, no second task.
        engine.onEvent(AutomationCatalog.TRIGGERS.get("invoice.posted"), org, big);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Task\" WHERE \"organizationId\" = ? AND title = ?")
                .params(org, "Review " + number + " for Auto Partner").query(Long.class).single()).isEqualTo(1);

        JsonNode dry = ok(200, "POST", "/automation-rules/" + ruleId + "/test", Map.of("recordId", small));
        assertThat(dry.get("matched").asBoolean()).isFalse();
        assertThat(dry.get("conditions").get(0).get("actual").asString()).isEqualTo("50.00");
        assertThat(dry.get("actions").get(2).get("title").asString()).contains("for Auto Partner");
        assertThat(ok(200, "GET", "/automation-rules/catalog", null).get("triggers")).hasSize(7);
        ok(200, "PATCH", "/automation-rules/" + ruleId, Map.of("isActive", false));
        ok(200, "DELETE", "/automation-rules/" + ruleId, null);
    }

    Res tokenForm(String body, String basic) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/oauth/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (basic != null) {
                b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(
                        StandardCharsets.UTF_8)));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String form(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v,
                StandardCharsets.UTF_8)));
        return b.toString();
    }

    @Test
    @DisplayName("api clients: client-credentials token, scopes enforced, user routes closed, rotate, revoke")
    void apiClients() {
        JsonNode created = ok(201, "POST", "/api-clients", Map.of("name", "Integration", "scopes",
                List.of("partner:read", "invoice:read")));
        String clientId = created.get("clientId").asString();
        String secret = created.get("clientSecret").asString();
        assertThat(clientId).startsWith("ierp_");
        assertThat(secret).startsWith("ierps_");
        assertThat(ok(200, "GET", "/api-clients", null).toString()).doesNotContain("secretHash")
                .doesNotContain(secret);
        assertThat(message("POST", "/api-clients", Map.of("name", "x", "scopes", List.of("nope:read"))))
                .isEqualTo("Unknown scope 'nope:read'");

        Res bad = tokenForm(form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret",
                "wrong")), null);
        assertThat(bad.status()).as(bad.raw().body()).isEqualTo(401);
        assertThat(bad.body().get("error").asString()).isEqualTo("invalid_client");
        assertThat(tokenForm(form(Map.of("grant_type", "password")), null).body().get("error").asString())
                .isEqualTo("unsupported_grant_type");
        assertThat(tokenForm(form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret",
                secret, "scope", "partner:create")), null).body().get("error").asString()).isEqualTo("invalid_scope");

        Res granted = tokenForm(form(Map.of("grant_type", "client_credentials", "scope", "partner:read")),
                clientId + ":" + secret);
        assertThat(granted.status()).as(granted.raw().body()).isEqualTo(200);
        assertThat(granted.body().get("token_type").asString()).isEqualTo("Bearer");
        assertThat(granted.body().get("expires_in").asInt()).isEqualTo(3600);
        assertThat(granted.raw().headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String clientToken = granted.body().get("access_token").asString();

        assertThat(call("GET", "/partners", null, clientToken).status()).isEqualTo(200);
        Res notGranted = call("GET", "/invoices", null, clientToken);
        assertThat(notGranted.status()).isEqualTo(403);
        assertThat(notGranted.body().get("message").asString()).isEqualTo("Missing required permission(s):"
                + " invoice:read");
        Res userRoute = call("GET", "/auth/me", null, clientToken);
        assertThat(userRoute.status()).isEqualTo(403);
        assertThat(userRoute.body().get("message").asString())
                .isEqualTo("API clients can only call permission-protected routes");
        assertThat(call("POST", "/partners", Map.of("code", "NO", "name", "No"), clientToken).status()).isEqualTo(403);

        // Rotating makes the old secret useless; revoking closes live tokens.
        String rotated = ok(200, "POST", "/api-clients/" + created.get("id").asString() + "/rotate-secret", null)
                .get("clientSecret").asString();
        assertThat(tokenForm(form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret",
                secret)), null).status()).isEqualTo(401);
        assertThat(tokenForm(form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret",
                rotated)), null).status()).isEqualTo(200);
        ok(200, "DELETE", "/api-clients/" + created.get("id").asString(), null);
        assertThat(call("GET", "/partners", null, clientToken).status()).isEqualTo(403);
        assertThat(tokenForm(form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret",
                rotated)), null).status()).isEqualTo(401);
        // A client token is never a user token.
        assertThat(call("GET", "/api-clients", null, clientToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("authorization: every customization route needs a token and its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CustomFieldsController.class, PrintTemplatesController.class,
                AutomationRulesController.class, ApiClientsController.class)).isEqualTo(41);
    }
}
