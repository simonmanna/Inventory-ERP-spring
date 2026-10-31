package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrRecruitmentService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /hr/recruitment} and {@code /hr/onboarding} (HR-1): job openings, applicants, interviews, hiring and
 * onboarding checklists. Bodies are untyped maps like the rest of {@code /hr}; the service validates them.
 */
@RestController
@RequestMapping("/hr")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrRecruitmentController {

    private final HrRecruitmentService recruitment;

    public HrRecruitmentController(HrRecruitmentService recruitment) {
        this.recruitment = recruitment;
    }

    // ─── openings ───

    @GetMapping("/recruitment/openings")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> listOpenings(@RequestParam Map<String, String> query) {
        return recruitment.listOpenings(query);
    }

    @GetMapping("/recruitment/openings/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> getOpening(@PathVariable String id) {
        return recruitment.getOpening(id);
    }

    @PostMapping("/recruitment/openings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> createOpening(@RequestBody Map<String, Object> dto) {
        return recruitment.createOpening(dto);
    }

    @PatchMapping("/recruitment/openings/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> updateOpening(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.updateOpening(id, dto);
    }

    @PostMapping("/recruitment/openings/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> setOpeningStatus(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.setOpeningStatus(id, dto);
    }

    @DeleteMapping("/recruitment/openings/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> deleteOpening(@PathVariable String id) {
        return recruitment.deleteOpening(id);
    }

    // ─── applicants ───

    @GetMapping("/recruitment/applicants")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> listApplicants(@RequestParam Map<String, String> query) {
        return recruitment.listApplicants(query);
    }

    @GetMapping("/recruitment/applicants/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> getApplicant(@PathVariable String id) {
        return recruitment.getApplicant(id);
    }

    @PostMapping("/recruitment/applicants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> createApplicant(@RequestBody Map<String, Object> dto) {
        return recruitment.createApplicant(dto);
    }

    @PatchMapping("/recruitment/applicants/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> updateApplicant(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.updateApplicant(id, dto);
    }

    @PostMapping("/recruitment/applicants/{id}/stage")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> moveApplicant(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.moveApplicant(id, dto);
    }

    @PostMapping("/recruitment/applicants/{id}/hire")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.HR__RECRUITMENT, Permissions.HR__EMPLOYEE})
    public Map<String, Object> hire(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.hire(id, dto);
    }

    @PostMapping("/recruitment/applicants/{id}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> addInterview(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.addInterview(id, dto);
    }

    @PatchMapping("/recruitment/interviews/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> updateInterview(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.updateInterview(id, dto);
    }

    // ─── onboarding ───

    @GetMapping("/onboarding/templates")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> listTemplates(@RequestParam Map<String, String> query) {
        return recruitment.listTemplates(query);
    }

    @PostMapping("/onboarding/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> createTemplate(@RequestBody Map<String, Object> dto) {
        return recruitment.createTemplate(dto);
    }

    @PatchMapping("/onboarding/templates/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> updateTemplate(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.updateTemplate(id, dto);
    }

    @DeleteMapping("/onboarding/templates/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> deleteTemplate(@PathVariable String id) {
        return recruitment.deleteTemplate(id);
    }

    @GetMapping("/onboarding/tasks")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> listTasks(@RequestParam Map<String, String> query) {
        return recruitment.listTasks(query);
    }

    @PostMapping("/onboarding/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> addTask(@RequestBody Map<String, Object> dto) {
        return recruitment.addTask(dto);
    }

    @PatchMapping("/onboarding/tasks/{id}")
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> updateTask(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return recruitment.updateTask(id, dto);
    }

    @PostMapping("/onboarding/employees/{employeeId}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__RECRUITMENT)
    public Map<String, Object> generateChecklist(@PathVariable String employeeId) {
        return recruitment.generateChecklist(employeeId);
    }
}
