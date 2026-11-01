package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reporting snapshots and AR/AP tie-out (ports of {@code snapshot-rebuild.service.ts}, {@code tieout.service.ts}
 * and {@code snapshot-cron.worker.ts}): materializes trial balance, P&amp;L, balance sheet and AP aging per
 * {@code (organizationId, asOf)}, reconciles the AR/AP control accounts against open sub-ledger residuals, and
 * runs both nightly at {@code SNAPSHOT_RUN_HOUR_UTC} (default 02:00 UTC).
 */
@Service
public class ReportSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ReportSnapshotService.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcClient jdbc;
    private final LedgerQueries ledger;
    private final AccountResolverService accounts;
    private final TransactionTemplate tx;

    public ReportSnapshotService(JdbcClient jdbc, LedgerQueries ledger, AccountResolverService accounts,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.tx = new TransactionTemplate(txm);
    }

    private static String legacyTypeOf(String classification) {
        return switch (String.valueOf(classification)) {
            case "liability", "equity", "revenue", "expense" -> classification;
            default -> "asset";
        };
    }

    /** Nightly at the configured UTC hour: snapshots then tie-out for every organization. */
    @Scheduled(cron = "0 0 ${SNAPSHOT_RUN_HOUR_UTC:2} * * *", zone = "UTC")
    @SchedulerLock(name = "report-snapshots", lockAtMostFor = "PT2H")
    public void nightly() {
        long started = System.currentTimeMillis();
        Instant asOf = Instant.now();
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\"").query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
                    rebuildForOrg(orgId, asOf);
                    runTieOut(orgId, asOf);
                    return null;
                });
            } catch (RuntimeException e) {
                log.error("Snapshot rebuild failed for org {}: {}", orgId, e.toString());
            }
        }
        log.info("Nightly rebuild done: {} orgs in {}ms", orgs.size(), System.currentTimeMillis() - started);
    }

    /** Rebuild every snapshot of one organization at {@code asOf} (idempotent). */
    public void rebuildForOrg(String organizationId, Instant asOf) {
        tx.executeWithoutResult(s -> {
            rebuildTrialBalance(organizationId, asOf);
            rebuildPnL(organizationId, asOf);
            rebuildBalanceSheet(organizationId, asOf);
            rebuildApAging(organizationId, asOf);
        });
    }

    private void rebuildTrialBalance(String org, Instant asOf) {
        jdbc.sql("DELETE FROM \"ReportTrialBalanceSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                .params(org, FiscalPeriodControl.ts(asOf)).update();
        Map<String, BigDecimal[]> grouped = ledger.balances(org, null, asOf, null);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            if (a == null) {
                continue;
            }
            BigDecimal d = g.getValue()[0];
            BigDecimal c = g.getValue()[1];
            jdbc.sql("INSERT INTO \"ReportTrialBalanceSnapshot\" (id, \"organizationId\", \"asOf\", \"accountId\", \"accountCode\","
                            + " \"accountName\", \"accountCategoryKey\", classification, \"normalBalance\", \"accountType\", debit, credit,"
                            + " balance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), a.id(), a.code(), a.name(),
                            a.categoryKey(), a.classification(), a.normalBalance(), legacyTypeOf(a.classification()), d, c,
                            d.subtract(c))
                    .update();
        }
    }

    private void rebuildPnL(String org, Instant asOf) {
        jdbc.sql("DELETE FROM \"ReportPnLSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                .params(org, FiscalPeriodControl.ts(asOf)).update();
        Map<String, BigDecimal[]> grouped = ledger.balances(org, null, asOf, null);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal contra = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            if (a == null) {
                continue;
            }
            BigDecimal v = AccountClassification.displayBalance(g.getValue()[0].subtract(g.getValue()[1]), a.normalBalance());
            switch (String.valueOf(a.reportSection())) {
                case "revenue", "other_income" -> revenue = revenue.add(v);
                case "contra_revenue" -> contra = contra.add(v);
                case "cogs" -> cogs = cogs.add(v);
                case "operating_expense", "other_expense" -> expense = expense.add(v);
                default -> { }
            }
        }
        jdbc.sql("INSERT INTO \"ReportPnLSnapshot\" (id, \"organizationId\", \"asOf\", revenue, \"contraRevenue\", cogs, expense,"
                        + " \"netIncome\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), revenue, contra, cogs, expense,
                        revenue.subtract(contra).subtract(cogs).subtract(expense))
                .update();
    }

    private void rebuildBalanceSheet(String org, Instant asOf) {
        jdbc.sql("DELETE FROM \"ReportBalanceSheetSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                .params(org, FiscalPeriodControl.ts(asOf)).update();
        Map<String, BigDecimal[]> grouped = ledger.balances(org, null, asOf, null);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            if (a == null || a.categoryId() == null || "off_balance".equals(a.classification())) {
                continue;
            }
            BigDecimal balance = AccountClassification.displayBalance(g.getValue()[0].subtract(g.getValue()[1]), a.normalBalance());
            jdbc.sql("INSERT INTO \"ReportBalanceSheetSnapshot\" (id, \"organizationId\", \"asOf\", \"accountId\", \"accountCode\","
                            + " \"accountName\", \"accountCategoryKey\", classification, \"normalBalance\", \"reportSection\","
                            + " \"accountType\", balance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), a.id(), a.code(), a.name(),
                            a.categoryKey(), a.classification(), a.normalBalance(), a.reportSection(),
                            legacyTypeOf(a.classification()), balance)
                    .update();
        }
    }

    /**
     * Payables raised by purchase-order receipts (receipt vouchers, P1-7): the bill matched to such a receipt carries no
     * residual, so the open amount lives on the PO — vouchered receipts less PO payments, aged from the first receipt.
     * Same measure as the PO pay route ({@code PurchaseOrdersService.vouchered}). Two org parameters.
     */
    private static final String RECEIPT_VOUCHER_PAYABLES = "SELECT po.\"partnerId\", p.name AS \"partnerName\", v.at AS \"dueDate\","
            + " v.at AS \"issueDate\", LEAST(po.\"totalAmount\", v.amt) - GREATEST(COALESCE(pp.paid, 0),"
            + " COALESCE(po.\"totalPaid\", 0)) AS \"amountResidual\" FROM (SELECT g.\"purchaseOrderId\" AS po,"
            + " MIN(g.\"receivedAt\") AS at, SUM(l.quantity * l.\"unitCost\" * (1 + COALESCE(pl.\"taxRate\", 0) / 100)) AS amt"
            + " FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
            + " LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE g.\"organizationId\" = ?"
            + " AND g.status::text = 'posted' AND g.\"deletedAt\" IS NULL AND l.\"productId\" IS NOT NULL"
            + " AND g.\"purchaseOrderId\" IS NOT NULL GROUP BY g.\"purchaseOrderId\") v"
            + " JOIN \"PurchaseOrder\" po ON po.id = v.po LEFT JOIN \"Partner\" p ON p.id = po.\"partnerId\""
            + " LEFT JOIN (SELECT \"purchaseOrderId\", SUM(amount) AS paid FROM \"PurchasePayment\" WHERE"
            + " \"organizationId\" = ? GROUP BY \"purchaseOrderId\") pp ON pp.\"purchaseOrderId\" = po.id"
            + " WHERE po.status::text <> 'cancelled' AND LEAST(po.\"totalAmount\", v.amt) - GREATEST(COALESCE(pp.paid, 0),"
            + " COALESCE(po.\"totalPaid\", 0)) > 0.005";

    private void rebuildApAging(String org, Instant asOf) {
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                .params(org, FiscalPeriodControl.ts(asOf)).update();
        // Bills with a residual plus the open receipt-voucher payables of purchase orders (same as ApAgingService).
        List<Map<String, Object>> bills = SqlRows.list(jdbc.sql("SELECT d.\"partnerId\", d.\"dueDate\", d.\"issueDate\","
                + " d.\"amountResidual\", p.name AS pname FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\""
                + " WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'vendor_bill' AND d.status::text IN ('posted', 'paid')"
                + " AND d.\"amountResidual\" > 0 UNION ALL SELECT v.\"partnerId\", v.\"dueDate\", v.\"issueDate\","
                + " v.\"amountResidual\", v.\"partnerName\" AS pname FROM (" + RECEIPT_VOUCHER_PAYABLES + ") v")
                .params(org, org, org));
        Map<Object, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> bill : bills) {
            Instant due = (Instant) (bill.get("dueDate") != null ? bill.get("dueDate") : bill.get("issueDate"));
            long days = Math.max(0, Math.floorDiv(Duration.between(due, asOf).toMillis(), 86_400_000L));
            BigDecimal residual = SqlRows.dec(bill, "amountResidual");
            String bucket = days <= 0 ? "current" : days <= 30 ? "b1_30" : days <= 60 ? "b31_60" : days <= 90 ? "b61_90" : "b90p";
            Map<String, Object> b = buckets.computeIfAbsent(bill.get("partnerId"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String f : List.of("total", "current", "b1_30", "b31_60", "b61_90", "b90p")) {
                    m.put(f, BigDecimal.ZERO);
                }
                m.put("partnerName", bill.get("pname") == null ? "" : bill.get("pname"));
                return m;
            });
            b.put(bucket, ((BigDecimal) b.get(bucket)).add(residual));
            b.put("total", ((BigDecimal) b.get("total")).add(residual));
            if (bill.get("pname") != null) {
                b.put("partnerName", bill.get("pname"));
            }
        }
        for (Map.Entry<Object, Map<String, Object>> e : buckets.entrySet()) {
            Map<String, Object> b = e.getValue();
            jdbc.sql("INSERT INTO \"ReportApAgingSnapshot\" (id, \"organizationId\", \"asOf\", \"partnerId\", \"partnerName\", total,"
                            + " \"current\", b1_30, b31_60, b61_90, b90p) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT DO NOTHING")
                    .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), e.getKey(), b.get("partnerName"),
                            b.get("total"), b.get("current"), b.get("b1_30"), b.get("b31_60"), b.get("b61_90"), b.get("b90p"))
                    .update();
        }
    }

    /** Tie-out for one organization: GL AR/AP control balance vs open sub-ledger residuals. */
    public Map<String, Object> runTieOut(String org, Instant asOf) {
        return tx.execute(s -> {
            jdbc.sql("DELETE FROM \"ReportTieoutSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                    .params(org, FiscalPeriodControl.ts(asOf)).update();
            String ar = mapping(org, "accounts_receivable");
            String ap = mapping(org, "accounts_payable");
            Map<String, Object> out = new LinkedHashMap<>();
            if (ar == null || ap == null) {
                log.warn("Org {} missing AR/AP account mapping; skipping tie-out", org);
                out.put("arBalanced", true);
                out.put("arVariance", "0");
                out.put("apBalanced", true);
                out.put("apVariance", "0");
                return out;
            }
            BigDecimal[] arSums = ledger.balances(org, null, asOf, null).getOrDefault(ar, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] apSums = ledger.balances(org, null, asOf, null).getOrDefault(ap, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal arGl = arSums[0].subtract(arSums[1]);
            BigDecimal apGl = apSums[1].subtract(apSums[0]);
            BigDecimal arSub = residuals("SELECT coalesce(sum(\"amountResidual\"), 0) FROM \"Document\" WHERE \"organizationId\" = ?"
                    + " AND \"documentType\"::text = 'sales_invoice' AND status::text IN ('posted', 'paid')", org)
                    .add(residuals("SELECT coalesce(sum(\"amountResidual\"), 0) FROM \"Invoice\" WHERE \"organizationId\" = ?"
                            + " AND status::text IN ('posted', 'paid')", org));
            BigDecimal apSub = residuals("SELECT coalesce(sum(\"amountResidual\"), 0) FROM \"Document\" WHERE \"organizationId\" = ?"
                    + " AND \"documentType\"::text = 'vendor_bill' AND status::text IN ('posted', 'paid')", org);
            BigDecimal arVariance = arGl.subtract(arSub);
            BigDecimal apVariance = apGl.subtract(apSub);
            boolean arBalanced = arVariance.abs().compareTo(TOLERANCE) <= 0;
            boolean apBalanced = apVariance.abs().compareTo(TOLERANCE) <= 0;
            jdbc.sql("INSERT INTO \"ReportTieoutSnapshot\" (id, \"organizationId\", \"asOf\", \"arBalanced\", \"arVariance\","
                            + " \"apBalanced\", \"apVariance\", \"arDetails\", \"apDetails\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)")
                    .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), arBalanced, arVariance, apBalanced,
                            apVariance, EntityJson.toJson(Map.of("glBalance", PostingService.plain(arGl),
                                    "subLedgerBalance", PostingService.plain(arSub))),
                            EntityJson.toJson(Map.of("glBalance", PostingService.plain(apGl),
                                    "subLedgerBalance", PostingService.plain(apSub))))
                    .update();
            if (!arBalanced || !apBalanced) {
                log.warn("Org {} tie-out imbalanced: AR variance {}, AP variance {}", org, arVariance, apVariance);
            }
            out.put("arBalanced", arBalanced);
            out.put("arVariance", PostingService.plain(arVariance));
            out.put("apBalanced", apBalanced);
            out.put("apVariance", PostingService.plain(apVariance));
            return out;
        });
    }

    /** Latest tie-out snapshot of the current tenant (optionally exactly at {@code asOf}); {@code null} when none. */
    public Map<String, Object> latestTieOut(String asOf) {
        return tx.execute(s -> {
            String org = TenantContext.organizationId();
            Map<String, Object> row = asOf == null || asOf.isEmpty()
                    ? SqlRows.one(jdbc.sql("SELECT * FROM \"ReportTieoutSnapshot\" WHERE \"organizationId\" = ? ORDER BY \"asOf\" DESC LIMIT 1")
                            .param(org))
                    : SqlRows.one(jdbc.sql("SELECT * FROM \"ReportTieoutSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?"
                            + " ORDER BY \"asOf\" DESC LIMIT 1").params(org, FiscalPeriodControl.ts(LedgerQueries.date(asOf))));
            if (row == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asOf", row.get("asOf"));
            out.put("arBalanced", row.get("arBalanced"));
            out.put("arVariance", PostingService.plain(SqlRows.dec(row, "arVariance")));
            out.put("apBalanced", row.get("apBalanced"));
            out.put("apVariance", PostingService.plain(SqlRows.dec(row, "apVariance")));
            out.put("arDetails", row.get("arDetails"));
            out.put("apDetails", row.get("apDetails"));
            return out;
        });
    }

    private String mapping(String org, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ? LIMIT 1")
                .params(org, key).query(String.class).optional().orElse(null);
    }

    private BigDecimal residuals(String sql, String org) {
        return jdbc.sql(sql).param(org).query(BigDecimal.class).single();
    }
}
