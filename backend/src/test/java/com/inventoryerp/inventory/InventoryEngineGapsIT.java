package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps of the stock engine and the stock documents ({@code docs/audits/readiness-gaps/gA-inventory-engine.md},
 * audit h-00 F1–F11, h-02 F8, h-06 F7): serial units received without serials, money-banded approvals and the
 * write-off pair, posting rules that resolve no account, the transit-receive key, negative receipt costs, the
 * adjustment / count / cancel races, transit-reversal and transfer lots, document lists and transfer scope, and the
 * key of a failed operation that committed nothing.
 */
class InventoryEngineGapsIT extends ApiTestSupport {

    private static final String KEY_REQUIRED = "An Idempotency-Key header is required for this operation so a retry can"
            + " never charge twice.";

    @Autowired
    DataSource dataSource;

    String adminId;
    String category;
    String uom;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ fixtures

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "G-" + suffix(), "name", "Gap " + suffix(),
                "type", type)).get("id").asString();
    }

    String product(String costing, boolean batch, boolean serial, String costPrice) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"categoryId\", \"uomId\", \"batchTracking\", \"serialTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "G-" + suffix(), "Gap item " + suffix(), costing, new BigDecimal(costPrice), category,
                        uom, batch, serial)
                .update();
        return id;
    }

    String name(String productId) {
        return jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(productId).query(String.class).single();
    }

    static Map<String, Object> line(String productId, Object qty, Object unitCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        return m;
    }

    static Map<String, Object> lot(String productId, Object qty, Object unitCost, String batchNumber, String expiry) {
        Map<String, Object> m = line(productId, qty, unitCost);
        m.put("batchNumber", batchNumber);
        if (expiry != null) {
            m.put("expiryDate", expiry);
        }
        return m;
    }

    Map<String, Object> direct(String loc, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationId", loc);
        body.put("responsibleById", adminId);
        body.put("approvedById", adminId);
        body.put("items", items);
        return body;
    }

    void stockIn(String loc, List<Map<String, Object>> items) {
        ok(200, "POST", "/inventory/direct-stock/in", direct(loc, items));
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    BigDecimal lotAt(String productId, String loc, String batchNumber) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ?"
                + " AND \"locationId\" = ? AND \"batchNumber\" = ?").params(productId, loc, batchNumber)
                .query(BigDecimal.class).single();
    }

    BigDecimal lotInTransit(String productId, String batchNumber) {
        return jdbc.sql("SELECT COALESCE(sum(b.quantity), 0) FROM \"InventoryBatch\" b JOIN \"InventoryLocation\" l"
                + " ON l.id = b.\"locationId\" WHERE b.\"productId\" = ? AND b.\"batchNumber\" = ? AND l.type = 'transit'")
                .params(productId, batchNumber).query(BigDecimal.class).single();
    }

    Map<String, String> keyed(String key) {
        return Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", key);
    }

    JsonNode transfer(String from, String to, String mode, Map<String, Object> item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromLocationId", from);
        body.put("toLocationId", to);
        body.put("mode", mode);
        body.put("responsibleById", adminId);
        body.put("approvedById", adminId);
        body.put("items", List.of(item));
        return ok(200, "POST", "/inventory/transfers", body);
    }

    /** A transit transfer of {@code qty}, approved and dispatched. */
    JsonNode dispatched(String from, String to, String productId, int qty) {
        String id = transfer(from, to, "transit", Map.of("productId", productId, "qtyRequested", qty)).get("id")
                .asString();
        ok(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        return ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
    }

    String roleWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Gap role " + suffix(), permissions).update();
        return role;
    }

    /** A new user of the demo organisation with {@code roleId}; returns {@code [userId, token]}. */
    String[] user(String roleId) {
        String email = "gap-" + suffix() + "@demo.test";
        String id = createUser(demoOrg, email, "Gap@12345", roleId);
        return new String[] {id, login("DEMO", email, "Gap@12345")};
    }

    String workflow(String entityType, int minAmount) {
        return ok(201, "POST", "/approval-workflows", Map.of("name", "Gap " + entityType + " " + suffix(), "entityType",
                entityType, "minAmount", minAmount, "steps", List.of(Map.of("stepOrder", 1, "name", "Controller",
                        "approverPermissions", List.of("approvals:decide"), "requiredCount", 1)))).get("id").asString();
    }

    static String requestId(Res held) {
        String message = held.body().get("message").asString();
        String marker = "Pending approval request ";
        return message.substring(message.indexOf(marker) + marker.length(), message.length() - 1);
    }

    BigDecimal snapshot(String requestId, String field) {
        return jdbc.sql("SELECT (snapshot->>?)::numeric FROM \"ApprovalRequest\" WHERE id = ?").params(field, requestId)
                .query(BigDecimal.class).single();
    }

    BigDecimal tieOutVariance() {
        return new BigDecimal(ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null).get("variance")
                .asString());
    }

    String quantKey(String productId, String loc) {
        return "avco:" + demoOrg + ":" + productId + "::" + loc;
    }

    static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    /** Wait until another backend waits on a lock while running a statement matching {@code pattern}. */
    void awaitLockWait(CompletableFuture<Res> request, String pattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (request.isDone()) {
                Res r = request.join();
                throw new AssertionError("the request finished without waiting: " + r.status() + " " + r.raw().body());
            }
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND pid <> pg_backend_pid() AND query ~ ?").param(pattern).query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no request waited on a lock while running " + pattern);
    }

    static JsonNode byId(JsonNode list, String id) {
        for (JsonNode row : list) {
            if (id.equals(row.get("id").asString())) {
                return row;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ h-00 F1

    @Test
    @DisplayName("F1: a serial item received without serials leaves at its receipt cost, so the GL tie-out holds")
    void serialUnitsWithoutSerialsLeaveAtTheirCost() {
        String loc = location("warehouse");
        String p = product("SPECIFIC", false, true, "90");
        BigDecimal before = tieOutVariance();
        stockIn(loc, List.of(line(p, 1, 100)));
        JsonNode out = ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 1, null))));
        assertThat(out.get("items").get(0).get("unitCost").asString()).isEqualTo("100");
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        assertThat(tieOutVariance().subtract(before)).as("stock GL left behind by the issue")
                .isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));

        // Two receipts without serials average; the issue takes the average, not the cost price.
        stockIn(loc, List.of(line(p, 1, 100)));
        stockIn(loc, List.of(line(p, 1, 120)));
        JsonNode both = ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 2, null))));
        assertThat(new BigDecimal(both.get("items").get(0).get("unitCost").asString())).isEqualByComparingTo("110");
        assertThat(tieOutVariance().subtract(before)).isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));
    }

    // ------------------------------------------------------------------ h-00 F2

    @Test
    @DisplayName("F2: stock-outs, adjustments and transfers reach amount-banded approvals by value")
    void stockDocumentsReachAmountBandedApprovals() {
        String loc = location("warehouse");
        String dest = location("branch");
        String out = product("AVCO", false, false, "1");
        String adj = product("AVCO", false, false, "1");
        String moved = product("AVCO", false, false, "1");
        stockIn(loc, List.of(line(out, 10, 10), line(adj, 10, 10), line(moved, 10, 10)));
        String checker = user(roleWith("inventory_doc:read", "approvals:read", "approvals:decide"))[1];
        List<String> workflows = new ArrayList<>();
        try {
            for (String type : List.of("stock_out", "stock_adjustment", "stock_transfer")) {
                workflows.add(workflow(type, 50));
            }
            // stock-out: 6 at 10 = 60 >= 50 (it carried 0 until posted, so no band ever matched)
            JsonNode so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", loc, "responsibleById", adminId,
                    "approvedById", adminId, "items", List.of(Map.of("productId", out, "qty", 6))));
            assertThat(new BigDecimal(so.get("totalValue").asString())).isEqualByComparingTo("60");
            String soPath = "/inventory/stock-outs/" + so.get("id").asString() + "/approve";
            String key = UUID.randomUUID().toString();
            Res held = call("POST", soPath, null, keyed(key));
            assertThat(held.status()).as(held.raw().body()).isEqualTo(400);
            assertThat(held.body().get("message").asString())
                    .startsWith("Approval required before posting stock-out. Pending approval request ");
            String request = requestId(held);
            assertThat(snapshot(request, "amount")).isEqualByComparingTo("60");
            assertThat(onHand(out, loc)).isEqualByComparingTo("10");
            Res decided = call("POST", "/approvals/" + request + "/decide", Map.of("status", "approved"), checker);
            assertThat(decided.status()).as(decided.raw().body()).isLessThan(300);
            // h-06 F7: the held attempt committed nothing of its own, so its key runs again once approved (was 409).
            Res posted = call("POST", soPath, null, keyed(key));
            assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
            assertThat(posted.body().get("status").asString()).isEqualTo("completed");
            assertThat(onHand(out, loc)).isEqualByComparingTo("4");

            // adjustment: 6 units short at 10 = 60 (the band compared 6 units)
            JsonNode a = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById", adminId,
                    "approvedById", adminId, "items", List.of(Map.of("productId", adj, "qtyActual", 4))));
            Res heldAdj = as("POST", "/inventory/adjustments/" + a.get("id").asString() + "/approve", Map.of());
            assertThat(heldAdj.status()).as(heldAdj.raw().body()).isEqualTo(400);
            assertThat(heldAdj.body().get("message").asString()).startsWith("Approval required before posting adjustment ");
            String adjRequest = requestId(heldAdj);
            assertThat(snapshot(adjRequest, "amount")).isEqualByComparingTo("60");
            assertThat(snapshot(adjRequest, "quantity")).isEqualByComparingTo("6");
            assertThat(onHand(adj, loc)).isEqualByComparingTo("10");

            // transfer: 6 at the source's 10 = 60 (the band compared 6 units)
            JsonNode t = transfer(loc, dest, "immediate", Map.of("productId", moved, "qtyRequested", 6));
            Res heldTrf = as("POST", "/inventory/transfers/" + t.get("id").asString() + "/approve", null);
            assertThat(heldTrf.status()).as(heldTrf.raw().body()).isEqualTo(400);
            assertThat(heldTrf.body().get("message").asString()).startsWith("Approval required before posting transfer ");
            String trfRequest = requestId(heldTrf);
            assertThat(snapshot(trfRequest, "amount")).isEqualByComparingTo("60");
            assertThat(snapshot(trfRequest, "quantity")).isEqualByComparingTo("6");
            assertThat(onHand(moved, dest)).isEqualByComparingTo("0");
        } finally {
            workflows.forEach(w -> ok(200, "DELETE", "/approval-workflows/" + w, null));
        }
    }

    @Test
    @DisplayName("F2: with the stock write-off pair on, whoever made a stock-out or waste record cannot post it")
    void writeOffPairKeepsTheMakerFromPosting() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false, "1");
        stockIn(loc, List.of(line(p, 10, 2)));
        String adminRole = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String poster = user(adminRole)[1];
        String rule = "/approval-controls/sod-rules/" + SegregationOfDutiesService.STOCK_WRITE_OFF;
        ok(200, "PUT", rule, Map.of("isActive", true));
        try {
            JsonNode so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", loc, "responsibleById", adminId,
                    "approvedById", adminId, "items", List.of(Map.of("productId", p, "qty", 1))));
            String soPath = "/inventory/stock-outs/" + so.get("id").asString() + "/approve";
            Res own = as("POST", soPath, null);
            assertThat(own.status()).as(own.raw().body()).isEqualTo(403);
            assertThat(own.body().get("message").asString()).isEqualTo("Separation of duties: the person who made this"
                    + " document cannot also clear it (Stock write-off)");
            assertThat(onHand(p, loc)).isEqualByComparingTo("10");
            Res other = call("POST", soPath, null, poster);
            assertThat(other.status()).as(other.raw().body()).isEqualTo(200);

            JsonNode waste = ok(200, "POST", "/inventory/waste", Map.of("locationId", loc, "responsibleById", adminId,
                    "approvedById", adminId, "category", "spoiled", "items", List.of(Map.of("productId", p, "qty", 2))));
            String wastePath = "/inventory/waste/" + waste.get("id").asString() + "/approve";
            assertThat(as("POST", wastePath, null).status()).isEqualTo(403);
            Res otherWaste = call("POST", wastePath, null, poster);
            assertThat(otherWaste.status()).as(otherWaste.raw().body()).isEqualTo(200);
            assertThat(onHand(p, loc)).isEqualByComparingTo("7");
        } finally {
            ok(200, "PUT", rule, Map.of("isActive", false));
        }
    }

    // ------------------------------------------------------------------ h-00 F3 / h-02 F8

    @Test
    @DisplayName("F3: a posting rule that resolves no account is refused when saved and fails the move loudly")
    void postingRulesMustResolveAnAccount() {
        ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        String loc = location("warehouse");
        String p = product("AVCO", false, false, "1");
        Map<String, Object> base = new LinkedHashMap<>(Map.of("movementType", "STOCK_OUT", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "account_mapping", "productId", p));
        Res noKey = as("POST", "/inventory/posting-rules", base);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString())
                .isEqualTo("accountMappingKey is required for a account_mapping posting rule");
        String unmapped = "nowhere_" + suffix();
        Map<String, Object> mapping = new LinkedHashMap<>(base);
        mapping.put("accountMappingKey", unmapped);
        Res noMapping = as("POST", "/inventory/posting-rules", mapping);
        assertThat(noMapping.status()).isEqualTo(400);
        assertThat(noMapping.body().get("message").asString()).isEqualTo("Account mapping '" + unmapped
                + "' is not configured; map it under Accounting > Account Mapping first");
        Map<String, Object> field = new LinkedHashMap<>(mapping);
        field.put("accountSource", "category_field");
        Res noField = as("POST", "/inventory/posting-rules", field);
        assertThat(noField.status()).isEqualTo(400);
        assertThat(noField.body().get("message").asString()).isEqualTo("Posting rule key '" + unmapped + "' resolves no"
                + " account for the products it covers; set that account on the product or category, or map '"
                + unmapped + "' under Accounting > Account Mapping");

        Map<String, Object> good = new LinkedHashMap<>(base);
        good.put("accountMappingKey", "default_expense");
        String id = ok(201, "POST", "/inventory/posting-rules", good).get("id").asString();
        try {
            assertThat(as("PATCH", "/inventory/posting-rules/" + id, Map.of("accountMappingKey", unmapped)).status())
                    .isEqualTo(400);
            // An inactive rule resolves nothing, so it may hold anything.
            ok(200, "PATCH", "/inventory/posting-rules/" + id, Map.of("isActive", false, "accountMappingKey", unmapped));
            assertThat(as("PATCH", "/inventory/posting-rules/" + id, Map.of("isActive", true)).status()).isEqualTo(400);
        } finally {
            as("DELETE", "/inventory/posting-rules/" + id, null);
        }

        // Rules saved before the check (or written directly) fail the move with a hint instead of skipping the journal.
        stockIn(loc, List.of(line(p, 2, 5)));
        String debit = UUID.randomUUID().toString();
        String credit = UUID.randomUUID().toString();
        String sql = "INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                + " \"debitOrCredit\", \"accountSource\", \"accountMappingKey\", \"productId\", \"createdAt\", \"updatedAt\")"
                + " VALUES (?, ?, 'STOCK_OUT', ?, ?, ?, NULL, ?, now(), now())";
        jdbc.sql(sql).params(debit, demoOrg, 0, "debit", "category_field", p).update();
        jdbc.sql(sql).params(credit, demoOrg, 1, "credit", "product_field", p).update();
        try {
            Res out = as("POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 1, null))));
            assertThat(out.status()).as(out.raw().body()).isEqualTo(400);
            assertThat(out.body().get("message").asString()).isEqualTo("The STOCK_OUT posting rule (debit line 0)"
                    + " resolves no GL account for " + name(p) + "; fix it under Accounting > Posting Rules");
            assertThat(onHand(p, loc)).isEqualByComparingTo("2");
        } finally {
            jdbc.sql("DELETE FROM \"InventoryPostingRule\" WHERE id IN (?, ?)").params(debit, credit).update();
        }
    }

    // ------------------------------------------------------------------ h-00 F4

    @Test
    @DisplayName("F4: a transit receive needs an Idempotency-Key and a retry with it books the goods once")
    void transitReceiveIsIdempotent() {
        String from = location("warehouse");
        String to = location("branch");
        String p = product("AVCO", false, false, "1");
        stockIn(from, List.of(line(p, 10, 3)));
        JsonNode trf = dispatched(from, to, p, 6);
        String id = trf.get("id").asString();
        String path = "/inventory/transfers/" + id + "/receive";
        Map<String, Object> body = Map.of("lines", List.of(Map.of("itemId", trf.get("items").get(0).get("id")
                .asString(), "received", 2)));
        Res noKey = call("POST", path, body, Map.of("Authorization", "Bearer " + adminToken, NO_IDEMPOTENCY_KEY, "1"));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString()).isEqualTo(KEY_REQUIRED);
        assertThat(onHand(p, to)).isEqualByComparingTo("0");

        String key = UUID.randomUUID().toString();
        Res first = call("POST", path, body, keyed(key));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res retry = call("POST", path, body, keyed(key));
        assertThat(retry.status()).as(retry.raw().body()).isEqualTo(200);
        assertThat(retry.body().get("id").asString()).isEqualTo(id);
        assertThat(retry.body().get("status").asString()).isEqualTo(first.body().get("status").asString());
        assertThat(onHand(p, to)).isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockTransferReceipt\" WHERE \"transferId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ h-00 F5

    @Test
    @DisplayName("F5: direct stock-in refuses a negative unit cost; zero stays allowed")
    void negativeUnitCostRefused() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false, "1");
        Res negative = as("POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 3, -5))));
        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.body().get("message").asString())
                .isEqualTo("Unit cost of \"" + name(p) + "\" cannot be negative; enter 0 or more");
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        stockIn(loc, List.of(line(p, 3, 0)));
        assertThat(onHand(p, loc)).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------ h-00 F6

    @Test
    @DisplayName("F6: an adjustment keeps a sale that commits after its drift check (it was overwritten)")
    void adjustmentKeepsAMovementAfterTheDriftCheck() throws Exception {
        String loc = location("warehouse");
        String x = product("AVCO", false, false, "1");
        String y = product("AVCO", false, false, "1");
        String first = x.compareTo(y) < 0 ? x : y;
        String second = first.equals(x) ? y : x;
        stockIn(loc, List.of(line(first, 10, 1), line(second, 10, 1)));
        JsonNode adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", first, "qtyActual", 9),
                        Map.of("productId", second, "qtyActual", 8))));
        String path = "/inventory/adjustments/" + adj.get("id").asString() + "/approve";
        CompletableFuture<Res> approve;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                // Hold the first line's quant: the approve passes its drift check, then waits here.
                exec(holder, "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", quantKey(first, loc));
                approve = CompletableFuture.supplyAsync(() -> as("POST", path, Map.of()));
                awaitLockWait(approve, "pg_advisory_xact_lock");
                ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(line(second, 1, null))));
            } finally {
                holder.rollback();
            }
        }
        Res done = approve.get(30, TimeUnit.SECONDS);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(onHand(first, loc)).isEqualByComparingTo("9");
        // counted 8 of 10, then 1 sold: 7 (the absolute count wrote 8 back)
        assertThat(onHand(second, loc)).isEqualByComparingTo("7");
    }

    // ------------------------------------------------------------------ h-00 F7

    @Test
    @DisplayName("F7: a count submit reads one state of its quants; a sale committing meanwhile is not erased")
    void countSubmitSeesOneStateOfItsQuants() throws Exception {
        String loc = location("warehouse");
        String p = product("AVCO", false, false, "1");
        stockIn(loc, List.of(line(p, 10, 1)));
        JsonNode session = ok(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "scopeProductIds", List.of(p)));
        String id = session.get("id").asString();
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId",
                session.get("lines").get(0).get("id").asString(), "countedQty", 10, "reason", "recount"))));
        CompletableFuture<Res> submit;
        try (Connection sale = dataSource.getConnection()) {
            sale.setAutoCommit(false);
            boolean committed = false;
            try {
                // A sale after the count, holding the quant (as the engine does) and the quant table, so the submit
                // waits either for the quant lock or between its ledger read and its on-hand read.
                exec(sale, "SET LOCAL lock_timeout = '10s'");
                exec(sale, "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", quantKey(p, loc));
                exec(sale, "LOCK TABLE \"StockItem\" IN ACCESS EXCLUSIVE MODE");
                exec(sale, "INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\","
                        + " \"locationId\", type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\", \"referenceType\","
                        + " \"referenceId\", \"createdAt\") VALUES (?, ?, ?, ?, ?, 'issue', 10, -1, 9, 'gap_test_sale', ?,"
                        + " date_trunc('milliseconds', clock_timestamp())::timestamp(3))",
                        UUID.randomUUID().toString(), demoOrg, "GAP/" + suffix(), p, loc, "SALE-" + suffix());
                exec(sale, "UPDATE \"StockItem\" SET quantity = quantity - 1 WHERE \"productId\" = ? AND \"locationId\" = ?",
                        p, loc);
                submit = CompletableFuture.supplyAsync(() -> as("POST", "/inventory/counts/" + id + "/submit", Map.of()));
                awaitLockWait(submit, "pg_advisory_xact_lock|runningAverageCost");
                sale.commit();
                committed = true;
            } finally {
                if (!committed) {
                    sale.rollback();
                }
            }
        }
        Res done = submit.get(30, TimeUnit.SECONDS);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        // counted 10 before the sale: no variance, the sale stands (the split reads posted +1 back)
        assertThat(onHand(p, loc)).isEqualByComparingTo("9");
    }

    // ------------------------------------------------------------------ h-00 F8

    @Test
    @DisplayName("F8: a cancel racing a submit cannot mark the submitted count cancelled")
    void cancelDoesNotOverwriteASubmittedCount() throws Exception {
        String loc = location("warehouse");
        String p = product("AVCO", false, false, "1");
        stockIn(loc, List.of(line(p, 2, 1)));
        String id = ok(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "scopeProductIds", List.of(p))).get("id").asString();
        CompletableFuture<Res> cancel;
        try (Connection submitter = dataSource.getConnection()) {
            submitter.setAutoCommit(false);
            boolean committed = false;
            try {
                exec(submitter, "SET LOCAL lock_timeout = '10s'");
                exec(submitter, "UPDATE \"InventoryCountSession\" SET status = 'submitted' WHERE id = ?", id);
                cancel = CompletableFuture.supplyAsync(() -> as("POST", "/inventory/counts/" + id + "/cancel", null));
                awaitLockWait(cancel, "InventoryCountSession");
                submitter.commit();
                committed = true;
            } finally {
                if (!committed) {
                    submitter.rollback();
                }
            }
        }
        Res r = cancel.get(30, TimeUnit.SECONDS);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
        assertThat(r.body().get("message").asString())
                .isEqualTo("Count was already submitted or cancelled by another request");
        assertThat(jdbc.sql("SELECT status::text FROM \"InventoryCountSession\" WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo("submitted");
    }

    // ------------------------------------------------------------------ h-00 F9

    @Test
    @DisplayName("F9: reversing a transit transfer moves back the lots it restored, not another transfer's")
    void transitReversalMovesBackItsOwnLots() {
        String src = location("warehouse");
        String dst = location("branch");
        String p = product("AVCO", true, false, "1");
        String l0 = "L0-" + suffix();
        String l1 = "L1-" + suffix();
        stockIn(src, List.of(lot(p, 5, 2, l0, "2098-01-31")));
        stockIn(src, List.of(lot(p, 5, 3, l1, "2099-12-31")));
        JsonNode t0 = dispatched(src, dst, p, 5);
        JsonNode t1 = dispatched(src, dst, p, 5);
        assertThat(lotInTransit(p, l0)).isEqualByComparingTo("5");
        assertThat(lotInTransit(p, l1)).isEqualByComparingTo("5");
        String t1Id = t1.get("id").asString();
        JsonNode received = ok(200, "POST", "/inventory/transfers/" + t1Id + "/receive", Map.of("lines", List.of(
                Map.of("itemId", t1.get("items").get(0).get("id").asString(), "received", 3, "damaged", 2)),
                "lossReason", "broken seal", "approvedById", adminId));
        assertThat(received.get("status").asString()).isEqualTo("completed");

        JsonNode reversed = ok(200, "POST", "/inventory/transfers/" + t1Id + "/reverse", Map.of("reason", "wrong lot"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(lotInTransit(p, l0)).as("the other transfer's lot stays in transit").isEqualByComparingTo("5");
        assertThat(lotInTransit(p, l1)).isEqualByComparingTo("0");
        assertThat(lotAt(p, src, l1)).isEqualByComparingTo("5");
        assertThat(lotAt(p, src, l0)).isEqualByComparingTo("0");

        JsonNode t0Done = ok(200, "POST", "/inventory/transfers/" + t0.get("id").asString() + "/receive", Map.of("lines",
                List.of(Map.of("itemId", t0.get("items").get(0).get("id").asString(), "received", 5))));
        assertThat(t0Done.get("status").asString()).isEqualTo("completed");
        assertThat(lotAt(p, dst, l0)).isEqualByComparingTo("5");
    }

    // ------------------------------------------------------------------ h-00 F10

    @Test
    @DisplayName("F10: a transfer line that names a lot moves that lot, immediate and in transit")
    void transferLineMovesTheLotItNames() {
        String src = location("warehouse");
        String dst = location("bar");
        String p = product("AVCO", true, false, "1");
        String l0 = "L0-" + suffix();
        String l1 = "L1-" + suffix();
        stockIn(src, List.of(lot(p, 5, 2, l0, "2098-01-31")));
        stockIn(src, List.of(lot(p, 5, 3, l1, "2099-12-31")));
        JsonNode now = transfer(src, dst, "immediate", Map.of("productId", p, "qtyRequested", 3, "batchNumber", l1));
        ok(200, "POST", "/inventory/transfers/" + now.get("id").asString() + "/approve", null);
        assertThat(lotAt(p, dst, l1)).isEqualByComparingTo("3");
        assertThat(lotAt(p, src, l0)).isEqualByComparingTo("5");

        JsonNode later = transfer(src, dst, "transit", Map.of("productId", p, "qtyRequested", 2, "batchNumber", l1));
        String id = later.get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(lotInTransit(p, l1)).isEqualByComparingTo("2");
        assertThat(lotAt(p, src, l1)).isEqualByComparingTo("0");
        assertThat(lotAt(p, src, l0)).isEqualByComparingTo("5");
    }

    // ------------------------------------------------------------------ h-00 F11 + transfer scope

    @Test
    @DisplayName("F11: document lists keep their items, locations and receipts; a scoped user reaches transfers by an end")
    void listsKeepTheirShapeAndTransfersHonourScope() {
        String inside = location("warehouse");
        String other = location("branch");
        String far = location("bar");
        String p = product("AVCO", false, false, "1");
        stockIn(inside, List.of(line(p, 10, 2)));
        stockIn(far, List.of(line(p, 10, 2)));
        String reach = transfer(inside, other, "immediate", Map.of("productId", p, "qtyRequested", 1)).get("id")
                .asString();
        String away = transfer(far, other, "immediate", Map.of("productId", p, "qtyRequested", 1)).get("id").asString();
        String waste = ok(200, "POST", "/inventory/waste", Map.of("locationId", inside, "responsibleById", adminId,
                "approvedById", adminId, "category", "spoiled", "items", List.of(Map.of("productId", p, "qty", 1))))
                .get("id").asString();
        String adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", inside, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", p, "qtyActual", 9)))).get("id").asString();

        JsonNode t = byId(ok(200, "GET", "/inventory/transfers", null), reach);
        assertThat(t).isNotNull();
        assertThat(t.get("items")).hasSize(1);
        assertThat(t.get("receipts").isArray()).isTrue();
        JsonNode w = byId(ok(200, "GET", "/inventory/waste", null), waste);
        assertThat(w).isNotNull();
        assertThat(w.get("items")).hasSize(1);
        assertThat(w.get("location").get("id").asString()).isEqualTo(inside);
        JsonNode a = byId(ok(200, "GET", "/inventory/adjustments", null), adj);
        assertThat(a).isNotNull();
        assertThat(a.get("items")).hasSize(1);
        assertThat(a.get("location").get("id").asString()).isEqualTo(inside);
        assertThat(a.get("location").get("type").asString()).isEqualTo("warehouse");

        String[] scoped = user(roleWith("inventory_doc:read"));
        ok(200, "PUT", "/user-data-scopes/" + scoped[0], Map.of("branchIds", List.of(), "locationIds", List.of(inside)));
        String token = login("DEMO", jdbc.sql("SELECT email FROM \"User\" WHERE id = ?").param(scoped[0])
                .query(String.class).single(), "Gap@12345");
        assertThat(call("GET", "/inventory/transfers/" + reach, null, token).status()).isEqualTo(200);
        assertThat(call("GET", "/inventory/transfers/" + away, null, token).status()).isEqualTo(403);
        Res list = call("GET", "/inventory/transfers", null, token);
        assertThat(list.status()).isEqualTo(200);
        assertThat(byId(list.body(), reach)).isNotNull();
        assertThat(byId(list.body(), away)).isNull();
    }

    // ------------------------------------------------------------------ h-06 F7

    @Test
    @DisplayName("h-06 F7: a keyed operation that failed and committed nothing runs again on a retry with its key")
    void failedOperationReleasesItsKey() {
        String loc = location("warehouse");
        String p = product("AVCO", true, false, "1");
        jdbc.sql("UPDATE \"Product\" SET \"stockPolicy\" = 'block' WHERE id = ?").param(p).update();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = direct(loc, List.of(line(p, 1, null)));
        Res refused = call("POST", "/inventory/direct-stock/out", body, keyed(key));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Insufficient stock for ");
        assertThat(jdbc.sql("SELECT count(*) FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(Long.class).single()).isZero();

        stockIn(loc, List.of(lot(p, 5, 2, "B1-" + suffix(), null)));
        Res retried = call("POST", "/inventory/direct-stock/out", body, keyed(key));
        assertThat(retried.status()).as(retried.raw().body()).isEqualTo(200);
        assertThat(onHand(p, loc)).isEqualByComparingTo("4");
        Res replayed = call("POST", "/inventory/direct-stock/out", body, keyed(key));
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(onHand(p, loc)).isEqualByComparingTo("4");
    }
}
