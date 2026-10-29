package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** POS settings, kitchen stations, menu, variants, accompaniments, modifiers, combos, PIN auth and overrides. */
class PosMenuIT extends ApiTestSupport {

    private String productId() {
        return jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"isActive\" = true ORDER BY code LIMIT 1").param(demoOrg).query(String.class).single();
    }

    @Test
    void settingsAndLookup() {
        JsonNode s = ok(200, "GET", "/pos/settings", null);
        assertThat(s.get("connectionMode").asString()).isIn("offline", "online");
        assertThat(s.get("discountApproval").get("tier1").asInt()).isEqualTo(10);
        JsonNode u = ok(200, "PATCH", "/pos/settings", Map.of("posMode", "retail", "sharedDrawer", true));
        assertThat(u.get("posMode").asString()).isEqualTo("retail");
        assertThat(ok(200, "GET", "/pos/settings", null).get("sharedDrawer").asBoolean()).isTrue();
        assertThat(ok(200, "PATCH", "/pos/settings", Map.of("posMode", "rental")).get("posMode").asString())
                .isEqualTo("rental");
        ok(200, "PATCH", "/pos/settings", Map.of("posMode", "cafe", "sharedDrawer", false));
        Res bad = as("PATCH", "/pos/settings", Map.of("posMode", "bogus"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.raw().body()).contains("posMode must be one of the following values: cafe, retail, rental");
        String code = jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId()).query(String.class).single();
        JsonNode found = ok(200, "GET", "/pos/lookup?sku=" + code.toLowerCase(), null);
        assertThat(found.toString()).contains("\"kind\":\"product\"").contains("stockItems");
        ok(200, "GET", "/pos/payment-accounts", null);
        ok(200, "GET", "/pos/payment-methods", null);
    }

    @Test
    void kitchenStations() {
        JsonNode list = ok(200, "GET", "/pos/kitchen-stations", null);
        assertThat(list.size()).isGreaterThanOrEqualTo(3);
        String code = "grill" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode st = ok(201, "POST", "/pos/kitchen-stations", Map.of("name", " Grill ", "code", code.toUpperCase()));
        assertThat(st.get("code").asString()).isEqualTo(code);
        assertThat(st.get("name").asString()).isEqualTo("Grill");
        Res dup = as("POST", "/pos/kitchen-stations", Map.of("name", "G", "code", code));
        assertThat(dup.body().get("message").asString()).isEqualTo("A station with code \"" + code + "\" already exists.");
        Res badCode = as("POST", "/pos/kitchen-stations", Map.of("name", "G", "code", "a b"));
        assertThat(badCode.body().get("message").asString())
                .isEqualTo("Station code must be lowercase letters/numbers/-/_ (max 32 chars).");
        String id = st.get("id").asString();
        JsonNode upd = ok(200, "PATCH", "/pos/kitchen-stations/" + id, Map.of("color", "#000", "isDefault", true));
        assertThat(upd.get("isDefault").asBoolean()).isTrue();
        Res del = as("DELETE", "/pos/kitchen-stations/" + id, null);
        assertThat(del.status()).isEqualTo(400);
        String cafe = jdbc.sql("SELECT id FROM \"KitchenStation\" WHERE \"organizationId\" = ? AND code = 'cafe'")
                .param(demoOrg).query(String.class).single();
        ok(200, "PATCH", "/pos/kitchen-stations/" + cafe, Map.of("isDefault", true));
        ok(200, "DELETE", "/pos/kitchen-stations/" + id, null);
        // revive by code
        JsonNode revived = ok(201, "POST", "/pos/kitchen-stations", Map.of("name", "Grill 2", "code", code));
        assertThat(revived.get("id").asString()).isEqualTo(id);
        ok(200, "PATCH", "/pos/kitchen-stations/reorder", Map.of("ids", List.of(id, cafe)));
    }

    @Test
    void menuLifecycle() {
        JsonNode cat = ok(201, "POST", "/pos/menu/categories", Map.of("name", "Drinks " + UUID.randomUUID()));
        String catId = cat.get("id").asString();
        assertThat(cat.get("displayOrder").asInt()).isZero();
        ok(200, "PATCH", "/pos/menu/categories/" + catId, Map.of("icon", "cup"));
        String pid = productId();
        JsonNode item = ok(201, "POST", "/pos/menu/items", Map.of("name", "Latte", "basePrice", 5000,
                "categoryId", catId, "ingredients", List.of(Map.of("productId", pid, "quantity", 0.25))));
        String itemId = item.get("id").asString();
        assertThat(item.get("basePrice").asString()).isEqualTo("5000");
        assertThat(item.get("ingredients").get(0).get("quantity").asString()).isEqualTo("0.25");
        assertThat(item.get("category").get("id").asString()).isEqualTo(catId);
        Res noRecipe = as("POST", "/pos/menu/items", Map.of("name", "X"));
        assertThat(noRecipe.body().get("message").asString())
                .isEqualTo("Inventory-tracked menu items require at least one recipe ingredient");
        Res badStation = as("POST", "/pos/menu/items", Map.of("name", "Y", "isInventoryTracked", false,
                "stationCode", "nope"));
        assertThat(badStation.body().get("message").asString()).isEqualTo("Unknown kitchen station \"nope\"");
        JsonNode got = ok(200, "GET", "/pos/menu/items/" + itemId, null);
        assertThat(got.get("ingredients").get(0).get("product").get("id").asString()).isEqualTo(pid);
        JsonNode page = ok(200, "GET", "/pos/menu/items?categoryId=" + catId, null);
        assertThat(page.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(page.get("meta").get("pageSize").asInt()).isEqualTo(25);
        assertThat(as("GET", "/pos/menu/items?categoryId=x", null).raw().body()).contains("categoryId must be a UUID");
        JsonNode upd = ok(200, "PATCH", "/pos/menu/items/" + itemId, Map.of("basePrice", 5500,
                "expectedUpdatedAt", got.get("updatedAt").asString()));
        assertThat(upd.get("basePrice").asString()).isEqualTo("5500");
        Res stale = as("PATCH", "/pos/menu/items/" + itemId, Map.of("name", "L2",
                "expectedUpdatedAt", got.get("updatedAt").asString()));
        assertThat(stale.status()).isEqualTo(409);
        ok(200, "PATCH", "/pos/menu/items/" + itemId + "/availability", Map.of("isAvailable", false));
        ok(200, "PATCH", "/pos/menu/items/" + itemId + "/availability", Map.of("isAvailable", true));
        assertThat(as("GET", "/pos/menu/items/deleted", null).status()).isEqualTo(404);

        // variants
        JsonNode v = ok(201, "POST", "/pos/menu/items/" + itemId + "/variants", Map.of("name", "Large", "price", 6500));
        assertThat(v.get("price").asInt()).isEqualTo(6500);
        Res dupV = as("POST", "/pos/menu/items/" + itemId + "/variants", Map.of("name", "Large", "price", 1));
        assertThat(dupV.body().get("message").asString()).isEqualTo("Variant \"Large\" already exists for this item");
        ok(200, "PATCH", "/pos/menu/items/" + itemId + "/variants/" + v.get("id").asString(), Map.of("price", 7000));
        assertThat(ok(200, "GET", "/pos/menu/items/" + itemId + "/variants", null).get(0).get("price").asInt())
                .isEqualTo(7000);

        // accompaniments
        JsonNode g = ok(201, "POST", "/pos/menu/accompaniments/groups", Map.of("name", "Milk " + UUID.randomUUID()));
        String gid = g.get("id").asString();
        ok(201, "POST", "/pos/menu/accompaniments/groups/" + gid + "/options", Map.of("name", "Oat", "priceImpact", 500));
        ok(201, "POST", "/pos/menu/items/" + itemId + "/accompaniments", Map.of("accompanimentGroupId", gid));
        assertThat(ok(200, "GET", "/pos/menu/items/" + itemId + "/accompaniments", null).get(0).get("options").size())
                .isEqualTo(1);
        Res inUse = as("DELETE", "/pos/menu/accompaniments/groups/" + gid, null);
        assertThat(inUse.status()).isEqualTo(409);

        // modifiers
        JsonNode mg = ok(201, "POST", "/pos/modifiers/groups", Map.of("name", "Extras " + UUID.randomUUID(),
                "maxSelect", 3));
        String mgid = mg.get("id").asString();
        JsonNode mod = ok(201, "POST", "/pos/modifiers/modifiers", Map.of("groupId", mgid, "name", "Shot",
                "priceDelta", 1000));
        ok(201, "POST", "/pos/modifiers/menu-items/" + itemId + "/groups", Map.of("modifierGroupId", mgid));
        JsonNode bundle = ok(200, "GET", "/pos/menu/items/" + itemId + "/bundle", null);
        assertThat(bundle.get("variants").size()).isEqualTo(1);
        assertThat(bundle.get("accompanimentGroups").size()).isEqualTo(1);
        assertThat(bundle.get("groups").get(0).get("modifiers").get(0).get("priceDelta").asInt()).isEqualTo(1000);
        JsonNode upg = ok(200, "PATCH", "/pos/modifiers/groups/" + mgid, Map.of("expectedVersion", 0, "minSelect", 1));
        assertThat(upg.get("version").asInt()).isEqualTo(1);
        assertThat(as("PATCH", "/pos/modifiers/groups/" + mgid, Map.of("expectedVersion", 0)).status()).isEqualTo(409);
        ok(200, "PATCH", "/pos/modifiers/modifiers/" + mod.get("id").asString(), Map.of("priceDelta", 1200));
        JsonNode items = ok(200, "GET", "/pos/modifiers/groups/" + mgid + "/menu-items", null);
        assertThat(items.findValues("isAssigned").stream().anyMatch(JsonNode::asBoolean)).isTrue();
        ok(200, "GET", "/pos/modifiers/groups?search=extras", null);

        // combos
        JsonNode combo = ok(201, "POST", "/pos/modifiers/combos", Map.of("name", "Combo " + UUID.randomUUID(),
                "price", 9000, "items", List.of(Map.of("productId", pid, "quantity", 2))));
        JsonNode cv = ok(200, "GET", "/pos/modifiers/combos/" + combo.get("id").asString(), null);
        assertThat(cv.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        ok(200, "DELETE", "/pos/modifiers/combos/" + combo.get("id").asString(), null);

        // soft delete / restore parity (Nest P2025 → 500)
        ok(200, "DELETE", "/pos/menu/items/" + itemId + "/hard", null);
        assertThat(as("PATCH", "/pos/menu/items/" + itemId + "/restore", null).status()).isEqualTo(500);
    }

    @Test
    void pinLoginAndOverrides() {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", "4321"));
        JsonNode staff = ok(200, "GET", "/pos/auth/staff", null);
        assertThat(staff.toString()).contains(adminId);
        Res bad = as("POST", "/pos/auth/pin-login", Map.of("userId", adminId, "pin", "0000"));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().get("message").asString()).isEqualTo("Invalid PIN");
        JsonNode login = ok(200, "POST", "/pos/auth/pin-login", Map.of("userId", adminId, "pin", "4321"));
        assertThat(login.get("posToken").asString()).isNotBlank();
        JsonNode v = ok(201, "POST", "/pos/override/verify", Map.of("pin", "4321", "overrideKind", "discount"));
        assertThat(v.get("managerId").asString()).isEqualTo(adminId);
        ok(201, "POST", "/pos/override/verify-pin", Map.of("pin", "4321"));
        JsonNode grant = ok(201, "POST", "/pos/operation-approval", Map.of("managerId", adminId, "pin", "4321",
                "operationKey", "k-" + UUID.randomUUID(), "endpoint", "/pos/checkout", "payload", Map.of("a", 1)));
        assertThat(grant.get("approvalToken").asString()).hasSize(64);
        Res noKey = as("POST", "/pos/operation-approval", Map.of("managerId", adminId, "pin", "4321",
                "operationKey", "k", "endpoint", "/x", "payload", Map.of()));
        assertThat(noKey.body().get("message").asString()).isEqualTo("A POS operation and key are required");
        assertThat(as("POST", "/pos/auth/change-pin", Map.of("currentPin", "9999", "newPin", "1234"))
                .body().get("message").asString()).isEqualTo("Current PIN is incorrect");
        ok(200, "POST", "/pos/auth/logoff", null);
    }
}
