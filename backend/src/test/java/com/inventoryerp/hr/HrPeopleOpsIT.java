package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.web.HrEssController;
import com.inventoryerp.hr.web.HrLeavePolicyController;
import com.inventoryerp.hr.web.HrSeparationController;
import com.inventoryerp.hr.web.HrTalentController;
import com.inventoryerp.hr.web.HrTimePayController;
import com.inventoryerp.hr.web.HrWorkforceReportsController;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** HP-4 leave policies, HP-5 time to pay, HP-6 separation, HP-8 self-service, HP-9 talent, HP-10 reports. */
class HrPeopleOpsIT extends ApiTestSupport {

    String checkerToken;

    @BeforeAll
    void checker() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String email = "ops-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String employee(String s, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("firstName", "Ops" + s));
        body.putAll(extra);
        return ok(201, "POST", "/hr/employees", body).get("id").asString();
    }

    @Test
    @DisplayName("leave policy: monthly accrual with hire pro-rata (idempotent), tenure tier, year-end carry, encashment")
    void leavePolicy() {
        String s = suffix();
        String type = ok(201, "POST", "/hr/leave/types", Map.of("code", "al" + s, "name", "Annual " + s,
                "daysPerYear", 21, "isPaid", true)).get("id").asString();
        assertThat(as("POST", "/hr/leave/policies", Map.of("leaveTypeId", type, "daysPerYear", -1)).status())
                .isEqualTo(400);
        ok(201, "POST", "/hr/leave/policies", Map.of("leaveTypeId", type, "accrualMethod", "MONTHLY",
                "daysPerYear", 24, "carryForwardMax", 5, "encashable", true, "maxEncashDays", 10,
                "tenureTiers", List.of(Map.of("years", 50, "daysPerYear", 30))));
        String emp = employee(s, Map.of("hireDate", "2091-03-16", "baseSalary", 3000000));
        JsonNode run = ok(201, "POST", "/hr/leave/accruals/run", Map.of("asOf", "2091-06-30"));
        assertThat(run.get("entries").asInt()).isGreaterThanOrEqualTo(4);
        ok(201, "POST", "/hr/leave/accruals/run", Map.of("asOf", "2091-06-30"));
        BigDecimal accrued = jdbc.sql("SELECT \"accruedDays\" FROM \"HrLeaveBalance\" WHERE \"employeeId\" = ? AND"
                + " \"leaveTypeId\" = ? AND year = 2091").params(emp, type).query(BigDecimal.class).single();
        // March 16/31 × 2 = 1.03, then 2 days for April, May, June.
        assertThat(accrued).isEqualByComparingTo("7.03");
        JsonNode ledger = ok(200, "GET", "/hr/leave/ledger?employeeId=" + emp + "&leaveTypeId=" + type, null);
        assertThat(ledger.get("total").asInt()).isEqualTo(4);

        JsonNode enc = ok(201, "POST", "/hr/leave/encashments", Map.of("employeeId", emp, "leaveTypeId", type,
                "days", 2, "year", 2091));
        assertThat(dec(enc.get("amount"))).isEqualByComparingTo("200000");
        assertThat(as("POST", "/hr/leave/encashments", Map.of("employeeId", emp, "leaveTypeId", type, "days", 11,
                "year", 2091)).status()).isEqualTo(400);
        ok(201, "POST", "/hr/leave/year-end", Map.of("year", 2091));
        BigDecimal carried = jdbc.sql("SELECT \"adjustedDays\" FROM \"HrLeaveBalance\" WHERE \"employeeId\" = ? AND"
                + " \"leaveTypeId\" = ? AND year = 2092").params(emp, type).query(BigDecimal.class).single();
        assertThat(carried).isEqualByComparingTo("5");

        JsonNode count = ok(200, "GET", "/hr/leave/count-days?from=2091-07-02&to=2091-07-09&leaveTypeId=" + type, null);
        assertThat(dec(count.get("days"))).isEqualByComparingTo("6");
        ok(200, "GET", "/hr/leave/calendar?from=2091-07-01&to=2091-07-31", null);
        jdbc.sql("UPDATE \"HrPayrollInput\" SET status = 'cancelled' WHERE \"employeeId\" = ?").param(emp).update();
        jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE id = ?").param(emp).update();
    }

    @Test
    @DisplayName("time to pay: overtime review and the period summary")
    void timeToPay() {
        String s = suffix();
        String emp = employee(s, Map.of("hourlyRate", 1000));
        String att = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrAttendance\" (id, \"organizationId\", \"employeeId\", date, \"workedMinutes\","
                + " \"overtimeMinutes\", \"updatedAt\") VALUES (?, ?, ?, '2093-05-04', 600, 120, now())")
                .params(att, demoOrg, emp).update();
        JsonNode pending = ok(200, "GET", "/hr/time/overtime?from=2093-05-01&to=2093-05-31", null);
        assertThat(pending.get("rows").toString()).contains(att);
        ok(201, "POST", "/hr/time/overtime/review", Map.of("attendanceIds", List.of(att), "approved", true));
        assertThat(jdbc.sql("SELECT \"overtimeApproved\" FROM \"HrAttendance\" WHERE id = ?").param(att)
                .query(Boolean.class).single()).isTrue();
        String period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", "2093-05-01", "endDate",
                "2093-05-31", "periodCode", "PRD-T" + s)).get("id").asString();
        JsonNode summary = ok(200, "GET", "/hr/time/periods/" + period + "/summary", null);
        JsonNode mine = null;
        for (JsonNode r : summary.get("rows")) {
            if (r.get("employeeId").asString().equals(emp)) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(dec(mine.get("overtimeApprovedHours"))).isEqualByComparingTo("2");
        jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE id = ?").param(emp).update();
    }

    @Test
    @DisplayName("separation: clearance gate, settlement (salary, leave, notice, loan), maker-checker, GL, terminated")
    void separation() {
        String s = suffix();
        String emp = employee(s, Map.of("baseSalary", 3000000, "hireDate", "2015-01-01"));
        ok(201, "POST", "/hr/loans", Map.of("employeeId", emp, "principal", 300000, "installmentsTotal", 3));
        JsonNode sep = ok(201, "POST", "/hr/separations", Map.of("employeeId", emp, "separationType", "TERMINATION",
                "noticeDate", "2026-03-01", "lastWorkingDay", "2026-03-15", "noticeDaysRequired", 30));
        String id = sep.get("id").asString();
        assertThat(sep.get("tasks")).hasSize(5);
        assertThat(dec(sep.get("noticePayDays"))).isEqualByComparingTo("16");
        assertThat(as("POST", "/hr/separations", Map.of("employeeId", emp, "separationType", "RESIGNATION",
                "noticeDate", "2026-03-01", "lastWorkingDay", "2026-03-31")).status()).isEqualTo(400);
        JsonNode calc = ok(201, "POST", "/hr/separations/" + id + "/calculate", null);
        JsonNode st = calc.get("settlement");
        // Salary 1–15 March = 15/31 × 3,000,000; notice 16 days × 100,000; the loan comes off.
        assertThat(st.get("lines").toString()).contains("NOTICE", "SALARY", "LOAN");
        assertThat(as("POST", "/hr/separations/" + id + "/approve", null).body().get("message").asString())
                .contains("checklist");
        for (JsonNode t : calc.get("tasks")) {
            ok(200, "PATCH", "/hr/separations/" + id + "/tasks/" + t.get("id").asString(), Map.of("isDone", true));
        }
        assertThat(as("POST", "/hr/separations/" + id + "/approve", null).status()).isEqualTo(403);
        JsonNode approved = call("POST", "/hr/separations/" + id + "/approve", null, checkerToken).body();
        assertThat(approved.get("status").asString()).as(approved.toString()).isEqualTo("APPROVED");
        Map<String, Object> sums = jdbc.sql("SELECT sum(debit) AS d, sum(credit) AS c FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ?").param(approved.get("journalEntryId").asString()).query().singleRow();
        assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo((BigDecimal) sums.get("c"));
        assertThat(jdbc.sql("SELECT \"employmentStatus\"::text FROM \"HrEmployee\" WHERE id = ?").param(emp)
                .query(String.class).single()).isEqualTo("TERMINATED");
        assertThat(jdbc.sql("SELECT balance FROM \"HrEmployeeLoan\" WHERE \"employeeId\" = ?").param(emp)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        String bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'")
                .param(demoOrg).query(String.class).single();
        JsonNode paid = ok(201, "POST", "/hr/separations/" + id + "/pay", Map.of("accountId", bank));
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("self-service: claim → manager inbox → HR approval → payroll input; profile change applied by HR")
    void selfService() {
        String s = suffix();
        String email = "ess-" + s.toLowerCase() + "@demo.test";
        String userId = createUser(demoOrg, email, "Ess@1234567", null);
        String manager = employee("M" + s, Map.of());
        String emp = employee(s, Map.of("supervisorId", manager));
        ok(201, "POST", "/hr/employees/" + emp + "/link-user", Map.of("userId", userId));
        String token = login("DEMO", email, "Ess@1234567");
        assertThat(call("POST", "/hr/me/claims", Map.of("claimType", "NOPE", "amount", 5), token).status())
                .isEqualTo(400);
        Res claim = call("POST", "/hr/me/claims", Map.of("claimType", "TRANSPORT", "amount", 25000, "description",
                "Site visit"), token);
        assertThat(claim.status()).as(claim.raw().body()).isEqualTo(201);
        String claimId = claim.body().get("id").asString();
        assertThat(call("GET", "/hr/me/claims", null, token).body().get("total").asInt()).isEqualTo(1);
        JsonNode approved = ok(201, "POST", "/hr/claims/" + claimId + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM \"HrPayrollInput\" WHERE id = ? AND status = 'pending'")
                .param(approved.get("payrollInputId").asString()).query(Long.class).single()).isEqualTo(1);
        jdbc.sql("UPDATE \"HrPayrollInput\" SET status = 'cancelled' WHERE id = ?")
                .param(approved.get("payrollInputId").asString()).update();

        assertThat(call("POST", "/hr/me/profile-requests", Map.of("changes", Map.of("baseSalary", "1")), token).status())
                .isEqualTo(400);
        Res req = call("POST", "/hr/me/profile-requests", Map.of("changes", Map.of("phone", "+256700000001")), token);
        assertThat(req.status()).as(req.raw().body()).isEqualTo(201);
        ok(201, "POST", "/hr/profile-requests/" + req.body().get("id").asString() + "/approve", Map.of());
        assertThat(ok(200, "GET", "/hr/employees/" + emp, null).get("phone").asString()).isEqualTo("+256700000001");
        assertThat(call("GET", "/hr/me/approvals", null, token).status()).isEqualTo(200);
        jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE id IN (?, ?)").params(emp, manager).update();
    }

    @Test
    @DisplayName("talent: cycle with goals and competencies, manager appraisal score, close → performance review")
    void talent() {
        String s = suffix();
        String dept = ok(201, "POST", "/hr/departments", Map.of("code", "tl" + s, "name", "Talent " + s)).get("id")
                .asString();
        String mgr = employee("TM" + s, Map.of("departmentId", dept));
        String emp = employee("TE" + s, Map.of("departmentId", dept, "supervisorId", mgr));
        String comp = ok(201, "POST", "/hr/talent/competencies", Map.of("code", "c" + s, "name", "Teamwork"))
                .get("id").asString();
        String cycle = ok(201, "POST", "/hr/talent/cycles", Map.of("name", "H1 " + s, "periodStart", "2026-01-01",
                "periodEnd", "2026-06-30", "goalWeightPct", 60, "competencyIds", List.of(comp), "departmentId", dept))
                .get("id").asString();
        String g1 = ok(201, "POST", "/hr/talent/goals", Map.of("cycleId", cycle, "employeeId", emp, "title", "Sales",
                "weight", 70)).get("id").asString();
        assertThat(as("POST", "/hr/talent/goals", Map.of("cycleId", cycle, "employeeId", emp, "title", "Too much",
                "weight", 40)).status()).isEqualTo(400);
        String g2 = ok(201, "POST", "/hr/talent/goals", Map.of("cycleId", cycle, "employeeId", emp, "title", "Quality",
                "weight", 30)).get("id").asString();
        JsonNode launched = ok(201, "POST", "/hr/talent/cycles/" + cycle + "/launch", null);
        assertThat(launched.get("status").asString()).isEqualTo("OPEN");
        String managerAppraisal = null;
        for (JsonNode a : launched.get("appraisals")) {
            if (a.get("employeeId").asString().equals(emp) && a.get("reviewerType").asString().equals("MANAGER")) {
                managerAppraisal = a.get("id").asString();
            }
        }
        assertThat(as("POST", "/hr/talent/appraisals/" + managerAppraisal + "/submit", Map.of("goalScores",
                Map.of(g1, 6))).status()).isEqualTo(400);
        JsonNode submitted = ok(201, "POST", "/hr/talent/appraisals/" + managerAppraisal + "/submit", Map.of(
                "goalScores", Map.of(g1, 4, g2, 5), "competencyScores", Map.of(comp, 3), "comments", "Solid"));
        // goals (4 × 70 + 5 × 30) / 100 = 4.3; overall 0.6 × 4.3 + 0.4 × 3 = 3.78
        assertThat(dec(submitted.get("goalScore"))).isEqualByComparingTo("4.3");
        assertThat(dec(submitted.get("overallScore"))).isEqualByComparingTo("3.78");
        JsonNode closed = ok(201, "POST", "/hr/talent/cycles/" + cycle + "/close", null);
        assertThat(closed.get("finalized").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT rating FROM \"HrPerformanceReview\" WHERE \"employeeId\" = ?").param(emp)
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    @DisplayName("reports answer for a range; validation of the range")
    void reports() {
        for (String r : List.of("payroll-summary", "cost-by-department", "headcount", "salary-changes")) {
            ok(200, "GET", "/hr/analytics/" + r + "?from=2026-01-01&to=2026-06-30", null);
        }
        ok(200, "GET", "/hr/analytics/statutory?year=2026", null);
        ok(200, "GET", "/hr/analytics/leave-liability?year=2026", null);
        assertThat(as("GET", "/hr/analytics/headcount?from=2026-06-01&to=2026-01-01", null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/hr/analytics/headcount?from=2026-01-01&to=2026-03-31", null).get("rows"))
                .hasSize(3);
    }

    @Test
    @DisplayName("authorization matrices")
    void authorization() {
        assertThat(assertAuthorizationMatrix(HrLeavePolicyController.class, HrTimePayController.class,
                HrSeparationController.class, HrTalentController.class, HrWorkforceReportsController.class,
                HrEssController.class)).isPositive();
    }
}
