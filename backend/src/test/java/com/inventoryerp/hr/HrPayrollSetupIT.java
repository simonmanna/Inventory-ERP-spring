package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.web.HrPayrollSetupController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** HP-0 payroll foundations: settings, pay groups and calendars, period locks, maker-checker on the real engine. */
class HrPayrollSetupIT extends ApiTestSupport {

    String checkerToken;

    @BeforeAll
    void checker() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String email = "payroll-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        // A default group must exist, or the first group a test creates becomes the default and gains every employee.
        if (jdbc.sql("SELECT count(*) FROM \"HrPayGroup\" WHERE \"organizationId\" = ? AND \"isDefault\" AND"
                + " \"deletedAt\" IS NULL").param(demoOrg).query(Long.class).single() == 0) {
            ok(201, "POST", "/hr/payroll/pay-groups", Map.of("code", "MAIN", "name", "Main payroll", "isDefault", true));
        }
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** A far-future year per test, so generated period codes and dates never meet another suite's. */
    int year() {
        return 2100 - (int) (Math.random() * 60);
    }

    /** Runs {@code body} without saved settings before and after, so other HR suites keep the Nest behaviour. */
    void withoutSettings(Runnable body) {
        jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        try {
            body.run();
        } finally {
            jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        }
    }

    /** Every other employee parked and components off, so a run covers only this test's people. */
    void isolated(Consumer<String> body) {
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> components = jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        try {
            withoutSettings(() -> body.accept(suffix()));
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(components.toArray(String[]::new)).update();
        }
    }

