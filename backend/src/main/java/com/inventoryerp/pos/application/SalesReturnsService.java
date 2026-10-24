package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales returns register (retail and café/restaurant). Read side over the immutable {@code PosRefund} events;
 * a return is created through {@link PosInvoiceService#refund} ({@code POST /pos/invoices/:id/refund}), which owns
 * the GL reversal, payout over the original tenders and the stock disposition.
 *
 * <p>Not part of the Nest reference — an explicitly labelled post-parity addition (see {@code docs/spec/pos.md}).
 */
@Service
@Transactional(readOnly = true)
public class SalesReturnsService {

    static final List<String> DISPOSITIONS = List.of("restock", "waste", "no_return");
    static final List<String> CHANNELS = List.of("retail", "restaurant");

    /** Café/restaurant when any line is a menu item; otherwise a retail (product) sale. */
    private static final String CHANNEL_SQL = "CASE WHEN EXISTS (SELECT 1 FROM \"InvoiceItem\" ci WHERE ci.\"invoiceId\" = i.id"
            + " AND ci.\"menuItemId\" IS NOT NULL) THEN 'restaurant' ELSE 'retail' END";
    private static final String USER_NAME_SQL = "(SELECT COALESCE(NULLIF(TRIM(COALESCE(u.\"firstName\", '') || ' '"
            + " || COALESCE(u.\"lastName\", '')), ''), u.email) FROM \"User\" u WHERE u.id = %s)";
    private static final String RETURN_NUMBER_SQL = "i.\"invoiceNumber\" || '-R' || (SELECT count(*) FROM \"PosRefund\" r2"
            + " WHERE r2.\"invoiceId\" = r.\"invoiceId\" AND r2.\"createdAt\" <= r.\"createdAt\")";

    private final JdbcClient jdbc;

    public SalesReturnsService(PosDb db) {
        this.jdbc = db.jdbc();
    }

    /** Paged returns register with filter totals. */
    public Map<String, Object> list(int page, int pageSize, String search, String stockDisposition, String channel,
            String dateFrom, String dateTo) {
        String orgId = org();
        StringBuilder where = new StringBuilder("r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (search != null && !search.isBlank()) {
            String q = PosSql.contains(search.trim());
            where.append(" AND (i.\"invoiceNumber\" ILIKE ? OR r.reason ILIKE ? OR p.name ILIKE ?)");
            params.addAll(List.of(q, q, q));
        }
        if (stockDisposition != null && DISPOSITIONS.contains(stockDisposition)) {
            where.append(" AND r.\"stockDisposition\" = ?");
            params.add(stockDisposition);
        }
        if (channel != null && CHANNELS.contains(channel)) {
            where.append(" AND ").append(CHANNEL_SQL).append(" = ?");
            params.add(channel);
        }
        LocalDate from = day(dateFrom);
        LocalDate to = day(dateTo);
        if (from != null) {
            where.append(" AND r.\"createdAt\" >= ?");
            params.add(SqlRows.ts(from.atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (to != null) {
            where.append(" AND r.\"createdAt\" <= ?");
            params.add(SqlRows.ts(to.atTime(LocalTime.of(23, 59, 59, 999_000_000)).atZone(BusinessZone.get())
                    .toInstant()));
        }
        String fromSql = " FROM \"PosRefund\" r JOIN \"Invoice\" i ON i.id = r.\"invoiceId\""
                + " LEFT JOIN \"Partner\" p ON p.id = i.\"partnerId\" WHERE " + where;
        List<Object> pp = new ArrayList<>(params);
        pp.add(pageSize);
        pp.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, " + RETURN_NUMBER_SQL + " AS \"returnNumber\","
                + " r.\"invoiceId\", i.\"invoiceNumber\", i.\"issueDate\" AS \"invoiceDate\", r.amount, r.reason,"
                + " r.\"stockDisposition\", " + CHANNEL_SQL + " AS channel, jsonb_array_length(r.items) AS \"itemCount\","
                + " p.id AS \"partnerId\", p.name AS \"partnerName\", r.\"cashSessionId\", r.\"journalEntryId\","
                + String.format(USER_NAME_SQL, "r.\"createdBy\"") + " AS \"createdByName\","
                + String.format(USER_NAME_SQL, "r.\"approvedById\"") + " AS \"approvedByName\", r.\"createdAt\""
                + fromSql + " ORDER BY r.\"createdAt\" DESC, r.id LIMIT ? OFFSET ?").params(pp));
        Map<String, Object> agg = SqlRows.one(jdbc.sql("SELECT count(*) AS total, COALESCE(sum(r.amount), 0) AS amount"
                + fromSql).params(params));
        long total = ((Number) agg.get("total")).longValue();
        rows.forEach(SalesReturnsService::nestPartner);
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize))),
                "summary", Obj.of("count", total, "amount", agg.get("amount")));
    }

    /** One return with its lines, payouts and the originating invoice. */
    public Map<String, Object> get(String id) {
        String orgId = org();
        Map<String, Object> ret = SqlRows.one(jdbc.sql("SELECT r.id, " + RETURN_NUMBER_SQL + " AS \"returnNumber\","
                + " r.\"invoiceId\", i.\"invoiceNumber\", i.\"issueDate\" AS \"invoiceDate\", i.\"totalAmount\" AS"
                + " \"invoiceTotal\", i.\"amountRefunded\" AS \"invoiceRefunded\", i.status::text AS \"invoiceStatus\","
                + " r.amount, r.reason, r.\"stockDisposition\", " + CHANNEL_SQL + " AS channel, p.id AS \"partnerId\","
                + " p.name AS \"partnerName\", r.\"cashSessionId\", cr.name AS \"cashRegisterName\", r.\"journalEntryId\","
                + " je.\"entryNumber\" AS \"journalEntryNumber\","
                + String.format(USER_NAME_SQL, "r.\"createdBy\"") + " AS \"createdByName\","
                + String.format(USER_NAME_SQL, "r.\"approvedById\"") + " AS \"approvedByName\", r.\"createdAt\""
                + " FROM \"PosRefund\" r JOIN \"Invoice\" i ON i.id = r.\"invoiceId\""
                + " LEFT JOIN \"Partner\" p ON p.id = i.\"partnerId\""
                + " LEFT JOIN \"CashSession\" cs ON cs.id = r.\"cashSessionId\""
                + " LEFT JOIN \"CashRegister\" cr ON cr.id = cs.\"cashRegisterId\""
                + " LEFT JOIN \"JournalEntry\" je ON je.id = r.\"journalEntryId\""
                + " WHERE r.id = ? AND r.\"organizationId\" = ?").params(id, orgId));
        if (ret == null) {
            throw ApiException.notFound("Return not found");
        }
        nestPartner(ret);
        ret.put("lines", SqlRows.list(jdbc.sql("SELECT e->>'lineId' AS \"lineId\", ii.description, ii.\"variantName\","
                + " ii.\"productId\", ii.\"menuItemId\", ii.\"unitPrice\", (e->>'quantity')::numeric AS quantity,"
                + " (e->>'subtotal')::numeric AS subtotal, (e->>'taxAmount')::numeric AS \"taxAmount\","
                + " (e->>'subtotal')::numeric + (e->>'taxAmount')::numeric AS total"
                + " FROM \"PosRefund\" r CROSS JOIN LATERAL jsonb_array_elements(r.items) WITH ORDINALITY AS t(e, n)"
                + " LEFT JOIN \"InvoiceItem\" ii ON ii.id = e->>'lineId' WHERE r.id = ? ORDER BY t.n").param(id)));
        ret.put("payments", SqlRows.list(jdbc.sql("SELECT e->>'paymentId' AS \"paymentId\", pay.\"paymentNumber\","
                + " pay.\"paymentMethod\", (e->>'amount')::numeric AS amount, op.\"paymentNumber\" AS \"originalPaymentNumber\""
                + " FROM \"PosRefund\" r CROSS JOIN LATERAL jsonb_array_elements(r.payments) WITH ORDINALITY AS t(e, n)"
                + " LEFT JOIN \"Payment\" pay ON pay.id = e->>'paymentId'"
                + " LEFT JOIN \"Payment\" op ON op.id = e->>'originalPaymentId' WHERE r.id = ? ORDER BY t.n").param(id)));
        return ret;
    }

    /** Settled sales that can still take a return, newest first (max 20). */
    public List<Map<String, Object>> returnableInvoices(String search) {
        String orgId = org();
        StringBuilder where = new StringBuilder("i.\"organizationId\" = ? AND i.status::text NOT IN ('refunded', 'cancelled',"
                + " 'draft') AND i.\"settlementStatus\"::text <> 'written_off' AND i.\"receivableAccountId\" IS NOT NULL"
                + " AND i.\"amountRefunded\" < i.\"totalAmount\"");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (search != null && !search.isBlank()) {
            String q = PosSql.contains(search.trim());
            where.append(" AND (i.\"invoiceNumber\" ILIKE ? OR p.name ILIKE ?)");
            params.addAll(List.of(q, q));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT i.id, i.\"invoiceNumber\", i.\"issueDate\","
                + " i.\"totalAmount\", i.\"amountRefunded\", i.\"paymentMode\"::text AS \"paymentMode\", " + CHANNEL_SQL
                + " AS channel, p.id AS \"partnerId\", p.name AS \"partnerName\" FROM \"Invoice\" i"
                + " LEFT JOIN \"Partner\" p ON p.id = i.\"partnerId\" WHERE " + where
                + " ORDER BY i.\"createdAt\" DESC LIMIT 20").params(params));
        rows.forEach(SalesReturnsService::nestPartner);
        return rows;
    }

    /** A sale with each line's remaining returnable quantity, its tenders and earlier returns. */
    public Map<String, Object> returnableInvoice(String invoiceId) {
        String orgId = org();
        Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT i.id, i.\"invoiceNumber\", i.\"issueDate\", i.status::text AS"
                + " status, i.\"settlementStatus\"::text AS \"settlementStatus\", i.\"totalAmount\", i.\"amountPaid\","
                + " i.\"amountResidual\", i.\"amountRefunded\", i.\"paymentMode\"::text AS \"paymentMode\","
                + " (i.\"receivableAccountId\" IS NOT NULL) AS \"postingReady\", " + CHANNEL_SQL + " AS channel,"
                + " p.id AS \"partnerId\", p.name AS \"partnerName\" FROM \"Invoice\" i"
                + " LEFT JOIN \"Partner\" p ON p.id = i.\"partnerId\" WHERE i.id = ? AND i.\"organizationId\" = ?")
                .params(invoiceId, orgId));
        if (inv == null) {
            throw ApiException.notFound("Invoice not found");
        }
        nestPartner(inv);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT ii.id, ii.description, ii.\"variantName\","
                + " ii.\"productId\", ii.\"menuItemId\", ii.quantity, ii.\"refundedQty\","
                + " ii.quantity - ii.\"refundedQty\" AS remaining, ii.\"unitPrice\", ii.subtotal, ii.\"taxAmount\", ii.total"
                + " FROM \"InvoiceItem\" ii WHERE ii.\"invoiceId\" = ? AND ii.\"organizationId\" = ?"
                + " ORDER BY ii.\"lineNumber\" ASC, ii.\"createdAt\" ASC").params(invoiceId, orgId));
        for (Map<String, Object> l : lines) {
            BigDecimal qty = SqlRows.dec(l, "quantity");
            BigDecimal gross = SqlRows.dec(l, "subtotal").add(SqlRows.dec(l, "taxAmount"));
            // Per-unit refund value, matching the refund engine's pro-rata (subtotal + tax) × qty / quantity.
            l.put("unitRefund", qty.signum() == 0 ? BigDecimal.ZERO : gross.divide(qty, 6, RoundingMode.HALF_UP));
        }
        inv.put("lines", lines);
        inv.put("tenders", SqlRows.list(jdbc.sql("SELECT p.\"paymentMethod\", sum(a.amount) AS amount,"
                + " sum(a.\"refundedAmount\") AS \"refundedAmount\" FROM \"PaymentAllocation\" a"
                + " JOIN \"Payment\" p ON p.id = a.\"paymentId\" WHERE a.\"invoiceId\" = ? AND a.\"organizationId\" = ?"
                + " AND p.direction::text = 'inbound' AND p.status::text <> 'cancelled' GROUP BY p.\"paymentMethod\""
                + " ORDER BY p.\"paymentMethod\"").params(invoiceId, orgId)));
        inv.put("returns", SqlRows.list(jdbc.sql("SELECT r.id, " + RETURN_NUMBER_SQL + " AS \"returnNumber\", r.amount,"
                + " r.reason, r.\"stockDisposition\", r.\"createdAt\" FROM \"PosRefund\" r JOIN \"Invoice\" i"
                + " ON i.id = r.\"invoiceId\" WHERE r.\"invoiceId\" = ? AND r.\"organizationId\" = ?"
                + " ORDER BY r.\"createdAt\" ASC").params(invoiceId, orgId)));
        return inv;
    }

    /** Open registers a cash refund can be paid out of. */
    public List<Map<String, Object>> openRegisters() {
        return SqlRows.list(jdbc.sql("SELECT cs.id, cs.\"cashRegisterId\", cr.name AS \"cashRegisterName\", cs.\"userId\","
                + String.format(USER_NAME_SQL, "cs.\"userId\"") + " AS \"userName\", cs.\"openedAt\""
                + " FROM \"CashSession\" cs JOIN \"CashRegister\" cr ON cr.id = cs.\"cashRegisterId\""
                + " WHERE cs.\"organizationId\" = ? AND cs.status::text = 'open' ORDER BY cr.name").param(org()));
    }

    private static void nestPartner(Map<String, Object> row) {
        Object partnerId = row.remove("partnerId");
        Object partnerName = row.remove("partnerName");
        row.put("partner", partnerId == null ? null : Obj.of("id", partnerId, "name", partnerName));
    }

    private static LocalDate day(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
