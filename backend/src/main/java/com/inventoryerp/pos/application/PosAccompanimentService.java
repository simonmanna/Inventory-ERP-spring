package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.AccompanimentGroup;
import com.inventoryerp.pos.domain.AccompanimentOption;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Accompaniment groups / options and their menu-item assignment (port of {@code pos-accompaniment.service.ts}). */
@Service
public class PosAccompanimentService {

    private static final String LIVE = PosMenuService.LIVE;

    /** {@code validateSelections} result. */
    public record Selection(List<String> names, double priceImpact) {}

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PosAccompanimentService(PosDb db, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    static Map<String, Object> mapOption(Map<String, Object> o) {
        return Obj.of("id", o.get("id"), "groupId", o.get("groupId"), "name", o.get("name"),
                "priceImpact", PosSql.js(o.get("priceImpact")), "isDefault", o.get("isDefault"),
                "sortOrder", o.get("sortOrder"), "isActive", o.get("isActive"), "inventoryItemId", o.get("inventoryItemId"));
    }

    static Map<String, Object> mapGroup(Map<String, Object> g, List<Map<String, Object>> options) {
        List<Map<String, Object>> opts = new ArrayList<>();
        options.forEach(o -> opts.add(mapOption(o)));
        return Obj.of("id", g.get("id"), "name", g.get("name"), "category", g.get("category"),
                "isRequired", g.get("isRequired"), "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"),
                "sortOrder", g.get("sortOrder"), "isActive", g.get("isActive"), "options", opts);
    }

    private List<Map<String, Object>> options(Object groupId, String filter) {
        return db.list(AccompanimentOption.class, "\"groupId\" = ?" + filter + " ORDER BY \"sortOrder\" ASC", groupId);
    }

    public Map<String, Object> listAllGroups(String search, Boolean isActive, Integer pageIn, Integer pageSizeIn) {
        String orgId = org();
        StringBuilder where = new StringBuilder(LIVE);
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (isActive != null) {
            where.append(" AND \"isActive\" = ?");
            params.add(isActive);
        }
        if (search != null && !search.isEmpty()) {
            where.append(" AND name ILIKE ?");
            params.add(PosSql.contains(search));
        }
        int page = pageIn == null ? 1 : pageIn;
        int pageSize = pageSizeIn == null ? 50 : pageSizeIn;
        List<Object> pp = new ArrayList<>(params);
        pp.add(pageSize);
        pp.add((page - 1) * pageSize);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> g : SqlRows.list(AccompanimentGroup.class, jdbc.sql("SELECT * FROM \"AccompanimentGroup\""
                + " WHERE " + where + " ORDER BY \"sortOrder\" ASC LIMIT ? OFFSET ?").params(pp))) {
            data.add(mapGroup(g, options(g.get("id"), "")));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"AccompanimentGroup\" WHERE " + where).params(params)
                .query(Long.class).single();
        return Obj.of("data", data, "total", total, "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> getGroup(String id) {
        Map<String, Object> g = db.first(AccompanimentGroup.class, "id = ? AND " + LIVE, id, org());
        if (g == null) {
            throw ApiException.notFound("AccompanimentGroup " + id + " not found");
        }
        return mapGroup(g, options(id, " AND \"isActive\" = true AND \"deletedAt\" IS NULL"));
    }

