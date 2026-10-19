package com.inventoryerp.kernel.reports;

import com.inventoryerp.kernel.common.ContractCatalogs;
import com.inventoryerp.kernel.common.ContractCatalogs.Report;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Per-role report visibility (port of {@code report-access.service.ts}). One org-level Setting
 * ({@code reports.visibility}) holds {@code {version: 1, roles: {roleId: [reportId]}}}; a role without
 * an entry sees every report its permissions allow. Configuration only ever narrows RBAC.
 */
@Service
public class ReportAccessService {

    public static final String REPORT_VISIBILITY_SETTING_KEY = "reports.visibility";

    /** Minimal role view. */
    public record RoleView(String id, String name, boolean isSystem, List<String> permissions) {}

    /** Data access, swappable in unit tests. */
    public interface Store {
        Object readConfig();

        void writeConfig(Map<String, Object> config);

        List<RoleView> roles();

        RoleView role(String id);

        /** Roles of an active user; {@code null} when the user is inactive or unknown. */
        List<RoleView> activeUserRoles(String userId);
    }

    private final Store store;

    @org.springframework.beans.factory.annotation.Autowired
    public ReportAccessService(SettingsService settings, JdbcClient jdbc) {
        this(new JdbcStore(settings, jdbc));
    }

    public ReportAccessService(Store store) {
        this.store = store;
    }

    public Map<String, Object> catalog() {
        return Map.of("reports", ContractCatalogs.reportCatalog());
    }

    public static List<String> defaultReportsForPermissions(List<String> permissions) {
        Set<String> held = new HashSet<>(permissions == null ? List.of() : permissions);
        return ContractCatalogs.reportCatalog().stream().filter(r -> held.contains(r.permission())).map(Report::id).toList();
    }

    public static boolean isReportId(String id) {
        return ContractCatalogs.reportCatalog().stream().anyMatch(r -> r.id().equals(id));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> readConfig() {
        Object value = store.readConfig();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m && m.get("roles") instanceof Map<?, ?> r) {
            r.forEach((k, v) -> roles.put((String) k, v instanceof List<?> l ? (List<String>) l : List.of()));
        }
        return roles;
    }

    private record Resolved(List<String> reports, boolean configured) {}

    private static Resolved resolve(RoleView role, List<String> configured) {
        Set<String> allowed = new LinkedHashSet<>(defaultReportsForPermissions(role.permissions()));
        if (configured == null) {
            return new Resolved(new ArrayList<>(allowed), false);
        }
        return new Resolved(configured.stream().filter(allowed::contains).toList(), true);
    }

    public Map<String, Object> listRoleVisibility() {
        Map<String, List<String>> config = readConfig();
        List<Map<String, Object>> roles = new ArrayList<>();
        for (RoleView role : store.roles()) {
            Resolved r = resolve(role, config.get(role.id()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", role.id());
            row.put("name", role.name());
            row.put("isSystem", role.isSystem());
            row.put("reports", r.reports());
            row.put("configured", r.configured());
            row.put("available", defaultReportsForPermissions(role.permissions()));
            roles.add(row);
        }
        return Map.of("roles", roles);
    }

    public Map<String, Object> setRoleVisibility(String roleId, List<String> reports) {
        RoleView role = store.role(roleId);
        if (role == null) {
            throw ApiException.notFound("Role " + roleId + " not found");
        }
        Map<String, List<String>> config = readConfig();
        if (reports == null) {
            config.remove(roleId);
        } else {
            List<String> unknown = reports.stream().filter(id -> !isReportId(id)).toList();
            if (!unknown.isEmpty()) {
                throw ApiException.badRequest("Unknown report id(s): " + String.join(", ", unknown));
            }
            config.put(roleId, List.copyOf(new LinkedHashSet<>(reports)));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1);
        value.put("roles", config);
        store.writeConfig(value);
        Resolved r = resolve(role, config.get(roleId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", role.id());
        out.put("name", role.name());
        out.put("reports", r.reports());
        out.put("configured", r.configured());
        out.put("available", defaultReportsForPermissions(role.permissions()));
        return out;
    }

    /** Union over the user's roles. */
    public List<String> reportsForUser(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<RoleView> roles = store.activeUserRoles(userId);
        if (roles == null) {
            return List.of();
        }
        Map<String, List<String>> config = readConfig();
        Set<String> visible = new LinkedHashSet<>();
        for (RoleView role : roles) {
            visible.addAll(resolve(role, config.get(role.id())).reports());
        }
        return new ArrayList<>(visible);
    }

    /** Production store: Setting via SettingsService, roles via SQL in the current tenant. */
    static final class JdbcStore implements Store {
        private final SettingsService settings;
        private final JdbcClient jdbc;

        JdbcStore(SettingsService settings, JdbcClient jdbc) {
            this.settings = settings;
            this.jdbc = jdbc;
        }

        @Override
        public Object readConfig() {
            return settings.getValue(REPORT_VISIBILITY_SETTING_KEY);
        }

        @Override
        public void writeConfig(Map<String, Object> config) {
            settings.set(REPORT_VISIBILITY_SETTING_KEY, config, null, null);
        }

        @Override
        public List<RoleView> roles() {
            return jdbc.sql("""
                            SELECT id, name, "isSystem", permissions FROM "Role" WHERE "organizationId" = ? AND "deletedAt" IS NULL
                            ORDER BY "isSystem" DESC, name ASC""")
                    .param(TenantContext.organizationId())
                    .query((rs, n) -> new RoleView(rs.getString("id"), rs.getString("name"), rs.getBoolean("isSystem"),
                            com.inventoryerp.kernel.auth.UserAccounts.array(rs, "permissions")))
                    .list();
        }

        @Override
        public RoleView role(String id) {
            return jdbc.sql("""
                            SELECT id, name, "isSystem", permissions FROM "Role" WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                    .params(id, TenantContext.organizationId())
                    .query((rs, n) -> new RoleView(rs.getString("id"), rs.getString("name"), rs.getBoolean("isSystem"),
                            com.inventoryerp.kernel.auth.UserAccounts.array(rs, "permissions")))
                    .optional()
                    .orElse(null);
        }

        @Override
        public List<RoleView> activeUserRoles(String userId) {
            String org = TenantContext.organizationId();
            boolean active = jdbc.sql("""
                            SELECT count(*) FROM "User" WHERE id = ? AND "organizationId" = ? AND "isActive" = true AND "deletedAt" IS NULL""")
                    .params(userId, org).query(Long.class).single() > 0;
            if (!active) {
                return null;
            }
            return jdbc.sql("""
                            SELECT r.id, r.name, r."isSystem", r.permissions FROM "Role" r JOIN "_UserRoles" ur ON ur."A" = r.id
                            WHERE ur."B" = ? ORDER BY r."createdAt", r.id""")
                    .param(userId)
                    .query((rs, n) -> new RoleView(rs.getString("id"), rs.getString("name"), rs.getBoolean("isSystem"),
                            com.inventoryerp.kernel.auth.UserAccounts.array(rs, "permissions")))
                    .list();
        }
    }
}
