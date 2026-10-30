package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.FleetDeliveryInvoiceService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures of the fleet 1.4b weighbridge ↔ dispatch tests: a branch whose rule requires the weighbridge for bulk
 * products, a quarry yard with stock of aggregate (sold per TON) and pit sand (per m³, 1.6 t / m³), a weighbridge
 * station on the yard with a simulator indicator, a customer site, operator / supervisor users, trucks and drivers.
 */
abstract class FleetWeighDispatchSupport extends FleetSafetyTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    FleetDeliveryInvoiceService deliveryInvoices;

    String today;
    String branch;
    String yard;
    String ton;
    String m3;
    String aggregate;
    String sand;
    String customer;
    String site;
    String station;
    String sim;
    String operator;
    String supervisor;
    String bulkRule;

    @BeforeAll
    void weighDispatchFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, demoOrg, "QRY-" + sfx, "Quarry branch " + sfx).update();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Quarry yard', 'warehouse', now())").params(yard, demoOrg, "YWD-" + sfx).update();
        ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'").param(demoOrg)
                .query(String.class).single();
        String volume = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UomCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(volume, demoOrg, "Bulk volume WD " + sfx).update();
        m3 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, \"categoryId\", category, factor,"
                + " ratio, \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'Cubic metre', ?, 'volume', 1, 1, 0.001,"
                + " now())").params(m3, demoOrg, "M3W-" + sfx, volume).update();
        aggregate = product("Aggregate 20mm", ton);
        sand = product("Pit sand", m3);
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> {
                    stock.receive(StockMove.of(aggregate, yard, new BigDecimal("500")).unitCost(new BigDecimal("30000")));
                    stock.receive(StockMove.of(sand, yard, new BigDecimal("200")).unitCost(new BigDecimal("20000")));
                    deliveryInvoices.transportRevenueAccount();
                }));
        ok(200, "PUT", "/fleet/product-load-factors/" + sand, Map.of("tonnesPerUnit", 1.6, "notes", "loose sand"));
        customer = partner("Contractor");
        site = ok(201, "POST", "/fleet/customer-sites", Obj.of("partnerId", customer, "name", "Bridge site " + sfx,
                "city", "Jinja", "zone", "East " + sfx, "distanceKm", 42)).get("id").asString();
        station = ok(201, "POST", "/fleet/weighbridge/stations", Map.of("code", "wd-" + sfx, "name", "Yard gate",
                "branchId", branch, "locationId", yard, "tolerancePct", 0.5, "toleranceKg", 20, "tareValidityDays", 30))
                .get("id").asString();
        sim = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "SIMD-" + sfx,
                "adapterType", "simulator", "config", Map.of("weightKg", 0, "motionFrames", 1), "readTimeoutMs", 1000))
                .get("id").asString();
        operator = tokenWith("weighbridge:read", "weighbridge:weigh");
        supervisor = tokenWith("weighbridge:read", "weighbridge:approve_exception");
        bulkRule = ok(200, "PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch, "mode",
                "bulk", "notes", "Quarry: every bulk load over the bridge")).get("id").asString();
    }

    String product(String name, String uomId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 45000, ?, ?, ?, now())")
                .params(id, demoOrg, "WDP-" + sfx + "-" + (++plates), name + " " + sfx, uomId, uomId, uomId).update();
        return id;
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, false, true, now())").params(id, demoOrg, "WDC-" + sfx + "-"
                        + (++plates), name + " " + sfx).update();
        return id;
    }

    /** A tipper with payload / body volume and an odometer. */
    String truck(Object payloadT) {
        return vehicle(Map.of("payloadCapacityT", payloadT, "bodyVolumeM3", 20, "openingOdometer", 1000)).get("id")
                .asString();
    }

    /** A driver with a valid licence, qualified for tippers. */
    String driver() {
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Driver", "lastName", sfx + "-" + (++plates),
                "baseSalary", 500000)).get("id").asString();
        return ok(201, "POST", "/fleet/drivers", Map.of("employeeId", emp, "licenceNumber", "DLW-" + sfx + "-" + plates,
                "licenceExpiresOn", LocalDate.now(BusinessZone.get()).plusYears(1).toString(), "qualifiedCategoryIds",
                List.of(tipperClass))).get("id").asString();
    }

    /** Manual delivery order of the quarry branch. */
    JsonNode deliveryOrder(String branchId, Object... productQty) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            lines.add(Map.of("productId", productQty[i], "quantity", productQty[i + 1], "unitPrice", 45000));
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", customer, "siteId", site, "locationId", yard,
                "requestedDate", today, "lines", lines));
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        return ok(201, "POST", "/fleet/delivery-orders", body);
    }

    /** Plans the delivery order on one truck + driver; returns the first trip id. */
    String plan(JsonNode order, String truckId, String driverId) {
        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + order.get("id").asString() + "/plan-loads",
                Map.of("vehicleId", truckId, "driverId", driverId));
        return planned.get("createdTripIds").get(0).asString();
    }

    /** A planned, assigned trip of {@code tonnes} t of aggregate for the quarry branch. */
    String aggregateTrip(Object tonnes) {
        return plan(deliveryOrder(branch, aggregate, tonnes), truck(30), driver());
    }

    JsonNode trip(String id) {
        return ok(200, "GET", "/fleet/trips/" + id, null);
    }

    /** Outbound ticket registered from the trip alone (everything else pre-filled). */
    JsonNode ticketFor(String tripId) {
        Res r = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType", "sale_delivery",
                "tripId", tripId), operator);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    /** Puts {@code kg} on the simulator and captures it as the first / second weighing. */
    JsonNode weigh(String ticketId, String slot, Object kg) {
        ok(201, "POST", "/fleet/weighbridge/devices/" + sim + "/simulator-weight", Map.of("weightKg", kg));
        Res r = call("POST", "/fleet/weighbridge/tickets/" + ticketId + "/" + slot + "-weigh", Map.of("deviceId", sim),
                operator);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    /** Tare 12 000 kg, then gross 12 000 + {@code netKg}. */
    JsonNode weighed(String ticketId, int netKg) {
        weigh(ticketId, "first", 12000);
        return weigh(ticketId, "second", 12000 + netKg);
    }

    JsonNode dispatchOk(String tripId) {
        Res r = money("POST", "/fleet/trips/" + tripId + "/dispatch", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, productId, yard).query(BigDecimal.class)
                .single();
    }

    static JsonNode line(JsonNode trip, int i) {
        return trip.get("lines").get(i);
    }
}
