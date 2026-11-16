package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PR-4 (roadmap 2.1, ADR-S026): bins, putaway, moves, pick / pack / ship, waves, ABC cycle counts, access. */
class WarehouseIT extends ApiTestSupport {

    String adminId;
    String category;
    String uom;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'").param(demoOrg)
                .query(String.class).optional().orElse(null);
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "WH-" + suffix(), "name", "Warehouse " + suffix(),
                "type", "warehouse")).get("id").asString();
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"categoryId\", \"uomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                + " 'AVCO'::\"CostingMethod\", 5, ?, ?, now())")
                .params(id, demoOrg, "WH-" + suffix(), name + " " + suffix(), category, uom).update();
        return id;
    }

    void stockIn(String product, String location, int qty) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", qty, "unitCost", 5))));
    }

    void stockOut(String product, String location, int qty) {
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", qty))));
    }

    String bin(String location, String code, String parentId, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("locationId", location, "code", code));
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        body.putAll(extra);
        return ok(201, "POST", "/warehouse/bins", body).get("id").asString();
    }

    BigDecimal binQty(String binId, String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?"
                + " AND \"productId\" = ?").params(binId, productId).query(BigDecimal.class).single();
    }

    JsonNode productRow(JsonNode overview, String productId) {
        for (JsonNode p : overview.get("products")) {
            if (productId.equals(p.get("productId").asString())) {
                return p;
            }
        }
        throw new AssertionError("product not in overview");
    }

    void completeAll(JsonNode tasks) {
        for (JsonNode t : tasks) {
            if ("open".equals(t.get("status").asString())) {
                ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of());
            }
        }
    }

    @Test
    @DisplayName("bins + putaway rules: generate splits by capacity, completes into bins, moves between bins")
    void putaway() {
        String loc = location();
        String zone = bin(loc, "Z1", null, Map.of("kind", "zone"));
        String a1 = bin(loc, "Z1-A1", zone, Map.of("pickSequence", 1, "maxQty", 30));
        String a2 = bin(loc, "Z1-A2", zone, Map.of("pickSequence", 2, "maxQty", 50));
        String other = bin(loc, "B9", null, Map.of("pickSequence", 9));
        String sand = product("Sand");

        // structure rules
        Res nested = call("POST", "/warehouse/bins", Map.of("locationId", loc, "code", "X", "parentId", a1), adminToken);
        assertThat(nested.status()).isEqualTo(400);
        assertThat(nested.body().get("message").asString()).isEqualTo("A bin cannot contain other bins");
        assertThat(call("POST", "/warehouse/bins", Map.of("locationId", loc, "code", "Z1-A1"), adminToken).status())
                .isEqualTo(409);
        assertThat(ok(200, "GET", "/warehouse/bins?locationId=" + loc, null)).hasSize(4);

        ok(201, "POST", "/warehouse/putaway-rules", Map.of("locationId", loc, "productId", sand, "targetZoneId", zone));
        stockIn(sand, loc, 70);

        JsonNode suggest = ok(200, "GET", "/warehouse/putaway/suggest?locationId=" + loc + "&productId=" + sand
                + "&quantity=70", null);
        assertThat(suggest.get("placements")).hasSize(2);
        assertThat(new BigDecimal(suggest.get("unplaced").asString())).isEqualByComparingTo("0");

        JsonNode gen = ok(200, "POST", "/warehouse/putaway/generate", Map.of("locationId", loc));
        assertThat(gen.get("created").asInt()).isEqualTo(2);
        // a second run finds nothing waiting
        assertThat(ok(200, "POST", "/warehouse/putaway/generate", Map.of("locationId", loc)).get("created").asInt())
                .isZero();
        completeAll(gen.get("tasks"));
        assertThat(binQty(a1, sand)).isEqualByComparingTo("30");
        assertThat(binQty(a2, sand)).isEqualByComparingTo("40");

        JsonNode row = productRow(ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null), sand);
        assertThat(new BigDecimal(row.get("binnedQty").asString())).isEqualByComparingTo("70");
        assertThat(new BigDecimal(row.get("unbinnedQty").asString())).isEqualByComparingTo("0");

        // capacity: a1 is full
        Res full = call("POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "move", "productId", sand,
                "quantity", 5, "fromBinId", a2, "toBinId", a1), adminToken);
        assertThat(full.status()).isEqualTo(201);
        Res over = call("POST", "/warehouse/tasks/" + full.body().get("id").asString() + "/complete", Map.of(), adminToken);
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).startsWith("Bin Z1-A1 capacity exceeded");
        JsonNode move = ok(200, "POST", "/warehouse/tasks/" + full.body().get("id").asString() + "/complete",
                Map.of("toBinId", other));
        assertThat(move.get("status").asString()).isEqualTo("done");
        assertThat(binQty(a2, sand)).isEqualByComparingTo("35");
        assertThat(binQty(other, sand)).isEqualByComparingTo("5");

        // a non-empty bin cannot be deleted or deactivated; an unused empty one is deleted
        assertThat(call("DELETE", "/warehouse/bins/" + a1, null, adminToken).status()).isEqualTo(400);
        String spare = bin(loc, "SPARE", null, Map.of());
        assertThat(ok(200, "DELETE", "/warehouse/bins/" + spare, null).get("deleted").asBoolean()).isTrue();

        // manual putaway beyond what is unbinned is refused
        Res tooMuch = call("POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", sand,
                "quantity", 1, "toBinId", other), adminToken);
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(tooMuch.body().get("message").asString()).contains("is waiting for putaway");
    }

    @Test
    @DisplayName("three-step pick → pack → ship stages stock, two-step ships, one-step ships itself, cancel returns stock")
    void outbound() {
        String loc = location();
        String b1 = bin(loc, "P1", null, Map.of("pickSequence", 1));
        String b2 = bin(loc, "P2", null, Map.of("pickSequence", 2));
        String cement = product("Cement");
        stockIn(cement, loc, 20);
        ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", cement,
                "quantity", 8, "toBinId", b1));
        ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", cement,
                "quantity", 12, "toBinId", b2));
        completeAll(ok(200, "GET", "/warehouse/tasks?locationId=" + loc + "&type=putaway", null));

        // shortage is refused
        JsonNode big = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", cement, "quantity", 25))));
        Res short1 = call("POST", "/warehouse/pick-orders/" + big.get("id").asString() + "/release", null, adminToken);
        assertThat(short1.status()).isEqualTo(400);
        assertThat(short1.body().get("message").asString()).contains("Not enough binned stock").contains("available 20");
        ok(200, "POST", "/warehouse/pick-orders/" + big.get("id").asString() + "/cancel", Map.of());

        // three steps
        JsonNode po = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 3, "sourceType",
                "sales_order", "sourceRef", "SO-1", "lines", List.of(Map.of("productId", cement, "quantity", 10))));
        String id = po.get("id").asString();
        JsonNode released = ok(200, "POST", "/warehouse/pick-orders/" + id + "/release", null);
        assertThat(released.get("status").asString()).isEqualTo("released");
        assertThat(released.get("tasks")).hasSize(2);
        assertThat(released.get("tasks").get(0).get("fromBinCode").asString()).isEqualTo("P1");
        assertThat(new BigDecimal(released.get("tasks").get(0).get("quantity").asString())).isEqualByComparingTo("8");
        assertThat(call("POST", "/warehouse/pick-orders/" + id + "/ship", Map.of(), adminToken).status()).isEqualTo(400);

        completeAll(released.get("tasks"));
        JsonNode picked = ok(200, "GET", "/warehouse/pick-orders/" + id, null);
        assertThat(picked.get("status").asString()).isEqualTo("picked");
        JsonNode row = productRow(ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null), cement);
        assertThat(new BigDecimal(row.get("stagedQty").asString())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(row.get("binnedQty").asString())).isEqualByComparingTo("10");

        Res early = call("POST", "/warehouse/pick-orders/" + id + "/ship", Map.of(), adminToken);
        assertThat(early.body().get("message").asString()).isEqualTo("Pack everything picked before shipping");
        String lineId = picked.get("lines").get(0).get("id").asString();
        JsonNode packed1 = ok(200, "POST", "/warehouse/pick-orders/" + id + "/pack", Map.of("packageType", "pallet",
                "weightKg", 120, "items", List.of(Map.of("lineId", lineId, "quantity", 6))));
        assertThat(packed1.get("status").asString()).isEqualTo("picked");
        JsonNode packed2 = ok(200, "POST", "/warehouse/pick-orders/" + id + "/pack", Map.of("packageType", "pallet"));
        assertThat(packed2.get("status").asString()).isEqualTo("packed");
        assertThat(packed2.get("packages")).hasSize(2);
        JsonNode shipped = ok(200, "POST", "/warehouse/pick-orders/" + id + "/ship", Map.of("carrier", "Own fleet",
                "trackingRef", "TRK-1"));
        assertThat(shipped.get("status").asString()).isEqualTo("shipped");
        assertThat(shipped.get("packages").get(0).get("status").asString()).isEqualTo("shipped");
        // the source document issues the ledger stock: staged drops once it is shipped
        stockOut(cement, loc, 10);
        row = productRow(ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null), cement);
        assertThat(new BigDecimal(row.get("stagedQty").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(row.get("unbinnedQty").asString())).isEqualByComparingTo("0");

        // one step ships itself on the last pick
        JsonNode one = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 1, "lines",
                List.of(Map.of("productId", cement, "quantity", 3))));
        completeAll(ok(200, "POST", "/warehouse/pick-orders/" + one.get("id").asString() + "/release", null).get("tasks"));
        assertThat(ok(200, "GET", "/warehouse/pick-orders/" + one.get("id").asString(), null).get("status").asString())
                .isEqualTo("shipped");

        // cancel after picking needs a return bin and puts the stock back
        JsonNode two = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", cement, "quantity", 4))));
        String twoId = two.get("id").asString();
        completeAll(ok(200, "POST", "/warehouse/pick-orders/" + twoId + "/release", null).get("tasks"));
        BigDecimal before = binQty(b2, cement);
        Res noBin = call("POST", "/warehouse/pick-orders/" + twoId + "/cancel", Map.of(), adminToken);
        assertThat(noBin.body().get("message").asString())
                .isEqualTo("returnBinId is required to put the picked stock back");
        assertThat(ok(200, "POST", "/warehouse/pick-orders/" + twoId + "/cancel", Map.of("returnBinId", b2))
                .get("status").asString()).isEqualTo("cancelled");
        assertThat(binQty(b2, cement)).isEqualByComparingTo(before.add(BigDecimal.valueOf(4)));
    }

    @Test
    @DisplayName("a wave releases its orders as one sequenced pick list and finishes when they ship")
    void waves() {
        String loc = location();
        String b1 = bin(loc, "W1", null, Map.of("pickSequence", 1));
        String p1 = product("Nails");
        String p2 = product("Screws");
        stockIn(p1, loc, 10);
        stockIn(p2, loc, 10);
        ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", p1,
                "quantity", 10, "toBinId", b1));
        ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", p2,
                "quantity", 10, "toBinId", b1));
        completeAll(ok(200, "GET", "/warehouse/tasks?locationId=" + loc, null));

        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            orders.add(ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "priority", i,
                    "lines", List.of(Map.of("productId", p1, "quantity", 2), Map.of("productId", p2, "quantity", 1))))
                    .get("id").asString());
        }
        JsonNode wave = ok(201, "POST", "/warehouse/waves", Map.of("locationId", loc, "pickOrderIds", orders));
        String waveId = wave.get("id").asString();
        assertThat(wave.get("orders")).hasSize(3);
        assertThat(call("POST", "/warehouse/waves", Map.of("locationId", loc, "pickOrderIds", List.of(orders.get(0))),
                adminToken).status()).isEqualTo(400);

        JsonNode released = ok(200, "POST", "/warehouse/waves/" + waveId + "/release", null);
        assertThat(released.get("status").asString()).isEqualTo("released");
        assertThat(released.get("pickList")).hasSize(2);
        assertThat(released.get("tasks")).hasSize(6);
        completeAll(ok(200, "GET", "/warehouse/tasks?locationId=" + loc + "&type=pick&waveId=" + waveId, null));
        for (String o : orders) {
            ok(200, "POST", "/warehouse/pick-orders/" + o + "/ship", Map.of());
        }
        assertThat(ok(200, "GET", "/warehouse/waves/" + waveId, null).get("status").asString()).isEqualTo("done");
        assertThat(ok(200, "GET", "/warehouse/waves?locationId=" + loc, null)).hasSize(1);
        assertThat(binQty(b1, p1)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("ABC classes by consumption value drive a blind cycle count of the due products")
    void abcCycleCounts() {
        String loc = location();
        String fast = product("Fast mover");
        String mid = product("Mid mover");
        String slow = product("Slow mover");
        stockIn(fast, loc, 100);
        stockIn(mid, loc, 100);
        stockIn(slow, loc, 100);
        stockOut(fast, loc, 80);
        stockOut(mid, loc, 15);
        stockOut(slow, loc, 5);

        ok(200, "PUT", "/warehouse/locations/" + loc + "/settings", Map.of("abcAPercent", 70, "abcBPercent", 90));
        JsonNode classified = ok(200, "POST", "/warehouse/locations/" + loc + "/abc/classify", null);
        assertThat(classified.get("products").asInt()).isEqualTo(3);
        Map<String, String> classes = new HashMap<>();
        for (JsonNode r : classified.get("rows")) {
            classes.put(r.get("productId").asString(), r.get("abcClass").asString());
        }
        assertThat(classes).containsEntry(fast, "A").containsEntry(mid, "B").containsEntry(slow, "C");

        JsonNode gen = ok(201, "POST", "/warehouse/locations/" + loc + "/cycle-counts", Map.of("classes", List.of("A",
                "B")));
        assertThat(gen.get("products").asInt()).isEqualTo(2);
        String sessionId = gen.get("session").get("id").asString();
        assertThat(jdbc.sql("SELECT \"countType\"::text FROM \"InventoryCountSession\" WHERE id = ?").param(sessionId)
                .query(String.class).single()).isEqualTo("cycle");
        Res again = call("POST", "/warehouse/locations/" + loc + "/cycle-counts", Map.of(), adminToken);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).contains("is still open for this location");

        JsonNode list = ok(200, "GET", "/warehouse/locations/" + loc + "/abc", null);
        long scheduled = 0;
        for (JsonNode r : list.get("rows")) {
            if (r.get("scheduled").asBoolean()) {
                scheduled++;
            }
        }
        assertThat(scheduled).isEqualTo(2);
        assertThat(list.get("due").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("settings, bin edits, rule edits, bin stock, task cancel and state guards")
    void masterDataAndGuards() {
        String loc = location();
        JsonNode defaults = ok(200, "GET", "/warehouse/locations/" + loc + "/settings", null);
        assertThat(defaults.get("configured").asBoolean()).isFalse();
        assertThat(defaults.get("outboundSteps").asInt()).isEqualTo(1);
        JsonNode saved = ok(200, "PUT", "/warehouse/locations/" + loc + "/settings", Map.of("outboundSteps", 2,
                "countDaysA", 15));
        assertThat(saved.get("configured").asBoolean()).isTrue();
        assertThat(saved.get("enabled").asBoolean()).isTrue();
        assertThat(saved.get("countDaysA").asInt()).isEqualTo(15);
        assertThat(call("PUT", "/warehouse/locations/" + loc + "/settings", Map.of("abcAPercent", 96), adminToken)
                .body().get("message").asString()).isEqualTo("abcAPercent must be lower than abcBPercent");

        String zone = bin(loc, "ZA", null, Map.of("kind", "zone"));
        String zone2 = bin(loc, "ZB", null, Map.of("kind", "zone"));
        String b = bin(loc, "ZA-1", zone, Map.of());
        // rename, move under another zone, refuse a cycle and a kind change with children
        JsonNode renamed = ok(200, "PATCH", "/warehouse/bins/" + b, Map.of("code", "ZB-1", "parentId", zone2,
                "maxQty", 100, "mixedProducts", false));
        assertThat(renamed.get("code").asString()).isEqualTo("ZB-1");
        assertThat(call("PATCH", "/warehouse/bins/" + zone, Map.of("parentId", zone), adminToken).status())
                .isEqualTo(400);
        assertThat(call("PATCH", "/warehouse/bins/" + zone2, Map.of("kind", "bin"), adminToken).status())
                .isEqualTo(400);
        assertThat(call("PATCH", "/warehouse/bins/" + zone2, Map.of("code", "ZA"), adminToken).status())
                .isEqualTo(409);

        String gravel = product("Gravel");
        String sand = product("Fine sand");
        stockIn(gravel, loc, 10);
        stockIn(sand, loc, 10);
        JsonNode t = ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId",
                gravel, "quantity", 6));
        assertThat(t.get("toBinCode").asString()).isEqualTo("ZB-1");
        ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of("quantity", 6));
        assertThat(ok(200, "GET", "/warehouse/bins/" + b, null).get("stock")).hasSize(1);
        assertThat(ok(200, "GET", "/warehouse/bin-stock?locationId=" + loc + "&productId=" + gravel, null)).hasSize(1);
        // single-product bin refuses another product
        JsonNode other = ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId",
                sand, "quantity", 2, "toBinId", b));
        Res mixed = call("POST", "/warehouse/tasks/" + other.get("id").asString() + "/complete", Map.of(), adminToken);
        assertThat(mixed.body().get("message").asString()).isEqualTo("Bin ZB-1 already holds another product");
        JsonNode cancelled = ok(200, "POST", "/warehouse/tasks/" + other.get("id").asString() + "/cancel", null);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(call("POST", "/warehouse/tasks/" + other.get("id").asString() + "/cancel", null, adminToken)
                .status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/warehouse/tasks?locationId=" + loc + "&status=cancelled", null)).hasSize(1);
        // a bin holding stock cannot become a zone, be deactivated or shrink below its quantity
        assertThat(call("PATCH", "/warehouse/bins/" + b, Map.of("isActive", false), adminToken).status())
                .isEqualTo(400);
        assertThat(call("PATCH", "/warehouse/bins/" + b, Map.of("maxQty", 1), adminToken).status()).isEqualTo(400);
        // used bins are deactivated instead of deleted once empty
        String spare = bin(loc, "ZA-2", zone, Map.of());
        JsonNode mv = ok(201, "POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "move", "productId", gravel,
                "quantity", 6, "fromBinId", b, "toBinId", spare));
        ok(200, "POST", "/warehouse/tasks/" + mv.get("id").asString() + "/complete", null);
        JsonNode del = ok(200, "DELETE", "/warehouse/bins/" + b, null);
        assertThat(del.get("deactivated").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/warehouse/bins?locationId=" + loc + "&includeInactive=true", null)).hasSize(4);
        assertThat(ok(200, "GET", "/warehouse/bins?locationId=" + loc, null)).hasSize(3);

        // rules
        assertThat(call("POST", "/warehouse/putaway-rules", Map.of("locationId", loc, "productId", gravel), adminToken)
                .body().get("message").asString()).isEqualTo("A rule needs a targetBinId or a targetZoneId");
        assertThat(call("POST", "/warehouse/putaway-rules", Map.of("locationId", loc, "productId", gravel,
                "categoryId", "x", "targetZoneId", zone), adminToken).status()).isEqualTo(400);
        String rule = ok(201, "POST", "/warehouse/putaway-rules", Map.of("locationId", loc, "targetZoneId", zone,
                "priority", 5)).get("id").asString();
        JsonNode patched = ok(200, "PATCH", "/warehouse/putaway-rules/" + rule, Map.of("targetBinId", spare,
                "targetZoneId", "", "priority", 1));
        assertThat(patched.get("targetBinId").asString()).isEqualTo(spare);
        assertThat(ok(200, "GET", "/warehouse/putaway-rules?locationId=" + loc, null)).hasSize(1);
        JsonNode suggest = ok(200, "GET", "/warehouse/putaway/suggest?locationId=" + loc + "&productId=" + sand
                + "&quantity=3", null);
        assertThat(suggest.get("placements").get(0).get("binId").asString()).isEqualTo(spare);
        ok(200, "DELETE", "/warehouse/putaway-rules/" + rule, null);
        assertThat(call("DELETE", "/warehouse/putaway-rules/" + rule, null, adminToken).status()).isEqualTo(404);

        // pick order defaults to the location's two steps; packing is for three steps only
        JsonNode po = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "lines",
                List.of(Map.of("productId", gravel, "quantity", 2))));
        assertThat(po.get("steps").asInt()).isEqualTo(2);
        String poId = po.get("id").asString();
        assertThat(call("POST", "/warehouse/pick-orders/" + poId + "/pack", Map.of(), adminToken).body().get("message")
                .asString()).isEqualTo("Only a three-step pick order is packed");
        JsonNode rel = ok(200, "POST", "/warehouse/pick-orders/" + poId + "/release", null);
        assertThat(call("POST", "/warehouse/pick-orders/" + poId + "/release", null, adminToken).status())
                .isEqualTo(400);
        String pickTask = rel.get("tasks").get(0).get("id").asString();
        // a short pick (1 of 2) still finishes picking
        ok(200, "POST", "/warehouse/tasks/" + pickTask + "/complete", Map.of("quantity", 1));
        assertThat(ok(200, "GET", "/warehouse/pick-orders?locationId=" + loc + "&status=picked", null)).hasSize(1);
        ok(200, "POST", "/warehouse/pick-orders/" + poId + "/ship", null);
        assertThat(call("POST", "/warehouse/pick-orders/" + poId + "/ship", null, adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/warehouse/pick-orders/" + poId + "/cancel", null, adminToken).status())
                .isEqualTo(400);
        assertThat(call("GET", "/warehouse/pick-orders/" + UUID.randomUUID(), null, adminToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/warehouse/waves/" + UUID.randomUUID(), null, adminToken).status()).isEqualTo(404);
        assertThat(call("POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "move", "productId", gravel,
                "quantity", 1, "fromBinId", spare), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/warehouse/locations/" + loc + "/cycle-counts", Map.of(), adminToken).body()
                .get("message").asString()).isEqualTo("No products are due for a cycle count");
    }

    BigDecimal onHand(String product, String location) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, location).query(BigDecimal.class).single();
    }

    void putaway(String location, String product, int qty, String binId) {
        JsonNode t = ok(201, "POST", "/warehouse/tasks", Map.of("locationId", location, "type", "putaway", "productId",
                product, "quantity", qty, "toBinId", binId));
        ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of());
    }

    @Test
    @DisplayName("a manual pick order issues the shipped stock with GL; a sourced one does not; empty picks never ship")
    void manualShipIssuesStock() {
        String loc = location();
        String b1 = bin(loc, "M1", null, Map.of("pickSequence", 1));
        String bolts = product("Bolts");
        stockIn(bolts, loc, 10);
        putaway(loc, bolts, 10, b1);

        JsonNode manual = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", bolts, "quantity", 4))));
        String id = manual.get("id").asString();
        completeAll(ok(200, "POST", "/warehouse/pick-orders/" + id + "/release", null).get("tasks"));
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("10");
        String code = manual.get("code").asString();
        ok(200, "POST", "/warehouse/pick-orders/" + id + "/ship", Map.of("carrier", "Van"));
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("6");
        long rows = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"referenceType\" = 'WarehousePickOrder' AND \"referenceId\" = ? AND \"quantityChange\" = -4")
                .params(demoOrg, code).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'WarehousePickOrder' AND \"sourceId\" = ?").params(demoOrg, code).query(Long.class).single())
                .isEqualTo(1);
        // a second ship is refused and posts nothing
        assertThat(call("POST", "/warehouse/pick-orders/" + id + "/ship", Map.of(), adminToken).status()).isEqualTo(400);
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("6");
        JsonNode row = productRow(ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null), bolts);
        assertThat(row.get("mismatch").asBoolean()).isFalse();
        assertThat(new BigDecimal(row.get("binnedQty").asString())).isEqualByComparingTo("6");

        // a sales-order pick order: the sales document posts the issue, shipping leaves the ledger alone
        JsonNode sourced = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2,
                "sourceType", "sales_order", "sourceRef", "SO-9", "lines", List.of(Map.of("productId", bolts,
                        "quantity", 1))));
        completeAll(ok(200, "POST", "/warehouse/pick-orders/" + sourced.get("id").asString() + "/release", null)
                .get("tasks"));
        ok(200, "POST", "/warehouse/pick-orders/" + sourced.get("id").asString() + "/ship", Map.of());
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("6");

        // one step, every pick task cancelled: back to draft, not shipped, nothing issued
        JsonNode one = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 1, "lines",
                List.of(Map.of("productId", bolts, "quantity", 2))));
        String oneId = one.get("id").asString();
        for (JsonNode t : ok(200, "POST", "/warehouse/pick-orders/" + oneId + "/release", null).get("tasks")) {
            ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/cancel", null);
        }
        assertThat(ok(200, "GET", "/warehouse/pick-orders/" + oneId, null).get("status").asString())
                .isEqualTo("draft");
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("6");
        // released again and picked, it ships and issues once
        completeAll(ok(200, "POST", "/warehouse/pick-orders/" + oneId + "/release", null).get("tasks"));
        assertThat(ok(200, "GET", "/warehouse/pick-orders/" + oneId, null).get("status").asString())
                .isEqualTo("shipped");
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("bin drift: release is capped by the ledger, reconcile trims the bins, adjust stays within on-hand")
    void binDriftAdjustReconcile() {
        String loc = location();
        String pick = bin(loc, "D1", null, Map.of("pickSequence", 1));
        String back = bin(loc, "D9", null, Map.of("pickSequence", 9, "usage", "quarantine"));
        String rope = product("Rope");
        stockIn(rope, loc, 10);
        putaway(loc, rope, 8, pick);
        // quarantine bins cannot be put away to by the planner, but a manual putaway names the bin
        putaway(loc, rope, 2, back);
        stockOut(rope, loc, 4); // issued without a pick order: the bins now hold 4 more than the ledger
        JsonNode overview = ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null);
        JsonNode row = productRow(overview, rope);
        assertThat(row.get("mismatch").asBoolean()).isTrue();
        assertThat(new BigDecimal(row.get("excessBinnedQty").asString())).isEqualByComparingTo("4");
        assertThat(overview.get("mismatches").asInt()).isEqualTo(1);

        JsonNode po = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", rope, "quantity", 7))));
        Res capped = call("POST", "/warehouse/pick-orders/" + po.get("id").asString() + "/release", null, adminToken);
        assertThat(capped.status()).isEqualTo(400);
        assertThat(capped.body().get("message").asString()).startsWith("Not enough stock on hand to pick")
                .contains("on hand 6");

        JsonNode rec = ok(200, "POST", "/warehouse/locations/" + loc + "/reconcile", Map.of());
        assertThat(rec.get("reconciled").asInt()).isEqualTo(1);
        // pickable storage bins are trimmed first, the quarantine bin keeps its stock
        assertThat(binQty(pick, rope)).isEqualByComparingTo("4");
        assertThat(binQty(back, rope)).isEqualByComparingTo("2");
        assertThat(productRow(ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null), rope)
                .get("mismatch").asBoolean()).isFalse();
        assertThat(ok(200, "POST", "/warehouse/locations/" + loc + "/reconcile", null).get("reconciled").asInt())
                .isZero();

        // adjust: never more than the ledger holds, a reason is required, decreases are free
        Res over = call("POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "newQty", 5,
                "reason", "found"), adminToken);
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).startsWith("Only 0 of");
        Res noReason = call("POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "delta", -1),
                adminToken);
        assertThat(noReason.status()).isEqualTo(400);
        Res both = call("POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "delta", -1,
                "newQty", 1, "reason", "x"), adminToken);
        assertThat(both.body().get("message").asString()).isEqualTo("Give either newQty or delta");
        JsonNode down = ok(200, "POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "delta", -3,
                "reason", "damaged shelf count"));
        assertThat(new BigDecimal(down.get("change").asString())).isEqualByComparingTo("-3");
        assertThat(binQty(pick, rope)).isEqualByComparingTo("1");
        ok(200, "POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "newQty", 4,
                "reason", "recounted"));
        assertThat(binQty(pick, rope)).isEqualByComparingTo("4");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'WarehouseBinStock' AND \"entityId\" = ?"
                + " AND action = 'adjust'").param(pick).query(Long.class).single()).isEqualTo(2);
        // the ledger never moved
        assertThat(onHand(rope, loc)).isEqualByComparingTo("6");
        // now the order fits the ledger: 6 on hand, 6 binned
        JsonNode po2 = ok(201, "POST", "/warehouse/pick-orders", Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", rope, "quantity", 4))));
        ok(200, "POST", "/warehouse/pick-orders/" + po2.get("id").asString() + "/release", null);
        // a held quantity cannot be adjusted away
        Res held = call("POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "newQty", 0,
                "reason", "x"), adminToken);
        assertThat(held.body().get("message").asString()).contains("is held by open pick / move tasks");
        assertThat(call("POST", "/warehouse/bins/" + pick + "/adjust", Map.of("productId", rope, "newQty", 1,
                "reason", "x"), weakToken()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a location whose warehouse settings are switched off refuses warehouse work but still reads")
    void settingsEnabledEnforced() {
        String loc = location();
        String b = bin(loc, "E1", null, Map.of());
        ok(200, "PUT", "/warehouse/locations/" + loc + "/settings", Map.of("enabled", false));
        Res create = call("POST", "/warehouse/bins", Map.of("locationId", loc, "code", "E2"), adminToken);
        assertThat(create.status()).isEqualTo(400);
        assertThat(create.body().get("message").asString())
                .isEqualTo("Warehouse management is switched off for this location; turn it on in the warehouse"
                        + " settings first");
        String sand = product("Off sand");
        stockIn(sand, loc, 3);
        assertThat(call("POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", sand,
                "quantity", 1, "toBinId", b), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/warehouse/pick-orders", Map.of("locationId", loc, "lines", List.of(Map.of(
                "productId", sand, "quantity", 1))), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/warehouse/putaway/generate", Map.of("locationId", loc), adminToken).status())
                .isEqualTo(400);
        assertThat(ok(200, "GET", "/warehouse/bins?locationId=" + loc, null)).hasSize(1);
        ok(200, "GET", "/warehouse/locations/" + loc + "/overview", null);
        ok(200, "PUT", "/warehouse/locations/" + loc + "/settings", Map.of("enabled", true));
        putaway(loc, sand, 1, b);
        assertThat(binQty(b, sand)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("routes need warehouse permissions and stay inside the organization")
    void access() {
        String loc = location();
        String weak = weakToken();
        assertThat(call("GET", "/warehouse/bins?locationId=" + loc, null, weak).status()).isEqualTo(403);
        assertThat(call("POST", "/warehouse/bins", Map.of("locationId", loc, "code", "Q"), weak).status())
                .isEqualTo(403);
        assertThat(call("POST", "/warehouse/putaway/generate", Map.of("locationId", loc), weak).status())
                .isEqualTo(403);
        assertThat(call("GET", "/warehouse/bins?locationId=" + loc, null, otherToken).status()).isEqualTo(404);
        String binId = bin(loc, "T1", null, Map.of());
        assertThat(call("GET", "/warehouse/bins/" + binId, null, otherToken).status()).isEqualTo(404);
        Res bad = call("PUT", "/warehouse/locations/" + loc + "/settings", Map.of("outboundSteps", 4), adminToken);
        assertThat(bad.status()).isEqualTo(400);
    }
}
