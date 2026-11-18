package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.lossprevention.application.LpAgentProtocolService;
import com.inventoryerp.lossprevention.application.LpVisionCommands.ClipsInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CommandResultInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EnrolInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.HeartbeatInput;
import jakarta.servlet.http.HttpServletRequest;
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

/**
 * The edge agent protocol (LP1, ADR-S077 §7). Enrolment is public: the one-time code is the authorization. Every
 * other route needs an enrolled agent's token ({@code lp:ingest}); a user or another API client gets 403. These
 * routes answer 200, except enrolment (201). Image and chunk bodies are read as raw bytes whatever their content type;
 * the services check what they are.
 */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_VISION)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionAgentApiController {

    private final LpAgentProtocolService protocol;

    public LossPreventionAgentApiController(LpAgentProtocolService protocol) {
        this.protocol = protocol;
    }

    @PostMapping("/agents/enrol")
    @ResponseStatus(HttpStatus.CREATED)
    @Public
    @Throttle(limit = 10, ttlMs = 60_000)
    public Map<String, Object> enrol(@Valid @RequestBody EnrolInput dto, HttpServletRequest request) {
        return protocol.enrol(dto, LpWebPaths.apiBase(request));
    }

    @GetMapping("/agents/config")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> config(AuthUser auth) {
        return protocol.config(auth);
    }

    @PostMapping("/agents/heartbeat")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> heartbeat(@Valid @RequestBody HeartbeatInput dto, AuthUser auth) {
        return protocol.heartbeat(auth, dto);
    }

    @GetMapping("/agents/commands")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> commands(@RequestParam(required = false) Integer waitSeconds, AuthUser auth) {
        return protocol.commands(auth, waitSeconds);
    }

    @PostMapping("/agents/commands/{id}/result")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> commandResult(@PathVariable String id, @Valid @RequestBody CommandResultInput dto,
            AuthUser auth) {
        return protocol.commandResult(auth, id, dto);
    }

    @PostMapping("/agents/snapshots/{commandId}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> snapshot(@PathVariable String commandId, @RequestBody byte[] jpeg, AuthUser auth) {
        return protocol.snapshot(auth, commandId, jpeg);
    }

    @PostMapping("/agents/live/{sessionId}/frames")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> frame(@PathVariable String sessionId, @RequestBody byte[] jpeg, AuthUser auth) {
        return protocol.frame(auth, sessionId, jpeg);
    }

    @PostMapping("/agents/clips")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> clips(@Valid @RequestBody ClipsInput dto, AuthUser auth) {
        return protocol.registerClips(auth, dto);
    }

    @GetMapping("/evidence/{id}/upload")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> uploadStatus(@PathVariable String id, AuthUser auth) {
        return protocol.uploadStatus(auth, id);
    }

    @PostMapping("/evidence/{id}/upload")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INGEST)
    public Map<String, Object> upload(@PathVariable String id, @RequestParam long offset, @RequestParam long total,
            @RequestParam String sha256, @RequestParam(required = false) String contentType,
            @RequestBody byte[] chunk, AuthUser auth) {
        return protocol.uploadChunk(auth, id, offset, total, sha256, contentType, chunk);
    }
}
