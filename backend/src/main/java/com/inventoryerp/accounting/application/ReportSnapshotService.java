package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    /** Differing partners kept on a tie-out snapshot's details, largest first. */
    static final int DETAIL_PARTNERS = 50;

    private final JdbcClient jdbc;
    private final LedgerQueries ledger;
    private final AccountResolverService accounts;
    private final ObjectProvider<SubledgerTieOut> tieOuts;
    private final TransactionTemplate tx;

    public ReportSnapshotService(JdbcClient jdbc, LedgerQueries ledger, AccountResolverService accounts,
            ObjectProvider<SubledgerTieOut> tieOuts, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.tieOuts = tieOuts;
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
        Map<String, AccountMeta> meta = accounts.ledgerMeta(grouped.keySet());
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
        Map<String, AccountMeta> meta = accounts.ledgerMeta(grouped.keySet());
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
        Map<String, AccountMeta> meta = accounts.ledgerMeta(grouped.keySet());
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

    private void rebuildApAging(String org, Instant asOf) {
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                .params(org, FiscalPeriodControl.ts(asOf)).update();
        // Purchase readiness B2: the ApAgingService measure — bills at their carrying rate and open receipt-voucher
        // payables at the receipts' rate, in base currency, less unapplied supplier payments (stored as a credit in the
        // partner's total: total = Σ buckets − unapplied).
        List<Map<String, Object>> bills = SqlRows.list(jdbc.sql("SELECT b.\"partnerId\", b.\"dueDate\", b.\"issueDate\","
                + " b.base AS \"amountResidual\", b.\"partnerName\" AS pname FROM (" + ApOpenItemsSql.OPEN_BILLS + ") b"
                + " UNION ALL SELECT v.\"partnerId\", v.\"dueDate\", v.\"issueDate\", round(v.\"amountResidual\" * v.rate, 2),"
                + " v.\"partnerName\" AS pname FROM (" + ApOpenItemsSql.RECEIPT_VOUCHER_PAYABLES + ") v")
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
        for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT u.\"partnerId\", sum(u.base) AS base, max(pt.name) AS"
                + " pname FROM (" + ApOpenItemsSql.UNAPPLIED_SUPPLIER_PAYMENTS + ") u LEFT JOIN \"Partner\" pt ON pt.id ="
                + " u.\"partnerId\" GROUP BY u.\"partnerId\"").params(org))) {
            Map<String, Object> b = buckets.computeIfAbsent(u.get("partnerId"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String f : List.of("total", "current", "b1_30", "b31_60", "b61_90", "b90p")) {
                    m.put(f, BigDecimal.ZERO);
                }
                m.put("partnerName", u.get("pname") == null ? "" : u.get("pname"));
                return m;
            });
            b.put("total", ((BigDecimal) b.get("total")).subtract(SqlRows.dec(u, "base")));
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

    /**
     * Tie-out for one organization (the current tenant): the AR and AP sub-ledgers against their control accounts at
     * the end of {@code asOf}'s org-local day.
     *
     * <p>Finance audit A5 (P2-5, deviation; Nest {@code tieout.service.ts} sums the residuals of sales invoices, POS
     * invoices and vendor bills in their own currencies against the one mapped {@code accounts_receivable} /
     * {@code accounts_payable} account): the {@link SubledgerTieOut} measure — base currency at each document's posting
     * rate, credit and debit notes and receipt-voucher payables included, less unapplied payments, against every
     * receivable / payable account, per partner — the measure of the {@code ar/ap.reconciliation} reports and of
     * integrity check I8. A side is balanced when its totals agree and no partner differs. The details keep
     * {@code glBalance} and {@code subLedgerBalance} and add the date, the basis and the differing partners.
     */
    public Map<String, Object> runTieOut(String org, Instant asOf) {
        if (!org.equals(TenantContext.organizationId())) {
            throw new IllegalStateException("The tie-out runs in the organisation's own tenant scope");
        }
        SubledgerTieOut tieOut = tieOuts.getIfAvailable();
        if (tieOut == null) {
            log.warn("No sub-ledger tie-out available; skipping the tie-out of org {}", org);
            return Obj.of("arBalanced", true, "arVariance", "0", "apBalanced", true, "apVariance", "0");
        }
        LocalDate day = asOf.atZone(AccountingDay.zone(jdbc, org)).toLocalDate();
        SubledgerTieOut.Measure ar = tieOut.measure("AR", day);
        SubledgerTieOut.Measure ap = tieOut.measure("AP", day);
        return tx.execute(s -> {
            jdbc.sql("DELETE FROM \"ReportTieoutSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ?")
                    .params(org, FiscalPeriodControl.ts(asOf)).update();
            jdbc.sql("INSERT INTO \"ReportTieoutSnapshot\" (id, \"organizationId\", \"asOf\", \"arBalanced\", \"arVariance\","
                            + " \"apBalanced\", \"apVariance\", \"arDetails\", \"apDetails\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)")
                    .params(UUID.randomUUID().toString(), org, FiscalPeriodControl.ts(asOf), ar.balanced(), ar.variance(),
                            ap.balanced(), ap.variance(), EntityJson.toJson(details(ar)), EntityJson.toJson(details(ap)))
                    .update();
            if (!ar.balanced() || !ap.balanced()) {
                log.warn("Org {} tie-out imbalanced: AR variance {} ({} partners differ), AP variance {} ({} partners"
                        + " differ)", org, ar.variance(), ar.differences().size(), ap.variance(),
                        ap.differences().size());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("arBalanced", ar.balanced());
            out.put("arVariance", PostingService.plain(ar.variance()));
            out.put("apBalanced", ap.balanced());
            out.put("apVariance", PostingService.plain(ap.variance()));
            return out;
        });
    }

    /** Snapshot details of one side: the totals, then the date, the basis and the partners that differ. */
    private static Map<String, Object> details(SubledgerTieOut.Measure m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("glBalance", PostingService.plain(m.gl()));
        d.put("subLedgerBalance", PostingService.plain(m.subledger()));
        d.put("asOf", m.asOf().toString());
        d.put("basis", "base currency: open items at their posting rate less unapplied payments, against every "
                + ("AR".equals(m.side()) ? "receivable" : "payable") + " account, per partner");
        d.put("partnersDiffering", m.differences().size());
        d.put("partners", m.differences().stream().limit(DETAIL_PARTNERS).map(p -> Obj.of("partnerId", p.partnerId(),
                "partner", p.partner(), "subLedger", PostingService.plain(p.subledger()), "gl",
                PostingService.plain(p.gl()), "difference", PostingService.plain(p.difference()))).toList());
        return d;
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
}
