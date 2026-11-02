package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.web.AbsorptionSettlementController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-4 (ADR-S034, R3-2): governed normal capacity and the cause-based settlement of absorbed vs
 * actual overhead — timing and capacity blockers, low volume expensed, abnormal spending expensed, high volume and
 * favourable spending credited to the period's output layers only, reversal, idempotency, isolation.
 */
@ExtendWith(BooksGuard.class)
class AbsorptionSettlementCausesIT extends MfgTestSupport {

    static final String C = "/costing";
    static final String MFG = "/manufacturing/costing";

    @Autowired
    PostingService posting;

    String checkerToken;
    String expense;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void checker() {
        String role = jdbc.sql("SELECT \"A\" FROM \"_UserRoles\" WHERE \"B\" = ? LIMIT 1").param(adminId)
                .query(String.class).single();
        String email = "checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND a.\"deletedAt\" IS"
                + " NULL AND a.id <> ? ORDER BY a.code LIMIT 1").params(demoOrg, overheadAccount).query(String.class)
                .single();
        classify(expense, "production_capitalisable");
    }

    void classify(String accountId, String cls) {
        jdbc.sql("INSERT INTO \"AccountingPolicy\" (id, \"organizationId\", election, \"scopeType\", \"scopeId\", value,"
                + " \"effectiveFrom\", status, justification, \"approvedAt\") VALUES (?, ?, 'cost_eligibility', 'account',"
                + " ?, ?, current_date - 1, 'approved', 'test classification', now() - interval '1 day')")
                .params(UUID.randomUUID().toString(), demoOrg, accountId, cls).update();
    }

    Map<String, String> keyed(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String costCenter() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(id, demoOrg, "CC" + suffix(), "Plant " + suffix()).update();
        return id;
    }

    void spend(String account, String costCenter, int amount) {
        String cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .execute(st -> posting.post(PostingRequest.of("GEN", Instant.now(), "Plant running cost", "test",
                        UUID.randomUUID().toString(), null, List.of(new Line(account, amount, null, null, "Cost", null,
                                costCenter, null), Line.credit(cash, amount, "Cost"))))));
    }

    record Plant(String wc, String cc, String orderId, String output) {}

    /** A work centre with rates 10 / 20 / 5 per hour and a one-unit order that ran two hours. */
    Plant plant(boolean absorbAndComplete) {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "SET-" + suffix(), "name", "Press"))
                .get("id").asString();
        String input = product("SETIN");
        String output = product("SETOUT");
        stockIn(bakery, input, 10, 10);
        stockIn(warehouse, output, 3, 10); // an older finished-goods layer that settlement must not touch
        String bomId = bom(output, input, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Press line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Press", "workCenterId", wc, "durationMins", 60))));
        ok(200, "PUT", MFG + "/rates/" + wc, Map.of("labourRatePerHour", 10, "machineRatePerHour", 20,
                "overheadRatePerHour", 5));
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null)
                .get(0).get("id").asString();
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 120));
        if (absorbAndComplete) {
            Res r = call("POST", MFG + "/orders/" + orderId + "/absorb", null, keyed(adminToken));
            assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
            ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 1));
        }
        return new Plant(wc, costCenter(), orderId, output);
    }

    /** Propose (admin) and approve (checker) a capacity per 30 days. */
    String capacity(Plant p, int normalHours, int budget, Integer rate) {
        Map<String, Object> body = new HashMap<>(Map.of("workCenterId", p.wc(), "costCenterId", p.cc(),
                "normalCapacity", normalHours, "budgetFixedOverhead", budget, "periodDays", 30, "derivation",
                "Average of the last six months less planned maintenance", "effectiveFrom", today));
        if (rate != null) {
            body.put("fixedOverheadRate", rate);
        }
        String id = ok(201, "POST", C + "/normal-capacities", body).get("id").asString();
        Res self = as("POST", C + "/normal-capacities/" + id + "/approve", null);
        assertThat(self.status()).isEqualTo(403);
        Res approved = call("POST", C + "/normal-capacities/" + id + "/approve", null, checkerToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        return id;
    }

    Map<String, Object> period(Plant p) {
        return new HashMap<>(Map.of("workCenterId", p.wc(), "from", today, "to", today));
    }

    JsonNode preview(Map<String, Object> body) {
        return ok(200, "POST", C + "/absorption-settlements/preview", body);
    }

    BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("blockers: no capacity, stale review, unabsorbed cost, unposted absorption, unclassified account")
    void blockers() {
        Plant open = plant(false);
        assertThat(preview(period(open)).get("blockers").toString()).contains("No approved normal capacity");
        String cap = capacity(open, 120, 3000, null);
        String blockers = preview(period(open)).get("blockers").toString();
        assertThat(blockers).contains("conversion cost not absorbed yet");

        Res absorbed = call("POST", MFG + "/orders/" + open.orderId() + "/absorb", null, keyed(adminToken));
        assertThat(absorbed.status()).isEqualTo(200);
        assertThat(preview(period(open)).get("blockers").toString()).contains("absorbed cost that is not posted yet");

        jdbc.sql("UPDATE \"WorkCenterCapacity\" SET \"reviewDue\" = current_date - 1, \"effectiveFrom\" = current_date"
                + " - 30 WHERE id = ?").param(cap).update();
        assertThat(preview(period(open)).get("blockers").toString()).contains("was due for review");

        String unclassified = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\""
                + " AND a.\"deletedAt\" IS NULL AND a.id NOT IN (?, ?) AND NOT EXISTS (SELECT 1 FROM \"AccountingPolicy\""
                + " p WHERE p.\"scopeId\" = a.id) ORDER BY a.code DESC LIMIT 1").params(demoOrg, expense,
                overheadAccount).query(String.class).single();
        spend(unclassified, open.cc(), 10);
        assertThat(preview(period(open)).get("blockers").toString()).contains("has no cost eligibility");
        Res refused = call("POST", C + "/absorption-settlements", period(open), keyed(adminToken));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Settlement blocked:");
    }

    @Test
    @DisplayName("under-absorbed: low volume → 5970, abnormal spending → 5972 after review; reversal nets to zero")
    void underAbsorbed() {
        Plant p = plant(true);
        capacity(p, 120, 3000, null); // per day: normal 4 h, fixed budget 100, rate 25
        spend(expense, p.cc(), 150);
        JsonNode pv = preview(period(p));
        assertThat(num(pv.get("actual"))).isEqualByComparingTo("150");
        assertThat(num(pv.get("absorbed"))).isEqualByComparingTo("70");
        assertThat(num(pv.get("actualActivity"))).isEqualByComparingTo("2");
        assertThat(num(pv.get("volume"))).isEqualByComparingTo("50");
        assertThat(num(pv.get("spending"))).isEqualByComparingTo("30");
        assertThat(pv.get("blockers").toString()).contains("exceeds the tolerance");

        Map<String, Object> body = period(p);
        body.put("classification", "abnormal");
        assertThat(as("POST", C + "/absorption-settlements", body).status()).as("Idempotency-Key").isEqualTo(400);
        Res posted = call("POST", C + "/absorption-settlements", body, keyed(adminToken));
        assertThat(posted.status()).as(posted.raw().body()).isIn(200, 201);
        JsonNode s = posted.body();
        String unabsorbed = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5970'")
                .param(demoOrg).query(String.class).single();
        String abnormal = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5972'")
                .param(demoOrg).query(String.class).single();
        String je = s.get("journalEntryId").asString();
        assertThat(entry(je, unabsorbed)).isEqualByComparingTo("50");
        assertThat(entry(je, abnormal)).isEqualByComparingTo("30");
        assertThat(entry(je, overheadAccount)).isEqualByComparingTo("-80");
        assertThat(s.get("lines")).hasSize(2);

        Res again = call("POST", C + "/absorption-settlements", body, keyed(adminToken));
        assertThat(again.status()).isEqualTo(409);
        JsonNode bridge = ok(200, "GET", C + "/cost-flow-bridge?from=" + today + "&to=" + today, null);
        assertThat(bridge.get("rows").toString()).contains(p.wc());
        for (JsonNode row : bridge.get("rows")) {
            if (row.get("workCenterId").asString().equals(p.wc())) {
                assertThat(num(row.get("unallocated"))).isEqualByComparingTo("0");
            }
        }

        String id = s.get("id").asString();
        assertThat(call("POST", C + "/absorption-settlements/" + id + "/reverse", null, keyed(otherToken)).status())
                .isEqualTo(404);
        Res reversed = call("POST", C + "/absorption-settlements/" + id + "/reverse", null, keyed(adminToken));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        String rev = reversed.body().get("reversalEntryId").asString();
        assertThat(entry(rev, unabsorbed)).isEqualByComparingTo("-50");
        assertThat(entry(rev, overheadAccount)).isEqualByComparingTo("80");
        assertThat(call("POST", C + "/absorption-settlements/" + id + "/reverse", null, keyed(adminToken)).status())
                .isEqualTo(400);
        assertThat(call("GET", C + "/absorption-settlements/" + id, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("over-absorbed: high volume and favourable spending credit only the period's output layer")
    void overAbsorbed() {
        Plant p = plant(true);
        capacity(p, 30, 300, 10); // per day: normal 1 h, fixed budget 10, governed rate 10 = F / N
        spend(expense, p.cc(), 50);
        JsonNode pv = preview(period(p));
        assertThat(num(pv.get("volume"))).isEqualByComparingTo("-10");
        assertThat(num(pv.get("spending"))).isEqualByComparingTo("-10");
        assertThat(num(pv.get("rateError"))).isEqualByComparingTo("0");
        assertThat(pv.get("blockers")).isEmpty();

        Res posted = call("POST", C + "/absorption-settlements", period(p), keyed(adminToken));
        assertThat(posted.status()).as(posted.raw().body()).isIn(200, 201);
        JsonNode s = posted.body();
        String outputLayer = jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND type::text = 'production_output'").params(demoOrg, p.output())
                .query(String.class).single();
        BigDecimal credited = BigDecimal.ZERO;
        for (JsonNode l : s.get("lines")) {
            assertThat(l.get("treatment").asString()).isEqualTo("reduce_inventory");
            assertThat(l.get("layerLedgerId").asString()).isEqualTo(outputLayer);
            assertThat(l.get("target").asString()).isEqualTo("on_hand");
            credited = credited.add(num(l.get("amount")));
        }
        assertThat(credited).isEqualByComparingTo("-20");
        String adjunct = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1396'")
                .param(demoOrg).query(String.class).single();
        assertThat(entry(s.get("journalEntryId").asString(), adjunct)).isEqualByComparingTo("-20");
        assertThat(entry(s.get("journalEntryId").asString(), overheadAccount)).isEqualByComparingTo("20");
        long olderTouched = jdbc.sql("SELECT count(*) FROM \"CostValueAdjustment\" a JOIN \"InventoryLedger\" l ON"
                + " l.id = a.\"layerLedgerId\" WHERE a.\"sourceId\" = ? AND l.type::text <> 'production_output'")
                .param(s.get("id").asString()).query(Long.class).single();
        assertThat(olderTouched).as("older finished-goods layers untouched").isZero();
        BigDecimal valueOnly = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"referenceType\" = 'absorption_settlement' AND \"referenceId\" = ?").param(s.get("id").asString())
                .query(BigDecimal.class).single();
        assertThat(valueOnly).as("value-only row carries the adjunct").isEqualByComparingTo("-20");

        Res reversed = call("POST", C + "/absorption-settlements/" + s.get("id").asString() + "/reverse", null,
                keyed(adminToken));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        BigDecimal net = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " (\"referenceType\" = 'absorption_settlement' AND \"referenceId\" = ?) OR (\"referenceType\" ="
                + " 'reversal' AND \"referenceId\" = ?)").params(s.get("id").asString(), s.get("journalEntryId")
                .asString()).query(BigDecimal.class).single();
        assertThat(net).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("capacity validation and authorization")
    void validationAndAuthorization() {
        Plant p = plant(false);
        Map<String, Object> bad = new HashMap<>(Map.of("workCenterId", p.wc(), "costCenterId", p.cc(),
                "normalCapacity", 10, "derivation", "short", "effectiveFrom", today));
        assertThat(as("POST", C + "/normal-capacities", bad).status()).isEqualTo(400);
        bad.put("derivation", "Design capacity times expected efficiency");
        bad.put("effectiveFrom", "2020-01-01");
        assertThat(as("POST", C + "/normal-capacities", bad).status()).isEqualTo(400);
        bad.put("effectiveFrom", today);
        bad.put("basis", "tonnes");
        assertThat(as("POST", C + "/normal-capacities", bad).status()).isEqualTo(400);
        bad.remove("basis");
        bad.put("reviewDue", LocalDate.now(ZoneOffset.UTC).plusYears(2).toString());
        assertThat(as("POST", C + "/normal-capacities", bad).status()).isEqualTo(400);
        assertThat(call("POST", C + "/normal-capacities", Map.of("workCenterId", p.wc(), "costCenterId", p.cc(),
                "normalCapacity", 10, "derivation", "Design capacity times efficiency", "effectiveFrom", today),
                otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(AbsorptionSettlementController.class)).isEqualTo(9);
    }

    /** Signed (debit − credit) amount of an account in one journal entry. */
    BigDecimal entry(String journalEntryId, String accountId) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND \"accountId\" = ?").params(journalEntryId, accountId)
                .query(BigDecimal.class).single();
    }
}
