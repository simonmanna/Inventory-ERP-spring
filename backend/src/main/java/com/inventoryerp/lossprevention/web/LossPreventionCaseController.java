package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.inventoryerp.lossprevention.application.LpCaseService;
import com.inventoryerp.lossprevention.application.LpCommands.AssignInput;
import com.inventoryerp.lossprevention.application.LpCommands.CaseQuery;
import com.inventoryerp.lossprevention.application.LpCommands.CloseInput;
import com.inventoryerp.lossprevention.application.LpCommands.EscalateInput;
import com.inventoryerp.lossprevention.application.LpCommands.NoteInput;

/** {@code /loss-prevention/cases} (ADR-S077 §6): review of a variance, closed with a factual outcome. */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionCaseController {

    private final LpCaseService cases;

    public LossPreventionCaseController(LpCaseService cases) {
        this.cases = cases;
    }

    @GetMapping("/cases")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public PageResult<Map<String, Object>> cases(@RequestParam(required = false) String state,
            @RequestParam(required = false) String outcome, @RequestParam(required = false) String engine,
            @RequestParam(required = false) String ruleCode, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String subjectId, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return cases.list(new CaseQuery(state, outcome, engine, ruleCode, branchId, subjectId, assigneeId, severity,
                from, to, page, pageSize));
    }

    @GetMapping("/cases/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> get(@PathVariable String id) {
        return cases.get(id);
    }

    @PostMapping("/cases/{id}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> assign(@PathVariable String id, @Valid @RequestBody AssignInput dto) {
        return cases.assign(id, dto.assigneeId());
    }

    @PostMapping("/cases/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> note(@PathVariable String id, @Valid @RequestBody NoteInput dto) {
        return cases.note(id, dto.note());
    }

    @PostMapping("/cases/{id}/start-review")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> startReview(@PathVariable String id) {
        return cases.startReview(id);
    }

    @PostMapping("/cases/{id}/escalate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> escalate(@PathVariable String id, @Valid @RequestBody EscalateInput dto) {
        return cases.escalate(id, dto.reason());
    }

    @PostMapping("/cases/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody CloseInput dto) {
        return cases.close(id, dto);
    }

    @PostMapping("/cases/{id}/reopen")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> reopen(@PathVariable String id, @Valid @RequestBody EscalateInput dto) {
        return cases.reopen(id, dto.reason());
    }
}
