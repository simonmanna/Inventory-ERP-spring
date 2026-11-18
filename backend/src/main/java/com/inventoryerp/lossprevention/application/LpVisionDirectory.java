package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only lookups of agents and zones of the caller's organization (LP1), shared by ingest and the agent routes. */
@Component
public class LpVisionDirectory {

    /** An enrolled edge agent, found from the API client of its token. */
    public record AgentRef(String id, String organizationId, String branchId, String name, String apiClientId,
            int configVersion) {}

    /** A zone with the camera and agent it belongs to. */
    public record ZoneRef(String id, String cameraId, String agentId, String branchId, String zoneType,
            String linkedType, String linkedId, boolean retired) {}

    /** A pour meter (LP3) with the agent that reads it, if any. */
    public record DeviceRef(String id, String branchId, String agentId, java.time.Instant retiredAt) {}

    private final JdbcClient jdbc;

    public LpVisionDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active agent whose credentials are the API client {@code apiClientId}, or {@code null}. */
    public AgentRef agentOfClient(String apiClientId) {
        if (apiClientId == null) {
            return null;
        }
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT id, \"organizationId\", \"branchId\", name,"
                + " \"apiClientId\", \"configVersion\" FROM \"LpAgent\" WHERE \"apiClientId\" = ? AND \"organizationId\" ="
                + " ? AND status = 'active'").params(apiClientId, LpScope.org()));
        return r == null ? null : new AgentRef((String) r.get("id"), (String) r.get("organizationId"),
                (String) r.get("branchId"), (String) r.get("name"), (String) r.get("apiClientId"),
                ((Number) r.get("configVersion")).intValue());
    }

    /** The zones among {@code ids} that exist in the organization, by id. Retired zones are included. */
    public Map<String, ZoneRef> zones(Collection<String> ids) {
        Map<String, ZoneRef> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT z.id, z.\"cameraId\", c.\"agentId\", z.\"branchId\","
                + " z.\"zoneType\", z.\"linkedType\", z.\"linkedId\", z.\"retiredAt\" FROM \"LpZone\" z JOIN \"LpCamera\" c"
                + " ON c.id = z.\"cameraId\" WHERE z.\"organizationId\" = ? AND z.id = ANY(?::text[])")
                .params(LpScope.org(), ids.stream().distinct().toArray(String[]::new)))) {
            out.put((String) r.get("id"), new ZoneRef((String) r.get("id"), (String) r.get("cameraId"),
                    (String) r.get("agentId"), (String) r.get("branchId"), (String) r.get("zoneType"),
                    (String) r.get("linkedType"), (String) r.get("linkedId"), r.get("retiredAt") != null));
        }
        return out;
    }

    /** The measurement devices among {@code ids} that exist in the organization, by id. Retired ones are included. */
    public Map<String, DeviceRef> devices(Collection<String> ids) {
        Map<String, DeviceRef> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"branchId\", \"agentId\", \"retiredAt\" FROM"
                + " \"LpMeasurementDevice\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])")
                .params(LpScope.org(), ids.stream().distinct().toArray(String[]::new)))) {
            out.put((String) r.get("id"), new DeviceRef((String) r.get("id"), (String) r.get("branchId"),
                    (String) r.get("agentId"), (java.time.Instant) r.get("retiredAt")));
        }
        return out;
    }
}
