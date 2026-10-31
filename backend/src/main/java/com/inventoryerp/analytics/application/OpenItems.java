package com.inventoryerp.analytics.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Open receivables and payables per partner, aged by due date (issue date when there is none), with the branch and
 * partner filters the AR / AP aging reports do not take. Same document set as those reports.
 */
@Component
public class OpenItems {

    private static final String BUCKETS = """
            COALESCE(SUM(x.residual), 0) AS total,
            COALESCE(SUM(x.residual) FILTER (WHERE x.overdue <= 0), 0) AS "notDue",
            COALESCE(SUM(x.residual) FILTER (WHERE x.overdue BETWEEN 1 AND 30), 0) AS d1_30,
            COALESCE(SUM(x.residual) FILTER (WHERE x.overdue BETWEEN 31 AND 60), 0) AS d31_60,
            COALESCE(SUM(x.residual) FILTER (WHERE x.overdue BETWEEN 61 AND 90), 0) AS d61_90,
            COALESCE(SUM(x.residual) FILTER (WHERE x.overdue > 90), 0) AS d90_plus
            """;

    private static final String RECEIVABLES = """
            SELECT d."partnerId", d."branchId", COALESCE(d."dueDate", d."issueDate") AS due,
                   d."amountResidual" AS residual
              FROM "Document" d
             WHERE d."organizationId" = ? AND d."documentType"::text = 'sales_invoice'
               AND d.status::text IN ('posted', 'paid') AND d."amountResidual" > 0
            UNION ALL
            SELECT i."partnerId", i."branchId", COALESCE(i."dueDate", i."issueDate"), i."amountResidual"
              FROM "Invoice" i
             WHERE i."organizationId" = ? AND i.status::text IN ('posted', 'paid') AND i."amountResidual" > 0""";

    private static final String PAYABLES = """
            SELECT d."partnerId", d."branchId", COALESCE(d."dueDate", d."issueDate") AS due,
                   d."amountResidual" AS residual
              FROM "Document" d
             WHERE d."organizationId" = ? AND d."documentType"::text = 'vendor_bill'
               AND d.status::text IN ('posted', 'paid') AND d."amountResidual" > 0""";

    private final JdbcClient jdbc;

    public OpenItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per customer, largest balance first. */
    public List<Map<String, Object>> receivables(AnalyticsView v, String partnerId) {
        String org = TenantContext.organizationId();
        return aged(RECEIVABLES, new ArrayList<>(List.of(org, org)), v, partnerId);
    }

    /** One row per supplier, largest balance first. */
    public List<Map<String, Object>> payables(AnalyticsView v, String partnerId) {
        return aged(PAYABLES, new ArrayList<>(List.of(TenantContext.organizationId())), v, partnerId);
    }

    private List<Map<String, Object>> aged(String items, List<Object> itemParams, AnalyticsView v, String partnerId) {
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(LocalDate.now(BusinessZone.get())));
        params.addAll(itemParams);
        StringBuilder where = new StringBuilder(" WHERE true");
        v.branch(where, params, "x.\"branchId\"");
        AnalyticsView.equal(where, params, "x.\"partnerId\"", partnerId);
        return SqlRows.list(jdbc.sql("SELECT x.\"partnerId\", COALESCE(pa.name, 'Walk-in') AS \"partnerName\", "
                + BUCKETS + " FROM (SELECT y.*, (?::date - y.due::date) AS overdue FROM (" + items + ") y) x"
                + " LEFT JOIN \"Partner\" pa ON pa.id = x.\"partnerId\"" + where
                + " GROUP BY x.\"partnerId\", pa.name ORDER BY total DESC").params(params));
    }

    /** Aging chart rows from the partner rows. */
    public static List<Map<String, Object>> agingBuckets(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String b : List.of("notDue", "d1_30", "d31_60", "d61_90", "d90_plus")) {
            out.add(Obj.of("bucket", b, "amount", KpiMath.sum(rows, b)));
        }
        return out;
    }
}
