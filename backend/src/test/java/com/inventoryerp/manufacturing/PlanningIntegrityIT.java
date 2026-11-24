package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.MachineDataService;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PollInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Integrity fix 2026-11-24 (MP-1 … MP-13): MRP units and locks, scheduler locks, plan-from-demand netting, machine
 * polling outside a transaction. Every test here failed before its fix.
 */
class PlanningIntegrityIT extends MfgTestSupport {

    static final ZoneId ZONE = ZoneId.of("Africa/Kampala");

    void params(String productId, Map<String, Object> body) {
        ok(200, "PUT", "/manufacturing/planning-params/" + productId, body);
    }

    static JsonNode find(JsonNode rows, String productId) {
        for (JsonNode p : rows) {
            if (p.get("productId").asString().equals(productId)) {
                return p;
            }
        }
        return null;
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }

    /** A product counted in pieces with a box of 12 (its purchase unit). Returns {product, piece, box}. */
    String[] boxed(String code) {
        String p = product(code);
        String piece = unit("PC");
        String box = unit("BOX");
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"purchaseUomId\" = ? WHERE id = ?").params(piece, box, p)
                .update();
        jdbc.sql("INSERT INTO \"ProductUom\" (id, \"organizationId\", \"productId\", \"uomId\", factor, \"isPurchase\","
                + " \"isSales\", \"isActive\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 12, true, true, true,"
                + " now(), now())").params(UUID.randomUUID().toString(), demoOrg, p, box).update();
        return new String[] {p, piece, box};
    }

    void purchaseOrder(String productId, int qty, BigDecimal factor, String uomId) {
        String po = UUID.randomUUID().toString();
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, false, now())").params(partner, demoOrg, "PI-" + suffix(),
                        "Supplier " + suffix()).update();
        jdbc.sql("INSERT INTO \"PurchaseOrder\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", status,"
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'approved', now())").params(po, demoOrg, "PO-" + suffix(),
                        partner).update();
        jdbc.sql("INSERT INTO \"PurchaseOrderLine\" (id, \"organizationId\", \"purchaseOrderId\", \"productId\","
                + " description, quantity, \"unitPrice\", \"uomFactor\", \"unitOfMeasureId\") VALUES (?, ?, ?, ?,"
                + " 'supply', ?, 50, ?, ?)").params(UUID.randomUUID().toString(), demoOrg, po, productId, qty, factor,
                        uomId).update();
    }

    <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    @Test
    @DisplayName("MP-1: open purchase orders count in base units (2 boxes of 12 = 24 pieces)")
    void openPurchaseInBaseUnits() {
        String[] p = boxed("MPBOX");
        params(p[0], Map.of("procurement", "buy", "reorderPoint", 30));
        purchaseOrder(p[0], 2, new BigDecimal("12"), p[2]);
        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        JsonNode item = find(run.get("items"), p[0]);
        assertThat(num(item.get("scheduledReceipts"))).isEqualByComparingTo("24");
        assertThat(num(item.get("plannedQty"))).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("MP-2: legacy MRP nets material needs in base units against base on-hand + PO remainders")
    void legacyMrpInBaseUnits() {
        String[] c = boxed("MPLEG");
        String out = product("MPLEGOUT");
        String bomId = bom(out, c[0], 1, 1, Map.of());
        String orderId = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        // Two boxes (24 pieces) needed; 10 pieces on hand; one box (12) on order → 2 pieces short.
        jdbc.sql("UPDATE \"ProductionMaterial\" SET \"uomId\" = ?, \"qtyPlanned\" = 2, \"qtyConsumed\" = 0"
                + " WHERE \"orderId\" = ?").params(c[2], orderId).update();
        stockIn(bakery, c[0], 10, 1);
        purchaseOrder(c[0], 1, new BigDecimal("12"), c[2]);
        JsonNode res = ok(201, "POST", "/manufacturing/mrp/run", null);
        JsonNode line = null;
        for (JsonNode l : res.get("purchaseRequest").get("lines")) {
            if (c[0].equals(l.get("productId").asString())) {
                line = l;
            }
        }
        assertThat(line).as(res.toString()).isNotNull();
        // 2 pieces short, labelled in the purchase unit: 2 / 12 boxes.
        assertThat(num(line.get("quantity"))).isEqualByComparingTo("0.166667");
        assertThat(line.get("unitOfMeasureId").asString()).isEqualTo(c[2]);
    }

    @Test
    @DisplayName("MP-12: production request lines in another unit count in base units; forecast weeks bounded")
    void requestLinesInBaseUnits() {
        String[] p = boxed("MPREQ");
        Instant due = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode req = ok(201, "POST", "/manufacturing/requests", Map.of("neededBy", due.toString(), "lines",
                List.of(Map.of("productId", p[0], "quantity", 2, "uomId", p[2]))));
        ok(201, "POST", "/manufacturing/requests/" + req.get("id").asString() + "/submit", null);
        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        assertThat(num(find(run.get("items"), p[0]).get("grossRequirement"))).isEqualByComparingTo("24");

        assertThat(as("GET", "/manufacturing/reports/forecast?weeks=500", null).body().get("message").asString())
                .isEqualTo("weeks must be between 1 and 104");
        assertThat(ok(200, "GET", "/manufacturing/reports/forecast?weeks=8", null).get("weeks").asInt())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("MP-3: concurrent MRP runs serialise — exactly one run stays completed")
    void runsSerialise() throws Exception {
        List<Integer> statuses = parallel(3, () -> as("POST", "/manufacturing/mrp/runs",
                Map.of("horizonDays", 7)).status());
        assertThat(statuses).containsOnly(201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"MrpRun\" WHERE \"organizationId\" = ? AND status = 'completed'")
                .param(demoOrg).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("MP-4: concurrent firms of one planned order make one purchase request line")
    void firmOnce() throws Exception {
        String p = product("MPFIRM");
        params(p, Map.of("procurement", "buy", "reorderPoint", 5));
        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        String poId = find(run.get("plannedOrders"), p).get("id").asString();
        List<Res> results = parallel(4, () -> as("POST", "/manufacturing/mrp/planned-orders/" + poId + "/firm", null));
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        for (Res r : results) {
            if (r.status() != 201) {
                assertThat(r.status()).isEqualTo(400);
                assertThat(r.body().get("message").asString()).isEqualTo("Planned order is already firmed.");
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"productId\" = ?").param(p)
                .query(Long.class).single()).isEqualTo(1L);
        // A cancel after the firm is refused, and a firm-run skips it.
        assertThat(as("POST", "/manufacturing/mrp/planned-orders/" + poId + "/cancel", null).body().get("message")
                .asString()).isEqualTo("Planned order is already firmed.");
        assertThat(ok(201, "POST", "/manufacturing/mrp/runs/" + run.get("id").asString() + "/firm",
                Map.of("ids", List.of(poId))).get("firmed").asInt()).isZero();
    }

    /** A single-slot centre working around the clock. */
    String centre() {
        return ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "MPWC-" + suffix(), "name", "Line"))
                .get("id").asString();
    }

    String routedOrder(String wcId, boolean start) {
        String comp = product("MPCOMP");
        String out = product("MPOUT");
        stockIn(bakery, comp, 20, 1);
        String bomId = bom(out, comp, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Line " + suffix(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 1, "name", "Run", "workCenterId", wcId, "durationMins", 60))));
        String orderId = start ? started(bomId).get("id").asString()
                : ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                        bakery, "outputLocationId", warehouse)).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);
        return orderId;
    }

    @Test
    @DisplayName("MP-5: two concurrent finite schedules on one single-slot centre never overlap")
    void scheduleLocks() throws Exception {
        String wc = centre();
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(routedOrder(wc, true));
        }
        Instant start = LocalDate.now(ZONE).plusDays(10).atTime(9, 0).atZone(ZONE).toInstant();
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> statuses = parallel(4, () -> as("POST", "/manufacturing/production-orders/"
                + orders.get(next.getAndIncrement()) + "/schedule", Map.of("startAt", start.toString())).status());
        assertThat(statuses).containsOnly(201);
        List<Map<String, Object>> bars = jdbc.sql("SELECT \"plannedStartAt\" AS s, \"plannedEndAt\" AS e FROM"
                + " \"WorkOrder\" WHERE \"workCenterId\" = ? ORDER BY \"plannedStartAt\"").param(wc).query()
                .listOfRows();
        assertThat(bars).hasSize(4);
        for (int i = 1; i < bars.size(); i++) {
            java.sql.Timestamp prevEnd = (java.sql.Timestamp) bars.get(i - 1).get("e");
            java.sql.Timestamp s = (java.sql.Timestamp) bars.get(i).get("s");
            assertThat(s).as("bar %s overlaps the one before", i).isAfterOrEqualTo(prevEnd);
        }
    }

    @Test
    @DisplayName("MP-11: a Gantt drag and a backward schedule of the same order lock in one order (no deadlock)")
    void rescheduleAndBackwardDoNotDeadlock() throws Exception {
        String wc = centre();
        String orderId = routedOrder(wc, false);
        String woId = jdbc.sql("SELECT id FROM \"WorkOrder\" WHERE \"productionOrderId\" = ?").param(orderId)
                .query(String.class).single();
        String due = LocalDate.now(ZONE).plusDays(20).toString();
        for (int i = 0; i < 5; i++) {
            Instant at = LocalDate.now(ZONE).plusDays(5 + i).atTime(10, 0).atZone(ZONE).toInstant();
            List<Callable<Integer>> tasks = List.of(
                    () -> as("POST", "/manufacturing/planning/work-orders/" + woId + "/reschedule",
                            Map.of("startAt", at.toString())).status(),
                    () -> as("POST", "/manufacturing/planning/orders/" + orderId + "/schedule-backward",
                            Map.of("dueAt", due)).status());
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Future<Integer> f : pool.invokeAll(tasks)) {
                    assertThat(f.get()).isEqualTo(200);
                }
            }
        }
    }

    @Test
    @DisplayName("MP-8: planning the same demand twice makes nothing new")
    void planFromDemandOnce() {
        String block = product("MPDEM");
        String bomId = bom(block, product("MPDEMC"), 1, 1, Map.of());
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, false, true, now())").params(partner, demoOrg, "PD-" + suffix(),
                        "Customer " + suffix()).update();
        LocalDate today = LocalDate.now(ZONE);
        String delivery = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\", priority) VALUES (?, ?, ?, ?, ?, ?, 'normal')").params(delivery, demoOrg,
                        "DO-" + suffix(), partner, warehouse, java.sql.Date.valueOf(today.plusDays(5))).update();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrderLine\" (id, \"organizationId\", \"deliveryOrderId\", \"lineNumber\","
                + " \"productId\", \"orderedQty\") VALUES (?, ?, ?, 1, ?, 40)").params(UUID.randomUUID().toString(),
                        demoOrg, delivery, block).update();
        Map<String, Object> body = Map.of("dateFrom", today.toString(), "dateTo", today.plusDays(10).toString(),
                "locationId", bakery, "includeStockPolicy", false, "productIds", List.of(block));
        JsonNode plan = ok(201, "POST", "/manufacturing/planning/plans/from-demand", body);
        assertThat(plan.get("id").asString()).isNotBlank();
        assertThat(bomId).isNotBlank();
        Res again = as("POST", "/manufacturing/planning/plans/from-demand", body);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Nothing to plan: stock and open production cover the demand.");
    }

    @Test
    @DisplayName("MP-9: a location-scoped run counts only the production output landing at that location")
    void locationScopedRun() {
        String out = product("MPLOC");
        String bomId = bom(out, product("MPLOCC"), 1, 1, Map.of());
        params(out, Map.of("procurement", "make", "reorderPoint", 50));
        // A draft order for 10 whose output lands in the warehouse.
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 10, "locationId", bakery,
                "outputLocationId", warehouse));
        JsonNode wide = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        assertThat(num(find(wide.get("items"), out).get("scheduledReceipts"))).isEqualByComparingTo("10");
        JsonNode scoped = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30, "locationId", bakery));
        assertThat(num(find(scoped.get("items"), out).get("scheduledReceipts"))).isEqualByComparingTo("0");
        JsonNode atWarehouse = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30, "locationId",
                warehouse));
        assertThat(num(find(atWarehouse.get("items"), out).get("scheduledReceipts"))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("MP-10: polling a machine adapter holds no transaction across the adapter's calls")
    void pollOutsideTransaction() throws Exception {
        assertThat(MachineDataService.class.getMethod("poll", PollInput.class).isAnnotationPresent(Transactional.class))
                .isFalse();
        assertThat(MachineDataService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
