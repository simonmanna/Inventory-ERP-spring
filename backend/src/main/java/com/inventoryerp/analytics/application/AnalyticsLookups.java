package com.inventoryerp.analytics.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The filter lists of the dashboards, separate from {@code branch:read} or {@code product:read} so a manager holding
 * only analytics permissions can filter. A restricted user sees their own branches and the sites of those branches.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsLookups {

    private static final int LIMIT = 300;

    private final BranchScopeService scopes;
    private final JdbcClient jdbc;

    public AnalyticsLookups(BranchScopeService scopes, JdbcClient jdbc) {
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    /** {@code partnerId} also returns that customer's delivery sites. */
    public Map<String, Object> options(String partnerId) {
        String org = TenantContext.organizationId();
        DataScope access = scopes.scope();
        String branchScope = access.restricted() ? " AND id = ANY(?::text[])" : "";
        List<Object> branchParams = new ArrayList<>(List.of(org));
        List<Object> siteParams = new ArrayList<>(List.of(org));
        if (access.restricted()) {
            branchParams.add(access.branchArray());
            siteParams.add(access.branchArray());
        }
        List<Map<String, Object>> branches = SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Branch\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL" + branchScope + " ORDER BY name")
                .params(branchParams));
        List<Map<String, Object>> sites = SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"QuarrySite\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + (access.restricted() ? " AND \"branchId\" = ANY(?::text[])" : "") + " ORDER BY name")
                .params(siteParams));
        List<Map<String, Object>> products = SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Product\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" ORDER BY name LIMIT ?")
                .params(org, LIMIT));
        List<Map<String, Object>> customers = partners("\"isCustomer\"", org);
        List<Map<String, Object>> suppliers = partners("\"isSupplier\"", org);
        List<Map<String, Object>> deliverySites = partnerId == null || partnerId.isBlank() ? List.of()
                : SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"FleetCustomerSite\" WHERE \"organizationId\" = ?"
                        + " AND \"partnerId\" = ? AND \"deletedAt\" IS NULL ORDER BY name").params(org, partnerId));
        return Obj.of("branches", branches, "sites", sites, "products", products, "customers", customers, "suppliers",
                suppliers, "deliverySites", deliverySites);
    }

    private List<Map<String, Object>> partners(String flag, String org) {
        return SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Partner\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND " + flag + " ORDER BY name LIMIT ?").params(org, LIMIT));
    }
}
