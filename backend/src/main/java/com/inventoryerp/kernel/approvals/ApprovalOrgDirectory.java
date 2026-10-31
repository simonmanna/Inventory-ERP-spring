package com.inventoryerp.kernel.approvals;

/**
 * Who sits where in the organization, for approval conditions and hierarchies. Kernel cannot read HR tables, so the
 * {@code hr} module implements this. Both answers are scoped to the current organization, and {@code null} means
 * unknown. No implementation at all simply means no department condition ever matches a requester.
 */
public interface ApprovalOrgDirectory {

    /** Department of the employee linked to this user. */
    String departmentOf(String userId);

    /** User account of the department's manager. */
    String departmentManagerOf(String departmentId);
}
