package com.inventoryerp.invoicing.application;

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

/** AP aging (port of {@code ap-aging.service.ts}): a fresh snapshot (≤ 60 s old, ≤ asOf) when present, else live. */
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
                    "partners", agg.perPartner, "source", "snapshot");
        }
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> bill : SqlRows.list(jdbc.sql("SELECT d.\"partnerId\", p.name AS \"partnerName\","
                + " d.\"dueDate\", d.\"issueDate\", d.\"amountResidual\" FROM \"Document\" d LEFT JOIN \"Partner\" p"
                + " ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\" = 'vendor_bill'"
                + " AND d.status IN ('posted', 'paid') AND d.\"amountResidual\" > 0").param(org))) {
            Instant due = (Instant) (bill.get("dueDate") != null ? bill.get("dueDate") : bill.get("issueDate"));
            long days = Math.max(0, (long) Math.floor((requested.toEpochMilli() - due.toEpochMilli()) / 86_400_000.0));
            String bucket = days <= 0 ? "current" : days <= 30 ? "b1_30" : days <= 60 ? "b31_60" : days <= 90 ? "b61_90"
                    : "b90p";
            BigDecimal residual = Money.dec(bill.get("amountResidual"));
            String name = bill.get("partnerName") == null ? "" : (String) bill.get("partnerName");
            Map<String, Object> e = buckets.computeIfAbsent((String) bill.get("partnerId"), k -> {
                Map<String, Object> m = Obj.of("partnerId", k, "partnerName", name, "total", Money.ZERO);
                BUCKETS.forEach(b -> m.put(b, Money.ZERO));
                return m;
            });
            e.put(bucket, ((BigDecimal) e.get(bucket)).add(residual));
            e.put("total", ((BigDecimal) e.get("total")).add(residual));
            e.put("partnerName", name);
        }
        Agg agg = aggregate(new ArrayList<>(buckets.values()));
        return Obj.of("asOf", asOf, "buckets", bucketsToString(agg.totals), "total", JsNumbers.dec(agg.grand), "partners",
                agg.perPartner, "source", "live");
    }

    private record Agg(Map<String, BigDecimal> totals, BigDecimal grand, List<Map<String, Object>> perPartner) {}

    private static Agg aggregate(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BUCKETS.forEach(b -> totals.put(b, Money.ZERO));
        BigDecimal grand = Money.ZERO;
        List<Map<String, Object>> per = new ArrayList<>();
        for (Map<String, Object> p : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            for (String k : BUCKETS) {
                BigDecimal v = Money.dec(p.get(k));
                totals.merge(k, v, BigDecimal::add);
                b.put(k, JsNumbers.dec(v));
            }
            grand = grand.add(Money.dec(p.get("total")));
            per.add(Obj.of("partnerId", p.get("partnerId"), "partnerName", p.get("partnerName"), "total",
                    JsNumbers.dec(Money.dec(p.get("total"))), "buckets", b));
        }
        return new Agg(totals, grand, per);
    }

    private static Map<String, Object> bucketsToString(Map<String, BigDecimal> t) {
        return Obj.of("current", Obj.of("label", "Current", "amount", JsNumbers.dec(t.get("current"))),
                "b1_30", Obj.of("label", "1-30 days", "amount", JsNumbers.dec(t.get("b1_30"))),
                "b31_60", Obj.of("label", "31-60 days", "amount", JsNumbers.dec(t.get("b31_60"))),
                "b61_90", Obj.of("label", "61-90 days", "amount", JsNumbers.dec(t.get("b61_90"))),
                "b90p", Obj.of("label", "90+ days", "amount", JsNumbers.dec(t.get("b90p"))));
    }
}
