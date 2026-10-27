package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Core master data on Testcontainers Postgres: generic CRUD contract (pagination, search, includes, soft
 * delete, Nest validation errors), partners with nested contacts/addresses, products (inventory config,
 * beverage config, packagings, barcode, restore), UoM conversion, payment terms, fiscal positions,
 * organization bootstrap/invite, authorization matrix and tenant isolation.
 */
class CoreIT extends ApiTestSupport {

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> messages(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("message").forEach(m -> out.add(m.asString()));
        return out;
    }

    @Test
    @DisplayName("branches: CRUD + list envelope + search + soft delete + 404 message")
    void branches() {
        String code = uniq("BR");
        JsonNode created = ok(201, "POST", "/branches", Map.of("code", code, "name", "Lakeview"));
        String id = created.get("id").asString();
        assertThat(created.get("organizationId").asString()).isEqualTo(demoOrg);
        assertThat(created.get("isActive").asBoolean()).isTrue();
        assertThat(created.get("deletedAt").isNull()).isTrue();

        JsonNode page = ok(200, "GET", "/branches?search=" + code.toLowerCase() + "&pageSize=5", null);
        assertThat(page.get("data")).hasSize(1);
        assertThat(page.get("meta").get("pageSize").asInt()).isEqualTo(5);
        assertThat(page.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(page.get("meta").get("totalPages").asInt()).isEqualTo(1);

        JsonNode updated = ok(200, "PATCH", "/branches/" + id, Map.of("name", "Lakeview 2", "isActive", false));
        assertThat(updated.get("name").asString()).isEqualTo("Lakeview 2");
        assertThat(updated.get("code").asString()).isEqualTo(code);

        assertThat(as("DELETE", "/branches/" + id, null).status()).isEqualTo(204);
        Res gone = as("GET", "/branches/" + id, null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("Branch " + id + " not found");
        assertThat(as("PATCH", "/branches/" + id, Map.of("name", "x")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("pagination query is validated like PaginationDto (whitelist + class-validator messages)")
    void paginationValidation() {
        Res unknown = as("GET", "/branches?foo=1", null);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(messages(unknown.body())).containsExactly("property foo should not exist");
        Res zero = as("GET", "/branches?page=0&pageSize=5000&sortOrder=up", null);
        assertThat(zero.status()).isEqualTo(400);
        assertThat(messages(zero.body())).containsExactly("page must not be less than 1",
                "pageSize must not be greater than 1000", "sortOrder must be one of the following values: asc, desc");
        Res body = as("POST", "/branches", Map.of("name", "x", "bogus", 1));
        assertThat(body.status()).isEqualTo(400);
        // class-validator reports the whitelist error together with the field errors (verified on Nest).
        assertThat(messages(body.body())).containsExactly("property bogus should not exist", "code must be a string");
    }

    @Test
    @DisplayName("partners: nested contacts/addresses, includes, auto code, replace on update, soft delete")
    void partners() {
        String catId = ok(201, "POST", "/partner-categories", Map.of("name", uniq("VIP"))).get("id").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Acme Ltd");
        body.put("isCustomer", true);
        body.put("categoryId", catId);
        body.put("customFields", Map.of("tier", "A"));
        body.put("contacts", List.of(Map.of("firstName", "Ann"), Map.of("firstName", "Bob", "isPrimary", true)));
        body.put("addresses", List.of(Map.of("line1", "1 Main St", "city", "Kampala")));
        JsonNode created = ok(201, "POST", "/partners", body);
        String id = created.get("id").asString();
        assertThat(created.get("code").asString()).startsWith("CUST-");
        assertThat(created.get("customFields").get("tier").asString()).isEqualTo("A");
        assertThat(created.has("contacts")).isFalse();

        JsonNode one = ok(200, "GET", "/partners/" + id, null);
        assertThat(one.get("category").get("id").asString()).isEqualTo(catId);
        assertThat(one.get("contacts")).hasSize(2);
        assertThat(one.get("contacts").get(0).get("firstName").asString()).isEqualTo("Bob");
        assertThat(one.get("addresses").get(0).get("type").asString()).isEqualTo("billing");
        assertThat(one.get("creditLimit").asString()).isEqualTo("0");

        JsonNode cat = ok(200, "GET", "/partner-categories/" + catId, null);
        assertThat(cat.get("_count").get("partners").asInt()).isEqualTo(1);
        assertThat(cat.get("_count").get("children").asInt()).isZero();
        assertThat(cat.get("parent").isNull()).isTrue();

        JsonNode updated = ok(200, "PATCH", "/partners/" + id,
                Map.of("phone", "+256700000000", "contacts", List.of(Map.of("firstName", "Cid"))));
        assertThat(updated.get("phone").asString()).isEqualTo("+256700000000");
        assertThat(ok(200, "GET", "/partners/" + id, null).get("contacts")).hasSize(1);

        Res bad = as("POST", "/partners", Map.of("name", "", "email", "nope", "membershipLevel", "platinum"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(messages(bad.body())).contains("name should not be empty", "email must be an email",
                "membershipLevel must be one of the following values: bronze, silver, gold");

        assertThat(as("DELETE", "/partners/" + id, null).status()).isEqualTo(204);
        assertThat(as("GET", "/partners/" + id, null).body().get("message").asString())
                .isEqualTo("Partner " + id + " not found");
        // Nest: deleting an unknown partner still answers 204 (updateMany count is not checked).
        assertThat(as("DELETE", "/partners/" + UUID.randomUUID(), null).status()).isEqualTo(204);
        Integer events = jdbc.sql("SELECT count(*)::int FROM \"EventOutbox\" WHERE \"eventName\" LIKE 'partner.%'"
                + " AND payload->>'id' = ?").param(id).query(Integer.class).single();
        assertThat(events).isEqualTo(3);
    }

    @Test
    @DisplayName("products: inventory/beverage rules, packagings, includes, barcode, search, delete + restore")
    void products() {
        String code = uniq("P");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Espresso Beans " + code);
        body.put("salesPrice", 12.5);
        body.put("costingMethod", "FIFO");
        body.put("packagings", List.of(Map.of("name", "Case", "quantity", 24, "barcode", "PK-" + code)));
        JsonNode created = ok(201, "POST", "/products", body);
        String id = created.get("id").asString();
        assertThat(created.get("batchTracking").asBoolean()).isTrue();
        assertThat(created.get("salesPrice").asString()).isEqualTo("12.5");
        assertThat(created.get("packagings")).isEmpty();
        assertThat(created.has("uom")).isTrue();

        JsonNode one = ok(200, "GET", "/products/" + id, null);
        assertThat(one.get("packagings")).hasSize(1);
        assertThat(one.get("packagings").get(0).get("quantity").asString()).isEqualTo("24");

        JsonNode scan = ok(200, "GET", "/products/resolve-barcode?code=PK-" + code, null);
        assertThat(scan.get("productId").asString()).isEqualTo(id);
        assertThat(scan.get("quantity").asInt()).isEqualTo(24);
        assertThat(scan.get("packaging").get("name").asString()).isEqualTo("Case");
        Res miss = as("GET", "/products/resolve-barcode?code=nothing-" + code, null);
        assertThat(miss.status()).isEqualTo(200);
        assertThat(miss.raw().body()).isEmpty();

        JsonNode found = ok(200, "GET", "/products/search?q=" + code.toLowerCase(), null);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).properties().stream().map(Map.Entry::getKey).toList())
                .containsExactly("id", "code", "name", "salesPrice", "productType", "station", "categoryId");

        Res bev = as("PATCH", "/products/" + id, Map.of("measurementMethod", "digital_weight"));
        assertThat(bev.status()).isEqualTo(400);
        assertThat(bev.body().get("message").asString()).isEqualTo(
                "Digital-weight products require container volume, empty bottle weight, and full bottle weight.");
        JsonNode bottle = ok(200, "PATCH", "/products/" + id, Map.of("measurementMethod", "digital_weight",
                "containerVolumeMl", 750, "emptyBottleWeightG", 500, "fullBottleWeightG", 1250));
        assertThat(bottle.get("liquidWeightG").asString()).isEqualTo("750");
        assertThat(bottle.get("conversionFactorMlPerG").asString()).isEqualTo("1");

        JsonNode list = ok(200, "GET", "/products?search=" + code + "&productType=stockable", null);
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);

        assertThat(as("DELETE", "/products/" + id, null).status()).isEqualTo(204);
        assertThat(ok(200, "GET", "/products?search=" + code, null).get("meta").get("total").asInt()).isZero();
        JsonNode restored = ok(200, "PATCH", "/products/" + id + "/restore", null);
        assertThat(restored.get("isActive").asBoolean()).isTrue();
        assertThat(restored.has("packagings")).isFalse();
        Res again = as("PATCH", "/products/" + id + "/restore", null);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.body().get("message").asString()).isEqualTo("Deleted Product " + id + " not found");
    }

    @Test
    @DisplayName("uoms: factor/ratio sync, convert within a category, cross-category error")
    void uoms() {
        String cat = ok(201, "POST", "/uom-categories", Map.of("name", uniq("Mass"))).get("id").asString();
        JsonNode kg = ok(201, "POST", "/uoms", Map.of("code", uniq("KG"), "name", "Kilogram", "categoryId", cat,
                "factor", 1, "roundingPrecision", 0.001));
        assertThat(kg.get("ratio").asString()).isEqualTo("1");
        JsonNode g = ok(201, "POST", "/uoms", Map.of("code", uniq("G"), "name", "Gram", "categoryId", cat,
                "ratio", 0.001, "roundingPrecision", 0.001));
        assertThat(g.get("factor").asString()).isEqualTo("0.001");
        JsonNode r = ok(201, "POST", "/uoms/convert", Map.of("quantity", 18, "fromUomId", g.get("id").asString(),
                "toUomId", kg.get("id").asString()));
        assertThat(r.get("result").asString()).isEqualTo("0.018");

        String other = ok(201, "POST", "/uom-categories", Map.of("name", uniq("Vol"))).get("id").asString();
        JsonNode l = ok(201, "POST", "/uoms", Map.of("code", uniq("L"), "name", "Litre", "categoryId", other, "factor", 1));
        Res cross = as("POST", "/uoms/convert", Map.of("quantity", 1, "fromUomId", l.get("id").asString(),
                "toUomId", kg.get("id").asString()));
        assertThat(cross.status()).isEqualTo(400);
        assertThat(cross.body().get("message").asString()).isEqualTo("No conversion from " + l.get("code").asString()
                + " to " + kg.get("code").asString() + ": different categories — add a product-specific conversion.");
        Res unknown = as("POST", "/uoms/convert", Map.of("quantity", 1, "fromUomId", "nope", "toUomId", "nada"));
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unit of measure nope not found");
    }

    @Test
    @DisplayName("payment terms: lazy defaults, CRUD, archive + restore")
    void paymentTerms() {
        JsonNode list = ok(200, "GET", "/payment-terms", null);
        List<String> codes = new ArrayList<>();
        list.forEach(t -> codes.add(t.get("code").asString()));
        assertThat(codes).contains("immediate", "net-7", "net-30", "eom", "eom-10");

        String code = uniq("net");
        JsonNode created = ok(201, "POST", "/payment-terms", Map.of("code", code, "name", "Net 60", "netDays", 60));
        String id = created.get("id").asString();
        assertThat(created.get("method").asString()).isEqualTo("net_days");
        assertThat(created.get("sortOrder").asInt()).isZero();
        JsonNode patched = ok(200, "PATCH", "/payment-terms/" + id, Map.of("netDays", 61, "ignored", true));
        assertThat(patched.get("netDays").asInt()).isEqualTo(61);
        assertThat(ok(200, "DELETE", "/payment-terms/" + id, null).get("deletedAt").isNull()).isFalse();
        JsonNode deleted = ok(200, "GET", "/payment-terms/deleted", null);
        assertThat(deleted.findValuesAsString("id")).contains(id);
        assertThat(as("GET", "/payment-terms/" + id, null).body().get("message").asString()).isEqualTo("Payment term not found");
        assertThat(ok(200, "PATCH", "/payment-terms/" + id + "/restore", null).get("deletedAt").isNull()).isTrue();
        assertThat(as("PATCH", "/payment-terms/" + id + "/restore", null).body().get("message").asString())
                .isEqualTo("Archived payment term not found");
    }

    @Test
    @DisplayName("fiscal positions: unique code per org, soft delete + restore")
    void fiscalPositions() {
        String code = uniq("EXP");
        String id = ok(201, "POST", "/fiscal-positions", Map.of("code", code, "name", "Export")).get("id").asString();
        Res dupe = as("POST", "/fiscal-positions", Map.of("code", code, "name", "Again"));
        assertThat(dupe.status()).isEqualTo(400);
        assertThat(dupe.body().get("message").asString()).isEqualTo("Fiscal position code \"" + code + "\" already exists");
        assertThat(ok(200, "PATCH", "/fiscal-positions/" + id, Map.of("name", "Exports")).get("name").asString())
                .isEqualTo("Exports");
        ok(200, "DELETE", "/fiscal-positions/" + id, null);
        assertThat(as("GET", "/fiscal-positions/" + id, null).status()).isEqualTo(404);
        ok(200, "PATCH", "/fiscal-positions/" + id + "/restore", null);
        assertThat(as("PATCH", "/fiscal-positions/" + id + "/restore", null).body().get("message").asString())
                .isEqualTo("Archived fiscal position not found");
    }

    @Test
    @DisplayName("organizations: public bootstrap, login, me/settings/branch, invite + accept, users, deactivate")
    void organizations() {
        String code = uniq("org").toLowerCase();
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName", "Boot Co",
                "adminEmail", "Owner@Boot.test", "adminFirstName", "Owner", "adminPassword", "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        assertThat(boot.body().get("adminUser").get("email").asString()).isEqualTo("owner@boot.test");
        assertThat(boot.body().has("inviteToken")).isFalse();
        String orgId = boot.body().get("organization").get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ?").param(orgId)
                .query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryPostingRule\" WHERE \"organizationId\" = ?").param(orgId)
                .query(Long.class).single()).isEqualTo(30);

        Res dupe = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName", "x",
                "adminEmail", "a@b.test", "adminFirstName", "A"), (String) null);
        assertThat(dupe.status()).isEqualTo(409);
        assertThat(dupe.body().get("message").asString()).isEqualTo("Organization '" + code + "' already exists");
        Res badCode = call("POST", "/organizations/bootstrap", Map.of("organizationCode", "a b", "organizationName", "x",
                "adminEmail", "a@b.test", "adminFirstName", "A"), (String) null);
        assertThat(badCode.body().get("message").asString())
                .isEqualTo("organizationCode must be 2-32 chars, letters/digits/hyphens");

