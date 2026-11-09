package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
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
import tools.jackson.databind.JsonNode;

/**
 * Maintenance of the job-costing structure and contract records after creation: edit and delete sites, WBS elements
 * and draft budgets (refused once anything refers to them), the reclass candidate list behind the reclass dialog, and
 * completing / cancelling subcontracts. Plus 404 for another organisation on every new route.
 */
class ProjectStructureIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String supplier;

    @BeforeAll
    void fixtures() {
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, false, true, now())").params(supplier, demoOrg, "PS-" + sfx,
                "Piling " + sfx).update();
    }

    Map<String, String> key() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode posted(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String message(int status, String method, String path, Object body) {
        Res r = call(method, path, body, adminToken);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body().get("message").asString();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String project(String tag) {
        String pid = ok(201, "POST", "/projects", Map.of("code", tag + "-" + sfx, "name", tag + " " + sfx))
                .get("id").asString();
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));
        return pid;
    }

    String wbs(String pid, String code, String parentId) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Element " + code));
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return ok(201, "POST", "/projects/" + pid + "/wbs", body).get("id").asString();
    }

    /** A posted expense journal (Dr operating expense / Cr cash); returns the expense line id. */
    String postedExpenseLine(int amount, String text) {
        String expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        String cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'")
                .param(demoOrg).query(String.class).single();
        JsonNode je = ok(200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", today, "description",
                text, "lines", List.of(Map.of("accountId", expense, "debit", amount), Map.of("accountId", cash,
                        "credit", amount))));
        String id = je.get("id").asString();
        jdbc.sql("UPDATE \"JournalEntry\" SET \"createdBy\" = NULL WHERE id = ?").param(id).update();
        ok(200, "POST", "/journal-entries/" + id + "/post", null);
        return jdbc.sql("SELECT id FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(id, expense).query(String.class).single();
    }

    @Test
    @DisplayName("PS-1: sites — edit, clear optional fields, unique code, delete unless costed")
    void sites() {
        String pid = project("PS1");
        String w = wbs(pid, "1", null);
        String s1 = ok(201, "POST", "/projects/" + pid + "/sites", Map.of("code", "S1", "name", "North", "address",
                "Plot 4")).get("id").asString();
        String s2 = ok(201, "POST", "/projects/" + pid + "/sites", Map.of("code", "S2", "name", "South")).get("id")
                .asString();

        JsonNode s = ok(200, "PATCH", "/projects/sites/" + s1, Map.of("name", "North block", "code", "N1"));
        assertThat(s.get("name").asString()).isEqualTo("North block");
        assertThat(s.get("code").asString()).isEqualTo("N1");
        assertThat(s.get("address").asString()).as("null keeps").isEqualTo("Plot 4");
        assertThat(ok(200, "PATCH", "/projects/sites/" + s1, Map.of("address", "")).get("address").isNull())
                .as("blank clears").isTrue();
        assertThat(message(409, "PATCH", "/projects/sites/" + s1, Map.of("code", "S2")))
                .isEqualTo("Site S2 already exists on this project");
        assertThat(message(404, "PATCH", "/projects/sites/" + s1, Map.of("deliveryLocationId",
                UUID.randomUUID().toString()))).isEqualTo("Location not found");
        assertThat(call("PATCH", "/projects/sites/" + s1, Map.of("colour", "red"), adminToken).status())
                .as("unknown field").isEqualTo(400);

        // costed site stays; an unused one goes and its tasks lose the link
        posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "siteId", s1, "equipmentRef", "EX",
                "hours", 1, "ratePerHour", 10));
        assertThat(message(400, "DELETE", "/projects/sites/" + s1, null))
                .isEqualTo("Site N1 has project cost lines and cannot be deleted");
        String task = ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "Pour slab")).get("id")
                .asString();
        jdbc.sql("UPDATE \"ProjectTask\" SET \"siteId\" = ? WHERE id = ?").params(s2, task).update();
        assertThat(ok(200, "DELETE", "/projects/sites/" + s2, null).get("deleted").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT \"siteId\" FROM \"ProjectTask\" WHERE id = ?").param(task).query(String.class)
                .optional()).isEmpty();
        assertThat(ok(200, "GET", "/projects/" + pid, null).get("sites").size()).isEqualTo(1);
        assertThat(message(404, "DELETE", "/projects/sites/" + s2, null)).isEqualTo("Site not found");
    }

    @Test
    @DisplayName("PS-2: WBS — edit, move under a parent, no cycles, delete only when unused and childless")
    void wbsElements() {
        String pid = project("PS2");
        String root = wbs(pid, "1", null);
        String child = wbs(pid, "1.1", root);
        String grandchild = wbs(pid, "1.1.1", child);
        String loose = wbs(pid, "2", null);

        JsonNode w = ok(200, "PATCH", "/projects/wbs/" + loose, Map.of("name", "Drainage", "isBillable", false,
                "parentId", root));
        assertThat(w.get("name").asString()).isEqualTo("Drainage");
        assertThat(w.get("isBillable").asBoolean()).isFalse();
        assertThat(w.get("isCostable").asBoolean()).as("null keeps").isTrue();
        assertThat(w.get("parentId").asString()).isEqualTo(root);
        assertThat(ok(200, "PATCH", "/projects/wbs/" + loose, Map.of("parentId", "")).get("parentId").isNull())
                .as("blank moves to the top").isTrue();
        assertThat(message(400, "PATCH", "/projects/wbs/" + root, Map.of("parentId", grandchild)))
                .isEqualTo("A WBS element cannot be placed under itself or one of its children");
        assertThat(message(400, "PATCH", "/projects/wbs/" + root, Map.of("parentId", root)))
                .isEqualTo("A WBS element cannot be placed under itself or one of its children");
        assertThat(message(409, "PATCH", "/projects/wbs/" + loose, Map.of("code", "1.1")))
                .isEqualTo("WBS element 1.1 already exists on this project");
        String otherProject = project("PS2B");
        String foreign = wbs(otherProject, "1", null);
        assertThat(message(404, "PATCH", "/projects/wbs/" + loose, Map.of("parentId", foreign)))
                .isEqualTo("WBS element not found on this project");

        assertThat(message(400, "DELETE", "/projects/wbs/" + child, null))
                .isEqualTo("Move or delete the child elements of WBS element 1.1 first");
        ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(Map.of("wbsId", grandchild,
                "costElement", "labour", "amount", 100))));
        assertThat(message(400, "DELETE", "/projects/wbs/" + grandchild, null)).isEqualTo("WBS element 1.1.1 is used"
                + " by cost, budget or forecast lines, variations or subcontracts and cannot be deleted");

        String task = ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "Ditch")).get("id")
                .asString();
        jdbc.sql("UPDATE \"ProjectTask\" SET \"wbsId\" = ? WHERE id = ?").params(loose, task).update();
        assertThat(ok(200, "DELETE", "/projects/wbs/" + loose, null).get("deleted").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT \"wbsId\" FROM \"ProjectTask\" WHERE id = ?").param(task).query(String.class)
                .optional()).isEmpty();
        assertThat(ok(200, "GET", "/projects/" + pid, null).get("wbs").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("PS-3: draft budgets — replace lines and notes, delete; approved versions are frozen")
    void draftBudgets() {
        String pid = project("PS3");
        String w = wbs(pid, "1", null);
        String draft = ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("notes", "first cut", "lines",
                List.of(Map.of("wbsId", w, "costElement", "labour", "amount", 100)))).get("id").asString();

        JsonNode b = ok(200, "PATCH", "/projects/budgets/" + draft, Map.of("lines", List.of(
                Map.of("wbsId", w, "costElement", "labour", "amount", 150, "revenue", 200),
                Map.of("wbsId", w, "costElement", "material", "amount", 50.25))));
        assertThat(num(b.get("total"))).isEqualByComparingTo("200.25");
        assertThat(b.get("lines").size()).isEqualTo(2);
        assertThat(b.get("notes").asString()).as("null keeps").isEqualTo("first cut");
        assertThat(ok(200, "PATCH", "/projects/budgets/" + draft, Map.of("notes", "revised")).get("lines").size())
                .as("lines untouched without lines").isEqualTo(2);
        assertThat(message(400, "PATCH", "/projects/budgets/" + draft, Map.of("lines", List.of())))
                .isEqualTo("lines should not be empty");
        assertThat(message(400, "PATCH", "/projects/budgets/" + draft, Map.of("lines", List.of(Map.of("wbsId", w,
                "costElement", "labour", "amount", -1))))).isEqualTo("amount must not be negative");
        assertThat(message(400, "PATCH", "/projects/budgets/" + draft, Map.of("lines", List.of(Map.of("wbsId", w,
                "costElement", "labour", "amount", 1, "revenue", -1))))).isEqualTo("revenue must not be negative");
        assertThat(num(ok(200, "GET", "/projects/budgets/" + draft, null).get("total")))
                .as("a refused change leaves the draft as it was").isEqualByComparingTo("200.25");

        ok(200, "POST", "/projects/budgets/" + draft + "/approve", null);
        assertThat(message(400, "PATCH", "/projects/budgets/" + draft, Map.of("notes", "late")))
                .isEqualTo("Only a draft budget can be changed");
        assertThat(message(400, "DELETE", "/projects/budgets/" + draft, null))
                .isEqualTo("Only a draft budget can be deleted");

        String revision = ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(Map.of("wbsId",
                w, "costElement", "labour", "amount", 300)))).get("id").asString();
        assertThat(ok(200, "DELETE", "/projects/budgets/" + revision, null).get("deleted").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProjectBudgetLine\" WHERE \"budgetId\" = ?").param(revision)
                .query(Long.class).single()).isZero();
        JsonNode budgets = ok(200, "GET", "/projects/" + pid + "/budgets", null);
        assertThat(budgets.size()).isEqualTo(1);
        assertThat(budgets.get(0).get("status").asString()).isEqualTo("approved");
        assertThat(message(404, "DELETE", "/projects/budgets/" + revision, null)).isEqualTo("Budget not found");
    }

    @Test
    @DisplayName("PS-4: reclass candidates — open expense lines only, search, shrink as they are reclassed")
    void reclassCandidates() {
        String pid = project("PS4");
        String w = wbs(pid, "1", null);
        String text = "Crane hire " + sfx;
        String line = postedExpenseLine(500, text);

        JsonNode found = ok(200, "GET", "/projects/" + pid + "/costs/reclassable?search=" + sfx, null);
        assertThat(found.size()).isEqualTo(1);
        JsonNode c = found.get(0);
        assertThat(c.get("journalLineId").asString()).isEqualTo(line);
        assertThat(c.get("description").asString()).isEqualTo(text);
        assertThat(c.get("entryNumber").asString()).isNotBlank();
        assertThat(c.get("accountCode").asString()).isNotBlank();
        assertThat(num(c.get("amount"))).isEqualByComparingTo("500");
        assertThat(num(c.get("open"))).isEqualByComparingTo("500");
        assertThat(ok(200, "GET", "/projects/" + pid + "/costs/reclassable?search=" + sfx + "&from=2000-01-01&to="
                + "2000-01-31", null).size()).as("outside the date range").isZero();

        posted("/projects/" + pid + "/costs/reclass", Map.of("wbsId", w, "journalLineId", line, "amount", 200,
                "costElement", "other"));
        c = ok(200, "GET", "/projects/" + pid + "/costs/reclassable?search=" + sfx, null).get(0);
        assertThat(num(c.get("reclassed"))).isEqualByComparingTo("200");
        assertThat(num(c.get("open"))).isEqualByComparingTo("300");
        posted("/projects/" + pid + "/costs/reclass", Map.of("wbsId", w, "journalLineId", line, "costElement",
                "other"));
        assertThat(ok(200, "GET", "/projects/" + pid + "/costs/reclassable?search=" + sfx, null).size())
                .as("fully reclassed lines drop out").isZero();

        // the project postings themselves are never candidates
        JsonNode all = ok(200, "GET", "/projects/" + pid + "/costs/reclassable?limit=200", null);
        for (JsonNode n : all) {
            String source = jdbc.sql("SELECT coalesce(\"sourceType\", '') FROM \"JournalEntry\" WHERE id = ?")
                    .param(n.get("journalEntryId").asString()).query(String.class).single();
            assertThat(source).doesNotStartWith("project_");
        }
        assertThat(message(400, "GET", "/projects/" + pid + "/costs/reclassable?from=yesterday", null))
                .isEqualTo("Dates must be valid ISO 8601 date strings");
    }

    @Test
    @DisplayName("PS-5: subcontracts — complete stops valuations, cancel only when nothing is valued")
    void subcontractLifecycle() {
        String pid = project("PS5");
        String w = wbs(pid, "1", null);
        Map<String, Object> body = Map.of("supplierId", supplier, "wbsId", w, "scope", "Piling", "value", 1000);
        String valued = ok(201, "POST", "/projects/" + pid + "/subcontracts", body).get("id").asString();
        String unused = ok(201, "POST", "/projects/" + pid + "/subcontracts", body).get("id").asString();
        posted("/projects/subcontracts/" + valued + "/valuations", Map.of("cumulativeValue", 400));

        assertThat(message(400, "POST", "/projects/subcontracts/" + valued + "/cancel", null))
                .isEqualTo("A subcontract with valuations cannot be cancelled; complete it instead");
        assertThat(ok(200, "POST", "/projects/subcontracts/" + valued + "/complete", null).get("status").asString())
                .isEqualTo("completed");
        Res late = call("POST", "/projects/subcontracts/" + valued + "/valuations", Map.of("cumulativeValue", 500),
                key());
        assertThat(late.status()).isEqualTo(400);
        assertThat(late.body().get("message").asString()).isEqualTo("Only an active subcontract can be valued");
        assertThat(message(400, "POST", "/projects/subcontracts/" + valued + "/complete", null))
                .isEqualTo("This subcontract is already completed");

        assertThat(ok(200, "POST", "/projects/subcontracts/" + unused + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(message(400, "POST", "/projects/subcontracts/" + unused + "/complete", null))
                .isEqualTo("This subcontract is already cancelled");
        JsonNode list = ok(200, "GET", "/projects/" + pid + "/subcontracts", null);
        assertThat(list.findValuesAsString("status")).containsExactlyInAnyOrder("completed", "cancelled");
        assertThat(message(404, "POST", "/projects/subcontracts/" + UUID.randomUUID() + "/complete", null))
                .isEqualTo("Subcontract not found");
    }

    @Test
    @DisplayName("PS-6: another organisation gets 404 on every new route and changes nothing")
    void tenantIsolation() {
        String pid = project("PS6");
        String w = wbs(pid, "1", null);
        String site = ok(201, "POST", "/projects/" + pid + "/sites", Map.of("code", "S1", "name", "Yard")).get("id")
                .asString();
        String budget = ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(Map.of("wbsId", w,
                "costElement", "labour", "amount", 10)))).get("id").asString();
        String sc = ok(201, "POST", "/projects/" + pid + "/subcontracts", Map.of("supplierId", supplier, "wbsId", w,
                "value", 100)).get("id").asString();
        Object[][] probes = {
            {"PATCH", "/projects/sites/" + site, Map.of("name", "Hijack")},
            {"DELETE", "/projects/sites/" + site, null},
            {"PATCH", "/projects/wbs/" + w, Map.of("name", "Hijack")},
            {"DELETE", "/projects/wbs/" + w, null},
            {"PATCH", "/projects/budgets/" + budget, Map.of("notes", "Hijack")},
            {"DELETE", "/projects/budgets/" + budget, null},
            {"GET", "/projects/" + pid + "/costs/reclassable", null},
            {"POST", "/projects/subcontracts/" + sc + "/complete", null},
            {"POST", "/projects/subcontracts/" + sc + "/cancel", null},
        };
        for (Object[] p : probes) {
            assertThat(call((String) p[0], (String) p[1], p[2], otherToken).status()).as(p[0] + " " + p[1])
                    .isEqualTo(404);
        }
        JsonNode project = ok(200, "GET", "/projects/" + pid, null);
        assertThat(project.get("sites").get(0).get("name").asString()).isEqualTo("Yard");
        assertThat(project.get("wbs").get(0).get("name").asString()).isEqualTo("Element 1");
        assertThat(ok(200, "GET", "/projects/budgets/" + budget, null).get("notes").isNull()).isTrue();
        assertThat(ok(200, "GET", "/projects/" + pid + "/subcontracts", null).get(0).get("status").asString())
                .isEqualTo("active");
    }
}
