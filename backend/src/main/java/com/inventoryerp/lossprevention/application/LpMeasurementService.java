package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.lossprevention.application.LpMeasurementCommands.CreateDeviceInput;
import com.inventoryerp.lossprevention.application.LpMeasurementCommands.UpdateDeviceInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pour meters (LP3, docs/phases/LP3-bar-intelligence.md): spouts, flow meters and dispensers whose readings reach the
 * ledger as {@code pour.measured}. A device read by an edge agent is listed in that agent's configuration, so every
 * change raises the agent's configuration version.
 */
@Service
public class LpMeasurementService {

    public static final List<String> KINDS = List.of("pour_spout", "flow_meter", "dispenser");

    private static final String COLUMNS = "d.*, b.name AS \"branchName\", a.name AS \"agentName\", p.name AS"
            + " \"productName\", (SELECT max(e.\"occurredAt\") FROM \"LpEvent\" e WHERE e.\"organizationId\" ="
            + " d.\"organizationId\" AND e.\"deviceId\" = d.id AND e.type = 'pour.measured') AS \"lastReadingAt\","
            + " (SELECT count(*) FROM \"LpEvent\" e WHERE e.\"organizationId\" = d.\"organizationId\" AND e.\"deviceId\" ="
            + " d.id AND e.type = 'pour.measured' AND e.\"occurredAt\" >= CURRENT_TIMESTAMP - INTERVAL '7 days') AS"
            + " \"readings7d\"";
    private static final String FROM = " FROM \"LpMeasurementDevice\" d LEFT JOIN \"Branch\" b ON b.id = d.\"branchId\""
            + " LEFT JOIN \"LpAgent\" a ON a.id = d.\"agentId\" LEFT JOIN \"Product\" p ON p.id = d.\"productId\"";

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpAgentService agents;
    private final AuditService audit;

