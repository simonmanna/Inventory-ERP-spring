package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.projects.web.ProjectController;
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
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-8a (ADR-S038): a project with two sites and a WBS; versioned budgets; cost captured by every
 * path (stock issue to a WBS element, delivery to a site, approved timesheets × rate, equipment hours, reclass of a
 * posted subcontract bill line); open commitments; budget vs committed vs actual vs EAC; the WIP tie-out; reversal;
 * guards, idempotency, tenant isolation and the authorization matrix.
 */
@ExtendWith(BooksGuard.class)
class ProjectCostScenarioIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String adminId;
    String store;
    String siteStore;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        store = location();
        siteStore = location();
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "PJ-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Yard " + UUID.randomUUID().toString().substring(0, 6), "type", "warehouse"))
                .get("id").asString();
    }

    String product(String loc, int qty, int unitCost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "PJ-" + UUID.randomUUID().toString().substring(0, 8), "Cement " + sfx).update();
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", id, "quantity", qty, "unitCost",
                        unitCost))));
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key());
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String account(String category) {
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = ? AND a.\"isPostable\" AND a.\"deletedAt\" IS NULL ORDER BY"
                + " a.code LIMIT 1").params(demoOrg, category).query(String.class).single();
    }

    /** A posted subcontract bill-like journal: Dr expense / Cr cash. Returns the expense line id. */
    String postedExpenseLine(int amount) {
        String expense = account("operating_expense");
        String cash = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100'")
                .param(demoOrg).query(String.class).single();
        JsonNode je = ok(200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", today, "description",
                "Subcontract bill " + sfx, "lines", List.of(Map.of("accountId", expense, "debit", amount),
                        Map.of("accountId", cash, "credit", amount))));
        String id = je.get("id").asString();
        // fixture: the bill was keyed by someone else (journal maker-checker)
        jdbc.sql("UPDATE \"JournalEntry\" SET \"createdBy\" = NULL WHERE id = ?").param(id).update();
        ok(200, "POST", "/journal-entries/" + id + "/post", null);
        return jdbc.sql("SELECT id FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(id, expense).query(String.class).single();
    }

    void approvedTimesheet(String projectId, int minutes) {
        String emp = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"updatedAt\")"
                + " VALUES (?, ?, ?, 'Crew', now())").params(emp, demoOrg, "E-" + sfx).update();
        String ts = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrTimesheet\" (id, \"organizationId\", \"timesheetCode\", \"employeeId\","
                + " \"periodStart\", \"periodEnd\", status, \"totalMinutes\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " current_date, current_date, 'APPROVED', ?, now())").params(ts, demoOrg, "TS-" + sfx, emp, minutes)
                .update();
        jdbc.sql("INSERT INTO \"HrTimesheetEntry\" (id, \"organizationId\", \"timesheetId\", date, \"startAt\","
                + " \"endAt\", minutes, \"linkedEntityType\", \"linkedEntityId\", \"updatedAt\") VALUES (?, ?, ?,"
                + " current_date, now(), now(), ?, 'project', ?, now())").params(UUID.randomUUID().toString(), demoOrg,
                ts, minutes, projectId).update();
    }

    @Test
    @DisplayName("project: two sites, WBS, budget versions, every capture path, commitments, EAC, tie-out, reversal")
    void projectScenario() {
        Res created = call("POST", "/projects", Map.of("code", "PRJ-" + sfx, "name", "Ring road " + sfx,
                "contractValue", 100000), adminToken);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String pid = created.body().get("id").asString();
        assertThat(call("POST", "/projects", Map.of("code", "PRJ-" + sfx, "name", "dup"), adminToken).status())
                .isEqualTo(409);
        String siteA = ok(201, "POST", "/projects/" + pid + "/sites", Map.of("code", "S1", "name", "North",
                "deliveryLocationId", siteStore)).get("id").asString();
        ok(201, "POST", "/projects/" + pid + "/sites", Map.of("code", "S2", "name", "South"));
        String root = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Road")).get("id")
                .asString();
        String earth = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1.1", "name", "Earthworks",
                "parentId", root)).get("id").asString();
        String paving = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1.2", "name", "Paving",
                "parentId", root)).get("id").asString();
        String summary = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "9", "name", "Summary",
                "isCostable", false)).get("id").asString();

        String b1 = ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(
                Map.of("wbsId", earth, "costElement", "labour", "amount", 1000),
                Map.of("wbsId", paving, "costElement", "material", "amount", 2000)))).get("id").asString();
        ok(200, "POST", "/projects/budgets/" + b1 + "/approve", null);
        JsonNode b2 = ok(201, "POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(
                Map.of("wbsId", earth, "costElement", "labour", "amount", 1200),
                Map.of("wbsId", paving, "costElement", "material", "amount", 2000),
                Map.of("wbsId", earth, "costElement", "subcontract", "amount", 800))));
        assertThat(b2.get("kind").asString()).isEqualTo("revised");
        ok(200, "POST", "/projects/budgets/" + b2.get("id").asString() + "/approve", null);
        assertThat(ok(200, "GET", "/projects/budgets/" + b1, null).get("status").asString()).isEqualTo("superseded");

        // capture is refused on a draft project
        String cement = product(store, 50, 10);
        Map<String, Object> issueBody = Map.of("wbsId", paving, "productId", cement, "locationId", store, "quantity",
                20);
        assertThat(post("/projects/" + pid + "/costs/issue", issueBody).status()).isEqualTo(400);
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));
        assertThat(call("POST", "/projects/" + pid + "/costs/issue", issueBody, adminToken).status())
                .as("Idempotency-Key").isEqualTo(400);
        Res issued = post("/projects/" + pid + "/costs/issue", issueBody);
        assertThat(issued.status()).as(issued.raw().body()).isIn(200, 201);
        assertThat(num(issued.body().get("amount"))).isEqualByComparingTo("200");
        String issueJe = issued.body().get("journalEntryId").asString();
        BigDecimal wipLine = jdbc.sql("SELECT coalesce(sum(\"baseDebit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND dimensions->>'projectId' = ? AND dimensions->>'wbsId' = ?")
                .params(issueJe, pid, paving).query(BigDecimal.class).single();
        assertThat(wipLine).as("WIP debit carries project and WBS").isEqualByComparingTo("200");
        assertThat(post("/projects/" + pid + "/costs/issue", Map.of("wbsId", summary, "productId", cement,
                "locationId", store, "quantity", 1)).status()).as("non-costable WBS").isEqualTo(400);

        // delivery straight to a site: the site's delivery location is used
        String gravel = product(siteStore, 10, 30);
        Res delivered = post("/projects/" + pid + "/costs/issue", Map.of("wbsId", paving, "siteId", siteA,
                "productId", gravel, "quantity", 5, "excludeFromProgress", true));
        assertThat(delivered.status()).as(delivered.raw().body()).isIn(200, 201);
        assertThat(num(delivered.body().get("amount"))).isEqualByComparingTo("150");

        approvedTimesheet(pid, 600); // 10 h
        Res ts = post("/projects/" + pid + "/costs/timesheets", Map.of("wbsId", earth, "from", today, "to", today,
                "ratePerHour", 25));
        assertThat(ts.status()).as(ts.raw().body()).isIn(200, 201);
        assertThat(num(ts.body().get("total"))).isEqualByComparingTo("250");
        assertThat(post("/projects/" + pid + "/costs/timesheets", Map.of("wbsId", earth, "from", today, "to", today,
                "ratePerHour", 25)).status()).as("each entry once").isEqualTo(400);

        Res eq = post("/projects/" + pid + "/costs/equipment", Map.of("wbsId", earth, "equipmentRef", "EXC-01",
                "hours", 4, "ratePerHour", 50));
        assertThat(eq.status()).as(eq.raw().body()).isIn(200, 201);
        assertThat(num(eq.body().get("amount"))).isEqualByComparingTo("200");

        String billLine = postedExpenseLine(500);
        Res rc = post("/projects/" + pid + "/costs/reclass", Map.of("wbsId", earth, "journalLineId", billLine,
                "amount", 300, "costElement", "subcontract"));
        assertThat(rc.status()).as(rc.raw().body()).isIn(200, 201);
        Res over = post("/projects/" + pid + "/costs/reclass", Map.of("wbsId", earth, "journalLineId", billLine,
                "amount", 300, "costElement", "subcontract"));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).isEqualTo("Only 200.00 of this line is left to reclass");

        // open commitment on the project
        String commitAccount = account("operating_expense");
        jdbc.sql("INSERT INTO \"Commitment\" (id, \"organizationId\", \"sourceType\", \"sourceId\", \"sourceLineId\","
                + " \"accountId\", \"projectId\", period, amount, \"openAmount\") VALUES (?, ?, 'purchase_order', ?, ?,"
                + " ?, ?, ?, 400, 400)").params(UUID.randomUUID().toString(), demoOrg, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), commitAccount, pid, today.substring(0, 7)).update();

        ok(200, "PUT", "/projects/" + pid + "/forecast", Map.of("lines", List.of(
                Map.of("wbsId", earth, "costElement", "labour", "costToComplete", 900),
                Map.of("wbsId", paving, "costElement", "material", "costToComplete", 1500))));

        JsonNode rep = ok(200, "GET", "/projects/" + pid + "/report", null);
        JsonNode t = rep.get("totals");
        assertThat(num(t.get("budget"))).isEqualByComparingTo("4000");
        assertThat(num(t.get("actual"))).isEqualByComparingTo("1100"); // 200 + 150 + 250 + 200 + 300
        assertThat(num(t.get("committed"))).isEqualByComparingTo("400");
        assertThat(num(t.get("costToComplete"))).isEqualByComparingTo("2400");
        assertThat(num(t.get("eac"))).isEqualByComparingTo("3900");
        assertThat(num(rep.get("wipTieOut").get("difference"))).as("WIP ties to the sub-ledger")
                .isEqualByComparingTo("0");

        // reversal returns the stock and the cost
        String issueLine = issued.body().get("id").asString();
        Res rev = post("/projects/costs/" + issueLine + "/reverse", null);
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(post("/projects/costs/" + issueLine + "/reverse", null).status()).isEqualTo(400);
        BigDecimal onHand = jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(cement, store).query(BigDecimal.class).single();
        assertThat(onHand).isEqualByComparingTo("50");
        JsonNode after = ok(200, "GET", "/projects/" + pid + "/report", null);
        assertThat(num(after.get("totals").get("actual"))).isEqualByComparingTo("900");
        assertThat(num(after.get("wipTieOut").get("difference"))).isEqualByComparingTo("0");

        // tenant isolation and free-text project references
        assertThat(call("GET", "/projects/" + pid, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/projects/" + pid + "/report", null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(ProjectController.class)).isEqualTo(20);
    }

    @Test
    @DisplayName("procurement: a purchase-request line must reference a real project")
    void purchaseRequestProject() {
        String product = product(store, 1, 1);
        Res bad = call("POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("productId",
                product, "description", "Cement", "quantity", 1, "projectId", "not-a-project"))), key());
        assertThat(bad.status()).as(bad.raw().body()).isEqualTo(400);
        assertThat(bad.body().get("message").asString()).isEqualTo("Project not found");
    }
}
