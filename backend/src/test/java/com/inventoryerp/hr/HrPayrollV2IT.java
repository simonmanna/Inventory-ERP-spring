package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.web.HrCompensationController;
import com.inventoryerp.hr.web.HrPayrollOpsController;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * HP-1 compensation, HP-2 engine v2 and HP-3 Uganda pack end to end on the real API: salary revision with
 * maker-checker, structure + formula element, calculation, balanced journal with employer NSSF, variance,
 * remittance schedule, bank file, payslip PDF, net-pay settlement and reversal.
 */
class HrPayrollV2IT extends ApiTestSupport {

    String checkerToken;

    @BeforeAll
    void checker() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String email = "v2-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    JsonNode checked(int status, String method, String path, Object body) {
        Res r = call(method, path, body, checkerToken);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** Other employees parked, components off, settings removed before and after. */
    void isolated(Consumer<String> body) {
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> components = jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> elements = jdbc.sql("UPDATE \"HrPayElement\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        try {
            body.accept(suffix());
        } finally {
            jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(components.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayElement\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(elements.toArray(String[]::new)).update();
        }
    }

    /** GET a file route as admin without parsing the body as JSON. */
    java.net.http.HttpResponse<String> raw(String path) {
        try {
            return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                    + "/api/v1" + path)).header("Authorization", "Bearer " + adminToken).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asString())) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " not in " + array);
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("compensation: grade band, formula validation, revision maker-checker, effective history")
    void compensation() {
        String s = suffix();
        ok(201, "POST", "/hr/compensation/grades", Map.of("code", "g" + s, "name", "Grade " + s, "minAmount", 500000,
                "maxAmount", 2000000));
        String gradeId = find(ok(200, "GET", "/hr/compensation/grades", null).get("rows"), "code", "G" + s).get("id")
                .asString();
        assertThat(as("POST", "/hr/compensation/elements", Map.of("code", "bad" + s, "name", "Bad", "kind", "EARNING",
                "calcMethod", "FORMULA", "formula", "SALARY * 2")).status()).isEqualTo(400);
        assertThat(as("POST", "/hr/compensation/elements", Map.of("code", "fx" + s, "name", "Fixed", "kind", "EARNING",
                "calcMethod", "FIXED")).status()).isEqualTo(400);
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Comp" + s, "baseSalary", 600000)).get("id")
                .asString();
        assertThat(as("POST", "/hr/compensation/employees/" + emp + "/revisions", Map.of("effectiveFrom", "2026-01-01",
                "basicSalary", 3000000, "gradeId", gradeId)).status()).isEqualTo(400);
        String rev = ok(201, "POST", "/hr/compensation/employees/" + emp + "/revisions", Map.of("effectiveFrom",
                "2026-01-01", "basicSalary", 900000, "gradeId", gradeId, "reason", "Promotion")).get("id").asString();
        assertThat(as("POST", "/hr/compensation/employees/" + emp + "/revisions", Map.of("effectiveFrom", "2026-02-01",
                "basicSalary", 950000)).status()).isEqualTo(400);
        Res self = as("POST", "/hr/compensation/revisions/" + rev + "/approve", null);
        assertThat(self.status()).isEqualTo(403);
        JsonNode approved = checked(201, "POST", "/hr/compensation/revisions/" + rev + "/approve", null);
        assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(dec(approved.get("previousBasic"))).isEqualByComparingTo("600000");
        assertThat(dec(ok(200, "GET", "/hr/employees/" + emp, null).get("baseSalary"))).isEqualByComparingTo("900000");
        JsonNode history = ok(200, "GET", "/hr/compensation/employees/" + emp, null);
        assertThat(dec(history.get("current").get("basicSalary"))).isEqualByComparingTo("900000");
        assertThat(as("DELETE", "/hr/compensation/grades/" + gradeId, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("engine v2 with the Uganda pack: decimal pay, balanced journal with employer NSSF, schedules, files")
    void engineV2Uganda() {
        isolated(s -> {
            ok(200, "PUT", "/hr/payroll/settings", Map.of("engine", "v2", "statutoryPack", "UG", "roundingScale", 0,
                    "requireSeparateApprover", true, "lockPeriodOnApproval", true));
            String housing = ok(201, "POST", "/hr/compensation/elements", Map.of("code", "hse" + s, "name", "Housing",
                    "kind", "EARNING", "calcMethod", "FORMULA", "formula", "BASIC * 0.25", "isTaxable", true))
                    .get("id").asString();
            String structure = ok(201, "POST", "/hr/compensation/structures", Map.of("code", "st" + s, "name",
                    "Staff", "lines", List.of(Map.of("elementId", housing)))).get("id").asString();
            String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Uganda" + s, "baseSalary", 1,
                    "hireDate", "2020-01-01", "bankName", "Stanbic", "bankAccountNumber", "9030001234567")).get("id")
                    .asString();
            String rev = ok(201, "POST", "/hr/compensation/employees/" + emp + "/revisions", Map.of("effectiveFrom",
                    "2020-01-01", "basicSalary", 800000, "structureId", structure)).get("id").asString();
            checked(201, "POST", "/hr/compensation/revisions/" + rev + "/approve", null);
            ok(201, "POST", "/hr/payroll/inputs", Map.of("employeeId", emp, "name", "Bonus", "amount", 100000));

            int year = 2100 - (int) (Math.random() * 50);
            String periodId = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", year + "-03-01", "endDate",
                    year + "-03-31", "periodCode", "PRD-V2" + s)).get("id").asString();
            String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", periodId)).get("id").asString();
            JsonNode calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            JsonNode item = calc.get("items").get(0);
            assertThat(dec(item.get("grossPay"))).isEqualByComparingTo("1100000");
            assertThat(dec(item.get("taxAmount"))).isEqualByComparingTo("232000");
            assertThat(dec(item.get("socialSecurityAmount"))).isEqualByComparingTo("55000");
            assertThat(dec(item.get("netPay"))).isEqualByComparingTo("813000");

            JsonNode lines = ok(200, "GET", "/hr/payroll/runs/" + runId + "/lines", null).get("rows");
            List<String> codes = new ArrayList<>();
            lines.forEach(l -> codes.add(l.get("code").asString() + ":" + l.get("kind").asString()));
            assertThat(codes).contains("BASIC:EARNING", "HSE" + s + ":EARNING", "INPUT:EARNING", "PAYE:DEDUCTION",
                    "NSSF:DEDUCTION", "NSSF:EMPLOYER_CONTRIBUTION");

            assertThat(as("POST", "/hr/payroll/runs/" + runId + "/approve", null).status()).isEqualTo(403);
            JsonNode approved = checked(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
            String journal = approved.get("journalEntryId").asString();
            Map<String, Object> sums = jdbc.sql("SELECT sum(debit) AS d, sum(credit) AS c FROM \"JournalLine\" WHERE"
                    + " \"journalEntryId\" = ?").param(journal).query().singleRow();
            assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo((BigDecimal) sums.get("c"));
            // Gross 1,100,000 + employer NSSF 110,000 debited.
            assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo("1210000");

            JsonNode variance = ok(200, "GET", "/hr/payroll/runs/" + runId + "/variance", null);
            // Our employee is new against the previous approved run; that run's people show as MISSING.
            assertThat(variance.get("rows").toString()).contains(emp);
            JsonNode remit = ok(200, "GET", "/hr/payroll/remittances?periodId=" + periodId, null).get("rows");
            JsonNode nssf = find(remit, "statutoryCode", "NSSF");
            assertThat(dec(nssf.get("employee"))).isEqualByComparingTo("55000");
            assertThat(dec(nssf.get("employer"))).isEqualByComparingTo("110000");

            java.net.http.HttpResponse<String> csv = raw("/hr/payroll/runs/" + runId + "/bank-file?format=CSV");
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.body()).contains("9030001234567", "813000");
            assertThat(as("GET", "/hr/payroll/runs/" + runId + "/bank-file?format=WPS_SIF", null).status())
                    .isEqualTo(400);
            String payslip = jdbc.sql("SELECT s.id FROM \"HrPayslip\" s JOIN \"HrPayrollItem\" i ON i.id = s.\"itemId\""
                    + " WHERE i.\"runId\" = ?").param(runId).query(String.class).single();
            java.net.http.HttpResponse<String> pdf = raw("/hr/payslips/" + payslip + "/pdf");
            assertThat(pdf.statusCode()).isEqualTo(200);
            assertThat(pdf.body()).startsWith("%PDF");
            JsonNode register = ok(200, "GET", "/hr/analytics/payroll-register/" + runId, null);
            assertThat(register.get("columns").toString()).contains("NSSF (ER)");

            String bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'")
                    .param(demoOrg).query(String.class).single();
            JsonNode paid = checked(201, "POST", "/hr/payroll/runs/" + runId + "/settle", Map.of("accountId", bank));
            assertThat(dec(paid.get("amount"))).isEqualByComparingTo("813000");
            assertThat(as("POST", "/hr/payroll/runs/" + runId + "/settle", Map.of("accountId", bank)).status())
                    .isEqualTo(400);
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "test cleanup"));
            assertThat(jdbc.sql("SELECT status::text FROM \"HrPayrollPeriod\" WHERE id = ?").param(periodId)
                    .query(String.class).single()).isEqualTo("OPEN");
        });
    }

    @Test
    @DisplayName("authorization matrices of the compensation and payroll operation routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(HrCompensationController.class)).isEqualTo(21);
        assertThat(assertAuthorizationMatrix(HrPayrollOpsController.class)).isEqualTo(17);
    }
}
