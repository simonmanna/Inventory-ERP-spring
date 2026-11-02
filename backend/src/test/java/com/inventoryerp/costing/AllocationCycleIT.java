package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.web.CostAllocationController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-3 (ADR-S033): a 3-step assessment (admin → workshop and plants by headcount, workshop → plants
 * equally) as a financial and a statistical cycle, a reciprocal cycle, idempotency, reversal, refusals, isolation.
 */
@ExtendWith(BooksGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AllocationCycleIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String org;
    String token;
    String userId;
    String expense;
    String admin;
    String workshop;
    String plantA;
    String plantB;
    String headcount;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void books() {
        String code = "al-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Allocation Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        userId = boot.body().get("adminUser").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND a.\"deletedAt\" IS"
                + " NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
        admin = center("ADM", "Administration");
        workshop = center("WSH", "Workshop");
        plantA = center("PLA", "Crusher A");
        plantB = center("PLB", "Block plant");
        post(admin, 1000);
        post(workshop, 600);
        headcount = ok(201, "POST", "/costing/statistical-keys", Map.of("code", "HEADS", "name", "Headcount",
                "unit", "people")).get("id").asString();
        ok(200, "PUT", "/costing/statistical-keys/" + headcount + "/values", Map.of("values", List.of(
                Map.of("receiverId", workshop, "periodFrom", today, "periodTo", today, "value", 1),
                Map.of("receiverId", plantA, "periodFrom", today, "periodTo", today, "value", 3),
                Map.of("receiverId", plantB, "periodFrom", today, "periodTo", today, "value", 1),
                Map.of("receiverId", admin, "periodFrom", today, "periodTo", today, "value", 1))));
    }

    String center(String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(id, org, code, name).update();
        return id;
    }

    void post(String costCenter, int amount) {
        String cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'").param(org)
                .query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(org, userId), () -> new TransactionTemplate(txm).execute(st ->
                posting.post(PostingRequest.of("GEN", Instant.now(), "Running cost", "test",
                        UUID.randomUUID().toString(), null, List.of(new Line(expense, amount, null, null, "Cost", null,
                                costCenter, null), Line.credit(cash, amount, "Cost"))))));
    }

    @Override
    protected JsonNode ok(int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    BigDecimal centre(String costCenter) {
        return jdbc.sql("SELECT coalesce(sum(debit - credit), 0) FROM \"JournalLine\" WHERE \"organizationId\" = ? AND"
                + " \"costCenterId\" = ? AND \"accountId\" = ?").params(org, costCenter, expense)
                .query(BigDecimal.class).single();
    }

    Map<String, Object> seg(String sender, List<String> receivers, String basis, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("senderCostCenterId", sender);
        m.put("senderAccountIds", List.of(expense));
        m.put("receiverIds", receivers);
        m.put("basis", basis);
        if (key != null) {
            m.put("statisticalKeyId", key);
        }
        return m;
    }

    String cycle(String code, String mode, String method, List<Map<String, Object>> segments) {
        return ok(201, "POST", "/costing/allocation-cycles", Map.of("code", code, "name", code, "postingMode", mode,
                "method", method, "segments", segments)).get("id").asString();
    }

    @Test
    @Order(1)
    @DisplayName("financial step-down: admin 1000 by headcount (200/600/200), workshop 800 equally; posted, reversible")
    void financial() {
        String id = cycle("ASSESS", "financial", "sequential", List.of(
                seg(admin, List.of(workshop, plantA, plantB), "headcount", headcount),
                seg(workshop, List.of(plantA, plantB), "equal", null)));
        JsonNode preview = ok(200, "POST", "/costing/allocation-cycles/" + id + "/preview", Map.of("from", today, "to",
                today));
        assertThat(new BigDecimal(preview.get("total").asString())).isEqualByComparingTo("1800");
        assertThat(call("POST", "/costing/allocation-cycles/" + id + "/runs", Map.of("from", today, "to", today),
                token).status()).isEqualTo(400); // Idempotency-Key required

        Res run = keyed("POST", "/costing/allocation-cycles/" + id + "/runs", Map.of("from", today, "to", today));
        assertThat(run.status()).as(run.raw().body()).isIn(200, 201);
        assertThat(run.body().get("journalEntryId").isNull()).isFalse();
        assertThat(run.body().get("lines")).hasSize(5);
        assertThat(centre(admin)).isEqualByComparingTo("0");
        assertThat(centre(workshop)).isEqualByComparingTo("0");
        assertThat(centre(plantA)).isEqualByComparingTo("1000"); // 600 + 400
        assertThat(centre(plantB)).isEqualByComparingTo("600"); // 200 + 400
        // Every line traces to its sender, source account and journal entry.
        for (JsonNode l : run.body().get("lines")) {
            assertThat(l.get("sourceAccountId").asString()).isEqualTo(expense);
            assertThat(l.get("journalEntryId").asString()).isEqualTo(run.body().get("journalEntryId").asString());
        }

        Res again = keyed("POST", "/costing/allocation-cycles/" + id + "/runs", Map.of("from", today, "to", today));
        assertThat(again.status()).isEqualTo(409);
        String runId = run.body().get("id").asString();
        Res reversed = keyed("POST", "/costing/allocation-runs/" + runId + "/reverse", null);
        assertThat(reversed.body().get("status").asString()).as(reversed.raw().body()).isEqualTo("reversed");
        assertThat(centre(admin)).isEqualByComparingTo("1000");
        assertThat(centre(plantA)).isEqualByComparingTo("0");
        assertThat(keyed("POST", "/costing/allocation-runs/" + runId + "/reverse", null).status()).isEqualTo(400);
        assertThat(call("GET", "/costing/allocation-runs/" + runId, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    @DisplayName("statistical: same lines, no journal entry, GL untouched")
    void statistical() {
        String id = cycle("STAT", "statistical", "sequential", List.of(
                seg(admin, List.of(workshop, plantA, plantB), "headcount", headcount),
                seg(workshop, List.of(plantA, plantB), "equal", null)));
        long before = jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single();
        Res run = keyed("POST", "/costing/allocation-cycles/" + id + "/runs", Map.of("from", today, "to", today));
        assertThat(run.status()).as(run.raw().body()).isIn(200, 201);
        assertThat(run.body().get("journalEntryId").isNull()).isTrue();
        assertThat(new BigDecimal(run.body().get("total").asString())).isEqualByComparingTo("1800");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single()).isEqualTo(before);
        assertThat(centre(admin)).isEqualByComparingTo("1000");
    }

    @Test
    @Order(3)
    @DisplayName("reciprocal: admin and workshop serve each other; both end at zero, plants take all 1600")
    void reciprocal() {
        String id = cycle("RECIP", "financial", "reciprocal", List.of(
                seg(admin, List.of(workshop, plantA, plantB), "headcount", headcount),
                seg(workshop, List.of(admin, plantA, plantB), "headcount", headcount)));
        Res run = keyed("POST", "/costing/allocation-cycles/" + id + "/runs", Map.of("from", today, "to", today));
        assertThat(run.status()).as(run.raw().body()).isIn(200, 201);
        assertThat(centre(admin).abs()).isLessThanOrEqualTo(new BigDecimal("0.02"));
        assertThat(centre(workshop).abs()).isLessThanOrEqualTo(new BigDecimal("0.02"));
        assertThat(centre(plantA).add(centre(plantB)).subtract(new BigDecimal("1600")).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.02"));
        keyed("POST", "/costing/allocation-runs/" + run.body().get("id").asString() + "/reverse", null);
    }

    @Test
    @Order(4)
    @DisplayName("refusals: allocate to itself, keyed driver without key, future period")
    void refusals() {
        Res self = call("POST", "/costing/allocation-cycles", Map.of("code", "BAD", "name", "Bad", "segments",
                List.of(seg(admin, List.of(admin), "equal", null))), token);
        assertThat(self.body().get("message").asString()).contains("cannot allocate to itself");
        Res keyless = call("POST", "/costing/allocation-cycles", Map.of("code", "BAD2", "name", "Bad", "segments",
                List.of(seg(admin, List.of(plantA), "machine_hours", null))), token);
        assertThat(keyless.body().get("message").asString()).contains("statistical key");
        String id = cycle("FUT", "statistical", "sequential", List.of(seg(admin, List.of(plantA), "equal", null)));
        assertThat(call("POST", "/costing/allocation-cycles/" + id + "/preview", Map.of("from", today, "to",
                "2999-01-01"), token).body().get("message").asString()).isEqualTo("A run cannot end in the future");
    }

    @Test
    @Order(5)
    @DisplayName("authorization: every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CostAllocationController.class)).isGreaterThan(8);
    }
}
