package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost-centre accounting (ADR-S007 §4): hierarchy, account rules (required / forbidden), defaults, inline split
 * rules (60 / 40 of a 10,000,000 office cost, exact sum), periodic allocation runs (once per period, reversible),
 * validity dates, P&amp;L roll-up and the centre × account matrix, tenant isolation.
 */
@ExtendWith(BooksGuard.class)
class CostCenterAccountingIT extends ApiTestSupport {

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String center(String name, String parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CC-" + suffix());
        body.put("name", name + " " + suffix());
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return ok(201, "POST", "/cost-centers", body).get("id").asString();
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    /** A fresh postable operating-expense account. */
    String expenseAccount() {
        String category = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE id = ?").param(mapped("default_expense"))
                .query(String.class).single();
        return ok(201, "POST", "/accounts", Map.of("code", "6" + (int) (Math.random() * 900000 + 100000), "name",
                "Office " + suffix(), "categoryId", category)).get("id").asString();
    }

    String offset;

    /** A fresh liability account for the credit side (keeps shared bank balances untouched). */
    String offsetAccount() {
        if (offset == null) {
            String category = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE id = ?").param(mapped("grni_accrued"))
                    .query(String.class).single();
            offset = ok(201, "POST", "/accounts", Map.of("code", "29" + (int) (Math.random() * 90000 + 10000), "name",
                    "CC test offset " + suffix(), "categoryId", category)).get("id").asString();
        }
        return offset;
    }

    JsonNode journal(String expense, Object amount, Map<String, Object> debitExtra) {
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", expense);
        debit.put("debit", amount);
        debit.putAll(debitExtra);
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("accountId", offsetAccount());
        credit.put("credit", amount);
        Res r = as("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", LocalDate.now().toString(),
                "description", "Office cost", "lines", List.of(debit, credit)));
        return r.body();
    }

    String postJournal(String expense, Object amount, Map<String, Object> debitExtra) {
        JsonNode draft = journal(expense, amount, debitExtra);
        assertThat(draft.get("id")).as(draft.toString()).isNotNull();
        String id = draft.get("id").asString();
        if (!"posted".equals(draft.get("status").asString())) {
            String other = createSecondApprover();
            Res posted = call("POST", "/journal-entries/" + id + "/post", Map.of(), other);
            assertThat(posted.status()).as(posted.raw().body()).isLessThan(300);
        }
        return id;
    }

    String approverToken;

