package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.web.HrPayrollConfigController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Payroll additions on the real payroll engine: configurable overtime (HR-3) and cost allocation (HR-5). */
class HrPayrollExtensionsIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** The first day of a far-future month whose first day is a Monday, so weekdays are known. */
    LocalDate monthStartingMonday() {
        LocalDate d = LocalDate.now(ZoneOffset.UTC).plusYears(4).withDayOfMonth(1);
        while (d.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            d = d.plusMonths(1);
        }
        return d;
    }

    /** Runs {@code body} with every other employee parked, so a payroll run covers only this test's people. */
    void isolated(Consumer<String> body) {
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> components = jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        jdbc.sql("UPDATE \"HrOvertimeRule\" SET \"isActive\" = false WHERE \"organizationId\" = ?").param(demoOrg)
                .update();
        try {
            body.accept(suffix());
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(components.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrOvertimeRule\" SET \"isActive\" = false WHERE \"organizationId\" = ?").param(demoOrg)
                    .update();
        }
    }

    void attendance(String employeeId, LocalDate day, int overtimeMinutes) {
        jdbc.sql("INSERT INTO \"HrAttendance\" (id, \"organizationId\", \"employeeId\", date, \"overtimeMinutes\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(UUID.randomUUID().toString(), demoOrg,
                employeeId, java.sql.Timestamp.valueOf(day.atStartOfDay()), overtimeMinutes).update();
    }

    String calculatedRun(LocalDate month, String s) {
        JsonNode period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", month.toString(), "endDate",
                month.plusMonths(1).minusDays(1).toString(), "periodCode", "PRD-X" + s));
        String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", period.get("id").asString())).get("id")
                .asString();
        ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
        return runId;
    }

    BigDecimal overtimePay(String runId) {
        return new BigDecimal(ok(200, "GET", "/hr/payroll/runs/" + runId, null).get("items").get(0).get("overtimePay")
                .asString());
    }

    @Test
    @DisplayName("without a rule overtime stays 1.5 × the hourly rate")
    void defaultMultiplier() {
        isolated(s -> {
            String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Default", "hourlyRate", 1000)).get("id")
                    .asString();
            LocalDate month = monthStartingMonday();
            attendance(emp, month, 120);
            assertThat(overtimePay(calculatedRun(month, s))).isEqualByComparingTo("3000");
        });
    }

    @Test
    @DisplayName("a rule pays by kind of day, and fractional overtime no longer fails the run")
    void ruleByDay() {
        isolated(s -> {
            ok(201, "POST", "/hr/payroll/overtime-rules", Map.of("code", "ot" + s, "name", "Site overtime",
                    "weekdayMultiplier", 1.25, "weekendMultiplier", 1.5, "holidayMultiplier", 2, "weekendDays", "SUNDAY"));
            String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Rule", "hourlyRate", 1000)).get("id")
                    .asString();
            LocalDate month = monthStartingMonday();
            LocalDate sunday = month.plusDays(6);
            LocalDate holiday = month.plusDays(9);
            ok(201, "POST", "/hr/holidays", Map.of("name", "Site holiday " + s, "date", holiday.toString()));
            attendance(emp, month, 90);
            attendance(emp, sunday, 60);
            attendance(emp, holiday, 60);
            // 1.5 h × 1.25 + 1 h × 1.5 + 1 h × 2 = 5.375 weighted hours; 3.5 hours would fail without a rule.
            assertThat(overtimePay(calculatedRun(month, s))).isEqualByComparingTo("5375");
        });
    }

    String costCenter(String s, String name) {
        return ok(201, "POST", "/cost-centers", Map.of("code", name + s, "name", name + s)).get("id").asString();
    }

    @Test
    @DisplayName("a split must add up to 100, name real cost centres, and can be cleared")
    void splitValidation() {
        String s = suffix();
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Split")).get("id").asString();
        String cc = costCenter(s, "V");
        String other = costCenter(s, "W");
        assertThat(as("PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "employee", "targetId", emp, "lines",
                List.of(Map.of("costCenterId", cc, "percent", 60)))).status()).isEqualTo(400);
        assertThat(as("PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "employee", "targetId", emp, "lines",
                List.of(Map.of("costCenterId", UUID.randomUUID().toString(), "percent", 100)))).status()).isEqualTo(404);
        assertThat(as("PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "nope", "targetId", emp)).status())
                .isEqualTo(400);
        JsonNode saved = ok(200, "PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "employee", "targetId", emp,
                "lines", List.of(Map.of("costCenterId", cc, "percent", 60), Map.of("costCenterId", other, "percent", 40))));
        assertThat(saved.get("rows")).hasSize(2);
        JsonNode cleared = ok(200, "PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "employee", "targetId", emp,
                "lines", List.of()));
        assertThat(cleared.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("with rules the salary debit splits by cost centre and branch, balances, and reverses cleanly")
    void allocatedJournal() {
        isolated(s -> {
            jdbc.sql("DELETE FROM \"HrCostAllocationRule\" WHERE \"organizationId\" = ?").param(demoOrg).update();
            String dept = ok(201, "POST", "/hr/departments", Map.of("code", "q" + s, "name", "Quarry " + s)).get("id")
                    .asString();
            String quarry = costCenter(s, "Q");
            String plant = costCenter(s, "P");
            String a = ok(201, "POST", "/hr/employees", Map.of("firstName", "A", "baseSalary", 1000000, "departmentId",
                    dept)).get("id").asString();
            String b = ok(201, "POST", "/hr/employees", Map.of("firstName", "B", "baseSalary", 500000, "departmentId",
                    dept)).get("id").asString();
            ok(200, "PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "department", "targetId", dept, "lines",
                    List.of(Map.of("costCenterId", quarry, "percent", 100))));
            ok(200, "PUT", "/hr/payroll/cost-allocation-rules", Map.of("scope", "employee", "targetId", b, "lines",
                    List.of(Map.of("costCenterId", quarry, "percent", 50), Map.of("costCenterId", plant, "percent", 50))));
            LocalDate month = monthStartingMonday().plusYears(1).withDayOfMonth(1);
            String runId = calculatedRun(month, s);
            String journal = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null).get("journalEntryId")
                    .asString();
            String salaries = jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                    + " \"costCenterId\" = ? LIMIT 1").params(journal, quarry).query(String.class).single();
            assertThat(jdbc.sql("SELECT sum(debit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?"
                    + " AND \"costCenterId\" = ?").params(journal, salaries, quarry).query(BigDecimal.class).single())
                    .isEqualByComparingTo("1250000");
            assertThat(jdbc.sql("SELECT sum(debit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?"
                    + " AND \"costCenterId\" = ?").params(journal, salaries, plant).query(BigDecimal.class).single())
                    .isEqualByComparingTo("250000");
            assertThat(jdbc.sql("SELECT sum(debit) - sum(credit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                    .param(journal).query(BigDecimal.class).single()).isEqualByComparingTo("0");
            JsonNode report = ok(200, "GET", "/hr/payroll/runs/" + runId + "/cost-allocation", null);
            assertThat(report.get("rows")).hasSize(2);
            JsonNode byPeriod = ok(200, "GET", "/hr/payroll/cost-report?from=" + month + "&to="
                    + month.plusMonths(1), null);
            assertThat(byPeriod.get("rows").toString()).contains("Quarry " + s);
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "Wrong month"));
            assertThat(ok(200, "GET", "/hr/payroll/runs/" + runId + "/cost-allocation", null).get("rows")).isEmpty();
            jdbc.sql("DELETE FROM \"HrCostAllocationRule\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        });
    }

    @Test
    @DisplayName("without rules the journal keeps one untagged salary line, and the report still shows the department")
    void noRulesKeepsTheJournal() {
        isolated(s -> {
            jdbc.sql("DELETE FROM \"HrCostAllocationRule\" WHERE \"organizationId\" = ?").param(demoOrg).update();
            ok(201, "POST", "/hr/employees", Map.of("firstName", "Plain", "baseSalary", 700000));
            String runId = calculatedRun(monthStartingMonday().plusYears(2).withDayOfMonth(1), s);
            String journal = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null).get("journalEntryId")
                    .asString();
            assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND debit > 0")
                    .param(journal).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"costCenterId\""
                    + " IS NOT NULL").param(journal).query(Long.class).single()).isZero();
            assertThat(ok(200, "GET", "/hr/payroll/runs/" + runId + "/cost-allocation", null).get("rows")).hasSize(1);
        });
    }

    @Test
    @DisplayName("rule validation")
    void validation() {
        String s = suffix();
        assertThat(as("POST", "/hr/payroll/overtime-rules", Map.of("code", "a" + s, "name", "A", "weekdayMultiplier", 0))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/hr/payroll/overtime-rules", Map.of("code", "b" + s, "name", "B", "weekendDays", "FUNDAY"))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/hr/payroll/overtime-rules", Map.of("code", "c" + s, "name", "C", "appliesTo", "x"))
                .status()).isEqualTo(400);
        String id = ok(201, "POST", "/hr/payroll/overtime-rules", Map.of("code", "d" + s, "name", "D", "isActive", false))
                .get("id").asString();
        assertThat(as("POST", "/hr/payroll/overtime-rules", Map.of("code", "D" + s, "name", "D")).status()).isEqualTo(400);
        ok(200, "DELETE", "/hr/payroll/overtime-rules/" + id, null);
    }

    @Test
    @DisplayName("authorization matrix")
    void authorization() {
        assertThat(assertAuthorizationMatrix(HrPayrollConfigController.class)).isEqualTo(8);
    }
}
