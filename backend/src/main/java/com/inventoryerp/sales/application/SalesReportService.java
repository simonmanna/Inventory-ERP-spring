package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational sales reports of release R1 (ADR-S011): quotation pipeline by status, pending approvals, open orders,
 * orders awaiting fulfilment, held orders, quotation → order conversion and monthly confirmed orders.
 */
@Service
public class SalesReportService {

    private final SalesDb db;
    private final SalesAccess access;

    public SalesReportService(SalesDb db, SalesAccess access) {
        this.db = db;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        access.assertAllBranches();
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("SELECT status, count(*) AS count, COALESCE(sum(total), 0) AS total FROM"
                + " \"SalesQuotation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL GROUP BY status", org())) {
            quotes.put(str(r.get("status")), Obj.of("count", r.get("count"), "total", r.get("total")));
        }
        long pending = db.count("SELECT (SELECT count(*) FROM \"SalesQuotation\" WHERE \"organizationId\" = ? AND"
                + " status = 'pending_approval' AND \"deletedAt\" IS NULL) + (SELECT count(*) FROM"
                + " \"SalesOrderChangeRequest\" WHERE \"organizationId\" = ? AND status = 'pending_approval') + (SELECT"
                + " count(*) FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND status = 'pending_approval' AND"
                + " \"deletedAt\" IS NULL)", List.of(org(), org(), org()));
        Map<String, Object> open = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS total FROM"
                + " \"SalesOrder\" WHERE \"organizationId\" = ? AND status IN ('confirmed', 'in_fulfilment') AND"
                + " \"deletedAt\" IS NULL", org());
        Map<String, Object> toFulfil = db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS total FROM"
                + " \"SalesOrder\" WHERE \"organizationId\" = ? AND status IN ('confirmed', 'in_fulfilment') AND"
                + " \"deliveryStatus\" <> 'full' AND NOT \"onHold\" AND \"deletedAt\" IS NULL", org());
        long held = db.count("SELECT count(*) FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND \"onHold\" AND"
                + " \"deletedAt\" IS NULL", List.of(org()));
        Map<String, Object> conv = db.row("SELECT count(*) FILTER (WHERE status NOT IN ('draft', 'cancelled')) AS quotes,"
                + " count(*) FILTER (WHERE status = 'converted') AS converted FROM \"SalesQuotation\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"issueDate\" >= CURRENT_DATE - 90", org());
        long q = ((Number) conv.get("quotes")).longValue();
        long c = ((Number) conv.get("converted")).longValue();
        BigDecimal rate = q == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(c * 100L).divide(BigDecimal.valueOf(q), 1, RoundingMode.HALF_UP);
        List<Map<String, Object>> monthly = db.rows("SELECT to_char(date_trunc('month', \"confirmedAt\"), 'YYYY-MM') AS"
                + " month, count(*) AS orders, COALESCE(sum(total), 0) AS total FROM \"SalesOrder\" WHERE"
                + " \"organizationId\" = ? AND \"confirmedAt\" >= date_trunc('month', now()) - interval '11 months' AND"
                + " status <> 'cancelled' AND \"deletedAt\" IS NULL GROUP BY 1 ORDER BY 1", org());
        return Obj.of("quotations", quotes, "pendingApprovals", pending, "openOrders", open, "toFulfil", toFulfil,
                "onHold", held, "conversion", Obj.of("quotes", q, "converted", c, "rate", JsNumbers.js(rate)),
                "monthly", monthly);
    }
}
