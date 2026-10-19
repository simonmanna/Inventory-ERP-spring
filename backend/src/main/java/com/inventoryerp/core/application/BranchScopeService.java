package com.inventoryerp.core.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Branch scoping for transactional queries (port of {@code branch-scope.service.ts}). A user with no
 * {@code defaultBranchId} has org-wide access; otherwise every transactional query is constrained to that
 * branch. Callers AND {@code branchId = ?} into their query when {@link #branchFilter()} is non-null.
 */
@Service
public class BranchScopeService {

    private final JdbcClient jdbc;

    public BranchScopeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The branch the current user is restricted to, or {@code null} for org-wide access. */
    public String branchFilter() {
        String userId = TenantContext.userId();
        if (userId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"defaultBranchId\" FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL")
                .params(userId, TenantContext.organizationId())
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
