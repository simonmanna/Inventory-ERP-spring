package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.procurement.application.ProcurementReplenishmentService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps 2026-11-17, stream gB (audit h-01 F2–F12 and minor items, h-04 F10): sourced pick orders and the
 * ledger, manual-ship controls, idempotent warehouse routes, replenishment branch scope and scan isolation, duplicate
 * event deliveries, scoped stock levels, per-store reorder supply, lot validation and report fields.
 */
class WarehouseReplenishmentGapsIT extends ApiTestSupport {

    @Autowired
    ReplenishmentService replenishment;

    @Autowired
    ProcurementReplenishmentService procurementReplenishment;

    record User(String id, String token) {}

    String adminId;
    String adminRole;
    String category;
    String uom;
    /** Second and third DEMO administrators. */
    User approver;
    User checker;
    /** May pick and ship (warehouse:operate) but not approve stock-outs. */
    User operator;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        adminRole = jdbc.sql("SELECT \"A\" FROM \"_UserRoles\" WHERE \"B\" = ? LIMIT 1").param(adminId)
                .query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'").param(demoOrg)
                .query(String.class).optional().orElse(null);
        approver = user(adminRole, null);
        checker = user(adminRole, null);
        operator = user(role("warehouse:read", "warehouse:operate"), null);
    }

    // ------------------------------------------------------------------ fixtures

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String role(String... permissions) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(id, demoOrg, "Gaps gB " + suffix(), permissions).update();
        return id;
    }

    /** A DEMO user with the role, limited to {@code onlyLocation} when given (set before the first login). */
    User user(String roleId, String onlyLocation) {
        String email = "gb-" + suffix() + "@demo.test";
        String id = createUser(demoOrg, email, "GapsGb@12345", roleId);
        if (onlyLocation != null) {
            ok(200, "PUT", "/user-data-scopes/" + id, Map.of("branchIds", List.of(), "locationIds",
                    List.of(onlyLocation)));
        }
        return new User(id, login("DEMO", email, "GapsGb@12345"));
    }

    JsonNode okAs(User u, int status, String method, String path, Object body) {
        Res r = call(method, path, body, u.token());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String location(String branchId) {
        Map<String, Object> body = new HashMap<>(Map.of("code", "GB-" + suffix(), "name", "Store " + suffix(), "type",
                "warehouse"));
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        return ok(201, "POST", "/inventory/locations", body).get("id").asString();
    }

    String branch() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, demoOrg, "GB-" + suffix(), "Branch " + suffix()).update();
        return id;
    }

    String product(String name, BigDecimal minQuantity) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"categoryId\", \"uomId\", \"minQuantity\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', 'AVCO'::\"CostingMethod\", 5, ?, ?, ?, now())")
                .params(id, demoOrg, "GB-" + suffix(), name + " " + suffix(), category, uom,
                        minQuantity == null ? BigDecimal.ZERO : minQuantity).update();
        return id;
    }

    String productName(String id) {
        return jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(id).query(String.class).single();
    }

    void stockIn(String product, String location, int qty) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", qty, "unitCost", 5))));
    }

    void directStockOut(String product, String location, int qty) {
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", qty))));
    }

    /** A pending stock-out document (its approve posts the issue). */
    JsonNode stockOutDoc(String product, String location, int qty) {
        return ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", product, "qty",
                        qty))));
    }

    void post(JsonNode stockOut) {
        ok(200, "POST", "/inventory/stock-outs/" + stockOut.get("id").asString() + "/approve", null);
    }

    String bin(String location, String code) {
        return ok(201, "POST", "/warehouse/bins", Map.of("locationId", location, "code", code, "pickSequence", 1))
                .get("id").asString();
    }

    void putaway(String location, String product, int qty, String binId) {
        JsonNode t = ok(201, "POST", "/warehouse/tasks", Map.of("locationId", location, "type", "putaway", "productId",
                product, "quantity", qty, "toBinId", binId));
        ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of());
    }

    /** A location with one bin holding {@code qty} of a fresh product: {@code [location, bin, product]}. */
    String[] binnedStock(String branchId, int qty) {
        String loc = location(branchId);
        String bin = bin(loc, "B1");
        String product = product("Valve", null);
        stockIn(product, loc, qty);
        putaway(loc, product, qty, bin);
        return new String[] {loc, bin, product};
    }

    JsonNode pickOrder(String token, Map<String, Object> body) {
        Res r = call("POST", "/warehouse/pick-orders", body, token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    /** Release and complete every pick task (as the administrator). */
    void pick(String orderId) {
        for (JsonNode t : ok(200, "POST", "/warehouse/pick-orders/" + orderId + "/release", null).get("tasks")) {
            ok(200, "POST", "/warehouse/tasks/" + t.get("id").asString() + "/complete", Map.of());
        }
    }

    Res ship(String orderId, String token) {
        return call("POST", "/warehouse/pick-orders/" + orderId + "/ship", Map.of(), token);
    }

    String status(String orderId) {
        return ok(200, "GET", "/warehouse/pick-orders/" + orderId, null).get("status").asString();
    }

    BigDecimal onHand(String product, String location) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, location).query(BigDecimal.class).single();
    }

    BigDecimal binQty(String binId, String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?"
                + " AND \"productId\" = ?").params(binId, productId).query(BigDecimal.class).single();
    }

    JsonNode overviewRow(String location, String productId) {
        for (JsonNode p : ok(200, "GET", "/warehouse/locations/" + location + "/overview", null).get("products")) {
            if (productId.equals(p.get("productId").asString())) {
                return p;
            }
        }
        throw new AssertionError("product not in overview");
    }

    static BigDecimal dec(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString());
    }

    // ------------------------------------------------------------------ h01-F2 sourced pick orders

    @Test
    @DisplayName("h01-F2: a document posted before the pick keeps its goods binned: overview, reconcile and release agree")
    void documentPostedFirst() {
        String[] s = binnedStock(null, 10);
        String loc = s[0];
        String bin = s[1];
        String bolts = s[2];
        JsonNode doc = stockOutDoc(bolts, loc, 8);
        post(doc);
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("2");
        JsonNode po = pickOrder(adminToken, Map.of("locationId", loc, "steps", 2, "sourceType", "stock_out",
                "sourceRef", doc.get("outCode").asString(), "lines", List.of(Map.of("productId", bolts, "quantity", 8))));
        String id = po.get("id").asString();

        // the 8 issued goods wait on the shelf for their pick order: no drift and nothing to reconcile
        JsonNode row = overviewRow(loc, bolts);
        assertThat(dec(row, "awaitingShipQty")).isEqualByComparingTo("8");
        assertThat(row.get("mismatch").asBoolean()).isFalse();
        assertThat(dec(row, "unbinnedQty")).isEqualByComparingTo("0");
        assertThat(ok(200, "POST", "/warehouse/locations/" + loc + "/reconcile", Map.of()).get("reconciled").asInt())
                .isZero();
        assertThat(binQty(bin, bolts)).isEqualByComparingTo("10");

        // release is not capped at the ledger's 2
        pick(id);
        assertThat(binQty(bin, bolts)).isEqualByComparingTo("2");
        row = overviewRow(loc, bolts);
        assertThat(dec(row, "stagedQty")).isEqualByComparingTo("8");
        assertThat(dec(row, "unbinnedQty")).isEqualByComparingTo("0");
        assertThat(row.get("mismatch").asBoolean()).isFalse();
        assertThat(ship(id, adminToken).status()).isEqualTo(200);
        assertThat(onHand(bolts, loc)).isEqualByComparingTo("2");
        row = overviewRow(loc, bolts);
        assertThat(dec(row, "stagedQty")).isEqualByComparingTo("0");
        assertThat(dec(row, "awaitingShipQty")).isEqualByComparingTo("0");
        assertThat(dec(row, "unbinnedQty")).isEqualByComparingTo("0");
        assertThat(row.get("mismatch").asBoolean()).isFalse();

        // stock that left without any pick order: the refusal no longer sends the user to reconcile good stock away
        directStockOut(bolts, loc, 1);
        JsonNode manual = pickOrder(adminToken, Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", bolts, "quantity", 2))));
        Res capped = call("POST", "/warehouse/pick-orders/" + manual.get("id").asString() + "/release", null,
                adminToken);
        assertThat(capped.status()).isEqualTo(400);
        assertThat(capped.body().get("message").asString()).startsWith("Not enough stock on hand to pick")
                .contains("on hand 1").contains("with that document as its source").doesNotContain("reconcile");
    }

    @Test
    @DisplayName("h01-F2: a sourced order ships only after its document took the goods out; the document must exist")
    void shipWaitsForTheDocument() {
        String[] s = binnedStock(null, 10);
        String loc = s[0];
        String valve = s[2];
        JsonNode doc = stockOutDoc(valve, loc, 3);
        String outCode = doc.get("outCode").asString();
        JsonNode po = pickOrder(adminToken, Map.of("locationId", loc, "steps", 1, "sourceType", "stock_out",
                "sourceRef", outCode, "lines", List.of(Map.of("productId", valve, "quantity", 3))));
        String id = po.get("id").asString();
        // a one-step order does not ship itself while its document has not posted
        pick(id);
        assertThat(status(id)).isEqualTo("picked");
        Res early = ship(id, adminToken);
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).isEqualTo("Post stock-out " + outCode
                + " before shipping pick order " + po.get("code").asString() + ": it has not yet taken the picked goods"
                + " out of stock at this location (" + productName(valve) + ": picked 3, taken out 0). Shipping a pick"
                + " order for a document only records the shipment");
        // staged, not "waiting for putaway"
        JsonNode row = overviewRow(loc, valve);
        assertThat(dec(row, "stagedQty")).isEqualByComparingTo("3");
        assertThat(dec(row, "unbinnedQty")).isEqualByComparingTo("0");
        post(doc);
        row = overviewRow(loc, valve);
        assertThat(dec(row, "unbinnedQty")).isEqualByComparingTo("0");
        assertThat(row.get("mismatch").asBoolean()).isFalse();
        assertThat(ship(id, adminToken).status()).isEqualTo(200);
        assertThat(onHand(valve, loc)).isEqualByComparingTo("7");

        // the source document must exist, and a stock-out must leave from this location
        String missing = "SO-NONE-" + suffix();
        Res unknown = as("POST", "/warehouse/pick-orders", Map.of("locationId", loc, "sourceType", "sales_order",
                "sourceRef", missing, "lines", List.of(Map.of("productId", valve, "quantity", 1))));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").asString()).isEqualTo("Sales order " + missing
                + " not found; a pick order for a document needs the number of an existing document");
        JsonNode elsewhere = stockOutDoc(valve, location(null), 1);
        Res wrongPlace = as("POST", "/warehouse/pick-orders", Map.of("locationId", loc, "sourceType", "stock_out",
                "sourceRef", elsewhere.get("outCode").asString(), "lines", List.of(Map.of("productId", valve,
                        "quantity", 1))));
        assertThat(wrongPlace.body().get("message").asString()).isEqualTo("Stock-out "
                + elsewhere.get("outCode").asString() + " takes its stock out of another location; pick it in that"
                + " location");
    }

    // ------------------------------------------------------------------ h01-F3 manual ship controls

    @Test
    @DisplayName("h01-F3: a manual ship needs inventory_doc:approve and someone other than the order's creator")
    void manualShipMakerChecker() {
        String[] s = binnedStock(null, 10);
        String loc = s[0];
        String valve = s[2];
        JsonNode po = pickOrder(operator.token(), Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", valve, "quantity", 3))));
        String id = po.get("id").asString();
        String code = po.get("code").asString();
        pick(id);
        Res denied = ship(id, operator.token());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().get("message").asString()).isEqualTo("Shipping manual pick order " + code
                + " takes its stock out of inventory; only a user who may approve stock-outs (inventory_doc:approve)"
                + " can ship it");
        assertThat(onHand(valve, loc)).isEqualByComparingTo("10");
        // the administrator approves: the operator who asked is responsible, the administrator approved
        assertThat(ship(id, adminToken).status()).isEqualTo(200);
        assertThat(onHand(valve, loc)).isEqualByComparingTo("7");
        Map<String, Object> people = jdbc.sql("SELECT \"responsibleById\", \"approvedById\" FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"referenceType\" = 'WarehousePickOrder' AND \"referenceId\" = ?")
                .params(demoOrg, code).query().singleRow();
        assertThat(people.get("responsibleById")).isEqualTo(operator.id());
        assertThat(people.get("approvedById")).isEqualTo(adminId);

        // whoever created a manual order cannot ship it as well
        JsonNode own = pickOrder(adminToken, Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", valve, "quantity", 2))));
        String ownId = own.get("id").asString();
        pick(ownId);
        Res self = ship(ownId, adminToken);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("message").asString()).isEqualTo("Separation of duties: the person who created pick"
                + " order " + own.get("code").asString() + " cannot also ship it (a manual pick takes the stock out of"
                + " inventory); ask a colleague who may approve stock-outs to ship it");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'SodCheck' AND \"entityId\" = ?"
                + " AND action = 'reject'").param(ownId).query(Long.class).single()).isEqualTo(1);
        assertThat(onHand(valve, loc)).isEqualByComparingTo("7");
        assertThat(ship(ownId, approver.token()).status()).isEqualTo(200);
        assertThat(onHand(valve, loc)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("h01-F3: a manual ship goes through the stock-out approval workflow at its picked value")
    void manualShipApprovalWorkflow() {
        String branchId = branch();
        String[] s = binnedStock(branchId, 10);
        String loc = s[0];
        String valve = s[2];
        Map<String, Object> step = new LinkedHashMap<>(Map.of("stepOrder", 1, "name", "Stock-out approval",
                "approverPermissions", List.of("inventory_doc:approve"), "requiredCount", 1));
        // scoped to this test's branch, so no other suite's stock-out meets it
        String workflow = ok(201, "POST", "/approval-workflows", Map.of("name", "Manual picks " + suffix(),
                "entityType", "stock_out", "branchId", branchId, "steps", List.of(step))).get("id").asString();
        try {
            JsonNode po = pickOrder(adminToken, Map.of("locationId", loc, "steps", 2, "lines",
                    List.of(Map.of("productId", valve, "quantity", 3))));
            String id = po.get("id").asString();
            pick(id);
            Res gated = ship(id, approver.token());
            assertThat(gated.status()).isEqualTo(400);
            assertThat(gated.body().get("message").asString()).startsWith("Approval required before shipping pick order "
                    + po.get("code").asString() + ". Pending approval request ");
            Map<String, Object> request = jdbc.sql("SELECT id, snapshot->>'amount' AS amount FROM \"ApprovalRequest\""
                    + " WHERE \"organizationId\" = ? AND \"entityType\" = 'stock_out' AND \"entityId\" = ?"
                    + " AND status = 'pending'").params(demoOrg, id).query().singleRow();
            assertThat(new BigDecimal((String) request.get("amount"))).isEqualByComparingTo("15");
            assertThat(onHand(valve, loc)).isEqualByComparingTo("10");
            okAs(checker, 201, "POST", "/approvals/" + request.get("id") + "/decide", Map.of("status", "approved"));
            // approved by a colleague in the engine: the creator may now ship it
            assertThat(ship(id, adminToken).status()).isEqualTo(200);
            assertThat(onHand(valve, loc)).isEqualByComparingTo("7");
        } finally {
            as("DELETE", "/approval-workflows/" + workflow, null);
        }
    }

    // ------------------------------------------------------------------ h01-F11 idempotent warehouse routes

    @Test
    @DisplayName("h01-F11: a retried ship with the same Idempotency-Key replays the answer and issues once")
    void shipReplays() {
        String[] s = binnedStock(null, 5);
        String loc = s[0];
        String valve = s[2];
        JsonNode po = pickOrder(adminToken, Map.of("locationId", loc, "steps", 2, "lines",
                List.of(Map.of("productId", valve, "quantity", 2))));
        String id = po.get("id").asString();
        pick(id);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + approver.token(), "Idempotency-Key",
                UUID.randomUUID().toString());
        Res first = call("POST", "/warehouse/pick-orders/" + id + "/ship", Map.of("carrier", "Van"), headers);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res again = call("POST", "/warehouse/pick-orders/" + id + "/ship", Map.of("carrier", "Van"), headers);
        assertThat(again.status()).as(again.raw().body()).isEqualTo(200);
        assertThat(again.body().get("status").asString()).isEqualTo("shipped");
        assertThat(onHand(valve, loc)).isEqualByComparingTo("3");
        // a bin adjustment is refused without a key: a retried delta would apply twice
        Res noKey = call("POST", "/warehouse/bins/" + s[1] + "/adjust", Map.of("productId", valve, "delta", -1,
                "reason", "recount"), adminToken);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString()).isEqualTo("An Idempotency-Key header is required for this"
                + " operation so a retry can never charge twice.");
    }

    // ------------------------------------------------------------------ minor: putaway lot

    @Test
    @DisplayName("h01 minor: a putaway names a lot of the product at the location, or none")
    void putawayLotValidated() {
        String loc = location(null);
        String bin = bin(loc, "L1");
        String p = product("Seal", null);
        stockIn(p, loc, 3);
        String lot = UUID.randomUUID().toString();
        Res r = as("POST", "/warehouse/tasks", Map.of("locationId", loc, "type", "putaway", "productId", p, "quantity", 1,
                "toBinId", bin, "batchId", lot));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Lot " + lot + " is not a lot of this product at this"
                + " location; name one of its lots or leave the lot empty");
    }

    // ------------------------------------------------------------------ h01-F4 replenishment branch scope

    String rule(String product, String location, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("productId", product, "locationId", location, "minQty", 10));
        body.putAll(extra);
        return ok(201, "POST", "/inventory/replenishment/rules", body).get("id").asString();
    }

    JsonNode liveAlerts(String location) {
        return ok(200, "GET", "/inventory/replenishment/alerts?status=live&locationId=" + location, null).get("data");
    }

    @Test
    @DisplayName("h01-F4: a branch-restricted user reads, edits, scans and requests only their locations' replenishment")
    void replenishmentBranchScope() {
        String inside = location(null);
        String outside = location(null);
        String ruleIn = rule(product("Sand", null), inside, Map.of());
        String ruleOut = rule(product("Gravel", null), outside, Map.of());
        User clerk = user(role("inventory:read", "inventory:replenish"), inside);

        JsonNode rules = okAs(clerk, 200, "GET", "/inventory/replenishment/rules", null).get("data");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("id").asString()).isEqualTo(ruleIn);
        assertThat(call("GET", "/inventory/replenishment/rules?locationId=" + outside, null, clerk.token()).status())
                .isEqualTo(403);
        assertThat(call("PATCH", "/inventory/replenishment/rules/" + ruleOut, Map.of("minQty", 5), clerk.token())
                .status()).isEqualTo(403);
        assertThat(call("DELETE", "/inventory/replenishment/rules/" + ruleOut, null, clerk.token()).status())
                .isEqualTo(403);
        assertThat(call("POST", "/inventory/replenishment/rules", Map.of("productId", product("Lime", null),
                "locationId", outside, "minQty", 3), clerk.token()).status()).isEqualTo(403);

        // the clerk's scan checks only the clerk's rules
        assertThat(okAs(clerk, 201, "POST", "/inventory/replenishment/scan", null).get("rules").asInt()).isEqualTo(1);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        String alertOut = liveAlerts(outside).get(0).get("id").asString();
        JsonNode visible = okAs(clerk, 200, "GET", "/inventory/replenishment/alerts?status=live", null)
                .get("data");
        assertThat(visible).isNotEmpty();
        visible.forEach(a -> assertThat(a.get("locationId").asString()).isEqualTo(inside));
        assertThat(call("POST", "/inventory/replenishment/alerts/" + alertOut + "/dismiss", Map.of("reason", "x"),
                clerk.token()).status()).isEqualTo(403);
        assertThat(call("POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of(alertOut)),
                clerk.token()).status()).isEqualTo(403);
        assertThat(liveAlerts(outside).get(0).get("status").asString()).isEqualTo("open");
    }

    // ------------------------------------------------------------------ h01-F5 scan isolation

    @Test
    @DisplayName("h01-F5: a rule whose source location was deleted fails alone; the rest of the scan is kept")
    void scanSurvivesABrokenRule() {
        String store = location(null);
        String source = location(null);
        String store2 = location(null);
        String cement = product("Cement", null);
        String transferRule = rule(cement, store, Map.of("route", "transfer", "sourceLocationId", source,
                "autoRequisition", true));
        String buyRule = rule(product("Lime", null), store2, Map.of("autoRequisition", true));
        assertThat(as("DELETE", "/inventory/locations/" + source, null).status()).isEqualTo(204);
        try {
            JsonNode scan = ok(201, "POST", "/inventory/replenishment/scan", null);
            assertThat(scan.get("requestFailed").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(scan.get("requested").asInt()).isGreaterThanOrEqualTo(1);
            JsonNode broken = liveAlerts(store).get(0);
            assertThat(broken.get("status").asString()).isEqualTo("open");
            assertThat(broken.get("statusReason").asString()).isEqualTo("Automatic request failed: The source location"
                    + " of the transfer rule for " + productName(cement) + " at " + broken.get("locationName").asString()
                    + " no longer exists; choose another source location on the rule");
            assertThat(liveAlerts(store2).get(0).get("status").asString()).isIn("requested", "requisitioned");
        } finally {
            ok(200, "DELETE", "/inventory/replenishment/rules/" + transferRule, null);
            ok(200, "DELETE", "/inventory/replenishment/rules/" + buyRule, null);
        }
    }

    // ------------------------------------------------------------------ h01-F12 duplicate event delivery

    @Test
    @DisplayName("h01-F12: a second delivery of the same request event waits for the first and raises nothing")
    void concurrentRedeliveryRaisesOnce() throws Exception {
        String loc = location(null);
        String sand = product("Sand", null);
        String ruleId = rule(sand, loc, Map.of());
        ok(201, "POST", "/inventory/replenishment/scan", null);
        String alertId = liveAlerts(loc).get(0).get("id").asString();
        // asked for, with the event not delivered yet
        jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requested' WHERE id = ?").param(alertId).update();
        ProcurementReplenishmentService.Line line = new ProcurementReplenishmentService.Line(alertId, sand, "Sand",
                BigDecimal.TEN, null, null, LocalDate.now().plusDays(7).toString(), loc);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // the first delivery holds the waiting alert while it raises its document
            Future<?> first = pool.submit(() -> TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
                        assertThat(replenishment.stillRequested(List.of(alertId))).containsExactly(alertId);
                        locked.countDown();
                        try {
                            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        jdbc.sql("UPDATE \"InventoryReplenishmentAlert\" SET status = 'requisitioned',"
                                + " \"documentNumber\" = 'FIRST' WHERE id = ?").param(alertId).update();
                    })));
            assertThat(locked.await(60, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> procurementReplenishment.raise(List.of(line))));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && !second.isDone() && jdbc.sql("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE wait_event_type = 'Lock' AND query LIKE '%InventoryReplenishmentAlert%'")
                    .query(Long.class).single() == 0) {
                Thread.sleep(50);
            }
            release.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"organizationId\" = ?"
                + " AND \"demandRef\" = ?").params(demoOrg, alertId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT \"documentNumber\" FROM \"InventoryReplenishmentAlert\" WHERE id = ?").param(alertId)
                .query(String.class).single()).isEqualTo("FIRST");
        ok(200, "DELETE", "/inventory/replenishment/rules/" + ruleId, null);
    }

    // ------------------------------------------------------------------ h01-F6 stock levels

    @Test
    @DisplayName("h01-F6: stock levels list only the user's locations; the low / out-of-stock filters run in SQL")
    void stockLevels() {
        String inside = location(null);
        String outside = location(null);
        String tag = "GBF6" + suffix();
        String pipe = product(tag + " Pipe", null);
        stockIn(pipe, inside, 2);
        stockIn(pipe, outside, 5);
        User viewer = user(role("inventory:read"), inside);
        JsonNode scoped = okAs(viewer, 200, "GET", "/inventory/product-stock-levels?search=" + tag, null).get("data")
                .get(0);
        assertThat(scoped.get("locationBreakdown")).hasSize(1);
        assertThat(scoped.get("locationBreakdown").get(0).get("locationId").asString()).isEqualTo(inside);
        assertThat(scoped.get("totalQuantity").asInt()).isEqualTo(2);
        JsonNode all = ok(200, "GET", "/inventory/product-stock-levels?search=" + tag, null).get("data").get(0);
        assertThat(all.get("locationBreakdown")).hasSize(2);
        assertThat(all.get("totalQuantity").asInt()).isEqualTo(7);

        String low = product(tag + " Low", BigDecimal.TEN);
        stockIn(low, inside, 3);
        String none = product(tag + " None", null);
        String plenty = product(tag + " Plenty", BigDecimal.ONE);
        stockIn(plenty, inside, 50);
        JsonNode lows = ok(200, "GET", "/inventory/product-stock-levels?lowStock=true&search=" + tag, null);
        assertThat(lows.get("meta").get("total").asInt()).isEqualTo(2);
        List<String> lowIds = new ArrayList<>();
        lows.get("data").forEach(r -> lowIds.add(r.get("id").asString()));
        assertThat(lowIds).containsExactlyInAnyOrder(low, none);
        JsonNode outs = ok(200, "GET", "/inventory/product-stock-levels?outOfStock=true&search=" + tag, null);
        assertThat(outs.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(outs.get("data").get(0).get("id").asString()).isEqualTo(none);
        assertThat(outs.get("data").get(0).get("isOut").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/inventory/product-stock-levels?lowStock=true&search=" + tag + "%20Low", null)
                .get("data").get(0).get("isLow").asBoolean()).isTrue();
        assertThat(plenty).isNotBlank();
    }

    // ------------------------------------------------------------------ h04-F10 reorder suggestions per store

    static JsonNode suggestion(JsonNode list, String ruleId) {
        for (JsonNode n : list) {
            if (ruleId.equals(n.get("ruleId").asString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("h04-F10: open requests count for their own store in base units; supplier and branch ids are checked")
    void reorderSupplyPerStore() {
        String a = location(null);
        String b = location(null);
        String cement = product("Cement", null);
        String ruleA = ok(200, "PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId", a,
                "minQuantity", 20, "maxQuantity", 50)).get("id").asString();
        String ruleB = ok(200, "PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId", b,
                "minQuantity", 20, "maxQuantity", 50)).get("id").asString();
        // store A's request does not cover store B
        ok(200, "POST", "/procurement/replenishment/raise", Map.of("ruleIds", List.of(ruleA)));
        JsonNode list = ok(200, "GET", "/procurement/replenishment/suggestions?all=true", null);
        assertThat(dec(suggestion(list, ruleA), "openRequests")).isEqualByComparingTo("50");
        JsonNode sb = suggestion(list, ruleB);
        assertThat(dec(sb, "openRequests")).isEqualByComparingTo("0");
        assertThat(sb.get("needed").asBoolean()).isTrue();
        assertThat(dec(sb, "suggestedQuantity")).isEqualByComparingTo("50");

        // a request line in packs of 10 for store B counts 1 pack as 10 units (position 10, below the minimum 20)
        String pack = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND id <> ? ORDER BY code"
                + " LIMIT 1").params(demoOrg, uom).query(String.class).single();
        jdbc.sql("INSERT INTO \"ProductUom\" (id, \"organizationId\", \"productId\", \"uomId\", factor) VALUES"
                + " (?, ?, ?, ?, 10)").params(UUID.randomUUID().toString(), demoOrg, cement, pack).update();
        String request = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PurchaseRequest\" (id, \"organizationId\", \"requestNumber\", status, \"updatedAt\")"
                + " VALUES (?, ?, ?, 'submitted', now())").params(request, demoOrg, "PR-GB" + suffix()).update();
        jdbc.sql("INSERT INTO \"PurchaseRequestLine\" (id, \"organizationId\", \"purchaseRequestId\", \"productId\","
                + " description, quantity, \"unitOfMeasureId\", \"deliveryLocationId\") VALUES (?, ?, ?, ?, 'Cement',"
                + " 1, ?, ?)").params(UUID.randomUUID().toString(), demoOrg, request, cement, pack, b).update();
        sb = suggestion(ok(200, "GET", "/procurement/replenishment/suggestions?all=true", null), ruleB);
        assertThat(dec(sb, "openRequests")).isEqualByComparingTo("10");
        assertThat(dec(sb, "suggestedQuantity")).isEqualByComparingTo("40");

        String foreign = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, 'Elsewhere', true, now())").params(foreign, otherOrg, "SUP-" + suffix()).update();
        Res supplier = as("PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId", a,
                "minQuantity", 20, "maxQuantity", 50, "preferredSupplierId", foreign));
        assertThat(supplier.status()).isEqualTo(404);
        assertThat(supplier.body().get("message").asString()).isEqualTo("Supplier not found");
        Res branch = as("PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId", a,
                "minQuantity", 20, "maxQuantity", 50, "branchId", UUID.randomUUID().toString()));
        assertThat(branch.status()).isEqualTo(404);
        assertThat(branch.body().get("message").asString()).isEqualTo("Branch not found");
        Res otherBranch = as("PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId",
                location(branch()), "minQuantity", 20, "maxQuantity", 50, "branchId", branch()));
        assertThat(otherBranch.status()).isEqualTo(400);
        assertThat(otherBranch.body().get("message").asString()).isEqualTo("The store belongs to another branch; use"
                + " the store's own branch or leave the branch empty");
    }

    // ------------------------------------------------------------------ h01-F9 / F10 reports and ledger detail

    @Test
    @DisplayName("h01-F9/F10: movement reports keep revaluations apart; ledger detail totals every line in one read")
    void reportsAndLedgerDetail() {
        String loc = location(null);
        String p = product("Rope", null);
        stockIn(p, loc, 8);
        String from = LocalDate.now().minusDays(1).toString();
        String to = LocalDate.now().plusDays(1).toString();
        JsonNode moves = ok(200, "GET", "/inventory/reports/item-movements?locationId=" + loc + "&productId=" + p
                + "&start=" + from + "&end=" + to, null);
        JsonNode row = moves.get("data").get(0);
        assertThat(dec(row, "openingQty")).isEqualByComparingTo("0");
        assertThat(dec(row, "closingQty")).isEqualByComparingTo("8");
        assertThat(dec(row, "valueAdjusted")).isEqualByComparingTo("0");
        assertThat(dec(moves.get("totals"), "valueAdjusted")).isEqualByComparingTo("0");
        JsonNode analysis = ok(200, "GET", "/inventory/reports/movement-analysis?locationId=" + loc + "&start=" + from
                + "&end=" + to, null);
        assertThat(dec(analysis.get("summary"), "valueIn")).isEqualByComparingTo("40");
        assertThat(dec(analysis.get("summary"), "valueAdjusted")).isEqualByComparingTo("0");

        String entryId = jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(demoOrg, p).query(String.class).single();
        JsonNode entry = ok(200, "GET", "/inventory/ledger/" + entryId, null);
        assertThat(entry.get("totals").get("lines").asInt()).isEqualTo(1);
        assertThat(dec(entry.get("totals"), "quantityIn")).isEqualByComparingTo("8");
        assertThat(entry.get("totals").get("truncated").asBoolean()).isFalse();
        JsonNode line = entry.get("lines").get(0);
        assertThat(line.get("product").get("id").asString()).isEqualTo(p);
        assertThat(line.get("location").get("id").asString()).isEqualTo(loc);
        assertThat(entry.get("entry").get("product").get("id").asString()).isEqualTo(p);
    }
}
