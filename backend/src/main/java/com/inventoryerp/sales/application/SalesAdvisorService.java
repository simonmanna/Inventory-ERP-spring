package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales advisor (ADR-S011 phase S10): read-only, explainable advisories computed from the ledgers — it never posts,
 * approves or changes anything. Rules (each advisory carries the numbers that triggered it):
 * <ul>
 *   <li>{@code churn_risk} — a customer's net revenue of the last 90 days fell below half of the 90 days before
 *       (prior period at least 1 000);</li>
 *   <li>{@code negative_margin} — invoice lines of the last 30 days sold below product cost;</li>
 *   <li>{@code discount_outlier} — order lines of the last 30 days whose discount is more than two standard deviations
 *       above the product's 180-day mean (at least five earlier lines);</li>
 *   <li>{@code quote_expiring} — sent quotations expiring within 7 days;</li>
 *   <li>{@code overdue_with_open_orders} — customers 30+ days overdue who still have undelivered orders;</li>
 *   <li>{@code agreement_expiring} — active agreements ending within 30 days with more than 20 % unreleased.</li>
 * </ul>
 */
@Service
public class SalesAdvisorService {

    private final SalesDb db;
    private final SalesRevenueQuery revenue;

    public SalesAdvisorService(SalesDb db, SalesRevenueQuery revenue) {
        this.db = db;
        this.revenue = revenue;
    }

    private SalesAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    private static Map<String, Object> advice(String kind, String severity, String title, String detail,
            String entityType, Object entityId, Object partnerId) {
        Map<String, Object> m = Obj.of("kind", kind, "severity", severity, "title", title, "detail", detail,
                "entityType", entityType, "entityId", entityId);
        m.put("partnerId", partnerId);
        return m;
    }

    private static String fmt(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> advisories() {
        access.assertAllBranches();
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        churn(out, today);
        if (access.canSeeCost()) {
            negativeMargin(out, today);
        }
        discountOutliers(out);
        for (Map<String, Object> q : db.rows("SELECT q.id, q.number, q.\"validUntil\", q.total, q.\"partnerId\", p.name"
                + " FROM \"SalesQuotation\" q LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" WHERE q.\"organizationId\" = ?"
                + " AND q.status = 'sent' AND q.\"deletedAt\" IS NULL AND q.\"validUntil\" BETWEEN CURRENT_DATE AND"
                + " CURRENT_DATE + 7 ORDER BY q.\"validUntil\"", org())) {
            out.add(advice("quote_expiring", "info", "Quotation " + q.get("number") + " expires " + q.get("validUntil"),
                    q.get("name") + " has not answered a quotation of " + fmt(dec(q.get("total"))) + "; follow up.",
                    "sales_quotation", q.get("id"), q.get("partnerId")));
        }
        for (Map<String, Object> r : db.rows("""
                SELECT p.id, p.name, sum(d."amountResidual") AS overdue,
                       (SELECT count(*) FROM "SalesOrder" o WHERE o."partnerId" = p.id AND o."organizationId" = p."organizationId"
                           AND o.status IN ('confirmed', 'in_fulfilment') AND o."deliveryStatus" <> 'full'
                           AND o."deletedAt" IS NULL) AS orders
                  FROM "Document" d JOIN "Partner" p ON p.id = d."partnerId"
                 WHERE d."organizationId" = ? AND d."documentType" = 'sales_invoice' AND d.status = 'posted'
                   AND d."amountResidual" > 0 AND d."dueDate" < CURRENT_DATE - 30
                 GROUP BY p.id, p.name""", org())) {
            if (((Number) r.get("orders")).longValue() > 0) {
                out.add(advice("overdue_with_open_orders", "warning", r.get("name") + " is 30+ days overdue with open"
                        + " orders", fmt(dec(r.get("overdue"))) + " overdue and " + r.get("orders") + " undelivered"
                                + " order(s); consider a credit hold before dispatch.", "partner", r.get("id"),
                        r.get("id")));
            }
        }
        for (Map<String, Object> a : db.rows("""
                SELECT a.id, a.number, a.name, a."endDate", a."partnerId",
                       sum(al."contractQty") AS contract,
                       COALESCE((SELECT sum((l.quantity - l."qtyCancelled") * l."uomFactor") FROM "SalesOrderLine" l
                                   JOIN "SalesOrder" o ON o.id = l."orderId" WHERE o."agreementId" = a.id
                                    AND o."deletedAt" IS NULL AND o.status IN ('confirmed', 'in_fulfilment', 'done')), 0)
                           AS released
                  FROM "SalesAgreement" a JOIN "SalesAgreementLine" al ON al."agreementId" = a.id
                 WHERE a."organizationId" = ? AND a.status = 'active' AND a."endDate" <= CURRENT_DATE + 30
                 GROUP BY a.id""", org())) {
            BigDecimal contract = dec(a.get("contract"));
            BigDecimal open = contract.subtract(dec(a.get("released")));
            if (contract.signum() > 0 && open.multiply(BigDecimal.valueOf(5)).compareTo(contract) > 0) {
                out.add(advice("agreement_expiring", "info", "Agreement " + a.get("number") + " ends " + a.get("endDate"),
                        fmt(open.multiply(SalesPricingEngine.HUNDRED).divide(contract, 0, RoundingMode.HALF_UP))
                                + "% of the contracted quantity is still unreleased; discuss an extension or the call-offs.",
                        "sales_agreement", a.get("id"), a.get("partnerId")));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> switch (str(m.get("severity"))) {
            case "critical" -> 0;
            case "warning" -> 1;
            default -> 2;
        }));
        return out;
    }

    private void churn(List<Map<String, Object>> out, LocalDate today) {
        Map<String, BigDecimal[]> by = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        LocalDate split = today.minusDays(90);
        for (Map<String, Object> l : revenue.invoiced(today.minusDays(180), today)) {
            BigDecimal[] a = by.computeIfAbsent(str(l.get("partnerId")), k -> new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO});
            boolean recent = ((LocalDate) l.get("date")).isAfter(split);
            a[recent ? 1 : 0] = a[recent ? 1 : 0].add(dec(l.get("revenue")));
        }
        for (Map.Entry<String, BigDecimal[]> e : by.entrySet()) {
            BigDecimal prior = e.getValue()[0];
            BigDecimal recent = e.getValue()[1];
            if (prior.compareTo(BigDecimal.valueOf(1000)) >= 0 && recent.multiply(BigDecimal.valueOf(2)).compareTo(prior)
                    < 0) {
                if (names.isEmpty()) {
                    db.rows("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ?", org())
                            .forEach(p -> names.put(str(p.get("id")), str(p.get("name"))));
                }
                BigDecimal drop = prior.subtract(recent).multiply(SalesPricingEngine.HUNDRED).divide(prior, 0,
                        RoundingMode.HALF_UP);
                out.add(advice("churn_risk", drop.compareTo(BigDecimal.valueOf(80)) >= 0 ? "warning" : "info",
                        names.get(e.getKey()) + " is buying " + drop.toPlainString() + "% less",
                        "Last 90 days " + fmt(recent) + " vs " + fmt(prior) + " the 90 days before; call the customer.",
                        "partner", e.getKey(), e.getKey()));
            }
        }
    }

