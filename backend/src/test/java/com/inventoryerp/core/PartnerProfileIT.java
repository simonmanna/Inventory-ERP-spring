package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Customer detail page: contact CRUD (primary demotes others, deactivate, soft delete), the sales / accounting
 * profile (incoterm, auto-post, accounts, bank accounts), the payment follow-up card (amounts, promise, exclusion),
 * tenant isolation and permissions.
 */
class PartnerProfileIT extends ApiTestSupport {

    String partner() {
        return ok(201, "POST", "/partners", Map.of("name", "Profile " + UUID.randomUUID(), "isCustomer", true))
                .get("id").asString();
    }

    @Test
    @DisplayName("contacts: add, primary demotes others, deactivate, delete")
    void contacts() {
        String p = partner();
        String a = ok(201, "POST", "/partners/" + p + "/contacts", Map.of("firstName", "Ann", "isPrimary", true))
                .get("id").asString();
        JsonNode b = ok(201, "POST", "/partners/" + p + "/contacts", Map.of("firstName", "Bob", "email", "bob@x.test"));
        assertThat(b.get("isActive").asBoolean()).isTrue();
        String bid = b.get("id").asString();
        assertThat(as("POST", "/partners/" + p + "/contacts", Map.of("firstName", " ")).status()).isEqualTo(400);
        assertThat(as("POST", "/partners/" + p + "/contacts", Map.of("firstName", "X", "email", "bad")).status())
                .isEqualTo(400);

        ok(200, "PATCH", "/partners/" + p + "/contacts/" + bid, Map.of("isPrimary", true, "position", "CFO"));
        JsonNode contacts = ok(200, "GET", "/partners/" + p, null).get("contacts");
        assertThat(contacts.size()).isEqualTo(2);
        assertThat(contacts.get(0).get("id").asString()).isEqualTo(bid);
        assertThat(contacts.get(0).get("position").asString()).isEqualTo("CFO");
        assertThat(contacts.get(1).get("isPrimary").asBoolean()).isFalse();

        assertThat(as("PATCH", "/partners/" + p + "/contacts/" + bid, Map.of("isActive", false)).status())
                .isEqualTo(400);
        assertThat(ok(200, "PATCH", "/partners/" + p + "/contacts/" + a, Map.of("isActive", false))
                .get("isActive").asBoolean()).isFalse();
        Map<String, Object> clear = new HashMap<>();
        clear.put("email", null);
        assertThat(ok(200, "PATCH", "/partners/" + p + "/contacts/" + bid, clear).get("email").isNull()).isTrue();

        assertThat(as("DELETE", "/partners/" + p + "/contacts/" + a, null).status()).isEqualTo(204);
        assertThat(ok(200, "GET", "/partners/" + p, null).get("contacts").size()).isEqualTo(1);
        assertThat(as("PATCH", "/partners/" + p + "/contacts/" + a, Map.of("firstName", "Z")).status()).isEqualTo(404);
        // another org cannot touch it
        assertThat(call("PATCH", "/partners/" + p + "/contacts/" + bid, Map.of("firstName", "Z"), otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("profile: sales configuration and accounting preferences")
    void profile() {
        String p = partner();
        JsonNode initial = ok(200, "GET", "/partners/" + p + "/profile", null);
        assertThat(initial.get("autoPostBills").asString()).isEqualTo("ask");
        assertThat(initial.get("bankAccounts").size()).isZero();

        String account = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        JsonNode saved = ok(200, "PATCH", "/partners/" + p + "/profile", Map.of("incoterm", "FOB", "incotermLocation",
                "Mombasa", "autoPostBills", "always", "receivableAccountId", account, "bankAccounts",
                List.of(Map.of("bankName", "Stanbic", "accountNumber", "0012345"))));
        assertThat(saved.get("incoterm").asString()).isEqualTo("FOB");
        assertThat(saved.get("incotermLocation").asString()).isEqualTo("Mombasa");
        assertThat(saved.get("autoPostBills").asString()).isEqualTo("always");
        assertThat(saved.get("receivableAccountId").asString()).isEqualTo(account);
        assertThat(saved.get("bankAccounts").get(0).get("accountNumber").asString()).isEqualTo("0012345");

        Map<String, Object> clear = new HashMap<>();
        clear.put("incoterm", null);
        JsonNode cleared = ok(200, "PATCH", "/partners/" + p + "/profile", clear);
        assertThat(cleared.get("incoterm").isNull()).isTrue();
        assertThat(cleared.get("incotermLocation").asString()).isEqualTo("Mombasa");

        assertThat(as("PATCH", "/partners/" + p + "/profile", Map.of("incoterm", "XYZ")).status()).isEqualTo(400);
        assertThat(as("PATCH", "/partners/" + p + "/profile", Map.of("autoPostBills", "sometimes")).status())
                .isEqualTo(400);
        assertThat(as("PATCH", "/partners/" + p + "/profile", Map.of("receivableAccountId", "nope")).status())
                .isEqualTo(400);
        assertThat(as("PATCH", "/partners/" + p + "/profile", Map.of("bankAccounts", List.of(Map.of("bankName", "x"))))
                .status()).isEqualTo(400);
        assertThat(as("PATCH", "/partners/" + p + "/profile", Map.of("hacker", 1)).status()).isEqualTo(400);
        assertThat(call("GET", "/partners/" + p + "/profile", null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("follow-up: amounts, status, promise to pay, exclusion")
    void followup() {
        String p = partner();
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 100,"
                + " 0, false, now())").params(product, demoOrg, "FU-" + product.substring(0, 6), "Followup svc").update();
        JsonNode empty = ok(200, "GET", "/sales/collections/partners/" + p + "/followup", null);
        assertThat(empty.get("status").asString()).isEqualTo("no_action_needed");
        assertThat(empty.get("amountDue").asDouble()).isZero();

        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", p, "issueDate", LocalDate.now().toString(), "lines",
                List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 5000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        jdbc.sql("UPDATE \"Document\" SET \"dueDate\" = now() - interval '10 days' WHERE id = ?").param(inv).update();

        JsonNode f = ok(200, "GET", "/sales/collections/partners/" + p + "/followup", null);
        assertThat(f.get("status").asString()).isEqualTo("in_need_of_action");
        double due = f.get("amountDue").asDouble();
        assertThat(due).isGreaterThan(0);
        assertThat(f.get("amountOverdue").asDouble()).isEqualTo(due);
        assertThat(f.get("daysOverdue").asInt()).isEqualTo(10);
        assertThat(f.get("lines").size()).isEqualTo(1);

        String next = LocalDate.now().plusDays(7).toString();
        JsonNode saved = ok(200, "PUT", "/sales/collections/partners/" + p + "/followup", Map.of("followupNextReminder",
                next, "followupNote", "Call the customer", "promisedDate", LocalDate.now().plusDays(3).toString(),
                "promiseNote", "Half before the 15th"));
        assertThat(saved.get("status").asString()).isEqualTo("with_overdue_invoices");
        assertThat(saved.get("followupNextReminder").asString()).isEqualTo(next);
        assertThat(saved.get("promise").get("notes").asString()).isEqualTo("Half before the 15th");
        assertThat(saved.get("promise").get("amount").asDouble()).isEqualTo(due);

        Map<String, Object> cancel = new HashMap<>();
        cancel.put("promisedDate", null);
        cancel.put("followupExcluded", true);
        JsonNode after = ok(200, "PUT", "/sales/collections/partners/" + p + "/followup", cancel);
        assertThat(after.get("promise").isNull()).isTrue();
        assertThat(after.get("followupExcluded").asBoolean()).isTrue();
        assertThat(as("PUT", "/sales/collections/partners/" + p + "/followup", Map.of("bogus", 1)).status())
                .isEqualTo(400);
        assertThat(call("GET", "/sales/collections/partners/" + p + "/followup", null, otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("new routes are permission-guarded")
    void permissions() {
        String p = partner();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Prof-" + role,
                new String[] {"partner:read"}).update();
        String email = "prof-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Prof@1234567", role);
        String token = login("DEMO", email, "Prof@1234567");
        assertThat(call("GET", "/partners/" + p + "/profile", null, token).status()).isEqualTo(200);
        assertThat(call("PATCH", "/partners/" + p + "/profile", Map.of("incoterm", "FOB"), token).status())
                .isEqualTo(403);
        assertThat(call("POST", "/partners/" + p + "/contacts", Map.of("firstName", "A"), token).status())
                .isEqualTo(403);
        assertThat(call("GET", "/sales/collections/partners/" + p + "/followup", null, token).status()).isEqualTo(403);
        assertThat(call("PUT", "/sales/collections/partners/" + p + "/followup", Map.of(), token).status())
                .isEqualTo(403);
    }
}
