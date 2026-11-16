package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase A A2 (plan §7, §11): the customer's weighing of a delivered truck is evidence on the proof of delivery. It is
 * compared with the quarry weight (ticket, else manual gross − tare) under the station tolerance; lighter beyond the
 * tolerance raises a full-variance weight claim through the approval engine, heavier is only flagged; a multi-product
 * truck waits for a confirmed split. The ticket, stock and delivered quantities never change.
 */
class SalesCustomerWeightIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    int seq;
    String ton;
    String unit;
    String aggregate;
    String sand;
    String blocks;
    String yard;
    String plainYard;
    String customer;
    String station;
    String sim;
    String operator;
    String decider;
    String readOnly;

    @BeforeAll
    void fixtures() {
        ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'").param(demoOrg)
                .query(String.class).single();
        unit = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'").param(demoOrg)
                .query(String.class).single();
        yard = location("CWY-");
        plainYard = location("CWP-");
        aggregate = product("CWA-", "Aggregate", ton, "weighed");
        sand = product("CWS-", "Sand", ton, "accepted");
        blocks = product("CWB-", "Blocks", unit, "accepted");
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "CWC-" + sfx, "CW cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    stock.receive(StockMove.of(aggregate, yard, new BigDecimal("1000")).unitCost(new BigDecimal("30000")));
                    stock.receive(StockMove.of(sand, plainYard, new BigDecimal("1000")).unitCost(new BigDecimal("20000")));
                    stock.receive(StockMove.of(blocks, plainYard, new BigDecimal("5000")).unitCost(new BigDecimal("900")));
                }));
        station = ok(201, "POST", "/fleet/weighbridge/stations", Map.of("code", "cws-" + sfx, "name", "CW gate "
                + sfx, "locationId", yard, "tolerancePct", 0.5, "toleranceKg", 20, "tareValidityDays", 30))
                .get("id").asString();
        sim = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "CWSIM-" + sfx,
                "adapterType", "simulator", "config", Map.of("weightKg", 0, "motionFrames", 1), "readTimeoutMs", 1000))
                .get("id").asString();
        operator = tokenWith("weighbridge:read", "weighbridge:weigh");
        decider = tokenWith("fleet_dispatch:read", "fleet_dispatch:approve_discrepancy");
        readOnly = tokenWith("sales:order:read", "sales:order:read_all", "sales:delivery:read");
    }

    String location(String prefix) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(id, demoOrg, prefix + sfx, prefix + " yard").update();
        return id;
    }

    String product(String prefix, String name, String uom, String basis) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"billingBasis\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 45000, ?, ?, ?,"
                + " ?, now())").params(id, demoOrg, prefix + sfx, name + " " + sfx, uom, uom, uom, basis).update();
        return id;
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "CW " + role, permissions).update();
        String email = "cw-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Weigh@12345", role);
        return login("DEMO", email, "Weigh@12345");
    }

    Map<String, String> headers(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, headers(adminToken));
    }

    JsonNode money(String path, Object body) {
        Res r = post(path, body);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    /** A confirmed order of {@code lines} (productId → qty) from {@code loc}. */
    JsonNode order(String loc, Map<String, Integer> lines) {
        List<Map<String, Object>> l = new ArrayList<>();
        lines.forEach((p, q) -> l.add(Map.of("productId", p, "quantity", q, "unitPrice", 45000)));
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", loc, "lines", l))
                .get("id").asString();
        return ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
    }

    /** One weighed truck of 20 t aggregate (ticket: tare 12 000, net 20 000 kg), dispatched. */
    String weighedTruck() {
        JsonNode o = order(yard, Map.of(aggregate, 20));
        String d = ok(201, "POST", "/sales/orders/" + o.get("id").asString() + "/deliveries", Map.of("vehiclePlate",
                "UCW " + sfx + "-" + (++seq), "driverName", "Driver", "lines", List.of(Map.of("lineId",
                        o.get("lines").get(0).get("id").asString(), "quantity", 20)))).get("id").asString();
        Res t = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType", "sale_delivery",
                "tripId", d), operator);
        assertThat(t.status()).as(t.raw().body()).isEqualTo(201);
        String ticket = t.body().get("id").asString();
        weigh(ticket, "first", 12000);
        weigh(ticket, "second", 32000);
        money("/sales/deliveries/" + d + "/issue", null);
        return d;
    }

    void weigh(String ticketId, String slot, int kg) {
        ok(201, "POST", "/fleet/weighbridge/devices/" + sim + "/simulator-weight", Map.of("weightKg", kg));
        Res r = call("POST", "/fleet/weighbridge/tickets/" + ticketId + "/" + slot + "-weigh", Map.of("deviceId", sim),
                operator);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
    }

    JsonNode delivery(String id) {
        return ok(200, "GET", "/sales/deliveries/" + id, null);
    }

    /** Confirm every line as fully accepted, optionally with the customer's weighing inline. */
    JsonNode confirm(String id, Map<String, Object> customerWeight) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : delivery(id).get("lines")) {
            lines.add(Map.of("loadLineId", l.get("id").asString(), "deliveredQty", l.get("dispatchedQty").asDouble()));
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of("receiverName", "Site agent", "lines", lines));
        if (customerWeight != null) {
            body.put("customerWeight", customerWeight);
        }
        return money("/sales/deliveries/" + id + "/confirm", body);
    }

    JsonNode weight(String id, Map<String, Object> body) {
        return money("/sales/deliveries/" + id + "/customer-weight", body);
    }

    List<Map<String, Object>> claims(String id) {
        return jdbc.sql("SELECT id, status, \"differenceQty\", \"claimKg\", \"withdrawnAt\", \"approvalRequestId\" FROM"
                + " \"FleetDeliveryDiscrepancy\" WHERE \"loadId\" = ? AND kind = 'weight' ORDER BY \"createdAt\"")
                .param(id).query().listOfRows();
    }

    BigDecimal onHand(String product, String loc) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, loc).query(BigDecimal.class).single();
    }

    Map<String, Object> ticketRow(String deliveryId) {
        return jdbc.sql("SELECT t.\"netKg\", t.\"grossKg\", t.\"tareKg\", t.status, t.\"updatedAt\" FROM \"WeighbridgeTicket\" t"
                + " JOIN \"FleetLoad\" l ON l.\"weighSourceId\" = t.id WHERE l.id = ?").param(deliveryId).query().singleRow();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static BigDecimal dec(Object o) {
        return new BigDecimal(o.toString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m == null ? "" : m.isArray() ? m.get(0).asString() : m.asString();
    }

    @Test
    @DisplayName("within tolerance on confirm: no claim; ticket, stock and delivered quantity unchanged")
    void withinTolerance() {
        String d = weighedTruck();
        Map<String, Object> ticketBefore = ticketRow(d);
        BigDecimal stockBefore = onHand(aggregate, yard);
        JsonNode done = confirm(d, Map.of("netKg", 19950, "ticketRef", "CUST-1"));
        JsonNode w = done.get("customerWeight");
        assertThat(w.get("weightOutcome").asString()).isEqualTo("within_tolerance");
        assertThat(dec(w.get("quarryNetKg"))).isEqualByComparingTo("20000");
        assertThat(w.get("quarryWeightSource").asString()).isEqualTo("ticket");
        assertThat(dec(w.get("weightVarianceKg"))).isEqualByComparingTo("50");
        assertThat(dec(w.get("allowedVarianceKg"))).isEqualByComparingTo("100");
        assertThat(w.get("customerTicketRef").asString()).isEqualTo("CUST-1");
        assertThat(claims(d)).isEmpty();
        assertThat(ticketRow(d)).isEqualTo(ticketBefore);
        assertThat(onHand(aggregate, yard)).isEqualByComparingTo(stockBefore);
        assertThat(dec(done.get("lines").get(0).get("deliveredQty"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("outside tolerance: full-variance pending claim; corrections update, withdraw and re-raise; decided → 409")
    void disputedAndResubmitted() {
        String d = weighedTruck();
        confirm(d, null);
        BigDecimal stockBefore = onHand(aggregate, yard);
        Map<String, Object> ticketBefore = ticketRow(d);

        JsonNode r = weight(d, Map.of("grossKg", 31000, "tareKg", 12000)).get("customerWeightResult");
        assertThat(r.get("outcome").asString()).isEqualTo("disputed");
        assertThat(dec(r.get("varianceKg"))).isEqualByComparingTo("1000");
        List<Map<String, Object>> c = claims(d);
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("status")).isEqualTo("pending");
        assertThat(dec(c.get(0).get("differenceQty"))).as("the full variance, not variance − tolerance")
                .isEqualByComparingTo("1");
        assertThat(dec(c.get(0).get("claimKg"))).isEqualByComparingTo("1000");
        assertThat(c.get(0).get("approvalRequestId")).as("through the approval engine").isNotNull();
        String claimId = (String) c.get(0).get("id");

        // Corrected evidence updates the same pending claim.
        weight(d, Map.of("netKg", 19500));
        c = claims(d);
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("id")).isEqualTo(claimId);
        assertThat(dec(c.get(0).get("differenceQty"))).isEqualByComparingTo("0.5");

        // Within tolerance now: the claim is withdrawn (not a decision) and its approval request cancelled.
        JsonNode within = weight(d, Map.of("netKg", 19990));
        assertThat(within.get("customerWeightResult").get("outcome").asString()).isEqualTo("within_tolerance");
        c = claims(d);
        assertThat(c.get(0).get("withdrawnAt")).isNotNull();
        assertThat(jdbc.sql("SELECT status FROM \"ApprovalRequest\" WHERE id = ?").param(c.get(0).get("approvalRequestId"))
                .query(String.class).single()).isEqualTo("cancelled");

        // A withdrawal never blocks a later correction: lighter again raises a new claim.
        weight(d, Map.of("netKg", 19000));
        c = claims(d);
        assertThat(c).hasSize(2);
        String second = (String) c.get(1).get("id");
        assertThat(c.get(1).get("status")).isEqualTo("pending");

        // Once decided, the customer weight can no longer change it.
        Res rejected = call("POST", "/fleet/delivery-discrepancies/" + second + "/reject", Map.of("notes",
                "Customer scale out of calibration"), headers(decider));
        assertThat(rejected.status()).as(rejected.raw().body()).isEqualTo(201);
        Res late = post("/sales/deliveries/" + d + "/customer-weight", Map.of("netKg", 18000));
        assertThat(late.status()).isEqualTo(409);
        assertThat(msg(late)).endsWith("is already dismissed; the customer weight can no longer change it");

        assertThat(ticketRow(d)).as("the quarry ticket never changes").isEqualTo(ticketBefore);
        assertThat(onHand(aggregate, yard)).as("evidence never moves stock").isEqualByComparingTo(stockBefore);
        assertThat(dec(delivery(d).get("lines").get(0).get("deliveredQty"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("heavier is only flagged (Fleet route); the claim is capped at the accepted quantity")
    void heavierAndCapped() {
        String d = weighedTruck();
        String line = delivery(d).get("lines").get(0).get("id").asString();
        money("/sales/deliveries/" + d + "/confirm", Map.of("receiverName", "Site agent", "lines", List.of(Map.of(
                "loadLineId", line, "deliveredQty", 17, "damagedQty", 3, "reason", "spillage"))));
        Res heavier = call("POST", "/fleet/trips/" + d + "/customer-weight", Map.of("netKg", 20500), headers(adminToken));
        assertThat(heavier.status()).as(heavier.raw().body()).isEqualTo(200);
        assertThat(heavier.body().get("customerWeight").get("outcome").asString()).isEqualTo("heavier");
        assertThat(claims(d)).isEmpty();

        // 1 000 kg weighed by the customer: V = 19 000 kg, but the claim stops at the 17 t accepted (damaged is
        // already out of the ceiling); the rest is the unallocated remainder.
        JsonNode r = weight(d, Map.of("netKg", 1000)).get("customerWeightResult");
        assertThat(r.get("outcome").asString()).isEqualTo("disputed");
        assertThat(dec(claims(d).get(0).get("differenceQty"))).isEqualByComparingTo("17");
        assertThat(dec(r.get("unallocatedVarianceKg"))).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("multi-product truck: awaiting split with a proposal, then the confirmed split raises one claim per line")
    void multiProductSplit() {
        JsonNode o = order(plainYard, new LinkedHashMap<>(Map.of(sand, 10, blocks, 100)));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : o.get("lines")) {
            lines.add(Map.of("lineId", l.get("id").asString(), "quantity", l.get("quantity").asDouble()));
        }
        String d = ok(201, "POST", "/sales/orders/" + o.get("id").asString() + "/deliveries", Map.of("vehiclePlate",
                "UCW " + sfx + "-" + (++seq), "driverName", "Driver", "lines", lines)).get("id").asString();
        money("/sales/deliveries/" + d + "/issue", null);
        confirm(d, null);
        // A Sales weighed delivery carries one line; a multi-product truck with a quarry weight is a Fleet trip
        // weighed in t / kg. Stand in for that with the trip's manual gross / tare (Q = 20 000 kg, no station).
        jdbc.sql("UPDATE \"FleetLoad\" SET \"manualGross\" = 32000, \"manualTare\" = 12000 WHERE id = ?").param(d)
                .update();
        String sandLine = null;
        String blockLine = null;
        for (JsonNode l : delivery(d).get("lines")) {
            if (l.get("productId").asString().equals(sand)) {
                sandLine = l.get("id").asString();
            } else {
                blockLine = l.get("id").asString();
            }
        }

        JsonNode r = weight(d, Map.of("netKg", 18800)).get("customerWeightResult");
        assertThat(r.get("quarryWeightSource").asString()).isEqualTo("manual");
        assertThat(r.get("outcome").asString()).isEqualTo("awaiting_split");
        assertThat(claims(d)).as("nothing is claimed before the split is confirmed").isEmpty();
        Map<String, JsonNode> proposal = new LinkedHashMap<>();
        r.get("lines").forEach(l -> proposal.put(l.get("loadLineId").asString(), l));
        assertThat(dec(proposal.get(sandLine).get("proposedQty"))).isEqualByComparingTo("1.2");
        assertThat(proposal.get(blockLine).get("needsManual").asBoolean()).isTrue();

        Res overCap = post("/sales/deliveries/" + d + "/customer-weight", Map.of("netKg", 18800, "allocations",
                List.of(Map.of("loadLineId", sandLine, "quantity", 11))));
        assertThat(overCap.status()).isEqualTo(400);
        assertThat(msg(overCap)).startsWith("The weight claim on a line cannot exceed what is still eligible on it");
        Res noKg = post("/sales/deliveries/" + d + "/customer-weight", Map.of("netKg", 18800, "allocations",
                List.of(Map.of("loadLineId", blockLine, "quantity", 20))));
        assertThat(noKg.status()).isEqualTo(400);

        JsonNode done = weight(d, Map.of("netKg", 18800, "allocations", List.of(Map.of("loadLineId", sandLine,
                "quantity", 0.8), Map.of("loadLineId", blockLine, "quantity", 20, "kg", 400))));
        assertThat(done.get("customerWeightResult").get("outcome").asString()).isEqualTo("disputed");
        assertThat(dec(done.get("customerWeight").get("unallocatedVarianceKg"))).isEqualByComparingTo("0");
        List<Map<String, Object>> c = claims(d);
        assertThat(c).hasSize(2);
        assertThat(c).extracting(x -> dec(x.get("claimKg")).stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("800", "400");
        assertThat(done.get("customerWeight").get("claims")).hasSize(2);
    }

    @Test
    void needsTheConfirmPermission() {
        String d = weighedTruck();
        confirm(d, null);
        Res r = call("POST", "/sales/deliveries/" + d + "/customer-weight", Map.of("netKg", 19000), headers(readOnly));
        assertThat(r.status()).isEqualTo(403);
        Res before = post("/sales/deliveries/" + weighedTruck() + "/customer-weight", Map.of("netKg", 19000));
        assertThat(before.status()).as("only after the proof of delivery").isEqualTo(400);
    }
}