    private static String trimmed(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static Object orNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public Map<String, Object> createGroup(Map<String, Object> dto) {
        String orgId = org();
        String name = trimmed(dto.get("name"));
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Accompaniment group name is required");
        }
        double minSelect = dto.get("minSelect") == null ? 1 : PosSql.num(dto.get("minSelect"));
        double maxSelect = dto.get("maxSelect") == null ? 1 : PosSql.num(dto.get("maxSelect"));
        if (minSelect > maxSelect) {
            throw ApiException.badRequest("minSelect cannot be greater than maxSelect");
        }
        if (db.first(AccompanimentGroup.class, LIVE + " AND name = ?", orgId, name) != null) {
            throw ApiException.badRequest("Group \"" + name + "\" already exists");
        }
        Map<String, Object> created = tx.execute(st -> {
            Map<String, Object> g = db.insert(AccompanimentGroup.class, Obj.of("organizationId", orgId, "name", name,
                    "category", orNull(trimmed(dto.get("category"))),
                    "isRequired", dto.get("isRequired") == null ? true : dto.get("isRequired"),
                    "minSelect", dto.get("minSelect") == null ? 1 : dto.get("minSelect"),
                    "maxSelect", dto.get("maxSelect") == null ? 1 : dto.get("maxSelect"),
                    "sortOrder", dto.get("sortOrder") == null ? 0 : dto.get("sortOrder"), "createdBy", user()));
            audit.recordInTx(AuditInput.of("AccompanimentGroup", (String) g.get("id"), "create", null,
                    Obj.of("name", g.get("name"), "category", g.get("category"), "isRequired", g.get("isRequired"),
                            "minSelect", g.get("minSelect"), "maxSelect", g.get("maxSelect"))));
            return g;
        });
        return mapGroup(created, List.of());
    }

