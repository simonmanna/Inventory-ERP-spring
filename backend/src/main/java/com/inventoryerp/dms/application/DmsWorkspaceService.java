package com.inventoryerp.dms.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspaces, folders, workspace access rules and tags. System workspaces (one per ERP area) are created on first use
 * per organization. Access to a workspace: the workspace's {@code requiredPermission} (if any) and, when the workspace
 * has access rules, a rule for the user or one of the user's roles; {@code dms:workspace:manage} sees everything.
 */
@Service
public class DmsWorkspaceService {

    /** Access levels, ordered. */
    public enum Level { NONE, READ, WRITE, MANAGE }

    record SystemWorkspace(String code, String name, String icon, String color, String permission, boolean approval,
            int order) {}

    static final List<SystemWorkspace> SYSTEM = List.of(
            new SystemWorkspace("general", "General", "folder", "#64748b", null, false, 0),
            new SystemWorkspace("crm", "CRM", "users", "#0ea5e9", Permissions.CRM__DEAL_READ, false, 10),
            new SystemWorkspace("sales", "Sales", "shopping-cart", "#22c55e", Permissions.SALES__ORDER_READ, false, 20),
            new SystemWorkspace("purchase", "Purchases", "truck", "#f97316",
                    Permissions.PROCUREMENT__PURCHASE_ORDER__READ, false, 30),
            new SystemWorkspace("expenses", "Expenses", "receipt", "#eab308", Permissions.EXPENSE__READ, false, 40),
            new SystemWorkspace("finance", "Finance", "landmark", "#8b5cf6", Permissions.INVOICE__READ, false, 50),
            new SystemWorkspace("hr", "HR", "id-card", "#ec4899", Permissions.HR__READ, false, 60),
            new SystemWorkspace("helpdesk", "Helpdesk", "life-buoy", "#14b8a6", Permissions.HELPDESK__TICKET_READ,
                    false, 70),
            new SystemWorkspace("correspondence", "Correspondence", "mail", "#6366f1",
                    Permissions.HELPDESK__TICKET_READ, false, 80),
            new SystemWorkspace("projects", "Projects", "kanban", "#0d9488", Permissions.PROJECT_TASK__READ, false,
                    85),
            new SystemWorkspace("shared_uploads", "Shared uploads", "inbox", "#ef4444", null, true, 90));

    static final Set<String> LEVELS = Set.of("read", "write", "manage");

    private final JdbcClient jdbc;

    public DmsWorkspaceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Create the system workspaces of {@code organizationId} when missing (idempotent). */
    @Transactional
    public void ensureSystemWorkspaces(String organizationId) {
        long have = jdbc.sql("SELECT count(*) FROM \"DmsWorkspace\" WHERE \"organizationId\" = ? AND \"isSystem\"")
                .param(organizationId).query(Long.class).single();
        if (have >= SYSTEM.size()) {
            return;
        }
        for (SystemWorkspace w : SYSTEM) {
            jdbc.sql("""
                    INSERT INTO "DmsWorkspace" (id, "organizationId", code, name, icon, color, "requiredPermission",
                      "requiresApproval", "isSystem", "sortOrder")
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?) ON CONFLICT ("organizationId", code) DO NOTHING""")
                    .params(UUID.randomUUID().toString(), organizationId, w.code(), w.name(), w.icon(), w.color(),
                            w.permission(), w.approval(), w.order())
                    .update();
        }
    }

    /** Workspace row of the current org by id (404). */
    public Map<String, Object> row(String id) {
        Map<String, Object> w = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsWorkspace\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (w == null) {
            throw ApiException.notFound("Workspace not found");
        }
        return w;
    }

