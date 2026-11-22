package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CRM enhancements T2a: bulk actions, lead CSV import and salesperson targets on the CRM dashboard. */
class CrmBulkImportIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String adminId;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, true, true, now())").params(customer, demoOrg, "BI-" + sfx,
                        "Bulk Customer " + sfx, "known-" + sfx.toLowerCase() + "@example.test").update();
    }

    String opportunity(String name) {
        return ok(201, "POST", "/crm/opportunities", Map.of("name", name + " " + sfx, "partnerId", customer, "amount",
                100)).get("id").asString();
    }

    @Test
    @DisplayName("bulk assign and archive go record by record: a won deal is refused, the others go through")
    void bulkAssignAndArchive() {
        String a = opportunity("Bulk A");
        String b = opportunity("Bulk B");
        String won = opportunity("Bulk Won");
        ok(200, "POST", "/crm/opportunities/" + won + "/won", null);

        JsonNode assigned = ok(200, "POST", "/crm/opportunities/bulk", Map.of("action", "assign", "ids", List.of(a, b,
                won), "ownerId", adminId));
        assertThat(assigned.get("done").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE id IN (?, ?, ?) AND \"ownerId\" = ?").params(a, b,
                won, adminId).query(Long.class).single()).isEqualTo(3);

        JsonNode archived = ok(200, "POST", "/crm/opportunities/bulk", Map.of("action", "archive", "ids", List.of(a,
                won, b)));
        assertThat(archived.get("done").asInt()).isEqualTo(2);
        assertThat(archived.get("failed")).hasSize(1);
        assertThat(archived.get("failed").get(0).get("id").asString()).isEqualTo(won);
        assertThat(archived.get("failed").get(0).get("error").asString()).isEqualTo(
                "Cannot delete a won deal; archive it instead");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE id IN (?, ?) AND \"deletedAt\" IS NOT NULL").params(
                a, b).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("bulk move needs a stage and records stage history per deal")
    void bulkMove() {
        String a = opportunity("Move A");
        String b = opportunity("Move B");
        assertThat(call("POST", "/crm/opportunities/bulk", Map.of("action", "move", "ids", List.of(a)), adminToken)
                .status()).isEqualTo(400);
        String stage = jdbc.sql("SELECT id FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" AND NOT"
                + " \"isWon\" ORDER BY sequence DESC LIMIT 1").param(demoOrg).query(String.class).single();
        JsonNode moved = ok(200, "POST", "/crm/opportunities/bulk", Map.of("action", "move", "ids", List.of(a, b),
                "stageId", stage));
        assertThat(moved.get("done").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE id IN (?, ?) AND \"stageId\" = ?").params(a, b, stage)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"CrmStageHistory\" WHERE \"dealId\" IN (?, ?) AND \"toStageId\" = ?")
                .params(a, b, stage).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("bulk on leads: lost works, an opportunity id is refused as 'Lead not found', move is not offered")
    void bulkLeads() {
        String lead = ok(201, "POST", "/crm/leads", Map.of("name", "Bulk lead " + sfx)).get("id").asString();
        String opp = opportunity("Not a lead");
        JsonNode lost = ok(200, "POST", "/crm/leads/bulk", Map.of("action", "lost", "ids", List.of(lead, opp),
                "lostNote", "No budget"));
        assertThat(lost.get("done").asInt()).isEqualTo(1);
        assertThat(lost.get("failed").get(0).get("error").asString()).isEqualTo("Lead not found");
        assertThat(jdbc.sql("SELECT status FROM \"Deal\" WHERE id = ?").param(lead).query(String.class).single())
                .isEqualTo("lost");
        assertThat(call("POST", "/crm/leads/bulk", Map.of("action", "move", "ids", List.of(lead), "stageId", "x"),
                adminToken).status()).isEqualTo(400);
        List<String> many = IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString()).toList();
        assertThat(call("POST", "/crm/leads/bulk", Map.of("action", "archive", "ids", many), adminToken).status())
                .isEqualTo(400);
        assertThat(call("POST", "/crm/leads/bulk", Map.of("action", "archive", "ids", List.of(lead)), otherToken)
                .body().get("failed").get(0).get("error").asString()).as("other tenant").isEqualTo("Lead not found");
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("lead import: the dry run checks and finds duplicates without writing; the import skips them")
    void importLeads() {
        String known = "known-" + sfx.toLowerCase() + "@example.test";
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row("name", "Import ok " + sfx, "companyName", "Import Co " + sfx, "amount", "1,500", "tags",
                        "NoSuchTag " + sfx, "source", "NoSuchSource " + sfx),
                row("companyName", "No name"),
                row("name", "Bad owner " + sfx, "owner", "nobody-" + sfx.toLowerCase() + "@example.test"),
                row("name", "Known " + sfx, "email", known.toUpperCase()),
                row("name", "Twin one " + sfx, "phone", "+256 701 " + Math.floorMod(sfx.hashCode(), 1000) + "999"),
                row("name", "Twin two " + sfx, "phone", "+256 701 " + Math.floorMod(sfx.hashCode(), 1000) + "999"),
                row("name", "Bad amount " + sfx, "amount", "lots")));

        JsonNode dry = ok(200, "POST", "/crm/leads/import", Map.of("rows", rows, "dryRun", true));
        List<String> statuses = new ArrayList<>();
        dry.get("rows").forEach(r -> statuses.add(r.get("status").asString()));
        assertThat(statuses).containsExactly("ok", "error", "error", "duplicate", "ok", "duplicate", "error");
        assertThat(dry.get("rows").get(0).get("warnings")).hasSize(2);
        assertThat(dry.get("rows").get(1).get("error").asString()).isEqualTo("name should not be empty");
        assertThat(dry.get("rows").get(2).get("error").asString()).startsWith("No user with email");
        List<Object> names = rows.stream().map(r -> r.get("name")).filter(java.util.Objects::nonNull).map(Object.class::cast)
                .toList();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE \"organizationId\" = ? AND name IN (?, ?, ?, ?, ?,"
                + " ?)").params(demoOrg, names.get(0), names.get(1), names.get(2), names.get(3), names.get(4),
                        names.get(5)).query(Long.class).single()).as("dry run writes nothing").isZero();

        JsonNode done = ok(200, "POST", "/crm/leads/import", Map.of("rows", rows, "skipDuplicates", true));
        assertThat(done.get("created").asInt()).isEqualTo(2);
        assertThat(done.get("skipped").asInt()).isEqualTo(2);
        assertThat(done.get("errors").asInt()).isEqualTo(3);
        Map<String, Object> made = jdbc.sql("SELECT kind, amount, \"ownerId\" FROM \"Deal\" WHERE \"organizationId\" ="
                + " ? AND name = ?").params(demoOrg, "Import ok " + sfx).query().singleRow();
        assertThat(made.get("kind")).isEqualTo("lead");
        assertThat(((java.math.BigDecimal) made.get("amount")).compareTo(new java.math.BigDecimal("1500"))).isZero();
        assertThat(made.get("ownerId")).isNotNull();

        assertThat(call("POST", "/crm/leads/import", Map.of("rows", List.of()), adminToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the CRM dashboard shows the caller's salesperson target for the current period")
    void dashboardTargets() {
        String target = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SalesTarget\" (id, \"organizationId\", month, \"salespersonId\", amount, metric)"
                + " VALUES (?, ?, ?, ?, 1000000, 'revenue')").params(target, demoOrg, java.time.LocalDate.now(
                        com.inventoryerp.kernel.common.BusinessZone.get()).withDayOfMonth(1), adminId).update();
        try {
            JsonNode d = ok(200, "GET", "/crm/dashboard?scope=mine", null);
            List<String> ids = new ArrayList<>();
            d.get("salesTargets").forEach(t -> ids.add(t.get("id").asString()));
            assertThat(ids).contains(target);
            JsonNode mine = null;
            for (JsonNode t : d.get("salesTargets")) {
                if (t.get("id").asString().equals(target)) {
                    mine = t;
                }
            }
            assertThat(mine.get("target").asDouble()).isEqualTo(1000000d);
            assertThat(mine.get("metric").asString()).isEqualTo("revenue");
            assertThat(mine.has("percent")).isTrue();
        } finally {
            jdbc.sql("DELETE FROM \"SalesTarget\" WHERE id = ?").param(target).update();
        }
    }
}
