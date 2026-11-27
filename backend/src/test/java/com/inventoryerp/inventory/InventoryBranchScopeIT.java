package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * R2 (gate G3): branch scope on stock actions and location master-data guards. A user restricted to location A holds
 * every permission, so only the data scope decides: reversals and transfer source actions need the source in scope,
 * a receipt needs the destination, reservations are confined to the user's locations, and a location that still holds
 * stock or open work cannot be deleted, deactivated, retyped or moved to another branch.
 */
class InventoryBranchScopeIT extends ApiTestSupport {

    private static final String OUTSIDE = "This location is outside your data access";

    String adminId;
    String category;
    String uom;
    String locA;
    String locB;
    String tokenA;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        locA = location("warehouse");
        locB = location("warehouse");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Branch A keeper " + suffix(),
                        Permissions.ALL.toArray(String[]::new)).update();
        String email = "keeper-a-" + suffix() + "@demo.test";
        String userId = createUser(demoOrg, email, "Keeper@12345", role);
        ok(200, "PUT", "/user-data-scopes/" + userId, Map.of("branchIds", List.of(), "locationIds", List.of(locA)));
        tokenA = login("DEMO", email, "Keeper@12345");
    }

    // ------------------------------------------------------------------ helpers

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "BS-" + suffix(), "name", "Loc " + suffix(),
                "type", type)).get("id").asString();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"categoryId\", \"uomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                        + " 'AVCO'::\"CostingMethod\", 4, ?, ?, now())")
                .params(id, demoOrg, "BS-" + suffix(), "Scoped item " + suffix(), category, uom).update();
        return id;
    }

    void stockIn(String loc, String productId, int qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("quantity", qty);
        line.put("unitCost", 2);
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line)));
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    Res asA(String method, String path, Object body) {
        return call(method, path, body, tokenA);
    }

    static void refused(Res r) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(403);
        assertThat(r.body().get("message").asString()).isEqualTo(OUTSIDE);
    }

    JsonNode transfer(String from, String to, String mode, String productId, int qty) {
        return ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", from, "toLocationId", to, "mode", mode,
                "responsibleById", adminId, "approvedById", adminId,
                "items", List.of(Map.of("productId", productId, "qtyRequested", qty))));
    }

    String status(String table, String id) {
        return jdbc.sql("SELECT status::text FROM \"" + table + "\" WHERE id = ?").param(id).query(String.class)
                .single();
    }

    // ------------------------------------------------------------------ reversal

    @Test
    @DisplayName("R2 reversal: a branch-A user cannot reverse a branch-B stock-out or a transfer out of B")
    void reversalNeedsTheSourceInScope() {
        String p = product();
        stockIn(locB, p, 10);
        stockIn(locA, p, 10);
        JsonNode outB = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", locB, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", p, "qty", 3))));
        String outBId = outB.get("id").asString();
        ok(200, "POST", "/inventory/stock-outs/" + outBId + "/approve", null);
        refused(asA("POST", "/inventory/stock-outs/" + outBId + "/reverse", Map.of("reason", "not mine")));
        assertThat(status("StockOut", outBId)).isEqualTo("completed");
        assertThat(onHand(p, locB)).isEqualByComparingTo("7");

        // the same user reverses a stock-out of their own location
        JsonNode outA = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", locA, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", p, "qty", 2))));
        ok(200, "POST", "/inventory/stock-outs/" + outA.get("id").asString() + "/approve", null);
        Res mine = asA("POST", "/inventory/stock-outs/" + outA.get("id").asString() + "/reverse",
                Map.of("reason", "counted wrong"));
        assertThat(mine.status()).as(mine.raw().body()).isEqualTo(200);
        assertThat(onHand(p, locA)).isEqualByComparingTo("10");

        // an immediate transfer B → A: A is only the destination, so it cannot reverse it
        JsonNode trf = transfer(locB, locA, "immediate", p, 4);
        String trfId = trf.get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + trfId + "/approve", null);
        refused(asA("POST", "/inventory/transfers/" + trfId + "/reverse", Map.of("reason", "send it back")));
        assertThat(status("StockTransfer", trfId)).isEqualTo("completed");
        assertThat(onHand(p, locA)).isEqualByComparingTo("14");
    }

    // ------------------------------------------------------------------ transfers

    @Test
    @DisplayName("R2 transfers: approve/dispatch/recall/cancel need the source; receive needs the destination")
    void transferActionsFollowTheirSide() {
        String p = product();
        stockIn(locB, p, 20);
        JsonNode trf = transfer(locB, locA, "transit", p, 6);
        String id = trf.get("id").asString();
        // reads stay reachable from either end
        assertThat(asA("GET", "/inventory/transfers/" + id, null).status()).isEqualTo(200);
        refused(asA("POST", "/inventory/transfers/" + id + "/approve", null));
        assertThat(status("StockTransfer", id)).isEqualTo("pending");
        ok(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        refused(asA("POST", "/inventory/transfers/" + id + "/dispatch", null));
        assertThat(status("StockTransfer", id)).isEqualTo("approved");
        JsonNode dispatched = ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        refused(asA("POST", "/inventory/transfers/" + id + "/recall", Map.of("reason", "wrong truck")));
        String itemId = dispatched.get("items").get(0).get("id").asString();
        Res received = asA("POST", "/inventory/transfers/" + id + "/receive",
                Map.of("lines", List.of(Map.of("itemId", itemId, "received", 6))));
        assertThat(received.status()).as(received.raw().body()).isEqualTo(200);
        assertThat(received.body().get("status").asString()).isEqualTo("completed");
        assertThat(onHand(p, locA)).isEqualByComparingTo("6");

        // a pending transfer B → A cannot be cancelled from A
        String pending = transfer(locB, locA, "transit", p, 1).get("id").asString();
        refused(asA("POST", "/inventory/transfers/" + pending + "/cancel", null));
        assertThat(status("StockTransfer", pending)).isEqualTo("pending");
        ok(200, "POST", "/inventory/transfers/" + pending + "/cancel", null);

        // A → B in transit: A dispatches, but cannot receive at B
        String outbound = transfer(locA, locB, "transit", p, 2).get("id").asString();
        assertThat(asA("POST", "/inventory/transfers/" + outbound + "/approve", null).status()).isEqualTo(200);
        Res sent = asA("POST", "/inventory/transfers/" + outbound + "/dispatch", null);
        assertThat(sent.status()).as(sent.raw().body()).isEqualTo(200);
        String outItem = sent.body().get("items").get(0).get("id").asString();
        refused(asA("POST", "/inventory/transfers/" + outbound + "/receive",
                Map.of("lines", List.of(Map.of("itemId", outItem, "received", 2)))));
        assertThat(status("StockTransfer", outbound)).isEqualTo("in_transit");
        ok(200, "POST", "/inventory/transfers/" + outbound + "/receive",
                Map.of("lines", List.of(Map.of("itemId", outItem, "received", 2))));
    }

    // ------------------------------------------------------------------ reservations

    @Test
    @DisplayName("R2 reservations: reserve checks scope and existence; release/list/overview stay in scope")
    void reservationsStayInScope() {
        String p = product();
        String source = "SO-" + suffix();
        refused(asA("POST", "/inventory/reservations", Map.of("productId", p, "locationId", locB, "quantity", 1,
                "sourceType", "manual", "sourceId", source)));
        Res noProduct = asA("POST", "/inventory/reservations", Map.of("productId", UUID.randomUUID().toString(),
                "locationId", locA, "quantity", 1, "sourceType", "manual", "sourceId", source));
        assertThat(noProduct.status()).isEqualTo(404);
        assertThat(noProduct.body().get("message").asString()).isEqualTo("Product not found");
        Res noLocation = asA("POST", "/inventory/reservations", Map.of("productId", p, "locationId",
                UUID.randomUUID().toString(), "quantity", 1, "sourceType", "manual", "sourceId", source));
        assertThat(noLocation.status()).isEqualTo(404);
        assertThat(noLocation.body().get("message").asString()).isEqualTo("Location not found");

        ok(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", locB, "quantity", 5,
                "sourceType", "manual", "sourceId", source));
        Res mine = asA("POST", "/inventory/reservations", Map.of("productId", p, "locationId", locA, "quantity", 2,
                "sourceType", "manual", "sourceId", source));
        assertThat(mine.status()).as(mine.raw().body()).isEqualTo(201);

        JsonNode seen = asA("GET", "/inventory/reservations?sourceType=manual&sourceId=" + source, null).body();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("locationId").asString()).isEqualTo(locA);
        assertThat(ok(200, "GET", "/inventory/reservations?sourceType=manual&sourceId=" + source, null)).hasSize(2);

        JsonNode overview = asA("GET", "/inventory/reservations/overview?productId=" + p, null).body();
        assertThat(overview.get("data")).hasSize(1);
        overview.get("data").forEach(r -> assertThat(r.get("locationId").asString()).isEqualTo(locA));
        refused(asA("GET", "/inventory/reservations/overview?locationId=" + locB, null));

        Res released = asA("POST", "/inventory/reservations/release", Map.of("sourceType", "manual", "sourceId", source));
        assertThat(released.body().get("released").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status::text FROM \"StockReservation\" WHERE \"sourceId\" = ? AND \"locationId\" = ?")
                .params(source, locB).query(String.class).single()).isEqualTo("active");
        Res consumed = asA("POST", "/inventory/reservations/consume", Map.of("sourceType", "manual", "sourceId", source));
        assertThat(consumed.body().get("consumed").asInt()).isZero();
        assertThat(ok(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "manual", "sourceId", source))
                .get("released").asInt()).isEqualTo(1);

        // an unkeyed list is paged: never more than 200 rows, and pageSize narrows it
        assertThat(ok(200, "GET", "/inventory/reservations", null).size()).isLessThanOrEqualTo(200);
        assertThat(ok(200, "GET", "/inventory/reservations?pageSize=1", null)).hasSize(1);
    }

    // ------------------------------------------------------------------ locations

    @Test
    @DisplayName("R2 locations: stock or open work blocks delete, deactivation, retype and a branch move")
    void locationWithStockIsGuarded() {
        String loc = location("warehouse");
        String p = product();
        stockIn(loc, p, 3);
        Res delete = as("DELETE", "/inventory/locations/" + loc, null);
        assertThat(delete.status()).isEqualTo(400);
        assertThat(delete.body().get("message").asString()).contains("cannot be deleted while it still has stock on hand (1)");
        Res deactivate = as("PATCH", "/inventory/locations/" + loc, Map.of("isActive", false));
        assertThat(deactivate.status()).isEqualTo(400);
        assertThat(deactivate.body().get("message").asString()).contains("cannot be deactivated while");
        Res retype = as("PATCH", "/inventory/locations/" + loc, Map.of("type", "bar"));
        assertThat(retype.status()).isEqualTo(400);
        assertThat(retype.body().get("message").asString()).contains("cannot be retyped while");
        String branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        if (branch != null) {
            Res move = as("PATCH", "/inventory/locations/" + loc, Map.of("branchId", branch));
            assertThat(move.status()).isEqualTo(400);
            assertThat(move.body().get("message").asString()).contains("cannot be moved to another branch while");
        }
        // a rename is still fine
        assertThat(ok(200, "PATCH", "/inventory/locations/" + loc, Map.of("name", "Renamed")).get("name").asString())
                .isEqualTo("Renamed");
        assertThat(ok(200, "GET", "/inventory/locations/" + loc, null).get("isActive").asBoolean()).isTrue();

        // once emptied (a zero quant row stays behind) it can be deleted
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", p, "quantity", 3))));
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        assertThat(as("DELETE", "/inventory/locations/" + loc, null).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("R2 locations: an open transfer blocks deletion; an empty location deletes")
    void openWorkBlocksDelete() {
        String from = location("warehouse");
        String to = location("warehouse");
        String p = product();
        String trf = transfer(from, to, "immediate", p, 1).get("id").asString();
        Res delete = as("DELETE", "/inventory/locations/" + to, null);
        assertThat(delete.status()).isEqualTo(400);
        assertThat(delete.body().get("message").asString()).contains("open transfers (1)");
        ok(200, "POST", "/inventory/transfers/" + trf + "/cancel", null);
        assertThat(as("DELETE", "/inventory/locations/" + to, null).status()).isEqualTo(204);
        assertThat(as("DELETE", "/inventory/locations/" + location("bar"), null).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("R2 locations: the transit location is never deleted, deactivated or retyped")
    void transitIsSystemManaged() {
        String transit = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type ="
                + " 'transit' AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(null);
        if (transit == null) {
            String p = product();
            stockIn(locB, p, 1);
            String id = transfer(locB, locA, "transit", p, 1).get("id").asString();
            ok(200, "POST", "/inventory/transfers/" + id + "/approve", null);
            transit = ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null).get("transitLocId").asString();
        }
        Res delete = as("DELETE", "/inventory/locations/" + transit, null);
        assertThat(delete.status()).isEqualTo(400);
        assertThat(delete.body().get("message").asString())
                .isEqualTo("The transit location is system-managed; it cannot be deleted");
        Res retype = as("PATCH", "/inventory/locations/" + transit, Map.of("type", "warehouse"));
        assertThat(retype.status()).isEqualTo(400);
        assertThat(retype.body().get("message").asString())
                .isEqualTo("The transit location is system-managed; it cannot be retyped");
        Res deactivate = as("PATCH", "/inventory/locations/" + transit, Map.of("isActive", false));
        assertThat(deactivate.body().get("message").asString())
                .isEqualTo("The transit location is system-managed; it cannot be deactivated");
        assertThat(jdbc.sql("SELECT type::text FROM \"InventoryLocation\" WHERE id = ?").param(transit)
                .query(String.class).single()).isEqualTo("transit");
    }

    // ------------------------------------------------------------------ replenishment

    @Test
    @DisplayName("R2 replenishment: a transfer rule's source location must be in the caller's scope")
    void replenishmentSourceInScope() {
        String p = product();
        refused(asA("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", locA, "minQty", 5,
                "route", "transfer", "sourceLocationId", locB)));
        // set up by an unrestricted user, the rule's levels stay editable from A while its source is kept
        String rule = ok(201, "POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", locA,
                "minQty", 5, "route", "transfer", "sourceLocationId", locB)).get("id").asString();
        Res levels = asA("PATCH", "/inventory/replenishment/rules/" + rule, Map.of("minQty", 7));
        assertThat(levels.status()).as(levels.raw().body()).isEqualTo(200);
        String other = location("warehouse");
        refused(asA("PATCH", "/inventory/replenishment/rules/" + rule, Map.of("sourceLocationId", other)));
        // an active rule ties its source location down
        Res delete = as("DELETE", "/inventory/locations/" + locB, null);
        assertThat(delete.status()).isEqualTo(400);
        assertThat(delete.body().get("message").asString()).contains("active replenishment rules");
        ok(200, "DELETE", "/inventory/replenishment/rules/" + rule, null);
    }
}