    public LpMeasurementService(JdbcClient jdbc, LpScope scope, LpAgentService agents, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.agents = agents;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String branchId, boolean includeRetired) {
        scope.assertBranch(branchId);
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("d", params));
        if (LpScope.has(branchId)) {
            w.append(" AND d.\"branchId\" = ?");
            params.add(branchId);
        }
        if (!includeRetired) {
            w.append(" AND d.\"retiredAt\" IS NULL");
        }
        return SqlRows.list(jdbc.sql("SELECT " + COLUMNS + FROM + w + " ORDER BY b.name, d.station NULLS LAST, d.name,"
                + " d.id").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("d", params) + " AND d.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT " + COLUMNS + FROM + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Measurement device not found");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> create(CreateDeviceInput dto) {
        scope.assertBranch(dto.branchId());
        if (jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(dto.branchId(), LpScope.org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Branch not found");
        }
        if (!KINDS.contains(dto.kind())) {
            throw ApiException.badRequest("kind must be one of: " + String.join(", ", KINDS));
        }
        String externalId = dto.externalId().trim();
        String agentId = blankToNull(dto.agentId());
        checkLinks(dto.branchId(), agentId, blankToNull(dto.productId()), dto.standardPourMl());
        if (jdbc.sql("SELECT count(*) FROM \"LpMeasurementDevice\" WHERE \"organizationId\" = ? AND \"branchId\" = ? AND"
                + " \"externalId\" = ? AND \"retiredAt\" IS NULL").params(LpScope.org(), dto.branchId(), externalId)
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("Another measurement device at this branch already uses this externalId");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpMeasurementDevice\" (id, \"organizationId\", \"branchId\", \"agentId\", name, kind,"
                + " vendor, \"externalId\", \"productId\", station, \"standardPourMl\", enabled) VALUES (?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), dto.branchId(), agentId, dto.name().trim(),
                        dto.kind(), blankToNull(dto.vendor()), externalId, blankToNull(dto.productId()),
                        blankToNull(dto.station()), dto.standardPourMl(), dto.enabled() == null || dto.enabled()))
                .update();
        if (agentId != null) {
            agents.configChanged(agentId);
        }
        audit.recordInTx(AuditInput.of("LpMeasurementDevice", id, "create", null, Obj.of("name", dto.name().trim(),
                "kind", dto.kind(), "externalId", externalId, "agentId", agentId, "productId",
                blankToNull(dto.productId()), "station", blankToNull(dto.station()))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateDeviceInput dto) {
        Map<String, Object> before = get(id);
        if (before.get("retiredAt") != null) {
            throw ApiException.badRequest("A retired measurement device cannot be changed");
        }
        String agentId = blankToNull(dto.agentId());
        String productId = blankToNull(dto.productId());
        checkLinks((String) before.get("branchId"), agentId, productId, dto.standardPourMl());
        boolean enabled = dto.enabled() == null ? Boolean.TRUE.equals(before.get("enabled")) : dto.enabled();
        jdbc.sql("UPDATE \"LpMeasurementDevice\" SET \"agentId\" = ?, name = ?, vendor = ?, \"productId\" = ?, station = ?,"
                + " \"standardPourMl\" = ?, enabled = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(java.util.Arrays.asList(agentId, dto.name().trim(), blankToNull(dto.vendor()), productId,
                        blankToNull(dto.station()), dto.standardPourMl(), enabled, id))
                .update();
        configChanged((String) before.get("agentId"), agentId);
        audit.recordInTx(AuditInput.of("LpMeasurementDevice", id, "update", Obj.of("name", before.get("name"),
                "agentId", before.get("agentId"), "productId", before.get("productId"), "station",
                before.get("station"), "standardPourMl", before.get("standardPourMl"), "enabled",
                before.get("enabled")), Obj.of("name", dto.name().trim(), "agentId", agentId, "productId", productId,
                        "station", blankToNull(dto.station()), "standardPourMl", dto.standardPourMl(), "enabled",
                        enabled)));
        return get(id);
    }

    /** Retires the device: its agent stops reading it; its past readings keep pointing at it. */
    @Transactional
    public Map<String, Object> retire(String id) {
        Map<String, Object> before = get(id);
        if (before.get("retiredAt") != null) {
            throw ApiException.badRequest("The measurement device is already retired");
        }
        jdbc.sql("UPDATE \"LpMeasurementDevice\" SET \"retiredAt\" = CURRENT_TIMESTAMP, enabled = false, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        configChanged((String) before.get("agentId"), null);
        audit.recordInTx(AuditInput.of("LpMeasurementDevice", id, "delete", Obj.of("name", before.get("name")), null));
        return get(id);
    }

    private void checkLinks(String branchId, String agentId, String productId, BigDecimal standardPourMl) {
        if (agentId != null) {
            Map<String, Object> agent = SqlRows.one(jdbc.sql("SELECT \"branchId\", status FROM \"LpAgent\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(agentId, LpScope.org()));
            if (agent == null) {
                throw ApiException.badRequest("Agent not found");
            }
            if ("revoked".equals(agent.get("status"))) {
                throw ApiException.badRequest("A revoked agent cannot read measurement devices");
            }
            if (!branchId.equals(agent.get("branchId"))) {
                throw ApiException.badRequest("The agent is at another branch");
            }
        }
        if (productId != null && jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, LpScope.org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Product not found");
        }
        if (standardPourMl != null && (standardPourMl.signum() <= 0
                || standardPourMl.compareTo(BigDecimal.valueOf(LpEventTypes.MAX_POUR_ML)) > 0)) {
            throw ApiException.badRequest("standardPourMl must be above 0 and at most " + LpEventTypes.MAX_POUR_ML);
        }
    }

    private void configChanged(String before, String after) {
        if (before != null) {
            agents.configChanged(before);
        }
        if (after != null && !Objects.equals(before, after)) {
            agents.configChanged(after);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
