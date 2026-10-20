package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts-receivable reports (port of {@code ar-reporting.service.ts}): aging, customer balances, outstanding
 * invoices (manual documents + POS invoices), revenue by customer and by month.
 */
@Service
@Transactional(readOnly = true)
public class ArReportingService {

    private static final Collator COLLATOR = Collator.getInstance(Locale.ENGLISH);

    private final JdbcClient jdbc;

    public ArReportingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Open documents + POS invoices, each with {@code documentNumber} and {@code partnerName}. */
    private List<Map<String, Object>> open(boolean ordered) {
        List<Map<String, Object>> out = new ArrayList<>(SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentNumber\","
                + " d.\"partnerId\", p.name AS \"partnerName\", d.\"issueDate\", d.\"dueDate\", d.\"totalAmount\","
                + " d.\"amountResidual\", d.\"paymentStatus\"::text AS \"paymentStatus\" FROM \"Document\" d"
                + " JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\" = 'sales_invoice' AND d.status IN ('posted', 'paid') AND d.\"amountResidual\" > 0"
                + (ordered ? " ORDER BY d.\"dueDate\" ASC NULLS LAST" : "")).param(org())));
        List<Map<String, Object>> invs = SqlRows.list(jdbc.sql("SELECT i.id, i.\"invoiceNumber\" AS \"documentNumber\","
                + " i.\"partnerId\", i.\"issueDate\", i.\"dueDate\", i.\"totalAmount\", i.\"amountResidual\","
                + " i.\"paymentStatus\"::text AS \"paymentStatus\" FROM \"Invoice\" i WHERE i.\"organizationId\" = ?"
                + " AND i.status IN ('posted', 'paid') AND i.\"amountResidual\" > 0 ORDER BY i.\"dueDate\" ASC NULLS LAST")
                .param(org()));
        if (!invs.isEmpty()) {
            Map<String, String> names = new HashMap<>();
            for (Map<String, Object> p : jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                    .param("ids", invs.stream().map(i -> (String) i.get("partnerId")).distinct().toList())
                    .param("org", org()).query().listOfRows()) {
                names.put((String) p.get("id"), (String) p.get("name"));
            }
            for (Map<String, Object> i : invs) {
                i.put("partnerName", names.getOrDefault((String) i.get("partnerId"), "Unknown"));
            }
            out.addAll(invs);
        }
        return out;
    }

    /** AR aging by due date relative to {@code asOf} (default now). */
    public Map<String, Object> aging(String asOfStr) {
        Instant asOf = asOfStr != null && !asOfStr.isEmpty() ? EntityPatch.toInstant(asOfStr) : Instant.now();
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        for (String b : List.of("current", "d1_30", "d31_60", "d61_90", "d90_plus")) {
            buckets.put(b, Money.ZERO);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = Money.ZERO;
        for (Map<String, Object> d : open(true)) {
            Instant due = (Instant) (d.get("dueDate") != null ? d.get("dueDate") : d.get("issueDate"));
            long days = (long) Math.floor((asOf.toEpochMilli() - due.toEpochMilli()) / 86_400_000.0);
            String bucket = days <= 0 ? "current" : days <= 30 ? "d1_30" : days <= 60 ? "d31_60" : days <= 90 ? "d61_90"
                    : "d90_plus";
            BigDecimal residual = Money.dec(d.get("amountResidual"));
            buckets.merge(bucket, residual, BigDecimal::add);
            total = total.add(residual);
            rows.add(Obj.of("documentId", d.get("id"), "documentNumber", d.get("documentNumber"), "partnerName",
                    d.get("partnerName"), "dueDate", due, "daysOverdue", Math.max(0, days), "residual",
                    JsNumbers.dec(residual), "bucket", bucket));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        buckets.forEach((k, v) -> b.put(k, JsNumbers.dec(v)));
        return Obj.of("asOf", asOf, "buckets", b, "total", JsNumbers.dec(total), "rows", rows);
    }

    /** Outstanding per customer, by name. */
    public List<Map<String, Object>> customerBalances() {
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Map<String, Object> d : open(false)) {
            Object[] cur = map.computeIfAbsent((String) d.get("partnerId"), k -> new Object[] {d.get("partnerName"),
                    Money.ZERO});
            cur[1] = ((BigDecimal) cur[1]).add(Money.dec(d.get("amountResidual")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(Obj.of("partnerId", k, "partnerName", v[0], "outstanding",
                JsNumbers.dec((BigDecimal) v[1]))));
        out.sort((a, c) -> COLLATOR.compare(String.valueOf(a.get("partnerName")), String.valueOf(c.get("partnerName"))));
        return out;
    }

    /** Every open invoice. */
    public List<Map<String, Object>> outstandingInvoices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : open(true)) {
            out.add(Obj.of("documentId", d.get("id"), "documentNumber", d.get("documentNumber"), "partnerName",
                    d.get("partnerName"), "issueDate", d.get("issueDate"), "dueDate", d.get("dueDate"), "total",
                    JsNumbers.dec(Money.dec(d.get("totalAmount"))), "residual",
                    JsNumbers.dec(Money.dec(d.get("amountResidual"))), "paymentStatus", d.get("paymentStatus")));
        }
        return out;
    }

    private List<Map<String, Object>> posted(String from, String to) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" d.\"organizationId\" = ? AND d.\"documentType\" = 'sales_invoice'"
                + " AND d.status IN ('posted', 'paid')");
        if (from != null && !from.isEmpty()) {
            where.append(" AND d.\"issueDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(from)));
        }
        if (to != null && !to.isEmpty()) {
            where.append(" AND d.\"issueDate\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(to)));
        }
        return SqlRows.list(jdbc.sql("SELECT d.\"partnerId\", p.name AS \"partnerName\", d.subtotal, d.\"issueDate\""
                + " FROM \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE" + where).params(params));
    }

    /** Revenue (subtotal) per customer. */
    public List<Map<String, Object>> revenueByCustomer(String from, String to) {
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Map<String, Object> d : posted(from, to)) {
            Object[] cur = map.computeIfAbsent((String) d.get("partnerId"), k -> new Object[] {d.get("partnerName"),
                    Money.ZERO});
            cur[1] = ((BigDecimal) cur[1]).add(Money.dec(d.get("subtotal")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(Obj.of("partnerId", k, "partnerName", v[0], "revenue",
                JsNumbers.dec((BigDecimal) v[1]))));
        out.sort((a, c) -> COLLATOR.compare(String.valueOf(a.get("partnerName")), String.valueOf(c.get("partnerName"))));
        return out;
    }

    /** Revenue (subtotal) per UTC month. */
    public List<Map<String, Object>> revenueByPeriod(String from, String to) {
        Map<String, BigDecimal> map = new TreeMap<>();
        for (Map<String, Object> d : posted(from, to)) {
            String key = com.inventoryerp.kernel.web.JsonConfig.toJsIso((Instant) d.get("issueDate")).substring(0, 7);
            map.merge(key, Money.dec(d.get("subtotal")), BigDecimal::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(Obj.of("period", k, "revenue", JsNumbers.dec(v))));
        return out;
    }
}
