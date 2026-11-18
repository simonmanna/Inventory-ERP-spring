package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.lossprevention.application.LpVisionCommands.ClipsInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CommandResultInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EnrolInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.HeartbeatInput;
import com.inventoryerp.lossprevention.application.LpVisionDirectory.AgentRef;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an edge agent calls (LP1, ADR-S077 §7), one method per route. Each checks that the caller is an enrolled agent
 * and then hands over to the service that owns the data.
 */
@Service
public class LpAgentProtocolService {

    private final LpAgentService agents;
    private final LpAgentCommandService commands;
    private final LpEvidenceService evidence;
    private final LpLiveService live;

    public LpAgentProtocolService(LpAgentService agents, LpAgentCommandService commands, LpEvidenceService evidence,
            LpLiveService live) {
        this.agents = agents;
        this.commands = commands;
        this.evidence = evidence;
        this.live = live;
    }

    public Map<String, Object> enrol(EnrolInput dto, String apiBase) {
        return agents.enrol(dto, apiBase);
    }

    public Map<String, Object> config(AuthUser auth) {
        return agents.config(agents.requireAgent(auth));
    }

    public Map<String, Object> heartbeat(AuthUser auth, HeartbeatInput dto) {
        return agents.heartbeat(agents.requireAgent(auth), dto);
    }

    /** Waits up to {@code waitSeconds} (at most 25) for commands. Holds no transaction while it waits. */
    public Map<String, Object> commands(AuthUser auth, Integer waitSeconds) {
        AgentRef agent = agents.requireAgent(auth);
        int wait = waitSeconds == null ? 0 : waitSeconds;
        if (wait < 0 || wait > LpAgentCommandService.MAX_WAIT_SECONDS) {
            throw ApiException.badRequest("waitSeconds must be between 0 and "
                    + LpAgentCommandService.MAX_WAIT_SECONDS);
        }
        return Obj.of("commands", commands.poll(agent.id(), wait));
    }

    /**
     * The agent's result for a command. A clip the box no longer has ({@code code} {@code not_found}) is missing; any
     * other failed upload waits on the box again. A live view the box could not start ends.
     */
    @Transactional
    public Map<String, Object> commandResult(AuthUser auth, String commandId, CommandResultInput dto) {
        AgentRef agent = agents.requireAgent(auth);
        Map<String, Object> cmd = commands.complete(agent.id(), commandId, dto.status(), dto.message(), dto.code());
        boolean first = Boolean.TRUE.equals(cmd.get("firstReport"));
        Map<String, Object> payload = LpJson.map(cmd.get("payload"));
        if (first && "failed".equals(dto.status())) {
            switch ((String) cmd.get("kind")) {
                case "clip_upload" -> {
                    String evidenceId = (String) payload.get("evidenceId");
                    if (evidenceId != null && "not_found".equals(dto.code())) {
                        evidence.markMissing(evidenceId, "agent_reported_not_found");
                    } else if (evidenceId != null) {
                        evidence.uploadLapsed(evidenceId);
                    }
                }
                case "live_start" -> live.agentFailed(agent.id(), (String) payload.get("sessionId"));
                default -> {
                    // snapshot, calibrate, config_changed: the result is kept with the command
                }
            }
        }
        return Obj.of("id", commandId, "state", first ? dto.status() : cmd.get("state"), "firstReport", first);
    }

    /** A still the agent took for a {@code snapshot} command; kept in memory for ten minutes. */
    @Transactional
    public Map<String, Object> snapshot(AuthUser auth, String commandId, byte[] jpeg) {
        AgentRef agent = agents.requireAgent(auth);
        Map<String, Object> cmd = commands.get(agent.id(), commandId);
        if (!"snapshot".equals(cmd.get("kind"))) {
            throw ApiException.notFound("Command not found");
        }
        String cameraId = (String) LpJson.map(cmd.get("payload")).get("cameraId");
        live.putSnapshot(cameraId, jpeg);
        commands.complete(agent.id(), commandId, "done", null, null);
        return Obj.of("id", commandId, "cameraId", cameraId, "received", true);
    }

    public Map<String, Object> frame(AuthUser auth, String sessionId, byte[] jpeg) {
        AgentRef agent = agents.requireAgent(auth);
        return Obj.of("continue", live.frame(agent.id(), sessionId, jpeg));
    }

    public Map<String, Object> registerClips(AuthUser auth, ClipsInput dto) {
        return evidence.register(agents.requireAgent(auth), dto.clips());
    }

    public Map<String, Object> uploadStatus(AuthUser auth, String evidenceId) {
        return evidence.uploadStatus(agents.requireAgent(auth), evidenceId);
    }

    public Map<String, Object> uploadChunk(AuthUser auth, String evidenceId, long offset, long total, String sha256,
            String contentType, byte[] chunk) {
        return evidence.uploadChunk(agents.requireAgent(auth), evidenceId, offset, total, sha256, contentType, chunk);
    }
}
