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
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.lossprevention.application.LpCommands.ReplayInput;
import com.inventoryerp.lossprevention.application.LpCommands.RunQuery;
import com.inventoryerp.lossprevention.application.LpProjectionService;
import com.inventoryerp.lossprevention.application.LpReconService;

/** {@code /loss-prevention/runs} (ADR-S077 §5): reconciliation history, replay, comparison and verification. */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionRunController {

    private final LpReconService recon;
    private final LpProjectionService projection;

    public LossPreventionRunController(LpReconService recon, LpProjectionService projection) {
        this.recon = recon;
        this.projection = projection;
    }

    @GetMapping("/runs")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public PageResult<Map<String, Object>> runs(@RequestParam(required = false) String engine,
            @RequestParam(required = false) String branchId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String windowId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return recon.list(new RunQuery(engine, branchId, from, to, windowId, page, pageSize));
    }

    @GetMapping("/runs/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> run(@PathVariable String id) {
        return recon.get(id);
    }

    @GetMapping("/runs/{id}/compare/{otherId}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> compare(@PathVariable String id, @PathVariable String otherId) {
        return recon.compare(id, otherId);
    }

    @GetMapping("/runs/{id}/verify")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> verify(@PathVariable String id) {
        return recon.verify(id);
    }

    @PostMapping("/runs/replay")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> replay(@Valid @RequestBody ReplayInput dto) {
        return recon.replay(dto);
    }

    /** Sweep this ERP's records into the ledger and run every window that is due, now instead of on the schedule. */
    @PostMapping("/projection/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> sync() {
        Map<String, Object> swept = projection.sweepAll();
        return Obj.of("projection", swept, "windowsReconciled", recon.runDue());
    }
}
