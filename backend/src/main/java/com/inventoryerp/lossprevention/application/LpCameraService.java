package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateCameraInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateZoneInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateCameraInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateZoneInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Cameras and zones (LP1, ADR-S077 §7). A camera is a name and the alias the edge box maps to a stream; the address and
 * password of the camera never reach the backend. A zone is a polygon on the picture with one purpose, linked to what
 * it watches. Every change raises the agent's configuration version and tells the box.
 */
@Service
public class LpCameraService {

    private static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final String CAMERA_COLUMNS = "c.*, a.name AS \"agentName\", a.status AS \"agentStatus\", a.health AS"
            + " \"agentHealth\", b.name AS \"branchName\", (SELECT count(*) FROM \"LpZone\" z WHERE z.\"cameraId\" = c.id"
            + " AND z.\"retiredAt\" IS NULL) AS \"zoneCount\"";
    private static final String CAMERA_FROM = " FROM \"LpCamera\" c JOIN \"LpAgent\" a ON a.id = c.\"agentId\" LEFT JOIN"
            + " \"Branch\" b ON b.id = c.\"branchId\"";
    private static final String ZONE_COLUMNS = "z.*, c.name AS \"cameraName\", c.\"agentId\"";
    private static final String ZONE_FROM = " FROM \"LpZone\" z JOIN \"LpCamera\" c ON c.id = z.\"cameraId\"";

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpAgentService agents;
    private final LpAgentCommandService commands;
    private final LpLiveService live;
    private final LpAccessLog access;
    private final AuditService audit;

    public LpCameraService(JdbcClient jdbc, LpScope scope, LpAgentService agents, LpAgentCommandService commands,
            LpLiveService live, LpAccessLog access, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.agents = agents;
        this.commands = commands;
        this.live = live;
        this.access = access;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ cameras

    /** The reason a stream alias is refused, or {@code null}. An address or a password is never accepted here. */
    static String aliasReason(String alias) {
        if (alias.contains("://") || alias.contains("@") || alias.contains(":") || alias.contains("/")) {
            return "streamAlias is a name, not an address: enter the camera address and password on the edge box";
        }
        return ALIAS.matcher(alias).matches() ? null : "streamAlias must be 1 to 64 characters: lowercase letters,"
                + " digits, '-' or '_', starting with a letter or digit";
    }

    private Map<String, Object> camera(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("c", params) + " AND c.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT " + CAMERA_COLUMNS + CAMERA_FROM + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Camera not found");
        }
        return row;
    }

    private void assertAlias(String agentId, String alias, String exceptCameraId) {
        String reason = aliasReason(alias);
        if (reason != null) {
            throw ApiException.badRequest(reason);
        }
        if (jdbc.sql("SELECT count(*) FROM \"LpCamera\" WHERE \"agentId\" = ? AND \"streamAlias\" = ? AND id IS DISTINCT"
                + " FROM ?").params(java.util.Arrays.asList(agentId, alias, exceptCameraId)).query(Long.class)
                .single() > 0) {
            throw ApiException.conflict("Another camera of this agent already uses this streamAlias");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String branchId, String agentId) {
        scope.assertBranch(branchId);
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("c", params));
        if (LpScope.has(branchId)) {
            w.append(" AND c.\"branchId\" = ?");
            params.add(branchId);
        }
        if (LpScope.has(agentId)) {
            w.append(" AND c.\"agentId\" = ?");
            params.add(agentId);
        }
        return SqlRows.list(jdbc.sql("SELECT " + CAMERA_COLUMNS + CAMERA_FROM + w + " ORDER BY b.name, c.name, c.id")
                .params(params));
    }

