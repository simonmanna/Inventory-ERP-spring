package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * HR on Testcontainers Postgres (ENABLE_HR on in the test profile): org masters, compensation redaction, identity
 * spine + lifecycle, attendance maths, leave balances, timesheets, payroll (calculate → approve → GL → bank payment →
 * reverse), advances / loans, documents / training, self-service, reports, tenant isolation and the authorization
 * matrix.
 */
class HrIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    JsonNode employee(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("firstName", "Ada", "lastName", "Byron"));
        body.putAll(extra);
        return ok(201, "POST", "/hr/employees", body);
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "HrTest-" + role, permissions).update();
        String email = "hr-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Hr@1234567", role);
        return login("DEMO", email, "Hr@1234567");
    }

    @Test
    @DisplayName("departments / positions: codes upper-cased, duplicates refused, delete blocked while staffed")
    void orgMasters() {
        String s = suffix();
        JsonNode dept = ok(201, "POST", "/hr/departments", Map.of("code", "k" + s, "name", "Kitchen " + s));
        assertThat(dept.get("code").asString()).isEqualTo("K" + s);
        Res dup = as("POST", "/hr/departments", Map.of("code", "K" + s, "name", "Again"));
        assertThat(dup.status()).isEqualTo(400);
        assertThat(dup.body().get("message").asString()).isEqualTo("Department code \"K" + s + "\" already exists");
        Res missing = as("POST", "/hr/departments", Map.of("name", "x"));
        assertThat(missing.body().get("message").asString()).isEqualTo("code and name are required");
        String deptId = dept.get("id").asString();
        JsonNode pos = ok(201, "POST", "/hr/positions", Map.of("code", "chef" + s, "name", "Chef", "departmentId", deptId,
                "defaultSalary", 900000));
        assertThat(pos.get("defaultSalary").asString()).isEqualTo("900000");
        JsonNode emp = employee(Map.of("departmentId", deptId, "positionId", pos.get("id").asString()));
        assertThat(emp.get("employeeCode").asString()).startsWith("EMP-");
        Res blocked = as("DELETE", "/hr/departments/" + deptId, null);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString())
                .isEqualTo("Department still has employees — reassign them first");
        JsonNode list = ok(200, "GET", "/hr/departments?search=" + s, null);
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("_count").get("employees").asInt()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("_count").get("positions").asInt()).isEqualTo(1);
        JsonNode one = ok(200, "GET", "/hr/departments/" + deptId, null);
        assertThat(one.get("positions")).hasSize(1);
        assertThat(one.get("manager").isNull()).isTrue();
        JsonNode positions = ok(200, "GET", "/hr/positions?departmentId=" + deptId, null);
        assertThat(positions.get("rows").get(0).get("department").get("id").asString()).isEqualTo(deptId);
        Res badType = as("POST", "/hr/employees", Map.of("firstName", "X", "employmentType", "NOPE"));
        assertThat(badType.body().get("message").asString()).isEqualTo("Invalid employmentType: NOPE");
        Res nf = as("GET", "/hr/departments/" + UUID.randomUUID(), null);
        assertThat(nf.status()).isEqualTo(404);
        assertThat(nf.body().get("message").asString()).isEqualTo("Department not found");
        Res foreign = call("GET", "/hr/employees/" + emp.get("id").asString(), null, otherToken);
        assertThat(foreign.status()).isIn(403, 404);
    }

    @Test
    @DisplayName("compensation fields are redacted and write-guarded without hr:compensation")
    void compensation() {
        JsonNode emp = employee(Map.of("baseSalary", 1500000, "bankAccountNumber", "903000"));
        assertThat(emp.get("baseSalary").asString()).isEqualTo("1500000");
        String id = emp.get("id").asString();
        String directory = tokenWith("hr:read", "hr:employee");
        Res read = call("GET", "/hr/employees/" + id, null, directory);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().has("baseSalary")).isFalse();
        assertThat(read.body().has("bankAccountNumber")).isFalse();
        assertThat(read.body().get("firstName").asString()).isEqualTo("Ada");
        assertThat(read.body().get("leaveBalances").isArray()).isTrue();
        Res write = call("PATCH", "/hr/employees/" + id, Map.of("baseSalary", 1, "taxNumber", "T"), directory);
        assertThat(write.status()).isEqualTo(403);
        assertThat(write.body().get("message").asString())
                .isEqualTo("Setting baseSalary, taxNumber requires the hr:compensation permission.");
        Res rename = call("PATCH", "/hr/employees/" + id, Map.of("phone", "+256700"), directory);
        assertThat(rename.status()).isEqualTo(200);
        assertThat(rename.body().has("baseSalary")).isFalse();
    }

    @Test
    @DisplayName("identity spine: provision, link rules, PIN, lifecycle with account disable and history")
    void identityAndLifecycle() {
        JsonNode emp = employee(Map.of());
        String id = emp.get("id").asString();
        JsonNode none = ok(200, "GET", "/hr/employees/" + id + "/access", null);
        assertThat(none.get("linked").asBoolean()).isFalse();
        Res unlinkNone = as("DELETE", "/hr/employees/" + id + "/link-user", null);
        assertThat(unlinkNone.status()).isEqualTo(400);
        assertThat(unlinkNone.body().get("message").asString()).isEqualTo("This employee has no linked user account");
        Res badLink = as("POST", "/hr/employees/" + id + "/link-user", Map.of("userId", "nope"));
        assertThat(badLink.status()).isEqualTo(400);
        assertThat(badLink.body().get("message").toString()).contains("userId must be a UUID");
        Res foreignUser = as("POST", "/hr/employees/" + id + "/link-user", Map.of("userId", UUID.randomUUID().toString()));
        assertThat(foreignUser.status()).isEqualTo(404);
        assertThat(foreignUser.body().get("message").asString()).isEqualTo("User account not found in this organization");

        String email = "staff-" + suffix().toLowerCase() + "@demo.test";
        String roleId = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).list().getFirst();
        JsonNode provisioned = ok(201, "POST", "/hr/employees/" + id + "/provision-user", Map.of("email", email,
                "password", "Staff@123456", "firstName", "Ada", "roleIds", List.of(roleId)));
        assertThat(provisioned.get("linked").asBoolean()).isTrue();
        assertThat(provisioned.get("user").get("email").asString()).isEqualTo(email);
        assertThat(provisioned.get("user").get("hasPin").asBoolean()).isFalse();
        assertThat(provisioned.get("user").get("posAccess").asBoolean()).isTrue();
        String userId = provisioned.get("user").get("id").asString();
        Res again = as("POST", "/hr/employees/" + id + "/provision-user", Map.of("email", "x" + email,
                "password", "Staff@123456", "firstName", "Ada", "roleIds", List.of()));
        assertThat(again.status()).isEqualTo(409);

        JsonNode other = employee(Map.of("firstName", "Grace"));
        Res claimed = as("POST", "/hr/employees/" + other.get("id").asString() + "/link-user", Map.of("userId", userId));
        assertThat(claimed.status()).isEqualTo(409);
        assertThat(claimed.body().get("message").asString())
                .isEqualTo("That account is already linked to employee " + emp.get("employeeCode").asString() + ".");
        assertThat(ok(201, "POST", "/hr/employees/" + id + "/link-user", Map.of("userId", userId)).get("linked")
                .asBoolean()).isTrue();
        assertThat(ok(201, "POST", "/hr/employees/" + id + "/access/pin", Map.of("pin", "4321")).get("user")
                .get("hasPin").asBoolean()).isTrue();
        assertThat(ok(200, "DELETE", "/hr/employees/" + id + "/access/pin", null).get("user").get("hasPin").asBoolean())
                .isFalse();
        Res noChange = as("PATCH", "/hr/employees/" + id + "/access", Map.of());
        assertThat(noChange.body().get("message").asString()).isEqualTo("Provide roleIds and/or isActive");

        // Self-service through the linked login.
        String staff = login("DEMO", email, "Staff@123456");
        JsonNode me = call("GET", "/hr/me", null, staff).body();
        assertThat(me.get("linked").asBoolean()).isTrue();
        assertThat(me.get("employee").get("id").asString()).isEqualTo(id);
        JsonNode adminMe = ok(200, "GET", "/hr/me", null);
        assertThat(adminMe.get("linked").asBoolean()).isFalse();

        // Lifecycle.
        Res shortReason = as("POST", "/hr/employees/" + id + "/suspend", Map.of("reason", "no", "disableAccount", true));
        assertThat(shortReason.status()).isEqualTo(400);
        assertThat(shortReason.body().get("message").toString()).contains("reason must be longer than or equal to 3"
                + " characters");
        JsonNode suspended = ok(201, "POST", "/hr/employees/" + id + "/suspend", Map.of("reason", "Investigation",
                "disableAccount", true));
        assertThat(suspended.get("employmentStatus").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("isActive").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT \"isActive\" FROM \"User\" WHERE id = ?").param(userId).query(Boolean.class).single())
                .isFalse();
        Res twice = as("POST", "/hr/employees/" + id + "/suspend", Map.of("reason", "Again", "disableAccount", false));
        assertThat(twice.body().get("message").asString()).isEqualTo("This employee is already suspended");
        JsonNode back = ok(201, "POST", "/hr/employees/" + id + "/reactivate", Map.of("enableAccount", true,
                "toProbation", true));
        assertThat(back.get("employmentStatus").asString()).isEqualTo("PROBATION");
        assertThat(ok(201, "POST", "/hr/employees/" + id + "/confirm", Map.of()).get("employmentStatus").asString())
                .isEqualTo("ACTIVE");
        Res sameTransfer = as("POST", "/hr/employees/" + id + "/transfer", Map.of("reason", "Nothing"));
        assertThat(sameTransfer.body().get("message").asString())
                .isEqualTo("Transfer would not change branch, department or position");
        String deptId = ok(201, "POST", "/hr/departments", Map.of("code", "T" + suffix(), "name", "Bar")).get("id")
                .asString();
        JsonNode moved = ok(201, "POST", "/hr/employees/" + id + "/transfer", Map.of("toDepartmentId", deptId,
                "reason", "Needed at the bar"));
        assertThat(moved.get("departmentId").asString()).isEqualTo(deptId);
        JsonNode terminated = ok(201, "POST", "/hr/employees/" + id + "/terminate", Map.of("reason", "Contract ended",
                "disableAccount", false, "resigned", true));
        assertThat(terminated.get("employmentStatus").asString()).isEqualTo("RESIGNED");
        Res ended = as("POST", "/hr/employees/" + id + "/suspend", Map.of("reason", "Late", "disableAccount", false));
        assertThat(ended.body().get("message").asString())
                .isEqualTo("This employee is already resigned. Reactivate them first if they were rehired.");
        JsonNode history = ok(200, "GET", "/hr/employees/" + id + "/status-history", null);
        assertThat(history.get("total").asInt()).isEqualTo(4);
        assertThat(history.get("rows").get(0).get("toStatus").asString()).isEqualTo("RESIGNED");
        assertThat(ok(200, "GET", "/hr/employees/" + id + "/transfers", null).get("total").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND action::text = 'assign'")
                .param(id).query(Long.class).single()).isGreaterThanOrEqualTo(1);
        JsonNode activity = ok(200, "GET", "/hr/employees/" + id + "/pos-activity", null);
        assertThat(activity.get("linked").asBoolean()).isTrue();
        assertThat(activity.get("sales").get("count").asInt()).isZero();
        assertThat(ok(200, "GET", "/hr/employees/" + other.get("id").asString() + "/pos-activity", null).get("linked")
                .asBoolean()).isFalse();
        JsonNode linkable = ok(200, "GET", "/hr/access/linkable-users", null);
        assertThat(linkable.toString()).doesNotContain(userId);
        ok(200, "DELETE", "/hr/employees/" + id + "/link-user", null);
    }

    @Test
    @DisplayName("attendance: shift lateness, overtime cap, double check-in refused, manual adjust audited")
    void attendance() {
        String s = suffix();
        String shiftId = ok(201, "POST", "/hr/shifts", Map.of("code", "m" + s, "name", "Morning", "startTime", "08:00",
                "endTime", "16:00", "graceMinutes", 10, "maxOvertimeMinutes", 60)).get("id").asString();
        String id = employee(Map.of()).get("id").asString();
        ok(201, "POST", "/hr/assignments", Map.of("employeeId", id, "shiftId", shiftId));
        String day = "2026-03-02";
        String tz = jdbc.sql("SELECT coalesce(timezone, 'UTC') FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        java.util.function.Function<String, String> local = t -> java.time.LocalDateTime.parse(day + "T" + t)
                .atZone(java.time.ZoneId.of(tz)).toInstant().toString();
        JsonNode in = ok(201, "POST", "/hr/attendance/clock", Map.of("employeeId", id, "eventType", "CHECK_IN",
                "timestamp", local.apply("08:30:00")));
        assertThat(in.get("shift").get("id").asString()).isEqualTo(shiftId);
        Res again = as("POST", "/hr/attendance/clock", Map.of("employeeId", id, "eventType", "CHECK_IN",
                "timestamp", local.apply("09:00:00")));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).isEqualTo("Already checked in today");
        ok(201, "POST", "/hr/attendance/clock", Map.of("employeeId", id, "eventType", "CHECK_OUT",
                "timestamp", local.apply("19:30:00")));
        String attId = in.get("id").asString();
        JsonNode row = ok(200, "GET", "/hr/attendance/" + attId, null);
        assertThat(row.get("status").asString()).isEqualTo("LATE");
        assertThat(row.get("lateMinutes").asInt()).isEqualTo(30);
        assertThat(row.get("workedMinutes").asInt()).isEqualTo(480);
        assertThat(row.get("overtimeMinutes").asInt()).isEqualTo(60);
        assertThat(row.get("logs")).hasSize(2);
        Res badEvent = as("POST", "/hr/attendance/clock", Map.of("employeeId", id, "eventType", "NAP"));
        assertThat(badEvent.body().get("message").asString()).isEqualTo("Invalid eventType: NAP");
        JsonNode list = ok(200, "GET", "/hr/attendance?employeeId=" + id + "&from=" + day + "&to=" + day, null);
        assertThat(list.get("total").asInt()).isEqualTo(1);
        JsonNode adjusted = ok(201, "POST", "/hr/attendance/manual", Map.of("employeeId", id, "date", day,
                "status", "PRESENT"));
        assertThat(adjusted.get("status").asString()).isEqualTo("PRESENT");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND action::text = 'adjust'")
                .param(attId).query(Long.class).single()).isEqualTo(1);
        JsonNode logs = ok(200, "GET", "/hr/attendance-logs?employeeId=" + id, null);
        assertThat(logs.get("total").asInt()).isEqualTo(2);
        JsonNode summary = ok(200, "GET", "/hr/attendance/summary?from=" + day + "&to=" + day, null);
        assertThat(summary.get("employees").toString()).contains(id);
    }

    @Test
    @DisplayName("leave: balance guard, overlap guard, approval stamps attendance, cancel returns days")
    void leave() {
        String s = suffix();
        String typeId = ok(201, "POST", "/hr/leave/types", Map.of("code", "al" + s, "name", "Annual " + s,
                "daysPerYear", 5)).get("id").asString();
        String id = employee(Map.of()).get("id").asString();
        Res tooMany = as("POST", "/hr/leave/requests", Map.of("employeeId", id, "leaveTypeId", typeId,
                "startDate", "2026-05-04", "endDate", "2026-05-13"));
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.body().get("message").asString())
                .isEqualTo("Insufficient leave balance: 5 day(s) available, 10 requested");
        Res noReason = as("POST", "/hr/leave/requests", Map.of("employeeId", id, "leaveTypeId", typeId,
                "startDate", "2026-05-04", "endDate", "2026-05-13", "overrideBalance", true));
        assertThat(noReason.body().get("message").asString())
                .isEqualTo("overrideBalance requires a reason explaining why the balance is being exceeded");
        JsonNode req = ok(201, "POST", "/hr/leave/requests", Map.of("employeeId", id, "leaveTypeId", typeId,
                "startDate", "2026-05-04", "endDate", "2026-05-06"));
        assertThat(req.get("requestCode").asString()).startsWith("LV-");
        assertThat(req.get("days").asString()).isEqualTo("3");
        Res overlap = as("POST", "/hr/leave/requests", Map.of("employeeId", id, "leaveTypeId", typeId,
                "startDate", "2026-05-06", "endDate", "2026-05-06"));
        assertThat(overlap.body().get("message").asString()).isEqualTo("Overlaps leave request "
                + req.get("requestCode").asString() + " (pending), which already covers part of those dates");
        String reqId = req.get("id").asString();
        JsonNode approved = ok(201, "POST", "/hr/leave/requests/" + reqId + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM \"HrAttendance\" WHERE \"employeeId\" = ? AND status::text = 'ON_LEAVE'")
                .param(id).query(Long.class).single()).isEqualTo(3);
        JsonNode balances = ok(200, "GET", "/hr/leave/balances?employeeId=" + id, null);
        assertThat(new BigDecimal(balances.get("rows").get(0).get("usedDays").asString())).isEqualByComparingTo("3");
        Res again = as("POST", "/hr/leave/requests/" + reqId + "/approve", Map.of());
        assertThat(again.body().get("message").asString()).isEqualTo("Only PENDING requests can be approved");
        ok(201, "POST", "/hr/leave/requests/" + reqId + "/cancel", null);
        assertThat(jdbc.sql("SELECT \"usedDays\" FROM \"HrLeaveBalance\" WHERE \"employeeId\" = ?").param(id)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        JsonNode adjusted = ok(201, "POST", "/hr/leave/balances/adjust", Map.of("employeeId", id, "leaveTypeId", typeId,
                "year", 2026, "adjustedDays", 2));
        assertThat(adjusted.get("adjustedDays").asString()).isEqualTo("2");
        JsonNode overview = ok(200, "GET", "/hr/reports/leave-overview?year=2026&employeeId=" + id, null);
        assertThat(overview.get("rows").get(0).get("remaining").asDouble()).isEqualTo(7.0);
        Res used = as("DELETE", "/hr/leave/types/" + typeId, null);
        assertThat(used.body().get("message").asString())
                .isEqualTo("Leave type has requests — deactivate instead of deleting");
        JsonNode holiday = ok(201, "POST", "/hr/holidays", Map.of("name", "Labour day", "date", "2031-05-01T10:00:00Z"));
        assertThat(holiday.get("date").asString()).isEqualTo("2031-05-01T00:00:00.000Z");
        assertThat(ok(200, "GET", "/hr/holidays?year=2031", null).get("total").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("timesheets: entries recompute totals, submit / approve / reject rules")
    void timesheets() {
        String id = employee(Map.of()).get("id").asString();
        JsonNode ts = ok(201, "POST", "/hr/timesheets", Map.of("employeeId", id, "periodStart", "2026-04-06",
                "periodEnd", "2026-04-12"));
        String tsId = ts.get("id").asString();
        Res empty = as("POST", "/hr/timesheets/" + tsId + "/submit", null);
        assertThat(empty.body().get("message").asString()).isEqualTo("Add at least one entry before submitting");
        JsonNode entry = ok(201, "POST", "/hr/timesheets/" + tsId + "/entries", Map.of("date", "2026-04-06",
                "startAt", "2026-04-06T08:00:00Z", "endAt", "2026-04-06T12:30:00Z", "workType", "REPAIR"));
        assertThat(entry.get("minutes").asInt()).isEqualTo(270);
        Res badType = as("POST", "/hr/timesheets/" + tsId + "/entries", Map.of("date", "2026-04-07",
                "startAt", "2026-04-07T08:00:00Z", "endAt", "2026-04-07T09:00:00Z", "workType", "NAP"));
        assertThat(badType.body().get("message").asString()).isEqualTo("Invalid workType: NAP");
        assertThat(ok(200, "GET", "/hr/timesheets/" + tsId, null).get("totalMinutes").asInt()).isEqualTo(270);
        ok(200, "PATCH", "/hr/timesheets/entries/" + entry.get("id").asString(), Map.of("endAt", "2026-04-06T13:00:00Z"));
        assertThat(ok(200, "GET", "/hr/timesheets/" + tsId, null).get("totalMinutes").asInt()).isEqualTo(300);
        assertThat(ok(201, "POST", "/hr/timesheets/" + tsId + "/submit", null).get("status").asString())
                .isEqualTo("SUBMITTED");
        Res locked = as("PATCH", "/hr/timesheets/entries/" + entry.get("id").asString(), Map.of("description", "x"));
        assertThat(locked.body().get("message").asString())
                .isEqualTo("Only DRAFT/REJECTED timesheets accept entry edits");
        JsonNode rejected = ok(201, "POST", "/hr/timesheets/" + tsId + "/reject", Map.of("reason", "Missing job"));
        assertThat(rejected.get("notes").asString()).isEqualTo("Rejected: Missing job");
        ok(201, "POST", "/hr/timesheets/" + tsId + "/submit", null);
        assertThat(ok(201, "POST", "/hr/timesheets/" + tsId + "/approve", null).get("status").asString())
                .isEqualTo("APPROVED");
        Res del = as("DELETE", "/hr/timesheets/" + tsId, null);
        assertThat(del.body().get("message").asString()).isEqualTo("Approved timesheets cannot be deleted");
        assertThat(ok(200, "GET", "/hr/timesheets?employeeId=" + id, null).get("rows").get(0).get("_count")
                .get("entries").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("payroll: calculate (allowance, PAYE brackets, loan + advance), approve posts balanced GL, reverse")
    void payroll() {
        String s = suffix();
        // Keep this run isolated from other suites' active employees (restored afterwards).
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        try {
            payrollFlow(s);
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
        }
    }

    void payrollFlow(String s) {
        String id = employee(Map.of("baseSalary", 1000000, "bankName", "Stanbic", "bankAccountNumber", "1234"))
                .get("id").asString();
        ok(201, "POST", "/hr/payroll/components", Map.of("code", "tr" + s, "name", "Transport", "componentType",
                "ALLOWANCE", "calcMethod", "FIXED", "amount", 100000));
        ok(201, "POST", "/hr/payroll/components", Map.of("code", "pension" + s, "name", "Pension", "componentType",
                "DEDUCTION", "calcMethod", "PERCENTAGE", "rate", 5));
        Res noAmount = as("POST", "/hr/payroll/components", Map.of("code", "x" + s, "name", "X",
                "componentType", "ALLOWANCE", "calcMethod", "FIXED"));
        assertThat(noAmount.body().get("message").asString()).isEqualTo("FIXED components need an amount");
        jdbc.sql("UPDATE \"HrTaxTable\" SET \"isActive\" = false WHERE \"organizationId\" = ?").param(demoOrg).update();
        JsonNode table = ok(201, "POST", "/hr/payroll/tax-tables", Map.of("code", "paye" + s, "name", "PAYE",
                "effectiveFrom", "2020-01-01", "brackets", List.of(Map.of("fromAmount", 0, "toAmount", 3000000,
                        "rate", 0), Map.of("fromAmount", 3000000, "rate", 0.1))));
        assertThat(table.get("brackets")).hasSize(2);
        ok(201, "POST", "/hr/loans", Map.of("employeeId", id, "principal", 300000, "installmentsTotal", 3));
        String advanceId = ok(201, "POST", "/hr/advances", Map.of("employeeId", id, "amount", 60000,
                "installmentMonths", 2)).get("id").asString();
        ok(201, "POST", "/hr/advances/" + advanceId + "/approve", null);

        LocalDate month = LocalDate.now(ZoneOffset.UTC).plusYears(3).withDayOfMonth(1);
        JsonNode period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", month.toString(),
                "endDate", month.plusMonths(1).minusDays(1).toString(), "periodCode", "PRD-T" + s));
        String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", period.get("id").asString())).get("id")
                .asString();
        Res dupRun = as("POST", "/hr/payroll/runs", Map.of("periodId", period.get("id").asString()));
        assertThat(dupRun.body().get("message").asString())
                .isEqualTo("A run already exists for this period (reverse it first)");
        Res early = as("POST", "/hr/payroll/runs/" + runId + "/approve", null);
        assertThat(early.body().get("message").asString()).isEqualTo("Only CALCULATED runs can be approved");
        JsonNode calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
        assertThat(calc.get("status").asString()).isEqualTo("CALCULATED");
        assertThat(calc.get("items")).hasSize(1);
        JsonNode item = calc.get("items").get(0);
        // gross = 1,000,000 + 100,000; pension 5 % of 1,000,000; PAYE 10 % above 3,000,000 annual on (1.1M − 50k)·12.
        assertThat(new BigDecimal(item.get("grossPay").asString())).isEqualByComparingTo("1100000");
        assertThat(new BigDecimal(item.get("pensionAmount").asString())).isEqualByComparingTo("50000");
        assertThat(new BigDecimal(item.get("taxAmount").asString())).isEqualByComparingTo("80000");
        assertThat(new BigDecimal(item.get("loanDeduction").asString())).isEqualByComparingTo("100000");
        assertThat(new BigDecimal(item.get("advanceDeduction").asString())).isEqualByComparingTo("30000");
        assertThat(new BigDecimal(item.get("netPay").asString())).isEqualByComparingTo("840000");
        assertThat(item.get("deductions")).hasSize(4);
        assertThat(item.get("employee").get("employeeCode").asString()).startsWith("EMP-");
        assertThat(calc.get("bankPayments")).isEmpty();

        JsonNode approved = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("glPosted").asBoolean()).isTrue();
        assertThat(approved.get("items").get(0).get("payslip").get("payslipNumber").asString()).startsWith("PS-");
        String journalId = approved.get("journalEntryId").asString();
        BigDecimal imbalance = jdbc.sql("SELECT sum(debit) - sum(credit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                .param(journalId).query(BigDecimal.class).single();
        assertThat(imbalance).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT balance FROM \"HrEmployeeLoan\" WHERE \"employeeId\" = ?").param(id)
                .query(BigDecimal.class).single()).isEqualByComparingTo("200000");
        assertThat(jdbc.sql("SELECT balance FROM \"HrSalaryAdvance\" WHERE id = ?").param(advanceId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("30000");

        JsonNode bank = ok(201, "POST", "/hr/bank-payments/generate", Map.of("runId", runId));
        assertThat(bank.get("paymentCode").asString()).startsWith("BANK-");
        assertThat(bank.get("lines")).hasSize(1);
        assertThat(bank.get("lines").get(0).get("bankAccountNumber").asString()).isEqualTo("1234");
        assertThat(ok(200, "PATCH", "/hr/bank-payments/" + bank.get("id").asString() + "/status",
                Map.of("status", "SENT")).get("status").asString()).isEqualTo("SENT");

        String payslipId = approved.get("items").get(0).get("payslip").get("id").asString();
        JsonNode slip = ok(200, "GET", "/hr/payslips/" + payslipId, null);
        assertThat(slip.get("item").get("run").get("period").get("id").asString()).isEqualTo(period.get("id").asString());
        Res badMethod = as("POST", "/hr/payslips/" + payslipId + "/paid", Map.of("paymentMethod", "GOLD"));
        assertThat(badMethod.body().get("message").asString()).isEqualTo("Invalid paymentMethod: GOLD");
        assertThat(ok(201, "POST", "/hr/payslips/" + payslipId + "/paid", Map.of("paymentMethod", "BANK"))
                .get("status").asString()).isEqualTo("PAID");
        assertThat(ok(200, "GET", "/hr/payslips?employeeId=" + id, null).get("total").asInt()).isEqualTo(1);
        JsonNode register = ok(200, "GET", "/hr/reports/payroll-register/" + runId, null);
        assertThat(register.get("register")).hasSize(1);

        Res del = as("DELETE", "/hr/payroll/runs/" + runId, null);
        assertThat(del.body().get("message").asString()).isEqualTo("Approved/paid runs cannot be deleted — reverse them");
        JsonNode reversed = ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "Wrong month"));
        assertThat(reversed.get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.get("glPosted").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversedEntryId\" = ? OR id = ?")
                .params(journalId, journalId).query(Long.class).single()).isGreaterThanOrEqualTo(1);
        JsonNode run = ok(200, "GET", "/hr/payroll/runs/" + runId, null);
        assertThat(run.get("bankPayments")).hasSize(1);
        JsonNode dash = ok(200, "GET", "/hr/dashboard", null);
        assertThat(dash.get("lastRun").get("id").asString()).isEqualTo(runId);
    }

    @Test
    @DisplayName("documents + training: date order, duplicate enrolment, delete guards")
    void documentsAndTraining() {
        String s = suffix();
        String id = employee(Map.of()).get("id").asString();
        Res order = as("POST", "/hr/documents", Map.of("employeeId", id, "title", "Permit", "issuedAt", "2026-02-01",
                "expiresAt", "2026-01-01"));
        assertThat(order.body().get("message").asString()).isEqualTo("expiresAt cannot be before issuedAt");
        ok(201, "POST", "/hr/documents", Map.of("employeeId", id, "title", "Permit", "documentType", "WORK_PERMIT",
                "expiresAt", java.time.Instant.now().plusSeconds(86400 * 10).toString()));
        assertThat(ok(200, "GET", "/hr/documents/expiring?days=30", null).toString()).contains(id);
        String programId = ok(201, "POST", "/hr/training/programs", Map.of("code", "fs" + s, "name", "Food safety"))
                .get("id").asString();
        Res dupProgram = as("POST", "/hr/training/programs", Map.of("code", "FS" + s, "name", "Again"));
        assertThat(dupProgram.body().get("message").asString()).isEqualTo("Program code \"FS" + s + "\" already exists");
        String enrolmentId = ok(201, "POST", "/hr/training/enrolments", Map.of("employeeId", id, "programId", programId))
                .get("id").asString();
        Res dup = as("POST", "/hr/training/enrolments", Map.of("employeeId", id, "programId", programId));
        assertThat(dup.body().get("message").asString())
                .isEqualTo("This employee is already enrolled on that program and has not finished it");
        JsonNode done = ok(200, "PATCH", "/hr/training/enrolments/" + enrolmentId, Map.of("status", "COMPLETED",
                "score", 92));
        assertThat(done.get("completedAt").isNull()).isFalse();
        Res guarded = as("DELETE", "/hr/training/programs/" + programId, null);
        assertThat(guarded.status()).isEqualTo(400);
        JsonNode review = ok(201, "POST", "/hr/reviews", Map.of("employeeId", id, "rating", 4));
        assertThat(review.get("status").asString()).isEqualTo("DRAFT");
        Res noEmployee = as("POST", "/hr/reviews", Map.of());
        assertThat(noEmployee.status()).isEqualTo(404);
        assertThat(noEmployee.body().get("message").asString()).isEqualTo("employeeId is required");
        assertThat(ok(200, "GET", "/hr/reports/headcount-trend?months=3", null)).hasSize(3);
        assertThat(ok(200, "GET", "/hr/reports/workforce-access", null).get("total").asInt()).isPositive();
    }

    @Test
    @DisplayName("authorization matrix: 401 anonymous on all routes, 403 without the hr permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.hr")).isEqualTo(171);
    }
}
