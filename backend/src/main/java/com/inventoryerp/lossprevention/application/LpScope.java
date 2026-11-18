package com.inventoryerp.lossprevention.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the caller may see (ADR-S077 §7): their organization, their branches when their data scope is restricted, and
 * never a case about themselves.
 */
@Component
public class LpScope {

    private final BranchScopeService branches;
    private final JsonMapper mapper;

    public LpScope(BranchScopeService branches, JsonMapper mapper) {
        this.branches = branches;
        this.mapper = mapper;
    }

    public static String org() {
        return TenantContext.organizationId();
    }

    public static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * {@code WHERE} for a table that has {@code organizationId} and a {@code branchId} where {@code ''} means "no
     * branch". A row without a branch is outside a restricted user's scope.
     */
    public String where(String alias, List<Object> params) {
        String a = alias.isEmpty() ? "" : alias + ".";
        StringBuilder w = new StringBuilder(" WHERE ").append(a).append("\"organizationId\" = ?");
        params.add(org());
        DataScope scope = branches.scope();
        if (scope.restricted()) {
            w.append(" AND ").append(a).append("\"branchId\" = ANY(?::text[])");
            params.add(scope.branchArray());
        }
        return w.toString();
    }

    /** {@link #where} for {@code LpCase}, without the cases that are about the caller. */
    public String caseWhere(String alias, List<Object> params) {
        String a = alias.isEmpty() ? "" : alias + ".";
        String w = where(alias, params) + " AND " + a + "\"subjectUserId\" IS DISTINCT FROM ?";
        params.add(TenantContext.userId());
        return w;
    }

    /** 403 when a branch filter names a branch outside the caller's scope. */
    public void assertBranch(String branchId) {
        if (has(branchId)) {
            branches.assertBranch(branchId);
        }
    }

    public String json(Object value) {
        return mapper.writeValueAsString(value);
    }
}
