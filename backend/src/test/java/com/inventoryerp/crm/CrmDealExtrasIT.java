package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * CRM enhancements T3b: competitors and forecast categories, deal product lines, extra contacts, merging deals and
 * customers, and email templates.
 */
class CrmDealExtrasIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String service;

    @BeforeAll
    void fixtures() {
        customer = partner("Extras Customer");
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "EX-" + sfx, "Extras service " + sfx).update();
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "EX-" + id.substring(0,
                        8), name + " " + sfx).update();
        return id;
    }

    String contact(String partnerId, String first) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Contact\" (id, \"organizationId\", \"partnerId\", \"firstName\", \"lastName\", email,"
                + " \"isPrimary\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, now())").params(id, demoOrg, partnerId,
                        first, sfx, first.toLowerCase() + "-" + sfx.toLowerCase() + "@example.test").update();
        return id;
    }

    String opportunity(String name, String partnerId) {
        return ok(201, "POST", "/crm/opportunities", Map.of("name", name + " " + sfx, "partnerId", partnerId, "amount",
                50)).get("id").asString();
    }

    @Test
    @DisplayName("competitors are a lookup; a lost deal records one; forecast categories feed the report")
    void competitorsAndForecast() {
        String rival = ok(201, "POST", "/crm/config/competitors", Map.of("name", "Rival " + sfx)).get("id").asString();
        assertThat(ok(200, "GET", "/crm/config/lookups", null).get("competitors").toString()).contains(rival);
        String a = opportunity("Forecast A", customer);
        assertThat(call("PATCH", "/crm/opportunities/" + a, Map.of("forecastCategory", "maybe"), adminToken).status())
                .isEqualTo(400);
        assertThat(ok(200, "PATCH", "/crm/opportunities/" + a, Map.of("forecastCategory", "commit"))
                .get("forecastCategory").asString()).isEqualTo("commit");

        String b = opportunity("Lost to rival", customer);
        assertThat(call("POST", "/crm/opportunities/" + b + "/lost", Map.of("competitorId", UUID.randomUUID()
                .toString()), adminToken).status()).isEqualTo(404);
        assertThat(ok(200, "POST", "/crm/opportunities/" + b + "/lost", Map.of("competitorId", rival))
                .get("competitorId").asString()).isEqualTo(rival);

        JsonNode report = ok(200, "GET", "/crm/reports", null);
        assertThat(report.get("lostCompetitors").toString()).contains("Rival " + sfx);
        List<String> keys = new ArrayList<>();
        report.get("forecastCategories").forEach(r -> keys.add(r.get("key").asString()));
        assertThat(keys).contains("commit");
        assertThat(report.get("forecast").get(0).has("bestCase") || report.get("forecast").isEmpty()).isTrue();
        assertThat(call("GET", "/crm/config/lookups", null, otherToken).body().toString()).doesNotContain(rival);
    }

    @Test
    @DisplayName("product lines replace each other, set the expected revenue and carry what a quotation needs")
    void productLines() {
        String deal = opportunity("Lines", customer);
        JsonNode saved = ok(200, "PUT", "/crm/opportunities/" + deal + "/lines", Map.of("lines", List.of(
                Map.of("productId", service, "quantity", 3, "unitPrice", 100, "discountPercent", 10),
                Map.of("description", "Installation", "quantity", "1", "unitPrice", "250.50"))));
        assertThat(saved.get("lines")).hasSize(2);
        assertThat(saved.get("lines").get(0).get("description").asString()).isEqualTo("Extras service " + sfx);
        assertThat(saved.get("lines").get(0).get("productName").asString()).isEqualTo("Extras service " + sfx);
        assertThat(saved.get("lines").get(0).has("taxId")).isTrue();
        assertThat(new BigDecimal(saved.get("amount").asString())).isEqualByComparingTo("520.50");

        assertThat(call("PUT", "/crm/opportunities/" + deal + "/lines", Map.of("lines", List.of(Map.of("description",
                "Zero", "quantity", 0))), adminToken).body().get("message").asString())
                .isEqualTo("Line 1: quantity must be greater than 0");
        assertThat(call("PUT", "/crm/opportunities/" + deal + "/lines", Map.of("lines", List.of(Map.of("productId",
                UUID.randomUUID().toString()))), adminToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/crm/opportunities/" + deal + "/lines", Map.of("lines", List.of(Map.of("quantity", 1))),
                adminToken).status()).isEqualTo(400);
        assertThat(call("PUT", "/crm/opportunities/" + deal + "/lines", Map.of("lines", List.of()), otherToken).status())
                .as("other tenant").isIn(403, 404);

        JsonNode kept = ok(200, "PUT", "/crm/opportunities/" + deal + "/lines", Map.of("updateAmount", false, "lines",
                List.of(Map.of("description", "Only this", "quantity", 1, "unitPrice", 5))));
        assertThat(kept.get("lines")).hasSize(1);
        assertThat(new BigDecimal(kept.get("amount").asString())).isEqualByComparingTo("520.50");
    }

    @Test
    @DisplayName("several contacts on a deal, with roles; unknown contacts are refused")
    void dealContacts() {
        String deal = opportunity("Contacts", customer);
        String ann = contact(customer, "Ann");
        String bob = contact(customer, "Bob");
        ok(201, "POST", "/crm/opportunities/" + deal + "/contacts", Map.of("contactId", ann, "role", "Decision maker"));
        JsonNode after = ok(201, "POST", "/crm/opportunities/" + deal + "/contacts", Map.of("contactId", bob, "role",
                "Finance"));
        assertThat(after.get("contacts")).hasSize(2);
        ok(201, "POST", "/crm/opportunities/" + deal + "/contacts", Map.of("contactId", ann, "role", "Sponsor"));
        JsonNode detail = ok(200, "GET", "/crm/opportunities/" + deal, null);
        assertThat(detail.get("contacts").toString()).contains("Sponsor").doesNotContain("Decision maker");
        assertThat(call("POST", "/crm/opportunities/" + deal + "/contacts", Map.of("contactId", UUID.randomUUID()
                .toString()), adminToken).status()).isEqualTo(404);
        assertThat(ok(200, "DELETE", "/crm/opportunities/" + deal + "/contacts/" + bob, null).get("contacts"))
                .hasSize(1);
    }

    @Test
    @DisplayName("merging a duplicate moves its activities, quotations and lines, fills gaps and archives it")
    void mergeDeals() {
        String target = opportunity("Keep", customer);
        String source = opportunity("Duplicate", customer);
        jdbc.sql("UPDATE \"Deal\" SET phone = '+256 700 999888', notes = 'Met at fair' WHERE id = ?").param(source)
                .update();
        jdbc.sql("UPDATE \"Deal\" SET phone = NULL WHERE id = ?").param(target).update();
        ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", "Call dup " + sfx, "dealId", source));
        String q = ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "dealId", source, "lines", List.of(
                Map.of("productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        ok(200, "PUT", "/crm/opportunities/" + source + "/lines", Map.of("lines", List.of(Map.of("description",
                "Dup line", "quantity", 1, "unitPrice", 10))));

        assertThat(call("POST", "/crm/opportunities/" + target + "/merge", Map.of("sourceId", target), adminToken)
                .status()).isEqualTo(400);
        assertThat(call("POST", "/crm/leads/" + target + "/merge", Map.of("sourceId", source), adminToken).status())
                .as("lead route on opportunities").isEqualTo(404);
        JsonNode merged = ok(200, "POST", "/crm/opportunities/" + target + "/merge", Map.of("sourceId", source));
        assertThat(merged.get("merged").get("quotations").asInt()).isEqualTo(1);
        assertThat(merged.get("phone").asString()).isEqualTo("+256 700 999888");
        assertThat(merged.get("notes").asString()).contains("Met at fair");
        assertThat(merged.get("lines").toString()).contains("Dup line");
        assertThat(jdbc.sql("SELECT \"dealId\" FROM \"SalesQuotation\" WHERE id = ?").param(q).query(String.class)
                .single()).isEqualTo(target);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Activity\" WHERE \"dealId\" = ? AND title = ?").params(target,
                "Call dup " + sfx).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"deletedAt\" IS NOT NULL FROM \"Deal\" WHERE id = ?").param(source)
                .query(Boolean.class).single()).isTrue();

        String won = opportunity("Won dup", customer);
        ok(200, "POST", "/crm/opportunities/" + won + "/won", null);
        assertThat(call("POST", "/crm/opportunities/" + target + "/merge", Map.of("sourceId", won), adminToken).status())
                .isEqualTo(400);
        String elsewhere = opportunity("Other customer", partner("Elsewhere"));
        assertThat(call("POST", "/crm/opportunities/" + target + "/merge", Map.of("sourceId", elsewhere), adminToken)
                .body().get("message").asString()).isEqualTo(
                        "The two records belong to different customers; merge the customers first");
    }

    @Test
    @DisplayName("a CRM-only duplicate customer merges; one with records outside CRM is refused")
    void mergeCustomers() {
        String keep = partner("Keep Co");
        String dup = partner("Keep Co dup");
        contact(keep, "Main");
        String dupContact = contact(dup, "Second");
        String deal = opportunity("Dup customer deal", dup);
        JsonNode preview = ok(200, "GET", "/crm/customers/" + keep + "/merge-preview?sourceId=" + dup, null);
        assertThat(preview.get("moves").get("Deal").asInt()).isEqualTo(1);
        assertThat(preview.get("blockedBy")).isEmpty();
        assertThat(call("GET", "/crm/customers/" + keep + "/merge-preview?sourceId=" + dup, null, otherToken).status())
                .isIn(403, 404);

        JsonNode merged = ok(200, "POST", "/crm/customers/" + keep + "/merge", Map.of("sourceId", dup));
        assertThat(merged.get("moved").get("Contact").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"Deal\" WHERE id = ?").param(deal).query(String.class).single())
                .isEqualTo(keep);
        Map<String, Object> c = jdbc.sql("SELECT \"partnerId\", \"isPrimary\" FROM \"Contact\" WHERE id = ?").param(
                dupContact).query().singleRow();
        assertThat(c.get("partnerId")).isEqualTo(keep);
        assertThat(c.get("isPrimary")).as("the kept customer's primary contact stays primary").isEqualTo(false);
        assertThat(jdbc.sql("SELECT \"deletedAt\" IS NOT NULL FROM \"Partner\" WHERE id = ?").param(dup)
                .query(Boolean.class).single()).isTrue();

        String busy = partner("Busy Co");
        jdbc.sql("INSERT INTO \"CustomerPortalAccess\" (id, \"organizationId\", \"partnerId\", \"tokenHash\", \"expiresAt\")"
                + " VALUES (?, ?, ?, ?, now() + interval '1 day')").params(UUID.randomUUID().toString(), demoOrg, busy,
                        UUID.randomUUID().toString()).update();
        Res refused = call("POST", "/crm/customers/" + keep + "/merge", Map.of("sourceId", busy), adminToken);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("message").asString()).contains("CustomerPortalAccess");
        assertThat(jdbc.sql("SELECT \"deletedAt\" IS NULL FROM \"Partner\" WHERE id = ?").param(busy)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("email templates render placeholders; sending logs a done email on the record")
    void emailTemplates() {
        assertThat(call("POST", "/crm/config/email-templates", Map.of("name", "Bad " + sfx, "subject", "Hi {{nope}}",
                "body", "x"), adminToken).status()).isEqualTo(400);
        String t = ok(201, "POST", "/crm/config/email-templates", Map.of("name", "Follow-up " + sfx, "subject",
                "About {{deal_name}}", "body", "Dear {{first_name}},\n\nThanks for meeting us. — {{my_name}}"))
                .get("id").asString();
        assertThat(call("POST", "/crm/config/email-templates", Map.of("name", "follow-up " + sfx, "subject", "s",
                "body", "b"), adminToken).status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/crm/config/email-templates", null).get("placeholders").toString())
                .contains("first_name");

        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Mail deal " + sfx, "contactName",
                "Grace Akello", "email", "grace-" + sfx.toLowerCase() + "@example.test")).get("id").asString();
        JsonNode draft = ok(200, "GET", "/crm/opportunities/" + deal + "/email?templateId=" + t, null);
        assertThat(draft.get("to").asString()).isEqualTo("grace-" + sfx.toLowerCase() + "@example.test");
        assertThat(draft.get("subject").asString()).isEqualTo("About Mail deal " + sfx);
        assertThat(draft.get("body").asString()).startsWith("Dear Grace,").doesNotContain("{{");

        assertThat(call("POST", "/crm/opportunities/" + deal + "/email", Map.of("to", "a@b.test, c@d.test", "subject",
                "x", "body", "y"), adminToken).status()).isEqualTo(400);
        JsonNode sent = ok(200, "POST", "/crm/opportunities/" + deal + "/email", Map.of("to", draft.get("to")
                .asString(), "subject", draft.get("subject").asString(), "body", draft.get("body").asString()));
        assertThat(sent.get("sentTo").asString()).isEqualTo(draft.get("to").asString());
        Map<String, Object> a = jdbc.sql("SELECT type::text AS type, status::text AS status, body FROM \"Activity\" WHERE"
                + " \"dealId\" = ? AND title = ?").params(deal, "Email: About Mail deal " + sfx).query().singleRow();
        assertThat(a.get("type")).isEqualTo("email");
        assertThat(a.get("status")).isEqualTo("done");
        assertThat((String) a.get("body")).contains("Dear Grace,");
        assertThat(call("GET", "/crm/leads/" + deal + "/email", null, adminToken).status()).as("not a lead")
                .isEqualTo(404);
        ok(200, "DELETE", "/crm/config/email-templates/" + t, null);
    }
}
