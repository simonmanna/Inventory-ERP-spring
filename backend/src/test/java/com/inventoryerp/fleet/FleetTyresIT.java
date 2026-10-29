package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.7 tyres over HTTP: register, wheel layout, fitment / rotation (swap) / removal with meter readings,
 * retread, scrap, tread readings, cost per km, the one-time stock issue of a stocked tyre (idempotent, GL balanced on
 * the vehicle's cost centre), concurrency on a position, tenant isolation and the authorization matrix.
 */
class FleetTyresIT extends FleetSafetyTestSupport {

    @Autowired
    StockService stock;

    String location;

    @BeforeAll
    void tyreFixtures() {
        location = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?,"
                + " ?, now())").params(location, demoOrg, "TYRE-" + sfx, "Tyre store " + sfx).update();
    }

    JsonNode tyre(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serialNumber", "SN-" + sfx + "-" + (++plates));
        body.put("brand", "Michelin");
        body.put("size", "315/80R22.5");
        body.put("tyreType", "drive");
        body.put("purchaseCost", 1_200_000);
        body.put("newTreadDepthMm", 16);
        body.putAll(extra);
        return ok(201, "POST", "/fleet/tyres", body);
    }

    String product() {
        String id = ok(201, "POST", "/products", Map.of("code", "TYR-" + sfx + "-" + (++plates), "name",
                "Tyre 315/80R22.5 " + sfx, "productType", "stockable", "salesPrice", 1_500_000, "costPrice",
                1_000_000)).get("id").asString();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> stock.receive(StockMove.of(id, location,
                new BigDecimal("3")).unitCost(new BigDecimal("950000")).source("fleet_tyre_it", sfx)));
        return id;
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, productId, location)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("lifecycle: register → fit (reading) → rotate with swap → remove → retread → refit → scrap; cost/km")
    void lifecycle() {
        // No opening reading: the back-dated fitment readings below are the vehicle's first.
        String vehicleId = vehicle(Map.of()).get("id").asString();
        JsonNode layout = ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/tyres", null);
        assertThat(layout.get("axles")).hasSize(3);
        assertThat(layout.get("positionCount").asInt()).isEqualTo(10);
        assertThat(layout.get("spares").get(0).get("code").asString()).isEqualTo("SP1");

        JsonNode a = tyre(Map.of("serialNumber", " sn-a " + sfx));
        assertThat(a.get("serialNumber").asString()).isEqualTo("SN-A " + sfx);
        assertThat(a.get("status").asString()).isEqualTo("in_stock");
        assertThat(as("POST", "/fleet/tyres", Map.of("serialNumber", "SN-A " + sfx)).status()).isEqualTo(409);
        String aId = a.get("id").asString();
        String bId = tyre(Map.of()).get("id").asString();

        Res badPos = money("POST", "/fleet/tyres/" + aId + "/fit", Map.of("vehicleId", vehicleId, "position", "A9LO"));
        assertThat(badPos.status()).isEqualTo(400);
        assertThat(msg(badPos)).startsWith("Position A9LO is not on vehicle").contains("6x4");
        Instant t1 = Instant.now().minus(3, ChronoUnit.DAYS);
        Res fitA = money("POST", "/fleet/tyres/" + aId + "/fit", Map.of("vehicleId", vehicleId, "position", "a2lo",
                "at", t1.toString(), "odometer", 10_100, "treadDepthMm", 16));
        assertThat(fitA.status()).as(fitA.raw().body()).isEqualTo(200);
        assertThat(fitA.body().get("status").asString()).isEqualTo("fitted");
        assertThat(fitA.body().get("position").asString()).isEqualTo("A2LO");
        assertThat(dec(fitA.body().get("fitOdometer"))).isEqualByComparingTo("10100");
        // The fitment reading went to the vehicle's meters (source tyre).
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source = 'tyre'")
                .param(vehicleId).query(Long.class).single()).isEqualTo(1);
        Res occupied = money("POST", "/fleet/tyres/" + bId + "/fit", Map.of("vehicleId", vehicleId, "position",
                "A2LO"));
        assertThat(occupied.status()).isEqualTo(409);
        assertThat(msg(occupied)).contains("already has tyre SN-A " + sfx);
        Res again = money("POST", "/fleet/tyres/" + aId + "/fit", Map.of("vehicleId", vehicleId, "position", "A3LO"));
        assertThat(msg(again)).contains("is already fitted to vehicle");
        assertThat(money("POST", "/fleet/tyres/" + bId + "/fit", Map.of("vehicleId", vehicleId, "position", "A3LO",
                "at", t1.toString())).status()).isEqualTo(200);

        // Rotation onto B's position swaps the two; both stints close at 15,100 km (5,000 km each).
        Instant t2 = Instant.now().minus(2, ChronoUnit.DAYS);
        JsonNode rotated = ok(201, "POST", "/fleet/tyres/" + aId + "/rotate", Map.of("position", "A3LO",
                "at", t2.toString(), "odometer", 15_100));
        assertThat(rotated.get("position").asString()).isEqualTo("A3LO");
        assertThat(dec(rotated.get("kmRun"))).isEqualByComparingTo("5000");
        JsonNode b = ok(200, "GET", "/fleet/tyres/" + bId, null);
        assertThat(b.get("position").asString()).isEqualTo("A2LO");
        assertThat(dec(b.get("kmRun"))).isEqualByComparingTo("5000");
        boolean swapped = false;
        for (JsonNode e : b.get("events")) {
            swapped |= "rotated".equals(e.get("eventType").asString()) && "A3LO".equals(e.get("fromPosition").asString())
                    && dec(e.get("kmRun")).compareTo(new BigDecimal("5000")) == 0;
        }
        assertThat(swapped).as("B's swap event").isTrue();

        // Live km includes the running stint (vehicle now at 18,100 km).
        ok(201, "POST", "/fleet/vehicles/" + vehicleId + "/meter-readings", Map.of("meterType", "odometer",
                "value", 18_100, "readingAt", Instant.now().minus(1, ChronoUnit.DAYS).toString()));
        JsonNode live = ok(200, "GET", "/fleet/tyres/" + aId, null);
        assertThat(dec(live.get("stats").get("km"))).isEqualByComparingTo("8000");
        assertThat(dec(live.get("stats").get("currentStintKm"))).isEqualByComparingTo("3000");
        assertThat(dec(live.get("stats").get("costPerKm"))).isEqualByComparingTo("150.0000");

        // Tread reading, then removal at 20,100 km with the depth.
        JsonNode reading = ok(201, "POST", "/fleet/tyres/" + aId + "/readings", Map.of("treadDepthMm", 12.5,
                "pressureKpa", 820));
        assertThat(reading.get("belowMinimum").asBoolean()).isFalse();
        assertThat(dec(reading.get("tyre").get("treadDepthMm"))).isEqualByComparingTo("12.5");
        JsonNode removed = ok(201, "POST", "/fleet/tyres/" + aId + "/remove", Map.of("reason", "Rotation to spare rack",
                "odometer", 20_100, "treadDepthMm", 10));
        assertThat(removed.get("status").asString()).isEqualTo("removed");
        assertThat(removed.get("vehicleId").isNull()).isTrue();
        assertThat(dec(removed.get("kmRun"))).isEqualByComparingTo("10000");
        assertThat(dec(removed.get("stats").get("costPerKm"))).isEqualByComparingTo("120.0000");
        assertThat(dec(removed.get("stats").get("wearPer1000Km"))).isEqualByComparingTo("0.600");
        assertThat(dec(removed.get("stats").get("projectedLifeKm"))).isEqualByComparingTo("21667");

        // Retread: cost adds up, tread restarts, refit elsewhere, then scrap via removal.
        assertThat(msg(as("POST", "/fleet/tyres/" + bId + "/retread", Map.of("cost", 1, "newTreadDepthMm", 14))))
                .contains("is fitted and cannot be retreaded");
        JsonNode retread = ok(201, "POST", "/fleet/tyres/" + aId + "/retread", Map.of("cost", 300_000,
                "newTreadDepthMm", 14));
        assertThat(retread.get("status").asString()).isEqualTo("retreaded");
        assertThat(retread.get("retreadCount").asInt()).isEqualTo(1);
        assertThat(dec(retread.get("stats").get("totalCost"))).isEqualByComparingTo("1500000");
        assertThat(dec(retread.get("stats").get("costPerKm"))).isEqualByComparingTo("150.0000");
        assertThat(retread.get("stats").get("wearPer1000Km").isNull()).isTrue();
        Res refit = money("POST", "/fleet/tyres/" + aId + "/fit", Map.of("vehicleId", vehicleId, "position", "SP1"));
        assertThat(refit.status()).as(refit.raw().body()).isEqualTo(200);
        assertThat(msg(as("POST", "/fleet/tyres/" + aId + "/scrap", Map.of("reason", "x"))))
                .contains("is fitted; remove it");
        JsonNode scrapped = ok(201, "POST", "/fleet/tyres/" + aId + "/remove", Map.of("reason", "Sidewall cut",
                "scrap", true));
        assertThat(scrapped.get("status").asString()).isEqualTo("scrapped");
        assertThat(scrapped.get("scrapReason").asString()).isEqualTo("Sidewall cut");
        assertThat(msg(as("POST", "/fleet/tyres/" + aId + "/readings", Map.of("treadDepthMm", 3))))
                .contains("is scrapped");
        assertThat(as("PATCH", "/fleet/tyres/" + aId, Map.of("brand", "X")).status()).isEqualTo(400);
        List<String> types = new java.util.ArrayList<>();
        ok(200, "GET", "/fleet/tyres/" + aId, null).get("events").forEach(e -> types.add(e.get("eventType").asString()));
        assertThat(types).containsExactlyInAnyOrder("scrapped", "removed", "fitted", "retreaded", "removed", "rotated",
                "fitted", "registered");

        // Vehicle layout shows B at A2LO; list filters.
        JsonNode after = ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/tyres", null);
        assertThat(after.get("fittedCount").asInt()).isEqualTo(1);
        assertThat(after.get("axles").get(1).get("positions").get(0).get("tyre").get("id").asString()).isEqualTo(bId);
        JsonNode fitted = ok(200, "GET", "/fleet/tyres?status=fitted&vehicleId=" + vehicleId, null);
        assertThat(fitted.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/tyres?search=" + sfx + "&pageSize=1", null).get("data")).hasSize(1);
        assertThat(as("GET", "/fleet/tyres?status=flat", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("stocked tyre: first fitment issues 1 unit once (replay, refit), GL balanced on the cost centre")
    void stockIssueOnce() {
        String cc = costCenter();
        String vehicleId = vehicle(Map.of("openingOdometer", 500, "costCenterId", cc, "homeLocationId", location))
                .get("id").asString();
        String productId = product();
        assertThat(onHand(productId)).isEqualByComparingTo("3");
        assertThat(as("POST", "/fleet/tyres", Map.of("serialNumber", "X-" + sfx, "productId",
                UUID.randomUUID().toString())).status()).isEqualTo(400);
        String tyreId = tyre(Map.of("productId", productId)).get("id").asString();
        JsonNode cleared = ok(200, "PATCH", "/fleet/tyres/" + tyreId, Map.of("clear", List.of("purchaseCost")));
        assertThat(cleared.get("purchaseCost").isNull()).isTrue();

        Res noKey = as("POST", "/fleet/tyres/" + tyreId + "/fit", Map.of("vehicleId", vehicleId, "position", "A1L"));
        assertThat(noKey.status()).isEqualTo(400);
        Map<String, String> headers = keyed(adminToken);
        Map<String, Object> body = Map.of("vehicleId", vehicleId, "position", "A1L");
        Res first = call("POST", "/fleet/tyres/" + tyreId + "/fit", body, headers);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", "/fleet/tyres/" + tyreId + "/fit", body, headers);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("stockLedgerCode").asString())
                .isEqualTo(first.body().get("stockLedgerCode").asString());
        assertThat(onHand(productId)).isEqualByComparingTo("2");
        JsonNode t = first.body();
        assertThat(t.get("stockIssuedAt").isNull()).isFalse();
        assertThat(dec(t.get("stockIssueValue"))).isEqualByComparingTo("950000");
        assertThat(dec(t.get("purchaseCost"))).as("cost basis from the issue").isEqualByComparingTo("950000");
        String entry = t.get("issueJournalEntryId").asString();
        assertBalanced(entry);
        Map<String, Object> debit = jdbc.sql("SELECT l.\"costCenterId\", l.dimensions::text AS dims, a.code FROM"
                + " \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND"
                + " l.debit > 0").param(entry).query().singleRow();
        assertThat(debit.get("costCenterId")).isEqualTo(cc);
        assertThat(debit.get("code")).isEqualTo("6430");
        assertThat((String) debit.get("dims")).contains(vehicleId).contains(tyreId);
        assertThat(jdbc.sql("SELECT \"postingKey\" FROM \"JournalEntry\" WHERE id = ?").param(entry)
                .query(String.class).single()).isEqualTo("fleet:tyre:issue:" + tyreId);

        // Remove and fit again: no second issue, no second entry.
        ok(201, "POST", "/fleet/tyres/" + tyreId + "/remove", Map.of("reason", "Puncture"));
        assertThat(money("POST", "/fleet/tyres/" + tyreId + "/fit", Map.of("vehicleId", vehicleId, "position", "A1R"))
                .status()).isEqualTo(200);
        assertThat(onHand(productId)).isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'fleet_tyre' AND \"referenceId\" = ?").params(demoOrg, tyreId).query(Long.class)
                .single())
                .isEqualTo(1);
        assertThat(as("PATCH", "/fleet/tyres/" + tyreId, Map.of("productId", product())).status()).isEqualTo(400);
        // A tyre with history cannot be deleted; a fresh one can.
        assertThat(as("DELETE", "/fleet/tyres/" + tyreId, null).status()).isEqualTo(400);
        String spare = tyre(Map.of()).get("id").asString();
        ok(200, "DELETE", "/fleet/tyres/" + spare, null);
        assertThat(as("GET", "/fleet/tyres/" + spare, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("concurrency: two tyres raced onto one position — exactly one wins")
    void concurrentFitment() throws Exception {
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String t1 = tyre(Map.of()).get("id").asString();
        String t2 = tyre(Map.of()).get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(
                    () -> money("POST", "/fleet/tyres/" + t1 + "/fit", Map.of("vehicleId", vehicleId, "position",
                            "A1L")).status(),
                    () -> money("POST", "/fleet/tyres/" + t2 + "/fit", Map.of("vehicleId", vehicleId, "position",
                            "A1L")).status());
            List<Integer> codes = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(jobs)) {
                codes.add(f.get());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetTyre\" WHERE \"vehicleId\" = ? AND position = 'A1L'")
                .param(vehicleId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's tyres")
    void tenantIsolation() {
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String id = tyre(Map.of()).get("id").asString();
        assertThat(call("GET", "/fleet/tyres/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/tyres/" + id, Map.of("brand", "X"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/tyres/" + id + "/readings", Map.of("treadDepthMm", 5), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/vehicles/" + vehicleId + "/tyres", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/tyres?search=" + sfx, null, otherToken).body().get("meta").get("total")
                .asInt()).isZero();
    }

    @Test
    @DisplayName("authorization: 401 / 403 on every tyre route; a read-only user can list but not fit")
    void authorization() {
        assertThat(assertRouteMatrix(java.util.Set.of(com.inventoryerp.fleet.web.FleetTyreController.class)))
                .isEqualTo(13);
        String reader = tokenWith("fleet_maintenance:read");
        assertThat(call("GET", "/fleet/tyres", null, reader).status()).isEqualTo(200);
        Res denied = call("POST", "/fleet/tyres", Map.of("serialNumber", "Z"), reader);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_maintenance:tyre");
    }
}
