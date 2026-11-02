package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrTalentService;
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

/** {@code /hr/talent} (HP-9): competencies, review cycles, goals, appraisals. */
@RestController
@RequestMapping("/hr/talent")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrTalentController {

    private final HrTalentService talent;

    public HrTalentController(HrTalentService talent) {
        this.talent = talent;
    }

    @GetMapping("/competencies")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> listCompetencies() {
        return talent.listCompetencies();
    }

    @PostMapping("/competencies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> createCompetency(@RequestBody Map<String, Object> dto) {
        return talent.saveCompetency(null, dto);
    }

    @PatchMapping("/competencies/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> updateCompetency(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.saveCompetency(id, dto);
    }

    @DeleteMapping("/competencies/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> deleteCompetency(@PathVariable String id) {
        return talent.deleteCompetency(id);
    }

    @GetMapping("/cycles")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> listCycles() {
        return talent.listCycles();
    }

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> createCycle(@RequestBody Map<String, Object> dto) {
        return talent.saveCycle(null, dto);
    }

    @GetMapping("/cycles/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> getCycle(@PathVariable String id) {
        return talent.getCycle(id);
    }

    @PatchMapping("/cycles/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> updateCycle(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.saveCycle(id, dto);
    }

    @PostMapping("/cycles/{id}/launch")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> launch(@PathVariable String id) {
        return talent.launch(id);
    }

    @PostMapping("/cycles/{id}/peers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> addPeer(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.addPeer(id, dto);
    }

    @PostMapping("/cycles/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> close(@PathVariable String id) {
        return talent.close(id);
    }

    @GetMapping("/goals")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> listGoals(@RequestParam Map<String, String> query) {
        return talent.listGoals(query);
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> createGoal(@RequestBody Map<String, Object> dto) {
        return talent.saveGoal(null, dto);
    }

    @PatchMapping("/goals/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> updateGoal(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.saveGoal(id, dto);
    }

    @DeleteMapping("/goals/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> deleteGoal(@PathVariable String id) {
        return talent.deleteGoal(id);
    }

    @GetMapping("/appraisals/{id}")
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> getAppraisal(@PathVariable String id) {
        return talent.getAppraisal(id);
    }

    @PostMapping("/appraisals/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__TALENT)
    public Map<String, Object> submit(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return talent.submit(id, dto, null);
    }
}
