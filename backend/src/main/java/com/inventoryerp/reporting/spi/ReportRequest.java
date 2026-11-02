package com.inventoryerp.reporting.spi;

import java.util.List;
import java.util.Map;

/**
 * Parameters of a report run, shared by every report. Unset fields mean "no filter". Dates are business dates
 * ({@code YYYY-MM-DD}) in the organization's time zone.
 *
 * @param period preset ({@code THIS_MONTH}, {@code LAST_MONTH}, {@code THIS_QUARTER}, {@code LAST_QUARTER},
 *     {@code THIS_YEAR}, {@code LAST_YEAR}, {@code TODAY}, {@code YESTERDAY}) resolved at run time, so a saved view or
 *     a schedule always means the current period; {@code from} / {@code to} / {@code asOf} win when set
 * @param compare comparative columns: {@code PRIOR_PERIOD}, {@code PRIOR_YEAR}, {@code VARIANCE}, {@code VARIANCE_PCT}
 * @param postingStatus {@code POSTED} (default: posted and reversed entries) or {@code ALL} (adds drafts)
 * @param dimensions analytic dimension key → allowed values ({@code projectId → [..]})
 */
public record ReportRequest(String period, String from, String to, String asOf, List<String> branchIds,
        List<String> costCenterIds, List<String> partnerIds, List<String> accountIds, List<String> journalIds,
        List<String> sourceTypes, Map<String, List<String>> dimensions, String postingStatus, List<String> compare,
        String layoutCode, Integer page, Integer limit) {

    public ReportRequest {
        branchIds = list(branchIds);
        costCenterIds = list(costCenterIds);
        partnerIds = list(partnerIds);
        accountIds = list(accountIds);
        journalIds = list(journalIds);
        sourceTypes = list(sourceTypes);
        compare = list(compare);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }

    public static ReportRequest empty() {
        return new ReportRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private static List<String> list(List<String> v) {
        return v == null ? List.of() : v.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    /** Same request with other branches (the data scope applied). */
    public ReportRequest withBranches(List<String> branches) {
        return new ReportRequest(period, from, to, asOf, branches, costCenterIds, partnerIds, accountIds, journalIds,
                sourceTypes, dimensions, postingStatus, compare, layoutCode, page, limit);
    }
}
