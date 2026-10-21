package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.domain.KitchenStation;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Configurable kitchen stations (port of {@code kitchen-station.service.ts}). */
@Service
public class KitchenStationService {

    /** Seeded for every org so legacy {@code Product.station} codes resolve. */
    public static final List<Map<String, Object>> DEFAULT_STATIONS = List.of(
            Obj.of("name", "Kitchen", "code", "kitchen", "color", "#f43f5e", "icon", "ChefHat", "displayOrder", 0,
                    "isDefault", false),
            Obj.of("name", "Bar", "code", "bar", "color", "#f59e0b", "icon", "Coffee", "displayOrder", 1,
                    "isDefault", false),
            Obj.of("name", "Cafe", "code", "cafe", "color", "#10b981", "icon", "Sandwich", "displayOrder", 2,
                    "isDefault", true));

    private static final String CODE_RE = "^[a-z0-9][a-z0-9_-]{0,31}$";
    private static final String LIVE = "\"organizationId\" = ? AND \"deletedAt\" IS NULL";

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public KitchenStationService(PosDb db, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    static Map<String, Object> serialize(Map<String, Object> s) {
        return Obj.of("id", s.get("id"), "name", s.get("name"), "code", s.get("code"), "color", s.get("color"),
                "icon", s.get("icon"), "displayOrder", s.get("displayOrder"), "isActive", s.get("isActive"),
                "isDefault", s.get("isDefault"), "printerId", s.get("printerId"));
    }

    private void createDefaults() {
        String orgId = org();
        for (Map<String, Object> d : DEFAULT_STATIONS) {
            if (db.first(KitchenStation.class, "code = ? AND " + LIVE, d.get("code"), orgId) != null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.putAll(d);
            db.insert(KitchenStation.class, data);
        }
    }

    /** All non-deleted stations; an org with none self-heals with the three defaults. */
    public List<Map<String, Object>> list(boolean includeInactive) {
        return tx.execute(st -> {
            String where = LIVE + (includeInactive ? "" : " AND \"isActive\" = true")
                    + " ORDER BY \"displayOrder\" ASC, name ASC";
            List<Map<String, Object>> rows = db.list(KitchenStation.class, where, org());
            if (rows.isEmpty()) {
                createDefaults();
                rows = db.list(KitchenStation.class, where, org());
            }
            List<Map<String, Object>> out = new ArrayList<>();
            rows.forEach(r -> out.add(serialize(r)));
            return out;
        });
    }

    public void ensureDefaults() {
        tx.executeWithoutResult(st -> createDefaults());
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = org();
        String code = dto.get("code") == null ? "" : dto.get("code").toString().trim().toLowerCase();
        if (!code.matches(CODE_RE)) {
            throw ApiException.badRequest("Station code must be lowercase letters/numbers/-/_ (max 32 chars).");
        }
        String name = dto.get("name").toString().trim();
        Map<String, Object> result = tx.execute(st -> {
            List<String> dead = jdbc.sql("SELECT id FROM \"KitchenStation\" WHERE \"organizationId\" = ? AND code = ?"
                    + " AND \"deletedAt\" IS NOT NULL LIMIT 1").params(orgId, code).query(String.class).list();
            if (!dead.isEmpty()) {
                return db.update(KitchenStation.class, Obj.of("deletedAt", null, "name", name, "color", dto.get("color"),
                        "icon", dto.get("icon"), "displayOrder", dto.get("displayOrder") == null ? 0
                                : dto.get("displayOrder"), "isActive", true, "printerId", dto.get("printerId")),
                        "id = ? AND \"organizationId\" = ?", dead.getFirst(), orgId);
            }
            if (db.first(KitchenStation.class, "code = ? AND " + LIVE, code, orgId) != null) {
                throw ApiException.badRequest("A station with code \"" + code + "\" already exists.");
            }
            return db.insert(KitchenStation.class, Obj.of("organizationId", orgId, "name", name, "code", code,
                    "color", dto.get("color"), "icon", dto.get("icon"),
                    "displayOrder", dto.get("displayOrder") == null ? 0 : dto.get("displayOrder"), "isDefault", false,
                    "printerId", dto.get("printerId")));
        });
        String id = (String) result.get("id");
        if (Boolean.TRUE.equals(dto.get("isDefault"))) {
            setDefault(id);
        }
        audit.record(AuditInput.of("KitchenStation", id, "create", null, Obj.of("code", code)));
        return getView(id);
    }

    /** Update editable fields; {@code code} is immutable. */
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", dto.get("name") == null ? null : dto.get("name").toString().trim());
        }
        for (String k : List.of("color", "icon", "displayOrder", "isActive", "printerId")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        tx.executeWithoutResult(st -> {
            if (db.first(KitchenStation.class, "id = ? AND " + LIVE, id, org()) == null) {
                throw ApiException.notFound("Kitchen station not found");
            }
            if (!data.isEmpty()) {
                db.update(KitchenStation.class, data, "id = ? AND " + LIVE, id, org());
            }
        });
        if (Boolean.TRUE.equals(dto.get("isDefault"))) {
            setDefault(id);
        }
        audit.record(AuditInput.of("KitchenStation", id, "update", null, data));
        return getView(id);
    }

    public List<Map<String, Object>> reorder(List<String> orderedIds) {
        tx.executeWithoutResult(st -> {
            for (int i = 0; i < orderedIds.size(); i++) {
                db.updateMany(KitchenStation.class, Obj.of("displayOrder", i), "id = ? AND " + LIVE, orderedIds.get(i),
                        org());
            }
        });
        return list(true);
    }

    public void setDefault(String id) {
        tx.executeWithoutResult(st -> {
            if (db.first(KitchenStation.class, "id = ? AND " + LIVE, id, org()) == null) {
                throw ApiException.notFound("Kitchen station not found");
            }
            db.updateMany(KitchenStation.class, Obj.of("isDefault", false), "\"isDefault\" = true AND " + LIVE, org());
            db.update(KitchenStation.class, Obj.of("isDefault", true), "id = ? AND " + LIVE, id, org());
        });
    }

    public Map<String, Object> remove(String id) {
        String code = tx.execute(st -> {
            Map<String, Object> s = db.first(KitchenStation.class, "id = ? AND " + LIVE, id, org());
            if (s == null) {
                throw ApiException.notFound("Kitchen station not found");
            }
            if (Boolean.TRUE.equals(s.get("isDefault"))) {
                throw ApiException.badRequest(
                        "Cannot delete the default station. Set another station as default first.");
            }
            db.update(KitchenStation.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "id = ? AND " + LIVE, id, org());
            return (String) s.get("code");
        });
        audit.record(AuditInput.of("KitchenStation", id, "delete", null, Obj.of("code", code)));
        return Obj.of("id", id);
    }

    private Map<String, Object> getView(String id) {
        Map<String, Object> s = db.first(KitchenStation.class, "id = ? AND " + LIVE, id, org());
        if (s == null) {
            throw ApiException.notFound("Kitchen station not found");
        }
        return serialize(s);
    }
}
