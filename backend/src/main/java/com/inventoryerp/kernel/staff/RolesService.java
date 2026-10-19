package com.inventoryerp.kernel.staff;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.domain.Role;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC role management (port of {@code roles.service.ts}), scoped to the current organization. System
 * roles are seeded but editable/deletable like any other role (Nest behaviour). Permission keys are
 * validated against the shared catalog.
 */
@Service
public class RolesService {

    private static final Set<String> PERMISSION_SET = new HashSet<>(Permissions.ALL);

    /** Create body. */
    public record CreateRole(String name, String description, List<String> permissions, Boolean isSystem) {}

    /** Update body (all optional). */
    public record UpdateRole(String name, String description, List<String> permissions) {}

    private final EntityManager em;
    private final AuditService audit;
    private final EventBus events;
    private final PermissionService permissionCache;

    public RolesService(EntityManager em, AuditService audit, EventBus events, PermissionService permissionCache) {
        this.em = em;
        this.audit = audit;
        this.events = events;
        this.permissionCache = permissionCache;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery("select r from Role r where r.organizationId = :org"
                        + " order by r.isSystem desc, r.name asc", Role.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Role row = find(id);
        if (row == null) {
            throw ApiException.notFound("Role " + id + " not found");
        }
        Map<String, Object> out = EntityJson.of(row);
        out.put("_count", Map.of("users", userCount(id)));
        return out;
    }

    @Transactional
    public Map<String, Object> create(CreateRole dto) {
        validatePermissions(dto.permissions());
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            throw ApiException.forbidden("Tenant context required");
        }
        boolean exists = !em.createQuery("select r.id from Role r where r.organizationId = :org and r.name = :name", String.class)
                .setParameter("org", orgId)
                .setParameter("name", dto.name())
                .getResultList()
                .isEmpty();
        if (exists) {
            throw ApiException.conflict("Role \"" + dto.name() + "\" already exists");
        }
        Role role = new Role();
        role.setOrganizationId(orgId);
        role.setName(dto.name());
        role.setDescription(dto.description());
        role.setPermissions(new ArrayList<>(dto.permissions()));
        role.setIsSystem(dto.isSystem() != null && dto.isSystem());
        em.persist(role);
        em.flush();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", role.getId());
        values.put("name", role.getName());
        values.put("permissions", role.getPermissions());
        audit.recordInTx(AuditInput.of("Role", role.getId(), "create", null, values));
        Map<String, Object> out = EntityJson.of(role);
        events.publish(DomainEvents.ROLE_CREATED, Map.of("id", role.getId(), "organizationId", orgId));
        permissionCache.evictAll();
        return out;
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateRole dto) {
        Role current = find(id);
        if (current == null) {
            throw ApiException.notFound("Role " + id + " not found");
        }
        if (dto.permissions() != null) {
            validatePermissions(dto.permissions());
        }
        if (dto.name() != null && !dto.name().equals(current.getName())) {
            boolean collision = !em.createQuery("select r.id from Role r where r.organizationId = :org and r.name = :name"
                            + " and r.id <> :id", String.class)
                    .setParameter("org", current.getOrganizationId())
                    .setParameter("name", dto.name())
                    .setParameter("id", id)
                    .getResultList()
                    .isEmpty();
            if (collision) {
                throw ApiException.conflict("Role \"" + dto.name() + "\" already exists");
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", current.getName());
        before.put("permissions", current.getPermissions() == null ? null : List.copyOf(current.getPermissions()));
        before.put("description", current.getDescription());
        // Prisma `x ?? undefined`: null/absent leaves the column unchanged.
        if (dto.name() != null) {
            current.setName(dto.name());
        }
        if (dto.description() != null) {
            current.setDescription(dto.description());
        }
        if (dto.permissions() != null) {
            current.setPermissions(new ArrayList<>(dto.permissions()));
        }
        em.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", current.getName());
        after.put("permissions", current.getPermissions());
        after.put("description", current.getDescription());
        audit.recordInTx(AuditInput.of("Role", id, "update", before, after));
        events.publish(DomainEvents.ROLE_UPDATED, Map.of("id", id, "organizationId", current.getOrganizationId()));
        permissionCache.evictAll();
        return EntityJson.of(current);
    }

    @Transactional
    public void remove(String id) {
        Role role = find(id);
        if (role == null) {
            throw ApiException.notFound("Role " + id + " not found");
        }
        long users = userCount(id);
        if (users > 0) {
            throw ApiException.conflict("Role \"" + role.getName() + "\" is assigned to " + users
                    + " user(s); reassign them first");
        }
        em.remove(role);
        em.flush();
        audit.recordInTx(AuditInput.of("Role", id, "delete", Map.of("id", id, "name", role.getName()), null));
        events.publish(DomainEvents.ROLE_DELETED, Map.of("id", id, "organizationId", role.getOrganizationId()));
        permissionCache.evictAll();
    }

    private Role find(String id) {
        List<Role> rows = em.createQuery("select r from Role r where r.id = :id and r.organizationId = :org", Role.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long userCount(String roleId) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM \"_UserRoles\" WHERE \"A\" = ?1")
                .setParameter(1, roleId)
                .getSingleResult()).longValue();
    }

    private static void validatePermissions(List<String> perms) {
        List<String> unknown = perms.stream().filter(p -> !PERMISSION_SET.contains(p)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown permission(s): " + String.join(", ", unknown)
                    + ". Use keys from the permission catalog.");
        }
    }
}
