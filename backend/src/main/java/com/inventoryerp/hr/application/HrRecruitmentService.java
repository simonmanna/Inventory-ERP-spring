package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.core.domain.Branch;
import com.inventoryerp.hr.domain.HrApplicant;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeStatusHistory;
import com.inventoryerp.hr.domain.HrInterview;
import com.inventoryerp.hr.domain.HrJobOpening;
import com.inventoryerp.hr.domain.HrOnboardingTask;
import com.inventoryerp.hr.domain.HrOnboardingTemplate;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Recruitment and onboarding (HR-1): job openings, applicants through the stage pipeline, interviews, and the hire
 * step that turns an applicant at {@code offer} into an {@link HrEmployee} through {@link HrOrgService#createEmployee}
 * (so codes, enum checks and the {@code hr:compensation} guard stay in one place) and copies the active onboarding
 * templates to the new employee as a dated checklist. Regular routes go through {@link HrDb} scoping.
 */
@Service
public class HrRecruitmentService {

    private static final String[] EMPLOYEE_REF = {"id", "employeeCode", "firstName", "lastName"};
    private static final List<String> INTERVIEW_STATUSES = List.of("scheduled", "completed", "cancelled");
    private static final List<String> INTERVIEW_OUTCOMES = List.of("pass", "fail", "hold");
    private static final List<String> TASK_STATUSES = List.of("pending", "done", "skipped");

    private final HrDb hr;
    private final SequenceService seq;
    private final HrOrgService orgs;

    public HrRecruitmentService(HrDb hr, SequenceService seq, HrOrgService orgs) {
        this.hr = hr;
        this.seq = seq;
        this.orgs = orgs;
    }

    // ─── openings ───

    public Map<String, Object> listOpenings(Map<String, String> query) {
        HrDb.Where w = hr.where(HrJobOpening.class).eqIf("status", query.get("status"))
                .eqIf("departmentId", query.get("departmentId")).search(query.get("q"), "code", "title");
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "department", HrDepartment.class, "departmentId", "id", "name");
        hr.oneAll(rows, "position", HrPosition.class, "positionId", "id", "name");
        for (Map<String, Object> r : rows) {
            r.put("applicantCount", hr.where(HrApplicant.class).eq("openingId", r.get("id")).count());
            r.put("hiredCount", hiredCount(r.get("id")));
        }
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getOpening(String id) {
        Map<String, Object> row = requireOpening(id);
        hr.onePick(row, "department", HrDepartment.class, row.get("departmentId"), "id", "name");
        hr.onePick(row, "position", HrPosition.class, row.get("positionId"), "id", "name");
        hr.onePick(row, "hiringManager", HrEmployee.class, row.get("hiringManagerId"), EMPLOYEE_REF);
        row.put("applicants", hr.where(HrApplicant.class).eq("openingId", id).list("\"appliedOn\" DESC, code DESC"));
        row.put("hiredCount", hiredCount(id));
        return row;
    }

    public Map<String, Object> createOpening(Map<String, Object> dto) {
        if (!truthy(dto.get("title"))) {
            throw ApiException.badRequest("title is required");
        }
        assertOpeningRefs(dto);
        int headcount = positive(dto.get("headcount"), 1, "headcount");
        String code = seq.next("hr_job_opening", "JOB-", 5);
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", org());
            data.put("code", code);
            data.put("title", dto.get("title"));
            data.put("departmentId", blankToNull(dto.get("departmentId")));
            data.put("positionId", blankToNull(dto.get("positionId")));
            data.put("branchId", blankToNull(dto.get("branchId")));
            data.put("hiringManagerId", blankToNull(dto.get("hiringManagerId")));
            data.put("employmentType", nz(dto.get("employmentType"), "FULL_TIME"));
            data.put("headcount", headcount);
            data.put("status", "draft");
            data.put("closesOn", day(dto.get("closesOn"), "closesOn"));
            data.put("description", dto.get("description"));
            data.put("createdBy", user());
            Map<String, Object> row = hr.insert(HrJobOpening.class, data);
            hr.auditInTx(AuditInput.of("HrJobOpening", (String) row.get("id"), "create", null,
                    Obj.of("code", code, "title", row.get("title"))));
            return row;
        });
    }

    public Map<String, Object> updateOpening(String id, Map<String, Object> dto) {
        Map<String, Object> row = requireOpening(id);
        if ("closed".equals(row.get("status")) || "cancelled".equals(row.get("status"))) {
            throw ApiException.badRequest("A " + row.get("status") + " opening cannot be edited");
        }
        assertOpeningRefs(dto);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("title", "employmentType", "description")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        for (String f : List.of("departmentId", "positionId", "branchId", "hiringManagerId")) {
            if (dto.containsKey(f)) {
                data.put(f, blankToNull(dto.get(f)));
            }
        }
        if (dto.containsKey("headcount")) {
            data.put("headcount", positive(dto.get("headcount"), 1, "headcount"));
        }
        if (dto.containsKey("closesOn")) {
            data.put("closesOn", day(dto.get("closesOn"), "closesOn"));
        }
        data.put("updatedBy", user());
        return hr.update(HrJobOpening.class, id, data);
    }

    public Map<String, Object> setOpeningStatus(String id, Map<String, Object> dto) {
        Map<String, Object> row = requireOpening(id);
        String to = String.valueOf(dto.get("status"));
        if (!HrRecruitmentRules.OPENING_STATUSES.contains(to)) {
            throw ApiException.badRequest("Invalid status: " + to);
        }
        String from = (String) row.get("status");
        if (!HrRecruitmentRules.openingMayBecome(from, to)) {
            throw ApiException.badRequest("An opening cannot go from " + from + " to " + to);
        }
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", to);
            if ("open".equals(to) && row.get("openedOn") == null) {
                data.put("openedOn", LocalDate.now(ZoneOffset.UTC));
            }
            data.put("updatedBy", user());
            Map<String, Object> out = hr.update(HrJobOpening.class, id, data);
            hr.auditInTx(AuditInput.of("HrJobOpening", id, "update", Obj.of("status", from), Obj.of("status", to)));
            return out;
        });
    }

    public Map<String, Object> deleteOpening(String id) {
        requireOpening(id);
        if (hr.where(HrApplicant.class).eq("openingId", id).count() > 0) {
            throw ApiException.badRequest("This opening has applicants; cancel it instead");
        }
        return hr.softDelete(HrJobOpening.class, id, Obj.of("updatedBy", user()));
    }

    // ─── applicants ───

    public Map<String, Object> listApplicants(Map<String, String> query) {
        HrDb.Where w = hr.where(HrApplicant.class).eqIf("openingId", query.get("openingId"))
                .eqIf("stage", query.get("stage")).search(query.get("q"), "code", "firstName", "lastName", "email", "phone");
        List<Map<String, Object>> rows = w.list("\"appliedOn\" DESC, code DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "opening", HrJobOpening.class, "openingId", "id", "code", "title");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getApplicant(String id) {
        Map<String, Object> row = requireApplicant(id);
        hr.onePick(row, "opening", HrJobOpening.class, row.get("openingId"), "id", "code", "title", "status");
        hr.onePick(row, "employee", HrEmployee.class, row.get("employeeId"), EMPLOYEE_REF);
        row.put("interviews", hr.where(HrInterview.class).eq("applicantId", id).list("\"scheduledAt\" DESC"));
        return row;
    }

    public Map<String, Object> createApplicant(Map<String, Object> dto) {
        if (!truthy(dto.get("openingId")) || !truthy(dto.get("firstName"))) {
            throw ApiException.badRequest("openingId and firstName are required");
        }
        Map<String, Object> opening = requireOpening((String) dto.get("openingId"));
        if (!"open".equals(opening.get("status"))) {
            throw ApiException.badRequest("Applicants can only be added to an open job opening");
        }
        Object email = blankToNull(dto.get("email"));
        if (email != null && hr.where(HrApplicant.class).eq("openingId", opening.get("id"))
                .and("lower(email) = lower(?)", email).first() != null) {
            throw ApiException.badRequest("This email has already applied to this opening");
        }
        String code = seq.next("hr_applicant", "APP-", 6);
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", org());
            data.put("code", code);
            data.put("openingId", opening.get("id"));
            data.put("firstName", dto.get("firstName"));
            data.put("lastName", dto.get("lastName"));
            data.put("email", email);
            data.put("phone", dto.get("phone"));
            data.put("source", dto.get("source"));
            data.put("stage", "applied");
            data.put("appliedOn", nz(day(dto.get("appliedOn"), "appliedOn"), LocalDate.now(ZoneOffset.UTC)));
            data.put("notes", dto.get("notes"));
            data.put("createdBy", user());
            Map<String, Object> row = hr.insert(HrApplicant.class, data);
            hr.auditInTx(AuditInput.of("HrApplicant", (String) row.get("id"), "create", null,
                    Obj.of("code", code, "openingId", opening.get("id"))));
            return row;
        });
    }

    public Map<String, Object> updateApplicant(String id, Map<String, Object> dto) {
        Map<String, Object> row = requireApplicant(id);
        if (HrRecruitmentRules.stageIsFinal((String) row.get("stage"))) {
            throw ApiException.badRequest("A " + row.get("stage") + " applicant cannot be edited");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("firstName", "lastName", "phone", "source", "notes")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("email")) {
            data.put("email", blankToNull(dto.get("email")));
        }
        data.put("updatedBy", user());
        return hr.update(HrApplicant.class, id, data);
    }

    public Map<String, Object> moveApplicant(String id, Map<String, Object> dto) {
        String to = String.valueOf(dto.get("stage"));
        if (!HrRecruitmentRules.STAGES.contains(to)) {
            throw ApiException.badRequest("Invalid stage: " + to);
        }
        if ("hired".equals(to)) {
            throw ApiException.badRequest("Use the hire action, which also creates the employee");
        }
        return hr.inTx(() -> {
            Map<String, Object> row = lockApplicant(id);
            String from = (String) row.get("stage");
            if (!HrRecruitmentRules.stageMayBecome(from, to)) {
                throw ApiException.badRequest("An applicant cannot go from " + from + " to " + to);
            }
            if (("rejected".equals(to) || "withdrawn".equals(to)) && !truthy(dto.get("reason"))) {
                throw ApiException.badRequest("A reason is required");
            }
            Map<String, Object> out = hr.update(HrApplicant.class, id, Obj.of("stage", to, "stageReason", dto.get("reason"),
                    "updatedBy", user()));
            hr.auditInTx(AuditInput.of("HrApplicant", id, "update", Obj.of("stage", from), Obj.of("stage", to,
                    "reason", dto.get("reason"))));
            return out;
        });
    }

    /**
     * Hire an applicant at {@code offer}: creates the employee, links it, copies the onboarding templates and closes
     * the opening when its headcount is filled. The applicant row lock makes a double submit hire only once.
     */
    public Map<String, Object> hire(String id, Map<String, Object> dto) {
        return hr.inTx(() -> {
            Map<String, Object> applicant = lockApplicant(id);
            if (!"offer".equals(applicant.get("stage"))) {
                throw ApiException.badRequest("Only an applicant at the offer stage can be hired");
            }
            String openingId = (String) applicant.get("openingId");
            Map<String, Object> opening = requireOpening(openingId);
            hr.jdbc().sql("SELECT id FROM \"HrJobOpening\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(openingId, org()).query().listOfRows();
            if (!"open".equals(opening.get("status")) && !"on_hold".equals(opening.get("status"))) {
                throw ApiException.badRequest("The opening is " + opening.get("status"));
            }
            long hired = hiredCount(openingId);
            int headcount = PosSql.intOf(opening.get("headcount"));
            if (hired >= headcount) {
                throw ApiException.badRequest("This opening's headcount is already filled");
            }
            LocalDate hireDate = nzDay(day(dto.get("hireDate"), "hireDate"));
            Map<String, Object> emp = new LinkedHashMap<>();
            emp.put("firstName", applicant.get("firstName"));
            emp.put("lastName", applicant.get("lastName"));
            emp.put("email", applicant.get("email"));
            emp.put("phone", applicant.get("phone"));
            emp.put("employmentType", nz(dto.get("employmentType"), opening.get("employmentType")));
            emp.put("hireDate", hireDate.toString());
            emp.put("departmentId", nz(blankToNull(dto.get("departmentId")), opening.get("departmentId")));
            emp.put("positionId", nz(blankToNull(dto.get("positionId")), opening.get("positionId")));
            emp.put("supervisorId", nz(blankToNull(dto.get("supervisorId")), opening.get("hiringManagerId")));
            if (truthy(dto.get("probationEndDate"))) {
                emp.put("probationEndDate", day(dto.get("probationEndDate"), "probationEndDate").toString());
            }
            for (String compensation : List.of("baseSalary", "hourlyRate", "payFrequency")) {
                if (dto.containsKey(compensation)) {
                    emp.put(compensation, dto.get(compensation));
                }
            }
            Map<String, Object> employee = orgs.createEmployee(emp);
            String employeeId = (String) employee.get("id");
            if (opening.get("branchId") != null) {
                hr.update(HrEmployee.class, employeeId, Obj.of("branchId", opening.get("branchId")));
            }
            if (truthy(dto.get("probationEndDate"))) {
                toProbation(employeeId, hireDate);
            }
            hr.update(HrApplicant.class, id, Obj.of("stage", "hired", "employeeId", employeeId,
                    "hiredAt", PosDb.raw(PosSql.NOW), "updatedBy", user()));
            List<Map<String, Object>> tasks = copyTemplates(employeeId, id, hireDate);
            if (hired + 1 >= headcount) {
                hr.update(HrJobOpening.class, openingId, Obj.of("status", "closed", "updatedBy", user()));
            }
            hr.auditInTx(AuditInput.of("HrApplicant", id, "update", Obj.of("stage", "offer"),
                    Obj.of("stage", "hired", "employeeId", employeeId)));
            return Obj.of("applicant", hr.byId(HrApplicant.class, id), "employee", employee, "tasks", tasks);
        });
    }

    private void toProbation(String employeeId, LocalDate hireDate) {
        hr.update(HrEmployee.class, employeeId, Obj.of("employmentStatus", "PROBATION"));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("organizationId", org());
        h.put("employeeId", employeeId);
        h.put("fromStatus", "ACTIVE");
        h.put("toStatus", "PROBATION");
        h.put("effectiveDate", hireDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        h.put("reason", "Hired through recruitment");
        h.put("accountDisabled", false);
        h.put("actorUserId", user());
        hr.insert(HrEmployeeStatusHistory.class, h);
    }

    // ─── interviews ───

    public Map<String, Object> addInterview(String applicantId, Map<String, Object> dto) {
        Map<String, Object> applicant = requireApplicant(applicantId);
        if (HrRecruitmentRules.stageIsFinal((String) applicant.get("stage"))) {
            throw ApiException.badRequest("A " + applicant.get("stage") + " applicant cannot be interviewed");
        }
        if (!truthy(dto.get("scheduledAt"))) {
            throw ApiException.badRequest("scheduledAt is required");
        }
        return hr.insert(HrInterview.class, Obj.of("organizationId", org(), "applicantId", applicantId,
                "scheduledAt", HrDb.jsDate(dto.get("scheduledAt")), "interviewer", dto.get("interviewer"),
                "location", dto.get("location"), "status", "scheduled", "notes", dto.get("notes"), "createdBy", user()));
    }

    public Map<String, Object> updateInterview(String id, Map<String, Object> dto) {
        if (hr.byId(HrInterview.class, id) == null) {
            throw ApiException.notFound("Interview not found");
        }
        if (truthy(dto.get("status")) && !INTERVIEW_STATUSES.contains(dto.get("status"))) {
            throw ApiException.badRequest("Invalid status: " + dto.get("status"));
        }
        if (truthy(dto.get("outcome")) && !INTERVIEW_OUTCOMES.contains(dto.get("outcome"))) {
            throw ApiException.badRequest("Invalid outcome: " + dto.get("outcome"));
        }
        if (truthy(dto.get("score"))) {
            int score = intVal(dto.get("score"));
            if (score < 1 || score > 5) {
                throw ApiException.badRequest("score must be between 1 and 5");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("interviewer", "location", "status", "outcome", "score", "notes")) {
            if (dto.containsKey(f)) {
                data.put(f, blankToNull(dto.get(f)));
            }
        }
        if (truthy(dto.get("scheduledAt"))) {
            data.put("scheduledAt", HrDb.jsDate(dto.get("scheduledAt")));
        }
        data.put("updatedBy", user());
        return hr.update(HrInterview.class, id, data);
    }

    // ─── onboarding templates ───

    public Map<String, Object> listTemplates(Map<String, String> query) {
        HrDb.Where w = hr.where(HrOnboardingTemplate.class);
        return HrDb.rows(w.list("sequence ASC, title ASC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip"))), w.count());
    }

    public Map<String, Object> createTemplate(Map<String, Object> dto) {
        if (!truthy(dto.get("title"))) {
            throw ApiException.badRequest("title is required");
        }
        return hr.insert(HrOnboardingTemplate.class, Obj.of("organizationId", org(), "title", dto.get("title"),
                "description", dto.get("description"), "sequence", intVal(nz(dto.get("sequence"), 10)),
                "dueDays", notNegative(dto.get("dueDays")), "isRequired", nz(dto.get("isRequired"), true),
                "responsible", dto.get("responsible"), "isActive", nz(dto.get("isActive"), true), "createdBy", user()));
    }

    public Map<String, Object> updateTemplate(String id, Map<String, Object> dto) {
        if (hr.byId(HrOnboardingTemplate.class, id) == null) {
            throw ApiException.notFound("Onboarding template not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("title", "description", "isRequired", "responsible", "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("sequence")) {
            data.put("sequence", intVal(dto.get("sequence")));
        }
        if (dto.containsKey("dueDays")) {
            data.put("dueDays", notNegative(dto.get("dueDays")));
        }
        data.put("updatedBy", user());
        return hr.update(HrOnboardingTemplate.class, id, data);
    }

    public Map<String, Object> deleteTemplate(String id) {
        if (hr.byId(HrOnboardingTemplate.class, id) == null) {
            throw ApiException.notFound("Onboarding template not found");
        }
        return hr.softDelete(HrOnboardingTemplate.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    // ─── onboarding tasks ───

    public Map<String, Object> listTasks(Map<String, String> query) {
        HrDb.Where w = hr.where(HrOnboardingTask.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"dueOn\" ASC NULLS LAST, sequence ASC",
                HrDb.take(query.get("take"), 100, 200), HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> addTask(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("title"))) {
            throw ApiException.badRequest("employeeId and title are required");
        }
        requireEmployee(dto.get("employeeId"));
        return hr.insert(HrOnboardingTask.class, Obj.of("organizationId", org(), "employeeId", dto.get("employeeId"),
                "title", dto.get("title"), "description", dto.get("description"),
                "sequence", intVal(nz(dto.get("sequence"), 100)), "dueOn", day(dto.get("dueOn"), "dueOn"),
                "isRequired", nz(dto.get("isRequired"), true), "responsible", dto.get("responsible"),
                "status", "pending", "createdBy", user()));
    }

    /** Complete, skip or reopen a task; a required task cannot be skipped. */
    public Map<String, Object> updateTask(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrOnboardingTask.class, id);
        if (row == null) {
            throw ApiException.notFound("Onboarding task not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (truthy(dto.get("status"))) {
            Object status = dto.get("status");
            if (!TASK_STATUSES.contains(status)) {
                throw ApiException.badRequest("Invalid status: " + status);
            }
            if ("skipped".equals(status) && Boolean.TRUE.equals(row.get("isRequired"))) {
                throw ApiException.badRequest("A required task cannot be skipped");
            }
            boolean done = "done".equals(status);
            data.put("status", status);
            data.put("completedAt", done ? PosDb.raw(PosSql.NOW) : null);
            data.put("completedBy", done ? user() : null);
        }
        for (String f : List.of("notes", "responsible")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("dueOn")) {
            data.put("dueOn", day(dto.get("dueOn"), "dueOn"));
        }
        return hr.update(HrOnboardingTask.class, id, data);
    }

    /** Copy the active templates to an employee hired outside recruitment (repeat calls add only what is missing). */
    public Map<String, Object> generateChecklist(String employeeId) {
        Map<String, Object> employee = requireEmployee(employeeId);
        LocalDate from = employee.get("hireDate") instanceof Instant i ? i.atOffset(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> created = hr.inTx(() -> copyTemplates(employeeId, null, from));
        return HrDb.rows(created, created.size());
    }

    private List<Map<String, Object>> copyTemplates(String employeeId, String applicantId, LocalDate from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : hr.where(HrOnboardingTemplate.class).eq("isActive", true).list("sequence ASC")) {
            if (hr.where(HrOnboardingTask.class).eq("employeeId", employeeId).eq("templateId", t.get("id"))
                    .first() != null) {
                continue;
            }
            out.add(hr.insert(HrOnboardingTask.class, Obj.of("organizationId", org(), "employeeId", employeeId,
                    "applicantId", applicantId, "templateId", t.get("id"), "title", t.get("title"),
                    "description", t.get("description"), "sequence", t.get("sequence"),
                    "dueOn", from.plusDays(PosSql.intOf(t.get("dueDays"))), "isRequired", t.get("isRequired"),
                    "responsible", t.get("responsible"), "status", "pending", "createdBy", user())));
        }
        return out;
    }

    // ─── helpers ───

    private long hiredCount(Object openingId) {
        return hr.where(HrApplicant.class).eq("openingId", openingId).eq("stage", "hired").count();
    }

    private Map<String, Object> requireOpening(String id) {
        Map<String, Object> row = hr.byId(HrJobOpening.class, id);
        if (row == null) {
            throw ApiException.notFound("Job opening not found");
        }
        return row;
    }

    private Map<String, Object> requireApplicant(String id) {
        Map<String, Object> row = hr.byId(HrApplicant.class, id);
        if (row == null) {
            throw ApiException.notFound("Applicant not found");
        }
        return row;
    }

    private Map<String, Object> lockApplicant(String id) {
        hr.jdbc().sql("SELECT id FROM \"HrApplicant\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org()).query().listOfRows();
        return requireApplicant(id);
    }

    private Map<String, Object> requireEmployee(Object id) {
        Map<String, Object> row = hr.byId(HrEmployee.class, id);
        if (row == null) {
            throw ApiException.notFound("Employee not found");
        }
        return row;
    }

    private void assertOpeningRefs(Map<String, Object> dto) {
        if (truthy(dto.get("departmentId")) && hr.byId(HrDepartment.class, dto.get("departmentId")) == null) {
            throw ApiException.notFound("Department not found");
        }
        if (truthy(dto.get("positionId")) && hr.byId(HrPosition.class, dto.get("positionId")) == null) {
            throw ApiException.notFound("Position not found");
        }
        if (truthy(dto.get("branchId")) && hr.byId(Branch.class, dto.get("branchId")) == null) {
            throw ApiException.notFound("Branch not found");
        }
        if (truthy(dto.get("hiringManagerId")) && hr.byId(HrEmployee.class, dto.get("hiringManagerId")) == null) {
            throw ApiException.notFound("Hiring manager not found");
        }
    }

    private static Object blankToNull(Object v) {
        return v instanceof String s && s.isBlank() ? null : v;
    }

    private static int positive(Object v, int def, String what) {
        int n = v == null ? def : intVal(v);
        if (n <= 0) {
            throw ApiException.badRequest(what + " must be positive");
        }
        return n;
    }

    private static int notNegative(Object v) {
        int n = v == null ? 0 : intVal(v);
        if (n < 0) {
            throw ApiException.badRequest("dueDays cannot be negative");
        }
        return n;
    }

    /** A body number (JSON number or numeric string) as an int. */
    static int intVal(Object v) {
        double n = HrDb.jsNumber(v);
        if (Double.isNaN(n)) {
            throw ApiException.badRequest("Invalid number: " + v);
        }
        return (int) n;
    }

    private static LocalDate nzDay(LocalDate d) {
        return d != null ? d : LocalDate.now(ZoneOffset.UTC);
    }

    /** A {@code YYYY-MM-DD} (or ISO timestamp) body value as a date; blank → null. */
    static LocalDate day(Object v, String what) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        String s = v.toString().trim();
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date for " + what + ": " + s);
        }
    }
}
