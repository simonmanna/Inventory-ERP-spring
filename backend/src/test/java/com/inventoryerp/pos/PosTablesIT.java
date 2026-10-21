package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.pos.application.TableStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Floor management over HTTP (ports of {@code pos-tables.spec.ts}, {@code pos-table-zones.spec.ts},
 * {@code pos-table-tab-uniqueness.spec.ts}): zones catalog, table CRUD guards, merge / unmerge, transfer,
 * item transfer, bill split, one open tab per table.
 */
class PosTablesIT extends ApiTestSupport {

    static final AtomicInteger NUMBER = new AtomicInteger((int) (Math.random() * 1000) * 1000 + 5000);

    String productId;
    String sessionId;
    String registerId;

    @org.junit.jupiter.api.AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }
    final Map<String, String> products = new LinkedHashMap<>();

    @BeforeAll
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        for (Map.Entry<String, Integer> p : Map.of("Coffee", 5000, "Burger", 15000, "Juice", 8000, "Tea", 3000,
                "Sale", 30000).entrySet()) {
            products.put(p.getKey(), ok(201, "POST", "/products", Map.of("code", "TBL-" + p.getKey() + "-" + suffix,
                    "name", p.getKey(), "productType", "service", "salesPrice", p.getValue())).get("id").asString());
        }
        productId = products.get("Juice");
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "TREG-" + suffix, "name", "Floor till"))
                .get("id").asString();
        Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        sessionId = opened.body().get("id").asString();
    }

    String table(String name) {
        return ok(201, "POST", "/pos/tables", Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 4),
                "number", NUMBER.incrementAndGet())).get("id").asString();
    }

    Map<String, Object> line(String description, int qty, int price) {
        return Map.of("productId", products.getOrDefault(description, productId), "description", description,
                "quantity", qty, "unitPrice", price);
    }

    /** Open a tab on a table with the given lines (first save → newRound). */
    JsonNode tab(String tableId, List<Map<String, Object>> lines) {
        return ok(201, "POST", "/pos/tabs/" + tableId + "/save", Map.of("cashSessionId", sessionId, "guestCount", 2,
                "newRound", true, "lines", lines));
    }

    String status(String tableId) {
        return ok(200, "GET", "/pos/tables/" + tableId, null).get("status").asString();
    }

    @Test
    @DisplayName("zones: lazy seed for a fresh org, create/rename, duplicate/re-key rejected, archive guard, restore")
    void zones() {
        JsonNode seeded = call("GET", "/pos/tables/zones", null, otherToken).body();
        List<String> keys = new ArrayList<>();
        seeded.forEach(z -> keys.add(z.get("key").asString()));
        assertThat(keys).containsExactly("indoor", "outdoor", "terrace", "vip", "garden", "bar");
        assertThat(seeded.get(0).get("sortOrder").asInt()).isEqualTo(1);

        String name = "Rooftop" + UUID.randomUUID().toString().substring(0, 4);
        JsonNode created = ok(201, "POST", "/pos/tables/zones", Map.of("name", name, "color", "#0ea5e9", "sortOrder", 7));
        String key = created.get("key").asString();
        assertThat(key).isEqualTo(name.toLowerCase());
        JsonNode renamed = ok(200, "PATCH", "/pos/tables/zones/" + created.get("id").asString(), Map.of("name", "Sky Deck",
                "sortOrder", 0));
        assertThat(renamed.get("name").asString()).isEqualTo("Sky Deck");
        assertThat(renamed.get("key").asString()).isEqualTo(key);
        Res dup = as("POST", "/pos/tables/zones", Map.of("name", "Indoor Clone", "key", "indoor"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("message").asString()).isEqualTo("Zone key \"indoor\" already exists");
        Res rekey = as("PATCH", "/pos/tables/zones/" + created.get("id").asString(), Map.of("key", "moved"));
        assertThat(rekey.status()).isEqualTo(400);

        JsonNode ref = ok(201, "POST", "/pos/tables", Map.of("name", "Ref " + UUID.randomUUID(), "number",
                NUMBER.incrementAndGet(), "zone", key));
        assertThat(ref.get("zone").asString()).isEqualTo(key);
        assertThat(as("DELETE", "/pos/tables/zones/" + created.get("id").asString(), null).status()).isEqualTo(409);
        ok(200, "DELETE", "/pos/tables/" + ref.get("id").asString(), null);
        JsonNode archived = ok(200, "DELETE", "/pos/tables/zones/" + created.get("id").asString(), null);
        assertThat(archived.get("deletedAt").isNull()).isFalse();
        assertThat(ok(200, "GET", "/pos/tables/zones", null).toString()).doesNotContain(created.get("id").asString());
        assertThat(ok(200, "GET", "/pos/tables/zones/deleted", null).toString()).contains(created.get("id").asString());
        JsonNode restored = ok(200, "PATCH", "/pos/tables/zones/" + created.get("id").asString() + "/restore", null);
        assertThat(restored.get("deletedAt").isNull()).isTrue();

        Res bad = as("POST", "/pos/tables", Map.of("name", "BadZone", "number", NUMBER.incrementAndGet(),
                "zone", "does_not_exist"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).startsWith("Unknown zone \"does_not_exist\" — available zones: ");
    }

    @Test
    @DisplayName("table CRUD guards: duplicate number, archive with an open order, status, waiter, stats")
    void crud() {
        int number = NUMBER.incrementAndGet();
        String id = ok(201, "POST", "/pos/tables", Map.of("name", "CRUD", "number", number, "seats", 4)).get("id").asString();
        Res dup = as("POST", "/pos/tables", Map.of("name", "CRUD2", "number", number));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("message").asString()).isEqualTo("Table number " + number + " already exists");
        tab(id, List.of(line("Juice", 1, 5000)));
        Res archive = as("DELETE", "/pos/tables/" + id, null);
        assertThat(archive.status()).isEqualTo(409);
        assertThat(archive.body().get("message").asString()).isEqualTo("Cannot archive table " + number + ": 1 open order(s)");
        Res badStatus = as("PUT", "/pos/tables/" + id + "/status", Map.of("status", "cleaning"));
        assertThat(badStatus.raw().body())
                .contains("status must be one of the following values: available, occupied, reserved, out_of_service");
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        assertThat(ok(201, "POST", "/pos/tables/" + id + "/assign-waiter", Map.of("waiterId", admin))
                .get("assignedWaiterId").asString()).isEqualTo(admin);
        JsonNode stats = ok(200, "GET", "/pos/tables/stats", null);
        assertThat(stats.toString()).contains("occupied");
        assertThat(ok(200, "GET", "/pos/tables?status=occupied", null).toString()).contains(id);
        Res other = call("GET", "/pos/tables/" + id, null, otherToken);
        assertThat(other.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("one open tab per table: a second order on the table is refused, even concurrently")
    void oneOpenTab() {
        String id = table("Uniq");
        Map<String, Object> body = Map.of("tableId", id, "orderType", "dine_in", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(line("Juice", 1, 5000)));
        List<CompletableFuture<Res>> races = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            races.add(CompletableFuture.supplyAsync(() -> as("POST", "/pos/orders", body)));
        }
        List<Integer> statuses = races.stream().map(f -> f.join().status()).toList();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Order\" WHERE \"tableId\" = ? AND status::text <> 'cancelled'")
                .param(id).query(Long.class).single()).isEqualTo(1);
        Res third = as("POST", "/pos/orders", body);
        assertThat(third.status()).isEqualTo(409);
        // Takeaway orders carry no table and are never blocked.
        ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "cashSessionId", sessionId,
                "lines", List.of(line("Juice", 1, 5000))));
    }

    @Test
    @DisplayName("merge moves the open order to the target; unmerge needs the source drained")
    void mergeAndUnmerge() {
        String src = table("Merge-Src");
        String tgt = table("Merge-Tgt");
        assertThat(as("POST", "/pos/tables/" + src + "/merge/" + src, null).body().get("message").asString())
                .isEqualTo("A table cannot be merged into itself");
        JsonNode order = tab(src, List.of(line("Coffee", 2, 5000)));
        ok(201, "POST", "/pos/tables/" + src + "/merge/" + tgt, null);
        assertThat(jdbc.sql("SELECT \"tableId\" FROM \"Order\" WHERE id = ?").param(order.get("id").asString())
                .query(String.class).single()).isEqualTo(tgt);
        assertThat(jdbc.sql("SELECT \"mergedIntoId\" FROM \"PosTable\" WHERE id = ?").param(src).query(String.class)
                .single()).isEqualTo(tgt);
        assertThat(as("POST", "/pos/tables/" + src + "/merge/" + tgt, null).status()).isEqualTo(409);
        ok(201, "POST", "/pos/tables/" + src + "/unmerge", null);
        assertThat(jdbc.sql("SELECT \"mergedIntoId\" FROM \"PosTable\" WHERE id = ?").param(src).query().singleRow()
                .get("mergedIntoId")).isNull();
        assertThat(as("POST", "/pos/tables/" + src + "/unmerge", null).body().get("message").asString())
                .isEqualTo("Table is not merged");
    }

    @Test
    @DisplayName("transfer refuses an occupied target and moves the whole order to a free one")
    void transfer() {
        String src = table("Tfr-Src");
        String busy = table("Tfr-Busy");
        String free = table("Tfr-Free");
        tab(src, List.of(line("Coffee", 1, 5000)));
        tab(busy, List.of(line("Tea", 1, 3000)));
        Res refused = as("POST", "/pos/tables/" + src + "/transfer/" + busy, null);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("message").asString()).startsWith("Target table T");
        ok(201, "POST", "/pos/tables/" + src + "/transfer/" + free, null);
        assertThat(status(free)).isEqualTo("occupied");
        assertThat(status(src)).isEqualTo("available");
        // Nest checks the target before the source, so a drained source re-sent to the now-busy target says so.
        assertThat(as("POST", "/pos/tables/" + src + "/transfer/" + free, null).body().get("message").asString())
                .startsWith("Target table T").endsWith("is occupied; cannot transfer into it");
    }

    @Test
    @DisplayName("transfer-items moves selected lines and frees a fully drained source")
    void transferItems() {
        String src = table("TI-Src");
        String tgt = table("TI-Tgt");
        JsonNode order = tab(src, List.of(line("Coffee", 2, 5000), line("Burger", 1, 15000), line("Juice", 1, 8000)));
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, \"productId\" FROM \"OrderItem\" WHERE \"orderId\" = ?"
                + " AND cancelled = false ORDER BY \"lineNumber\"").param(order.get("id").asString()).query().listOfRows();
        assertThat(rows).hasSize(3);
        List<Map<String, Object>> move = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!products.get("Coffee").equals(r.get("productId"))) {
                move.add(Map.of("lineId", r.get("id"), "quantity", 1));
            }
        }
        Res tooMuch = as("POST", "/pos/tables/" + src + "/transfer-items/" + tgt, Map.of("items",
                List.of(Map.of("lineId", move.getFirst().get("lineId"), "quantity", 5))));
        assertThat(tooMuch.status()).isEqualTo(400);
        ok(201, "POST", "/pos/tables/" + src + "/transfer-items/" + tgt, Map.of("items", move));
        assertThat(jdbc.sql("SELECT count(*) FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false")
                .param(order.get("id").asString()).query(Long.class).single()).isEqualTo(1);
        assertThat(status(tgt)).isEqualTo("occupied");
        // The move rebuilds the source lines (new ids), so re-read the remaining one.
        String coffee = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false")
                .param(order.get("id").asString()).query(String.class).single();
        ok(201, "POST", "/pos/tables/" + src + "/transfer-items/" + tgt, Map.of("items",
                List.of(Map.of("lineId", coffee, "quantity", 2))));
        assertThat(status(src)).isEqualTo("available");
    }

    @Test
    @DisplayName("split-bill must cover each source line exactly")
    void splitBill() {
        String id = table("Split");
        JsonNode order = tab(id, List.of(line("Coffee", 2, 5000), line("Juice", 1, 8000)));
        List<Map<String, Object>> rows = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\"").param(order.get("id").asString()).query().listOfRows();
        Object coffee = rows.get(0).get("id");
        Object juice = rows.get(1).get("id");
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("sourceOrderId", order.get("id").asString());
        missing.put("splits", List.of(Map.of("label", "A", "lines", List.of(Map.of("sourceItemId", coffee, "quantity", 2)))));
        Res bad = as("POST", "/pos/tables/" + id + "/split-bill", missing);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).startsWith("Split quantities do not match source item");
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("sourceOrderId", order.get("id").asString());
        good.put("splits", List.of(
                Map.of("label", "A", "lines", List.of(Map.of("sourceItemId", coffee, "quantity", 1))),
                Map.of("label", "B", "lines", List.of(Map.of("sourceItemId", coffee, "quantity", 1),
                        Map.of("sourceItemId", juice, "quantity", 1)))));
        // Kept as in Nest: the child orders are raised while the source tab still holds the table, so the
        // Order_one_open_dine_in_tab_per_table index rejects them (P2002 → generic 409). Split bills go through
        // /pos/tabs/:tableId/split instead.
        Res split = as("POST", "/pos/tables/" + id + "/split-bill", good);
        assertThat(split.status()).isEqualTo(409);
        assertThat(split.body().get("message").asString())
                .isEqualTo("This operation conflicts with one that was just completed. Refresh and try again.");
    }

    @Test
    @DisplayName("table revenue report rolls up POS invoices by table")
    void revenue() {
        String id = table("Report");
        tab(id, List.of(line("Sale", 2, 1)));
        Res paid = call("POST", "/pos/tabs/" + id + "/settle", Map.of("cashSessionId", sessionId, "tenders",
                List.of(Map.of("method", "cash", "amount", 60000))),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("available");
        String today = java.time.LocalDate.now().toString();
        JsonNode revenue = ok(200, "GET", "/pos/reports/tables/revenue?fromDate=" + today + "&toDate=" + today, null);
        JsonNode row = null;
        for (JsonNode r : revenue.get("perTable")) {
            if (id.equals(r.get("tableId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("revenue").asString()).isEqualTo("60000.00");
        assertThat(row.get("orders").asInt()).isEqualTo(1);
    }

    void forceStatus(String tableId, String status) {
        jdbc.sql("UPDATE \"PosTable\" SET status = ?::\"PosTableStatus\" WHERE id = ?").params(status, tableId).update();
    }

    String rawStatus(String tableId) {
        return jdbc.sql("SELECT status::text FROM \"PosTable\" WHERE id = ?").param(tableId).query(String.class).single();
    }

    @Test
    @DisplayName("recomputeTableStatus: derived from live items, overrides never flip, legacy cleaning heals")
    void recomputeTableStatus() {
        // Port of table-status.util.spec.ts against the real tables instead of Prisma doubles.
        assertThat(TableStatus.recompute(jdbc, demoOrg, null)).isNull();
        assertThat(TableStatus.recompute(jdbc, demoOrg, "")).isNull();
        assertThat(TableStatus.recompute(jdbc, demoOrg, UUID.randomUUID().toString())).isNull();
        assertThat(TableStatus.HELD).contains("served").doesNotContain("closed", "cancelled");

        String empty = table("Derive");
        forceStatus(empty, "occupied");
        assertThat(TableStatus.recompute(jdbc, demoOrg, empty)).isEqualTo("available");
        assertThat(rawStatus(empty)).isEqualTo("available");
        assertThat(TableStatus.recompute(jdbc, demoOrg, empty)).isEqualTo("available");
        forceStatus(empty, "cleaning");
        assertThat(TableStatus.recompute(jdbc, demoOrg, empty)).isEqualTo("available");

        String busy = table("Busy");
        tab(busy, List.of(line("Juice", 1, 8000)));
        forceStatus(busy, "available");
        assertThat(TableStatus.recompute(jdbc, demoOrg, busy)).isEqualTo("occupied");
        assertThat(rawStatus(busy)).isEqualTo("occupied");
        forceStatus(busy, "cleaning");
        assertThat(TableStatus.recompute(jdbc, demoOrg, busy)).isEqualTo("occupied");
        for (String override : List.of("reserved", "out_of_service")) {
            forceStatus(busy, override);
            assertThat(TableStatus.recompute(jdbc, demoOrg, busy)).isEqualTo(override);
            assertThat(rawStatus(busy)).isEqualTo(override);
        }
        forceStatus(busy, "occupied");
        // Another org never sees the table.
        assertThat(TableStatus.recompute(jdbc, otherOrg, busy)).isNull();
    }
}
