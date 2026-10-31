package com.inventoryerp.invoicing.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales analysis (SALES-5): net sales of posted ERP and POS invoices, grouped by product, category, branch, customer,
 * salesperson or month, with common filters. Read-only and limited to a restricted user's branches. Amounts are net of
 * tax and discount; credit notes are not deducted.
 */
@Service
public class SalesAnalysisService {

    /** Grouping key and label per {@code groupBy}; the values are fixed strings, never request data. */
    private static final Map<String, String[]> GROUPS = Map.of(
            "product", new String[] {"COALESCE(s.\"productId\", s.description)", "COALESCE(p.name, s.description)"},
            "category", new String[] {"COALESCE(p.\"categoryId\", 'none')", "COALESCE(c.name, 'No category')"},
            "branch", new String[] {"COALESCE(s.\"branchId\", 'none')", "COALESCE(b.name, 'No branch')"},
            "customer", new String[] {"COALESCE(s.\"partnerId\", 'none')", "COALESCE(pa.name, 'Walk-in')"},
            "salesperson", new String[] {"COALESCE(s.\"salespersonId\", 'none')",
                "COALESCE(NULLIF(trim(concat(e.\"firstName\", ' ', e.\"lastName\")), ''), 'No salesperson')"},
            "month", new String[] {"to_char(s.day, 'YYYY-MM')", "to_char(s.day, 'YYYY-MM')"});

    private static final String LINES = """
            SELECT l."productId", l.description, d."partnerId", d."branchId", d."salespersonId", d."issueDate" AS day,
                   l.quantity, l.subtotal
              FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
             WHERE d."organizationId" = ? AND d."documentType"::text = 'sales_invoice'
               AND d.status::text IN ('posted', 'paid') AND l."lineType" = 'product'
            UNION ALL
            SELECT i."productId", i.description, v."partnerId", v."branchId", NULL, v."issueDate", i.quantity, i.subtotal
              FROM "InvoiceItem" i JOIN "Invoice" v ON v.id = i."invoiceId"
             WHERE v."organizationId" = ? AND v.status::text IN ('posted', 'paid')""";

    /** {@code GET /reports/ar/sales-analysis} filters; all optional. */
    public record Filter(String groupBy, String from, String to, String branchId, String productId, String partnerId,
            String departmentId) {}

    private final JdbcClient jdbc;
    private final BranchScopeService scopes;

    public SalesAnalysisService(JdbcClient jdbc, BranchScopeService scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analyse(Filter f) {
        String groupBy = has(f.groupBy()) ? f.groupBy() : "product";
        String[] group = GROUPS.get(groupBy);
        if (group == null) {
            throw ApiException.badRequest("groupBy must be one of: product, category, branch, customer, salesperson, month");
        }
        String org = TenantContext.organizationId();
        List<Object> params = new ArrayList<>(List.of(org, org));
        StringBuilder where = new StringBuilder(" WHERE true");
        if (has(f.from())) {
            where.append(" AND s.day >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(f.from())));
        }
        if (has(f.to())) {
            where.append(" AND s.day < CAST(? AS timestamp) + interval '1 day'");
            params.add(SqlRows.ts(EntityPatch.toInstant(f.to())));
        }
        if (has(f.branchId())) {
            where.append(" AND s.\"branchId\" = ?");
            params.add(f.branchId());
        }
        if (has(f.productId())) {
            where.append(" AND s.\"productId\" = ?");
            params.add(f.productId());
        }
        if (has(f.partnerId())) {
            where.append(" AND s.\"partnerId\" = ?");
            params.add(f.partnerId());
        }
        if (has(f.departmentId())) {
            where.append(" AND e.\"departmentId\" = ?");
            params.add(f.departmentId());
        }
        DataScope scope = scopes.scope();
        if (scope.restricted()) {
            where.append(" AND s.\"branchId\" = ANY(?::text[])");
            params.add(scope.branchArray());
        }
        List<Map<String, Object>> found = SqlRows.list(jdbc.sql("SELECT " + group[0] + " AS key, " + group[1] + " AS label,"
                + " COALESCE(sum(s.quantity), 0) AS quantity, COALESCE(sum(s.subtotal), 0) AS amount FROM (" + LINES + ") s"
                + " LEFT JOIN \"Product\" p ON p.id = s.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " p.\"categoryId\" LEFT JOIN \"Branch\" b ON b.id = s.\"branchId\" LEFT JOIN \"Partner\" pa ON pa.id ="
                + " s.\"partnerId\" LEFT JOIN \"HrEmployee\" e ON e.id = s.\"salespersonId\"" + where
                + " GROUP BY 1, 2 ORDER BY amount DESC LIMIT 1000").params(params));
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        for (Map<String, Object> r : found) {
            total = total.add(SqlRows.dec(r, "amount"));
            quantity = quantity.add(SqlRows.dec(r, "quantity"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : found) {
            BigDecimal amount = SqlRows.dec(r, "amount");
            rows.add(Obj.of("key", r.get("key"), "label", r.get("label"), "quantity",
                    JsNumbers.js(SqlRows.dec(r, "quantity")), "amount", JsNumbers.js(amount), "sharePercent",
                    total.signum() == 0 ? 0 : JsNumbers.js(amount.multiply(BigDecimal.valueOf(100))
                            .divide(total, 2, RoundingMode.HALF_UP))));
        }
        return Obj.of("groupBy", groupBy, "rows", rows, "totalAmount", JsNumbers.js(total), "totalQuantity",
                JsNumbers.js(quantity));
    }
}
