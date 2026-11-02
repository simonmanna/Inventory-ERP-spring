package com.inventoryerp.reporting.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Parameters of a report run, shared by every report. Unset fields mean "no filter". Dates are business dates
 * ({@code YYYY-MM-DD}) in the organization's time zone.
 *
 * @param period preset ({@code TODAY}, {@code YESTERDAY}, {@code THIS_MONTH}, {@code LAST_MONTH},
 *     {@code THIS_QUARTER}, {@code LAST_QUARTER}, {@code THIS_YEAR}, {@code LAST_YEAR}, {@code FISCAL_YTD},
 *     {@code LAST_FISCAL_YEAR}) resolved at run time, so a saved view or a schedule always means the current period;
 *     {@code from} / {@code to} / {@code asOf} win when set
 * @param compare comparative columns: {@code PRIOR_PERIOD}, {@code PRIOR_YEAR}, {@code BUDGET}, {@code VARIANCE},
 *     {@code VARIANCE_PCT}
 * @param postingStatus {@code POSTED} (default: posted and reversed entries) or {@code ALL} (adds drafts)
 * @param dimensions analytic dimension key → allowed values ({@code projectId → [..]})
 * @param options report-specific parameters ({@link OptionSpec}); defaults filled by the engine
 */
public record ReportRequest(String period, String from, String to, String asOf, List<String> branchIds,
        List<String> costCenterIds, List<String> partnerIds, List<String> accountIds, List<String> journalIds,
        List<String> sourceTypes, Map<String, List<String>> dimensions, String postingStatus, List<String> compare,
        String layoutCode, Integer page, Integer limit, Map<String, String> options) {

    public ReportRequest {
        branchIds = list(branchIds);
        costCenterIds = list(costCenterIds);
        partnerIds = list(partnerIds);
        accountIds = list(accountIds);
        journalIds = list(journalIds);
        sourceTypes = list(sourceTypes);
        compare = list(compare);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ReportRequest empty() {
        return new ReportRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static List<String> list(List<String> v) {
        return v == null ? List.of() : v.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    /** Same request with other branches (the data scope applied). */
    public ReportRequest withBranches(List<String> branches) {
        return new ReportRequest(period, from, to, asOf, branches, costCenterIds, partnerIds, accountIds, journalIds,
                sourceTypes, dimensions, postingStatus, compare, layoutCode, page, limit, options);
    }

    /** Same request with other options (defaults filled). */
    public ReportRequest withOptions(Map<String, String> opts) {
        return new ReportRequest(period, from, to, asOf, branchIds, costCenterIds, partnerIds, accountIds, journalIds,
                sourceTypes, dimensions, postingStatus, compare, layoutCode, page, limit, opts);
    }

    /** Same request with other accounts (drill helpers). */
    public ReportRequest withAccounts(List<String> accounts) {
        return new ReportRequest(period, from, to, asOf, branchIds, costCenterIds, partnerIds, accounts, journalIds,
                sourceTypes, dimensions, postingStatus, compare, layoutCode, page, limit, options);
    }

    /** Same request with other cost centers and dimensions (matrix columns). */
    public ReportRequest withSegment(List<String> branches, List<String> costCenters,
            Map<String, List<String>> dims) {
        return new ReportRequest(period, from, to, asOf, branches, costCenters, partnerIds, accountIds, journalIds,
                sourceTypes, dims, postingStatus, compare, layoutCode, page, limit, options);
    }

    public String option(String key) {
        return options.get(key);
    }

    public BigDecimal decimalOption(String key) {
        String v = options.get(key);
        return v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v.trim());
    }

    public int intOption(String key) {
        return decimalOption(key).intValue();
    }
}