    public Map<String, Object> updateGroup(String id, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(AccompanimentGroup.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("AccompanimentGroup " + id + " not found");
        }
        if (dto.containsKey("name") && (dto.get("name") == null || trimmed(dto.get("name")).isEmpty())) {
            throw ApiException.badRequest("Group name cannot be empty");
        }
        if (PosSql.truthy(dto.get("expectedUpdatedAt")) && existing.get("updatedAt") != null) {
            Instant expected = PosSql.date(dto.get("expectedUpdatedAt").toString());
            if (!existing.get("updatedAt").equals(expected)) {
                throw ApiException.conflict(
                        "This accompaniment group was modified by another user. Please reload and try again.");
            }
        }
        if (dto.containsKey("minSelect") && dto.containsKey("maxSelect")
                && PosSql.num(dto.get("minSelect")) > PosSql.num(dto.get("maxSelect"))) {
            throw ApiException.badRequest("minSelect cannot be greater than maxSelect");
        }
        if (dto.containsKey("name") && !trimmed(dto.get("name")).equals(existing.get("name"))) {
            if (db.first(AccompanimentGroup.class, LIVE + " AND name = ? AND id <> ?", orgId, trimmed(dto.get("name")), id)
                    != null) {
                throw ApiException.badRequest("Group \"" + trimmed(dto.get("name")) + "\" already exists");
            }
        }
        Map<String, Object> oldValues = Obj.of("name", existing.get("name"), "category", existing.get("category"),
                "isRequired", existing.get("isRequired"), "minSelect", existing.get("minSelect"),
                "maxSelect", existing.get("maxSelect"), "sortOrder", existing.get("sortOrder"),
                "isActive", existing.get("isActive"));
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", trimmed(dto.get("name")));
        }
        if (dto.containsKey("category")) {
            data.put("category", orNull(trimmed(dto.get("category"))));
        }
        for (String k : List.of("isRequired", "minSelect", "maxSelect", "sortOrder", "isActive")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        data.put("updatedBy", user());
        tx.executeWithoutResult(st -> {
            Map<String, Object> g = db.update(AccompanimentGroup.class, data, "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(new AuditInput("AccompanimentGroup", id, "update", oldValues, Obj.of("name", g.get("name"),
                    "category", g.get("category"), "isRequired", g.get("isRequired"), "minSelect", g.get("minSelect"),
                    "maxSelect", g.get("maxSelect"), "sortOrder", g.get("sortOrder"), "isActive", g.get("isActive")),
                    (String) dto.get("ipAddress"), (String) dto.get("userAgent")));
        });
        return getGroup(id);
    }

    public void deleteGroup(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(AccompanimentGroup.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("AccompanimentGroup " + id + " not found");
        }
        long assignments = jdbc.sql("SELECT count(*) FROM \"MenuItemAccompanimentGroup\" WHERE \"accompanimentGroupId\" = ?"
                + " AND " + LIVE).params(id, orgId).query(Long.class).single();
        if (assignments > 0) {
            throw ApiException.conflict("Cannot delete \"" + existing.get("name") + "\" — assigned to " + assignments
                    + " menu item(s). Deactivate instead.");
        }
        tx.executeWithoutResult(st -> {
            db.updateMany(AccompanimentOption.class, Obj.of("deletedAt", PosDb.raw(NOW), "isActive", false),
                    "\"groupId\" = ? AND " + LIVE, id, orgId);
            Map<String, Object> g = db.update(AccompanimentGroup.class, Obj.of("deletedAt", PosDb.raw(NOW),
                    "isActive", false), "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(AuditInput.of("AccompanimentGroup", id, "delete", Obj.of("name", existing.get("name")),
                    Obj.of("name", g.get("name"), "isActive", false)));
        });
    }

    // ─── Options ───

    public Map<String, Object> createOption(String groupId, Map<String, Object> dto) {
        String orgId = org();
        String name = trimmed(dto.get("name"));
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Option name is required");
        }
        if (dto.containsKey("consumptionQty") && !(PosSql.num(dto.get("consumptionQty")) > 0)) {
            throw ApiException.badRequest("Consumption quantity must be positive");
        }
        if (db.first(AccompanimentGroup.class, "id = ? AND " + LIVE, groupId, orgId) == null) {
            throw ApiException.notFound("AccompanimentGroup " + groupId + " not found");
        }
        if (db.first(AccompanimentOption.class, LIVE + " AND \"groupId\" = ? AND name = ?", orgId, groupId, name) != null) {
            throw ApiException.badRequest("Option \"" + name + "\" already exists in this group");
        }
        Map<String, Object> created = tx.execute(st -> {
            Map<String, Object> o = db.insert(AccompanimentOption.class, Obj.of("organizationId", orgId, "groupId", groupId,
                    "name", name, "priceImpact", dto.get("priceImpact") == null ? 0 : dto.get("priceImpact"),
                    "isDefault", dto.get("isDefault") == null ? false : dto.get("isDefault"),
                    "sortOrder", dto.get("sortOrder") == null ? 0 : dto.get("sortOrder"),
                    "inventoryItemId", dto.get("inventoryItemId"),
                    "consumptionQty", dto.get("consumptionQty") == null ? 1 : dto.get("consumptionQty"),
                    "consumptionUomId", dto.get("consumptionUomId"), "createdBy", user()));
            audit.recordInTx(AuditInput.of("AccompanimentOption", (String) o.get("id"), "create", null,
                    Obj.of("groupId", groupId, "name", o.get("name"), "priceImpact", PosSql.js(o.get("priceImpact")),
                            "isDefault", o.get("isDefault"))));
            return o;
        });
        return mapOption(created);
    }

    public Map<String, Object> updateOption(String id, Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = db.first(AccompanimentOption.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("AccompanimentOption " + id + " not found");
        }
        if (dto.containsKey("name") && (dto.get("name") == null || trimmed(dto.get("name")).isEmpty())) {
            throw ApiException.badRequest("Option name cannot be empty");
        }
        if (dto.containsKey("consumptionQty") && !(PosSql.num(dto.get("consumptionQty")) > 0)) {
            throw ApiException.badRequest("Consumption quantity must be positive");
        }
        // AccompanimentOption has no updatedAt column, so the optimistic check never fires (Nest parity).
        Map<String, Object> oldValues = Obj.of("name", existing.get("name"),
                "priceImpact", PosSql.js(existing.get("priceImpact")), "isDefault", existing.get("isDefault"),
                "sortOrder", existing.get("sortOrder"), "isActive", existing.get("isActive"),
                "inventoryItemId", existing.get("inventoryItemId"));
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            data.put("name", trimmed(dto.get("name")));
        }
        for (String k : List.of("priceImpact", "isDefault", "sortOrder", "isActive", "inventoryItemId", "consumptionQty",
                "consumptionUomId")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        data.put("updatedBy", user());
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> o = db.update(AccompanimentOption.class, data, "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(new AuditInput("AccompanimentOption", id, "update", oldValues, Obj.of("name", o.get("name"),
                    "priceImpact", PosSql.js(o.get("priceImpact")), "isDefault", o.get("isDefault"),
                    "sortOrder", o.get("sortOrder"), "isActive", o.get("isActive"),
                    "inventoryItemId", o.get("inventoryItemId")), (String) dto.get("ipAddress"),
                    (String) dto.get("userAgent")));
            return o;
        });
        return mapOption(updated);
    }

    public void deleteOption(String id) {
        String orgId = org();
        Map<String, Object> existing = db.first(AccompanimentOption.class, "id = ? AND " + LIVE, id, orgId);
        if (existing == null) {
            throw ApiException.notFound("AccompanimentOption " + id + " not found");
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> o = db.update(AccompanimentOption.class, Obj.of("deletedAt", PosDb.raw(NOW),
                    "isActive", false), "id = ? AND " + LIVE, id, orgId);
            audit.recordInTx(AuditInput.of("AccompanimentOption", id, "delete", Obj.of("name", existing.get("name")),
                    Obj.of("name", o.get("name"), "isActive", false)));
        });
    }

    // ─── MenuItem assignment ───

    private List<Map<String, Object>> assignedGroups(String menuItemId, boolean groupNotDeleted) {
        String orgId = org();
        return SqlRows.list(AccompanimentGroup.class, jdbc.sql("SELECT g.* FROM \"MenuItemAccompanimentGroup\" l"
                + " JOIN \"MenuItem\" mi ON mi.id = l.\"menuItemId\" JOIN \"AccompanimentGroup\" g ON g.id ="
                + " l.\"accompanimentGroupId\" WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL"
                + " AND l.\"menuItemId\" = ? AND mi.\"organizationId\" = ? AND g.\"isActive\" = true"
                + (groupNotDeleted ? " AND g.\"deletedAt\" IS NULL" : "") + " ORDER BY l.\"sortOrder\" ASC")
                .params(orgId, menuItemId, orgId));
    }

    public List<Map<String, Object>> listGroupsForMenuItem(String menuItemId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : assignedGroups(menuItemId, false)) {
            out.add(mapGroup(g, options(g.get("id"), " AND \"isActive\" = true")));
        }
        return out;
    }

    public void assignGroupToMenuItem(String menuItemId, Object accompanimentGroupId, Object sortOrderIn) {
        String orgId = org();
        if (db.first(MenuItem.class, "id = ? AND " + LIVE, menuItemId, orgId) == null) {
            throw ApiException.notFound("MenuItem " + menuItemId + " not found");
        }
        if (accompanimentGroupId == null) {
            // Prisma drops an undefined id filter (any group matches), then the upsert rejects it.
            if (db.first(AccompanimentGroup.class, LIVE, orgId) != null) {
                throw new IllegalStateException("Argument `accompanimentGroupId` is missing.");
            }
            throw ApiException.notFound("AccompanimentGroup undefined not found");
        }
        if (db.first(AccompanimentGroup.class, "id = ? AND " + LIVE, accompanimentGroupId, orgId) == null) {
            throw ApiException.notFound("AccompanimentGroup " + accompanimentGroupId + " not found");
        }
        Object sortOrder = sortOrderIn == null ? 0 : sortOrderIn;
        String id = jdbc.sql("INSERT INTO \"MenuItemAccompanimentGroup\" (id, \"organizationId\", \"menuItemId\","
                        + " \"accompanimentGroupId\", \"sortOrder\") VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"menuItemId\","
                        + " \"accompanimentGroupId\") DO UPDATE SET \"sortOrder\" = EXCLUDED.\"sortOrder\" RETURNING id")
                .params(UUID.randomUUID().toString(), orgId, menuItemId, accompanimentGroupId, sortOrder)
                .query(String.class).single();
        audit.record(AuditInput.of("MenuItemAccompanimentGroup", id, "assign", null,
                Obj.of("menuItemId", menuItemId, "accompanimentGroupId", accompanimentGroupId, "sortOrder", sortOrder)));
    }

    public void unassignGroupFromMenuItem(String menuItemId, String accompanimentGroupId) {
        String orgId = org();
        String id = jdbc.sql("SELECT l.id FROM \"MenuItemAccompanimentGroup\" l JOIN \"MenuItem\" mi ON mi.id ="
                        + " l.\"menuItemId\" WHERE l.\"menuItemId\" = ? AND l.\"accompanimentGroupId\" = ?"
                        + " AND l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL AND mi.\"organizationId\" = ? LIMIT 1")
                .params(menuItemId, accompanimentGroupId, orgId, orgId).query(String.class).optional().orElse(null);
        if (id == null) {
            return;
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("UPDATE \"MenuItemAccompanimentGroup\" SET \"deletedAt\" = " + NOW + " WHERE id = ?").param(id).update();
            audit.recordInTx(AuditInput.of("MenuItemAccompanimentGroup", id, "unassign",
                    Obj.of("menuItemId", menuItemId, "accompanimentGroupId", accompanimentGroupId), null));
        });
    }

    /** Validate a line's accompaniment picks; names + total price impact. */
    public Selection validateSelections(String menuItemId, List<String> selectedOptionIds, boolean bypassRequired) {
        List<Map<String, Object>> groups = assignedGroups(menuItemId, true);
        if (groups.isEmpty()) {
            return new Selection(List.of(), 0);
        }
        Set<String> selected = new HashSet<>(selectedOptionIds == null ? List.of() : selectedOptionIds);
        double total = 0;
        List<String> names = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            List<Map<String, Object>> inGroup = new ArrayList<>();
            for (Map<String, Object> o : db.list(AccompanimentOption.class, "\"groupId\" = ? AND \"isActive\" = true"
                    + " AND \"deletedAt\" IS NULL", g.get("id"))) {
                if (selected.contains((String) o.get("id"))) {
                    inGroup.add(o);
                }
            }
            int count = inGroup.size();
            int minSelect = PosSql.intOf(g.get("minSelect"));
            int effectiveMin = Math.max(minSelect, Boolean.TRUE.equals(g.get("isRequired")) ? 1 : 0);
            if (!bypassRequired && count < effectiveMin) {
                throw ApiException.badRequest("\"" + g.get("name") + "\" requires at least " + effectiveMin
                        + " selection(s). " + count + " selected.");
            }
            int maxSelect = PosSql.intOf(g.get("maxSelect"));
            if (maxSelect > 0 && count > maxSelect) {
                throw ApiException.badRequest("\"" + g.get("name") + "\" allows at most " + maxSelect + " selection(s). "
                        + count + " selected.");
            }
            for (Map<String, Object> o : inGroup) {
                total += PosSql.num(o.get("priceImpact"));
                names.add((String) o.get("name"));
            }
        }
        return new Selection(names, total);
    }

    /**
     * Nest queries {@code order: { orderType: 'pos', reference: ... }} — neither the enum value nor the relation
     * exists, so Prisma rejects the query (500). Kept on purpose until parity is signed off.
     */
    public List<Map<String, Object>> accompanimentSalesReport(String from, String to) {
        throw new IllegalStateException("Invalid `prisma.orderItem.findMany()` invocation: Unknown argument `reference`.");
    }
}
