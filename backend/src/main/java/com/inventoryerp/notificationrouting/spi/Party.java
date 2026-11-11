package com.inventoryerp.notificationrouting.spi;

import java.util.Objects;

/**
 * Someone responsible for a record: a stable {@code key} (salesperson, assignee, ...), the label shown in the settings,
 * and a query returning their {@code "User".id}s. The query takes the named parameters {@code :id} (the record) and
 * {@code :org} (the organization), must filter on the organization, and may return several rows or a null. A party
 * can instead read user ids from the payload ({@link #payload}). Whatever it yields is checked again: only active users
 * of the organization are told.
 */
public record Party(String key, String label, String sql, String payloadField) {

    public static final String CREATOR = "creator";

    public Party {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        if ((sql == null) == (payloadField == null)) {
            throw new IllegalArgumentException(key + ": exactly one of sql and payloadField");
        }
    }

    public Party(String key, String label, String sql) {
        this(key, label, sql, null);
    }

    /** User id(s) carried by the event itself: a string or an array of strings at {@code field}. */
    public static Party payload(String key, String label, String field) {
        return new Party(key, label, null, field);
    }

    /** A user column on the record's own table: {@code SELECT "column" FROM "table" WHERE id = :id}. */
    public static Party column(String key, String label, String table, String column) {
        return new Party(key, label, "SELECT \"" + column + "\" FROM \"" + table + "\" WHERE id = :id AND"
                + " \"organizationId\" = :org");
    }

    /** The user who created the record, from its {@code createdById} column. */
    public static Party creator(String table) {
        return column(CREATOR, "Created by", table, "createdById");
    }

    /** The user linked to an HR employee column: {@code "HrEmployee"."userId"} of {@code table.employeeColumn}. */
    public static Party employee(String key, String label, String table, String employeeColumn) {
        return new Party(key, label, "SELECT e.\"userId\" FROM \"" + table + "\" t JOIN \"HrEmployee\" e ON e.id = t.\""
                + employeeColumn + "\" WHERE t.id = :id AND t.\"organizationId\" = :org");
    }

    /**
     * The manager of the HR employee in {@code table.employeeColumn}: the direct supervisor and the department manager
     * (never the employee themselves).
     */
    public static Party employeeManager(String key, String label, String table, String employeeColumn) {
        return new Party(key, label, "SELECT s.\"userId\" FROM \"" + table + "\" t JOIN \"HrEmployee\" e ON e.id = t.\""
                + employeeColumn + "\" JOIN \"HrEmployee\" s ON s.id = e.\"supervisorId\" WHERE t.id = :id AND"
                + " t.\"organizationId\" = :org UNION SELECT m.\"userId\" FROM \"" + table + "\" t JOIN \"HrEmployee\" e"
                + " ON e.id = t.\"" + employeeColumn + "\" JOIN \"HrDepartment\" d ON d.id = e.\"departmentId\" JOIN"
                + " \"HrEmployee\" m ON m.id = d.\"managerId\" WHERE t.id = :id AND t.\"organizationId\" = :org AND"
                + " m.id <> e.id");
    }

    /** Everyone in the organization holding {@code permission} through a role: a team with no owner column. */
    public static Party permission(String key, String label, String permission) {
        if (!permission.matches("[a-z_]+[:.][a-z_.]+")) {
            throw new IllegalArgumentException("Not a permission: " + permission);
        }
        return new Party(key, label, "SELECT DISTINCT ur.\"B\" FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\""
                + " WHERE r.\"organizationId\" = :org AND r.\"deletedAt\" IS NULL AND '" + permission
                + "' = ANY(r.permissions)");
    }

    /** Any query: {@code :id} and {@code :org} bound. */
    public static Party query(String key, String label, String sql) {
        return new Party(key, label, sql);
    }
}
