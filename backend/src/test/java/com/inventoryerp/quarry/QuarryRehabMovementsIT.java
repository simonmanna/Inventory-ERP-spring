package com.inventoryerp.quarry;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.web.QuarryCostingController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Cost Management CM-5 (ADR-S035, R3-3): quarry areas, IAS 37 rehabilitation — initial / unwind / production /
 * remeasurement / utilisation each on its own GL route with the area dimension, roll-forward = GL — and IFRIC 20
 * stripping assets (assessment, maker-checker, election, strip-ratio split, units-of-production amortisation into ROM).
 */
class QuarryRehabMovementsIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String rom;
    String employee;
    String checkerToken;
    String cash;
    int sites;

    @BeforeAll
    void fixtures() {
        rom = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"salesPrice\", \"costPrice\","
                + " \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 0, 0, true, now())")
                .params(rom, demoOrg, "ROMX-" + sfx, "ROM limestone " + sfx).update();
        employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Neema', 'Supervisor', now())").params(employee, demoOrg, "QR-" + sfx)
                .update();
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String role = jdbc.sql("SELECT \"A\" FROM \"_UserRoles\" WHERE \"B\" = ? LIMIT 1").param(admin)
                .query(String.class).single();
        String email = "qchecker-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'").param(demoOrg)
                .query(String.class).single();
    }

    record Site(String id, String zoneId, String benchId, String materialId) {}

    Site site() {
        String code = "QR" + sfx + (++sites);
        String siteId = ok(201, "POST", "/quarry/sites", Map.of("code", code, "name", "Quarry " + code)).get("id")
                .asString();
        ok(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "ROM-" + code, "name", "ROM pad"));
        String material = ok(201, "POST", "/quarry/materials", Map.of("code", "LS-" + code, "name", "Limestone",
                "rockType", "limestone", "productId", rom)).get("id").asString();
        String pit = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "pit", "code", "P1", "name",
                "Main pit")).get("id").asString();
        String zone = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "zone", "parentId", pit, "code",
                "Z1", "name", "North zone")).get("id").asString();
        String bench = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "bench", "parentId", zone,
                "code", "B1", "name", "Bench 1", "benchHeightM", 8)).get("id").asString();
        return new Site(siteId, zone, bench, material);
    }

    /** A posted extraction of {@code tonnes} costing {@code cost} on the given area. */
    JsonNode extract(Site s, String zoneId, int tonnes, int cost) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", s.id());
        body.put("zoneId", zoneId);
        body.put("materialId", s.materialId());
        body.put("extractedOn", today);
        body.put("shift", "day");
        body.put("supervisorEmployeeId", employee);
        body.put("loads", List.of(Map.of("method", "survey", "tonnes", tonnes)));
        body.put("resources", List.of());
        body.put("losses", List.of());
        body.put("otherCost", cost);
        body.put("post", true);
        return money("POST", "/quarry/extraction-logs", body);
    }

    Map<String, String> keyed(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String method, String path, Object body) {
        Res r = call(method, path, body, keyed(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    Res refused(String path, Object body) {
        return call("POST", path, body, keyed(adminToken));
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    BigDecimal line(String entryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(entryId, code).query(BigDecimal.class).single();
    }

    JsonNode lastMovement(JsonNode obligation) {
        JsonNode m = obligation.get("movements");
        return m.get(m.size() - 1);
    }

    @Test
    @DisplayName("rehabilitation: five movements on their own routes, area dimension, roll-forward ties to the GL")
    void rehabilitation() {
        Site s = site();
        String ob = ok(201, "POST", "/quarry/rehab-obligations", Map.of("code", "RH-" + sfx + sites, "siteId", s.id(),
                "zoneId", s.zoneId(), "discountRatePct", 10, "estimatePerM2", 2, "description",
                "Backfill and topsoil the north zone")).get("id").asString();
        String path = "/quarry/rehab-obligations/" + ob + "/movements";

        JsonNode o = money("POST", path, Map.of("type", "initial", "amount", 1000));
        String initialLayer = lastMovement(o).get("layerId").asString();
        String je = lastMovement(o).get("journalEntryId").asString();
        assertThat(line(je, "1665")).isEqualByComparingTo("1000");
        assertThat(line(je, "2690")).isEqualByComparingTo("-1000");

        extract(s, s.benchId(), 100, 100); // a bench below the obligation's zone
        o = money("POST", path, Map.of("type", "production", "disturbedM2", 50));
        String productionLayer = lastMovement(o).get("layerId").asString();
        je = lastMovement(o).get("journalEntryId").asString();
        assertThat(line(je, "1396")).as("ROM still on hand: adjunct").isEqualByComparingTo("100");
        assertThat(line(je, "2690")).isEqualByComparingTo("-100");
        assertThat(ok(200, "GET", "/quarry/areas?siteId=" + s.id(), null).toString()).contains("\"disturbedAreaM2\":"
                + "\"50\"");

        o = money("POST", path, Map.of("type", "unwind", "from", today, "date", today));
        je = lastMovement(o).get("journalEntryId").asString();
        assertThat(line(je, "7150")).isEqualByComparingTo("0.30"); // 1100 × 10 % / 365
        assertThat(line(je, "1396")).as("unwinding never reaches inventory").isEqualByComparingTo("0");

        o = money("POST", path, Map.of("type", "remeasurement", "layerId", initialLayer, "amount", -1050));
        je = lastMovement(o).get("journalEntryId").asString();
        assertThat(line(je, "2690")).isEqualByComparingTo("1050");
        assertThat(line(je, "1665")).isEqualByComparingTo("-1000");
        assertThat(line(je, "5368")).as("decrease beyond the asset → P&L").isEqualByComparingTo("-50");

        o = money("POST", path, Map.of("type", "remeasurement", "layerId", productionLayer, "amount", 40));
        je = lastMovement(o).get("journalEntryId").asString();
        assertThat(line(je, "5368")).as("production layer → P&L, not inventory").isEqualByComparingTo("40");
        assertThat(line(je, "1396")).isEqualByComparingTo("0");

        o = money("POST", path, Map.of("type", "remeasurement", "layerId", initialLayer, "amount", 20));
        assertThat(lastMovement(o).get("impairmentIndicator").asBoolean()).isTrue();
        assertThat(line(lastMovement(o).get("journalEntryId").asString(), "1665")).isEqualByComparingTo("20");

        assertThat(refused(path, Map.of("type", "utilisation", "amount", 10000, "creditAccountId", cash)).status())
                .isEqualTo(400);
        o = money("POST", path, Map.of("type", "utilisation", "amount", 30, "creditAccountId", cash));
        assertThat(num(o.get("balance"))).isEqualByComparingTo("80.30");

        long undimensioned = jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" IN (SELECT"
                + " \"journalEntryId\" FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ?) AND (dimensions IS NULL"
                + " OR dimensions->>'quarryAreaId' IS NULL)").param(ob).query(Long.class).single();
        assertThat(undimensioned).as("every rehab line carries the area").isZero();

        JsonNode rf = ok(200, "GET", "/quarry/rehab-roll-forward?from=" + today + "&to=" + today + "&siteId=" + s.id(),
                null).get("rows").get(0);
        assertThat(num(rf.get("closing"))).isEqualByComparingTo("80.30");
        assertThat(num(rf.get("difference"))).isEqualByComparingTo("0");
        assertThat(num(rf.get("initial"))).isEqualByComparingTo("1000");
        assertThat(num(rf.get("utilisation"))).isEqualByComparingTo("-30");

        String first = o.get("movements").get(0).get("id").asString();
        assertThat(refused("/quarry/rehab-movements/" + first + "/reverse", null).status()).isEqualTo(400);
        String last = lastMovement(o).get("id").asString();
        o = money("POST", "/quarry/rehab-movements/" + last + "/reverse", null);
        assertThat(num(o.get("balance"))).isEqualByComparingTo("110.30");
        rf = ok(200, "GET", "/quarry/rehab-roll-forward?from=" + today + "&to=" + today + "&siteId=" + s.id(), null)
                .get("rows").get(0);
        assertThat(num(rf.get("difference"))).isEqualByComparingTo("0");
        assertThat(call("GET", "/quarry/rehab-obligations/" + ob, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("IFRIC 20: assessment, maker-checker, election, waste above the strip ratio, UoP amortisation")
    void stripping() {
        Site s = site();
        Map<String, Object> asset = new HashMap<>(Map.of("code", "SA-" + sfx + sites, "siteId", s.id(), "zoneId",
                s.zoneId(), "expectedOreTonnes", 1000, "expectedWasteTonnes", 2000, "benefitProbable", true,
                "componentIdentified", false, "costMeasurable", true, "assessment",
                "North zone pushback exposes the lower limestone seam"));
        Res bad = as("POST", "/quarry/stripping-assets", asset);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).startsWith("IFRIC 20.9");
        asset.put("componentIdentified", true);
        String id = ok(201, "POST", "/quarry/stripping-assets", asset).get("id").asString();
        assertThat(as("POST", "/quarry/stripping-assets/" + id + "/approve", null).status()).isEqualTo(403);
        Res approved = call("POST", "/quarry/stripping-assets/" + id + "/approve", null, checkerToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);

        extract(s, s.zoneId(), 100, 100);
        String expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" ORDER BY a.code"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        Map<String, Object> cap = Map.of("from", today, "to", today, "strippingCost", 500, "wasteTonnes", 400,
                "creditAccountId", expense);
        Res noElection = refused("/quarry/stripping-assets/" + id + "/capitalise", cap);
        assertThat(noElection.status()).isEqualTo(400);
        assertThat(noElection.body().get("message").asString()).contains("elected to go to inventory");

        String policy = ok(201, "POST", "/costing/policies", Map.of("election", "stripping_cost", "scopeType",
                "quarry", "scopeId", s.id(), "value", "stripping_asset", "effectiveFrom", today, "justification",
                "Approved IFRIC 20 assessment of the north zone component")).get("id").asString();
        Res elected = call("POST", "/costing/policies/" + policy + "/approve", null, checkerToken);
        assertThat(elected.status()).as(elected.raw().body()).isEqualTo(200);

        JsonNode a = money("POST", "/quarry/stripping-assets/" + id + "/capitalise", cap);
        // 100 t of ore at the expected 2 : 1 strip ratio explains 200 t of waste; the other 200 t build the asset.
        assertThat(num(a.get("capitalised"))).isEqualByComparingTo("250");
        JsonNode capMove = a.get("movements").get(0);
        assertThat(num(capMove.get("inventoryShare"))).isEqualByComparingTo("250");
        assertThat(line(capMove.get("journalEntryId").asString(), "1660")).isEqualByComparingTo("250");

        a = money("POST", "/quarry/stripping-assets/" + id + "/amortise", Map.of("from", today, "to", today));
        assertThat(num(a.get("amortised"))).isEqualByComparingTo("25"); // 250 × 100 / 1000
        JsonNode amort = a.get("movements").get(1);
        assertThat(line(amort.get("journalEntryId").asString(), "1661")).isEqualByComparingTo("-25");
        assertThat(line(amort.get("journalEntryId").asString(), "1396")).isEqualByComparingTo("25");

        JsonNode kpi = ok(200, "GET", "/quarry/cost-kpis?from=" + today + "&to=" + today + "&siteId=" + s.id(), null);
        assertThat(num(kpi.get("rows").get(0).get("strippingAmortisation"))).isEqualByComparingTo("25");
        assertThat(num(kpi.get("costPerTonne"))).isEqualByComparingTo("1.25"); // (100 + 25) / 100 t

        assertThat(refused("/quarry/stripping-movements/" + capMove.get("id").asString() + "/reverse", null).status())
                .isEqualTo(400);
        a = money("POST", "/quarry/stripping-movements/" + amort.get("id").asString() + "/reverse", null);
        assertThat(num(a.get("amortised"))).isEqualByComparingTo("0");
        assertThat(num(a.get("carrying"))).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("areas: pit level, lifecycle validation; authorization matrix")
    void areasAndAuthorization() {
        Site s = site();
        assertThat(as("POST", "/quarry/zones", Map.of("siteId", s.id(), "kind", "pit", "parentId", s.zoneId(), "code",
                "P2", "name", "Bad pit")).status()).isEqualTo(400);
        assertThat(as("PUT", "/quarry/areas/" + s.zoneId(), Map.of("areaStatus", "mined_out")).status())
                .isEqualTo(400);
        JsonNode z = ok(200, "PUT", "/quarry/areas/" + s.zoneId(), Map.of("areaStatus", "rehabilitating",
                "reservesTonnes", 50000, "oreBodyComponent", "Lower seam"));
        assertThat(z.get("areaStatus").asString()).isEqualTo("rehabilitating");
        assertThat(call("PUT", "/quarry/areas/" + s.zoneId(), Map.of(), otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(QuarryCostingController.class)).isEqualTo(16);
    }
}