    String payGroup(String s, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("code", "pg" + s, "name", "Group " + s));
        body.putAll(extra);
        return ok(201, "POST", "/hr/payroll/pay-groups", body).get("id").asString();
    }

    @Test
    @DisplayName("settings: defaults until saved, validated, audited")
    void settings() {
        withoutSettings(() -> {
            JsonNode d = ok(200, "GET", "/hr/payroll/settings", null);
            assertThat(d.get("configured").asBoolean()).isFalse();
            assertThat(d.get("engine").asString()).isEqualTo("v1");
            assertThat(d.get("requireSeparateApprover").asBoolean()).isTrue();

            assertThat(as("PUT", "/hr/payroll/settings", Map.of("engine", "v9")).status()).isEqualTo(400);
            assertThat(as("PUT", "/hr/payroll/settings", Map.of("statutoryPack", "XX")).status()).isEqualTo(400);
            assertThat(as("PUT", "/hr/payroll/settings", Map.of("roundingScale", 7)).status()).isEqualTo(400);
            assertThat(as("PUT", "/hr/payroll/settings", Map.of("weekendDays", "FUNDAY")).status()).isEqualTo(400);
            assertThat(as("PUT", "/hr/payroll/settings", Map.of("standardHoursPerDay", 0)).status()).isEqualTo(400);

            JsonNode saved = ok(200, "PUT", "/hr/payroll/settings", Map.of("statutoryPack", "UG", "roundingScale", 0,
                    "weekendDays", "sunday", "prorationBasis", "WORKING_DAYS"));
            assertThat(saved.get("configured").asBoolean()).isTrue();
            assertThat(saved.get("statutoryPack").asString()).isEqualTo("UG");
            assertThat(saved.get("weekendDays").asString()).isEqualTo("SUNDAY");
            JsonNode again = ok(200, "PUT", "/hr/payroll/settings", Map.of("requireSeparateApprover", false));
            assertThat(again.get("id").asString()).isEqualTo(saved.get("id").asString());
            assertThat(again.get("statutoryPack").asString()).isEqualTo("UG");
            assertThat(again.get("requireSeparateApprover").asBoolean()).isFalse();
            assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'HrPayrollSettings' AND"
                    + " \"entityId\" = ?").param(saved.get("id").asString()).query(Long.class).single()).isEqualTo(2);

            JsonNode packs = ok(200, "GET", "/hr/payroll/statutory-packs", null);
            List<String> codes = new ArrayList<>();
            packs.forEach(p -> codes.add(p.get("code").asString()));
            assertThat(codes).containsExactly("NONE", "UG", "AE");
        });
    }

    @Test
    @DisplayName("pay groups: validation, one default, members, generated calendar is idempotent")
    void payGroups() {
        String s = suffix();
        int year = year();
        assertThat(as("POST", "/hr/payroll/pay-groups", Map.of("code", "w" + s, "name", "Weekly",
                "payFrequency", "WEEKLY")).status()).isEqualTo(400);
        assertThat(as("POST", "/hr/payroll/pay-groups", Map.of("code", "x" + s, "name", "X",
                "payFrequency", "DAILY")).status()).isEqualTo(400);
        String monthly = payGroup(s, Map.of("paymentDay", 25));
        assertThat(as("POST", "/hr/payroll/pay-groups", Map.of("code", "pg" + s, "name", "Dup")).status())
                .isEqualTo(400);
        String weekly = payGroup("W" + s, Map.of("payFrequency", "WEEKLY", "anchorDate", year + "-01-01"));

        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Member" + s)).get("id").asString();
        assertThat(as("PUT", "/hr/payroll/pay-groups/" + monthly + "/members",
                Map.of("employeeIds", List.of(UUID.randomUUID().toString()))).status()).isEqualTo(400);
        assertThat(ok(200, "PUT", "/hr/payroll/pay-groups/" + monthly + "/members", Map.of("employeeIds", List.of(emp)))
                .get("assigned").asInt()).isEqualTo(1);
        JsonNode members = ok(200, "GET", "/hr/payroll/pay-groups/" + monthly + "/members", null);
        assertThat(members.get("total").asInt()).isEqualTo(1);
        assertThat(members.get("rows").get(0).get("payGroupId").asString()).isEqualTo(monthly);
        assertThat(ok(200, "GET", "/hr/payroll/pay-groups/" + monthly, null).get("employeeCount").asInt())
                .isEqualTo(1);
        // The Nest employee JSON does not grow a payGroupId.
        assertThat(ok(200, "GET", "/hr/employees/" + emp, null).has("payGroupId")).isFalse();
        assertThat(as("DELETE", "/hr/payroll/pay-groups/" + monthly, null).status()).isEqualTo(400);

        JsonNode gen = ok(201, "POST", "/hr/payroll/pay-groups/" + monthly + "/periods", Map.of("year", year));
        assertThat(gen.get("created")).hasSize(12);
        JsonNode feb = gen.get("created").get(1);
        assertThat(feb.get("periodCode").asString()).isEqualTo("PG" + s + "-" + year + "-02");
        assertThat(feb.get("payGroupId").asString()).isEqualTo(monthly);
        assertThat(feb.get("paymentDate").asString()).isEqualTo(year + "-02-25");
        JsonNode again = ok(201, "POST", "/hr/payroll/pay-groups/" + monthly + "/periods", Map.of("year", year));
        assertThat(again.get("created")).isEmpty();
        assertThat(again.get("skipped").asInt()).isEqualTo(12);
        assertThat(as("PATCH", "/hr/payroll/pay-groups/" + monthly, Map.of("payFrequency", "WEEKLY",
                "anchorDate", year + "-01-01")).status()).isEqualTo(400);

        JsonNode weeks = ok(201, "POST", "/hr/payroll/pay-groups/" + weekly + "/periods", Map.of("year", year));
        assertThat(weeks.get("created").size()).isBetween(52, 53);
        JsonNode cal = ok(200, "GET", "/hr/payroll/calendar?payGroupId=" + monthly + "&year=" + year, null);
        assertThat(cal.get("total").asInt()).isEqualTo(12);
        assertThat(cal.get("rows").get(0).get("run").isNull()).isTrue();

        // Moving the member out lets the group go.
        String def = jdbc.sql("SELECT id FROM \"HrPayGroup\" WHERE \"organizationId\" = ? AND \"isDefault\" AND"
                + " \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        assertThat(as("DELETE", "/hr/payroll/pay-groups/" + def, null).status()).isEqualTo(400);
        ok(200, "PUT", "/hr/payroll/pay-groups/" + def + "/members", Map.of("employeeIds", List.of(emp)));
        ok(200, "DELETE", "/hr/payroll/pay-groups/" + weekly, null);
        assertThat(as("GET", "/hr/payroll/pay-groups/" + weekly, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a pay-group period pays only its members; maker-checker; approval locks, reversal reopens")
    void runControls() {
        isolated(s -> {
            int year = year();
            String group = payGroup(s, Map.of());
            String inGroup = ok(201, "POST", "/hr/employees", Map.of("firstName", "In" + s, "baseSalary", 500000))
                    .get("id").asString();
            ok(201, "POST", "/hr/employees", Map.of("firstName", "Out" + s, "baseSalary", 700000));
            ok(200, "PUT", "/hr/payroll/pay-groups/" + group + "/members", Map.of("employeeIds", List.of(inGroup)));
            String periodId = ok(201, "POST", "/hr/payroll/pay-groups/" + group + "/periods", Map.of("year", year))
                    .get("created").get(0).get("id").asString();

            JsonNode run = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", periodId));
            assertThat(run.get("paymentDate").asString()).startsWith(year + "-01-31");
            String runId = run.get("id").asString();
            JsonNode calculated = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            assertThat(calculated.get("items")).hasSize(1);
            assertThat(calculated.get("items").get(0).get("employeeId").asString()).isEqualTo(inGroup);

            ok(200, "PUT", "/hr/payroll/settings", Map.of("requireSeparateApprover", true,
                    "lockPeriodOnApproval", true));
            Res self = as("POST", "/hr/payroll/runs/" + runId + "/approve", null);
            assertThat(self.status()).isEqualTo(403);
            assertThat(self.raw().body()).contains("different user");
            Res checked = call("POST", "/hr/payroll/runs/" + runId + "/approve", null, checkerToken);
            assertThat(checked.status()).as(checked.raw().body()).isEqualTo(201);

            JsonNode cal = ok(200, "GET", "/hr/payroll/calendar?payGroupId=" + group + "&year=" + year, null);
            JsonNode first = cal.get("rows").get(0);
            assertThat(first.get("status").asString()).isEqualTo("LOCKED");
            assertThat(first.get("lockedAt").isNull()).isFalse();
            assertThat(first.get("run").get("status").asString()).isEqualTo("APPROVED");
            assertThat(as("POST", "/hr/payroll/periods/" + periodId + "/unlock", Map.of("reason", "fix")).status())
                    .isEqualTo(400);

            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "test"));
            assertThat(jdbc.sql("SELECT status::text FROM \"HrPayrollPeriod\" WHERE id = ?").param(periodId)
                    .query(String.class).single()).isEqualTo("OPEN");

            ok(201, "POST", "/hr/payroll/periods/" + periodId + "/lock", null);
            assertThat(as("POST", "/hr/payroll/periods/" + periodId + "/lock", null).status()).isEqualTo(400);
            Res blocked = as("POST", "/hr/payroll/runs", Map.of("periodId", periodId));
            assertThat(blocked.status()).isEqualTo(400);
            assertThat(blocked.raw().body()).contains("Payroll period is locked");
            assertThat(as("POST", "/hr/payroll/periods/" + periodId + "/unlock", Map.of()).status()).isEqualTo(400);
            JsonNode open = ok(201, "POST", "/hr/payroll/periods/" + periodId + "/unlock", Map.of("reason", "rerun"));
            assertThat(open.get("status").asString()).isEqualTo("OPEN");
            assertThat(open.get("lockedAt").isNull()).isTrue();
        });
    }

    @Test
    @DisplayName("without saved settings the same user may calculate and approve (Nest behaviour)")
    void nestDefault() {
        isolated(s -> {
            ok(201, "POST", "/hr/employees", Map.of("firstName", "Solo" + s, "baseSalary", 100000));
            int year = year();
            String periodId = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", year + "-03-01",
                    "endDate", year + "-03-31", "periodCode", "PRD-HP0" + s)).get("id").asString();
            String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", periodId)).get("id").asString();
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
            assertThat(jdbc.sql("SELECT status::text FROM \"HrPayrollPeriod\" WHERE id = ?").param(periodId)
                    .query(String.class).single()).isEqualTo("OPEN");
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "cleanup"));
        });
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix")
    void isolationAndAuthz() {
        String group = payGroup(suffix(), Map.of());
        assertThat(call("GET", "/hr/payroll/pay-groups/" + group, null, otherToken).status()).isEqualTo(404);
        JsonNode other = call("GET", "/hr/payroll/pay-groups", null, otherToken).body();
        other.get("rows").forEach(r -> assertThat(r.get("id").asString()).isNotEqualTo(group));
        assertThat(assertAuthorizationMatrix(HrPayrollSetupController.class)).isEqualTo(14);
    }
}