    private void negativeMargin(List<Map<String, Object>> out, LocalDate today) {
        for (Map<String, Object> l : revenue.invoiced(today.minusDays(30), today)) {
            BigDecimal rev = dec(l.get("revenue"));
            BigDecimal cost = dec(l.get("cost"));
            if (rev.signum() > 0 && cost.compareTo(rev) > 0) {
                out.add(advice("negative_margin", "warning", "Sold below cost on " + l.get("number"), l.get("description")
                        + ": revenue " + fmt(rev) + ", cost " + fmt(cost) + "; review the price or the cost.",
                        "document", l.get("documentId"), l.get("partnerId")));
            }
        }
    }

    private void discountOutliers(List<Map<String, Object>> out) {
        for (Map<String, Object> r : db.rows("""
                WITH hist AS (
                  SELECT l."productId", avg(l."discountPercent") AS mean, stddev_samp(l."discountPercent") AS sd,
                         count(*) AS n
                    FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
                   WHERE o."organizationId" = ? AND o.status <> 'cancelled' AND o."deletedAt" IS NULL
                     AND o."createdAt" >= CURRENT_DATE - 180 AND o."createdAt" < CURRENT_DATE - 30
                     AND l."productId" IS NOT NULL
                   GROUP BY l."productId")
                SELECT o.id, o.number, o."partnerId", l.description, l."discountPercent", h.mean, h.sd
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
                  JOIN hist h ON h."productId" = l."productId"
                 WHERE o."organizationId" = ? AND o.status <> 'cancelled' AND o."deletedAt" IS NULL
                   AND o."createdAt" >= CURRENT_DATE - 30 AND h.n >= 5
                   AND l."discountPercent" > h.mean + 2 * COALESCE(h.sd, 0) AND l."discountPercent" > 0""", org(),
                org())) {
            out.add(advice("discount_outlier", "info", "Unusual discount on " + r.get("number"), r.get("description")
                    + " at " + JsNumbers.js(dec(r.get("discountPercent"))) + "% vs a usual "
                    + dec(r.get("mean")).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%.", "sales_order",
                    r.get("id"), r.get("partnerId")));
        }
    }
}
