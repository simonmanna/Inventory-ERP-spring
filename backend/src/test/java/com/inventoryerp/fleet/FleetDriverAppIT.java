package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * DRIVER-1 — the driver app's API: home for the signed-in driver, scope to the driver's own units, draft fuel logs
 * (idempotent replay, never posted), the pre-trip check raising defects, and 403 for anyone without a driver profile.
 */
class FleetDriverAppIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String tipperClass;
    String truck;
    String otherTruck;
    String employee;
    String driverToken;
    String noProfileToken;
    String bank;

    @BeforeAll
    void fixtures() {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipperClass = c.get("id").asString();
            }
        }
        truck = vehicle();
        otherTruck = vehicle();
        employee = ok(201, "POST", "/hr/employees", Map.of("firstName", "Dan", "lastName", "Driver " + sfx))
                .get("id").asString();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Driver app " + sfx, new String[] {"fleet_trip:read", "fleet_trip:deliver"})
                .update();
        String email = "driver-" + sfx.toLowerCase() + "@demo.test";
        String user = createUser(demoOrg, email, "Driver@12345", role);
        jdbc.sql("UPDATE \"HrEmployee\" SET \"userId\" = ? WHERE id = ?").params(user, employee).update();
        driverToken = login("DEMO", email, "Driver@12345");
        String stranger = "nodriver-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, stranger, "Driver@12345", role);
        noProfileToken = login("DEMO", stranger, "Driver@12345");
        String driver = ok(201, "POST", "/fleet/drivers", Map.of("employeeId", employee, "licenceNumber", "DL" + sfx,
                "licenceClasses", List.of("CE"), "licenceExpiresOn", "2099-01-01", "qualifiedCategoryIds",
                List.of(tipperClass))).get("id").asString();
        ok(201, "POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId", driver, "kind", "primary"));
        bank = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.\"isCashEquivalent\" = true AND a.\"isPostable\" = true AND"
                + " a.\"isActive\" = true AND a.\"deletedAt\" IS NULL LIMIT 1").param(demoOrg).query(String.class)
                .single();
    }

    String vehicle() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", tipperClass);
        body.put("registrationPlate", "UDA " + sfx + "-" + (++seq));
        body.put("openingOdometer", 1000);
        return ok(201, "POST", "/fleet/vehicles", body).get("id").asString();
    }

    Map<String, String> keyed(String token, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", key);
        return h;
    }

    Map<String, Object> fuel(String vehicleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("source", "station");
        body.put("litres", 40);
        body.put("amount", 200000);
        body.put("odometer", 1100);
        body.put("paymentMethod", "cash");
        body.put("paymentAccountId", bank);
        return body;
    }

    @Test
    @DisplayName("home: my unit, today's check, live trips; 403 without a driver profile")
    void home() {
        JsonNode home = call("GET", "/fleet/driver-app/home", null, driverToken).body();
        assertThat(home.get("driver").get("employeeId").asString()).isEqualTo(employee);
        assertThat(home.get("units")).hasSize(1);
        assertThat(home.get("units").get(0).get("id").asString()).isEqualTo(truck);
        assertThat(call("GET", "/fleet/driver-app/home", null, noProfileToken).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("fuel: a draft under my name, replayed once, refused on someone else's unit, no balances exposed")
    void fuelLog() {
        Res options = call("GET", "/fleet/driver-app/fuel-options?vehicleId=" + truck, null, driverToken);
        assertThat(options.status()).isEqualTo(200);
        assertThat(options.body().get("paymentAccounts").get(0).has("balance")).isFalse();
        assertThat(call("GET", "/fleet/driver-app/fuel-options?vehicleId=" + otherTruck, null, driverToken).status())
                .isEqualTo(403);
        String key = UUID.randomUUID().toString();
        Res first = call("POST", "/fleet/driver-app/fuel-logs", fuel(truck), keyed(driverToken, key));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200); // idempotent routes answer 200 (CONTRACT.md)
        assertThat(first.body().get("status").asString()).isEqualTo("draft");
        Res replay = call("POST", "/fleet/driver-app/fuel-logs", fuel(truck), keyed(driverToken, key));
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(jdbc.sql("SELECT \"employeeId\" FROM \"FleetFuelLog\" WHERE id = ?")
                .param(first.body().get("id").asString()).query(String.class).single()).isEqualTo(employee);
        assertThat(call("POST", "/fleet/driver-app/fuel-logs", fuel(otherTruck),
                keyed(driverToken, UUID.randomUUID().toString())).status()).isEqualTo(403);
        assertThat(call("GET", "/fleet/driver-app/fuel-logs", null, driverToken).body().toString())
                .contains(first.body().get("code").asString());
    }

    @Test
    @DisplayName("pre-trip: the checklist of my unit; a failed item raises a defect; today's check shows on home")
    void preTrip() {
        JsonNode pre = call("GET", "/fleet/driver-app/pre-trip?vehicleId=" + truck, null, driverToken).body();
        JsonNode template = pre.get("template");
        List<Map<String, Object>> items = new ArrayList<>();
        boolean failed = false;
        for (JsonNode i : template.get("items")) {
            items.add(Map.of("code", i.get("code").asString(), "result", failed ? "pass" : "fail"));
            failed = true;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", truck);
        body.put("templateId", template.get("id").asString());
        body.put("items", items);
        Res r = call("POST", "/fleet/driver-app/inspections", body, keyed(driverToken, UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("result").asString()).isEqualTo("fail");
        assertThat(r.body().get("defectCount").asInt()).isEqualTo(1);
        JsonNode unit = call("GET", "/fleet/driver-app/home", null, driverToken).body().get("units").get(0);
        assertThat(unit.get("preTripToday").asString()).isEqualTo("fail");
        assertThat(unit.get("openDefects").asInt()).isGreaterThanOrEqualTo(1);
        body.put("vehicleId", otherTruck);
        assertThat(call("POST", "/fleet/driver-app/inspections", body,
                keyed(driverToken, UUID.randomUUID().toString())).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("tenant isolation: another organization's user has no driver profile here")
    void tenantIsolation() {
        assertThat(call("GET", "/fleet/driver-app/pre-trip?vehicleId=" + truck, null, otherToken).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("authorization matrix: 401 / 403 on every driver-app route")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fleet.web.driver")).isEqualTo(8);
    }
}
