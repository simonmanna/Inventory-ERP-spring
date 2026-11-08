package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Users and roles as the approval rules and the approval hub see them (ADR-S061): who holds a role, display names,
 * and the pick lists of the rules builder. Every query is scoped to the current organization and to active,
 * undeleted users.
 */
@Component
public class ApprovalAssignees {

    /** A user or role as shown in pickers and stage cards. */
    public record Person(String id, String name, String email) {}

    private final JdbcClient jdbc;

    public ApprovalAssignees(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active users holding any of the roles. */
    public Set<String> membersOf(Collection<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT u.id FROM "_UserRoles" ur JOIN "User" u ON u.id = ur."B"
                        JOIN "Role" r ON r.id = ur."A" AND r."deletedAt" IS NULL
                        WHERE ur."A" IN (:roles) AND u."organizationId" = :org AND u."isActive" = true
                          AND u."deletedAt" IS NULL""")
                .param("roles", roleIds)
                .param("org", TenantContext.organizationId())
                .query(String.class)
                .list());
    }

    /** Only the ids that are active users of the organization. */
    public Set<String> activeUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT id FROM "User" WHERE id IN (:ids) AND "organizationId" = :org AND "isActive" = true
                          AND "deletedAt" IS NULL""")
                .param("ids", userIds)
                .param("org", TenantContext.organizationId())
                .query(String.class)
                .list());
    }

    /** Whether the user holds any of the roles. */
    public boolean holdsAny(String userId, Collection<String> roleIds) {
        return roleIds != null && !roleIds.isEmpty() && membersOf(roleIds).contains(userId);
    }

    /** Display names by user id (deleted users keep their name so history stays readable). */
    public Map<String, Person> users(Collection<String> userIds) {
        Map<String, Person> out = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT id, "firstName", "lastName", email FROM "User"
                        WHERE id IN (:ids) AND "organizationId" = :org""")
                .param("ids", new LinkedHashSet<>(userIds))
                .param("org", TenantContext.organizationId())
                .query((rs, n) -> new Person(rs.getString("id"),
                        fullName(rs.getString("firstName"), rs.getString("lastName")), rs.getString("email")))
                .list()
                .forEach(p -> out.put(p.id(), p));
        return out;
    }

    /** Role names by id. */
    public Map<String, String> roleNames(Collection<String> roleIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, name FROM \"Role\" WHERE id IN (:ids) AND \"organizationId\" = :org")
                .param("ids", new LinkedHashSet<>(roleIds))
                .param("org", TenantContext.organizationId())
                .query((rs, n) -> Map.entry(rs.getString("id"), rs.getString("name")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Pick list of active users for the rules builder. */
    public List<Person> allUsers() {
        return jdbc.sql("""
                        SELECT id, "firstName", "lastName", email FROM "User"
                        WHERE "organizationId" = ? AND "isActive" = true AND "deletedAt" IS NULL
                        ORDER BY "firstName", "lastName" LIMIT 1000""")
                .param(TenantContext.organizationId())
                .query((rs, n) -> new Person(rs.getString("id"),
                        fullName(rs.getString("firstName"), rs.getString("lastName")), rs.getString("email")))
                .list();
    }

    /** Pick list of roles for the rules builder. */
    public List<Person> allRoles() {
        return jdbc.sql("SELECT id, name FROM \"Role\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY name")
                .param(TenantContext.organizationId())
                .query((rs, n) -> new Person(rs.getString("id"), rs.getString("name"), null))
                .list();
    }

    static String fullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        return (f + " " + l).trim();
    }
}