        String owner = login(code, "owner@boot.test", "Owner@12345");
        JsonNode me = call("GET", "/organizations/me", null, owner).body();
        assertThat(me.get("code").asString()).isEqualTo(code);
        assertThat(me.get("currencyCode").asString()).isEqualTo("USD");
        JsonNode settings = call("PATCH", "/organizations/me/settings", Map.of("timezone", "Africa/Kampala",
                "settings", Map.of("a", 1)), owner).body();
        assertThat(settings.get("timezone").asString()).isEqualTo("Africa/Kampala");
        assertThat(settings.get("settings").get("a").asInt()).isEqualTo(1);
        Map<String, Object> nullBranch = new LinkedHashMap<>();
        nullBranch.put("defaultBranchId", null);
        JsonNode branch = call("PATCH", "/organizations/me/branch", nullBranch, owner).body();
        assertThat(branch.get("ok").asBoolean()).isTrue();
        assertThat(branch.get("defaultBranchId").isNull()).isTrue();

        Res invite = call("POST", "/organizations/users/invite", Map.of("email", "Staff@Boot.test", "firstName", "Staff"),
                owner);
        assertThat(invite.status()).isEqualTo(201);
        String token = invite.body().get("inviteToken").asString();
        Res again = call("POST", "/organizations/users/invite", Map.of("email", "staff@boot.test", "firstName", "S"), owner);
        assertThat(again.status()).isEqualTo(409);
        assertThat(call("POST", "/organizations/accept-invite", Map.of("token", token, "newPassword", "Staff@12345"),
                (String) null).body().get("ok").asBoolean()).isTrue();
        assertThat(call("POST", "/organizations/accept-invite", Map.of("token", token, "newPassword", "Staff@12345"),
                (String) null).body().get("message").asString()).isEqualTo("Invalid or expired invite token");
        login(code, "staff@boot.test", "Staff@12345");

