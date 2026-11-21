package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.licensing.LicenseLimits;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.sync.domain.PosDevice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Offline device registry (port of {@code sync-devices.service.ts}). The opaque token is returned exactly once; only
 * its sha256 is stored. Devices present it as {@code X-Device-Token} on {@code /sync/pull} and {@code /sync/push}.
 */
@Service
public class SyncDevicesService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PosDb db;
    private final AuditService audit;
    private final LicenseLimits licenseLimits;
    private SyncPolicyService policy;
    private SyncLedger ledger;

    public SyncDevicesService(PosDb db, AuditService audit, LicenseLimits licenseLimits) {
        this.db = db;
        this.audit = audit;
        this.licenseLimits = licenseLimits;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSyncCollaborators(SyncPolicyService policy, SyncLedger ledger) {
        this.policy = policy;
        this.ledger = ledger;
    }

    static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> register(String nameIn, String platform, String branchId) {
        String name = nameIn == null ? "" : nameIn.trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("A device name is required");
        }
        // A terminal is a licensed seat, so the cap is checked before one is issued a token.
        licenseLimits.assertCanAddDevice();
        String token = newToken();
        String orgId = TenantContext.organizationId();
        long count = db.count(PosDevice.class, "\"organizationId\" = ?", orgId);
        String prefix = "D" + (count + 1);
        for (int i = 0; i < 20; i++) {
            if (db.first(PosDevice.class, "\"organizationId\" = ? AND prefix = ?", orgId, prefix) == null) {
                break;
            }
            prefix = "D" + (count + 2 + i);
        }
        Map<String, Object> device = db.insert(PosDevice.class, Obj.of("organizationId", orgId, "name", name,
                "platform", platform == null ? "android" : platform, "branchId", branchId, "tokenHash", sha256Hex(token),
                "prefix", prefix, "registeredById", TenantContext.userId()));
        // ADR-S083 S2/C4: issuing server and token expiry (additive columns, not in the Nest entity).
        db.jdbc().sql("UPDATE \"PosDevice\" SET \"serverId\" = ?, \"tokenExpiresAt\" = " + PosSql.NOW
                + " + make_interval(days => ?) WHERE id = ?")
                .params(ledger.serverId(), policy.tokenTtlDays(orgId), device.get("id")).update();
        audit.record(AuditInput.of("PosDevice", (String) device.get("id"), "create", null, Obj.of("name", device.get("name"),
                "platform", device.get("platform"), "prefix", device.get("prefix"))));
        return Obj.of("id", device.get("id"), "name", device.get("name"), "platform", device.get("platform"),
                "prefix", device.get("prefix"), "deviceToken", token);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : db.list(PosDevice.class, "\"organizationId\" = ? ORDER BY \"createdAt\" ASC",
                TenantContext.organizationId())) {
            out.add(Obj.of("id", d.get("id"), "name", d.get("name"), "platform", d.get("platform"), "prefix", d.get("prefix"),
                    "branchId", d.get("branchId"), "lastSeenAt", d.get("lastSeenAt"), "lastPushSeq", d.get("lastPushSeq"),
                    "revokedAt", d.get("revokedAt"), "createdAt", d.get("createdAt"),
                    // ADR-S083 (additive)
                    "serverId", d.get("serverId"), "tokenExpiresAt", d.get("tokenExpiresAt"),
                    "protocolVersion", d.get("protocolVersion"), "lastPullAt", d.get("lastPullAt"),
                    "offlineWindowHours", d.get("offlineWindowHours")));
        }
        return out;
    }

    public Map<String, Object> revoke(String id) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> device = db.first(PosDevice.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (device == null) {
            throw ApiException.notFound("Device not found");
        }
        if (device.get("revokedAt") != null) {
            return Obj.of("id", id, "revokedAt", device.get("revokedAt"));
        }
        Map<String, Object> updated = db.update(PosDevice.class, Obj.of("revokedAt", PosDb.raw(PosSql.NOW)),
                "id = ? AND \"organizationId\" = ?", id, orgId);
        audit.record(AuditInput.of("PosDevice", id, "update", null, Obj.of("revoked", true)));
        return Obj.of("id", id, "revokedAt", updated.get("revokedAt"));
    }

    /** Heartbeat for an authenticated device call; never fails the sync. */
    public void touch(String deviceId) {
        touch(deviceId, null);
    }

    /** Heartbeat plus the protocol version the device declared (ADR-S083 S8). */
    public void touch(String deviceId, String protocolVersion) {
        try {
            db.jdbc().sql("UPDATE \"PosDevice\" SET \"lastSeenAt\" = " + PosSql.NOW + ", \"updatedAt\" = " + PosSql.NOW
                    + ", \"protocolVersion\" = COALESCE(?, \"protocolVersion\") WHERE id = ?")
                    .params(protocolVersion, deviceId).update();
        } catch (RuntimeException ignored) {
            // fire-and-forget like Nest
        }
    }

    /**
     * ADR-S083 S2/C4 after a successful pull. Protocol 2: rotate the token (the replaced one stays valid for a grace
     * period, so a lost response does not lock the device out; a pull made with that previous token is answered with a
     * fresh token again while the grace lasts). Protocol 1 clients cannot store a new token, so their expiry slides
     * forward instead. Returns the members to add to the pull response.
     */
    public Map<String, Object> afterPull(Map<?, ?> device, boolean rotate) {
        String id = (String) device.get("id");
        String orgId = (String) device.get("organizationId");
        int ttl = policy.tokenTtlDays(orgId);
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        if (rotate) {
            String token = newToken();
            boolean viaCurrent = Boolean.TRUE.equals(device.get("currentToken"));
            String sql = "UPDATE \"PosDevice\" SET \"tokenHash\" = ?, \"tokenRotatedAt\" = " + PosSql.NOW
                    + ", \"lastPullAt\" = " + PosSql.NOW + ", \"tokenExpiresAt\" = " + PosSql.NOW
                    + " + make_interval(days => ?)"
                    + (viaCurrent ? ", \"previousTokenHash\" = \"tokenHash\", \"previousTokenValidUntil\" = " + PosSql.NOW
                            + " + make_interval(hours => " + SyncPolicyService.PREVIOUS_TOKEN_GRACE_HOURS + ")" : "")
                    + " WHERE id = ? AND \"revokedAt\" IS NULL";
            db.jdbc().sql(sql).params(sha256Hex(token), ttl, id).update();
            extra.put("deviceToken", token);
        } else {
            db.jdbc().sql("UPDATE \"PosDevice\" SET \"lastPullAt\" = " + PosSql.NOW + ", \"tokenExpiresAt\" = "
                    + PosSql.NOW + " + make_interval(days => ?) WHERE id = ? AND \"revokedAt\" IS NULL")
                    .params(ttl, id).update();
        }
        Map<String, Object> row = db.first(PosDevice.class, "id = ?", id);
        Object override = db.jdbc().sql("SELECT \"offlineWindowHours\" FROM \"PosDevice\" WHERE id = ?").param(id)
                .query(Integer.class).optional().orElse(null);
        extra.put("deviceTokenExpiresAt", db.jdbc().sql("SELECT \"tokenExpiresAt\" FROM \"PosDevice\" WHERE id = ?")
                .param(id).query((rs, n) -> {
                    java.sql.Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : com.inventoryerp.kernel.web.JsonConfig.toJsIso(
                            t.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC));
                }).optional().orElse(null));
        extra.put("offlinePolicy", policy.offlinePolicy(orgId, (Integer) override));
        extra.put("serverId", ledger.serverId());
        extra.put("deviceBranchId", row == null ? null : row.get("branchId"));
        return extra;
    }

    /** ADR-S083 C4: a device policy may only lower the org's offline-authorization window ({@code null} clears it). */
    public Map<String, Object> setPolicy(String id, Integer offlineWindowHours) {
        if (offlineWindowHours != null && (offlineWindowHours < 1 || offlineWindowHours > 720)) {
            throw ApiException.badRequest("offlineWindowHours must be between 1 and 720");
        }
        String orgId = TenantContext.organizationId();
        Map<String, Object> device = db.first(PosDevice.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (device == null) {
            throw ApiException.notFound("Device not found");
        }
        db.jdbc().sql("UPDATE \"PosDevice\" SET \"offlineWindowHours\" = ?, \"updatedAt\" = " + PosSql.NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(offlineWindowHours, id, orgId).update();
        audit.record(AuditInput.of("PosDevice", id, "update", null, Obj.of("offlineWindowHours", offlineWindowHours)));
        return Obj.of("id", id, "offlineWindowHours", offlineWindowHours,
                "effectiveOfflineWindowHours", policy.offlineWindowHours(orgId, offlineWindowHours));
    }
}
