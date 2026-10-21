package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.pos.domain.PosTableZone;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Configurable dining zones (port of {@code pos-table-zones.service.ts}). */
@Service
public class PosTableZonesService {

    public static final List<Map<String, Object>> DEFAULT_ZONES = List.of(
            Obj.of("key", "indoor", "name", "Indoor", "sortOrder", 1, "color", "#10b981"),
            Obj.of("key", "outdoor", "name", "Outdoor", "sortOrder", 2, "color", "#f59e0b"),
            Obj.of("key", "terrace", "name", "Terrace", "sortOrder", 3, "color", "#fb923c"),
            Obj.of("key", "vip", "name", "VIP", "sortOrder", 4, "color", "#a855f7"),
            Obj.of("key", "garden", "name", "Garden", "sortOrder", 5, "color", "#22c55e"),
            Obj.of("key", "bar", "name", "Bar", "sortOrder", 6, "color", "#ec4899"));

    private static final String LIVE = PosMenuService.LIVE;

    private final PosDb db;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    public PosTableZonesService(PosDb db, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.db = db;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    /** Deterministic slug used to derive a zone key from its name. */
    public static String slugifyZoneKey(String name) {
        String slug = name.toLowerCase().trim().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug.isEmpty() ? "zone" : slug;
    }

    private void publish(String organizationId, Map<String, Object> extra) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("zones", true);
        changes.putAll(extra);
        events.publish(DomainEvents.POS_TABLE_UPDATED, Obj.of("organizationId", organizationId, "tableId", "",
                "changes", changes));
    }

