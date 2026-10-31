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

/** Payroll additions: configurable overtime (HR-3) on the real payroll engine. */
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
        jdbc.sql("UPDATE \"HrOvertimeRule\" SET \"isActive\" = false WHERE \"organizationId\" = ?").param(demoOrg)
                .update();
        try {
            body.accept(suffix());
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
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
        assertThat(assertAuthorizationMatrix(HrPayrollConfigController.class)).isEqualTo(4);
    }
}
