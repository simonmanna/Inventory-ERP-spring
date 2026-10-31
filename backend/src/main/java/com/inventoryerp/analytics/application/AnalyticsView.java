package com.inventoryerp.analytics.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A resolved filter: the period and what this user may see. {@code branchIds} null means every branch; otherwise only
 * rows of those branches (a restricted user's grants, or the requested branch), so a row without a branch is out.
 * {@code locationIds} is the restricted user's location list, null for an unrestricted user.
 */
public record AnalyticsView(AnalyticsFilter filter, LocalDate from, LocalDate to, String[] branchIds,
        String[] locationIds) {

    /** Whether the view excludes part of the organization by branch. */
    public boolean limited() {
        return branchIds != null;
    }

    public String fromText() {
        return from.toString();
    }

    public String toText() {
        return to.toString();
    }

    /** End of the last day for the ledger reports, whose date-only {@code to} would stop at that day's midnight. */
    public String ledgerTo() {
        return to + "T23:59:59.999Z";
    }

    public Date fromDate() {
        return Date.valueOf(from);
    }

    public Date toDate() {
        return Date.valueOf(to);
    }

    /** Start of the first business day. */
    public Timestamp start() {
        return SqlRows.ts(from.atStartOfDay(BusinessZone.get()).toInstant());
    }

    /** Start of the day after the last business day (exclusive bound). */
    public Timestamp end() {
        return SqlRows.ts(to.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant());
    }

    /** {@code AND column = ANY(branches)} when the view is limited. */
    public void branch(StringBuilder where, List<Object> params, String column) {
        if (branchIds != null) {
            where.append(" AND ").append(column).append(" = ANY(?::text[])");
            params.add(branchIds);
        }
    }

    /** {@code AND column = value} when a value was given. */
    public static void equal(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    /** The filters with a value that this dashboard does not honour, so the screen can say so. */
    public List<String> ignored(List<String> supported) {
        Set<String> honoured = Set.copyOf(supported);
        List<String> out = new ArrayList<>();
        addIgnored(out, honoured, "branch", filter.branchId());
        addIgnored(out, honoured, "site", filter.siteId());
        addIgnored(out, honoured, "deliverySite", filter.deliverySiteId());
        addIgnored(out, honoured, "product", filter.productId());
        addIgnored(out, honoured, "partner", filter.partnerId());
        return out;
    }

    private static void addIgnored(List<String> out, Set<String> honoured, String name, String value) {
        if (value != null && !honoured.contains(name)) {
            out.add(name);
        }
    }
}
