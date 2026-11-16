package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.web.ReplenishmentController;
import com.inventoryerp.kernel.events.OutboxWorker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Replenishment (INV-3): scan, available stock, one live alert, requisition through procurement, tenancy. */
class ReplenishmentIT extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    String adminId;
    String supplier;
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
        supplier = ok(201, "POST", "/partners", Map.of("name", "Sand Supplier " + suffix(), "isSupplier", true)).get("id")
                .asString();
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "RP-" + suffix(), "name", "Yard " + suffix(), "type",
                "warehouse")).get("id").asString();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"costPrice\", \"categoryId\", \"uomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                + " 'AVCO'::\"CostingMethod\", 4, ?, ?, now())")
                .params(id, demoOrg, "RP-" + suffix(), "Sand " + suffix(), category, uom).update();
        return id;
    }

    void stock(String product, String location, int qty) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", location, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", product, "quantity", qty, "unitCost", 4))));
    }

    String rule(String product, String location, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("productId", product, "locationId", location, "minQty", 10));
        body.putAll(extra);
        return ok(201, "POST", "/inventory/replenishment/rules", body).get("id").asString();
    }

    JsonNode liveAlert(String location) {
        return ok(200, "GET", "/inventory/replenishment/alerts?status=live&locationId=" + location, null).get("data");
    }

    void drain() {
        drainOutboxFully();
    }

    @Test
    @DisplayName("a scan raises one alert below minimum, keeps it current and resolves it when stock recovers")
    void lifecycle() {
        String p = product();
        String l = location();
        stock(p, l, 6);
        rule(p, l, Map.of("maxQty", 30));
        assertThat(ok(201, "POST", "/inventory/replenishment/scan", null).get("raised").asInt()).isGreaterThanOrEqualTo(1);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        JsonNode alerts = liveAlert(l);
        assertThat(alerts).hasSize(1);
        assertThat(new BigDecimal(alerts.get(0).get("suggestedQty").asString())).isEqualByComparingTo("24");
        assertThat(alerts.get(0).get("status").asString()).isEqualTo("open");
        stock(p, l, 20);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l)).isEmpty();
        assertThat(ok(200, "GET", "/inventory/replenishment/alerts?status=resolved&locationId=" + l, null).get("data"))
                .hasSize(1);
    }

    String openRequest(String product, String location, int qty) {
        String request = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PurchaseRequest\" (id, \"organizationId\", \"requestNumber\", status, \"updatedAt\")"
                + " VALUES (?, ?, ?, 'submitted', now())").params(request, demoOrg, "PR-T" + suffix()).update();
        jdbc.sql("INSERT INTO \"PurchaseRequestLine\" (id, \"organizationId\", \"purchaseRequestId\", \"productId\","
                + " description, quantity, \"deliveryLocationId\") VALUES (?, ?, ?, ?, 'Sand', ?, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, request, product, qty, location).update();
        return request;
    }

    @Test
    @DisplayName("supply already requested for the location counts: no alert while it covers the minimum")
    void incomingSupplyCounts() {
        String p = product();
        String l = location();
        stock(p, l, 4);
        rule(p, l, Map.of("maxQty", 30));
        String elsewhere = location();
        openRequest(p, elsewhere, 50);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        JsonNode alert = liveAlert(l).get(0);
        assertThat(new BigDecimal(alert.get("incoming").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(alert.get("suggestedQty").asString())).isEqualByComparingTo("26");

        String request = openRequest(p, l, 4);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        alert = liveAlert(l).get(0);
        assertThat(new BigDecimal(alert.get("incoming").asString())).isEqualByComparingTo("4");
        assertThat(new BigDecimal(alert.get("suggestedQty").asString())).isEqualByComparingTo("22");

        // 4 on hand + 6 on the way meets the minimum of 10: the open alert resolves.
        jdbc.sql("UPDATE \"PurchaseRequestLine\" SET quantity = 6 WHERE \"purchaseRequestId\" = ?").param(request)
                .update();
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l)).isEmpty();
    }

    @Test
    @DisplayName("active reservations count against available stock")
    void reservationsCount() {
        String p = product();
        String l = location();
        stock(p, l, 12);
        rule(p, l, Map.of());
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l)).isEmpty();
        ok(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", l, "quantity", 5, "sourceType",
                "manual", "sourceId", "R-" + suffix()));
        ok(201, "POST", "/inventory/replenishment/scan", null);
        JsonNode alert = liveAlert(l).get(0);
        assertThat(new BigDecimal(alert.get("available").asString())).isEqualByComparingTo("7");
        assertThat(new BigDecimal(alert.get("reserved").asString())).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("requesting a requisition raises one draft purchase request per supplier, once")
    void requisition() {
        String p = product();
        String l = location();
        rule(p, l, Map.of("supplierId", supplier, "reorderQty", 40, "leadTimeDays", 3));
        ok(201, "POST", "/inventory/replenishment/scan", null);
        String alertId = liveAlert(l).get(0).get("id").asString();
        ok(201, "POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of(alertId)));
        assertThat(as("POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of(alertId))).status())
                .isEqualTo(400);
        drain();
        JsonNode alert = liveAlert(l).get(0);
        assertThat(alert.get("status").asString()).isEqualTo("requisitioned");
        String requestId = alert.get("purchaseRequestId").asString();
        assertThat(alert.get("requestNumber").asString()).startsWith("PR-");
        assertThat(jdbc.sql("SELECT status::text FROM \"PurchaseRequest\" WHERE id = ?").param(requestId)
                .query(String.class).single()).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"PurchaseRequest\" WHERE id = ?").param(requestId)
                .query(String.class).single()).isEqualTo(supplier);
        assertThat(jdbc.sql("SELECT quantity FROM \"PurchaseRequestLine\" WHERE \"purchaseRequestId\" = ?").param(requestId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("40");
        // A scan neither repeats nor resolves the alert while it is still short.
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l).get(0).get("purchaseRequestId").asString()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("an automatic rule asks for the requisition on the scan")
    void automatic() {
        String p = product();
        String l = location();
        rule(p, l, Map.of("autoRequisition", true));
        assertThat(ok(201, "POST", "/inventory/replenishment/scan", null).get("requested").asInt()).isGreaterThanOrEqualTo(1);
        drain();
        assertThat(liveAlert(l).get(0).get("status").asString()).isEqualTo("requisitioned");
    }

    @Test
    @DisplayName("a dismissed alert needs a reason and does not come straight back")
    void dismissal() {
        String p = product();
        String l = location();
        String ruleId = rule(p, l, Map.of());
        ok(201, "POST", "/inventory/replenishment/scan", null);
        String alertId = liveAlert(l).get(0).get("id").asString();
        assertThat(as("POST", "/inventory/replenishment/alerts/" + alertId + "/dismiss", Map.of()).status()).isEqualTo(400);
        ok(201, "POST", "/inventory/replenishment/alerts/" + alertId + "/dismiss", Map.of("reason", "Stopped selling"));
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l)).isEmpty();
        // Stock recovers, then falls again: the rule speaks up again.
        stock(p, l, 20);
        ok(201, "POST", "/inventory/replenishment/scan", null);
        ok(200, "PATCH", "/inventory/replenishment/rules/" + ruleId, Map.of("minQty", 25));
        ok(201, "POST", "/inventory/replenishment/scan", null);
        assertThat(liveAlert(l)).hasSize(1);
    }

    @Test
    @DisplayName("rule validation: one rule per product and location, sensible levels")
    void validation() {
        String p = product();
        String l = location();
        Map<String, Object> bad = Map.of("productId", p, "locationId", l, "minQty", 10, "maxQty", 5);
        assertThat(as("POST", "/inventory/replenishment/rules", bad).status()).isEqualTo(400);
        assertThat(as("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", l, "minQty", 0))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/inventory/replenishment/rules", Map.of("productId", UUID.randomUUID().toString(),
                "locationId", l, "minQty", 1)).status()).isEqualTo(404);
        String id = rule(p, l, Map.of());
        assertThat(as("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", l, "minQty", 5))
                .status()).isEqualTo(400);
        assertThat(ok(200, "PATCH", "/inventory/replenishment/rules/" + id, Map.of("isActive", false)).get("isActive")
                .asBoolean()).isFalse();
        ok(200, "DELETE", "/inventory/replenishment/rules/" + id, null);
        assertThat(as("PATCH", "/inventory/replenishment/rules/" + id, Map.of()).status()).isEqualTo(404);
        rule(p, l, Map.of());
    }

    @Test
    @DisplayName("request bodies are validated records: unknown fields are refused, service messages kept")
    void bodies() {
        String p = product();
        String l = location();
        Map<String, Object> unknown = new java.util.HashMap<>(Map.of("productId", p, "locationId", l, "minQty", 10,
                "colour", "red"));
        assertThat(as("POST", "/inventory/replenishment/rules", unknown).status()).isEqualTo(400);
        Res badRoute = as("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", l, "minQty",
                10, "route", "teleport"));
        assertThat(badRoute.status()).isEqualTo(400);
        assertThat(badRoute.body().get("message").asString()).isEqualTo("route must be one of: buy, manufacture, transfer");
        String id = ok(201, "POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", l, "minQty", 10,
                "maxQty", 30, "reorderQty", 5, "supplierId", supplier, "leadTimeDays", 3, "autoRequisition", false,
                "route", "buy")).get("id").asString();
        assertThat(as("PATCH", "/inventory/replenishment/rules/" + id, Map.of("productId", p)).status()).isEqualTo(400);
        Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("maxQty", null);
        clear.put("leadTimeDays", 7);
        JsonNode patched = ok(200, "PATCH", "/inventory/replenishment/rules/" + id, clear);
        assertThat(patched.get("maxQty").isNull()).isTrue();
        assertThat(patched.get("leadTimeDays").asInt()).isEqualTo(7);
        assertThat(new BigDecimal(patched.get("minQty").asString()).compareTo(BigDecimal.TEN)).isZero();
        String source = location();
        JsonNode transfer = ok(200, "PATCH", "/inventory/replenishment/rules/" + id, Map.of("route", "transfer",
                "sourceLocationId", source));
        assertThat(transfer.get("route").asString()).isEqualTo("transfer");
        assertThat(as("POST", "/inventory/replenishment/alerts/" + UUID.randomUUID() + "/dismiss", Map.of("reason", "x",
                "note", "y")).status()).isEqualTo(400);
        assertThat(as("POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of(), "force", true))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of())).body().get("message")
                .asString()).isEqualTo("alertIds is required");
    }

    @Test
    @DisplayName("another organization sees no rule or alert, and cannot touch one")
    void tenantIsolation() {
        String p = product();
        String l = location();
        String id = rule(p, l, Map.of());
        assertThat(call("GET", "/inventory/replenishment/rules", null, otherToken).raw().body()).doesNotContain(id);
        assertThat(call("PATCH", "/inventory/replenishment/rules/" + id, Map.of("minQty", 1), otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("authorization matrix on all 8 routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ReplenishmentController.class)).isEqualTo(8);
    }
}
