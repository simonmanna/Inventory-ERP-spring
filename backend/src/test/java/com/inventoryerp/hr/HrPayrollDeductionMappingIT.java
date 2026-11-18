package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * R12: a payroll deduction must credit a liability / receivable account. Approving a run whose deduction has none is
 * refused with a 400 naming the component, before anything posts; salaries expense is never the fallback (Nest
 * credited it there, understating the cost and recording no liability). Both engines.
 */
class HrPayrollDeductionMappingIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** Other employees parked, components / elements off, settings removed before and after (shared DEMO org). */
    void isolated(Consumer<String> body) {
        String s = suffix();
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> components = jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> elements = jdbc.sql("UPDATE \"HrPayElement\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        try {
            body.accept(s);
        } finally {
            jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
            // This test's own employee, component and element never leak into other suites' runs.
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND \"firstName\""
                    + " LIKE ?").params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code LIKE"
                    + " ?").params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrPayElement\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code LIKE ?")
                    .params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(components.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayElement\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(elements.toArray(String[]::new)).update();
        }
    }

    String run(String s, String month) {
        int year = 2100 - (int) (Math.random() * 50);
        String periodId = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", year + "-" + month + "-01",
                "endDate", year + "-" + month + "-28", "periodCode", "PRD-R12" + s)).get("id").asString();
        return ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", periodId)).get("id").asString();
    }

    String runStatus(String runId) {
        return jdbc.sql("SELECT status::text FROM \"HrPayrollRun\" WHERE id = ?").param(runId).query(String.class)
                .single();
    }

    long entries(String runId) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ?")
                .params(demoOrg, "payroll_run:" + runId).query(Long.class).single();
    }

    /** Credits of the entry on the account mapped to {@code key}. */
    BigDecimal credited(String entryId, String key) {
        return jdbc.sql("SELECT coalesce(sum(l.credit), 0) FROM \"JournalLine\" l JOIN \"AccountMapping\" m ON"
                + " m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key = ? AND"
                + " m.\"deletedAt\" IS NULL WHERE l.\"journalEntryId\" = ?").params(key, entryId)
                .query(BigDecimal.class).single();
    }

    void assertBalanced(String entryId) {
        Map<String, Object> sums = jdbc.sql("SELECT sum(debit) AS d, sum(credit) AS c FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ?").param(entryId).query().singleRow();
        assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo((BigDecimal) sums.get("c"));
    }

    @Test
    @DisplayName("v1: an 'other' deduction component refuses approval with a 400 naming it; without it the journal"
            + " never credits salaries expense")
    void v1OtherDeduction() {
        isolated(s -> {
            ok(201, "POST", "/hr/employees", Map.of("firstName", "Dues" + s, "baseSalary", 1000000));
            String union = ok(201, "POST", "/hr/payroll/components", Map.of("code", "union" + s, "name",
                    "Union dues", "componentType", "DEDUCTION", "calcMethod", "FIXED", "amount", 20000)).get("id")
                    .asString();
            String runId = run(s, "05");
            JsonNode calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            assertThat(new BigDecimal(calc.get("items").get(0).get("otherDeductions").asString()))
                    .isEqualByComparingTo("20000");

            Res refused = as("POST", "/hr/payroll/runs/" + runId + "/approve", null);
            assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).contains("without a liability or receivable account")
                    .contains("UNION" + s + " (Union dues)");
            assertThat(runStatus(runId)).isEqualTo("CALCULATED");
            assertThat(entries(runId)).as("nothing posted").isZero();

            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE id = ?").param(union).update();
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            String journal = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null).get("journalEntryId")
                    .asString();
            assertBalanced(journal);
            assertThat(credited(journal, "salary_expense")).as("no salaries expense contra").isZero();
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "test cleanup"));
        });
    }

    @Test
    @DisplayName("v2: an element deduction without a GL account refuses approval; mapped to a liability it is credited"
            + " there")
    void v2ElementDeduction() {
        isolated(s -> {
            ok(200, "PUT", "/hr/payroll/settings", Map.of("engine", "v2", "statutoryPack", "NONE",
                    "requireSeparateApprover", false, "lockPeriodOnApproval", false));
            String element = ok(201, "POST", "/hr/compensation/elements", Map.of("code", "sacco" + s, "name",
                    "SACCO savings", "kind", "DEDUCTION", "calcMethod", "FIXED", "amount", 30000)).get("id")
                    .asString();
            String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Sacco" + s, "baseSalary", 900000,
                    "hireDate", "2020-01-01")).get("id").asString();
            ok(201, "POST", "/hr/compensation/employees/" + emp + "/elements", Map.of("elementId", element,
                    "effectiveFrom", "2020-01-01"));
            String runId = run(s, "06");
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);

            Res refused = as("POST", "/hr/payroll/runs/" + runId + "/approve", null);
            assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).contains("SACCO" + s + " (SACCO savings)")
                    .contains("deduction has no GL account");
            assertThat(entries(runId)).as("nothing posted").isZero();

            String liability = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2240'")
                    .param(demoOrg).query(String.class).single();
            ok(200, "PATCH", "/hr/compensation/elements/" + element, Map.of("glAccountId", liability));
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            String journal = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null).get("journalEntryId")
                    .asString();
            assertBalanced(journal);
            assertThat(jdbc.sql("SELECT coalesce(sum(credit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                    + " \"accountId\" = ?").params(journal, liability).query(BigDecimal.class).single())
                    .as("deduction credited to its liability").isEqualByComparingTo("30000");
            assertThat(credited(journal, "salary_expense")).as("no salaries expense contra").isZero();
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "test cleanup"));
        });
    }
}
