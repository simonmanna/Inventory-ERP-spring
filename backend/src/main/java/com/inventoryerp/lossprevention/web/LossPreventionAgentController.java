package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpAgentService;
import com.inventoryerp.lossprevention.application.LpModelCatalog;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateAgentInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.RevokeAgentInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateAgentInput;
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

/**
 * {@code /loss-prevention/agents} for administrators (LP1, ADR-S077 §7): edge agents, their enrolment codes and the
 * models and releases on offer. Creating an agent or a code needs {@code lp:ingest} as well, because the agent's
 * credentials carry that scope.
 */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_VISION)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionAgentController {

    private final LpAgentService agents;
    private final LpModelCatalog models;

    public LossPreventionAgentController(LpAgentService agents, LpModelCatalog models) {
        this.agents = agents;
        this.models = models;
    }

    @GetMapping("/agents")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public List<Map<String, Object>> list(@RequestParam(required = false) String branchId) {
        return agents.list(branchId);
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.LOSS_PREVENTION__CONFIGURE, Permissions.LOSS_PREVENTION__INGEST})
    public Map<String, Object> create(@Valid @RequestBody CreateAgentInput dto) {
        return agents.create(dto);
    }

    @GetMapping("/agents/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> get(@PathVariable String id) {
        return agents.get(id);
    }

    @GetMapping("/agents/{id}/commands")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public List<Map<String, Object>> commands(@PathVariable String id, @RequestParam(required = false) String state,
            @RequestParam(required = false) String kind, @RequestParam(required = false) Integer limit) {
        return agents.commands(id, state, kind, limit);
    }

    @GetMapping("/agents/{id}/commands/{commandId}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> command(@PathVariable String id, @PathVariable String commandId) {
        return agents.command(id, commandId);
    }

    @PutMapping("/agents/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateAgentInput dto) {
        return agents.update(id, dto);
    }

    @PostMapping("/agents/{id}/enrolment-code")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.LOSS_PREVENTION__CONFIGURE, Permissions.LOSS_PREVENTION__INGEST})
    public Map<String, Object> enrolmentCode(@PathVariable String id) {
        return agents.issueCode(id);
    }

    @PostMapping("/agents/{id}/revoke")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> revoke(@PathVariable String id,
            @Valid @RequestBody(required = false) RevokeAgentInput dto) {
        return agents.revoke(id, dto == null ? null : dto.reason());
    }

    @GetMapping("/models")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> models() {
        return models.view();
    }
}
