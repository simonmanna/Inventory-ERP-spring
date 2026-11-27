package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.ApOpenItemsSql;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP aging (port of {@code ap-aging.service.ts}): a fresh snapshot (≤ 60 s old, ≤ asOf) when present, else live. In
 * base currency, net of unapplied supplier payments ({@code unapplied}, per partner and in total), so it ties to the
 * payable control accounts ({@link ApOpenItemsSql}, purchase readiness B2).
 */
@Service
@Transactional(readOnly = true)
public class ApAgingService {

    private static final long FALLBACK_EPSILON_MS = 60_000L;
    private static final List<String> BUCKETS = List.of("current", "b1_30", "b31_60", "b61_90", "b90p");

    private final JdbcClient jdbc;

    public ApAgingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** AP aging as of {@code asOf} (ISO; default now). */
    public Map<String, Object> apAging(String asOfIn) {
        String asOf = asOfIn != null ? asOfIn : com.inventoryerp.kernel.web.JsonConfig.toJsIso(Instant.now());
        Instant requested = EntityPatch.toInstant(asOf);
        String org = TenantContext.organizationId();
        Instant snapshot = null;
        long now = System.currentTimeMillis();
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT \"asOf\" FROM \"ReportApAgingSnapshot\" WHERE"
                + " \"organizationId\" = ? ORDER BY \"asOf\" DESC LIMIT 5").param(org))) {
            Instant at = (Instant) c.get("asOf");
            if (at.isAfter(requested) || Math.abs(now - at.toEpochMilli()) > FALLBACK_EPSILON_MS) {
                continue;
            }
            snapshot = at;
            break;
        }
        if (snapshot != null) {
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"ReportApAgingSnapshot\" WHERE"
                    + " \"organizationId\" = ? AND \"asOf\" = ? ORDER BY \"partnerName\" ASC").params(org, SqlRows.ts(snapshot)));
            Agg agg = aggregate(rows);
            return Obj.of("asOf", snapshot, "buckets", bucketsToString(agg.totals), "total", JsNumbers.dec(agg.grand),
                    "unapplied", JsNumbers.dec(agg.unapplied), "partners", agg.perPartner, "source", "snapshot");
        }
        // Purchase readiness B2 (owner-approved 2026-11-27): base currency — bills at their carrying rate, open
        // receipt-voucher payables (stocked lines, as the PO pay route) at the receipts' rate — less unapplied supplier
        // payments as a credit per partner, so the total ties to the payable control accounts (the A5 tie-out measure).
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> bill : SqlRows.list(jdbc.sql("SELECT b.\"partnerId\", b.\"partnerName\", b.\"dueDate\","
                + " b.\"issueDate\", b.base AS \"amountResidual\" FROM (" + ApOpenItemsSql.OPEN_BILLS + ") b UNION ALL"
                + " SELECT v.\"partnerId\", v.\"partnerName\", v.\"dueDate\", v.\"issueDate\", round(v.\"amountResidual\" *"
                + " v.rate, 2) FROM (" + ApOpenItemsSql.RECEIPT_VOUCHER_PAYABLES + ") v").params(org, org, org))) {
            Instant due = (Instant) (bill.get("dueDate") != null ? bill.get("dueDate") : bill.get("issueDate"));
            long days = Math.max(0, (long) Math.floor((requested.toEpochMilli() - due.toEpochMilli()) / 86_400_000.0));
            String bucket = days <= 0 ? "current" : days <= 30 ? "b1_30" : days <= 60 ? "b31_60" : days <= 90 ? "b61_90"
                    : "b90p";
            BigDecimal residual = Money.dec(bill.get("amountResidual"));
            String name = bill.get("partnerName") == null ? "" : (String) bill.get("partnerName");
            Map<String, Object> e = partnerRow(buckets, (String) bill.get("partnerId"), name);
            e.put(bucket, ((BigDecimal) e.get(bucket)).add(residual));
            e.put("total", ((BigDecimal) e.get("total")).add(residual));
            e.put("partnerName", name);
        }
        for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT u.\"partnerId\", max(pt.name) AS \"partnerName\","
                + " sum(u.base) AS base FROM (" + ApOpenItemsSql.UNAPPLIED_SUPPLIER_PAYMENTS + ") u LEFT JOIN \"Partner\" pt"
                + " ON pt.id = u.\"partnerId\" GROUP BY u.\"partnerId\"").params(org))) {
            Map<String, Object> e = partnerRow(buckets, (String) u.get("partnerId"),
                    u.get("partnerName") == null ? "" : (String) u.get("partnerName"));
            e.put("total", ((BigDecimal) e.get("total")).subtract(Money.dec(u.get("base"))));
        }
        Agg agg = aggregate(new ArrayList<>(buckets.values()));
        return Obj.of("asOf", asOf, "buckets", bucketsToString(agg.totals), "total", JsNumbers.dec(agg.grand),
                "unapplied", JsNumbers.dec(agg.unapplied), "partners", agg.perPartner, "source", "live");
    }

    private static Map<String, Object> partnerRow(Map<String, Map<String, Object>> rows, String partnerId, String name) {
        return rows.computeIfAbsent(partnerId, k -> {
            Map<String, Object> m = Obj.of("partnerId", k, "partnerName", name, "total", Money.ZERO);
            BUCKETS.forEach(b -> m.put(b, Money.ZERO));
            return m;
        });
    }

    private record Agg(Map<String, BigDecimal> totals, BigDecimal grand, BigDecimal unapplied,
            List<Map<String, Object>> perPartner) {}

    private static Agg aggregate(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BUCKETS.forEach(b -> totals.put(b, Money.ZERO));
        BigDecimal grand = Money.ZERO;
        BigDecimal unapplied = Money.ZERO;
        List<Map<String, Object>> per = new ArrayList<>();
        for (Map<String, Object> p : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            BigDecimal bucketed = Money.ZERO;
            for (String k : BUCKETS) {
                BigDecimal v = Money.dec(p.get(k));
                totals.merge(k, v, BigDecimal::add);
                bucketed = bucketed.add(v);
                b.put(k, JsNumbers.dec(v));
            }
            BigDecimal total = Money.dec(p.get("total"));
            // B2: unapplied supplier payments are the part of the partner's total no bucket holds (total = Σ − unapplied)
            BigDecimal partnerUnapplied = bucketed.subtract(total).max(Money.ZERO);
            unapplied = unapplied.add(partnerUnapplied);
            grand = grand.add(total);
            per.add(Obj.of("partnerId", p.get("partnerId"), "partnerName", p.get("partnerName"), "total",
                    JsNumbers.dec(total), "unapplied", JsNumbers.dec(partnerUnapplied), "buckets", b));
        }
        return new Agg(totals, grand, unapplied, per);
    }

    private static Map<String, Object> bucketsToString(Map<String, BigDecimal> t) {
        return Obj.of("current", Obj.of("label", "Current", "amount", JsNumbers.dec(t.get("current"))),
                "b1_30", Obj.of("label", "1-30 days", "amount", JsNumbers.dec(t.get("b1_30"))),
                "b31_60", Obj.of("label", "31-60 days", "amount", JsNumbers.dec(t.get("b31_60"))),
                "b61_90", Obj.of("label", "61-90 days", "amount", JsNumbers.dec(t.get("b61_90"))),
                "b90p", Obj.of("label", "90+ days", "amount", JsNumbers.dec(t.get("b90p"))));
    }
}
