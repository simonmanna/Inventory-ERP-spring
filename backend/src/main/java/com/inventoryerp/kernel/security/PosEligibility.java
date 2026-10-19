package com.inventoryerp.kernel.security;

import java.time.Instant;
import java.util.List;

/**
 * Who may operate a POS terminal: one rule shared by every door into POS (port of
 * {@code pos-eligibility.ts}). Roles decide WHAT a cashier can do; this only answers WHETHER the
 * account can sign in at a till right now: the login must be active and not deleted, and a linked,
 * live HR record must not be suspended, terminated or resigned. A user with no employee record is
 * unaffected.
 */
public final class PosEligibility {

    /** Employment statuses that take someone off the tills. */
    public static final List<String> POS_BLOCKING_EMPLOYMENT_STATUSES = List.of("SUSPENDED", "TERMINATED", "RESIGNED");

    /**
     * SQL fragment (alias {@code u} = "User") excluding accounts whose linked live employee record has
     * ended or is suspended ({@code posEligibleUserWhere}).
     */
    public static final String ELIGIBLE_USER_SQL = "NOT EXISTS (SELECT 1 FROM \"HrEmployee\" e WHERE e.\"userId\" = u.id"
            + " AND e.\"deletedAt\" IS NULL AND e.\"employmentStatus\"::text IN ('SUSPENDED','TERMINATED','RESIGNED'))";

    private PosEligibility() {}

    /** Linked employee as loaded for the check. */
    public record Employee(String employmentStatus, Instant deletedAt) {}

    public static boolean employmentBlocksPos(Employee employee) {
        if (employee == null || employee.deletedAt() != null) {
            return false;
        }
        return POS_BLOCKING_EMPLOYMENT_STATUSES.contains(employee.employmentStatus());
    }

    /** Full check for a loaded user row (login state + employment). */
    public static boolean userMayOperatePos(boolean isActive, Instant deletedAt, Employee employee) {
        return isActive && deletedAt == null && !employmentBlocksPos(employee);
    }
}
