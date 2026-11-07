package com.inventoryerp.sales.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may see what in Sales (SG-1): product cost and margin need {@code sales:cost:read}; a user with a data scope sees
 * only the granted branches unless they hold {@code sales:branch:override}.
 *
 * <p>Null-branch rule: orders and quotations created before branch stamping have no branch. A restricted user sees such
 * a document only when they own or created it, so it cannot leak across branches. Report figures with no branch are
 * outside every restricted scope.
 */
@Component
public class SalesAccess {

    private static final Set<String> NOT_COST = Set.of("marginCheck", "minMarginPercent", "cogsRecognition");

    private final BranchScopeService branches;

    public SalesAccess(BranchScopeService branches) {
        this.branches = branches;
    }

    /** True when a response field reveals product cost or margin. */
    public static boolean isCostField(String key) {
        if (key == null || NOT_COST.contains(key)) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("costcent")) {
            return false;
        }
        return k.contains("cost") || k.contains("margin") || k.contains("profit") || k.contains("cogs")
                || k.contains("contribution") || "floorprice".equals(k) || "netpercent".equals(k);
    }

    public boolean canSeeCost() {
        return TenantContext.has(Permissions.SALES__COST_READ);
    }

    public void assertCost() {
        if (!canSeeCost()) {
            throw ApiException.forbidden("You do not have permission to see cost or margin");
        }
    }

    /** The caller's effective sales scope. */
    public DataScope scope() {
        return TenantContext.has(Permissions.SALES__BRANCH_OVERRIDE) ? DataScope.OPEN : branches.scope();
    }

    /** Branch to stamp on a new or edited document: inside the caller's scope, else the user's working branch. */
    public String documentBranch(String requested) {
        if (TenantContext.has(Permissions.SALES__BRANCH_OVERRIDE)) {
            return requested != null && !requested.isEmpty() ? requested : branches.branchFilter();
        }
        String stamped = branches.documentBranch(requested);
        return stamped != null && !stamped.isEmpty() ? stamped : branches.branchFilter();
    }

    /** Adds the branch condition of a document with owner and creator columns under {@code alias}. */
    public void filterDocument(StringBuilder where, List<Object> params, String alias) {
        DataScope s = scope();
        if (!s.restricted()) {
            return;
        }
        where.append(" AND (").append(alias).append(".\"branchId\" = ANY(?::text[]) OR (").append(alias)
                .append(".\"branchId\" IS NULL AND (").append(alias).append(".\"ownerId\" = ? OR ").append(alias)
                .append(".\"createdById\" = ?)))");
        params.add(s.branchArray());
        params.add(SalesDb.user());
        params.add(SalesDb.user());
    }

    /** SQL for a plain branch column, with its parameter added; empty when the caller is unrestricted. */
    public String branchClause(String column, List<Object> params) {
        DataScope s = scope();
        if (!s.restricted()) {
            return "";
        }
        params.add(s.branchArray());
        return " AND " + column + " = ANY(?::text[])";
    }

    /** 404-free check for a document with a plain branch: refuse it when the caller's scope excludes that branch. */
    public void assertBranchIfScoped(String branchId) {
        if (!scope().allowsBranch(branchId)) {
            throw ApiException.forbidden("This branch is outside your data access");
        }
    }

    /** Refuse an organization-wide figure or action for a restricted user. */
    public void assertAllBranches() {
        if (scope().restricted()) {
            throw ApiException.forbidden("This report covers all branches and is outside your data access");
        }
    }
}
