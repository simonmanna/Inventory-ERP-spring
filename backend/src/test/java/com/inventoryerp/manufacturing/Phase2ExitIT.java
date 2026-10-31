package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.BusinessZone;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase 2 exit (a): quarry to sized stockpiles over HTTP. A planned blast, haulage into the ROM stockpile, an extraction
 * log posted into ROM stock at its cost per tonne, a crushing run with co-products feeding from that stock into the
 * sized stockpiles, cost per tonne, and GL WIP back to zero with every journal balanced. Firing a blast needs the
 * explosives issue flow, which {@code QuarryIT} covers, so the blast here stays planned. Exit (b) is
 * {@code PrecastManufacturingIT.actualCostPerAcceptableBlock}, exit (c) {@code MultiStageProductionIT.multiLevelTraceability}.
 */
class Phase2ExitIT extends MfgTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 5).toUpperCase();

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    JsonNode pile(String siteId, String code, String kind) {
        return ok(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", code + sfx, "name", code + " pile",
                "kind", kind));
    }

    static Map<String, Object> grading(String product, String role, double pct, String pile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", product);
        m.put("role", role);
        m.put("expectedPct", pct);
        m.put("stocked", pile != null);
        m.put("stockpileId", pile);
        return m;
    }

    String lineId(JsonNode profile, String product) {
        for (JsonNode l : profile.get("lines")) {
            if (product.equals(l.get("productId").asString())) {
                return l.get("id").asString();
            }
        }
        throw new AssertionError("no line");
    }

    long unbalanced() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal') GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    @Test
    @DisplayName("blast → haulage → extraction log → ROM stock → crushing run → sized stockpiles → cost per tonne → GL")
    void quarryToSizedStockpiles() {
        String today = LocalDate.now(BusinessZone.get()).toString();
        String rom = product("E2E ROM");
        String a20 = product("E2E 20mm");
        String a10 = product("E2E 10mm");
        String dust = product("E2E dust");
        String employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Amani', 'Crew', now())").params(employee, demoOrg, "E2E-" + sfx)
                .update();
        String tipper = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", category("TIPPER"), "registrationPlate",
                "UE2E " + sfx, "payloadCapacityT", 20)).get("id").asString();

        String siteId = ok(201, "POST", "/quarry/sites", Map.of("code", "E2E" + sfx, "name", "Quarry " + sfx,
                "licenceNumber", "ML-" + sfx)).get("id").asString();
        JsonNode romPile = pile(siteId, "ROM", "rom");
        String materialId = ok(201, "POST", "/quarry/materials", Map.of("code", "GR" + sfx, "name", "Granite",
                "rockType", "granite", "grade", "A", "densityTPerM3", 2.7, "productId", rom)).get("id").asString();
        String zone = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "zone", "code", "Z1", "name",
                "North zone")).get("id").asString();
        String bench = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "bench", "parentId", zone,
                "code", "B1", "name", "Bench 1", "benchHeightM", 9)).get("id").asString();

        Map<String, Object> blast = new LinkedHashMap<>();
        blast.put("siteId", siteId);
        blast.put("zoneId", bench);
        blast.put("materialId", materialId);
        blast.put("patternType", "staggered");
        blast.put("holeCount", 20);
        blast.put("holeDiameterMm", 89);
        blast.put("averageDepthM", 10);
        blast.put("burdenM", 3);
        blast.put("spacingM", 3.5);
        blast.put("subDrillM", 1);
        blast.put("stemmingM", 2.5);
        blast.put("shotfirerEmployeeId", employee);
        blast.put("shotfirerLicenceNo", "SF-77");
        blast.put("blastPermitNo", "BP-" + sfx);
        blast.put("permitExpiresOn", LocalDate.now().plusDays(30).toString());
        blast.put("exclusionZoneM", 500);
        assertThat(ok(201, "POST", "/quarry/blasts", blast).get("id").asString()).isNotBlank();

        JsonNode haul = ok(201, "POST", "/quarry/haulage-trips", Map.of("siteId", siteId, "fromZoneId", bench,
                "destination", "rom_stockpile", "stockpileId", romPile.get("id").asString(), "hauledOn", today, "shift",
                "day", "truckVehicleId", tipper, "driverEmployeeId", employee, "method", "loads", "loadCount", 100));
        assertThat(dec(haul.get("tonnes"))).isEqualByComparingTo("2000");

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("siteId", siteId);
        log.put("zoneId", bench);
        log.put("materialId", materialId);
        log.put("extractedOn", today);
        log.put("shift", "day");
        log.put("supervisorEmployeeId", employee);
        log.put("loads", List.of(Map.of("method", "haulage", "haulageId", haul.get("id").asString())));
        log.put("resources", List.of(Map.of("kind", "labour", "employeeId", employee, "hours", 20, "ratePerHour", 10)));
        log.put("losses", List.of());
        log.put("post", true);
        JsonNode extraction = money("/quarry/extraction-logs", log);
        assertThat(extraction.get("status").asString()).isEqualTo("posted");
        BigDecimal romUnit = dec(extraction.get("unitCost"));
        assertThat(romUnit).isPositive();
        assertThat(onHand(rom, romPile.get("locationId").asString())).isEqualByComparingTo("2000");

        JsonNode k20 = pile(siteId, "A20", "product");
        JsonNode k10 = pile(siteId, "A10", "product");
        JsonNode kDust = pile(siteId, "DU", "product");
        JsonNode plant = ok(201, "POST", "/manufacturing/crushing/plants", Map.of("code", "PL" + sfx, "name", "Plant " + sfx,
                "siteId", siteId, "feedStockpileId", romPile.get("id").asString(), "designCapacityTph", 150,
                "electricityCostPerKwh", 0.2, "labourRatePerHour", 5, "stages", List.of(
                        Map.of("name", "Primary crush", "kind", "primary", "capacityTph", 200, "units", List.of(
                                Map.of("code", "JAW", "name", "Jaw crusher", "kind", "jaw_crusher", "capacityTph", 200,
                                        "meterKind", "both"))),
                        Map.of("name", "Screening", "kind", "screening", "units", List.of(
                                Map.of("code", "SCR", "name", "Screen", "kind", "screen", "meterKind", "kwh"))))));
        String plantId = plant.get("id").asString();
        JsonNode draftProfile = ok(201, "POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", plantId,
                "name", "Granite split " + sfx, "feedProductId", rom, "lines", List.of(
                        grading(a20, "main", 50, k20.get("id").asString()),
                        grading(a10, "co_product", 30, k10.get("id").asString()),
                        grading(dust, "co_product", 15, kDust.get("id").asString()))));
        JsonNode profile = ok(200, "POST", "/manufacturing/crushing/grading-profiles/" + draftProfile.get("id").asString()
                + "/activate", null);

        BigDecimal wip0 = balance(wipAccount);
        Map<String, Object> run = new HashMap<>();
        run.put("plantId", plantId);
        run.put("runDate", today);
        run.put("shift", "day");
        run.put("feedTonnes", 1000);
        run.put("feedMethod", "belt_scale");
        run.put("outputMethod", "belt_scale");
        run.put("operatingHours", 8);
        run.put("labourHours", 16);
        run.put("outputs", List.of(
                Map.of("gradingLineId", lineId(profile, a20), "actualTonnes", 500),
                Map.of("gradingLineId", lineId(profile, a10), "actualTonnes", 300),
                Map.of("gradingLineId", lineId(profile, dust), "actualTonnes", 150)));
        String runId = money("/manufacturing/crushing/runs", run).get("id").asString();
        JsonNode posted = money("/manufacturing/crushing/runs/" + runId + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");

        // Feed left ROM at its extraction cost; 16 crew hours at 5 are the conversion.
        assertThat(onHand(rom, romPile.get("locationId").asString())).isEqualByComparingTo("1000");
        BigDecimal feedCost = romUnit.multiply(BigDecimal.valueOf(1000));
        assertThat(dec(posted.get("feedCost"))).isEqualByComparingTo(feedCost.setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(dec(posted.get("conversionCost"))).isEqualByComparingTo("80");
        assertThat(onHand(a20, k20.get("locationId").asString())).isEqualByComparingTo("500");
        assertThat(onHand(a10, k10.get("locationId").asString())).isEqualByComparingTo("300");
        assertThat(onHand(dust, kDust.get("locationId").asString())).isEqualByComparingTo("150");

        JsonNode cpt = ok(200, "GET", "/manufacturing/crushing/reports/cost-per-tonne?plantId=" + plantId, null);
        assertThat(cpt.get("products")).hasSize(3);
        assertThat(dec(cpt.get("plants").get(0).get("costPerTonne"))).isPositive();
        JsonNode byPlant = ok(200, "GET", "/manufacturing/costing/reports/summary?groupBy=plant", null);
        assertThat(byPlant.get("rows").toString()).contains("Plant " + sfx);
        assertThat(ok(200, "GET", "/manufacturing/costing/orders/" + posted.get("productionOrderId").asString(), null)
                .get("crushing").get("id").asString()).isEqualTo(runId);

        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(unbalanced()).isZero();
    }
}
