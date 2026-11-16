package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Delivery &amp; fulfilment management on a sales order: several deliveries of several products with their own
 * schedule, transport and outcome; per-line quantities that never double-count; stock issued once at dispatch.
 */
class SalesDeliveryManagementIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String granite;
    String sand;
    String blocks;
    String yard;
    String depot;
    String customer;
    String clerkToken;

    @BeforeAll
    void fixtures() {
        granite = product("Granite");
        sand = product("Sand");
        blocks = product("Blocks");
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "DMY-" + sfx, "name", "DM yard " + sfx, "type", "warehouse"))
                .get("id").asString();
        depot = ok(201, "POST", "/inventory/locations", Map.of("code", "DMD-" + sfx, "name", "DM depot " + sfx, "type",
                "warehouse")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "DMC-" + sfx, "DM cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    for (String p : List.of(granite, sand, blocks)) {
                        stock.receive(StockMove.of(p, yard, new BigDecimal("100000")).unitCost(new BigDecimal("20")));
                    }
                }));
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Delivery planner " + sfx, new String[] {
                    "sales:order:read", "sales:order:read_all", "sales:delivery:read", "sales:delivery:write"}).update();
        String email = "dm-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Plan@12345", role);
        clerkToken = login("DEMO", email, "Plan@12345");
    }

    String product(String name) {
        return ok(201, "POST", "/products", Map.of("code", "DM" + name.charAt(0) + "-" + sfx, "name", name + " " + sfx,
                "productType", "stockable", "trackInventory", true, "salesPrice", 10, "costPrice", 4)).get("id").asString();
    }

    /** A confirmed order: product → quantity. Returns the order with its lines. */
    JsonNode order(Map<String, Integer> qty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        qty.forEach((p, q) -> lines.add(Map.of("productId", p, "quantity", q, "unitPrice", 10)));
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", lines))
                .get("id").asString();
        return ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
    }

    static String lineOf(JsonNode order, String productId) {
        for (JsonNode l : order.get("lines")) {
            if (productId.equals(l.get("productId").asString())) {
                return l.get("id").asString();
            }
        }
        throw new AssertionError("no line for " + productId);
    }

    Res post(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", path, body, h);
    }

    JsonNode money(String path, Object body) {
        Res r = post(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    /** Plan a delivery: order line → quantity, plus extra fields. */
    Res plan(JsonNode o, Map<String, Integer> qty, Map<String, Object> extra) {
        List<Map<String, Object>> lines = new ArrayList<>();
        qty.forEach((p, q) -> lines.add(Map.of("lineId", lineOf(o, p), "quantity", q)));
        Map<String, Object> body = new HashMap<>(Map.of("vehiclePlate", "UBH 123D", "driverName", "John Kamau", "lines", lines));
        body.putAll(extra);
        return as("POST", "/sales/orders/" + o.get("id").asString() + "/deliveries", body);
    }

    JsonNode planned(JsonNode o, Map<String, Integer> qty, Map<String, Object> extra) {
        Res r = plan(o, qty, extra);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    JsonNode summary(JsonNode o) {
        return ok(200, "GET", "/sales/orders/" + o.get("id").asString() + "/delivery-summary", null);
    }

    static JsonNode line(JsonNode summary, String productId) {
        for (JsonNode l : summary.get("lines")) {
            if (productId.equals(l.get("productId").asString())) {
                return l;
            }
        }
        throw new AssertionError("no summary line for " + productId);
    }

    /** Confirm a delivery with every line fully delivered. */
    JsonNode deliverAll(String id) {
        JsonNode d = ok(200, "GET", "/sales/deliveries/" + id, null);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : d.get("lines")) {
            lines.add(Map.of("loadLineId", l.get("id").asString(), "deliveredQty", l.get("dispatchedQty").asDouble()));
        }
        return money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Site agent", "receiverPhone", "0700000000",
                "lines", lines));
    }

    BigDecimal onHand(String product) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, yard).query(BigDecimal.class).single();
    }

    static Map<String, Integer> qty(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("three deliveries of three products on different dates, drivers and vehicles fulfil every line exactly")
    void multiProductPartialDeliveries() {
        JsonNode o = order(qty(granite, 100, sand, 50, blocks, 2000));
        assertThat(summary(o).get("stage").asString()).isEqualTo("pending");
        String today = LocalDate.now().toString();
        JsonNode d1 = planned(o, qty(granite, 40, sand, 20, blocks, 500), Map.of("plannedDate", today, "scheduledTime", "10:00",
                "deliveryMethod", "site_delivery", "vehicleType", "Tipper", "driverPhone", "0772000001", "transportCost", 150000));
        JsonNode d2 = planned(o, qty(granite, 30, sand, 30, blocks, 1000), Map.of("plannedDate", LocalDate.now().plusDays(1)
                .toString(), "vehiclePlate", "UBG 456E", "driverName", "Peter Ochieng"));
        JsonNode d3 = planned(o, qty(granite, 30, sand, 0, blocks, 500), Map.of("plannedDate", LocalDate.now().plusDays(2)
                .toString(), "vehiclePlate", "UAA 001A", "driverName", "Grace"));
        assertThat(d3.get("lineCount").asInt()).as("a 0 quantity line is left out").isEqualTo(2);
        assertThat(d1.get("scheduledTime").asString()).isEqualTo("10:00");
        assertThat(d1.get("transportCost").asDouble()).isEqualTo(150000);
        assertThat(d2.get("driverName").asString()).isEqualTo("Peter Ochieng");
        assertThat(d1.get("stage").asString()).isEqualTo("scheduled");

        JsonNode s = summary(o);
        assertThat(s.get("stage").asString()).isEqualTo("scheduled");
        assertThat(s.get("trips").asInt()).isEqualTo(3);
        assertThat(line(s, granite).get("scheduled").asDouble()).isEqualTo(100);
        assertThat(line(s, granite).get("toSchedule").asDouble()).isZero();
        assertThat(line(s, sand).get("toSchedule").asDouble()).isZero();
        assertThat(line(s, granite).get("delivered").asDouble()).as("scheduled is not delivered").isZero();

        Res over = plan(o, qty(granite, 1), Map.of());
        assertThat(over.status()).as("over-delivery").isEqualTo(400);
        assertThat(over.raw().body()).contains("remain to be planned");

        BigDecimal before = onHand(granite);
        money("/sales/deliveries/" + d1.get("id").asString() + "/issue", null);
        assertThat(onHand(granite)).as("stock leaves at dispatch").isEqualByComparingTo(before.subtract(new BigDecimal("40")));
        s = summary(o);
        assertThat(s.get("stage").asString()).isEqualTo("dispatched");
        assertThat(line(s, granite).get("dispatched").asDouble()).isEqualTo(40);
        assertThat(line(s, granite).get("scheduled").asDouble()).as("not in two buckets").isEqualTo(60);

        ok(200, "POST", "/sales/deliveries/" + d1.get("id").asString() + "/depart", null);
        JsonNode done = deliverAll(d1.get("id").asString());
        assertThat(done.get("stage").asString()).isEqualTo("delivered");
        assertThat(onHand(granite)).as("confirmation does not issue again").isEqualByComparingTo(before.subtract(new BigDecimal("40")));
        s = summary(o);
        assertThat(s.get("stage").asString()).isEqualTo("partially_delivered");
        assertThat(line(s, granite).get("delivered").asDouble()).isEqualTo(40);
        assertThat(line(s, granite).get("dispatched").asDouble()).isZero();
        assertThat(line(s, granite).get("outstanding").asDouble()).isEqualTo(60);
        assertThat(line(s, blocks).get("outstanding").asDouble()).isEqualTo(1500);

        for (JsonNode d : List.of(d2, d3)) {
            money("/sales/deliveries/" + d.get("id").asString() + "/issue", null);
            deliverAll(d.get("id").asString());
        }
        s = summary(o);
        assertThat(s.get("stage").asString()).isEqualTo("delivered");
        assertThat(s.get("percentDelivered").asDouble()).isEqualTo(100);
        for (String p : List.of(granite, sand, blocks)) {
            assertThat(line(s, p).get("outstanding").asDouble()).isZero();
        }
        assertThat(onHand(granite)).isEqualByComparingTo(before.subtract(new BigDecimal("100")));
        assertThat(ok(200, "GET", "/sales/orders/" + o.get("id").asString(), null).get("deliveryStatus").asString()).isEqualTo("full");
    }

    @Test
    @DisplayName("a planned delivery is edited, re-planned within what remains, rescheduled and cancelled; history records it")
    void editReplanCancel() {
        JsonNode o = order(qty(granite, 10));
        String id = planned(o, qty(granite, 10), Map.of()).get("id").asString();
        String line = lineOf(o, granite);
        JsonNode edited = ok(200, "PATCH", "/sales/deliveries/" + id, Map.of("lines", List.of(Map.of("lineId", line, "quantity", 6)),
                "plannedDate", LocalDate.now().plusDays(3).toString(), "driverName", "Second driver", "locationId", depot,
                "expectedArrivalAt", "2030-01-01T10:00:00Z"));
        assertThat(edited.get("plannedQty").asDouble()).isEqualTo(6);
        assertThat(edited.get("driverName").asString()).isEqualTo("Second driver");
        assertThat(edited.get("locationName").asString()).contains("DM depot");
        assertThat(edited.get("plannedDate").asString()).isEqualTo(LocalDate.now().plusDays(3).toString());
        assertThat(line(summary(o), granite).get("toSchedule").asDouble()).isEqualTo(4);
        assertThat(as("PATCH", "/sales/deliveries/" + id, Map.of("lines", List.of(Map.of("lineId", line, "quantity", 11))))
                .status()).as("re-plan above the order").isEqualTo(400);
        assertThat(as("PATCH", "/sales/deliveries/" + id, Map.of("lines", List.of(Map.of("lineId", line, "quantity", 0))))
                .status()).as("empty delivery").isEqualTo(400);
        assertThat(as("PATCH", "/sales/deliveries/" + id, Map.of("bogus", 1)).status()).isEqualTo(400);
        String second = planned(o, qty(granite, 4), Map.of()).get("id").asString();
        assertThat(plan(o, qty(granite, 1), Map.of()).status()).isEqualTo(400);

        assertThat(as("POST", "/sales/deliveries/" + id + "/cancel", Map.of()).status()).as("reason required").isEqualTo(400);
        JsonNode cancelled = ok(200, "POST", "/sales/deliveries/" + id + "/cancel", Map.of("reason", "Customer postponed"));
        assertThat(cancelled.get("stage").asString()).isEqualTo("cancelled");
        assertThat(line(summary(o), granite).get("toSchedule").asDouble()).as("cancel releases").isEqualTo(6);
        assertThat(line(summary(o), granite).get("scheduled").asDouble()).isEqualTo(4);
        assertThat(as("PATCH", "/sales/deliveries/" + id, Map.of("driverName", "x")).status()).isEqualTo(400);
        assertThat(summary(o).get("trips").asInt()).as("cancelled is not a trip").isEqualTo(1);

        JsonNode history = ok(200, "GET", "/sales/deliveries/" + id + "/history", null);
        List<String> actions = new ArrayList<>();
        history.forEach(h -> actions.add(h.get("action").asString()));
        assertThat(actions).contains("create", "update", "cancel");
        assertThat(history.get(0).get("actorName").isNull()).isFalse();

        money("/sales/deliveries/" + second + "/issue", null);
        Res late = as("PATCH", "/sales/deliveries/" + second, Map.of("lines", List.of(Map.of("lineId", line, "quantity", 2))));
        assertThat(late.status()).as("quantities are fixed once dispatched").isEqualTo(400);
        JsonNode tracked = ok(200, "PATCH", "/sales/deliveries/" + second, Map.of("trackingNumber", "TRK-1", "tripExpenses", 25000));
        assertThat(tracked.get("trackingNumber").asString()).isEqualTo("TRK-1");
    }

    @Test
    @DisplayName("a failed attempt brings the load back to stock, frees the quantity and is rescheduled on a new delivery")
    void failAndReschedule() {
        JsonNode o = order(qty(sand, 8));
        String id = planned(o, qty(sand, 8), Map.of()).get("id").asString();
        BigDecimal before = onHand(sand);
        money("/sales/deliveries/" + id + "/issue", null);
        ok(200, "POST", "/sales/deliveries/" + id + "/depart", null);
        assertThat(ok(200, "GET", "/sales/deliveries/" + id, null).get("stage").asString()).isEqualTo("in_transit");
        assertThat(post("/sales/deliveries/" + id + "/fail", Map.of()).status()).as("reason required").isEqualTo(400);
        JsonNode failed = money("/sales/deliveries/" + id + "/fail", Map.of("reason", "Site gate locked", "reasonCode", "site_closed"));
        assertThat(failed.get("stage").asString()).isEqualTo("failed");
        assertThat(onHand(sand)).as("load back in the yard").isEqualByComparingTo(before);
        JsonNode s = summary(o);
        assertThat(line(s, sand).get("toSchedule").asDouble()).isEqualTo(8);
        assertThat(line(s, sand).get("returned").asDouble()).isEqualTo(8);
        assertThat(s.get("stage").asString()).isEqualTo("pending");
        assertThat(s.get("stages").get("failed").asInt()).isEqualTo(1);

        String other = planned(o, qty(sand, 3), Map.of()).get("id").asString();
        assertThat(plan(o, qty(sand, 5), Map.of("rescheduledFromId", other)).status()).as("only a failed one").isEqualTo(400);
        JsonNode again = planned(o, qty(sand, 5), Map.of("rescheduledFromId", id, "plannedDate", LocalDate.now().plusDays(1)
                .toString()));
        assertThat(again.get("rescheduledFromId").asString()).isEqualTo(id);
        assertThat(line(summary(o), sand).get("toSchedule").asDouble()).isZero();
    }

    @Test
    @DisplayName("shortage, damage and rejection are traced apart; rejected goods return; over-confirmation is refused")
    void shortageDamagedRejected() {
        JsonNode o = order(qty(blocks, 10));
        JsonNode d = planned(o, qty(blocks, 10), Map.of());
        String id = d.get("id").asString();
        String loadLine = d.get("lines").get(0).get("id").asString();
        BigDecimal before = onHand(blocks);
        money("/sales/deliveries/" + id + "/issue", null);
        Res tooMuch = post("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", loadLine, "deliveredQty", 9, "rejectedQty", 2))));
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(tooMuch.raw().body()).contains("exceed");
        Res noReason = post("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", loadLine, "deliveredQty", 9))));
        assertThat(noReason.status()).as("shortage without reason").isEqualTo(400);
        JsonNode done = money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "arrivedAt",
                "2020-01-01T00:00:00Z", "lines", List.of(Map.of("loadLineId", loadLine, "deliveredQty", 6, "damagedQty", 1,
                        "rejectedQty", 2, "reason", "spillage"))));
        assertThat(done.get("stage").asString()).isEqualTo("partially_delivered");
        JsonNode l = done.get("lines").get(0);
        assertThat(l.get("deliveredQty").asDouble()).isEqualTo(6);
        assertThat(l.get("damagedQty").asDouble()).isEqualTo(1);
        assertThat(l.get("rejectedQty").asDouble()).isEqualTo(2);
        assertThat(l.get("shortageQty").asDouble()).isEqualTo(1);
        assertThat(onHand(blocks)).as("10 out, 2 rejected back").isEqualByComparingTo(before.subtract(new BigDecimal("8")));
        JsonNode s = line(summary(o), blocks);
        assertThat(s.get("delivered").asDouble()).isEqualTo(6);
        assertThat(s.get("rejected").asDouble()).isEqualTo(2);
        assertThat(s.get("damaged").asDouble()).isEqualTo(1);
        assertThat(s.get("shortage").asDouble()).isEqualTo(1);
        assertThat(s.get("outstanding").asDouble()).isEqualTo(4);
        assertThat(s.get("toSchedule").asDouble()).as("rejected can go again").isEqualTo(2);
    }

    @Test
    @DisplayName("the customer refuses the whole load: it is returned to stock and the delivery reads returned")
    void wholeLoadReturned() {
        JsonNode o = order(qty(granite, 5));
        String id = planned(o, qty(granite, 5), Map.of()).get("id").asString();
        BigDecimal before = onHand(granite);
        money("/sales/deliveries/" + id + "/issue", null);
        JsonNode r = money("/sales/deliveries/" + id + "/return", Map.of("reason", "Wrong grade"));
        assertThat(r.get("stage").asString()).isEqualTo("returned");
        assertThat(onHand(granite)).isEqualByComparingTo(before);
        assertThat(line(summary(o), granite).get("toSchedule").asDouble()).isEqualTo(5);
    }

    @Test
    @DisplayName("delivery methods: collection needs no plate, transfer needs a destination, courier a company")
    void methods() {
        JsonNode o = order(qty(sand, 9));
        Map<String, Object> noPlate = new HashMap<>(Map.of("vehiclePlate", "", "deliveryMethod", "customer_collect"));
        JsonNode collect = planned(o, qty(sand, 3), noPlate);
        assertThat(collect.get("deliveryMethod").asString()).isEqualTo("customer_collect");
        money("/sales/deliveries/" + collect.get("id").asString() + "/issue", null);
        assertThat(plan(o, qty(sand, 1), Map.of("deliveryMethod", "warehouse_transfer")).status()).isEqualTo(400);
        JsonNode transfer = planned(o, qty(sand, 3), Map.of("deliveryMethod", "warehouse_transfer", "destinationLocationId", depot));
        assertThat(transfer.get("destinationLocationName").asString()).contains("DM depot");
        assertThat(plan(o, qty(sand, 1), Map.of("deliveryMethod", "courier", "vehiclePlate", "")).status()).isEqualTo(400);
        JsonNode courier = planned(o, qty(sand, 3), Map.of("deliveryMethod", "courier", "vehiclePlate", "", "transportCompany",
                "Fast Couriers", "trackingNumber", "FC-99"));
        assertThat(courier.get("trackingNumber").asString()).isEqualTo("FC-99");
        JsonNode o2 = order(qty(sand, 1));
        assertThat(plan(o2, qty(sand, 1), Map.of("deliveryMethod", "teleport")).status()).isEqualTo(400);
        assertThat(plan(o2, qty(sand, 1), Map.of("scheduledTime", "25:00")).status()).isEqualTo(400);
        assertThat(plan(o2, qty(sand, -1), Map.of()).status()).isEqualTo(400);
        assertThat(plan(o2, qty(sand, 1), Map.of("transportCost", -5)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("concurrent deliveries on one order never plan more than ordered")
    void concurrentCreation() throws Exception {
        JsonNode o = order(qty(granite, 10));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(() -> plan(o, qty(granite, 6), Map.of()).status());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(jobs)) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 201).count()).as(codes.toString()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        JsonNode s = line(summary(o), granite);
        assertThat(s.get("scheduled").asDouble()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"sourceId\" = ?").param(o.get("id").asString())
                .query(Long.class).single()).as("one delivery order").isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch, confirm and cancel need their own permissions; planning alone does not grant them")
    void permissions() {
        JsonNode o = order(qty(blocks, 4));
        List<Map<String, Object>> lines = List.of(Map.of("lineId", lineOf(o, blocks), "quantity", 4));
        Res made = call("POST", "/sales/orders/" + o.get("id").asString() + "/deliveries", Map.of("vehiclePlate", "UAX 1",
                "lines", lines), clerkToken);
        assertThat(made.status()).as(made.raw().body()).isEqualTo(201);
        String id = made.body().get("id").asString();
        assertThat(call("PATCH", "/sales/deliveries/" + id, Map.of("driverName", "A"), clerkToken).status()).isEqualTo(200);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + clerkToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/deliveries/" + id + "/issue", null, h).status()).isEqualTo(403);
        assertThat(call("POST", "/sales/deliveries/" + id + "/cancel", Map.of("reason", "x"), clerkToken).status()).isEqualTo(403);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/deliveries/" + id + "/fail", Map.of("reason", "x"), h).status()).isEqualTo(403);
        assertThat(call("GET", "/sales/orders/" + o.get("id").asString() + "/delivery-summary", null, clerkToken).status())
                .isEqualTo(200);
        String weak = weakToken();
        assertThat(call("GET", "/sales/orders/" + o.get("id").asString() + "/delivery-summary", null, weak).status()).isEqualTo(403);
        assertThat(call("GET", "/sales/deliveries/" + id + "/history", null, weak).status()).isEqualTo(403);
        assertThat(call("GET", "/sales/deliveries/" + id, null, otherToken).status()).as("tenant isolation").isIn(403, 404);
    }
}
