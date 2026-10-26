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

    public SyncDevicesService(PosDb db, AuditService audit, LicenseLimits licenseLimits) {
        this.db = db;
        this.audit = audit;
        this.licenseLimits = licenseLimits;
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
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
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
                    "revokedAt", d.get("revokedAt"), "createdAt", d.get("createdAt")));
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
        try {
            db.jdbc().sql("UPDATE \"PosDevice\" SET \"lastSeenAt\" = " + PosSql.NOW + ", \"updatedAt\" = " + PosSql.NOW
                    + " WHERE id = ?").param(deviceId).update();
        } catch (RuntimeException ignored) {
            // fire-and-forget like Nest
        }
    }
}
