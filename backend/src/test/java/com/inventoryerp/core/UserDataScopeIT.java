package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.web.UserDataScopeController;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * BR-1. The first test is the one that matters most: a user with no grant sees the same data as before, even with
 * {@code defaultBranchId} set, which is why {@code defaultBranchId} is not the restriction.
 */
class UserDataScopeIT extends ApiTestSupport {

    String branchA;
    String branchB;
    String yardA;
    String yardB;
    String role;
    String sfx = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    void fixtures() {
        branchA = ok(201, "POST", "/branches", Map.of("code", "SA-" + sfx, "name", "Scope A")).get("id").asString();
        branchB = ok(201, "POST", "/branches", Map.of("code", "SB-" + sfx, "name", "Scope B")).get("id").asString();
        yardA = ok(201, "POST", "/inventory/locations", Map.of("code", "YA-" + sfx, "name", "Yard A", "type", "warehouse",
                "branchId", branchA)).get("id").asString();
        yardB = ok(201, "POST", "/inventory/locations", Map.of("code", "YB-" + sfx, "name", "Yard B", "type", "warehouse",
                "branchId", branchB)).get("id").asString();
        role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Scope clerk " + sfx, new String[] {
                    "inventory_location:read", "inventory_location:update", "inventory:read", "inventory_doc:read",
                    "inventory_doc:create", "orders:read", "orders:create", "orders:update", "invoice:read",
                    "invoice:create"}).update();
    }

    record Clerk(String id, String token) {}

    Clerk clerk() {
        String email = "scope-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String id = createUser(demoOrg, email, "Scope@12345", role);
        return new Clerk(id, login("DEMO", email, "Scope@12345"));
    }

    Set<String> locationIds(String token) {
        JsonNode rows = call("GET", "/inventory/locations?pageSize=1000", null, token).body().get("data");
        Set<String> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.get("id").asString()));
        return ids;
    }

    @Test
    @DisplayName("no grant: same locations as the admin, even with defaultBranchId set")
    void unrestrictedUnchanged() {
        Clerk c = clerk();
        jdbc.sql("UPDATE \"User\" SET \"defaultBranchId\" = ? WHERE id = ?").params(branchA, c.id()).update();
        assertThat(locationIds(c.token())).isEqualTo(locationIds(adminToken));
        assertThat(call("GET", "/inventory/locations/" + yardB, null, c.token()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a branch grant limits locations through the branch link")
    void branchGrant() {
        Clerk c = clerk();
        ok(200, "PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of(branchA), "locationIds", List.of()));
        assertThat(locationIds(c.token())).containsExactly(yardA);
        assertThat(call("GET", "/inventory/locations/" + yardB, null, c.token()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a location grant reaches that location only; reports outside it are refused")
    void locationGrant() {
        Clerk c = clerk();
        ok(200, "PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of(), "locationIds", List.of(yardB)));
        assertThat(locationIds(c.token())).containsExactly(yardB);
        assertThat(call("GET", "/inventory/items?locationId=" + yardA, null, c.token()).status()).isEqualTo(403);
        assertThat(call("GET", "/inventory/reports/valuation?locationId=" + yardA, null, c.token()).status())
                .isEqualTo(403);
        assertThat(call("GET", "/inventory/reports/valuation", null, c.token()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("lifting the grant restores full access")
    void lift() {
        Clerk c = clerk();
        ok(200, "PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of(branchA)));
        ok(200, "PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of(), "locationIds", List.of()));
        assertThat(locationIds(c.token())).isEqualTo(locationIds(adminToken));
    }

    @Test
    @DisplayName("orders: a restricted user's order lands in their branch and others cannot be reached")
    void orders() {
        Clerk c = clerk();
        ok(200, "PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of(branchA)));
        String product = ok(201, "POST", "/products", Map.of("code", "SC-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Scope svc " + sfx, "productType", "service", "salesPrice", 10, "costPrice", 0)).get("id")
                .asString();
        Map<String, Object> body = Map.of("lines", List.of(Map.of("productId", product, "quantity", 1)));
        Res mine = call("POST", "/orders", body, c.token());
        assertThat(mine.status()).isEqualTo(200);
        String id = mine.body().get("id").asString();
        assertThat(jdbc.sql("SELECT \"branchId\" FROM \"Order\" WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(branchA);
        String other = ok(200, "POST", "/orders", Map.of("branchId", branchB, "lines", List.of(Map.of("productId",
                product, "quantity", 1)))).get("id").asString();
        assertThat(call("GET", "/orders/" + other, null, c.token()).status()).isEqualTo(404);
        assertThat(call("POST", "/orders", Map.of("branchId", branchB, "lines", List.of(Map.of("productId", product,
                "quantity", 1))), c.token()).status()).isEqualTo(403);
        ok(200, "POST", "/orders/" + id + "/cancel", Map.of("reason", "test"));
        ok(200, "POST", "/orders/" + other + "/cancel", Map.of("reason", "test"));
    }

    @Test
    @DisplayName("nobody changes their own scope, and a restricted user changes nobody's")
    void noSelfWidening() {
        Clerk c = clerk();
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        assertThat(as("PUT", "/user-data-scopes/" + admin, Map.of("branchIds", List.of(branchA))).status())
                .isEqualTo(403);
        assertThat(ok(200, "GET", "/user-data-scopes/" + c.id(), null).get("restricted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("another organization's user, branch and location cannot be used")
    void tenantIsolation() {
        Clerk c = clerk();
        assertThat(call("GET", "/user-data-scopes/" + c.id(), null, otherToken).status()).isEqualTo(404);
        assertThat(as("PUT", "/user-data-scopes/" + c.id(), Map.of("branchIds", List.of("nope"))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("authorization matrix")
    void authorization() {
        assertThat(assertAuthorizationMatrix(UserDataScopeController.class)).isEqualTo(2);
    }
}
