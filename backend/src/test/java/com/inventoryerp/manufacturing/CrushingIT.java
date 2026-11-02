package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.manufacturing.web.CrushingOperationsController;
import com.inventoryerp.manufacturing.web.CrushingSetupController;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Crushing, screening &amp; aggregate processing (Track C 2.3, ADR-S016) over HTTP: plant + circuit (work centres,
 * routing with a stage set-up), grading profile → system-managed BOM, a crushing run posted through the multi-stage
 * engine (feed out of the ROM stockpile, conversion cost from meters, sized fractions into their stockpiles at joint
 * cost, NRV by-product, unstocked waste, normal vs abnormal loss), GL balanced, idempotent replay, reversal, reports,
 * stockpile surveys with approval (segregation of duties, adjustment + GL, reversal), validation, concurrent posting
 * on scarce feed, tenant isolation and the authorization matrix of the crushing routes.
 */
@ExtendWith(BooksGuard.class)
class CrushingIT extends MfgTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    String today;
    String rom;
    String p20;
    String p10;
    String dust;
    String scalp;
    int sites;

    @BeforeAll
    void crushingFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        rom = product("ROM granite");
        p20 = product("Aggregate 20mm");
        p10 = product("Aggregate 10mm");
        dust = product("Quarry dust 0-6mm");
        scalp = product("Scalpings");
    }

    // ------------------------------------------------------------------ helpers

    record Setup(String siteId, String romPile, String romLoc, String pile20, String loc20, String pile10, String loc10,
            String pileDust, String locDust, String plantId, String profileId, JsonNode plant, JsonNode profile) {}

    JsonNode stockpile(String siteId, String code, String kind) {
        return ok(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", code, "name", code + " pile",
                "kind", kind));
    }

    /** A site with ROM + three product stockpiles, a 3-stage plant and an active profile; ROM stocked at 10 / t. */
    Setup setup(int romTonnes) {
        String code = "CR" + sfx + (++sites);
        String siteId = ok(201, "POST", "/quarry/sites", Map.of("code", code, "name", "Site " + code)).get("id")
                .asString();
        JsonNode romPile = stockpile(siteId, "ROM" + code, "rom");
        JsonNode k20 = stockpile(siteId, "A20" + code, "product");
        JsonNode k10 = stockpile(siteId, "A10" + code, "product");
        JsonNode kd = stockpile(siteId, "DU" + code, "product");
        if (romTonnes > 0) {
            stockIn(romPile.get("locationId").asString(), rom, romTonnes, 10);
        }
        JsonNode plant = ok(201, "POST", "/manufacturing/crushing/plants", plantBody(code, siteId,
                romPile.get("id").asString(), "Screening"));
        String plantId = plant.get("id").asString();
        List<Map<String, Object>> lines = List.of(
                line(p20, "main", null, 40, k20.get("id").asString(), null),
                line(p10, "co_product", null, 25, k10.get("id").asString(), null),
                line(dust, "by_product", null, 20, kd.get("id").asString(), Map.of("valuation", "nrv",
                        "nrvUnitValue", 2)),
                line(scalp, "waste", 1, 8, null, Map.of("stocked", false)));
        JsonNode draft = ok(201, "POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", plantId,
                "name", "Granite split " + code, "feedProductId", rom, "lines", lines));
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        JsonNode active = ok(200, "POST", "/manufacturing/crushing/grading-profiles/" + draft.get("id").asString()
                + "/activate", null);
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(dec(active.get("expectedRecoveryPct"))).isEqualByComparingTo("93");
        return new Setup(siteId, romPile.get("id").asString(), romPile.get("locationId").asString(),
                k20.get("id").asString(), k20.get("locationId").asString(), k10.get("id").asString(),
                k10.get("locationId").asString(), kd.get("id").asString(), kd.get("locationId").asString(), plantId,
                active.get("id").asString(), ok(200, "GET", "/manufacturing/crushing/plants/" + plantId, null), active);
    }

    Map<String, Object> plantBody(String code, String siteId, String feedPile, String lastStage) {
        return Map.of("code", code, "name", "Plant " + code, "siteId", siteId, "feedStockpileId", feedPile,
                "designCapacityTph", 150, "dieselCostPerLitre", 2, "electricityCostPerKwh", 0.2, "labourRatePerHour", 5,
                "stages", List.of(
                        Map.of("name", "Primary crush", "kind", "primary", "capacityTph", 200, "units", List.of(
                                Map.of("code", "FDR", "name", "Grizzly feeder", "kind", "feeder", "meterKind", "none"),
                                Map.of("code", "JAW", "name", "Jaw crusher", "kind", "jaw_crusher", "capacityTph", 200,
                                        "meterKind", "both"))),
                        Map.of("name", "Secondary crush", "kind", "secondary", "units", List.of(
                                Map.of("code", "CONE", "name", "Cone crusher", "kind", "cone_crusher", "meterKind",
                                        "kwh"))),
                        Map.of("name", lastStage, "kind", "screening", "units", List.of(
                                Map.of("code", "SCR", "name", "Triple-deck screen", "kind", "screen", "meterKind",
                                        "kwh"),
                                Map.of("code", "CV1", "name", "Stacker conveyor", "kind", "conveyor")))));
    }

    static Map<String, Object> line(String product, String role, Integer stage, double pct, String pile,
            Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", product);
        m.put("role", role);
        m.put("stageSequence", stage);
        m.put("expectedPct", pct);
        m.put("stockpileId", pile);
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }

    String lineId(Setup s, String product) {
        for (JsonNode l : s.profile().get("lines")) {
            if (product.equals(l.get("productId").asString())) {
                return l.get("id").asString();
            }
        }
        throw new AssertionError("no line");
    }

    String unitId(Setup s, String code) {
        for (JsonNode st : s.plant().get("stages")) {
            for (JsonNode u : st.get("units")) {
                if (code.equals(u.get("code").asString())) {
                    return u.get("id").asString();
                }
            }
        }
        throw new AssertionError("no unit " + code);
    }

    /** 1000 t feed → 20mm 390, 10mm 250, dust 200, scalpings 80 (stage 1) → loss 80; meters, crew, wear parts. */
    Map<String, Object> runBody(Setup s, double feed) {
        Map<String, Object> b = new HashMap<>();
        b.put("plantId", s.plantId());
        b.put("runDate", today);
        b.put("shift", "day");
        b.put("operatorName", "Operator " + sfx);
        b.put("feedTonnes", feed);
        b.put("feedMethod", "belt_scale");
        b.put("outputMethod", "belt_scale");
        b.put("recirculatedTonnes", 120);
        b.put("operatingHours", 8);
        b.put("downtimeHours", 2);
        b.put("downtimeReason", "Liner change");
        b.put("labourHours", 16);
        b.put("outputs", List.of(
                Map.of("gradingLineId", lineId(s, p20), "actualTonnes", 390),
                Map.of("gradingLineId", lineId(s, p10), "actualTonnes", 250),
                Map.of("gradingLineId", lineId(s, dust), "actualTonnes", 200),
                Map.of("gradingLineId", lineId(s, scalp), "actualTonnes", 80)));
        b.put("meters", List.of(
                Map.of("unitId", unitId(s, "JAW"), "hoursStart", 100, "hoursEnd", 108, "dieselLitres", 50),
                Map.of("unitId", unitId(s, "CONE"), "kwhStart", 1000, "kwhEnd", 1500),
                Map.of("unitId", unitId(s, "SCR"), "kwh", 200)));
        b.put("costs", List.of(Map.of("kind", "wear_parts", "unitId", unitId(s, "JAW"), "amount", 300,
                "description", "Jaw liners")));
        return b;
    }

    Map<String, String> key(String token, String idem) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", idem);
        return h;
    }

    Res keyed(String path, Object body, String token) {
        return call("POST", path, body, key(token, UUID.randomUUID().toString()));
    }

    JsonNode money(String path, Object body) {
        Res r = keyed(path, body, adminToken);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode draftRun(Map<String, Object> body) {
        return money("/manufacturing/crushing/runs", body);
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    long unbalancedEntries() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal', 'crushing_survey', 'crushing_survey_reversal')"
                + " GROUP BY je.id HAVING sum(jl.debit) <> sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    String user(String name, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, name + " " + sfx + role.substring(0, 4),
                        permissions).update();
        String email = "crush-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Crush@12345", role);
        return login("DEMO", email, "Crush@12345");
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("plant set-up: stages → routing operations, units → work centres, circuit change → new routing")
    void plantSetup() {
        Setup s = setup(0);
        JsonNode plant = s.plant();
        assertThat(plant.get("stages")).hasSize(3);
        assertThat(plant.get("stages").get(0).get("units")).hasSize(2);
        assertThat(plant.get("circuitVersion").asInt()).isEqualTo(1);
        String routingId = plant.get("routingId").asString();
        JsonNode routing = ok(200, "GET", "/manufacturing/routings/" + routingId + "/stage-setup", null);
        assertThat(routing.get("operations")).hasSize(3);
        assertThat(routing.get("operations").get(0).get("stage").get("issueMode").asString()).isEqualTo("manual");
        String jawWc = plant.get("stages").get(0).get("units").get(1).get("workCenterId").asString();
        assertThat(jdbc.sql("SELECT code FROM \"WorkCenter\" WHERE id = ?").param(jawWc).query(String.class).single())
                .isEqualTo(plant.get("code").asString() + "-JAW");
        // Same circuit saved again → same routing; a renamed stage → a new routing version.
        Map<String, Object> body = new HashMap<>(plantBody(plant.get("code").asString(), s.siteId(), s.romPile(),
                "Screening"));
        JsonNode same = ok(200, "PUT", "/manufacturing/crushing/plants/" + s.plantId(), body);
        assertThat(same.get("routingId").asString()).isEqualTo(routingId);
        JsonNode changed = ok(200, "PUT", "/manufacturing/crushing/plants/" + s.plantId(),
                plantBody(plant.get("code").asString(), s.siteId(), s.romPile(), "Final screening"));
        assertThat(changed.get("routingId").asString()).isNotEqualTo(routingId);
        assertThat(changed.get("circuitVersion").asInt()).isEqualTo(2);
        assertThat(changed.get("stages").get(0).get("units").get(1).get("id").asString())
                .isEqualTo(plant.get("stages").get(0).get("units").get(1).get("id").asString());
        // Duplicate code / unit code.
        Res dup = as("POST", "/manufacturing/crushing/plants", plantBody(plant.get("code").asString(), s.siteId(),
                s.romPile(), "Screening"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/manufacturing/crushing/plants", null).toString()).contains(s.plantId());
        // The system-managed BOM: feed 100 → main 40, co-products with their stockpile locations.
        JsonNode bom = ok(200, "GET", "/manufacturing/boms/" + s.profile().get("bomId").asString() + "/stage-setup",
                null);
        assertThat(dec(bom.get("outputQuantity"))).isEqualByComparingTo("40");
        assertThat(bom.get("coProducts")).hasSize(3);
    }

    @Test
    @DisplayName("grading profile rules: one main fraction, Σ % ≤ 100, drafts only; a new version archives the old")
    void profileRules() {
        Setup s = setup(0);
        Res twoMains = as("POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", s.plantId(), "name",
                "Bad", "feedProductId", rom, "lines", List.of(line(p20, "main", null, 40, s.pile20(), null),
                        line(p10, "main", null, 30, s.pile10(), null))));
        assertThat(twoMains.status()).isEqualTo(400);
        assertThat(msg(twoMains)).isEqualTo("A grading profile needs exactly one main fraction");
        Res over = as("POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", s.plantId(), "name",
                "Bad", "feedProductId", rom, "lines", List.of(line(p20, "main", null, 70, s.pile20(), null),
                        line(p10, "co_product", null, 40, s.pile10(), null))));
        assertThat(msg(over)).isEqualTo("The expected split adds up to 110 %; it cannot exceed 100 %");
        Res feedOut = as("POST", "/manufacturing/crushing/grading-profiles", Map.of("plantId", s.plantId(), "name",
                "Bad", "feedProductId", rom, "lines", List.of(line(rom, "main", null, 70, s.pile20(), null))));
        assertThat(msg(feedOut)).isEqualTo("The feed material cannot also be an output fraction");
        Res edit = as("PUT", "/manufacturing/crushing/grading-profiles/" + s.profileId(), Map.of("plantId",
                s.plantId(), "name", "X", "feedProductId", rom, "lines", List.of(line(p20, "main", null, 50,
                        s.pile20(), null))));
        assertThat(edit.status()).isEqualTo(400);
        JsonNode v2 = ok(201, "POST", "/manufacturing/crushing/grading-profiles/" + s.profileId() + "/new-version",
                null);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("lines")).hasSize(4);
        String oldBom = s.profile().get("bomId").asString();
        ok(200, "POST", "/manufacturing/crushing/grading-profiles/" + v2.get("id").asString() + "/activate", null);
        assertThat(ok(200, "GET", "/manufacturing/crushing/grading-profiles/" + s.profileId(), null).get("status")
                .asString()).isEqualTo("archived");
        assertThat(jdbc.sql("SELECT status::text FROM \"Bom\" WHERE id = ?").param(oldBom).query(String.class).single())
                .isEqualTo("archived");
        // A draft of the old version can no longer be used for a run.
        Map<String, Object> body = runBody(s, 1000);
        body.put("profileId", s.profileId());
        Res stale = keyed("/manufacturing/crushing/runs", body, adminToken);
        assertThat(stale.status()).isEqualTo(400);
        assertThat(msg(stale)).contains("is archived; use the active version");
    }

    @Test
    @DisplayName("run: draft → preview → post (engine, joint cost, NRV dust, abnormal loss, GL) → replay → reverse")
    void runLifecycle() {
        Setup s = setup(2000);
        JsonNode draft = draftRun(runBody(s, 1000));
        String id = draft.get("id").asString();
        assertThat(draft.get("code").asString()).startsWith("CR-");
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        JsonNode meters = draft.get("meters");
        assertThat(meters).hasSize(3);
        JsonNode preview = draft.get("preview");
        assertThat(dec(preview.get("outputTonnes"))).isEqualByComparingTo("920");
        assertThat(dec(preview.get("lossTonnes"))).isEqualByComparingTo("80");
        assertThat(dec(preview.get("actualYieldPct"))).isEqualByComparingTo("92");
        assertThat(dec(preview.get("yieldVariancePct"))).isEqualByComparingTo("-1");
        assertThat(dec(preview.get("feedCost"))).isEqualByComparingTo("10000");
        // diesel 50 × 2 + kWh (500 + 200) × 0.2 + crew 16 × 5 + wear parts 300.
        assertThat(dec(preview.get("conversionCost"))).isEqualByComparingTo("620");
        assertThat(dec(preview.get("throughputTph"))).isEqualByComparingTo("125");
        assertThat(dec(preview.get("availabilityPct"))).isEqualByComparingTo("80");
        assertThat(dec(preview.get("circulatingLoadPct"))).isEqualByComparingTo("12");
        assertThat(preview.get("balanceError").isNull()).isTrue();

        String idem = UUID.randomUUID().toString();
        Res posted = call("POST", "/manufacturing/crushing/runs/" + id + "/post", null, key(adminToken, idem));
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        JsonNode run = posted.body();
        assertThat(run.get("status").asString()).isEqualTo("posted");
        assertThat(run.get("orderCode").asString()).startsWith("MO-");
        // Stock: feed out, fractions in their stockpiles, unstocked scalpings nowhere.
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("1000");
        assertThat(onHand(p20, s.loc20())).isEqualByComparingTo("390");
        assertThat(onHand(p10, s.loc10())).isEqualByComparingTo("250");
        assertThat(onHand(dust, s.locDust())).isEqualByComparingTo("200");
        assertThat(jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(scalp)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        // Cost: feed 10000 + conversion 620; dust at NRV 2 × 200; quantity split of the rest between 20 / 10 mm.
        assertThat(dec(run.get("totalCost"))).isEqualByComparingTo("10620");
        assertThat(dec(run.get("conversionCost"))).isEqualByComparingTo("620");
        BigDecimal v20 = BigDecimal.ZERO;
        BigDecimal u20 = null;
        BigDecimal u10 = null;
        BigDecimal valued = BigDecimal.ZERO;
        for (JsonNode o : run.get("outputs")) {
            String pid = o.get("productId").asString();
            BigDecimal v = o.get("value").isNull() ? BigDecimal.ZERO : dec(o.get("value"));
            valued = valued.add(v);
            if (pid.equals(p20)) {
                v20 = v;
                u20 = dec(o.get("unitCost"));
            } else if (pid.equals(p10)) {
                u10 = dec(o.get("unitCost"));
            } else if (pid.equals(dust)) {
                assertThat(v).isEqualByComparingTo("400");
            } else if (pid.equals(scalp)) {
                assertThat(v).isEqualByComparingTo("0");
            }
        }
        assertThat(v20.signum()).isPositive();
        assertThat(u20).isEqualByComparingTo(u10);
        // Abnormal loss: last stage received 920, normal loss 7 % = 64.4 t, abnormal 15.6 t written off.
        assertThat(dec(run.get("abnormalLossValue")).signum()).isPositive();
        // Values are kept at 6 dp, the run totals at 2 dp.
        assertThat(valued.add(dec(run.get("abnormalLossValue"))).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(dec(run.get("totalCost")));
        assertThat(unbalancedEntries()).isZero();
        assertThat(run.get("journals").size()).isGreaterThan(3);
        assertThat(run.get("costs").size()).isEqualTo(5); // wear parts + diesel + 2 × electricity + labour

        // Idempotent replay: the same response, still one production order; a fresh key is refused.
        Res replay = call("POST", "/manufacturing/crushing/runs/" + id + "/post", null, key(adminToken, idem));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("productionOrderId").asString()).isEqualTo(run.get("productionOrderId")
                .asString());
        Res again = keyed("/manufacturing/crushing/runs/" + id + "/post", null, adminToken);
        assertThat(again.status()).isEqualTo(400);
        assertThat(msg(again)).endsWith("is posted; only drafts can be changed");
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("1000");
        Res edit = as("PUT", "/manufacturing/crushing/runs/" + id, runBody(s, 900));
        assertThat(edit.status()).isEqualTo(400);

        // Reports over the posted run.
        String q = "?from=" + today + "&to=" + today + "&plantId=" + s.plantId();
        JsonNode y = ok(200, "GET", "/manufacturing/crushing/reports/yield" + q, null);
        assertThat(dec(y.get("plants").get(0).get("feedTonnes"))).isEqualByComparingTo("1000");
        assertThat(dec(y.get("plants").get(0).get("actualYieldPct"))).isEqualByComparingTo("92");
        assertThat(y.get("products")).hasSize(4);
        JsonNode cpt = ok(200, "GET", "/manufacturing/crushing/reports/cost-per-tonne" + q, null);
        assertThat(dec(cpt.get("plants").get(0).get("totalCost"))).isEqualByComparingTo("10620");
        assertThat(cpt.get("plants").get(0).get("byKind").size()).isEqualTo(4);
        JsonNode hours = ok(200, "GET", "/manufacturing/crushing/reports/plant-hours" + q, null);
        assertThat(dec(hours.get("plants").get(0).get("throughputTph"))).isEqualByComparingTo("125");
        assertThat(dec(hours.get("plants").get(0).get("utilisationPct"))).isEqualByComparingTo("83.3333");
        assertThat(hours.get("units")).hasSize(3);
        JsonNode list = ok(200, "GET", "/manufacturing/crushing/runs?plantId=" + s.plantId() + "&status=posted", null);
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);
        JsonNode piles = ok(200, "GET", "/manufacturing/crushing/stockpiles?kind=product", null);
        assertThat(piles.toString()).contains(s.pile20());

        // Reversal: stock back, run reversed, GL still balanced.
        Res noKey = as("POST", "/manufacturing/crushing/runs/" + id + "/reverse", Map.of("reason", "Wrong belt scale"));
        assertThat(noKey.status()).isEqualTo(400);
        JsonNode reversed = money("/manufacturing/crushing/runs/" + id + "/reverse", Map.of("reason",
                "Wrong belt scale"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("2000");
        assertThat(onHand(p20, s.loc20())).isEqualByComparingTo("0");
        assertThat(onHand(dust, s.locDust())).isEqualByComparingTo("0");
        assertThat(unbalancedEntries()).isZero();
        assertThat(jdbc.sql("SELECT status::text FROM \"ProductionOrder\" WHERE id = ?")
                .param(run.get("productionOrderId").asString()).query(String.class).single()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("run validation: outputs above the feed, survey % split, future date, unknown unit, short feed")
    void runValidation() {
        Setup s = setup(500);
        Map<String, Object> body = runBody(s, 1000);
        body.put("outputs", List.of(Map.of("gradingLineId", lineId(s, p20), "actualTonnes", 1200)));
        JsonNode over = draftRun(body);
        assertThat(over.get("preview").get("balanceError").asString())
                .isEqualTo("The outputs (1200 t) exceed the feed (1000 t)");
        Res post = keyed("/manufacturing/crushing/runs/" + over.get("id").asString() + "/post", null, adminToken);
        assertThat(post.status()).isEqualTo(400);
        assertThat(msg(post)).isEqualTo("The outputs (1200 t) exceed the feed (1000 t)");
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("500");

        Map<String, Object> pct = runBody(s, 400);
        pct.put("outputMethod", "survey_pct");
        pct.put("outputs", List.of(Map.of("gradingLineId", lineId(s, p20), "actualPct", 45),
                Map.of("gradingLineId", lineId(s, p10), "actualPct", 25)));
        JsonNode bySurvey = draftRun(pct);
        assertThat(dec(bySurvey.get("outputs").get(0).get("actualTonnes"))).isEqualByComparingTo("180");
        assertThat(dec(bySurvey.get("outputs").get(1).get("actualTonnes"))).isEqualByComparingTo("100");

        Map<String, Object> future = runBody(s, 100);
        future.put("runDate", LocalDate.now(BusinessZone.get()).plusDays(2).toString());
        Res f = keyed("/manufacturing/crushing/runs", future, adminToken);
        assertThat(msg(f)).isEqualTo("The run date cannot be in the future");

        Map<String, Object> unit = runBody(s, 100);
        unit.put("meters", List.of(Map.of("unitId", UUID.randomUUID().toString(), "runHours", 2)));
        assertThat(keyed("/manufacturing/crushing/runs", unit, adminToken).status()).isEqualTo(400);
        Map<String, Object> backwards = runBody(s, 100);
        backwards.put("meters", List.of(Map.of("unitId", unitId(s, "JAW"), "hoursStart", 10, "hoursEnd", 9)));
        assertThat(msg(keyed("/manufacturing/crushing/runs", backwards, adminToken)))
                .isEqualTo("The engine-hours end reading is below the start reading");

        // 1000 t asked of a 500 t pile: the engine refuses the issue, nothing moves.
        JsonNode shortRun = draftRun(runBody(s, 1000));
        Res shortPost = keyed("/manufacturing/crushing/runs/" + shortRun.get("id").asString() + "/post", null,
                adminToken);
        assertThat(shortPost.status()).isEqualTo(400);
        assertThat(msg(shortPost)).startsWith("Insufficient stock");
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("500");
        assertThat(ok(200, "GET", "/manufacturing/crushing/runs/" + shortRun.get("id").asString(), null).get("status")
                .asString()).isEqualTo("draft");

        // Drafts can be edited and deleted.
        JsonNode edited = ok(200, "PUT", "/manufacturing/crushing/runs/" + shortRun.get("id").asString(),
                runBody(s, 450));
        assertThat(dec(edited.get("feedTonnes"))).isEqualByComparingTo("450");
        ok(200, "DELETE", "/manufacturing/crushing/runs/" + shortRun.get("id").asString(), null);
        assertThat(as("GET", "/manufacturing/crushing/runs/" + shortRun.get("id").asString(), null).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("concurrent posts on scarce feed: exactly one run gets the stone")
    void concurrentPosts() throws Exception {
        Setup s = setup(1500);
        String a = draftRun(runBody(s, 1000)).get("id").asString();
        String b = draftRun(runBody(s, 1000)).get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (String id : List.of(a, b)) {
            futures.add(pool.submit(() -> {
                go.await();
                return keyed("/manufacturing/crushing/runs/" + id + "/post", null, adminToken);
            }));
        }
        go.countDown();
        int okCount = 0;
        for (Future<Res> f : futures) {
            Res r = f.get();
            if (r.status() == 200) {
                okCount++;
            } else {
                assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(1);
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("500");
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("stockpile survey: volume × density, submit, approval by another person posts the adjustment + GL")
    void survey() {
        Setup s = setup(0);
        stockIn(s.loc10(), p10, 500, 12);
        Map<String, Object> body = Map.of("stockpileId", s.pile10(), "productId", p10, "surveyDate", today,
                "method", "drone", "surveyor", "Survey Co", "volumeM3", 300, "bulkDensity", 1.6);
        JsonNode draft = money("/manufacturing/crushing/stockpile-surveys", body);
        String id = draft.get("id").asString();
        assertThat(draft.get("code").asString()).startsWith("SV-");
        assertThat(dec(draft.get("surveyedTonnes"))).isEqualByComparingTo("480");
        assertThat(dec(draft.get("previewVarianceTonnes"))).isEqualByComparingTo("-20");
        Res early = keyed("/manufacturing/crushing/stockpile-surveys/" + id + "/approve", null, adminToken);
        assertThat(msg(early)).endsWith("is draft and cannot be approved");
        JsonNode submitted = ok(200, "POST", "/manufacturing/crushing/stockpile-surveys/" + id + "/submit", null);
        assertThat(dec(submitted.get("bookTonnes"))).isEqualByComparingTo("500");
        assertThat(dec(submitted.get("variancePct"))).isEqualByComparingTo("-4");
        assertThat(dec(submitted.get("varianceValue"))).isEqualByComparingTo("-240");
        Res self = keyed("/manufacturing/crushing/stockpile-surveys/" + id + "/approve", null, adminToken);
        assertThat(self.status()).isEqualTo(403);
        assertThat(msg(self)).isEqualTo("The person who submitted a stockpile survey cannot approve it");
        String approver = user("Plant manager", "crushing:read", "crushing_survey:approve");
        Res approved = keyed("/manufacturing/crushing/stockpile-surveys/" + id + "/approve", null, approver);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("posted");
        assertThat(approved.body().get("ledgerCode").isNull()).isFalse();
        assertThat(onHand(p10, s.loc10())).isEqualByComparingTo("480");
        assertThat(approved.body().get("journals").size()).isEqualTo(1);
        assertThat(unbalancedEntries()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'crushing_survey'"
                + " AND \"referenceId\" = ?").param(id).query(Long.class).single()).isEqualTo(1);
        JsonNode reversed = call("POST", "/manufacturing/crushing/stockpile-surveys/" + id + "/reverse",
                Map.of("reason", "Wrong density"), key(approver, UUID.randomUUID().toString())).body();
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(p10, s.loc10())).isEqualByComparingTo("500");
        assertThat(unbalancedEntries()).isZero();

        // Reject path and the draft-only edit / delete.
        String other = money("/manufacturing/crushing/stockpile-surveys", body).get("id").asString();
        ok(200, "PUT", "/manufacturing/crushing/stockpile-surveys/" + other, Map.of("stockpileId", s.pile10(),
                "productId", p10, "surveyDate", today, "volumeM3", 310, "bulkDensity", 1.6));
        ok(200, "POST", "/manufacturing/crushing/stockpile-surveys/" + other + "/submit", null);
        JsonNode rejected = call("POST", "/manufacturing/crushing/stockpile-surveys/" + other + "/reject",
                Map.of("reason", "Re-fly the drone"), approver).body();
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(onHand(p10, s.loc10())).isEqualByComparingTo("500");
        JsonNode page = ok(200, "GET", "/manufacturing/crushing/stockpile-surveys?stockpileId=" + s.pile10(), null);
        assertThat(page.get("meta").get("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("tenant isolation: another organization neither sees nor uses this org's crushing rows")
    void tenantIsolation() {
        Setup s = setup(100);
        String run = draftRun(runBody(s, 50)).get("id").asString();
        assertThat(call("GET", "/manufacturing/crushing/plants/" + s.plantId(), null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/manufacturing/crushing/runs/" + run, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/manufacturing/crushing/grading-profiles/" + s.profileId(), null, otherToken)
                .status()).isEqualTo(404);
        assertThat(call("GET", "/manufacturing/crushing/plants", null, otherToken).body().toString())
                .doesNotContain(s.plantId());
        assertThat(call("GET", "/manufacturing/crushing/runs", null, otherToken).body().get("meta").get("total")
                .asInt()).isZero();
        Res post = call("POST", "/manufacturing/crushing/runs/" + run + "/post", null,
                key(otherToken, UUID.randomUUID().toString()));
        assertThat(post.status()).isEqualTo(404);
        assertThat(onHand(rom, s.romLoc())).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("authorization matrix of the 36 crushing routes; an operator records but cannot post")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CrushingSetupController.class, CrushingOperationsController.class))
                .isEqualTo(36);
        Setup s = setup(100);
        String operator = user("Plant operator", "crushing:read", "crushing_run:record");
        Res draft = call("POST", "/manufacturing/crushing/runs", runBody(s, 50), operator);
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(200);
        Res post = keyed("/manufacturing/crushing/runs/" + draft.body().get("id").asString() + "/post", null,
                operator);
        assertThat(post.status()).isEqualTo(403);
    }
}