    /** The camera with its zones, the ones in use first. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = new LinkedHashMap<>(camera(id));
        out.put("zones", zones(SqlRows.list(jdbc.sql("SELECT " + ZONE_COLUMNS + ZONE_FROM + " WHERE z.\"cameraId\" = ?"
                + " ORDER BY z.\"retiredAt\" NULLS FIRST, z.name, z.id").param(id))));
        return out;
    }

    @Transactional
    public Map<String, Object> create(CreateCameraInput dto) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("", params) + " AND id = ?";
        params.add(dto.agentId());
        Map<String, Object> agent = SqlRows.one(jdbc.sql("SELECT id, \"branchId\", status FROM \"LpAgent\"" + w)
                .params(params));
        if (agent == null) {
            throw ApiException.notFound("Agent not found");
        }
        if ("revoked".equals(agent.get("status"))) {
            throw ApiException.badRequest("A revoked agent cannot have cameras");
        }
        assertAlias(dto.agentId(), dto.streamAlias(), null);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpCamera\" (id, \"organizationId\", \"branchId\", \"agentId\", name, \"streamAlias\","
                + " enabled, analysed, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), agent.get("branchId"), dto.agentId(),
                        dto.name().trim(), dto.streamAlias(), dto.enabled() == null || dto.enabled(),
                        dto.analysed() == null || dto.analysed(), dto.notes()))
                .update();
        agents.configChanged(dto.agentId());
        audit.recordInTx(AuditInput.of("LpCamera", id, "create", null, Obj.of("name", dto.name().trim(),
                "streamAlias", dto.streamAlias(), "agentId", dto.agentId())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateCameraInput dto) {
        Map<String, Object> before = camera(id);
        if ("revoked".equals(before.get("agentStatus"))) {
            throw ApiException.badRequest("A camera of a revoked agent cannot be changed");
        }
        assertAlias((String) before.get("agentId"), dto.streamAlias(), id);
        boolean enabled = dto.enabled() == null ? Boolean.TRUE.equals(before.get("enabled")) : dto.enabled();
        boolean analysed = dto.analysed() == null ? Boolean.TRUE.equals(before.get("analysed")) : dto.analysed();
        jdbc.sql("UPDATE \"LpCamera\" SET name = ?, \"streamAlias\" = ?, enabled = ?, analysed = ?, notes = ?,"
                + " \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(dto.name().trim(), dto.streamAlias(), enabled, analysed, dto.notes(),
                        id))
                .update();
        agents.configChanged((String) before.get("agentId"));
        audit.recordInTx(AuditInput.of("LpCamera", id, "update", Obj.of("name", before.get("name"), "streamAlias",
                before.get("streamAlias"), "enabled", before.get("enabled"), "analysed", before.get("analysed")),
                Obj.of("name", dto.name().trim(), "streamAlias", dto.streamAlias(), "enabled", enabled, "analysed",
                        analysed)));
        return get(id);
    }

    /** Asks the agent for a still of the camera; it arrives within seconds at {@link #snapshot}. */
    @Transactional
    public Map<String, Object> requestSnapshot(String id) {
        Map<String, Object> cam = camera(id);
        LpLiveService.assertReachable(cam);
        String commandId = commands.queue((String) cam.get("agentId"), "snapshot", Obj.of("cameraId", id),
                Duration.ofMinutes(2));
        access.record(null, id, null, LpAccessLog.SNAPSHOT, Obj.of("commandId", commandId));
        return Obj.of("commandId", commandId, "cameraId", id);
    }

    @Transactional(readOnly = true)
    public LpLiveService.Snapshot snapshot(String id) {
        camera(id);
        return live.snapshot(id);
    }

    // ------------------------------------------------------------------ zones

