package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.DetailedReportService;
import com.inventoryerp.accounting.application.FinancialReportsService;
import com.inventoryerp.accounting.application.PosGlReconciliationService;
import com.inventoryerp.accounting.application.ReportSnapshotService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/accounting} (port of {@code accounting-reporting.controller.ts}): financial statements and controls. */
@RestController
@RequestMapping("/reports/accounting")
@RequirePermissions(Permissions.REPORT__ACCOUNTING)
public class AccountingReportingController {

    private final FinancialReportsService reports;
    private final DetailedReportService detailed;
    private final PosGlReconciliationService posGlRecon;
    private final ReportSnapshotService snapshots;

    public AccountingReportingController(FinancialReportsService reports, DetailedReportService detailed,
            PosGlReconciliationService posGlRecon, ReportSnapshotService snapshots) {
        this.reports = reports;
        this.detailed = detailed;
        this.posGlRecon = posGlRecon;
        this.snapshots = snapshots;
    }

    /** Per-account opening balance, every posted line with a running balance, and the closing balance. */
    @GetMapping("/detailed")
    public Map<String, Object> detailedReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String classification, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String costCenterId, @RequestParam(required = false) String journalId,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String includeZero,
            @RequestParam(required = false) String summaryOnly, @RequestParam(required = false) String maxLines) {
        return detailed.detailed(new DetailedReportService.Query(from, to, accountIds, classification, branchId,
                costCenterId, journalId, partnerId, "true".equals(includeZero), "true".equals(summaryOnly),
                maxLines != null && !maxLines.isEmpty() ? JsNumbers.number(maxLines) : null));
    }

    @GetMapping("/trial-balance")
    public Map<String, Object> trialBalance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String view) {
        return reports.trialBalance(from, to, view);
    }

    @GetMapping("/account-ledger/{accountId}")
    public Map<String, Object> accountLedger(@PathVariable String accountId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.accountLedger(accountId, from, to);
    }

    @GetMapping("/general-ledger")
    public Map<String, Object> generalLedger(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String page) {
        return reports.generalLedger(from, to, JsNumbers.orDefault(page, 1), 100);
    }

    @GetMapping("/profit-and-loss")
    public Map<String, Object> profitAndLoss(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.pnl(from, to);
    }

    @GetMapping("/balance-sheet/detailed")
    public Map<String, Object> balanceSheetDetailed(@RequestParam(required = false) String asOf) {
        return reports.balanceSheetDetailed(asOf);
    }

    @GetMapping("/balance-sheet")
    public Map<String, Object> balanceSheetReport(@RequestParam(required = false) String asOf) {
        return reports.balanceSheet(asOf);
    }

    /** Accepts both {@code from/to} and the POS-report names {@code fromDate/toDate}. */
    @GetMapping("/cash-flow")
    public Map<String, Object> cashFlowReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return reports.cashFlow(from != null ? from : fromDate, to != null ? to : toDate);
    }

    @GetMapping("/tieout")
    public Map<String, Object> tieout(@RequestParam(required = false) String asOf) {
        return snapshots.latestTieOut(asOf);
    }

    /** Independent POS → GL reconciliation (C-20) + inventory/COGS lag monitor (C-08). */
    @GetMapping("/pos-gl-reconciliation")
    public Map<String, Object> posGlReconciliation(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return posGlRecon.reconcile(from, to);
    }

    /** Operator-triggered: rebuild the snapshots of the current org now, then the AR/AP tie-out. */
    @PostMapping("/rebuild-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> rebuildSnapshots() {
        Instant asOf = Instant.now();
        String organizationId = TenantContext.organizationId();
        snapshots.rebuildForOrg(organizationId, asOf);
        snapshots.runTieOut(organizationId, asOf);
        return Map.of("rebuiltAt", asOf);
    }
}
