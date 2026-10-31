package com.inventoryerp.quarry;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.quarry.application.QuarryPostingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Quarry 2.2 over HTTP: master data, extraction log → ROM stock with cost (loads × capacity, weighbridge tonnage,
 * haulage, losses, plant / labour / fuel, royalties) and GL balanced + idempotent replay, gross-tonne allocation,
 * reversal, blasting with controlled explosives (authorisation + approval engine + segregation of duties + audit,
 * concurrent issues on scarce stock, reconcile, blast cost absorbed per tonne), reports, tenant isolation, validation
 * and the authorization matrix.
 */
class QuarryIT extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    @Autowired
    QuarryPostingService postings;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String today;
    String romProduct;
    String explosiveProduct;
    String supplier;
    String authority;
    String employee;
    String tipper;
    String excavator;
    String excavatorClass;
    String requesterToken;
    String approverToken;
    String keeperToken;
    int plates;
    int sites;

    @BeforeAll
    void quarryFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        romProduct = product("ROM granite (t)", "ROM");
        explosiveProduct = product("Emulsion explosive (kg)", "EXP");
        supplier = partner("Explosives supplier");
        authority = partner("District council");
        employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Amani', 'Operator', now())").params(employee, demoOrg, "QE-" + sfx)
                .update();
        String tipperClass = category("TIPPER");
        excavatorClass = category("EXCAVATOR");
        tipper = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", tipperClass, "registrationPlate", plate(),
                "payloadCapacityT", 20)).get("id").asString();
        excavator = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", excavatorClass, "registrationPlate",
                plate())).get("id").asString();
        ok(200, "PUT", "/quarry/settings", Map.of("allocationBasis", "net_tonnes", "fuelCostPerLitre", 2.5,
                "labourRatePerHour", 10, "equipmentRatePerHour", 0, "distinctExplosiveIssuer", true));
        JsonNode rates = ok(200, "GET", "/quarry/equipment-rates", null);
        String rateId = null;
        for (JsonNode r : rates) {
            if (excavatorClass.equals(r.path("categoryId").asString(""))) {
                rateId = r.get("id").asString();
            }
        }
        if (rateId == null) {
            ok(201, "POST", "/quarry/equipment-rates", Map.of("categoryId", excavatorClass, "ratePerHour", 120));
        } else {
            ok(200, "PATCH", "/quarry/equipment-rates/" + rateId, Map.of("ratePerHour", 120));
        }
        requesterToken = user("Blaster", "quarry_site:read", "quarry_blasting:read",
                "quarry_blasting:request_explosive");
        approverToken = user("Blasting manager", "quarry_site:read", "quarry_blasting:read",
                "quarry_blasting:approve_explosive");
        keeperToken = user("Magazine keeper", "quarry_site:read", "quarry_blasting:read",
                "quarry_blasting:issue_explosive");
        // Accounts created once, so concurrent first postings never race on them.
        TenantContext.run(TenantStore.organization(demoOrg), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    postings.absorbedAccount();
                    postings.royaltyPayableAccount();
                    postings.blastingExpenseAccount();
                    postings.lossAccount();
                }));
    }

    // ------------------------------------------------------------------ helpers

    String product(String name, String prefix) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"salesPrice\", \"costPrice\","
                + " \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 0, 0, true, now())")
                .params(id, demoOrg, prefix + "-" + sfx + "-" + UUID.randomUUID().toString().substring(0, 4),
                        name + " " + sfx).update();
        return id;
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, false, now())").params(id, demoOrg, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name + " " + sfx).update();
        return id;
    }

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    String plate() {
        return "UQY " + sfx + "-" + (++plates);
    }

    String user(String roleName, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, roleName + " " + sfx + role.substring(0, 4),
                        permissions).update();
        String email = "quarry-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Quarry@12345", role);
        return login("DEMO", email, "Quarry@12345");
    }

    Map<String, String> key(String token, String idem) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", idem);
        return h;
    }

    Res keyed(String method, String path, Object body, String token) {
        return call(method, path, body, key(token, UUID.randomUUID().toString()));
    }

    JsonNode money(String path, Object body) {
        Res r = keyed("POST", path, body, adminToken);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    /** A fresh site with a ROM stockpile (new inventory location), a granite material and a bench. */
    record Site(String id, String code, String stockpileId, String locationId, String materialId, String zoneId) {}

    Site site() {
        String code = "Q" + sfx + (++sites);
        JsonNode s = ok(201, "POST", "/quarry/sites", Map.of("code", code, "name", "Quarry " + code,
                "licenceNumber", "ML-" + code));
        String siteId = s.get("id").asString();
        JsonNode k = ok(201, "POST", "/quarry/stockpiles", Map.of("siteId", siteId, "code", "ROM-" + code,
                "name", "ROM pad"));
        assertThat(k.get("isDefault").asBoolean()).isTrue();
        JsonNode m = ok(201, "POST", "/quarry/materials", Map.of("code", "GR-" + code, "name", "Granite A",
                "rockType", "granite", "grade", "A", "densityTPerM3", 2.7, "productId", romProduct));
        JsonNode zone = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "zone", "code", "Z1",
                "name", "North zone"));
        JsonNode bench = ok(201, "POST", "/quarry/zones", Map.of("siteId", siteId, "kind", "bench", "parentId",
                zone.get("id").asString(), "code", "B1", "name", "Bench 1", "benchHeightM", 9));
        return new Site(siteId, code, k.get("id").asString(), k.get("locationId").asString(), m.get("id").asString(),
                bench.get("id").asString());
    }

    String ticket(String subject, String status, int netKg) {
        String station = jdbc.sql("SELECT id FROM \"WeighbridgeStation\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, "QWB-" + sfx).query(String.class).optional().orElse(null);
        if (station == null) {
            station = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"WeighbridgeStation\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES"
                    + " (?, ?, ?, 'Pit weighbridge', now())").params(station, demoOrg, "QWB-" + sfx).update();
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"WeighbridgeTicket\" (id, \"organizationId\", \"ticketNumber\", \"stationId\","
                + " \"subjectType\", direction, status, \"vehicleId\", \"registrationPlate\", \"grossKg\", \"tareKg\","
                + " \"netKg\", \"completedAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'internal', ?, ?, ?, ?, ?, ?,"
                + " now(), now())").params(id, demoOrg, "WT-" + sfx + "-" + UUID.randomUUID().toString().substring(0, 5),
                station, subject, status, tipper, "UQY-" + UUID.randomUUID().toString().substring(0, 6), netKg + 15000, 15000, "completed".equals(status) ? netKg : null)
                .update();
        return id;
    }

    void assertBalanced(String entryId) {
        Map<String, Object> sums = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        BigDecimal debit = (BigDecimal) sums.get("d");
        assertThat(debit).isPositive();
        assertThat(debit).isEqualByComparingTo((BigDecimal) sums.get("c"));
    }

    BigDecimal lineSum(String entryId, String accountCode, String side) {
        return jdbc.sql("SELECT COALESCE(SUM(l." + side + "), 0) FROM \"JournalLine\" l JOIN \"Account\" a ON a.id ="
                + " l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?").params(entryId, accountCode)
                .query(BigDecimal.class).single();
    }

    BigDecimal stockAt(String locationId, String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, productId, locationId)
                .query(BigDecimal.class).single();
    }

    Map<String, Object> log(Site s, List<Map<String, Object>> loads, List<Map<String, Object>> resources,
            List<Map<String, Object>> losses, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", s.id());
        body.put("zoneId", s.zoneId());
        body.put("materialId", s.materialId());
        body.put("extractedOn", today);
        body.put("shift", "day");
        body.put("supervisorEmployeeId", employee);
        body.put("loads", loads);
        body.put("resources", resources);
        body.put("losses", losses);
        body.putAll(extra);
        return body;
    }

    JsonNode awaitIssue(String issueId, String notStatus) {
        long until = System.currentTimeMillis() + 20_000;
        while (true) {
            outbox.tick();
            JsonNode i = ok(200, "GET", "/quarry/explosive-issues/" + issueId, null);
            if (!notStatus.equals(i.get("status").asString()) || System.currentTimeMillis() > until) {
                return i;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return i;
            }
        }
    }

    String approve(JsonNode issue) {
        String requestId = issue.get("approvalRequestId").asString();
        Res r = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"), approverToken);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        JsonNode after = awaitIssue(issue.get("id").asString(), "pending_approval");
        assertThat(after.get("status").asString()).isEqualTo("approved");
        return after.get("id").asString();
    }

    record Magazine(String id, String locationId, String explosiveId) {}

    Magazine magazine(String siteId, int kg, int unitCost) {
        JsonNode g = ok(201, "POST", "/quarry/magazines", Map.of("siteId", siteId, "name", "Magazine " + sfx + sites,
                "licenceNumber", "MAG-LIC-1", "licenceExpiresOn", LocalDate.now().plusYears(1).toString(),
                "capacityKg", 2000, "keeperEmployeeId", employee));
        String loc = g.get("locationId").asString();
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", loc,
                "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", explosiveProduct,
                        "description", "Emulsion", "quantity", kg, "unitCost", unitCost))));
        String explosiveId = jdbc.sql("SELECT id FROM \"QuarryExplosive\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"deletedAt\" IS NULL").params(demoOrg, explosiveProduct).query(String.class)
                .optional().orElse(null);
        if (explosiveId == null) {
            explosiveId = ok(201, "POST", "/quarry/explosives", Map.of("productId", explosiveProduct, "kind",
                    "high_explosive", "hazardClass", "1.1D", "unNumber", "UN0241", "netExplosiveKg", 1))
                    .get("id").asString();
        }
        return new Magazine(g.get("id").asString(), loc, explosiveId);
    }

    String blast(Site s) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("siteId", s.id());
        b.put("zoneId", s.zoneId());
        b.put("materialId", s.materialId());
        b.put("patternType", "staggered");
        b.put("holeCount", 20);
        b.put("holeDiameterMm", 89);
        b.put("averageDepthM", 10);
        b.put("burdenM", 3);
        b.put("spacingM", 3.5);
        b.put("subDrillM", 1);
        b.put("stemmingM", 2.5);
        b.put("drillVehicleId", excavator);
        b.put("drillingCost", 500);
        b.put("otherCost", 100);
        b.put("shotfirerEmployeeId", employee);
        b.put("shotfirerLicenceNo", "SF-77");
        b.put("blastPermitNo", "BP-" + sfx);
        b.put("permitExpiresOn", LocalDate.now().plusDays(30).toString());
        b.put("exclusionZoneM", 500);
        JsonNode blast = ok(201, "POST", "/quarry/blasts", b);
        assertThat(dec(blast.get("drilledMetres"))).isEqualByComparingTo("200");
        assertThat(dec(blast.get("estimatedVolumeM3"))).isEqualByComparingTo("1890");
        assertThat(dec(blast.get("estimatedTonnes"))).isEqualByComparingTo("5103");
        return blast.get("id").asString();
    }

    JsonNode request(String blastId, Magazine g, int kg, String token) {
        Res r = call("POST", "/quarry/explosive-issues", Map.of("blastId", blastId, "magazineId", g.id(), "purpose",
                "Bench blast", "lines", List.of(Map.of("explosiveId", g.explosiveId(), "quantity", kg))), token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.body().get("status").asString()).isEqualTo("pending_approval");
        return r.body();
    }

    // ------------------------------------------------------------------ extraction → ROM stock

    @Test
    @DisplayName("extraction log: loads × capacity + weighbridge − losses → ROM stock at cost; GL balanced; idempotent;"
            + " reports")
    void extractionIntoRomStock() {
        Site s = site();
        ok(201, "POST", "/quarry/royalty-rates", Map.of("name", "Mineral royalty", "kind", "royalty", "siteId", s.id(),
                "basis", "net_tonnes", "ratePerTonne", 3, "authorityPartnerId", authority, "effectiveFrom",
                LocalDate.now().minusDays(30).toString()));
        ok(201, "POST", "/quarry/royalty-rates", Map.of("name", "Council levy", "kind", "levy", "siteId", s.id(),
                "basis", "gross_tonnes", "ratePerTonne", 0.5, "effectiveFrom", LocalDate.now().minusDays(30).toString()));
        ok(201, "POST", "/quarry/royalty-rates", Map.of("name", "Expired levy", "kind", "levy", "siteId", s.id(),
                "ratePerTonne", 99, "effectiveFrom", LocalDate.now().minusDays(60).toString(), "effectiveTo",
                LocalDate.now().minusDays(31).toString()));
        LocalDate first = LocalDate.now(BusinessZone.get()).withDayOfMonth(1);
        ok(201, "POST", "/quarry/extraction-plans", Map.of("name", "Month target", "siteId", s.id(), "periodStart",
                first.toString(), "periodEnd", first.plusMonths(1).minusDays(1).toString(), "targetTonnes", 1000,
                "budgetCostPerTonne", 9));
        String wt = ticket("internal_haulage", "completed", 25500);
        Map<String, Object> body = log(s,
                List.of(Map.of("method", "loads", "truckVehicleId", tipper, "loadCount", 10),
                        Map.of("method", "weighbridge", "weighbridgeTicketId", wt)),
                List.of(Map.of("kind", "equipment", "vehicleId", excavator, "employeeId", employee, "hours", 8,
                                "fuelLitres", 100),
                        Map.of("kind", "labour", "employeeId", employee, "role", "Spotter", "hours", 8)),
                List.of(Map.of("kind", "overburden", "tonnes", 15.5, "reason", "Clay seam")),
                Map.of("otherCost", 20, "otherCostNote", "Water"));
        JsonNode draft = money("/quarry/extraction-logs", body);
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(dec(draft.get("grossTonnes"))).isEqualByComparingTo("225.5");
        assertThat(dec(draft.get("netTonnes"))).isEqualByComparingTo("210");
        assertThat(dec(draft.get("loads").get(0).get("tonnes"))).isEqualByComparingTo("200");
        assertThat(dec(draft.get("loads").get(1).get("tonnes"))).isEqualByComparingTo("25.5");
        // the same ticket cannot be counted twice
        Res dup = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "weighbridge",
                "weighbridgeTicketId", wt)), List.of(), List.of(), Map.of()), adminToken);
        assertThat(dup.status()).isEqualTo(400);
        assertThat(msg(dup)).contains("is already counted on extraction log " + draft.get("code").asString());

        JsonNode preview = ok(200, "GET", "/quarry/extraction-logs/" + id + "/cost-preview", null);
        assertThat(dec(preview.get("totalCost"))).isEqualByComparingTo("2052.75");

        String idem = UUID.randomUUID().toString();
        Res posted = call("POST", "/quarry/extraction-logs/" + id + "/post", null, key(adminToken, idem));
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        JsonNode p = posted.body();
        assertThat(p.get("status").asString()).isEqualTo("posted");
        assertThat(dec(p.get("equipmentCost"))).isEqualByComparingTo("960");
        assertThat(dec(p.get("fuelCost"))).isEqualByComparingTo("250");
        assertThat(dec(p.get("labourCost"))).isEqualByComparingTo("80");
        assertThat(dec(p.get("royaltyCost"))).isEqualByComparingTo("742.75");
        assertThat(dec(p.get("otherCost"))).isEqualByComparingTo("20");
        assertThat(dec(p.get("totalCost"))).isEqualByComparingTo("2052.75");
        assertThat(dec(p.get("unitCost"))).isEqualByComparingTo("9.775");
        assertThat(p.get("costs").size()).isEqualTo(6);
        assertThat(stockAt(s.locationId(), romProduct)).isEqualByComparingTo("210");
        BigDecimal avg = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, romProduct, s.locationId())
                .query(BigDecimal.class).single();
        assertThat(avg).isEqualByComparingTo("9.775");
        String entry = p.get("journalEntryId").asString();
        assertBalanced(entry);
        assertThat(lineSum(entry, "1400", "debit")).isEqualByComparingTo("2052.75");
        assertThat(lineSum(entry, "5361", "credit")).isEqualByComparingTo("1310");
        assertThat(lineSum(entry, "2177", "credit")).isEqualByComparingTo("742.75");
        String partnerOnRoyalty = jdbc.sql("SELECT l.\"partnerId\" FROM \"JournalLine\" l JOIN \"Account\" a ON a.id ="
                + " l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = '2177' AND l.credit = 630")
                .param(entry).query(String.class).single();
        assertThat(partnerOnRoyalty).isEqualTo(authority);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'quarry.extraction.posted'"
                + " AND payload->>'extractionLogId' = ?").param(id).query(Long.class).single()).isEqualTo(1);

        // replay with the same key: same answer, nothing posted twice
        Res replay = call("POST", "/quarry/extraction-logs/" + id + "/post", null, key(adminToken, idem));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("journalEntryId").asString()).isEqualTo(entry);
        assertThat(stockAt(s.locationId(), romProduct)).isEqualByComparingTo("210");
        Res again = keyed("POST", "/quarry/extraction-logs/" + id + "/post", null, adminToken);
        assertThat(again.status()).isEqualTo(400);
        assertThat(msg(again)).isEqualTo("Extraction log " + p.get("code").asString()
                + " is posted and can no longer be posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ? AND status = 'posted'")
                .param(id).query(Long.class).single()).isEqualTo(1);
        Res missingKey = as("POST", "/quarry/extraction-logs/" + id + "/post", null);
        assertThat(missingKey.status()).isEqualTo(400);

        // ROM stockpile view + public stock API
        JsonNode view = ok(200, "GET", "/quarry/stockpiles/" + s.stockpileId(), null);
        assertThat(dec(view.get("onHandTonnes"))).isEqualByComparingTo("210");
        assertThat(view.get("stock").get(0).get("productId").asString()).isEqualTo(romProduct);
        assertThat(view.get("movements").get("data").get(0).get("extractionLogCode").asString())
                .isEqualTo(p.get("code").asString());
        JsonNode rom = ok(200, "GET", "/quarry/rom-stock?siteId=" + s.id(), null);
        assertThat(rom.size()).isEqualTo(1);
        assertThat(dec(rom.get(0).get("onHandTonnes"))).isEqualByComparingTo("210");

        // reports
        JsonNode cpt = ok(200, "GET", "/quarry/reports/cost-per-tonne?siteId=" + s.id(), null);
        assertThat(dec(cpt.get("totals").get("netTonnes"))).isEqualByComparingTo("210");
        assertThat(dec(cpt.get("totals").get("totalCost"))).isEqualByComparingTo("2052.75");
        assertThat(dec(cpt.get("totals").get("costPerTonne"))).isEqualByComparingTo("9.775");
        assertThat(dec(cpt.get("totals").get("lossPct"))).isEqualByComparingTo("6.87");
        assertThat(cpt.get("rows").size()).isEqualTo(1);
        JsonNode roy = ok(200, "GET", "/quarry/reports/royalties?siteId=" + s.id(), null);
        assertThat(roy.get("rows").size()).isEqualTo(2);
        assertThat(dec(roy.get("totals").get("amount"))).isEqualByComparingTo("742.75");
        JsonNode losses = ok(200, "GET", "/quarry/reports/losses?siteId=" + s.id(), null);
        assertThat(dec(losses.get("totals").get("tonnes"))).isEqualByComparingTo("15.5");
        assertThat(losses.get("rows").get(0).get("kind").asString()).isEqualTo("overburden");
        JsonNode target = ok(200, "GET", "/quarry/reports/extraction-vs-target?siteId=" + s.id(), null);
        assertThat(dec(target.get("totals").get("actualTonnes"))).isEqualByComparingTo("210");
        assertThat(dec(target.get("totals").get("achievedPct"))).isEqualByComparingTo("21.0");
        assertThat(dec(target.get("rows").get(0).get("actualCostPerTonne"))).isEqualByComparingTo("9.775");
        assertThat(ok(200, "GET", "/quarry/overview", null).get("month").has("netTonnes")).isTrue();
    }

    @Test
    @DisplayName("reversal: stone back out at its cost, GL reversed (balanced), weighbridge ticket and haulage released")
    void reverseReleasesEvidence() {
        Site s = site();
        String wt = ticket("internal_haulage", "completed", 30000);
        JsonNode haul = ok(201, "POST", "/quarry/haulage-trips", Map.of("siteId", s.id(), "fromZoneId", s.zoneId(),
                "destination", "rom_stockpile", "stockpileId", s.stockpileId(), "hauledOn", today, "shift", "day",
                "truckVehicleId", tipper, "driverEmployeeId", employee, "method", "loads", "loadCount", 5));
        assertThat(dec(haul.get("tonnes"))).isEqualByComparingTo("100");
        JsonNode p = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "weighbridge",
                "weighbridgeTicketId", wt), Map.of("method", "haulage", "haulageId", haul.get("id").asString())),
                List.of(Map.of("kind", "labour", "employeeId", employee, "hours", 4)), List.of(),
                Map.of("post", true)));
        assertThat(p.get("status").asString()).isEqualTo("posted");
        assertThat(dec(p.get("netTonnes"))).isEqualByComparingTo("130");
        assertThat(stockAt(s.locationId(), romProduct)).isEqualByComparingTo("130");
        Res del = as("DELETE", "/quarry/haulage-trips/" + haul.get("id").asString(), null);
        assertThat(del.status()).isEqualTo(400);
        assertThat(msg(del)).contains("is on extraction log " + p.get("code").asString());

        JsonNode r = money("/quarry/extraction-logs/" + p.get("id").asString() + "/reverse", Map.of("reason",
                "Wrong shift"));
        assertThat(r.get("status").asString()).isEqualTo("reversed");
        assertThat(stockAt(s.locationId(), romProduct)).isEqualByComparingTo("0");
        assertBalanced(r.get("reversalEntryId").asString());
        assertThat(lineSum(r.get("reversalEntryId").asString(), "1400", "credit")).isEqualByComparingTo("40");
        // evidence released: the ticket and the haulage can be used again
        JsonNode again = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "weighbridge",
                "weighbridgeTicketId", wt), Map.of("method", "haulage", "haulageId", haul.get("id").asString())),
                List.of(), List.of(), Map.of()));
        assertThat(dec(again.get("grossTonnes"))).isEqualByComparingTo("130");
        Res reverseDraft = keyed("POST", "/quarry/extraction-logs/" + again.get("id").asString() + "/reverse",
                Map.of("reason", "x"), adminToken);
        assertThat(reverseDraft.status()).isEqualTo(400);
        ok(200, "DELETE", "/quarry/extraction-logs/" + again.get("id").asString(), null);
        ok(200, "DELETE", "/quarry/haulage-trips/" + haul.get("id").asString(), null);
    }

    @Test
    @DisplayName("gross-tonne allocation: loss share written off to 5363; capitalised + loss = total")
    void grossTonneAllocation() {
        Site s = site();
        ok(200, "PUT", "/quarry/settings", Map.of("allocationBasis", "gross_tonnes"));
        try {
            JsonNode p = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 120)),
                    List.of(Map.of("kind", "labour", "employeeId", employee, "hours", 10)),
                    List.of(Map.of("kind", "unusable_rock", "tonnes", 20)), Map.of("otherCost", 20, "post", true)));
            assertThat(dec(p.get("totalCost"))).isEqualByComparingTo("120");
            assertThat(dec(p.get("capitalisedCost"))).isEqualByComparingTo("100");
            assertThat(dec(p.get("lossCost"))).isEqualByComparingTo("20");
            assertThat(dec(p.get("unitCost"))).isEqualByComparingTo("1");
            String entry = p.get("journalEntryId").asString();
            assertBalanced(entry);
            assertThat(lineSum(entry, "5363", "debit")).isEqualByComparingTo("20");
            assertThat(lineSum(entry, "1400", "debit")).isEqualByComparingTo("100");
            assertThat(stockAt(s.locationId(), romProduct)).isEqualByComparingTo("100");
        } finally {
            ok(200, "PUT", "/quarry/settings", Map.of("allocationBasis", "net_tonnes"));
        }
    }

    @Test
    @DisplayName("validation: mobile plant only, ticket subject / status, losses vs gross, other site's zone, post right")
    void validation() {
        Site s = site();
        Site other = site();
        Res plant = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 10)),
                List.of(Map.of("kind", "equipment", "vehicleId", tipper, "hours", 2)), List.of(), Map.of()), adminToken);
        assertThat(plant.status()).isEqualTo(400);
        assertThat(msg(plant)).contains("is not mobile plant");
        Res sale = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "weighbridge",
                "weighbridgeTicketId", ticket("sale_delivery", "completed", 10000))), List.of(), List.of(), Map.of()),
                adminToken);
        assertThat(sale.status()).isEqualTo(400);
        assertThat(msg(sale)).contains("is a sale delivery weighing, not internal haulage");
        Res open = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "weighbridge",
                "weighbridgeTicketId", ticket("internal_haulage", "first_weighed", 0))), List.of(), List.of(),
                Map.of()), adminToken);
        assertThat(open.status()).isEqualTo(400);
        assertThat(msg(open)).contains("is not completed (it is first_weighed)");
        Res zone = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 10)),
                List.of(), List.of(), Map.of("zoneId", other.zoneId())), adminToken);
        assertThat(zone.status()).isEqualTo(400);
        assertThat(msg(zone)).contains("belongs to another site");
        JsonNode d = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 10)),
                List.of(), List.of(Map.of("kind", "waste", "tonnes", 10)), Map.of()));
        Res losses = keyed("POST", "/quarry/extraction-logs/" + d.get("id").asString() + "/post", null, adminToken);
        assertThat(losses.status()).isEqualTo(400);
        assertThat(msg(losses)).isEqualTo("Losses (10 t) must be less than the gross tonnes (10 t)");
        Res noKey = as("POST", "/quarry/extraction-logs", log(s, List.of(), List.of(), List.of(), Map.of()));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(msg(noKey)).contains("Idempotency-Key");
        String clerk = user("Pit clerk", "quarry_site:read", "quarry_extraction:read", "quarry_extraction:create");
        Res noPost = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 5)),
                List.of(), List.of(), Map.of("post", true)), clerk);
        assertThat(noPost.status()).isEqualTo(403);
        assertThat(msg(noPost)).isEqualTo("Missing required permission(s): quarry_extraction:post");
        Res child = as("POST", "/quarry/zones", Map.of("siteId", s.id(), "kind", "zone", "parentId", s.zoneId(),
                "name", "Bad"));
        assertThat(child.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ blasting & explosives

    @Test
    @DisplayName("explosives: request → approval engine (no self-approval) → issue (authorised, SoD, idempotent) →"
            + " reconcile; audit; blast cost absorbed per tonne")
    void explosiveIssueAndBlastAbsorption() {
        Site s = site();
        Magazine g = magazine(s.id(), 500, 4);
        assertThat(stockAt(g.locationId(), explosiveProduct)).isEqualByComparingTo("500");
        String blastId = blast(s);

        // admin requests: the engine refuses self-approval, so does the magazine (SoD on issue)
        JsonNode own = request(blastId, g, 10, adminToken);
        Res self = as("POST", "/approvals/" + own.get("approvalRequestId").asString() + "/decide",
                Map.of("status", "approved"));
        assertThat(self.status()).isEqualTo(400);
        assertThat(msg(self)).isEqualTo("You cannot approve a request you created");
        approve(own);
        Res sod = keyed("POST", "/quarry/explosive-issues/" + own.get("id").asString() + "/issue", null, adminToken);
        assertThat(sod.status()).isEqualTo(403);
        assertThat(msg(sod)).startsWith("Segregation of duties");
        ok(200, "POST", "/quarry/explosive-issues/" + own.get("id").asString() + "/cancel", Map.of("reason", "Dup"));

        JsonNode req = request(blastId, g, 100, requesterToken);
        String issueId = req.get("id").asString();
        Res early = keyed("POST", "/quarry/explosive-issues/" + issueId + "/issue", null, keeperToken);
        assertThat(early.status()).isEqualTo(400);
        assertThat(msg(early)).contains("only an approved issue can be issued");
        Res requesterIssues = keyed("POST", "/quarry/explosive-issues/" + issueId + "/issue", null, requesterToken);
        assertThat(requesterIssues.status()).isEqualTo(403);
        assertThat(msg(requesterIssues)).isEqualTo("Missing required permission(s): quarry_blasting:issue_explosive");
        Res fireOpen = as("POST", "/quarry/blasts/" + blastId + "/fire", Map.of());
        assertThat(fireOpen.status()).isEqualTo(400);
        assertThat(msg(fireOpen)).contains("is still open");
        approve(req);

        String idem = UUID.randomUUID().toString();
        Res issued = call("POST", "/quarry/explosive-issues/" + issueId + "/issue", null, key(keeperToken, idem));
        assertThat(issued.status()).as(issued.raw().body()).isEqualTo(200);
        assertThat(issued.body().get("status").asString()).isEqualTo("issued");
        assertThat(dec(issued.body().get("issuedValue"))).isEqualByComparingTo("400");
        assertThat(stockAt(g.locationId(), explosiveProduct)).isEqualByComparingTo("400");
        String issueEntry = issued.body().get("issueJournalEntryId").asString();
        assertBalanced(issueEntry);
        assertThat(lineSum(issueEntry, "6470", "debit")).isEqualByComparingTo("400");
        Res replay = call("POST", "/quarry/explosive-issues/" + issueId + "/issue", null, key(keeperToken, idem));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(stockAt(g.locationId(), explosiveProduct)).isEqualByComparingTo("400");

        JsonNode fired = ok(200, "POST", "/quarry/blasts/" + blastId + "/fire", Map.of("firedAt",
                Instant.now().minusSeconds(60).toString(), "warningSignalAt", Instant.now().minusSeconds(300).toString(),
                "allClearAt", Instant.now().toString(), "vibrationPpvMmS", 4.2, "airblastDb", 118, "weather", "Clear"));
        assertThat(fired.get("status").asString()).isEqualTo("fired");
        Res closeEarly = as("POST", "/quarry/blasts/" + blastId + "/close", null);
        assertThat(closeEarly.status()).isEqualTo(400);
        assertThat(msg(closeEarly)).contains("is not reconciled");

        String lineId = issued.body().get("lines").get(0).get("id").asString();
        Res over = keyed("POST", "/quarry/explosive-issues/" + issueId + "/reconcile", Map.of("lines",
                List.of(Map.of("lineId", lineId, "returnedQty", 101))), keeperToken);
        assertThat(over.status()).isEqualTo(400);
        Res rec = keyed("POST", "/quarry/explosive-issues/" + issueId + "/reconcile", Map.of("lines",
                List.of(Map.of("lineId", lineId, "returnedQty", 10)), "notes", "10 kg unused"), keeperToken);
        assertThat(rec.status()).as(rec.raw().body()).isEqualTo(200);
        assertThat(rec.body().get("status").asString()).isEqualTo("reconciled");
        assertThat(stockAt(g.locationId(), explosiveProduct)).isEqualByComparingTo("410");
        assertBalanced(rec.body().get("returnJournalEntryId").asString());
        List<String> actions = new ArrayList<>();
        for (JsonNode a : ok(200, "GET", "/quarry/explosive-issues/" + issueId, null).get("audit")) {
            if ("QuarryExplosiveIssue".equals(a.get("entity").asString())) {
                actions.add(a.get("action").asString());
            }
        }
        assertThat(actions).containsSubsequence("create", "approve", "issue", "reconcile");

        JsonNode b = ok(200, "GET", "/quarry/blasts/" + blastId, null);
        assertThat(dec(b.get("explosivesCost"))).isEqualByComparingTo("360");
        assertThat(dec(b.get("explosivesKg"))).isEqualByComparingTo("90");
        assertThat(dec(b.get("totalCost"))).isEqualByComparingTo("960");

        JsonNode p = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 1000)),
                List.of(), List.of(), Map.of("blastId", blastId, "post", true)));
        assertThat(dec(p.get("blastingCost"))).isEqualByComparingTo("188.13");
        assertBalanced(p.get("journalEntryId").asString());
        assertThat(dec(ok(200, "GET", "/quarry/blasts/" + blastId, null).get("absorbedCost")))
                .isEqualByComparingTo("188.13");
        assertThat(ok(200, "POST", "/quarry/blasts/" + blastId + "/close", null).get("status").asString())
                .isEqualTo("closed");
        Res afterClose = keyed("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes",
                5)), List.of(), List.of(), Map.of("blastId", blastId, "post", true)), adminToken);
        assertThat(afterClose.status()).isEqualTo(400);
        assertThat(msg(afterClose)).contains("only a fired blast that is not closed");
    }

    @Test
    @DisplayName("concurrency: two approved issues on scarce magazine stock — exactly one is issued, never negative")
    void concurrentExplosiveIssues() throws Exception {
        Site s = site();
        Magazine g = magazine(s.id(), 100, 5);
        String blastId = blast(s);
        String a = approve(request(blastId, g, 60, requesterToken));
        String b = approve(request(blastId, g, 60, requesterToken));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Res>> results = new ArrayList<>();
            for (String id : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    go.await();
                    return keyed("POST", "/quarry/explosive-issues/" + id + "/issue", null, keeperToken);
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Res> f : results) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockAt(g.locationId(), explosiveProduct)).isEqualByComparingTo("40");
        assertThat(jdbc.sql("SELECT count(*) FROM \"QuarryExplosiveIssue\" WHERE \"blastId\" = ? AND status = 'issued'")
                .param(blastId).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ isolation & authorization

    @Test
    @DisplayName("tenant isolation: another organization neither sees nor uses this org's quarry rows")
    void tenantIsolation() {
        Site s = site();
        JsonNode p = money("/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 3)),
                List.of(), List.of(), Map.of()));
        assertThat(call("GET", "/quarry/sites/" + s.id(), null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/quarry/extraction-logs/" + p.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
        JsonNode list = call("GET", "/quarry/sites?search=" + s.code(), null, otherToken).body();
        assertThat(list.get("data").size()).isZero();
        Res use = call("POST", "/quarry/extraction-logs", log(s, List.of(Map.of("method", "survey", "tonnes", 3)),
                List.of(), List.of(), Map.of()), key(otherToken, UUID.randomUUID().toString()));
        assertThat(use.status()).isEqualTo(404);
        assertThat(msg(use)).isEqualTo("Quarry site not found");
        assertThat(call("GET", "/quarry/stockpiles/" + s.stockpileId(), null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization matrix: every quarry route answers 401 anonymous and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.quarry")).isGreaterThanOrEqualTo(70);
    }

    @Test
    @DisplayName("master data: site detail tree, duplicate codes, plans with variance, haulage by weighbridge")
    void masterData() {
        Site s = site();
        JsonNode detail = ok(200, "GET", "/quarry/sites/" + s.id(), null);
        assertThat(detail.get("zones").size()).isEqualTo(2);
        assertThat(detail.get("stockpiles").size()).isEqualTo(1);
        Res dup = as("POST", "/quarry/sites", Map.of("code", s.code(), "name", "Dup"));
        assertThat(dup.status()).isEqualTo(409);
        JsonNode plan = ok(201, "POST", "/quarry/extraction-plans", Map.of("name", "Bench plan", "siteId", s.id(),
                "zoneId", s.zoneId(), "periodStart", today, "periodEnd", today, "targetTonnes", 50));
        assertThat(plan.get("variance").get("status").asString()).isIn("behind", "on_track", "ahead");
        JsonNode haul = ok(201, "POST", "/quarry/haulage-trips", Map.of("siteId", s.id(), "destination", "crusher",
                "hauledOn", today, "shift", "night", "method", "weighbridge", "weighbridgeTicketId",
                ticket("internal_haulage", "completed", 18200)));
        assertThat(dec(haul.get("tonnes"))).isEqualByComparingTo("18.2");
        assertThat(haul.get("truckVehicleId").asString()).isEqualTo(tipper);
        JsonNode trips = ok(200, "GET", "/quarry/haulage-trips?siteId=" + s.id() + "&unassigned=true", null);
        assertThat(dec(trips.get("summary").get("tonnes"))).isEqualByComparingTo("18.2");
        JsonNode lookups = ok(200, "GET", "/quarry/lookups", null);
        assertThat(lookups.get("plant").toString()).contains(excavator);
        assertThat(lookups.get("trucks").toString()).contains(tipper);
        Res std = as("POST", "/quarry/materials", Map.of("name", "Bad", "productId", UUID.randomUUID().toString()));
        assertThat(std.status()).isEqualTo(400);
    }
}