    String createSecondApprover() {
        if (approverToken == null) {
            String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'"
                    + " AND \"deletedAt\" IS NULL LIMIT 1").param(demoOrg).query(String.class).single();
            String email = "cc-approver-" + suffix() + "@demo.test";
            createUser(demoOrg, email, "Passw0rd!x", role);
            approverToken = login("DEMO", email, "Passw0rd!x");
        }
        return approverToken;
    }

    @Test
    @DisplayName("hierarchy: tree nests children; a center cannot become its own descendant")
    void hierarchy() {
        String company = center("Company", null);
        String kampala = center("Kampala Branch", company);
        String sales = center("Sales", kampala);
        JsonNode tree = ok(200, "GET", "/cost-centers/tree", null);
        JsonNode root = null;
        for (JsonNode n : tree) {
            if (n.get("id").asString().equals(company)) {
                root = n;
            }
        }
        assertThat(root).isNotNull();
        assertThat(root.get("children").get(0).get("id").asString()).isEqualTo(kampala);
        assertThat(root.get("children").get(0).get("children").get(0).get("id").asString()).isEqualTo(sales);
        Res cycle = as("PATCH", "/cost-centers/" + company, Map.of("parentId", sales));
        assertThat(cycle.status()).isEqualTo(400);
        assertThat(cycle.body().get("message").asString()).isEqualTo(
                "A cost center cannot be its own parent or descendant");
        Res badCategory = as("POST", "/cost-centers", Map.of("code", "X-" + suffix(), "name", "x", "category", "planet"));
        assertThat(badCategory.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("split 60/40: 10,000,000 office cost → Sales 6,000,000 / Admin 4,000,000; required rule; matrix")
    void splitAndRules() {
        String sales = center("Sales", null);
        String admin = center("Administration", null);
        String expense = expenseAccount();
        String rule = ok(201, "POST", "/cost-centers/allocation-rules", Map.of("code", "SPLIT-" + suffix(), "name",
                "Office 60/40", "kind", "split", "targets", List.of(Map.of("costCenterId", sales, "weight", 60),
                        Map.of("costCenterId", admin, "weight", 40)))).get("id").asString();

        ok(200, "PUT", "/cost-centers/account-rules/" + expense, Map.of("rule", "required"));
        JsonNode missing = journal(expense, 100, Map.of());
        assertThat(missing.get("message").asString()).endsWith("requires a cost center");

        String je = postJournal(expense, 10_000_000, Map.of("dimensions", Map.of("costAllocationRuleId", rule)));
        Map<String, BigDecimal> byCenter = new LinkedHashMap<>();
        jdbc.sql("SELECT \"costCenterId\", debit FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(je, expense).query().listOfRows()
                .forEach(r -> byCenter.put((String) r.get("costCenterId"), (BigDecimal) r.get("debit")));
        assertThat(byCenter.get(sales)).isEqualByComparingTo("6000000");
        assertThat(byCenter.get(admin)).isEqualByComparingTo("4000000");

        // Thirds still sum exactly.
        String third = ok(201, "POST", "/cost-centers/allocation-rules", Map.of("code", "THIRD-" + suffix(), "name",
                "Thirds", "kind", "split", "targets", List.of(Map.of("costCenterId", sales, "weight", 1),
                        Map.of("costCenterId", admin, "weight", 1), Map.of("costCenterId", center("Ops", null),
                                "weight", 1)))).get("id").asString();
        String je3 = postJournal(expense, 100, Map.of("dimensions", Map.of("costAllocationRuleId", third)));
        BigDecimal sum = jdbc.sql("SELECT SUM(debit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\""
                + " = ?").params(je3, expense).query(BigDecimal.class).single();
        assertThat(sum).isEqualByComparingTo("100");

        // Forbidden: the bank line may not carry a center.
        ok(200, "PUT", "/cost-centers/account-rules/" + offsetAccount(), Map.of("rule", "forbidden"));
        Map<String, Object> debit = Map.of("accountId", expense, "debit", 5, "costCenterId", sales);
        Map<String, Object> credit = Map.of("accountId", offsetAccount(), "credit", 5, "costCenterId", sales);
        Res forbidden = as("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", LocalDate.now().toString(),
                "description", "x", "lines", List.of(debit, credit)));
        assertThat(forbidden.body().get("message").asString()).endsWith("does not take a cost center");
        ok(200, "PUT", "/cost-centers/account-rules/" + offsetAccount(), Map.of("rule", "optional"));
        ok(200, "PUT", "/cost-centers/account-rules/" + expense, Map.of("rule", "optional"));

        String today = LocalDate.now().toString();
        JsonNode matrix = ok(200, "GET", "/cost-centers/reports/matrix?from=" + today + "&to=" + today, null);
        JsonNode row = null;
        for (JsonNode r : matrix.get("accounts")) {
            if (r.get("accountId").asString().equals(expense)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("byCostCenter").get(sales).asString()).isEqualTo("6000033.333333");
    }

    @Test
    @DisplayName("periodic allocation: source centre's period cost moves to targets once; reverse allows a rerun")
    void periodicAllocationAndRollup() {
        String parent = center("Kampala", null);
        String shared = center("Shared Services", parent);
        String sales = center("Kampala Sales", parent);
        String warehouse = center("Kampala Warehouse", parent);
        String expense = expenseAccount();
        postJournal(expense, 1_000, Map.of("costCenterId", shared));
        String rule = ok(201, "POST", "/cost-centers/allocation-rules", Map.of("code", "PER-" + suffix(), "name",
                "Shared by floor area", "kind", "periodic", "sourceCostCenterId", shared, "accountIds", List.of(expense),
                "targets", List.of(Map.of("costCenterId", sales, "weight", 300), Map.of("costCenterId", warehouse,
                        "weight", 700)))).get("id").asString();
        String today = LocalDate.now().toString();
        JsonNode preview = ok(200, "GET", "/cost-centers/allocation-rules/" + rule + "/preview?periodFrom=" + today
                + "&periodTo=" + today, null);
        assertThat(preview.get("totalAmount").asString()).isEqualTo("1000");

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res run = call("POST", "/cost-centers/allocation-rules/" + rule + "/run", Map.of("periodFrom", today, "periodTo",
                today), h);
        assertThat(run.status()).as(run.raw().body()).isEqualTo(200);
        String runId = run.body().get("id").asString();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        jdbc.sql("SELECT \"costCenterId\", SUM(debit - credit) AS n FROM \"JournalLine\" WHERE \"accountId\" = ?"
                + " GROUP BY 1").param(expense).query().listOfRows()
                .forEach(r -> net.put((String) r.get("costCenterId"), (BigDecimal) r.get("n")));
        assertThat(net.get(shared)).isEqualByComparingTo("0");
        assertThat(net.get(sales)).isEqualByComparingTo("300");
        assertThat(net.get(warehouse)).isEqualByComparingTo("700");

        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res twice = call("POST", "/cost-centers/allocation-rules/" + rule + "/run", Map.of("periodFrom", today,
                "periodTo", today), h);
        assertThat(twice.status()).isEqualTo(409);

        JsonNode pnl = ok(200, "GET", "/cost-centers/reports/profit-and-loss?from=" + today + "&to=" + today
                + "&costCenterId=" + parent, null);
        JsonNode top = null;
        for (JsonNode r : pnl.get("costCenters")) {
            if (r.get("costCenterId").asString().equals(parent)) {
                top = r;
            }
        }
        assertThat(pnl.get("costCenters")).hasSize(4);
        assertThat(top).isNotNull();
        assertThat(top.get("rollupExpense").asString()).isEqualTo("1000");

        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res rev = call("POST", "/cost-centers/allocation-runs/" + runId + "/reverse", null, h);
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(rev.body().get("status").asString()).isEqualTo("reversed");
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/cost-centers/allocation-rules/" + rule + "/run", Map.of("periodFrom", today,
                "periodTo", today), h).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("defaults by partner fill expense lines; inactive / expired centres rejected; tenants isolated")
    void defaultsValidityAndIsolation() {
        String cc = center("Mbarara Branch", null);
        String expense = expenseAccount();
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(partner, demoOrg, "P-" + suffix(), "Landlord").update();
        JsonNode def = ok(200, "POST", "/cost-centers/defaults", Map.of("scopeType", "partner", "scopeId", partner,
                "costCenterId", cc));
        String je = postJournal(expense, 2_000_000, Map.of("partnerId", partner));
        assertThat(jdbc.sql("SELECT \"costCenterId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(je, expense).query(String.class).single()).isEqualTo(cc);
        assertThat(jdbc.sql("SELECT \"costCenterId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\""
                + " <> ?").params(je, expense).query(String.class).list()).containsOnlyNulls();
        ok(204, "DELETE", "/cost-centers/defaults/" + def.get("id").asString(), null);

        String old = center("Closed project", null);
        ok(200, "PATCH", "/cost-centers/" + old, Map.of("validTo", "2000-01-31"));
        JsonNode expired = journal(expense, 10, Map.of("costCenterId", old));
        assertThat(expired.get("message").asString()).contains("is inactive or not valid on");

        assertThat(call("GET", "/cost-centers/allocation-rules", null, otherToken).body()).isEmpty();
        assertThat(call("GET", "/cost-centers/defaults", null, otherToken).body()).isEmpty();
        JsonNode otherTree = call("GET", "/cost-centers/tree", null, otherToken).body();
        otherTree.forEach(n -> assertThat(n.get("id").asString()).isNotEqualTo(cc));
    }
}
