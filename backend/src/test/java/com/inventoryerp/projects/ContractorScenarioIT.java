package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.BooksGuard;
import com.inventoryerp.fleet.web.projects.FleetProjectTagController;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.projects.web.ContractorController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S075: schedule of values and line-level progress claims (G702 / G703 JSON and PDF), subcontract variations and
 * committed cost by WBS, the plant rate table, fleet trips and work orders charged to a project, overhead absorption
 * runs (idempotent per project and period, reversible), customer site ↔ project and site-scoped price lists; the
 * authorization matrix of {@link ContractorController} and tenant isolation.
 */
@ExtendWith(BooksGuard.class)
class ContractorScenarioIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String checkerToken;
    String customer;
    String supplier;

    @BeforeAll
    void fixtures() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, "Contractor checker " + sfx,
                Permissions.ALL.toArray(String[]::new)).update();
        createUser(demoOrg, "cchecker-" + sfx + "@demo.test", "Checker@12345", role);
        checkerToken = login("DEMO", "cchecker-" + sfx + "@demo.test", "Checker@12345");
        customer = partner("CC", true);
        supplier = partner("CS", false);
    }

    String partner(String tag, boolean isCustomer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, tag + "-" + sfx + "-"
                + UUID.randomUUID().toString().substring(0, 4), tag + " " + sfx, isCustomer, !isCustomer).update();
        return id;
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, "ctr-" + role, permissions).update();
        String email = "ctr-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Ctr@123456", role);
        return login("DEMO", email, "Ctr@123456");
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key(adminToken));
    }

    JsonNode posted(String path, Object body) {
        Res r = post(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    BigDecimal entry(String entryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(entryId, code).query(BigDecimal.class).single();
    }

    /** An active project with a customer and one costable WBS element; returns {projectId, wbsId}. */
    String[] project(String tag, int contractValue) {
        String pid = ok(201, "POST", "/projects", Map.of("code", tag + "-" + sfx, "name", tag + " " + sfx,
                "customerId", customer, "contractValue", contractValue)).get("id").asString();
        String w = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Works")).get("id")
                .asString();
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));
        return new String[] {pid, w};
    }

    void assertWipTied(String pid) {
        JsonNode rep = ok(200, "GET", "/projects/" + pid + "/report", null);
        assertThat(num(rep.get("wipTieOut").get("difference"))).as("WIP ties to the sub-ledger")
                .isEqualByComparingTo("0");
    }

    // ---- 1. schedule of values ---------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-1: schedule of values, variations on lines, line-level claims, certification, G702/G703")
    void scheduleOfValues() {
        String[] p = project("SOV", 100000);
        String pid = p[0];
        String a = ok(201, "POST", "/projects/" + pid + "/sov", Map.of("code", "A", "description", "Substructure",
                "wbsId", p[1], "scheduledValue", 60000)).get("id").asString();
        String b = ok(201, "POST", "/projects/" + pid + "/sov", Map.of("code", "B", "description", "Superstructure",
                "scheduledValue", 40000, "retentionPct", 5)).get("id").asString();
        assertThat(call("POST", "/projects/" + pid + "/sov", Map.of("code", "A", "description", "dup",
                "scheduledValue", 1), adminToken).status()).isEqualTo(409);

        // variations: one adds line C, one raises B (maker ≠ checker)
        String v1 = ok(201, "POST", "/projects/" + pid + "/variations", Map.of("description", "Boundary wall",
                "priceDelta", 10000, "sovCode", "C", "sovDescription", "Boundary wall")).get("id").asString();
        String v2 = ok(201, "POST", "/projects/" + pid + "/variations", Map.of("description", "Extra slab",
                "priceDelta", 5000, "sovLineId", b)).get("id").asString();
        assertThat(call("POST", "/projects/variations/" + v1 + "/approve", null, checkerToken).status()).isEqualTo(200);
        assertThat(call("POST", "/projects/variations/" + v2 + "/approve", null, checkerToken).status()).isEqualTo(200);
        JsonNode sov = ok(200, "GET", "/projects/" + pid + "/sov", null);
        assertThat(sov.get("lines").size()).isEqualTo(3);
        assertThat(num(sov.get("totals").get("currentValue"))).isEqualByComparingTo("115000");

        // claim 1: A at 50 %, B 9 000 done + 2 000 stored (B retention 5 %, others the claim's 10 %)
        assertThat(call("POST", "/projects/" + pid + "/claims", Map.of("cumulativeCertified", 1000, "lines",
                List.of(Map.of("sovLineId", a, "percentComplete", 10))), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/projects/" + pid + "/claims", Map.of("lines", List.of(Map.of("sovLineId", a,
                "workCompletedThisPeriod", 70000))), adminToken).status()).as("beyond the line value").isEqualTo(400);
        JsonNode c1 = ok(201, "POST", "/projects/" + pid + "/claims", Map.of("retentionPct", 10, "lines", List.of(
                Map.of("sovLineId", a, "percentComplete", 50),
                Map.of("sovLineId", b, "workCompletedThisPeriod", 9000, "materialsStored", 2000))));
        assertThat(num(c1.get("cumulativeCertified"))).isEqualByComparingTo("41000");
        assertThat(num(c1.get("thisClaim"))).isEqualByComparingTo("41000");
        assertThat(num(c1.get("retention"))).isEqualByComparingTo("3550"); // 3 000 + 5 % of 11 000
        assertThat(num(c1.get("netAmount"))).isEqualByComparingTo("37450");
        assertThat(c1.get("lines").size()).isEqualTo(3);
        String c1id = c1.get("id").asString();
        JsonNode cert = call("POST", "/projects/claims/" + c1id + "/certify", null, key(adminToken)).body();
        assertThat(cert.get("status").asString()).isEqualTo("certified");
        assertThat(entry(cert.get("journalEntryId").asString(), "1475")).isEqualByComparingTo("3550");
        assertThat(call("PATCH", "/projects/sov/" + a, Map.of("scheduledValue", 1), adminToken).status())
                .as("claimed line value changes only through a variation").isEqualTo(400);
        assertThat(call("DELETE", "/projects/sov/" + a, null, adminToken).status()).isEqualTo(400);

        // claim 2: D carried forward; stored materials on B installed
        JsonNode c2 = ok(201, "POST", "/projects/" + pid + "/claims", Map.of("retentionPct", 10, "lines", List.of(
                Map.of("sovLineId", a, "workCompletedThisPeriod", 6000),
                Map.of("sovLineId", b, "workCompletedThisPeriod", 3000, "materialsStored", 0))));
        assertThat(num(c2.get("cumulativeCertified"))).isEqualByComparingTo("48000");
        assertThat(num(c2.get("thisClaim"))).isEqualByComparingTo("7000");
        assertThat(num(c2.get("retention"))).isEqualByComparingTo("650"); // 600 + 5 % of 1 000
        String c2id = c2.get("id").asString();
        call("POST", "/projects/claims/" + c2id + "/certify", null, key(adminToken));

        JsonNode app = ok(200, "GET", "/projects/claims/" + c2id + "/payment-application", null);
        JsonNode s = app.get("summary");
        assertThat(num(s.get("contractSumToDate"))).isEqualByComparingTo("115000");
        assertThat(num(s.get("totalCompletedAndStored"))).isEqualByComparingTo("48000");
        assertThat(num(s.get("retainage"))).isEqualByComparingTo("4200");
        assertThat(num(s.get("lessPreviousCertificates"))).isEqualByComparingTo("37450");
        assertThat(num(s.get("currentPaymentDue"))).isEqualByComparingTo("6350");
        assertThat(app.get("lines").size()).isEqualTo(3);
        assertThat(num(app.get("totals").get("totalCompleted"))).isEqualByComparingTo("48000");
        var pdf = downloadBytes("/projects/claims/" + c2id + "/payment-application/pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        // a lump-sum claim still works next to it and is shown as one line
        JsonNode c3 = ok(201, "POST", "/projects/" + pid + "/claims", Map.of("cumulativeCertified", 50000));
        assertThat(ok(200, "GET", "/projects/claims/" + c3.get("id").asString() + "/payment-application", null)
                .get("lines").size()).isEqualTo(1);
        ok(200, "POST", "/projects/claims/" + c3.get("id").asString() + "/cancel", null);
    }

    // ---- 2. committed cost ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-2: committed = purchasing by WBS + unvalued subcontract balance after approved variations")
    void committedByWbs() {
        String[] p = project("COM", 200000);
        String pid = p[0];
        String w1 = p[1];
        String w2 = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "2", "name", "Roofing")).get("id")
                .asString();
        String[] other = project("COMX", 1000);
        assertThat(call("POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "Rebar", "quantity", 1, "projectId", pid, "wbsId", other[1]))), adminToken).status())
                .as("WBS of another project").isEqualTo(400);
        JsonNode pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of(
                "description", "Rebar", "quantity", 4, "estimatedUnitPrice", 100, "projectId", pid, "wbsId", w1))));
        String prLine = pr.get("lines").get(0).get("id").asString();
        String account = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? ORDER BY code LIMIT 1")
                .param(demoOrg).query(String.class).single();
        jdbc.sql("INSERT INTO \"Commitment\" (id, \"organizationId\", \"sourceType\", \"sourceId\", \"sourceLineId\","
                + " \"accountId\", \"projectId\", period, amount, \"openAmount\") VALUES (?, ?, 'purchase_request', ?, ?,"
                + " ?, ?, ?, 400, 400) ON CONFLICT DO NOTHING").params(UUID.randomUUID().toString(), demoOrg,
                pr.get("id").asString(), prLine, account, pid, today.substring(0, 7)).update();

        String sc = ok(201, "POST", "/projects/" + pid + "/subcontracts", Map.of("supplierId", supplier, "wbsId", w2,
                "value", 50000, "retentionPct", 5)).get("id").asString();
        String scv = ok(201, "POST", "/projects/subcontracts/" + sc + "/variations", Map.of("description",
                "Extra gutters", "valueDelta", 5000)).get("id").asString();
        assertThat(call("POST", "/projects/subcontract-variations/" + scv + "/approve", null, adminToken).status())
                .as("maker ≠ checker").isEqualTo(403);
        assertThat(call("POST", "/projects/subcontract-variations/" + scv + "/approve", null, checkerToken).status())
                .isEqualTo(200);
        posted("/projects/subcontracts/" + sc + "/valuations", Map.of("cumulativeValue", 20000));
        assertThat(post("/projects/subcontracts/" + sc + "/valuations", Map.of("cumulativeValue", 55001)).status())
                .as("capped at value + approved variations").isEqualTo(400);

        JsonNode rep = ok(200, "GET", "/projects/" + pid + "/report", null);
        JsonNode t = rep.get("totals");
        assertThat(num(t.get("committedPurchasing"))).isEqualByComparingTo("400");
        assertThat(num(t.get("committedSubcontracts"))).isEqualByComparingTo("35000");
        assertThat(num(t.get("committed"))).isEqualByComparingTo("35400");
        assertThat(num(t.get("eac"))).isEqualByComparingTo("55400"); // actual 20 000 + committed
        Map<String, JsonNode> byWbs = new HashMap<>();
        rep.get("wbs").forEach(r -> byWbs.put(r.get("wbsId").isNull() ? "-" : r.get("wbsId").asString(), r));
        assertThat(num(byWbs.get(w1).get("committedPurchasing"))).isEqualByComparingTo("400");
        assertThat(num(byWbs.get(w2).get("committedSubcontracts"))).isEqualByComparingTo("35000");
        assertThat(num(byWbs.get(w2).get("eac"))).isEqualByComparingTo("55000");
        JsonNode com = ok(200, "GET", "/projects/" + pid + "/commitments", null);
        assertThat(num(com.get("total"))).isEqualByComparingTo("35400");
        assertThat(com.get("subcontracts").get(0).get("adjustedValue").asString()).startsWith("55000");
        assertWipTied(pid);
    }

    // ---- 3. plant rates and fleet ----------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-3: plant rate charge, manual-rate permission, fleet trip and work order charged to a project")
    void plantAndFleet() {
        String[] p = project("PLT", 500000);
        String pid = p[0];
        String w = p[1];
        String crane = "CRANE-" + sfx;
        String rate = ok(201, "POST", "/projects/plant-rates", Map.of("equipmentType", "code", "equipmentId", crane,
                "name", "Mobile crane", "unit", "day", "rate", 250000, "validFrom", "2020-01-01")).get("id").asString();
        assertThat(call("POST", "/projects/plant-rates", Map.of("equipmentType", "code", "equipmentId", crane, "unit",
                "day", "rate", 1, "validFrom", "2020-01-01"), adminToken).status()).isEqualTo(409);
        Res charge = post("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "plantId", crane, "quantity", 2));
        assertThat(charge.status()).as(charge.raw().body()).isEqualTo(200); // idempotent route + key → 200 (Nest parity)
        assertThat(num(charge.body().get("amount"))).isEqualByComparingTo("500000");
        String je = charge.body().get("journalEntryId").asString();
        assertThat(entry(je, "1460")).isEqualByComparingTo("500000");
        assertThat(entry(je, "5392")).as("plant recovery credited").isEqualByComparingTo("-500000");
        String poster = tokenWith(Permissions.PROJECT__POST, Permissions.PROJECT__READ);
        Res manual = call("POST", "/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "plantId", crane,
                "quantity", 1, "ratePerHour", 1000), key(poster));
        assertThat(manual.status()).isEqualTo(403);
        assertThat(post("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "plantId", crane, "quantity", 1,
                "ratePerHour", 1000)).status()).as("admin holds project:rate_override").isEqualTo(200);
        assertThat(post("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "plantId", "NOPE-" + sfx,
                "quantity", 1)).status()).isEqualTo(400);
        ok(200, "DELETE", "/projects/plant-rates/" + rate, null);
        assertThat(post("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "plantId", crane, "quantity", 1))
                .status()).as("inactive rate").isEqualTo(400);

        // a vehicle with a per-km rate, its delivered trip to the project's site, and a completed work order
        String category = null;
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                category = c.get("id").asString();
            }
        }
        String vehicle = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", category, "registrationPlate",
                "UPJ " + sfx, "openingOdometer", 1000)).get("id").asString();
        ok(201, "POST", "/projects/plant-rates", Map.of("equipmentType", "vehicle", "equipmentId", vehicle, "unit",
                "km", "rate", 1500, "validFrom", "2020-01-01"));
        String site = ok(201, "POST", "/fleet/customer-sites", Map.of("partnerId", customer, "name", "Job site " + sfx,
                "projectId", pid)).get("id").asString();
        assertThat(ok(200, "GET", "/fleet/customer-sites/" + site, null).get("projectId").asString()).isEqualTo(pid);
        String yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Yard', 'storage_room', now())").params(yard, demoOrg, "PJY-" + sfx).update();
        String order = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"siteId\","
                + " \"projectId\", \"locationId\", \"requestedDate\") VALUES (?, ?, ?, ?, ?, ?, ?, current_date)")
                .params(order, demoOrg, "DO-PJ-" + sfx, customer, site, pid, yard).update();
        String trip = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence,"
                + " \"plannedDate\", status, carrier, \"vehicleId\", \"locationId\", \"distanceKm\", \"dispatchedAt\","
                + " \"deliveredAt\") VALUES (?, ?, ?, ?, 1, current_date, 'delivered', 'own', ?, ?, 40, now() - interval"
                + " '2 hours', now())").params(trip, demoOrg, "TRP-PJ-" + sfx, order, vehicle, yard).update();
        JsonNode cands = ok(200, "GET", "/projects/" + pid + "/costs/fleet", null);
        assertThat(cands.get("trips").toString()).contains(trip);
        Res tc = post("/projects/" + pid + "/costs/fleet", Map.of("sourceType", "trip", "sourceId", trip, "wbsId", w));
        assertThat(tc.status()).as(tc.raw().body()).isEqualTo(200);
        assertThat(num(tc.body().get("amount"))).isEqualByComparingTo("60000"); // 40 km × 1 500
        assertThat(tc.body().get("basis").asString()).isEqualTo("plant_rate_km");
        assertThat(post("/projects/" + pid + "/costs/fleet", Map.of("sourceType", "trip", "sourceId", trip, "wbsId",
                w)).status()).as("a trip is charged once").isEqualTo(409);

        String wo = ok(201, "POST", "/fleet/work-orders", Map.of("vehicleId", vehicle, "type", "corrective", "title",
                "Hydraulic hose on site " + sfx)).get("id").asString();
        assertThat(call("PUT", "/fleet/work-orders/" + wo + "/project", Map.of("projectId", pid, "wbsId", w),
                adminToken).status()).isEqualTo(200);
        assertThat(post("/projects/" + pid + "/costs/fleet", Map.of("sourceType", "work_order", "sourceId", wo))
                .status()).as("not completed").isEqualTo(400);
        jdbc.sql("INSERT INTO \"FleetWorkOrderLine\" (id, \"organizationId\", \"workOrderId\", \"vehicleId\","
                + " \"lineType\", \"partnerId\", amount) VALUES (?, ?, ?, ?, 'external', ?, 750)").params(
                UUID.randomUUID().toString(), demoOrg, wo, vehicle, supplier).update();
        jdbc.sql("UPDATE \"FleetWorkOrder\" SET status = 'completed', \"completedAt\" = now() WHERE id = ?").param(wo)
                .update();
        Res wc = post("/projects/" + pid + "/costs/fleet", Map.of("sourceType", "work_order", "sourceId", wo));
        assertThat(wc.status()).as(wc.raw().body()).isEqualTo(200);
        assertThat(num(wc.body().get("amount"))).isEqualByComparingTo("750");
        // reversing the charge frees the trip for a new charge
        assertThat(post("/projects/costs/" + tc.body().get("id").asString() + "/reverse", null).status())
                .isEqualTo(200);
        assertThat(post("/projects/" + pid + "/costs/fleet", Map.of("sourceType", "trip", "sourceId", trip, "wbsId",
                w)).status()).isEqualTo(200);
        assertWipTied(pid);
    }

    // ---- 4. overhead ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-4: overhead absorption run per project and period — idempotent, reversible, re-runnable")
    void overheadAbsorption() {
        String[] p = project("OVH", 100000);
        String pid = p[0];
        String w = p[1];
        posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "equipmentRef", "GEN", "hours", 4,
                "ratePerHour", 100)); // 400 direct
        posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "equipmentRef", "GEN", "hours", 1,
                "ratePerHour", 600)); // 1 000 direct in total
        ok(201, "POST", "/projects/overhead-rules", Map.of("projectId", pid, "name", "Site overhead", "basis",
                "direct_cost_pct", "rate", 12.5));
        ok(201, "POST", "/projects/overhead-rules", Map.of("projectId", pid, "name", "Equipment yard", "basis",
                "direct_cost_pct", "costElement", "equipment", "rate", 2));
        assertThat(call("POST", "/projects/overhead-rules", Map.of("name", "bad", "basis", "magic", "rate", 1),
                adminToken).status()).isEqualTo(400);
        String period = today.substring(0, 7);
        JsonNode preview = ok(200, "GET", "/projects/" + pid + "/overhead-runs/preview?period=" + period, null);
        assertThat(num(preview.get("amount"))).isEqualByComparingTo("145"); // 12.5 % + 2 % of 1 000
        JsonNode run = posted("/projects/" + pid + "/overhead-runs", Map.of("period", period));
        assertThat(num(run.get("amount"))).isEqualByComparingTo("145");
        String je = run.get("journalEntryId").asString();
        assertThat(entry(je, "1460")).isEqualByComparingTo("145");
        JsonNode again = posted("/projects/" + pid + "/overhead-runs", Map.of("period", period));
        assertThat(again.get("id").asString()).as("one posted run per project and period")
                .isEqualTo(run.get("id").asString());
        JsonNode all = posted("/projects/overhead-runs", Map.of("period", period));
        assertThat(all.toString()).contains(run.get("id").asString());
        assertThat(post("/projects/" + pid + "/overhead-runs", Map.of("period", "2999-01")).status())
                .isEqualTo(400);
        JsonNode rep = ok(200, "GET", "/projects/" + pid + "/report", null);
        assertThat(num(rep.get("totals").get("actual"))).isEqualByComparingTo("1145");
        assertWipTied(pid);

        JsonNode rev = posted("/projects/overhead-runs/" + run.get("id").asString() + "/reverse", Map.of());
        assertThat(rev.get("status").asString()).isEqualTo("reversed");
        assertThat(post("/projects/overhead-runs/" + run.get("id").asString() + "/reverse", Map.of()).status())
                .isEqualTo(400);
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/report", null).get("totals").get("actual")))
                .isEqualByComparingTo("1000");
        JsonNode rerun = posted("/projects/" + pid + "/overhead-runs", Map.of("period", period));
        assertThat(rerun.get("id").asString()).isNotEqualTo(run.get("id").asString());
        assertThat(ok(200, "GET", "/projects/" + pid + "/overhead-runs", null).size()).isEqualTo(2);
        assertWipTied(pid);
    }

    // ---- 5. supplies side ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-5: site-scoped price list wins over the customer's, applies to its site only")
    void sitePricing() {
        String buyer = partner("SP", true);
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', false,"
                + " 'AVCO'::\"CostingMethod\", 0, 100, now())").params(product, demoOrg, "SP-" + sfx, "Ready-mix " + sfx)
                .update();
        String[] p = project("SUP", 1000);
        String site = ok(201, "POST", "/fleet/customer-sites", Map.of("partnerId", buyer, "name", "Tower " + sfx,
                "projectId", p[0])).get("id").asString();
        String otherSite = ok(201, "POST", "/fleet/customer-sites", Map.of("partnerId", buyer, "name", "Depot " + sfx))
                .get("id").asString();
        String customerList = ok(201, "POST", "/sales/pricelists", Map.of("code", "SPC-" + sfx, "name", "Customer "
                + sfx, "type", "contractor")).get("id").asString();
        ok(201, "POST", "/sales/pricelists/" + customerList + "/items", Map.of("scope", "product", "productId",
                product, "compute", "fixed", "value", 90));
        jdbc.sql("UPDATE \"Partner\" SET \"pricelistId\" = ? WHERE id = ?").params(customerList, buyer).update();
        assertThat(call("POST", "/sales/pricelists", Map.of("code", "SPX-" + sfx, "name", "bad", "type", "contractor",
                "customerSiteId", site, "isDefault", true), adminToken).status()).isEqualTo(400);
        String siteList = ok(201, "POST", "/sales/pricelists", Map.of("code", "SPS-" + sfx, "name", "Tower " + sfx,
                "type", "contractor", "customerSiteId", site)).get("id").asString();
        ok(201, "POST", "/sales/pricelists/" + siteList + "/items", Map.of("scope", "product", "productId", product,
                "compute", "fixed", "value", 80));
        assertThat(price(buyer, product, site)).isEqualByComparingTo("80");
        assertThat(price(buyer, product, otherSite)).isEqualByComparingTo("90");
        assertThat(price(buyer, product, null)).isEqualByComparingTo("90");
        assertThat(price(partner("SQ", true), product, null)).as("list price").isEqualByComparingTo("100");
    }

    BigDecimal price(String partner, String product, String site) {
        Map<String, Object> body = new HashMap<>();
        body.put("partnerId", partner);
        body.put("lines", List.of(Map.of("productId", product, "quantity", 1)));
        if (site != null) {
            body.put("siteId", site);
        }
        return num(ok(200, "POST", "/sales/pricing/resolve", body).get("lines").get(0).get("unitPrice"));
    }

    // ---- 6. authorization and tenancy ----------------------------------------------------------------------------

    @Test
    @DisplayName("CT-6: authorization matrix of the contractor routes; another organisation gets 404")
    void authorizationAndTenancy() {
        assertThat(assertAuthorizationMatrix(ContractorController.class)).isEqualTo(25);
        assertThat(assertAuthorizationMatrix(FleetProjectTagController.class)).isEqualTo(2);
        String[] p = project("TEN", 1000);
        String pid = p[0];
        String line = ok(201, "POST", "/projects/" + pid + "/sov", Map.of("code", "A", "description", "x",
                "scheduledValue", 100)).get("id").asString();
        String rate = ok(201, "POST", "/projects/plant-rates", Map.of("equipmentType", "code", "equipmentId",
                "TEN-" + sfx, "unit", "hour", "rate", 10)).get("id").asString();
        String rule = ok(201, "POST", "/projects/overhead-rules", Map.of("projectId", pid, "name", "x", "basis",
                "labour_hour", "rate", 1)).get("id").asString();
        List<String> leaks = new ArrayList<>();
        Object[][] probes = {
            {"GET", "/projects/" + pid + "/sov", null},
            {"POST", "/projects/" + pid + "/sov", Map.of("code", "Z", "description", "x", "scheduledValue", 1)},
            {"PATCH", "/projects/sov/" + line, Map.of("description", "hijack")},
            {"DELETE", "/projects/sov/" + line, null},
            {"GET", "/projects/" + pid + "/commitments", null},
            {"PATCH", "/projects/plant-rates/" + rate, Map.of("rate", 1)},
            {"DELETE", "/projects/plant-rates/" + rate, null},
            {"GET", "/projects/" + pid + "/costs/fleet", null},
            {"PATCH", "/projects/overhead-rules/" + rule, Map.of("rate", 2)},
            {"DELETE", "/projects/overhead-rules/" + rule, null},
            {"GET", "/projects/" + pid + "/overhead-runs", null},
            {"GET", "/projects/" + pid + "/overhead-runs/preview?period=" + today.substring(0, 7), null},
        };
        for (Object[] probe : probes) {
            Res r = call((String) probe[0], (String) probe[1], probe[2], otherToken);
            if (r.status() != 404) {
                leaks.add(probe[0] + " " + probe[1] + " → " + r.status());
            }
        }
        Res run = call("POST", "/projects/" + pid + "/overhead-runs", Map.of("period", today.substring(0, 7)),
                key(otherToken));
        if (run.status() != 404) {
            leaks.add("POST overhead-runs → " + run.status());
        }
        Res fleet = call("POST", "/projects/" + pid + "/costs/fleet", Map.of("sourceType", "trip", "sourceId", "x"),
                key(otherToken));
        if (fleet.status() != 404) {
            leaks.add("POST costs/fleet → " + fleet.status());
        }
        assertThat(leaks).isEmpty();
        assertThat(call("GET", "/projects/plant-rates", null, otherToken).raw().body()).doesNotContain(rate);
        assertThat(call("GET", "/projects/overhead-rules", null, otherToken).raw().body()).doesNotContain(rule);
    }
}
