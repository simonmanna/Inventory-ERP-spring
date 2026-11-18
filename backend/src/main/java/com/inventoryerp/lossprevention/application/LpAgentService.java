package com.inventoryerp.lossprevention.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.kernel.apiclients.ApiClientService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CameraStatus;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateAgentInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EnrolInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.HeartbeatInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateAgentInput;
import com.inventoryerp.lossprevention.application.LpVisionDirectory.AgentRef;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edge agents (LP1, ADR-S077 §7): created by an administrator with a one-time enrolment code, enrolled by the box
 * itself, then known by their heartbeats. An agent's credentials are an API client with the {@code lp:ingest} scope,
 * inactive until the box enrols; revoking the agent revokes them.
 */
@Service
public class LpAgentService {

    static final Duration CODE_TTL = Duration.ofMinutes(15);
    public static final int HEARTBEAT_SECONDS = 60;
    /** Below this free space, or below a tenth of the disk, the agent is degraded. */
    static final long DISK_LOW_BYTES = 5L * 1024 * 1024 * 1024;
    /** More unsent observations than this and the agent is degraded. */
    static final int QUEUE_BACKLOG = 1000;
    /** Unambiguous when read aloud or typed: no I, L, O, U, 0 or 1. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();

    private static final String COLUMNS = "a.id, a.\"branchId\", b.name AS \"branchName\", a.name, a.status, a.health,"
            + " a.\"healthReasons\", a.\"healthSince\", a.hostname, a.\"agentVersion\", a.\"modelVersion\","
            + " a.\"desiredModelVersion\", a.\"desiredAgentVersion\", a.\"configVersion\", a.\"reportedConfigVersion\","
            + " a.\"lastHeartbeatAt\", a.\"observedThrough\", a.\"clockDriftMs\", a.\"diskFreeBytes\","
            + " a.\"diskTotalBytes\", a.\"queueLength\", (a.\"enrolCodeHash\" IS NOT NULL AND a.\"enrolCodeExpiresAt\" >"
            + " CURRENT_TIMESTAMP) AS \"enrolmentPending\", CASE WHEN a.\"enrolCodeHash\" IS NOT NULL THEN"
            + " a.\"enrolCodeExpiresAt\" END AS \"enrolmentCodeExpiresAt\", a.\"enrolledAt\", a.\"revokedAt\","
            + " a.\"revokedBy\", a.\"revokeReason\", a.\"apiClientId\", a.\"createdBy\", a.\"createdAt\", a.\"updatedAt\","
            + " (SELECT count(*) FROM \"LpCamera\" c WHERE c.\"agentId\" = a.id) AS \"cameraCount\"";
    private static final String FROM = " FROM \"LpAgent\" a LEFT JOIN \"Branch\" b ON b.id = a.\"branchId\"";

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final BranchScopeService branches;
    private final ApiClientService apiClients;
    private final LpSettingsService settings;
    private final LpAgentCommandService commands;
    private final LpModelCatalog models;
    private final LpReconService recon;
    private final LpVisionDirectory directory;
    private final LpLiveService live;
    private final AuditService audit;
    private final EventBus events;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public LpAgentService(JdbcClient jdbc, LpScope scope, BranchScopeService branches, ApiClientService apiClients,
            LpSettingsService settings, LpAgentCommandService commands, LpModelCatalog models, LpReconService recon,
            LpVisionDirectory directory, LpLiveService live, AuditService audit, EventBus events) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.branches = branches;
        this.apiClients = apiClients;
        this.settings = settings;
        this.commands = commands;
        this.models = models;
        this.recon = recon;
        this.directory = directory;
        this.live = live;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ helpers

    private void timeline(String agentId, String kind, Map<String, Object> data) {
        jdbc.sql("INSERT INTO \"LpAgentEvent\" (id, \"organizationId\", \"agentId\", kind, \"actorId\", data) VALUES (?,"
                + " ?, ?, ?, ?, ?::jsonb)")
                .params(java.util.Arrays.asList(UUID.randomUUID().toString(), LpScope.org(), agentId, kind,
                        TenantContext.userId(), scope.json(data == null ? Map.of() : data)))
                .update();
    }

    private String newCode() {
        char[] c = new char[12];
        for (int i = 0; i < c.length; i++) {
            c[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        String raw = new String(c);
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
    }

    /** Dashes, spaces and case do not matter when the code is typed. */
    static String codeHash(String code) {
        String normalized = code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return LpEvidenceCipher.sha256(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /** The agent when the caller may see it (its branch is in the caller's scope); otherwise 404. */
    private Map<String, Object> load(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("a", params) + " AND a.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT " + COLUMNS + FROM + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Agent not found");
        }
        return row;
    }

    // ------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String branchId) {
        scope.assertBranch(branchId);
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("a", params));
        if (LpScope.has(branchId)) {
            w.append(" AND a.\"branchId\" = ?");
            params.add(branchId);
        }
        return SqlRows.list(jdbc.sql("SELECT " + COLUMNS + FROM + w + " ORDER BY b.name, a.name, a.id")
                .params(params));
    }

    /** The agent with its cameras, its last 60 heartbeats and its timeline. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = new LinkedHashMap<>(load(id));
        out.put("cameras", SqlRows.list(jdbc.sql("SELECT c.*, (SELECT count(*) FROM \"LpZone\" z WHERE z.\"cameraId\" ="
                + " c.id AND z.\"retiredAt\" IS NULL) AS \"zoneCount\" FROM \"LpCamera\" c WHERE c.\"organizationId\" = ?"
                + " AND c.\"agentId\" = ? ORDER BY c.name, c.id").params(LpScope.org(), id)));
        out.put("heartbeats", SqlRows.list(jdbc.sql("SELECT * FROM \"LpAgentHeartbeat\" WHERE \"organizationId\" = ? AND"
                + " \"agentId\" = ? ORDER BY \"receivedAt\" DESC LIMIT 60").params(LpScope.org(), id)));
        out.put("timeline", SqlRows.list(jdbc.sql("SELECT t.*, coalesce(nullif(trim(coalesce(u.\"firstName\", '') || ' '"
                + " || coalesce(u.\"lastName\", '')), ''), u.email) AS \"actorName\" FROM \"LpAgentEvent\" t LEFT JOIN"
                + " \"User\" u ON u.id = t.\"actorId\" WHERE t.\"organizationId\" = ? AND t.\"agentId\" = ? ORDER BY"
                + " t.\"createdAt\" DESC, t.id LIMIT 100").params(LpScope.org(), id)));
        return out;
    }

    /**
     * A new agent at a branch, with its one-time enrolment code (shown once, valid 15 minutes). Its credentials exist
     * from now on but stay inactive until the box enrols.
     */
    @Transactional
    public Map<String, Object> create(CreateAgentInput dto) {
        branches.assertBranchExists(dto.branchId());
        scope.assertBranch(dto.branchId());
        String name = dto.name().trim();
        Map<String, Object> client = apiClients.create("Edge agent: " + name,
                List.of(Permissions.LOSS_PREVENTION__INGEST), null);
        String clientRowId = (String) client.get("id");
        apiClients.update(clientRowId, null, null, false, null, false);
        String id = UUID.randomUUID().toString();
        String code = newCode();
        Instant expires = clock.instant().plus(CODE_TTL);
        jdbc.sql("INSERT INTO \"LpAgent\" (id, \"organizationId\", \"branchId\", name, \"enrolCodeHash\","
                + " \"enrolCodeExpiresAt\", \"apiClientId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), dto.branchId(), name, codeHash(code),
                        SqlRows.ts(expires), clientRowId, TenantContext.userId()))
                .update();
        timeline(id, "created", Obj.of("branchId", dto.branchId(), "name", name));
        timeline(id, "code_issued", Obj.of("expiresAt", expires.toString()));
        audit.recordInTx(AuditInput.of("LpAgent", id, "create", null, Obj.of("name", name, "branchId",
                dto.branchId())));
        Map<String, Object> out = new LinkedHashMap<>(load(id));
        out.put("enrolmentCode", code);
        out.put("enrolmentCodeExpiresAt", expires);
        return out;
    }

    /** A new one-time code. Enrolling with it gives the box new credentials; the old ones stop working. */
    @Transactional
    public Map<String, Object> issueCode(String id) {
        Map<String, Object> a = load(id);
        if ("revoked".equals(a.get("status"))) {
            throw ApiException.badRequest("A revoked agent cannot be enrolled again; add a new agent");
        }
        String code = newCode();
        Instant expires = clock.instant().plus(CODE_TTL);
        jdbc.sql("UPDATE \"LpAgent\" SET \"enrolCodeHash\" = ?, \"enrolCodeExpiresAt\" = ?, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?").params(codeHash(code), SqlRows.ts(expires), id).update();
        timeline(id, "code_issued", Obj.of("expiresAt", expires.toString()));
        audit.recordInTx(AuditInput.of("LpAgent", id, "issue", null, Obj.of("enrolmentCode", "issued")));
        Map<String, Object> out = new LinkedHashMap<>(load(id));
        out.put("enrolmentCode", code);
        out.put("enrolmentCodeExpiresAt", expires);
        return out;
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateAgentInput dto) {
        Map<String, Object> a = load(id);
        if ("revoked".equals(a.get("status"))) {
            throw ApiException.badRequest("A revoked agent cannot be changed");
        }
        String model = LpScope.has(dto.desiredModelVersion()) ? dto.desiredModelVersion().trim() : null;
        String release = LpScope.has(dto.desiredAgentVersion()) ? dto.desiredAgentVersion().trim() : null;
        if (model != null && models.model(model) == null) {
            throw ApiException.badRequest("Unknown model version '" + model + "'");
        }
        if (release != null && models.release(release) == null) {
            throw ApiException.badRequest("Unknown agent release '" + release + "'");
        }
        boolean targetChanged = !Objects.equals(model, a.get("desiredModelVersion"))
                || !Objects.equals(release, a.get("desiredAgentVersion"));
        int version = jdbc.sql("UPDATE \"LpAgent\" SET name = ?, \"desiredModelVersion\" = ?, \"desiredAgentVersion\" = ?,"
                + " \"configVersion\" = \"configVersion\" + ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ? RETURNING"
                + " \"configVersion\"").params(java.util.Arrays.asList(dto.name().trim(), model, release,
                        targetChanged ? 1 : 0, id))
                .query(Integer.class).single();
        if (targetChanged) {
            commands.configChanged(id, version);
        }
        Map<String, Object> after = Obj.of("name", dto.name().trim(), "desiredModelVersion", model,
                "desiredAgentVersion", release);
        timeline(id, "updated", after);
        audit.recordInTx(AuditInput.of("LpAgent", id, "update", Obj.of("name", a.get("name"), "desiredModelVersion",
                a.get("desiredModelVersion"), "desiredAgentVersion", a.get("desiredAgentVersion")), after));
        return get(id);
    }

    /** For good: the credentials stop working, open work for the box expires and live views end. */
    @Transactional
    public Map<String, Object> revoke(String id, String reason) {
        Map<String, Object> a = load(id);
        if ("revoked".equals(a.get("status"))) {
            throw ApiException.badRequest("The agent is already revoked");
        }
        jdbc.sql("UPDATE \"LpAgent\" SET status = 'revoked', \"revokedAt\" = CURRENT_TIMESTAMP, \"revokedBy\" = ?,"
                + " \"revokeReason\" = ?, \"enrolCodeHash\" = NULL, \"enrolCodeExpiresAt\" = NULL, health = 'offline',"
                + " \"healthReasons\" = '[\"revoked\"]'::jsonb, \"healthSince\" = CURRENT_TIMESTAMP, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(TenantContext.userId(), reason, id)).update();
        if (a.get("apiClientId") != null) {
            apiClients.revoke((String) a.get("apiClientId"));
        }
        commands.expireAll(id);
        live.stopAgent(id);
        timeline(id, "revoked", Obj.of("reason", reason));
        audit.recordInTx(AuditInput.of("LpAgent", id, "delete", Obj.of("status", a.get("status")), Obj.of("status",
                "revoked", "reason", reason)));
        return get(id);
    }

    /** A camera or zone of the agent changed: a new configuration version, and the box is told to read it. */
    @Transactional
    public int configChanged(String agentId) {
        int version = jdbc.sql("UPDATE \"LpAgent\" SET \"configVersion\" = \"configVersion\" + 1, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ? AND \"organizationId\" = ? RETURNING \"configVersion\"")
                .params(agentId, LpScope.org()).query(Integer.class).single();
        commands.configChanged(agentId, version);
        return version;
    }

    // ------------------------------------------------------------------ the box

    /**
     * Exchanges a one-time code for the box's credentials. Public: the code is the authorization. The code works once
     * and for 15 minutes; enrolling an agent again gives it a new secret and the old one stops working.
     *
     * @param apiBase the path of the API on this server, so the box can find the token endpoint
     */
    @Transactional
    public Map<String, Object> enrol(EnrolInput dto, String apiBase) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT id, \"organizationId\", \"branchId\", status,"
                + " \"apiClientId\", \"enrolCodeExpiresAt\" FROM \"LpAgent\" WHERE \"enrolCodeHash\" = ? FOR UPDATE")
                .param(codeHash(dto.code())));
        Instant expires = row == null ? null : (Instant) row.get("enrolCodeExpiresAt");
        if (row == null || "revoked".equals(row.get("status")) || expires == null
                || expires.isBefore(clock.instant()) || row.get("apiClientId") == null) {
            throw ApiException.badRequest("Enrolment code is invalid or has expired");
        }
        String org = (String) row.get("organizationId");
        return TenantContext.run(TenantStore.organization(org), () -> {
            String id = (String) row.get("id");
            String clientRowId = (String) row.get("apiClientId");
            Map<String, Object> client = apiClients.rotate(clientRowId);
            apiClients.update(clientRowId, null, null, true, null, false);
            boolean again = "active".equals(row.get("status"));
            jdbc.sql("UPDATE \"LpAgent\" SET status = 'active', \"enrolCodeHash\" = NULL, \"enrolCodeExpiresAt\" = NULL,"
                    + " \"enrolledAt\" = CURRENT_TIMESTAMP, hostname = ?, \"agentVersion\" = coalesce(?,"
                    + " \"agentVersion\"), \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                    .params(java.util.Arrays.asList(dto.hostname(), dto.agentVersion(), id)).update();
            timeline(id, "enrolled", Obj.of("hostname", dto.hostname(), "agentVersion", dto.agentVersion(),
                    "replacedCredentials", again));
            audit.recordInTx(AuditInput.of("LpAgent", id, "update", Obj.of("status", row.get("status")),
                    Obj.of("status", "active", "enrolled", true)));
            return Obj.of("agentId", id, "organizationId", org, "branchId", row.get("branchId"), "clientId",
                    client.get("clientId"), "clientSecret", client.get("clientSecret"), "tokenUrl",
                    apiBase + "/oauth/token", "apiBase", apiBase, "scope", Permissions.LOSS_PREVENTION__INGEST,
                    "heartbeatSeconds", HEARTBEAT_SECONDS);
        });
    }

    /** The calling agent, or 403: these routes are for an enrolled box, not for a user or another API client. */
    public AgentRef requireAgent(AuthUser auth) {
        AgentRef agent = auth == null || !auth.isClient() ? null : directory.agentOfClient(auth.clientId());
        if (agent == null) {
            throw ApiException.forbidden("Only an enrolled edge agent can call this route");
        }
        return agent;
    }

    private static Instant instant(String v, String field, Instant now) {
        if (v == null) {
            return null;
        }
        Instant at = LpEventValidator.instant(v);
        if (at == null) {
            throw ApiException.badRequest(field + " must be an ISO-8601 instant");
        }
        // A box whose clock runs ahead never moves a watermark past the server's now.
        return at.isAfter(now) ? now : at;
    }

    private static void notNegative(Number v, String field) {
        if (v != null && v.longValue() < 0) {
            throw ApiException.badRequest(field + " must be 0 or more");
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    /**
     * Records a heartbeat: versions, clock, disk, queue, the watermarks and each camera's stream. The agent is online,
     * or degraded with its reasons. A watermark never moves back. Answers with the due commands.
     */
    @Transactional
    public Map<String, Object> heartbeat(AgentRef agent, HeartbeatInput dto) {
        Instant now = clock.instant();
        Instant observed = instant(dto.observedThrough(), "observedThrough", now);
        notNegative(dto.diskFreeBytes(), "diskFreeBytes");
        notNegative(dto.diskTotalBytes(), "diskTotalBytes");
        notNegative(dto.queueLength(), "queueLength");
        List<CameraStatus> reported = dto.cameras() == null ? List.of() : dto.cameras();
        Map<String, CameraStatus> byId = new HashMap<>();
        for (CameraStatus c : reported) {
            if (!LpVisionCatalog.STREAM_STATES.contains(c.streamState())) {
                throw ApiException.badRequest("cameras[].streamState must be one of: "
                        + String.join(", ", LpVisionCatalog.STREAM_STATES));
            }
            if (c.fps() != null && (c.fps().signum() < 0 || c.fps().compareTo(BigDecimal.valueOf(1000)) > 0)) {
                throw ApiException.badRequest("cameras[].fps must be between 0 and 1000");
            }
            instant(c.lastFrameAt(), "cameras[].lastFrameAt", now);
            instant(c.observedThrough(), "cameras[].observedThrough", now);
            byId.put(c.cameraId(), c);
        }
        String org = LpScope.org();
        Map<String, Object> before = SqlRows.one(jdbc.sql("SELECT health, \"observedThrough\", \"configVersion\" FROM"
                + " \"LpAgent\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(agent.id(), org));
        Map<String, Integer> s = settings.effective(agent.branchId());

        List<String> reasons = new ArrayList<>();
        List<String> unknownCameras = new ArrayList<>(byId.keySet());
        for (Map<String, Object> cam : SqlRows.list(jdbc.sql("SELECT id, enabled, analysed, \"observedThrough\" FROM"
                + " \"LpCamera\" WHERE \"organizationId\" = ? AND \"agentId\" = ?").params(org, agent.id()))) {
            String id = (String) cam.get("id");
            unknownCameras.remove(id);
            CameraStatus st = byId.get(id);
            boolean watched = Boolean.TRUE.equals(cam.get("enabled")) && Boolean.TRUE.equals(cam.get("analysed"));
            if (watched && (st == null || !"ok".equals(st.streamState())) && !reasons.contains("stream_lost")) {
                reasons.add("stream_lost");
            }
            if (st != null) {
                Instant mark = later((Instant) cam.get("observedThrough"),
                        instant(st.observedThrough(), "cameras[].observedThrough", now));
                jdbc.sql("UPDATE \"LpCamera\" SET \"streamState\" = ?, fps = ?, \"lastFrameAt\" = ?, \"observedThrough\" ="
                        + " ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                        .params(java.util.Arrays.asList(st.streamState(), st.fps(),
                                SqlRows.ts(instant(st.lastFrameAt(), "cameras[].lastFrameAt", now)),
                                SqlRows.ts(mark), id))
                        .update();
            }
        }
        if (dto.diskFreeBytes() != null && dto.diskTotalBytes() != null && (dto.diskFreeBytes() < DISK_LOW_BYTES
                || dto.diskFreeBytes() * 10 < dto.diskTotalBytes())) {
            reasons.add("disk_low");
        }
        if (dto.clockDriftMs() != null && Math.abs(dto.clockDriftMs()) > s.get(LpSettingsService.MAX_CLOCK_DRIFT)) {
            reasons.add("clock_drift");
        }
        if (dto.queueLength() != null && dto.queueLength() > QUEUE_BACKLOG) {
            reasons.add("queue_backlog");
        }
        String health = reasons.isEmpty() ? "online" : "degraded";
        String previous = (String) before.get("health");
        Instant oldMark = (Instant) before.get("observedThrough");
        Instant mark = later(oldMark, observed);
        jdbc.sql("UPDATE \"LpAgent\" SET \"lastHeartbeatAt\" = ?, \"agentVersion\" = coalesce(?, \"agentVersion\"),"
                + " \"modelVersion\" = ?, \"reportedConfigVersion\" = ?, \"clockDriftMs\" = ?, \"diskFreeBytes\" = ?,"
                + " \"diskTotalBytes\" = ?, \"queueLength\" = ?, \"observedThrough\" = ?, health = ?, \"healthReasons\" ="
                + " ?::jsonb, \"healthSince\" = CASE WHEN health = ? AND \"healthSince\" IS NOT NULL THEN"
                + " \"healthSince\" ELSE ? END, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(SqlRows.ts(now), dto.agentVersion(), dto.modelVersion(),
                        dto.configVersion(), dto.clockDriftMs() == null ? null : dto.clockDriftMs().intValue(),
                        dto.diskFreeBytes(), dto.diskTotalBytes(), dto.queueLength(), SqlRows.ts(mark), health,
                        scope.json(reasons), health, SqlRows.ts(now), agent.id()))
                .update();
        jdbc.sql("INSERT INTO \"LpAgentHeartbeat\" (id, \"organizationId\", \"agentId\", \"receivedAt\", \"agentVersion\","
                + " \"modelVersion\", \"configVersion\", \"clockDriftMs\", \"diskFreeBytes\", \"diskTotalBytes\","
                + " \"queueLength\", \"observedThrough\", cameras, health, \"healthReasons\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)")
                .params(java.util.Arrays.asList(UUID.randomUUID().toString(), org, agent.id(), SqlRows.ts(now),
                        dto.agentVersion(), dto.modelVersion(), dto.configVersion(),
                        dto.clockDriftMs() == null ? null : dto.clockDriftMs().intValue(), dto.diskFreeBytes(),
                        dto.diskTotalBytes(), dto.queueLength(), SqlRows.ts(observed), scope.json(reported), health,
                        scope.json(reasons)))
                .update();
        if (!health.equals(previous)) {
            healthChanged(agent.id(), agent.branchId(), agent.name(), previous, health, reasons);
        }
        if (mark != null && (oldMark == null || mark.isAfter(oldMark)) && recon.observationAdvanced(agent.branchId(),
                mark) > 0) {
            jdbc.sql("UPDATE \"LpAgent\" SET \"coverageMarkedAt\" = CURRENT_TIMESTAMP WHERE id = ?").param(agent.id())
                    .update();
        }
        return Obj.of("serverTime", now, "health", health, "healthReasons", reasons, "configVersion",
                before.get("configVersion"), "unknownCameras", unknownCameras, "commands", commands.take(agent.id()));
    }

    private void healthChanged(String agentId, String branchId, String name, String from, String to,
            List<String> reasons) {
        timeline(agentId, to, Obj.of("from", from, "reasons", reasons));
        events.publishOnCommit(DomainEvents.LP_AGENT_HEALTH_CHANGED, Obj.of("organizationId", LpScope.org(),
                "agentId", agentId, "branchId", branchId, "name", name, "from", from, "to", to, "reasons", reasons));
    }

    /** What the box runs: its cameras (by alias), their zones with the effective parameters, model and release. */
    @Transactional(readOnly = true)
    public Map<String, Object> config(AgentRef agent) {
        String org = LpScope.org();
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT \"configVersion\", \"desiredModelVersion\","
                + " \"desiredAgentVersion\" FROM \"LpAgent\" WHERE id = ? AND \"organizationId\" = ?")
                .params(agent.id(), org));
        Map<String, Integer> s = settings.effective(agent.branchId());
        List<Map<String, Object>> cameras = new ArrayList<>();
        Map<String, List<Map<String, Object>>> zonesByCamera = new HashMap<>();
        for (Map<String, Object> z : SqlRows.list(jdbc.sql("SELECT z.id, z.\"cameraId\", z.name, z.\"zoneType\","
                + " z.polygon, z.\"inSide\", z.\"linkedType\", z.\"linkedId\", z.params, z.enabled FROM \"LpZone\" z JOIN"
                + " \"LpCamera\" c ON c.id = z.\"cameraId\" WHERE z.\"organizationId\" = ? AND c.\"agentId\" = ? AND"
                + " z.\"retiredAt\" IS NULL ORDER BY z.name, z.id").params(org, agent.id()))) {
            zonesByCamera.computeIfAbsent((String) z.get("cameraId"), k -> new ArrayList<>()).add(Obj.of("id",
                    z.get("id"), "name", z.get("name"), "zoneType", z.get("zoneType"), "polygon", z.get("polygon"),
                    "inSide", z.get("inSide"), "linkedType", z.get("linkedType"), "linkedId", z.get("linkedId"),
                    "enabled", z.get("enabled"), "params", LpVisionCatalog.effectiveParams((String) z.get("zoneType"),
                            LpJson.map(z.get("params")))));
        }
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT id, name, \"streamAlias\", enabled, analysed FROM"
                + " \"LpCamera\" WHERE \"organizationId\" = ? AND \"agentId\" = ? ORDER BY name, id")
                .params(org, agent.id()))) {
            cameras.add(Obj.of("id", c.get("id"), "name", c.get("name"), "streamAlias", c.get("streamAlias"),
                    "enabled", c.get("enabled"), "analysed", c.get("analysed"), "zones",
                    zonesByCamera.getOrDefault((String) c.get("id"), List.of())));
        }
        return Obj.of("agentId", agent.id(), "branchId", agent.branchId(), "configVersion", a.get("configVersion"),
                "heartbeatSeconds", HEARTBEAT_SECONDS, "commandWaitSeconds", LpAgentCommandService.MAX_WAIT_SECONDS,
                "clock", Obj.of("maxDriftMs", s.get(LpSettingsService.MAX_CLOCK_DRIFT)),
                "evidence", Obj.of("bufferDays", s.get(LpSettingsService.EVIDENCE_BUFFER_DAYS), "maxClipSeconds",
                        LpVisionCatalog.MAX_CLIP_SECONDS, "maxClipBytes", LpVisionCatalog.MAX_CLIP_BYTES,
                        "chunkBytes", LpVisionCatalog.MAX_CHUNK_BYTES),
                "model", models.modelFor((String) a.get("desiredModelVersion")),
                "release", models.releaseFor((String) a.get("desiredAgentVersion")),
                "cameras", cameras, "measurementDevices", measurementDevices(agent.id()));
    }

    /** The pour meters this agent's adapters read (LP3); see {@link LpMeasurementService}. */
    private List<Map<String, Object>> measurementDevices(String agentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : SqlRows.list(jdbc.sql("SELECT id, kind, vendor, \"externalId\", \"productId\","
                + " station, \"standardPourMl\", enabled FROM \"LpMeasurementDevice\" WHERE \"organizationId\" = ? AND"
                + " \"agentId\" = ? AND \"retiredAt\" IS NULL ORDER BY \"externalId\", id").params(LpScope.org(), agentId))) {
            out.add(Obj.of("id", d.get("id"), "kind", d.get("kind"), "vendor", d.get("vendor"), "externalId",
                    d.get("externalId"), "productId", d.get("productId"), "station", d.get("station"),
                    "standardPourMl", d.get("standardPourMl"), "enabled", d.get("enabled")));
        }
        return out;
    }

    // ------------------------------------------------------------------ schedule

    /** Agents silent for longer than {@code agentSilentMinutes} go offline. Returns how many did. */
    @Transactional
    public int markSilent() {
        Instant now = clock.instant();
        Map<String, Map<String, Integer>> byBranch = new HashMap<>();
        int changed = 0;
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT id, \"branchId\", name, health, \"lastHeartbeatAt\","
                + " \"enrolledAt\" FROM \"LpAgent\" WHERE \"organizationId\" = ? AND status = 'active' AND health <>"
                + " 'offline' FOR UPDATE").param(LpScope.org()))) {
            Instant last = later((Instant) a.get("lastHeartbeatAt"), (Instant) a.get("enrolledAt"));
            String branch = (String) a.get("branchId");
            int minutes = byBranch.computeIfAbsent(branch, settings::effective).get(LpSettingsService.AGENT_SILENT);
            if (last == null || !last.plus(Duration.ofMinutes(minutes)).isBefore(now)) {
                continue;
            }
            List<String> reasons = List.of("no_heartbeat");
            jdbc.sql("UPDATE \"LpAgent\" SET health = 'offline', \"healthReasons\" = ?::jsonb, \"healthSince\" = ?,"
                    + " \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                    .params(scope.json(reasons), SqlRows.ts(now), a.get("id")).update();
            jdbc.sql("UPDATE \"LpCamera\" SET \"streamState\" = NULL, fps = NULL WHERE \"agentId\" = ?")
                    .param(a.get("id")).update();
            healthChanged((String) a.get("id"), branch, (String) a.get("name"), (String) a.get("health"), "offline",
                    reasons);
            changed++;
        }
        return changed;
    }

    /** Heartbeats older than {@code heartbeatRetentionDays} are removed. */
    @Transactional
    public int pruneHeartbeats() {
        int days = settings.effective(null).get(LpSettingsService.HEARTBEAT_RETENTION_DAYS);
        return jdbc.sql("DELETE FROM \"LpAgentHeartbeat\" WHERE \"organizationId\" = ? AND \"receivedAt\" < ?")
                .params(LpScope.org(), SqlRows.ts(clock.instant().minus(Duration.ofDays(Math.max(1, days)))))
                .update();
    }
}