    /** Workspace id of a system/custom workspace code in the current org (creating system ones on demand). */
    public String idOfCode(String code) {
        ensureSystemWorkspaces(org());
        return jdbc.sql("SELECT id FROM \"DmsWorkspace\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), code).query(String.class).optional()
                .orElseGet(() -> idOfCode("general"));
    }

    private List<String> myRoles() {
        String user = TenantContext.userId();
        if (user == null) {
            return List.of();
        }
        return jdbc.sql("SELECT \"A\" FROM \"_UserRoles\" WHERE \"B\" = ?").param(user).query(String.class).list();
    }

    /** The caller's access level on every workspace of the org (id → level). */
    public Map<String, Level> levels() {
        ensureSystemWorkspaces(org());
        Map<String, Level> out = new HashMap<>();
        boolean manage = TenantContext.has(Permissions.DMS__WORKSPACE_MANAGE);
        boolean write = TenantContext.has(Permissions.DMS__DOCUMENT_WRITE);
        boolean read = TenantContext.has(Permissions.DMS__DOCUMENT_READ);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"requiredPermission\" FROM \"DmsWorkspace\""
                + " WHERE \"organizationId\" = ?").param(org()));
        Map<String, List<Map<String, Object>>> rules = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"workspaceId\", \"userId\", \"roleId\", level FROM"
                + " \"DmsAccessRule\" WHERE \"organizationId\" = ?").param(org()))) {
            rules.computeIfAbsent((String) r.get("workspaceId"), k -> new ArrayList<>()).add(r);
        }
        String user = TenantContext.userId();
        List<String> roles = rules.isEmpty() ? List.of() : myRoles();
        for (Map<String, Object> w : rows) {
            String id = (String) w.get("id");
            if (manage) {
                out.put(id, Level.MANAGE);
                continue;
            }
            String req = (String) w.get("requiredPermission");
            if (!read || (req != null && !TenantContext.has(req))) {
                out.put(id, Level.NONE);
                continue;
            }
            List<Map<String, Object>> wr = rules.get(id);
            Level level;
            if (wr == null || wr.isEmpty()) {
                level = write ? Level.WRITE : Level.READ;
            } else {
                level = Level.NONE;
                for (Map<String, Object> r : wr) {
                    boolean mine = (r.get("userId") != null && r.get("userId").equals(user))
                            || (r.get("roleId") != null && roles.contains(r.get("roleId")));
                    if (mine) {
                        Level l = Level.valueOf(((String) r.get("level")).toUpperCase());
                        if (l.ordinal() > level.ordinal()) {
                            level = l;
                        }
                    }
                }
                if (!write && level.ordinal() > Level.READ.ordinal()) {
                    level = Level.READ;
                }
            }
            out.put(id, level);
        }
        return out;
    }

    public Level level(String workspaceId) {
        return levels().getOrDefault(workspaceId, Level.NONE);
    }

    /** 403 unless the caller has at least {@code min} on the workspace. */
    public void require(String workspaceId, Level min) {
        if (level(workspaceId).ordinal() < min.ordinal()) {
            throw ApiException.forbidden("You do not have " + min.name().toLowerCase() + " access to this workspace");
        }
    }

    // ---- workspaces ----

    @Transactional
    public List<Map<String, Object>> list() {
        Map<String, Level> levels = levels();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : SqlRows.list(jdbc.sql("""
                SELECT w.*, (SELECT count(*) FROM "DmsDocument" d WHERE d."workspaceId" = w.id
                  AND d."deletedAt" IS NULL) AS "documentCount"
                FROM "DmsWorkspace" w WHERE w."organizationId" = ? AND w."archivedAt" IS NULL
                ORDER BY w."sortOrder", w.name""").param(org()))) {
            Level l = levels.getOrDefault((String) w.get("id"), Level.NONE);
            if (l == Level.NONE) {
                continue;
            }
            w.put("access", l.name().toLowerCase());
            out.add(w);
        }
        return out;
    }

    public record WorkspaceInput(String code, String name, String description, String parentId, String icon,
            String color, String requiredPermission, Boolean requiresApproval, Integer sortOrder) {}

    @Transactional
    public Map<String, Object> create(WorkspaceInput in) {
        String code = in.code() == null || in.code().isBlank()
                ? in.name().trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "")
                : in.code().trim().toLowerCase();
        if (code.isEmpty() || !code.matches("[a-z0-9_]{1,40}")) {
            throw ApiException.badRequest("code must be 1-40 lowercase letters, digits or underscores");
        }
        if (in.parentId() != null) {
            row(in.parentId());
        }
        validatePermission(in.requiredPermission());
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("""
                    INSERT INTO "DmsWorkspace" (id, "organizationId", code, name, description, "parentId", icon, color,
                      "requiredPermission", "requiresApproval", "sortOrder", "createdById")
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, org(), code, in.name().trim(), in.description(), in.parentId(), in.icon(), in.color(),
                            blank(in.requiredPermission()), Boolean.TRUE.equals(in.requiresApproval()),
                            in.sortOrder() == null ? 100 : in.sortOrder(), TenantContext.userId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A workspace with code \"" + code + "\" already exists");
        }
        return row(id);
    }

    @Transactional
    public Map<String, Object> update(String id, WorkspaceInput in) {
        Map<String, Object> w = row(id);
        if (in.requiredPermission() != null) {
            validatePermission(in.requiredPermission());
        }
        if (in.parentId() != null && !in.parentId().isEmpty()) {
            if (in.parentId().equals(id)) {
                throw ApiException.badRequest("A workspace cannot be its own parent");
            }
            row(in.parentId());
        }
        jdbc.sql("""
                UPDATE "DmsWorkspace" SET name = coalesce(?, name), description = coalesce(?, description),
                  icon = coalesce(?, icon), color = coalesce(?, color),
                  "requiredPermission" = CASE WHEN ? THEN ? ELSE "requiredPermission" END,
                  "requiresApproval" = coalesce(?, "requiresApproval"), "sortOrder" = coalesce(?, "sortOrder"),
                  "parentId" = CASE WHEN ? THEN nullif(?, '') ELSE "parentId" END, "updatedAt" = now()
                WHERE id = ?""")
                .params(trim(in.name()), in.description(), in.icon(), in.color(), in.requiredPermission() != null,
                        blank(in.requiredPermission()), in.requiresApproval(), in.sortOrder(), in.parentId() != null,
                        in.parentId(), w.get("id"))
                .update();
        return row(id);
    }

    @Transactional
    public Map<String, Object> archive(String id) {
        Map<String, Object> w = row(id);
        if (Boolean.TRUE.equals(w.get("isSystem"))) {
            throw ApiException.badRequest("System workspaces cannot be archived");
        }
        long docs = jdbc.sql("SELECT count(*) FROM \"DmsDocument\" WHERE \"workspaceId\" = ? AND \"deletedAt\" IS NULL")
                .param(id).query(Long.class).single();
        if (docs > 0) {
            throw ApiException.badRequest("Move or delete the " + docs + " document(s) of this workspace first");
        }
        jdbc.sql("UPDATE \"DmsWorkspace\" SET \"archivedAt\" = now(), \"updatedAt\" = now() WHERE id = ?").param(id)
                .update();
        return row(id);
    }

    private static void validatePermission(String p) {
        if (p != null && !p.isBlank() && !Permissions.ALL.contains(p)) {
            throw ApiException.badRequest("Unknown permission: " + p);
        }
    }

    // ---- access rules ----

    @Transactional
    public List<Map<String, Object>> accessRules(String workspaceId) {
        row(workspaceId);
        return SqlRows.list(jdbc.sql("""
                SELECT a.*, r.name AS "roleName", trim(coalesce(u."firstName", '') || ' ' || coalesce(u."lastName", ''))
                  AS "userName", u.email AS "userEmail"
                FROM "DmsAccessRule" a LEFT JOIN "Role" r ON r.id = a."roleId" LEFT JOIN "User" u ON u.id = a."userId"
                WHERE a."workspaceId" = ? ORDER BY a."createdAt\"""").param(workspaceId));
    }

    public record AccessRuleInput(String userId, String roleId, String level) {}

    /** Replace the workspace's rules (empty list = open to every DMS user who passes the required permission). */
    @Transactional
    public List<Map<String, Object>> replaceAccessRules(String workspaceId, List<AccessRuleInput> rules) {
        row(workspaceId);
        for (AccessRuleInput r : rules) {
            if ((r.userId() == null) == (r.roleId() == null)) {
                throw ApiException.badRequest("Each rule needs exactly one of userId or roleId");
            }
            if (r.level() == null || !LEVELS.contains(r.level())) {
                throw ApiException.badRequest("level must be one of: manage, read, write");
            }
            if (r.userId() != null && jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(r.userId(), org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("User not found: " + r.userId());
            }
            if (r.roleId() != null && jdbc.sql("SELECT count(*) FROM \"Role\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(r.roleId(), org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Role not found: " + r.roleId());
            }
        }
        jdbc.sql("DELETE FROM \"DmsAccessRule\" WHERE \"workspaceId\" = ?").param(workspaceId).update();
        for (AccessRuleInput r : rules) {
            jdbc.sql("INSERT INTO \"DmsAccessRule\" (id, \"organizationId\", \"workspaceId\", \"userId\", \"roleId\", level)"
                    + " VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), workspaceId, r.userId(), r.roleId(), r.level())
                    .update();
        }
        return accessRules(workspaceId);
    }

    // ---- folders ----

    public Map<String, Object> folder(String id) {
        Map<String, Object> f = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsFolder\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (f == null) {
            throw ApiException.notFound("Folder not found");
        }
        return f;
    }

    @Transactional
    public List<Map<String, Object>> folders(String workspaceId) {
        row(workspaceId);
        require(workspaceId, Level.READ);
        return SqlRows.list(jdbc.sql("""
                SELECT f.*, (SELECT count(*) FROM "DmsDocument" d WHERE d."folderId" = f.id AND d."deletedAt" IS NULL)
                  AS "documentCount"
                FROM "DmsFolder" f WHERE f."workspaceId" = ? ORDER BY f."sortOrder", lower(f.name)""")
                .param(workspaceId));
    }

    @Transactional
    public Map<String, Object> createFolder(String workspaceId, String parentId, String name) {
        row(workspaceId);
        require(workspaceId, Level.WRITE);
        if (parentId != null) {
            Map<String, Object> p = folder(parentId);
            if (!workspaceId.equals(p.get("workspaceId"))) {
                throw ApiException.badRequest("Parent folder belongs to another workspace");
            }
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"DmsFolder\" (id, \"organizationId\", \"workspaceId\", \"parentId\", name,"
                    + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id, org(), workspaceId, parentId, name.trim(), TenantContext.userId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A folder named \"" + name.trim() + "\" already exists here");
        }
        return folder(id);
    }

    /** Find or create a folder path ({@code incoming/2026}) under the workspace root; returns the leaf id. */
    @Transactional
    public String ensureFolderPath(String workspaceId, String path) {
        String parent = null;
        for (String seg : path.split("/")) {
            if (seg.isBlank()) {
                continue;
            }
            String p = parent;
            String found = jdbc.sql("SELECT id FROM \"DmsFolder\" WHERE \"workspaceId\" = ? AND coalesce(\"parentId\", '')"
                    + " = ? AND lower(name) = lower(?)").params(workspaceId, p == null ? "" : p, seg.trim())
                    .query(String.class).optional().orElse(null);
            if (found == null) {
                found = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"DmsFolder\" (id, \"organizationId\", \"workspaceId\", \"parentId\", name)"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                        .params(found, org(), workspaceId, p, seg.trim()).update();
                found = jdbc.sql("SELECT id FROM \"DmsFolder\" WHERE \"workspaceId\" = ? AND coalesce(\"parentId\", '')"
                        + " = ? AND lower(name) = lower(?)").params(workspaceId, p == null ? "" : p, seg.trim())
                        .query(String.class).single();
            }
            parent = found;
        }
        return parent;
    }

    @Transactional
    public Map<String, Object> updateFolder(String id, String name, String parentId, boolean moveParent) {
        Map<String, Object> f = folder(id);
        String ws = (String) f.get("workspaceId");
        require(ws, Level.WRITE);
        if (moveParent && parentId != null) {
            if (parentId.equals(id)) {
                throw ApiException.badRequest("A folder cannot be its own parent");
            }
            Map<String, Object> p = folder(parentId);
            if (!ws.equals(p.get("workspaceId"))) {
                throw ApiException.badRequest("Parent folder belongs to another workspace");
            }
            // no cycles: the new parent must not be a descendant of this folder
            String cursor = parentId;
            while (cursor != null) {
                if (cursor.equals(id)) {
                    throw ApiException.badRequest("A folder cannot be moved into its own subfolder");
                }
                cursor = (String) folder(cursor).get("parentId");
            }
        }
        try {
            jdbc.sql("UPDATE \"DmsFolder\" SET name = coalesce(?, name), \"parentId\" = CASE WHEN ? THEN ? ELSE"
                    + " \"parentId\" END, \"updatedAt\" = now() WHERE id = ?")
                    .params(trim(name), moveParent, parentId, id).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A folder with that name already exists here");
        }
        return folder(id);
    }

    @Transactional
    public void deleteFolder(String id) {
        Map<String, Object> f = folder(id);
        require((String) f.get("workspaceId"), Level.WRITE);
        long children = jdbc.sql("SELECT count(*) FROM \"DmsFolder\" WHERE \"parentId\" = ?").param(id)
                .query(Long.class).single();
        long docs = jdbc.sql("SELECT count(*) FROM \"DmsDocument\" WHERE \"folderId\" = ? AND \"deletedAt\" IS NULL")
                .param(id).query(Long.class).single();
        if (children > 0 || docs > 0) {
            throw ApiException.badRequest("Folder is not empty");
        }
        jdbc.sql("UPDATE \"DmsDocument\" SET \"folderId\" = NULL WHERE \"folderId\" = ?").param(id).update();
        jdbc.sql("DELETE FROM \"DmsFolder\" WHERE id = ?").param(id).update();
    }

    // ---- tags ----

    @Transactional
    public List<Map<String, Object>> tags() {
        return SqlRows.list(jdbc.sql("""
                SELECT t.*, c.name AS "categoryName", (SELECT count(*) FROM "DmsDocumentTag" dt WHERE dt."tagId" = t.id)
                  AS "documentCount"
                FROM "DmsTag" t LEFT JOIN "DmsTagCategory" c ON c.id = t."categoryId"
                WHERE t."organizationId" = ? ORDER BY c."sortOrder" NULLS LAST, c.name NULLS LAST, t.name""")
                .param(org()));
    }

    @Transactional
    public List<Map<String, Object>> tagCategories() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"DmsTagCategory\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"sortOrder\", name").param(org()));
    }

    @Transactional
    public Map<String, Object> createTagCategory(String name, Integer sortOrder) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"DmsTagCategory\" (id, \"organizationId\", name, \"sortOrder\") VALUES (?, ?, ?, ?)")
                    .params(id, org(), name.trim(), sortOrder == null ? 0 : sortOrder).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Tag category \"" + name.trim() + "\" already exists");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"DmsTagCategory\" WHERE id = ?").param(id));
    }

    @Transactional
    public void deleteTagCategory(String id) {
        if (jdbc.sql("DELETE FROM \"DmsTagCategory\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update() == 0) {
            throw ApiException.notFound("Tag category not found");
        }
    }

    @Transactional
    public Map<String, Object> createTag(String name, String categoryId, String color) {
        if (categoryId != null && jdbc.sql("SELECT count(*) FROM \"DmsTagCategory\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(categoryId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Tag category not found");
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"DmsTag\" (id, \"organizationId\", \"categoryId\", name, color) VALUES (?, ?, ?, ?, ?)")
                    .params(id, org(), categoryId, name.trim(), color).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Tag \"" + name.trim() + "\" already exists");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"DmsTag\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> updateTag(String id, String name, String categoryId, String color) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsTag\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Tag not found");
        }
        try {
            jdbc.sql("UPDATE \"DmsTag\" SET name = coalesce(?, name), \"categoryId\" = coalesce(?, \"categoryId\"),"
                    + " color = coalesce(?, color) WHERE id = ?").params(trim(name), categoryId, color, id).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Tag \"" + name.trim() + "\" already exists");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"DmsTag\" WHERE id = ?").param(id));
    }

    @Transactional
    public void deleteTag(String id) {
        if (jdbc.sql("DELETE FROM \"DmsTag\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update() == 0) {
            throw ApiException.notFound("Tag not found");
        }
    }

    /** Tag ids for names (created when missing). */
    @Transactional
    public List<String> tagIdsForNames(List<String> names) {
        List<String> ids = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            jdbc.sql("INSERT INTO \"DmsTag\" (id, \"organizationId\", name) VALUES (?, ?, ?) ON CONFLICT"
                    + " (\"organizationId\", name) DO NOTHING").params(UUID.randomUUID().toString(), org(), n.trim())
                    .update();
            ids.add(jdbc.sql("SELECT id FROM \"DmsTag\" WHERE \"organizationId\" = ? AND name = ?")
                    .params(org(), n.trim()).query(String.class).single());
        }
        return ids;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