    private Map<String, Object> zone(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("z", params) + " AND z.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT " + ZONE_COLUMNS + ", a.status AS \"agentStatus\""
                + ZONE_FROM + " JOIN \"LpAgent\" a ON a.id = c.\"agentId\"" + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Zone not found");
        }
        return row;
    }

    /** Each zone with the parameters the agent will use. */
    private static List<Map<String, Object>> zones(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> z : rows) {
            Map<String, Object> m = new LinkedHashMap<>(z);
            m.put("effectiveParams", LpVisionCatalog.effectiveParams((String) z.get("zoneType"),
                    LpJson.map(z.get("params"))));
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listZones(String cameraId, String branchId, boolean includeRetired) {
        scope.assertBranch(branchId);
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("z", params));
        if (LpScope.has(cameraId)) {
            w.append(" AND z.\"cameraId\" = ?");
            params.add(cameraId);
        }
        if (LpScope.has(branchId)) {
            w.append(" AND z.\"branchId\" = ?");
            params.add(branchId);
        }
        if (!includeRetired) {
            w.append(" AND z.\"retiredAt\" IS NULL");
        }
        return zones(SqlRows.list(jdbc.sql("SELECT " + ZONE_COLUMNS + ZONE_FROM + w + " ORDER BY c.name, z.name, z.id")
                .params(params)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getZone(String id) {
        return zones(List.of(zone(id))).getFirst();
    }

    /** Checked shape of a zone: polygon, counting-line side, link and parameters. */
    private record Shape(String polygon, String inSide, String linkedType, String linkedId, String params) {}

    private Shape shape(String zoneType, JsonNode polygon, String inSide, String linkedType, String linkedId,
            JsonNode params, String branchId) {
        if (polygon == null || !polygon.isArray() || polygon.size() < 2 || polygon.size() > LpVisionCatalog.MAX_POINTS) {
            throw ApiException.badRequest("polygon must hold 2 to 32 points");
        }
        for (JsonNode p : polygon) {
            if (!p.isArray() || p.size() != 2 || !unit(p.get(0)) || !unit(p.get(1))) {
                throw ApiException.badRequest("Each point of polygon must be [x, y] with x and y from 0 to 1");
            }
        }
        boolean line = polygon.size() == 2;
        if (line && !LpVisionCatalog.LINE_ALLOWED.contains(zoneType)) {
            throw ApiException.badRequest("A " + zoneType + " zone must be a polygon of 3 or more points");
        }
        if (!line && LpVisionCatalog.LINE_ONLY.contains(zoneType)) {
            throw ApiException.badRequest("An entrance zone must be a counting line of 2 points");
        }
        if (line && polygon.get(0).get(0).decimalValue().compareTo(polygon.get(1).get(0).decimalValue()) == 0
                && polygon.get(0).get(1).decimalValue().compareTo(polygon.get(1).get(1).decimalValue()) == 0) {
            throw ApiException.badRequest("The two points of a counting line must differ");
        }
        String side = LpScope.has(inSide) ? inSide : null;
        if (line && (side == null || !LpVisionCatalog.IN_SIDES.contains(side))) {
            throw ApiException.badRequest("inSide is required for a counting line: left or right");
        }
        if (!line && side != null) {
            throw ApiException.badRequest("inSide applies to a counting line only");
        }
        String type = LpScope.has(linkedType) ? linkedType : null;
        String id = LpScope.has(linkedId) ? linkedId.trim() : null;
        List<String> allowed = LpVisionCatalog.LINKS.getOrDefault(zoneType, List.of());
        if (type == null) {
            if (LpVisionCatalog.LINK_REQUIRED.contains(zoneType)) {
                throw ApiException.badRequest("A " + zoneType + " zone must be linked to a till");
            }
            if (id != null) {
                throw ApiException.badRequest("linkedId needs a linkedType");
            }
        } else {
            if (!allowed.contains(type)) {
                throw ApiException.badRequest(allowed.isEmpty() ? "An entrance zone is not linked: it counts for the"
                        + " branch" : "A " + zoneType + " zone links to: " + String.join(", ", allowed));
            }
            if (id == null || id.length() > LpVisionCatalog.MAX_LINK_LENGTH) {
                throw ApiException.badRequest("linkedId is required with linkedType and holds at most 200 characters");
            }
            assertLink(type, id, branchId);
        }
        return new Shape(polygon.toString(), side, type, id, scope.json(params(zoneType, params)));
    }

    private static boolean unit(JsonNode v) {
        return v != null && v.isNumber() && v.decimalValue().signum() >= 0 && v.decimalValue().compareTo(BigDecimal.ONE)
                <= 0;
    }

    /**
     * A till or stock location must exist in the organization and, when it has a branch, at the camera's branch; a
     * table must exist in the organization.
     */
    private void assertLink(String type, String id, String branchId) {
        if ("till".equals(type) && jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE id = ? AND \"organizationId\" ="
                + " ? AND \"deletedAt\" IS NULL AND (\"branchId\" IS NULL OR \"branchId\" = ?)")
                .params(id, LpScope.org(), branchId).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Till not found at the camera's branch");
        }
        if ("location".equals(type) && jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND (\"branchId\" IS NULL OR \"branchId\" = ?)")
                .params(id, LpScope.org(), branchId).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Location not found at the camera's branch");
        }
        // LP6b: a POS table. Tables belong to the organization, not to a branch.
        if ("table".equals(type) && jdbc.sql("SELECT count(*) FROM \"PosTable\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, LpScope.org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Table not found");
        }
    }

    /**
     * The zone's own parameters, checked against the type's defaults. A parameter the backend does not know is passed
     * on to the agent unchanged.
     */
    private static Map<String, Object> params(String zoneType, JsonNode params) {
        if (params == null || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject() || params.toString().getBytes(StandardCharsets.UTF_8).length
                > LpVisionCatalog.MAX_PARAMS_BYTES) {
            throw ApiException.badRequest("params must be a JSON object of at most 4096 bytes");
        }
        Map<String, Object> defaults = LpVisionCatalog.DEFAULT_PARAMS.getOrDefault(zoneType, Map.of());
        Map<String, Object> own = LpJson.map(params);
        for (Map.Entry<String, Object> e : own.entrySet()) {
            String key = e.getKey();
            Object def = defaults.get(key);
            Object v = e.getValue();
            if (def instanceof Number) {
                if (!(v instanceof Number) || LpNumbers.decimal(v).signum() < 0) {
                    throw ApiException.badRequest("params." + key + " must be a number of 0 or more");
                }
                if (LpVisionCatalog.UNIT_PARAMS.contains(key) && LpNumbers.decimal(v).compareTo(BigDecimal.ONE) > 0) {
                    throw ApiException.badRequest("params." + key + " must be between 0 and 1");
                }
            } else if (def instanceof String) {
                List<String> values = LpVisionCatalog.TEXT_PARAMS.getOrDefault(key, List.of(String.valueOf(def)));
                if (!(v instanceof String s) || !values.contains(s)) {
                    throw ApiException.badRequest("params." + key + " must be one of: " + String.join(", ", values));
                }
            } else if (def instanceof List<?>) {
                if (!(v instanceof List<?> list) || list.size() > 20 || list.stream().anyMatch(x -> !(x instanceof String s)
                        || s.isBlank() || s.length() > 40)) {
                    throw ApiException.badRequest("params." + key + " must be a list of at most 20 names");
                }
            }
        }
        return own;
    }

    @Transactional
    public Map<String, Object> createZone(CreateZoneInput dto) {
        Map<String, Object> cam = camera(dto.cameraId());
        if ("revoked".equals(cam.get("agentStatus"))) {
            throw ApiException.badRequest("A camera of a revoked agent cannot have zones");
        }
        if (!LpVisionCatalog.ZONE_TYPES.contains(dto.zoneType())) {
            throw ApiException.badRequest("zoneType must be one of: " + String.join(", ", LpVisionCatalog.ZONE_TYPES));
        }
        String branchId = (String) cam.get("branchId");
        Shape shape = shape(dto.zoneType(), dto.polygon(), dto.inSide(), dto.linkedType(), dto.linkedId(),
                dto.params(), branchId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpZone\" (id, \"organizationId\", \"branchId\", \"cameraId\", name, \"zoneType\", polygon,"
                + " \"inSide\", \"linkedType\", \"linkedId\", params, enabled) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?,"
                + " ?::jsonb, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), branchId, dto.cameraId(), dto.name().trim(),
                        dto.zoneType(), shape.polygon(), shape.inSide(), shape.linkedType(), shape.linkedId(),
                        shape.params(), dto.enabled() == null || dto.enabled()))
                .update();
        agents.configChanged((String) cam.get("agentId"));
        audit.recordInTx(AuditInput.of("LpZone", id, "create", null, Obj.of("name", dto.name().trim(), "zoneType",
                dto.zoneType(), "cameraId", dto.cameraId(), "linkedType", shape.linkedType(), "linkedId",
                shape.linkedId())));
        return getZone(id);
    }

    @Transactional
    public Map<String, Object> updateZone(String id, UpdateZoneInput dto) {
        Map<String, Object> before = zone(id);
        if (before.get("retiredAt") != null) {
            throw ApiException.badRequest("A retired zone cannot be changed");
        }
        if ("revoked".equals(before.get("agentStatus"))) {
            throw ApiException.badRequest("A camera of a revoked agent cannot have zones");
        }
        String zoneType = (String) before.get("zoneType");
        Shape shape = shape(zoneType, dto.polygon(), dto.inSide(), dto.linkedType(), dto.linkedId(), dto.params(),
                (String) before.get("branchId"));
        boolean enabled = dto.enabled() == null ? Boolean.TRUE.equals(before.get("enabled")) : dto.enabled();
        jdbc.sql("UPDATE \"LpZone\" SET name = ?, polygon = ?::jsonb, \"inSide\" = ?, \"linkedType\" = ?, \"linkedId\" ="
                + " ?, params = ?::jsonb, enabled = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(dto.name().trim(), shape.polygon(), shape.inSide(),
                        shape.linkedType(), shape.linkedId(), shape.params(), enabled, id))
                .update();
        agents.configChanged((String) before.get("agentId"));
        audit.recordInTx(AuditInput.of("LpZone", id, "update", Obj.of("name", before.get("name"), "linkedType",
                before.get("linkedType"), "linkedId", before.get("linkedId"), "enabled", before.get("enabled")),
                Obj.of("name", dto.name().trim(), "linkedType", shape.linkedType(), "linkedId", shape.linkedId(),
                        "enabled", enabled)));
        return getZone(id);
    }

    /** Retires the zone: the agent stops watching it; its past observations keep pointing at it. */
    @Transactional
    public Map<String, Object> retireZone(String id) {
        Map<String, Object> before = zone(id);
        if (before.get("retiredAt") != null) {
            throw ApiException.badRequest("The zone is already retired");
        }
        jdbc.sql("UPDATE \"LpZone\" SET \"retiredAt\" = CURRENT_TIMESTAMP, enabled = false, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        agents.configChanged((String) before.get("agentId"));
        audit.recordInTx(AuditInput.of("LpZone", id, "delete", Obj.of("name", before.get("name")), null));
        return getZone(id);
    }

    /** Asks the agent to take the drawer's current picture as its closed state. */
    @Transactional
    public Map<String, Object> calibrate(String id) {
        Map<String, Object> z = zone(id);
        if (!"cash_drawer".equals(z.get("zoneType"))) {
            throw ApiException.badRequest("Only a cash_drawer zone is calibrated");
        }
        if (z.get("retiredAt") != null) {
            throw ApiException.badRequest("A retired zone cannot be changed");
        }
        LpLiveService.assertReachable(camera((String) z.get("cameraId")));
        String commandId = commands.queue((String) z.get("agentId"), "calibrate", Obj.of("cameraId",
                z.get("cameraId"), "zoneId", id), Duration.ofMinutes(5));
        return Obj.of("commandId", commandId, "zoneId", id);
    }
}
