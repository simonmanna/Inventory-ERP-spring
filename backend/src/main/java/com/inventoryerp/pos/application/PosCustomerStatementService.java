package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Customer credit statement and open-credit worklist, derived from credit invoices, their allocations and write-off
 * journals (port of {@code PosCustomerStatementService} in {@code billing/pos-customer-statement.controller.ts}).
 */
@Service
public class PosCustomerStatementService {

    private final JdbcClient jdbc;

    public PosCustomerStatementService(PosDb db) {
        this.jdbc = db.jdbc();
    }

    private static Instant instant(Object v) {
        return v instanceof Timestamp t ? t.toInstant() : (Instant) v;
    }

    private static String iso(Object v) {
        return JsonConfig.toJsIso(instant(v));
    }

    public Map<String, Object> statement(String partnerId) {
        String orgId = org();
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT id, name, code FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(partnerId, orgId));
        if (partner == null) {
            throw ApiException.notFound("Customer not found");
        }
        List<Map<String, Object>> invoices = jdbc.sql("SELECT id, \"invoiceNumber\", \"issueDate\", \"createdAt\","
                + " \"totalAmount\" FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND \"paymentMode\" = 'credit'").params(orgId, partnerId).query().listOfRows();
        Map<String, Map<String, Object>> invById = new LinkedHashMap<>();
        invoices.forEach(i -> invById.put((String) i.get("id"), i));
        List<Object> ids = new ArrayList<>(invById.keySet());

