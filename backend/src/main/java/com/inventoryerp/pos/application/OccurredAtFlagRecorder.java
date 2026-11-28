package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * IPR review 1 (finding 1, SW-7 / ADR-S088, owner decision P15-1): records the flagged {@code occurredAt} of the online
 * POS and cash routes in the discrepancy report, as the sync push does for a device op. These routes also take the
 * web client's offline-queue replays, so a time that is too old ({@code offline_late_sync}, kept as sent) or too far
 * ahead ({@code offline_clock_skew}, stored at the receive time) is accepted and leaves a report-only
 * {@code InventoryException} row (this module owns the table) with {@code payload.channel = online}.
 *
 * <p>The row is written in the caller's transaction when one is open (a refused sale leaves no flag), else on its own.
 * One request resolves the same time more than once (a checkout resolves it for the order, the invoice and the
 * payment), so an open row of the same kind, person and device time is not written again.
 */
@Component
public class OccurredAtFlagRecorder {

    private final JdbcClient jdbc;

    public OccurredAtFlagRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
        OccurredAt.recorder(this::record);
    }

    void record(String source, OccurredAt.Resolved r) {
        String org = TenantContext.optionalOrganizationId();
        if (org == null || r == null || !r.flagged()) {
            return;
        }
        if (r.clockSkew()) {
            write(org, OccurredAt.CLOCK_SKEW_KIND, source, r, "Device clock ahead of the server",
                    "The " + source + " was stamped " + r.device() + ", ahead of the server; it was recorded at the"
                            + " receive time " + r.at() + ". Check the device clock.");
        }
        if (r.lateSync()) {
            write(org, OccurredAt.LATE_SYNC_KIND, source, r, "Old offline work synced late",
                    "The " + source + " happened at " + r.device() + " and reached the server late; it was accepted"
                            + " as of that time.");
        }
    }

    private void write(String org, String kind, String source, OccurredAt.Resolved r, String description,
            String reason) {
        String actor = TenantContext.userId();
        String deviceTime = r.device() == null ? null : r.device().toString();
        long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ?"
                        + " AND status = 'open' AND payload->>'channel' = 'online' AND payload->>'deviceTime' = ?"
                        + " AND payload->>'actorUserId' IS NOT DISTINCT FROM ?")
                .params(org, kind, deviceTime, actor).query(Long.class).single();
        if (open > 0) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", "online");
        payload.put("source", source);
        payload.put("opType", source);
        payload.put("actorUserId", actor);
        payload.put("occurredAt", deviceTime);
        payload.put("deviceTime", deviceTime);
        payload.put("storedTime", r.at() == null ? null : r.at().toString());
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", description, quantity, kind, reason,"
                        + " payload, status, \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'open', CURRENT_TIMESTAMP)")
                .params(UUID.randomUUID().toString(), org, description, BigDecimal.ZERO, kind, reason,
                        EntityJson.toJson(payload))
                .update();
    }
}
