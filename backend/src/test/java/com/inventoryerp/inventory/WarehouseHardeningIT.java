package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R5a (inventory review 2026-11-22), warehouse: a move cannot take stock that open picks hold, switching
 * warehouse management off still lets open work finish, reconcile cancels the moves whose stock it takes (never a
 * pick's), the ABC list is read-only and transfers are not consumption, and ship / approve / dispatch need an
 * Idempotency-Key.
 */
class WarehouseHardeningIT extends InventoryAuditSupport {

    /** A second administrator: a manual pick order is shipped by someone other than its creator. */
    String shipperToken;

    @BeforeAll
    void shipper() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, permissions, \"updatedAt\") VALUES (?, ?,"
                + " 'Shipper', ?, now())").params(role, org, Permissions.ALL.toArray(String[]::new)).update();
        String email = "shipper-" + sfx() + "@audit.test";
        createUser(org, email, "Shipper@12345", role);
        shipperToken = login(orgCode, email, "Shipper@12345");
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String warehouse() {
        return location("W-" + sfx(), "Warehouse " + sfx(), "warehouse");
    }

    String bin(String loc, String code) {
        return okA(201, "POST", "/warehouse/bins", Map.of("locationId", loc, "code", code)).get("id").asString();
    }

    void binned(String product, String loc, String bin, int qty) {
        okA(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(item(product, qty, 5))));
        JsonNode t = okA(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId",
                product, "quantity", qty, "toBinId", bin));
        okA(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of());
    }

    /** A released pick order of {@code qty}: its id and its (single) open pick task id. */
    String[] releasedPick(String loc, String product, int qty) {
        String id = okA(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", product, "quantity", qty)))).get("id").asString();
        JsonNode rel = okA(200, "POST", "/warehouse/pick-orders/" + id + "/release", null);
        return new String[] {id, rel.get("tasks").get(0).get("id").asString()};
    }

    Map<String, Object> move(String loc, String product, int qty, String from, String to) {
        return Map.of("locationId", loc, "type", "move", "productId", product, "quantity", qty, "fromBinId", from,
                "toBinId", to);
    }

    BigDecimal binQty(String binId, String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?"
                + " AND \"productId\" = ?").params(binId, productId).query(BigDecimal.class).single();
    }

    String taskStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"WarehouseTask\" WHERE id = ?").param(id).query(String.class)
                .single();
    }

    @Test
    @DisplayName("a move takes only what open picks leave in the bin, at creation and at completion")
    void moveRespectsPickHolds() {
        String loc = warehouse();
        String a = bin(loc, "A");
        String b = bin(loc, "B");
        String p = product("MOVE");
        binned(p, loc, a, 10);
        releasedPick(loc, p, 6);

        Res tooMuch = api("POST", "/warehouse/tasks", move(loc, p, 5, a, b));
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(tooMuch.body().get("message").asString()).startsWith("Not enough stock in bin A");
        String mv = okA(201, "POST", "/warehouse/tasks", move(loc, p, 4, a, b)).get("id").asString();

        // the bin drifts below what the pick and the move hold together: completing the move is refused
        jdbc.sql("UPDATE \"WarehouseBinStock\" SET quantity = 8 WHERE \"binId\" = ? AND \"productId\" = ?").params(a, p)
                .update();
        Res drifted = api("POST", "/warehouse/tasks/" + mv + "/complete", Map.of());
        assertThat(drifted.status()).isEqualTo(400);
        assertThat(drifted.body().get("message").asString()).contains("open picks hold part of it");
        assertThat(okA(200, "POST", "/warehouse/tasks/" + mv + "/complete", Map.of("quantity", 2)).get("status")
                .asString()).isEqualTo("done");
        assertThat(binQty(b, p)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("warehouse management switched off: open tasks and orders can still be finished; new work is refused")
    void disabledStillFinishesOpenWork() {
        String loc = warehouse();
        String a = bin(loc, "A");
        String b = bin(loc, "B");
        String p = product("OFF");
        binned(p, loc, a, 20);
        String mv = okA(201, "POST", "/warehouse/tasks", move(loc, p, 2, a, b)).get("id").asString();
        String[] open = releasedPick(loc, p, 2);
        String[] picked = releasedPick(loc, p, 3);
        okA(200, "POST", "/warehouse/tasks/" + picked[1] + "/complete", Map.of());

        okA(200, "PUT", "/warehouse/locations/" + loc + "/settings", Map.of("enabled", false));
        Res newTask = api("POST", "/warehouse/tasks", move(loc, p, 1, a, b));
        assertThat(newTask.status()).isEqualTo(400);
        assertThat(newTask.body().get("message").asString()).startsWith("Warehouse management is switched off");
        assertThat(api("POST", "/warehouse/pick-orders", Map.of("locationId", loc, "lines", List.of(Map.of(
                "productId", p, "quantity", 1)))).status()).isEqualTo(400);

        assertThat(okA(200, "POST", "/warehouse/tasks/" + mv + "/complete", Map.of()).get("status").asString())
                .isEqualTo("done");
        assertThat(okA(200, "POST", "/warehouse/tasks/" + open[1] + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(okA(200, "POST", "/warehouse/pick-orders/" + open[0] + "/cancel", Map.of()).get("status")
                .asString()).isEqualTo("cancelled");
        Res shipped = call("POST", "/warehouse/pick-orders/" + picked[0] + "/ship", null, shipperToken);
        assertThat(shipped.status()).as(shipped.raw().body()).isEqualTo(200);
        assertThat(shipped.body().get("status").asString()).isEqualTo("shipped");
    }

    @Test
    @DisplayName("reconcile: excess taken from move-held stock cancels those moves; pick-held stock is never taken")
    void reconcileCancelsStaleMoves() {
        String loc = warehouse();
        String a = bin(loc, "A");
        String b = bin(loc, "B");
        String moved = product("RMOVE");
        binned(moved, loc, a, 10);
        String mv = okA(201, "POST", "/warehouse/tasks", move(loc, moved, 8, a, b)).get("id").asString();
        okA(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(item(moved, 5, null))));

        String picked = product("RPICK");
        binned(picked, loc, a, 10);
        String pickTask = releasedPick(loc, picked, 8)[1];
        okA(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(item(picked, 5, null))));

        JsonNode rec = okA(200, "POST", "/warehouse/locations/" + loc + "/reconcile", Map.of());
        assertThat(binQty(a, moved)).isEqualByComparingTo("5");
        assertThat(rec.get("cancelledTasks").toString()).contains(mv);
        assertThat(taskStatus(mv)).isEqualTo("cancelled");
        // the pick keeps its 8: 2 free units were taken, the other 3 of the excess stay and are reported
        assertThat(binQty(a, picked)).isEqualByComparingTo("8");
        assertThat(taskStatus(pickTask)).isEqualTo("open");
        JsonNode held = rec.get("heldByPicks");
        assertThat(held).hasSize(1);
        assertThat(held.get(0).get("productId").asString()).isEqualTo(picked);
        assertThat(num(held.get(0).get("quantity"))).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("ABC: the list reads the latest submitted count without writing; transfers out are not consumption")
    void abcListReadOnlyAndTransfersNotConsumption() {
        String loc = warehouse();
        String sold = product("ABC-SOLD");
        String moved = product("ABC-MOVED");
        okA(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(item(sold, 100, 5), item(moved, 100, 5))));
        okA(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(item(sold, 40, null))));
        asOrg(() -> stock.transfer(StockMove.transfer(moved, loc, alt, new BigDecimal("60"))
                .source("r5a_transfer", "T-" + sfx())));

        JsonNode classified = okA(200, "POST", "/warehouse/locations/" + loc + "/abc/classify", null);
        Map<String, JsonNode> rows = new HashMap<>();
        classified.get("rows").forEach(r -> rows.put(r.get("productId").asString(), r));
        assertThat(num(rows.get(sold).get("consumptionValue"))).isEqualByComparingTo("200");
        assertThat(num(rows.get(moved).get("consumptionValue"))).isEqualByComparingTo("0");

        String session = okA(201, "POST", "/warehouse/locations/" + loc + "/cycle-counts", Map.of()).get("session")
                .get("id").asString();
        jdbc.sql("UPDATE \"InventoryCountLine\" SET \"countedQty\" = 1 WHERE \"sessionId\" = ?").param(session).update();
        jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'submitted', \"submittedAt\" = now() WHERE id = ?")
                .param(session).update();
        JsonNode list = okA(200, "GET", "/warehouse/locations/" + loc + "/abc", null);
        list.get("rows").forEach(r -> assertThat(r.get("lastCountedAt").isNull()).as(r.toString()).isFalse());
        assertThat(jdbc.sql("SELECT count(*) FROM \"WarehouseAbcClass\" WHERE \"locationId\" = ? AND"
                + " \"lastCountedAt\" IS NOT NULL").param(loc).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("approve, dispatch and ship refuse a request without an Idempotency-Key")
    void keysRequired() {
        String p = product("KEY");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 5, 5))));
        String transferId = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId",
                alt, "mode", "transit", "responsibleById", auditUserId, "approvedById", auditUserId, "items",
                List.of(Map.of("productId", p, "qtyRequested", 1)))).get("id").asString();
        Map<String, String> noKey = Map.of("Authorization", "Bearer " + auditToken, NO_IDEMPOTENCY_KEY, "1");
        for (String path : List.of("/inventory/transfers/" + transferId + "/approve",
                "/inventory/transfers/" + transferId + "/dispatch",
                "/inventory/adjustments/" + UUID.randomUUID() + "/approve",
                "/inventory/waste/" + UUID.randomUUID() + "/approve",
                "/warehouse/pick-orders/" + UUID.randomUUID() + "/ship")) {
            Res r = call("POST", path, null, noKey);
            assertThat(r.status()).as(path).isEqualTo(400);
            assertThat(r.body().get("message").asString()).as(path).contains("Idempotency-Key header is required");
        }
        okA(200, "POST", "/inventory/transfers/" + transferId + "/approve", null);
    }
}