        List<Map<String, Object>> allocations = new ArrayList<>();
        List<Map<String, Object>> writeOffs = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(ids);
            allocations = jdbc.sql("SELECT a.\"invoiceId\", a.amount, a.\"createdAt\", p.\"paymentNumber\","
                    + " p.\"paymentDate\", p.direction::text AS direction FROM \"PaymentAllocation\" a"
                    + " LEFT JOIN \"Payment\" p ON p.id = a.\"paymentId\" WHERE a.\"organizationId\" = ?"
                    + " AND a.\"invoiceId\" IN (" + PosSql.marks(ids.size()) + ")").params(params).query().listOfRows();
            writeOffs = jdbc.sql("SELECT \"sourceId\", \"postingDate\", \"createdAt\" FROM \"JournalEntry\""
                    + " WHERE \"organizationId\" = ? AND \"sourceType\" = 'pos_invoice_writeoff'"
                    + " AND \"sourceId\" IN (" + PosSql.marks(ids.size()) + ")").params(params).query().listOfRows();
        }

        Map<String, Double> paidByInvoice = new HashMap<>();
        for (Map<String, Object> a : allocations) {
            if (!inbound(a)) {
                continue;
            }
            paidByInvoice.merge((String) a.get("invoiceId"), PosSql.num(a.get("amount")), Double::sum);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> inv : invoices) {
            rows.add(entry(iso(inv.get("issueDate") != null ? inv.get("issueDate") : inv.get("createdAt")),
                    "credit_issue", inv.get("invoiceNumber"), inv, PosSql.num(inv.get("totalAmount"))));
        }
        for (Map<String, Object> a : allocations) {
            if (!inbound(a)) {
                continue;
            }
            Map<String, Object> inv = invById.get((String) a.get("invoiceId"));
            if (inv == null) {
                continue;
            }
            rows.add(entry(iso(a.get("paymentDate") != null ? a.get("paymentDate") : a.get("createdAt")), "payment",
                    a.get("paymentNumber") != null ? a.get("paymentNumber") : "—", inv, -PosSql.num(a.get("amount"))));
        }
        for (Map<String, Object> w : writeOffs) {
            Map<String, Object> inv = invById.get((String) w.get("sourceId"));
            if (inv == null) {
                continue;
            }
            double written = PosSql.num(inv.get("totalAmount")) - paidByInvoice.getOrDefault((String) inv.get("id"), 0.0);
            if (written <= 0.001) {
                continue;
            }
            rows.add(entry(iso(w.get("postingDate") != null ? w.get("postingDate") : w.get("createdAt")), "write_off",
                    inv.get("invoiceNumber"), inv, -written));
        }
        rows.sort(Comparator.comparing(r -> (String) r.get("date")));
        double running = 0;
        for (Map<String, Object> r : rows) {
            running = PosSql.round2(running + ((Number) r.get("amount")).doubleValue());
            r.put("amount", PosSql.js(r.get("amount")));
            r.put("runningBalance", PosSql.js(running));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partner", partner);
        out.putAll(creditStatus(partnerId));
        out.put("entries", rows);
        return out;
    }

    private static boolean inbound(Map<String, Object> a) {
        Object d = a.get("direction");
        return d == null || "inbound".equals(d);
    }

    private static Map<String, Object> entry(String date, String type, Object reference, Map<String, Object> inv,
            double amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("type", type);
        m.put("reference", reference);
        m.put("invoiceId", inv.get("id"));
        m.put("invoiceNumber", inv.get("invoiceNumber"));
        m.put("amount", amount);
        return m;
    }

    /** Credit limit / outstanding / headroom / hold for one customer. */
    public Map<String, Object> creditStatus(String partnerId) {
        return CreditStatus.resolve(jdbc, org(), partnerId, false).toMap();
    }

    /** Every open credit invoice, oldest first (the receivables worklist). */
    public Map<String, Object> openCredit(String partnerId, String search, Double pageIn, Double pageSizeIn) {
        String orgId = org();
        int page = (int) Math.max(1, pageIn == null || pageIn.isNaN() || pageIn == 0 ? 1 : pageIn);
        int pageSize = (int) Math.min(200, Math.max(1, pageSizeIn == null || pageSizeIn.isNaN() || pageSizeIn == 0
                ? 50 : pageSizeIn));
        String q = search == null ? null : search.trim();

        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND \"paymentMode\" = 'credit'"
                + " AND \"settlementStatus\"::text IN ('unsettled', 'partially_settled')");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND \"partnerId\" = ?");
            params.add(partnerId);
        }
        if (q != null && !q.isEmpty()) {
            List<String> nameMatches = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND name ILIKE ?").params(orgId, PosSql.contains(q))
                    .query(String.class).list();
            where.append(" AND (\"invoiceNumber\" ILIKE ? OR \"partnerId\" IN (").append(PosSql.marks(nameMatches.size()))
                    .append("))");
            params.add(PosSql.contains(q));
            params.addAll(nameMatches);
        }
        String w = where.toString();
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, \"invoiceNumber\", \"issueDate\", \"partnerId\","
                + " \"totalAmount\", \"amountPaid\", \"amountResidual\" FROM \"Invoice\" WHERE " + w
                + " ORDER BY \"issueDate\" ASC OFFSET " + ((long) (page - 1) * pageSize) + " LIMIT " + pageSize)
                .params(params).query().listOfRows();
        long total = jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE " + w).params(params).query(Long.class).single();
        Object sum = jdbc.sql("SELECT sum(\"amountResidual\") FROM \"Invoice\" WHERE " + w).params(params)
                .query(PosSql.SCALAR).optional().orElse(null);
        long customersOwing = jdbc.sql("SELECT count(*) FROM (SELECT DISTINCT \"partnerId\" FROM \"Invoice\" WHERE " + w
                + ") d").params(params).query(Long.class).single();
        Object oldest = jdbc.sql("SELECT \"issueDate\" FROM \"Invoice\" WHERE " + w + " ORDER BY \"issueDate\" ASC LIMIT 1")
                .params(params).query(PosSql.SCALAR).optional().orElse(null);

        Map<String, Object> nameById = new HashMap<>();
        if (!rows.isEmpty()) {
            LinkedHashSet<Object> pids = new LinkedHashSet<>();
            rows.forEach(r -> pids.add(r.get("partnerId")));
            List<Object> p = new ArrayList<>(List.of(orgId));
            p.addAll(pids);
            for (Map<String, Object> r : jdbc.sql("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND id IN (" + PosSql.marks(pids.size()) + ")").params(p).query()
                    .listOfRows()) {
                nameById.put((String) r.get("id"), r.get("name"));
            }
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", r.get("id"));
            m.put("invoiceNumber", r.get("invoiceNumber"));
            m.put("issueDate", iso(r.get("issueDate")));
            m.put("partnerId", r.get("partnerId"));
            m.put("partnerName", nameById.getOrDefault((String) r.get("partnerId"), "—"));
            m.put("totalAmount", PosSql.js(r.get("totalAmount")));
            m.put("amountPaid", PosSql.js(r.get("amountPaid")));
            m.put("amountResidual", PosSql.js(r.get("amountResidual")));
            m.put("daysOutstanding", days(now, r.get("issueDate")));
            out.add(m);
        }
        return Obj.of("rows", out, "total", total, "page", page, "pageSize", pageSize,
                "totalOutstanding", PosSql.js(PosSql.round2(PosSql.num(sum))), "customersOwing", customersOwing,
                "oldestDebtDays", oldest == null ? 0 : days(now, oldest));
    }

    private static long days(long now, Object d) {
        return Math.max(0, Math.floorDiv(now - instant(d).toEpochMilli(), 86_400_000L));
    }
}
