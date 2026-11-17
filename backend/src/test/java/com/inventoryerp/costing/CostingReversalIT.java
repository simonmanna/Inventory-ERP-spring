package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
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

/**
 * Review 2026-11-17 (ACR-6 / ACR-7): a costing run is reversed on its own date while that period is open (so the period
 * nets to zero and the run can be posted again from the right base), never into a locked costing period (the reversal
 * moves to today), and a run cannot overlap a posted run of the same cycle.
 */
class CostingReversalIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String org;
    String token;
    String userId;
    String expense;
    String admin;
    String plant;
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();

    @BeforeAll
    void books() {
        String code = "cr-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Reversal Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        userId = boot.body().get("adminUser").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND a.\"deletedAt\" IS"
                + " NULL ORDER BY a.code LIMIT 1").param(org).query(String.class).single();
        admin = center("ADM", "Administration");
        plant = center("PLA", "Crusher");
        cost(admin, 1000, yesterday);
    }

    String center(String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(id, org, code, name).update();
        return id;
    }

    void cost(String costCenter, int amount, String day) {
        String cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'").param(org)
                .query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(org, userId), () -> new TransactionTemplate(txm).execute(st ->
                posting.post(PostingRequest.of("GEN", java.time.Instant.parse(day + "T10:00:00Z"), "Running cost",
                        "test", UUID.randomUUID().toString(), null, List.of(new Line(expense, amount, null, null,
                                "Cost", null, costCenter, null), Line.credit(cash, amount, "Cost"))))));
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

    String postingDay(String journalEntryId) {
        return jdbc.sql("SELECT \"postingDate\"::date::text FROM \"JournalEntry\" WHERE id = ?").param(journalEntryId)
                .query(String.class).single();
    }

    JsonNode run(String cycle, String from, String to) {
        Res r = keyed("POST", "/costing/allocation-cycles/" + cycle + "/runs", Map.of("from", from, "to", to));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    @Test
    @DisplayName("reversed on the run's date while open; re-run allocates the full pool; overlap refused; a locked"
            + " costing period moves the reversal to today")
    void reversalDatesAndOverlap() {
        Map<String, Object> seg = Map.of("senderCostCenterId", admin, "senderAccountIds", List.of(expense),
                "receiverIds", List.of(plant), "basis", "equal");
        String cycle = call("POST", "/costing/allocation-cycles", Map.of("code", "REV", "name", "Reversal",
                "postingMode", "financial", "method", "sequential", "segments", List.of(seg)), token).body().get("id")
                .asString();
        JsonNode first = run(cycle, yesterday, yesterday);
        assertThat(postingDay(first.get("journalEntryId").asString())).isEqualTo(yesterday);
        assertThat(centre(plant)).isEqualByComparingTo("1000");

        // ACR-7: the same dates keep the verbatim message; an overlapping period names the run
        Res same = keyed("POST", "/costing/allocation-cycles/" + cycle + "/runs", Map.of("from", yesterday, "to",
                yesterday));
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.body().get("message").asString()).isEqualTo("This cycle already has a posted run for "
                + yesterday + " – " + yesterday + "; reverse it first");
        Res overlap = keyed("POST", "/costing/allocation-cycles/" + cycle + "/runs", Map.of("from", yesterday, "to",
                today));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.body().get("message").asString()).isEqualTo("This cycle's posted run "
                + first.get("number").asString() + " (" + yesterday + " – " + yesterday + ") overlaps " + yesterday
                + " – " + today + "; reverse it first");

        // ACR-6: reversed on the run's own date (its period is open), so the period nets to zero
        JsonNode reversed = keyed("POST", "/costing/allocation-runs/" + first.get("id").asString() + "/reverse", null)
                .body();
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(postingDay(reversed.get("reversalEntryId").asString())).isEqualTo(yesterday);
        assertThat(centre(plant)).isEqualByComparingTo("0");
        // the period re-runs from its full pool (a reversal dated today left the period's pool at zero)
        JsonNode second = run(cycle, yesterday, yesterday);
        assertThat(new BigDecimal(second.get("total").asString())).isEqualByComparingTo("1000");
        assertThat(centre(plant)).isEqualByComparingTo("1000");

        // lock the costing period of the run: its reversal moves to today
        assertThat(call("POST", "/costing/profitability/runs", Map.of("from", yesterday, "to", yesterday), token)
                .status()).isEqualTo(201);
        Res locked = call("POST", "/costing/close/locks", Map.of("from", yesterday, "to", yesterday), token);
        assertThat(locked.status()).as(locked.raw().body()).isEqualTo(201);
        JsonNode moved = keyed("POST", "/costing/allocation-runs/" + second.get("id").asString() + "/reverse", null)
                .body();
        assertThat(moved.get("status").asString()).as(moved.toString()).isEqualTo("reversed");
        assertThat(postingDay(moved.get("reversalEntryId").asString())).isEqualTo(today);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingDate\"::date"
                + " = ?::date AND id = ?").params(org, yesterday, moved.get("reversalEntryId").asString())
                .query(Long.class).single()).isZero();
    }
}
