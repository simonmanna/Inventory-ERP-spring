package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpCommands.EventQuery;
import com.inventoryerp.lossprevention.application.LpCommands.IngestRequest;
import com.inventoryerp.lossprevention.application.LpLedgerService;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /loss-prevention/events} (ADR-S077): the canonical event ledger, written by senders and read by investigators. */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionEventController {

    private final LpLedgerService ledger;

    public LossPreventionEventController(LpLedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest dto, AuthUser auth) {
        return ledger.ingest(dto.events(), true, auth == null ? null : auth.clientId());
    }

    @GetMapping("/events")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public PageResult<Map<String, Object>> events(@RequestParam(required = false) String type,
            @RequestParam(required = false) String source, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String deviceId, @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String transactionId, @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String ticketId, @RequestParam(required = false) String lineId,
            @RequestParam(required = false) String sessionId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ledger.list(new EventQuery(type, source, branchId, deviceId, actorId, transactionId, orderId, ticketId,
                lineId, sessionId, from, to, page, pageSize));
    }

    @GetMapping("/events/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> event(@PathVariable String id) {
        return ledger.get(id);
    }
}
