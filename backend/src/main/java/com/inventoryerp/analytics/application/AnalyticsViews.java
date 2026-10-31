package com.inventoryerp.analytics.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns a filter into an {@link AnalyticsView}: default period (month to date), period limits, the branch filter and
 * the user's data scope. Everything a dashboard shows passes through here, so a restricted user can only narrow their
 * view. Sites follow {@code QuarrySite.branchId} and plants their site, so a site or plant outside the view yields no
 * rows instead of an error.
 */
@Component
public class AnalyticsViews {

    private static final long MAX_DAYS = 731;

    private final BranchScopeService scopes;
    private final JdbcClient jdbc;

    public AnalyticsViews(BranchScopeService scopes, JdbcClient jdbc) {
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    /** Validates the period and the branch filter against the user's scope. */
    public AnalyticsView view(AnalyticsFilter f) {
        LocalDate to = f.to() == null ? LocalDate.now(BusinessZone.get()) : f.to();
        LocalDate from = f.from() == null ? to.withDayOfMonth(1) : f.from();
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("A report period can cover at most two years");
        }
        DataScope access = scopes.scope();
        String[] branchIds = access.restricted() ? access.branchArray() : null;
        if (f.branchId() != null) {
            scopes.assertBranchExists(f.branchId());
            scopes.assertBranch(f.branchId());
            branchIds = new String[] {f.branchId()};
        }
        return new AnalyticsView(f, from, to, branchIds, access.restricted() ? access.locationArray() : null);
    }

    /** Quarry sites in the view; null when nothing narrows them. */
    public String[] siteIds(AnalyticsView v) {
        if (!v.limited() && v.filter().siteId() == null) {
            return null;
        }
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        v.branch(where, params, "s.\"branchId\"");
        AnalyticsView.equal(where, params, "s.id", v.filter().siteId());
        return jdbc.sql("SELECT s.id FROM \"QuarrySite\" s" + where).params(params).query(String.class).list()
                .toArray(String[]::new);
    }

    /** Crushing plants of the sites in the view; null when nothing narrows them. */
    public String[] plantIds(AnalyticsView v) {
        String[] sites = siteIds(v);
        if (sites == null) {
            return null;
        }
        return jdbc.sql("SELECT p.id FROM \"CrushingPlant\" p WHERE p.\"organizationId\" = ? AND"
                + " p.\"deletedAt\" IS NULL AND p.\"siteId\" = ANY(?::text[])")
                .params(TenantContext.organizationId(), sites).query(String.class).list().toArray(String[]::new);
    }

    /**
     * Stock locations in the view: the user's grants, the branch filter and the stockpiles of the site filter. Null
     * when nothing narrows them.
     */
    public String[] stockLocationIds(AnalyticsView v) {
        if (!v.limited() && v.locationIds() == null && v.filter().siteId() == null) {
            return null;
        }
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        v.branch(where, params, "l.\"branchId\"");
        if (v.locationIds() != null) {
            where.append(" AND l.id = ANY(?::text[])");
            params.add(v.locationIds());
        }
        if (v.filter().siteId() != null) {
            where.append(" AND l.id IN (SELECT k.\"locationId\" FROM \"QuarryStockpile\" k WHERE k.\"organizationId\" ="
                    + " l.\"organizationId\" AND k.\"siteId\" = ? AND k.\"deletedAt\" IS NULL)");
            params.add(v.filter().siteId());
        }
        return jdbc.sql("SELECT l.id FROM \"InventoryLocation\" l" + where).params(params).query(String.class).list()
                .toArray(String[]::new);
    }
}
