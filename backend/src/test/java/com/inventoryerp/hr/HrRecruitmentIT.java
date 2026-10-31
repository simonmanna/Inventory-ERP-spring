package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.web.HrRecruitmentController;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Recruitment and onboarding (HR-1): opening lifecycle, applicant pipeline, hire, checklists, tenancy, permissions. */
class HrRecruitmentIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    String openOpening(int headcount) {
        String id = ok(201, "POST", "/hr/recruitment/openings", Map.of("title", "Quarry operator " + suffix(),
                "headcount", headcount)).get("id").asString();
        ok(201, "POST", "/hr/recruitment/openings/" + id + "/status", Map.of("status", "open"));
        return id;
    }

    String applicant(String openingId) {
        return ok(201, "POST", "/hr/recruitment/applicants", Map.of("openingId", openingId, "firstName", "Grace",
                "email", "grace-" + suffix() + "@demo.test")).get("id").asString();
    }

    void toOffer(String applicantId) {
        ok(201, "POST", "/hr/recruitment/applicants/" + applicantId + "/stage", Map.of("stage", "interview"));
        ok(201, "POST", "/hr/recruitment/applicants/" + applicantId + "/stage", Map.of("stage", "offer"));
    }

    @Test
    @DisplayName("an opening moves draft → open → closed; applicants need an open opening; a draft can be deleted")
    void openingLifecycle() {
        JsonNode draft = ok(201, "POST", "/hr/recruitment/openings", Map.of("title", "Draft " + suffix()));
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("code").asString()).startsWith("JOB-");
        Res closed = as("POST", "/hr/recruitment/applicants", Map.of("openingId", id, "firstName", "A"));
        assertThat(closed.status()).isEqualTo(400);
        assertThat(as("POST", "/hr/recruitment/openings/" + id + "/status", Map.of("status", "closed")).status())
                .isEqualTo(400);
        JsonNode open = ok(201, "POST", "/hr/recruitment/openings/" + id + "/status", Map.of("status", "open"));
        assertThat(open.get("openedOn").isNull()).isFalse();
        applicant(id);
        assertThat(as("DELETE", "/hr/recruitment/openings/" + id, null).status()).isEqualTo(400);
        String empty = ok(201, "POST", "/hr/recruitment/openings", Map.of("title", "Empty " + suffix())).get("id")
                .asString();
        ok(200, "DELETE", "/hr/recruitment/openings/" + empty, null);
        assertThat(as("GET", "/hr/recruitment/openings/" + empty, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the pipeline refuses skipped or final moves; a rejection needs a reason; the same email cannot reapply")
    void pipeline() {
        String opening = openOpening(1);
        String email = "dup-" + suffix() + "@demo.test";
        String id = ok(201, "POST", "/hr/recruitment/applicants", Map.of("openingId", opening, "firstName", "Tom",
                "email", email)).get("id").asString();
        Res dup = as("POST", "/hr/recruitment/applicants", Map.of("openingId", opening, "firstName", "Tim",
                "email", email.toUpperCase()));
        assertThat(dup.status()).isEqualTo(400);
        assertThat(as("POST", "/hr/recruitment/applicants/" + id + "/stage", Map.of("stage", "offer")).status())
                .isEqualTo(400);
        assertThat(as("POST", "/hr/recruitment/applicants/" + id + "/stage", Map.of("stage", "hired")).status())
                .isEqualTo(400);
        assertThat(as("POST", "/hr/recruitment/applicants/" + id + "/stage", Map.of("stage", "rejected")).status())
                .isEqualTo(400);
        ok(201, "POST", "/hr/recruitment/applicants/" + id + "/stage", Map.of("stage", "rejected", "reason", "No ticket"));
        assertThat(as("POST", "/hr/recruitment/applicants/" + id + "/stage", Map.of("stage", "screening")).status())
                .isEqualTo(400);
        assertThat(as("PATCH", "/hr/recruitment/applicants/" + id, Map.of("phone", "1")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("hiring creates the employee, copies the onboarding templates, fills the headcount and only happens once")
    void hire() {
        String title = "Safety induction " + suffix();
        ok(201, "POST", "/hr/onboarding/templates", Map.of("title", title, "dueDays", 3));
        String opening = openOpening(1);
        String id = applicant(opening);
        ok(201, "POST", "/hr/recruitment/applicants/" + id + "/interviews", Map.of("scheduledAt",
                "2026-11-02T08:00:00Z", "interviewer", "Site manager"));
        Res early = as("POST", "/hr/recruitment/applicants/" + id + "/hire", Map.of());
        assertThat(early.status()).isEqualTo(400);
        toOffer(id);
        JsonNode out = ok(201, "POST", "/hr/recruitment/applicants/" + id + "/hire", Map.of("hireDate", "2026-11-02",
                "probationEndDate", "2027-02-02"));
        String employeeId = out.get("employee").get("id").asString();
        assertThat(out.get("applicant").get("stage").asString()).isEqualTo("hired");
        assertThat(out.get("applicant").get("employeeId").asString()).isEqualTo(employeeId);
        assertThat(ok(200, "GET", "/hr/employees/" + employeeId, null).get("employmentStatus").asString())
                .isEqualTo("PROBATION");
        JsonNode tasks = ok(200, "GET", "/hr/onboarding/tasks?employeeId=" + employeeId, null);
        JsonNode task = null;
        for (JsonNode t : tasks.get("rows")) {
            if (title.equals(t.get("title").asString())) {
                task = t;
            }
        }
        assertThat(task).isNotNull();
        assertThat(task.get("dueOn").asString()).isEqualTo("2026-11-05");
        assertThat(as("PATCH", "/hr/onboarding/tasks/" + task.get("id").asString(), Map.of("status", "skipped")).status())
                .isEqualTo(400);
        JsonNode done = ok(200, "PATCH", "/hr/onboarding/tasks/" + task.get("id").asString(), Map.of("status", "done"));
        assertThat(done.get("completedAt").isNull()).isFalse();
        // The headcount of one is filled: the opening closed, and a second hire is refused.
        assertThat(ok(200, "GET", "/hr/recruitment/openings/" + opening, null).get("status").asString())
                .isEqualTo("closed");
        assertThat(as("POST", "/hr/recruitment/applicants/" + id + "/hire", Map.of()).status()).isEqualTo(400);
        JsonNode again = ok(201, "POST", "/hr/onboarding/employees/" + employeeId + "/generate", null);
        assertThat(again.get("rows").toString()).doesNotContain(title);
    }

    @Test
    @DisplayName("hiring with pay needs hr:compensation, and nothing is half-created when it is refused")
    void hireNeedsCompensation() {
        String opening = openOpening(1);
        String id = applicant(opening);
        toOffer(id);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Recruiter-" + role,
                new String[] {"hr:recruitment", "hr:employee"}).update();
        String email = "rec-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Hr@1234567", role);
        String token = login("DEMO", email, "Hr@1234567");
        Res refused = call("POST", "/hr/recruitment/applicants/" + id + "/hire", Map.of("baseSalary", 500000), token);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(ok(200, "GET", "/hr/recruitment/applicants/" + id, null).get("stage").asString()).isEqualTo("offer");
    }

    @Test
    @DisplayName("another organization sees none of this")
    void tenantIsolation() {
        String opening = openOpening(1);
        String id = applicant(opening);
        assertThat(call("GET", "/hr/recruitment/openings/" + opening, null, otherToken).status()).isIn(403, 404);
        assertThat(call("GET", "/hr/recruitment/applicants/" + id, null, otherToken).status()).isIn(403, 404);
        assertThat(call("GET", "/hr/recruitment/openings", null, otherToken).raw().body()).doesNotContain(opening);
    }

    @Test
    @DisplayName("authorization matrix: 401 anonymous, 403 without the permission, on all 22 routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(HrRecruitmentController.class)).isEqualTo(22);
    }
}