        JsonNode users = call("GET", "/organizations/users", null, owner).body();
        assertThat(users).hasSize(2);
        users.forEach(u -> assertThat(u.has("passwordHash")).isFalse());
        String staffId = invite.body().get("id").asString();
        assertThat(call("PATCH", "/organizations/users/" + staffId + "/deactivate", null, owner).body().get("ok")
                .asBoolean()).isTrue();
    }

    @Test
    @DisplayName("authorization matrix: every core route → 401 without a token, 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.core")).isGreaterThanOrEqualTo(81);
    }

    @Test
    @DisplayName("tenant isolation: another organization never sees or touches DEMO master data")
    void tenantIsolation() {
        String partner = ok(201, "POST", "/partners", Map.of("name", "Tenant Probe")).get("id").asString();
        String branch = ok(201, "POST", "/branches", Map.of("code", uniq("TB"), "name", "Probe")).get("id").asString();
        assertThat(call("GET", "/partners/" + partner, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/branches/" + branch, Map.of("name", "Hacked"), otherToken).status()).isEqualTo(404);
        assertThat(call("DELETE", "/branches/" + branch, null, otherToken).status()).isEqualTo(404);
        JsonNode list = call("GET", "/partners?pageSize=1000", null, otherToken).body();
        list.get("data").forEach(p -> assertThat(p.get("organizationId").asString()).isEqualTo(otherOrg));
        call("DELETE", "/partners/" + partner, null, otherToken);
        assertThat(ok(200, "GET", "/partners/" + partner, null).get("deletedAt").isNull()).isTrue();
    }
}
