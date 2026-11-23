package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Sales teams ↔ HR (deviation, docs/spec/crm.md): members and leader must be active HR employees with a login. */
class CrmTeamsHrIT extends ApiTestSupport {

    String sfx;
    String dept;
    String branch;
    String salesUser;
    String salesEmployee;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
        dept = ok(201, "POST", "/hr/departments", Map.of("code", "SL" + sfx, "name", "Sales " + sfx)).get("id")
                .asString();
        branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " code LIMIT 1").param(demoOrg).query(String.class).single();
        salesUser = createUser(demoOrg, "rep-" + sfx + "@demo.test", "Rep@123456", null);
        salesEmployee = employee("Rita", salesUser);
    }

    String employee(String firstName, String userId) {
        String id = ok(201, "POST", "/hr/employees", Map.of("firstName", firstName, "lastName", "Rep " + sfx,
                "departmentId", dept)).get("id").asString();
        if (userId != null) {
            ok(201, "POST", "/hr/employees/" + id + "/link-user", Map.of("userId", userId));
        }
        return id;
    }

    JsonNode team(String id) {
        for (JsonNode t : ok(200, "GET", "/crm/config/lookups", null).get("teams")) {
            if (t.get("id").asString().equals(id)) {
                return t;
            }
        }
        throw new AssertionError("team " + id + " not in lookups");
    }

    @Test
    @DisplayName("candidates are active employees with a login; departments and branches offered")
    void candidates() {
        String noLogin = employee("Nolan", null);
        JsonNode c = ok(200, "GET", "/crm/config/team-candidates", null);
        List<String> employees = c.get("employees").valueStream().map(e -> e.get("employeeId").asString()).toList();
        assertThat(employees).contains(salesEmployee).doesNotContain(noLogin);
        assertThat(c.get("departments").valueStream().map(d -> d.get("id").asString())).contains(dept);
        assertThat(c.get("branches").valueStream().map(d -> d.get("id").asString())).contains(branch);
    }

    @Test
    @DisplayName("save: HR employee admitted with department + branch; non-employee, terminated and bad ids refused")
    void rules() {
        JsonNode t = ok(201, "POST", "/crm/config/teams", Map.of("name", "Kampala " + sfx, "leaderId", salesUser,
                "memberIds", List.of(salesUser), "departmentId", dept, "branchId", branch));
        String id = t.get("id").asString();
        JsonNode view = team(id);
        assertThat(view.get("departmentName").asString()).isEqualTo("Sales " + sfx);
        assertThat(view.get("branchId").asString()).isEqualTo(branch);
        assertThat(view.get("notEligibleCount").asInt()).isZero();
        JsonNode m = view.get("members").get(0);
        assertThat(m.get("eligible").asBoolean()).isTrue();
        assertThat(m.get("employee").get("employeeId").asString()).isEqualTo(salesEmployee);
        assertThat(view.get("leader").get("employee").get("departmentName").asString()).isEqualTo("Sales " + sfx);

        String plainUser = createUser(demoOrg, "plain-" + sfx + "@demo.test", "Plain@1234", null);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("memberIds", List.of(salesUser, plainUser)),
                Map.of("leaderId", plainUser))) {
            Res r = as("PATCH", "/crm/config/teams/" + id, bad);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString())
                    .isEqualTo("Sales team members must be active HR employees linked to a user account");
        }
        assertThat(as("PATCH", "/crm/config/teams/" + id, Map.of("departmentId", UUID.randomUUID().toString()))
                .status()).isEqualTo(404);
        assertThat(as("PATCH", "/crm/config/teams/" + id, Map.of("branchId", UUID.randomUUID().toString()))
                .body().get("message").asString()).isEqualTo("Branch not found");

        String leaver = createUser(demoOrg, "leaver-" + sfx + "@demo.test", "Leave@1234", null);
        String leaverEmp = employee("Lee", leaver);
        jdbc.sql("UPDATE \"HrEmployee\" SET \"employmentStatus\" = 'TERMINATED' WHERE id = ?").param(leaverEmp).update();
        assertThat(as("PATCH", "/crm/config/teams/" + id, Map.of("memberIds", List.of(salesUser, leaver))).status())
                .isEqualTo(400);

        ok(200, "PATCH", "/crm/config/teams/" + id, Map.of("departmentId", "", "branchId", ""));
        assertThat(team(id).get("departmentId").isNull()).isTrue();
    }

    @Test
    @DisplayName("legacy: a member admitted before the HR link is kept, flagged, and does not block later edits")
    void legacyMember() {
        String legacyUser = createUser(demoOrg, "legacy-" + sfx + "@demo.test", "Legacy@123", null);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CrmTeam\" (id, \"organizationId\", name, \"leaderId\", \"memberIds\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, now())").params(id, demoOrg, "Legacy " + sfx, legacyUser,
                        new String[] {legacyUser}).update();
        JsonNode view = team(id);
        assertThat(view.get("notEligibleCount").asInt()).isEqualTo(2);
        assertThat(view.get("members").get(0).get("eligible").asBoolean()).isFalse();
        assertThat(view.get("members").get(0).get("employee").isNull()).isTrue();

        // Adding an employee while keeping the legacy member (and leader) is allowed.
        ok(200, "PATCH", "/crm/config/teams/" + id, Map.of("name", "Legacy 2 " + sfx, "leaderId", legacyUser,
                "memberIds", List.of(legacyUser, salesUser)));
        assertThat(team(id).get("members").size()).isEqualTo(2);
        // Re-adding a removed legacy member is a new admission → refused.
        ok(200, "PATCH", "/crm/config/teams/" + id, Map.of("memberIds", List.of(salesUser)));
        assertThat(as("PATCH", "/crm/config/teams/" + id, Map.of("memberIds", List.of(salesUser, legacyUser)))
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("employee profile lists the sales teams the employee leads or belongs to")
    void employeeProfile() {
        String lead = createUser(demoOrg, "lead-" + sfx + "@demo.test", "Lead@12345", null);
        String leadEmp = employee("Leah", lead);
        String id = ok(201, "POST", "/crm/config/teams", Map.of("name", "Profile " + sfx, "leaderId", lead,
                "memberIds", List.of(salesUser), "departmentId", dept)).get("id").asString();
        JsonNode leader = ok(200, "GET", "/hr/employees/" + leadEmp, null).get("salesTeams");
        assertThat(leader.valueStream().filter(t -> t.get("id").asString().equals(id))
                .map(t -> t.get("role").asString())).containsExactly("leader");
        JsonNode member = ok(200, "GET", "/hr/employees/" + salesEmployee, null).get("salesTeams");
        assertThat(member.valueStream().filter(t -> t.get("id").asString().equals(id))
                .map(t -> t.get("departmentName").asString())).containsExactly("Sales " + sfx);
        assertThat(ok(200, "GET", "/hr/employees/" + employee("Nobody", null), null).get("salesTeams").size())
                .isZero();
    }

    @Test
    @DisplayName("team-candidates needs crm:configure")
    void authorization() {
        assertThat(call("GET", "/crm/config/team-candidates", null, weakToken()).status()).isEqualTo(403);
    }
}