    /** Provision the default zones for an org that has none yet (idempotent). */
    public void ensureDefaults(String organizationId) {
        if (db.count(PosTableZone.class, LIVE, organizationId) > 0) {
            return;
        }
        for (Map<String, Object> z : DEFAULT_ZONES) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("organizationId", organizationId);
            d.putAll(z);
            d.put("active", true);
            d.put("createdBy", user());
            d.put("updatedBy", user());
            db.insert(PosTableZone.class, d);
        }
        publish(organizationId, Obj.of("zonesSeeded", true));
    }

    public List<Map<String, Object>> list() {
        String organizationId = org();
        ensureDefaults(organizationId);
        return db.list(PosTableZone.class, LIVE + " ORDER BY \"sortOrder\" ASC, name ASC", organizationId);
    }

    public List<Map<String, Object>> listDeleted() {
        return db.list(PosTableZone.class, "\"organizationId\" = ? AND \"deletedAt\" IS NOT NULL ORDER BY \"deletedAt\" DESC",
                org());
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String organizationId = org();
        String name = dto.get("name") == null ? "" : dto.get("name").toString().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Zone name is required");
        }
        String requested = dto.get("key") == null ? "" : dto.get("key").toString().trim();
        String key = (requested.isEmpty() ? slugifyZoneKey(dto.get("name").toString()) : requested).toLowerCase();
        Map<String, Object> created;
        try {
            created = tx.execute(st -> {
                String color = dto.get("color") == null ? "" : dto.get("color").toString().trim();
                Map<String, Object> zone = db.insert(PosTableZone.class, Obj.of("organizationId", organizationId,
                        "key", key, "name", name, "sortOrder", dto.get("sortOrder") == null ? 0 : dto.get("sortOrder"),
                        "color", color.isEmpty() ? "#10b981" : color,
                        "active", dto.get("active") == null ? true : dto.get("active"),
                        "createdBy", user(), "updatedBy", user()));
                audit.recordInTx(AuditInput.of("PosTableZone", (String) zone.get("id"), "create", null,
                        Obj.of("key", zone.get("key"), "name", zone.get("name"), "sortOrder", zone.get("sortOrder"),
                                "color", zone.get("color"))));
                return zone;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Zone key \"" + key + "\" already exists");
        }
        publish(organizationId, Obj.of("zoneCreated", created.get("key")));
        return created;
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        String organizationId = org();
        Map<String, Object> existing = db.first(PosTableZone.class, "id = ? AND " + LIVE, id, organizationId);
        if (existing == null) {
            throw ApiException.notFound("Zone not found");
        }
        if (dto.containsKey("key") && !dto.get("key").toString().trim().equals(existing.get("key"))) {
            throw ApiException.badRequest(
                    "Zone key cannot be changed — tables reference it. Archive and recreate instead.");
        }
        if (dto.containsKey("name") && dto.get("name").toString().trim().isEmpty()) {
            throw ApiException.badRequest("Zone name cannot be empty");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String f : List.of("name", "sortOrder", "color", "active")) {
            if (dto.containsKey(f) && !jsEquals(existing.get(f), dto.get(f))) {
                changes.put(f, Obj.of("from", existing.get(f), "to", dto.get(f)));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", dto.get("name").toString().trim());
        }
        if (dto.containsKey("sortOrder")) {
            data.put("sortOrder", dto.get("sortOrder"));
        }
        if (dto.containsKey("color")) {
            data.put("color", dto.get("color").toString().trim());
        }
        if (dto.containsKey("active")) {
            data.put("active", dto.get("active"));
        }
        data.put("updatedBy", user());
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> zone = db.update(PosTableZone.class, data, "id = ? AND " + LIVE, id, organizationId);
            if (!changes.isEmpty()) {
                audit.recordInTx(AuditInput.of("PosTableZone", id, "update", changes, null));
            }
            return zone;
        });
        publish(organizationId, Obj.of("zoneUpdated", updated.get("key")));
        return updated;
    }

    /** JS {@code !==} between a DB value and a JSON value (numbers compare by value). */
    static boolean jsEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    public Map<String, Object> archive(String id) {
        String organizationId = org();
        Map<String, Object> existing = db.first(PosTableZone.class, "id = ? AND " + LIVE, id, organizationId);
        if (existing == null) {
            throw ApiException.notFound("Zone not found");
        }
        long refCount = db.jdbc().sql("SELECT count(*) FROM \"PosTable\" WHERE \"organizationId\" = ? AND zone = ?"
                + " AND active = true").params(organizationId, existing.get("key")).query(Long.class).single();
        if (refCount > 0) {
            throw ApiException.conflict("Zone \"" + existing.get("name") + "\" has " + refCount + " active table"
                    + (refCount == 1 ? "" : "s") + " — reassign them before archiving");
        }
        Map<String, Object> archived = tx.execute(st -> {
            Map<String, Object> zone = db.update(PosTableZone.class, Obj.of("deletedAt", PosDb.raw(NOW),
                    "updatedBy", user()), "id = ? AND " + LIVE, id, organizationId);
            audit.recordInTx(AuditInput.of("PosTableZone", id, "delete",
                    Obj.of("key", existing.get("key"), "name", existing.get("name")), null));
            return zone;
        });
        publish(organizationId, Obj.of("zoneArchived", existing.get("key")));
        return archived;
    }

    public Map<String, Object> restore(String id) {
        String organizationId = org();
        Map<String, Object> existing = db.first(PosTableZone.class,
                "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL", id, organizationId);
        if (existing == null) {
            throw ApiException.notFound("Archived zone not found");
        }
        Map<String, Object> restored = tx.execute(st -> {
            int n = db.updateMany(PosTableZone.class, Obj.of("deletedAt", null, "updatedBy", user()),
                    "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL", id, organizationId);
            if (n == 0) {
                throw ApiException.notFound("Archived zone not found");
            }
            Map<String, Object> zone = db.first(PosTableZone.class, "id = ? AND " + LIVE, id, organizationId);
            audit.recordInTx(AuditInput.of("PosTableZone", id, "restore", null,
                    Obj.of("key", existing.get("key"), "name", existing.get("name"))));
            return zone;
        });
        publish(organizationId, Obj.of("zoneRestored", existing.get("key")));
        return restored;
    }

    /** Active zone row for a key, or {@code null}. */
    public Map<String, Object> resolveKey(String organizationId, String key) {
        return db.first(PosTableZone.class, LIVE + " AND key = ? AND active = true", organizationId, key);
    }

    /** key → {name, color} of active zones. */
    public Map<String, Map<String, Object>> mapForOrganization(String organizationId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> z : db.list(PosTableZone.class, LIVE + " AND active = true", organizationId)) {
            out.put((String) z.get("key"), Obj.of("name", z.get("name"), "color", z.get("color")));
        }
        return out;
    }
}
