package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Branch dimension on costing postings (Cost Management CM-2, ADR-S032, AD-6). Setting
 * {@code costing.dimensionEnforcement}: {@code off}; {@code warn} (default) — postings without a branch are listed by
 * {@link #compliance}; {@code enforce} — a costing posting without a branch on every line is refused. Only costing
 * sources are checked, so ordinary sales / cash postings are never affected.
 */
@Component
public class CostingDimensionGuard {

    /** Journal source types produced by the costing engines. */
    public static final Set<String> COSTING_SOURCES = Set.of("landed_cost", "landed_cost_voucher",
            "landed_cost_voucher_reversal", "inventory_revaluation", "inventory_nrv", "cost_release",
            "material_ledger_run", "standard_cost", "cost_policy", "production_variance", "cost_allocation",
            "absorption_settlement", "quarry_stripping", "quarry_rehab", "landed_cost_accrual",
            "landed_cost_accrual_clearing", "delivery_cost_run", "branch_transfer", "project_issue",
            "project_timesheet", "project_equipment", "project_reclass", "project_subcontract", "project_back_charge",
            "progress_claim", "revenue_recognition", "onerous_contract", "cost_adjustment_run");

    private final SettingResolverService settings;
    private final JdbcClient jdbc;

    public CostingDimensionGuard(SettingResolverService settings, JdbcClient jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /** Called by the posting engine after the lines are resolved. */
    void check(List<NormalizedLine> lines, String requestBranchId, String sourceType, java.time.Instant date) {
        if (sourceType == null || !COSTING_SOURCES.contains(sourceType)) {
            return;
        }
        assertCostingPeriodOpen(date);
        if (!"enforce".equals(settings.resolveString("costing.dimensionEnforcement", SettingContext.EMPTY))) {
            return;
        }
        for (NormalizedLine l : lines) {
            if ((l.branchId() == null || l.branchId().isBlank()) && (requestBranchId == null
                    || requestBranchId.isBlank())) {
                throw ApiException.badRequest("Costing postings need a branch on every line (Costing & Valuation"
                        + " settings: branch dimension is enforced)");
            }
        }
    }

    /**
     * CM-10 (ADR-S041): costing postings dated inside a locked costing period are refused until the period is
     * reopened ({@code costing_close:reopen}, audited).
     */
    public void assertCostingPeriodOpen(java.time.Instant date) {
        String org = TenantContext.optionalOrganizationId();
        if (org == null || date == null) {
            return;
        }
        java.sql.Date day = java.sql.Date.valueOf(AccountingDay.of(date, AccountingDay.zone(jdbc, org)));
        jdbc.sql("SELECT \"periodFrom\", \"periodTo\" FROM \"CostingPeriodLock\" WHERE \"organizationId\" = ? AND"
                + " status = 'locked' AND \"periodFrom\" <= ? AND \"periodTo\" >= ? LIMIT 1").params(org, day, day)
                .query((rs, n) -> rs.getString(1) + " – " + rs.getString(2)).optional().ifPresent(p -> {
                    throw ApiException.badRequest("The costing period " + p + " is locked; reopen it before posting"
                            + " costing entries into it");
                });
    }

    /** As {@link #compliance(Timestamp, Timestamp)} for the org-local days {@code from} … {@code to} (ADR-S070). */
    public List<Map<String, Object>> compliance(java.time.LocalDate from, java.time.LocalDate to) {
        java.time.ZoneId zone = AccountingDay.zone(jdbc);
        return compliance(AccountingDay.startTs(from, zone), AccountingDay.endExclusiveTs(to, zone));
    }

    /** Costing journal lines without a branch in a period, grouped by source type. */
    public List<Map<String, Object>> compliance(Timestamp from, Timestamp to) {
        return SqlRows.list(jdbc.sql("SELECT e.\"sourceType\", count(*) AS lines, count(DISTINCT e.id) AS entries,"
                        + " sum(jl.\"baseDebit\" + jl.\"baseCredit\") AS amount FROM \"JournalLine\" jl JOIN \"JournalEntry\""
                        + " e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND e.status <> 'draft' AND"
                        + " e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND jl.\"branchId\" IS NULL AND"
                        + " e.\"sourceType\" = ANY (?) GROUP BY 1 ORDER BY 1")
                .params(TenantContext.organizationId(), from, to, COSTING_SOURCES.toArray(String[]::new)));
    }
}
