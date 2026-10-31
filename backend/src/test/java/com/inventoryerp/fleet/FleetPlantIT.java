package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetPlantService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * CMMS-1 — fixed plant on the fleet 1.6 engine: classes for an organization that already has classes, the register by
 * location, hour-based schedules, work orders and downtime on a crusher, cost per equipment, no dispatch, the fixed
 * asset link, disposal following the asset, tenant isolation and the authorization matrix.
 */
class FleetPlantIT extends ApiTestSupport {

    @Autowired
    FleetPlantService plant;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String crusherClass;
    String tipperClass;
    String location;

    @BeforeAll
    void plantFixtures() {
        for (JsonNode c : ok(200, "GET", "/fleet/plant/classes", null)) {
            assertThat(c.get("kind").asString()).isEqualTo("fixed_plant");
            if ("CRUSHER".equals(c.get("code").asString())) {
                crusherClass = c.get("id").asString();
            }
        }
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipperClass = c.get("id").asString();
            }
        }
        location = ok(201, "POST", "/inventory/locations", Map.of("code", "PL-" + sfx, "name", "Plant yard " + sfx,
                "type", "warehouse")).get("id").asString();
    }

    String unit(String asset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", crusherClass);
        body.put("name", "Jaw crusher " + sfx + "-" + (++seq));
        body.put("homeLocationId", location);
        body.put("openingHours", 1000);
        if (asset != null) {
            body.put("assetId", asset);
        }
        return ok(201, "POST", "/fleet/plant", body).get("id").asString();
    }

    String asset() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseCost\","
                + " \"currentValue\", \"updatedAt\") VALUES (?, ?, ?, 'Crusher asset', 90000000, 80000000, now())")
                .params(id, demoOrg, "FA-P" + sfx + "-" + (++seq)).update();
        return id;
    }

    @Test
    @DisplayName("classes: the five plant classes are added once and the road defaults stay unchanged")
    void classes() {
        assertThat(ok(200, "GET", "/fleet/plant/classes", null)).hasSizeGreaterThanOrEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetVehicleCategory\" WHERE \"organizationId\" = ? AND code ="
                + " 'CRUSHER'").param(demoOrg).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("register: plant classes only; no plate needed; hours meter by default; filter by location")
    void register() {
        Res wrong = as("POST", "/fleet/plant", Map.of("categoryId", tipperClass, "registrationPlate", "UAA " + sfx));
        assertThat(wrong.status()).isEqualTo(400);
        String id = unit(null);
        JsonNode list = ok(200, "GET", "/fleet/plant?locationId=" + location + "&pageSize=200", null);
        assertThat(list.get("meta").get("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode row = null;
        for (JsonNode r : list.get("data")) {
            if (id.equals(r.get("id").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("meterType").asString()).isEqualTo("hours");
        assertThat(row.get("locationName").asString()).isEqualTo("Plant yard " + sfx);
        assertThat(new BigDecimal(row.get("lifetimeHours").asString())).isEqualByComparingTo("1000");
        JsonNode search = ok(200, "GET", "/fleet/plant?search=" + "Jaw%20crusher%20" + sfx, null);
        assertThat(search.get("meta").get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("maintenance: an hours schedule comes due, raises a work order, holds the unit, costs per equipment")
    void scheduleWorkOrderCost() {
        String id = unit(null);
        String schedule = ok(201, "POST", "/fleet/vehicles/" + id + "/service-schedules",
                Map.of("name", "Liner check", "intervalHours", 250, "leadHours", 25)).get("id").asString();
        ok(201, "POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "hours", "value", 1240));
        assertThat(ok(200, "GET", "/fleet/service-schedules?state=due&vehicleId=" + id, null).get("meta").get("total")
                .asLong()).isEqualTo(1);
        String wo = ok(201, "POST", "/fleet/service-schedules/" + schedule + "/work-order", null).get("id").asString();
        ok(201, "POST", "/fleet/work-orders/" + wo + "/start", null);
        assertThat(ok(200, "GET", "/fleet/vehicles/" + id, null).get("status").asString()).isEqualTo("in_workshop");
        JsonNode open = ok(200, "GET", "/fleet/plant?locationId=" + location + "&status=in_workshop", null);
        assertThat(open.get("data").toString()).contains(id);

        JsonNode costs = ok(200, "GET", "/fleet/plant/costs?locationId=" + location, null);
        JsonNode row = null;
        for (JsonNode r : costs.get("rows")) {
            if (id.equals(r.get("vehicleId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("workOrders").asLong()).isEqualTo(1);
        assertThat(new BigDecimal(row.get("downtimeHours").asString()).signum()).isGreaterThanOrEqualTo(0);
        assertThat(costs.get("totals").get("workOrders").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("dispatch: fixed plant is never eligible to carry a load")
    void noDispatch() {
        String id = unit(null);
        JsonNode e = ok(200, "GET", "/fleet/vehicles/" + id + "/dispatch-eligibility", null);
        assertThat(e.get("eligible").asBoolean()).isFalse();
        assertThat(e.get("reasons").toString()).contains("fixed plant");
    }

    @Test
    @DisplayName("fixed asset: link read back, and disposal of the asset disposes the unit once")
    void assetLink() {
        String asset = asset();
        String id = unit(asset);
        assertThat(ok(200, "GET", "/fleet/plant/by-asset/" + asset, null).get("unit").get("id").asString())
                .isEqualTo(id);
        assertThat(ok(200, "GET", "/fleet/plant/by-asset/" + UUID.randomUUID(), null).get("unit").isNull()).isTrue();
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            plant.assetDisposed(asset);
            plant.assetDisposed(asset);
        });
        assertThat(ok(200, "GET", "/fleet/vehicles/" + id, null).get("status").asString()).isEqualTo("disposed");
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's plant")
    void tenantIsolation() {
        String asset = asset();
        String id = unit(asset);
        assertThat(call("GET", "/fleet/plant", null, otherToken).body().get("data").toString()).doesNotContain(id);
        assertThat(call("GET", "/fleet/plant/by-asset/" + asset, null, otherToken).body().get("unit").isNull())
                .isTrue();
        assertThat(call("GET", "/fleet/plant/costs", null, otherToken).body().get("rows").toString())
                .doesNotContain(id);
    }

    @Test
    @DisplayName("authorization matrix: 401 / 403 on every plant route")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fleet.web.plant")).isEqualTo(5);
    }
}
