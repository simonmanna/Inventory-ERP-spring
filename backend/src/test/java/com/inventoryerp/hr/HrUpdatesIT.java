package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** HR edits the other suites never make (every master record's PATCH) and the self-service / team views. */
class HrUpdatesIT extends ApiTestSupport {

    static String s() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    String employee() {
        return ok(201, "POST", "/hr/employees", Map.of("firstName", "Upd", "lastName", "Ate " + s())).get("id")
                .asString();
    }

    @Test
    @DisplayName("organisation records: department, position, shift, leave type, holiday")
    void organisationEdits() {
        String s = s();
        String dept = ok(201, "POST", "/hr/departments", Map.of("code", "d" + s, "name", "Dept " + s)).get("id")
                .asString();
        assertThat(ok(200, "PATCH", "/hr/departments/" + dept, Map.of("name", "Dept 2 " + s)).get("name").asString())
                .isEqualTo("Dept 2 " + s);
        String pos = ok(201, "POST", "/hr/positions", Map.of("code", "p" + s, "name", "Cook", "departmentId", dept))
                .get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/positions/" + pos, Map.of("name", "Head cook")).get("name").asString())
                .isEqualTo("Head cook");
        String shift = ok(201, "POST", "/hr/shifts", Map.of("code", "sh" + s, "name", "Early", "startTime", "06:00",
                "endTime", "14:00")).get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/shifts/" + shift, Map.of("name", "Dawn", "graceMinutes", 15)).get("name")
                .asString()).isEqualTo("Dawn");
        String type = ok(201, "POST", "/hr/leave/types", Map.of("code", "lt" + s, "name", "Study " + s,
                "daysPerYear", 3)).get("id").asString();
        JsonNode t = ok(200, "PATCH", "/hr/leave/types/" + type, Map.of("daysPerYear", 4, "isPaid", false));
        assertThat(t.get("daysPerYear").asInt()).isEqualTo(4);
        String holiday = ok(201, "POST", "/hr/holidays", Map.of("name", "Founders " + s,
                "date", "2032-03-01T10:00:00Z")).get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/holidays/" + holiday, Map.of("name", "Founders day " + s)).toString())
                .contains("Founders day " + s);
    }

    @Test
    @DisplayName("people records: document, training, review, loan, contract")
    void peopleEdits() {
        String s = s();
        String emp = employee();
        String doc = ok(201, "POST", "/hr/documents", Map.of("employeeId", emp, "title", "Permit", "documentType",
                "WORK_PERMIT")).get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/documents/" + doc, Map.of("title", "Work permit")).get("title").asString())
                .isEqualTo("Work permit");
        String program = ok(201, "POST", "/hr/training/programs", Map.of("code", "tp" + s, "name", "Hygiene"))
                .get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/training/programs/" + program, Map.of("name", "Food hygiene",
                "durationHours", 4)).get("name").asString()).isEqualTo("Food hygiene");
        String review = ok(201, "POST", "/hr/reviews", Map.of("employeeId", emp, "rating", 3)).get("id").asString();
        assertThat(as("PATCH", "/hr/reviews/" + review, Map.of("period", "2031-H1")).status()).isEqualTo(200);
        String loan = ok(201, "POST", "/hr/loans", Map.of("employeeId", emp, "principal", 90000,
                "installmentsTotal", 3)).get("id").asString();
        JsonNode l = ok(200, "PATCH", "/hr/loans/" + loan, Map.of("installmentsPaid", 1, "balance", 60000));
        assertThat(l.get("installmentsPaid").asInt()).isEqualTo(1);
        Res contracts = as("GET", "/hr/contracts?employeeId=" + emp, null);
        assertThat(contracts.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("payroll setup: component, tax table, period")
    void payrollSetupEdits() {
        String s = s();
        String comp = ok(201, "POST", "/hr/payroll/components", Map.of("code", "c" + s, "name", "Meal",
                "componentType", "ALLOWANCE", "calcMethod", "FIXED", "amount", 5000)).get("id").asString();
        JsonNode c = ok(200, "PATCH", "/hr/payroll/components/" + comp, Map.of("amount", 7000, "name", "Meals"));
        assertThat(c.get("name").asString()).isEqualTo("Meals");
        String table = ok(201, "POST", "/hr/payroll/tax-tables", Map.of("code", "tt" + s, "name", "Old PAYE",
                "effectiveFrom", "2019-01-01", "brackets", List.of(Map.of("fromAmount", 0, "rate", 0))))
                .get("id").asString();
        JsonNode tt = ok(200, "PATCH", "/hr/payroll/tax-tables/" + table, Map.of("name", "Older PAYE",
                "brackets", List.of(Map.of("fromAmount", 0, "toAmount", 100000, "rate", 0),
                        Map.of("fromAmount", 100000, "rate", 0.05))));
        assertThat(tt.get("name").asString()).isEqualTo("Older PAYE");
        LocalDate month = LocalDate.now(ZoneOffset.UTC).plusYears(5).withDayOfMonth(1);
        String period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", month.toString(),
                "endDate", month.plusMonths(1).minusDays(1).toString(), "periodCode", "UPD-" + s)).get("id").asString();
        JsonNode p = ok(200, "PATCH", "/hr/payroll/periods/" + period, Map.of("endDate",
                month.plusMonths(1).minusDays(2).toString()));
        assertThat(p.toString()).contains(month.plusMonths(1).minusDays(2).toString());
    }

    @Test
    @DisplayName("recruitment: opening, interview and onboarding template edits")
    void recruitmentEdits() {
        String s = s();
        String opening = ok(201, "POST", "/hr/recruitment/openings", Map.of("title", "Barista " + s, "headcount", 1))
                .get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/recruitment/openings/" + opening, Map.of("title", "Head barista " + s,
                "description", "Mornings")).get("title").asString()).isEqualTo("Head barista " + s);
        ok(201, "POST", "/hr/recruitment/openings/" + opening + "/status", Map.of("status", "open"));
        String applicant = ok(201, "POST", "/hr/recruitment/applicants", Map.of("openingId", opening,
                "firstName", "Lea", "email", "lea-" + s + "@demo.test")).get("id").asString();
        ok(201, "POST", "/hr/recruitment/applicants/" + applicant + "/stage", Map.of("stage", "interview"));
        String interview = ok(201, "POST", "/hr/recruitment/applicants/" + applicant + "/interviews",
                Map.of("scheduledAt", "2032-01-05T09:00:00Z", "interviewer", "Owner")).get("id").asString();
        JsonNode iv = ok(200, "PATCH", "/hr/recruitment/interviews/" + interview, Map.of("score", 4,
                "outcome", "pass"));
        assertThat(iv.get("score").asInt()).isEqualTo(4);
        String template = ok(201, "POST", "/hr/onboarding/templates", Map.of("title", "Badge " + s, "dueDays", 2))
                .get("id").asString();
        assertThat(ok(200, "PATCH", "/hr/onboarding/templates/" + template, Map.of("dueDays", 5)).get("dueDays")
                .asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("self service and team views for a provisioned employee")
    void selfServiceViews() {
        String emp = employee();
        String email = "self-" + s() + "@demo.test";
        String roleId = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).list().getFirst();
        ok(201, "POST", "/hr/employees/" + emp + "/provision-user", Map.of("email", email, "password",
                "Staff@123456", "firstName", "Self", "roleIds", List.of(roleId)));
        String token = login("DEMO", email, "Staff@123456");
        for (String path : List.of("/hr/me", "/hr/me/attendance", "/hr/me/leave", "/hr/me/leave/balances",
                "/hr/me/payslips", "/hr/me/trainings", "/hr/team", "/hr/team/today", "/hr/team/leave")) {
            Res r = call("GET", path, null, token);
            assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        }
        assertThat(call("GET", "/hr/me", null, token).body().toString()).contains(emp);
    }
}
