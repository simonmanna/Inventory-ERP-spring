package com.inventoryerp.dms.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Record types documents can be linked to: module beans ({@link DmsResourceProvider}) plus table-backed built-ins for
 * the core ERP records. The built-ins only read the record's label (a query, never a write) so the DMS does not
 * depend on the owning modules.
 */
@Component
public class DmsResourceRegistry {

    private final Map<String, DmsResourceProvider> providers = new LinkedHashMap<>();

    public DmsResourceRegistry(JdbcClient jdbc, List<DmsResourceProvider> beans) {
        for (DmsResourceProvider p : builtIns(jdbc)) {
            providers.put(p.resType(), p);
        }
        for (DmsResourceProvider p : beans) {
            providers.put(p.resType(), p);
        }
    }

    /** The provider of {@code resType}; 400 when unknown. */
    public DmsResourceProvider get(String resType) {
        DmsResourceProvider p = resType == null ? null : providers.get(resType);
        if (p == null) {
            throw ApiException.badRequest("Unknown record type: " + resType);
        }
        return p;
    }

    public DmsResourceProvider find(String resType) {
        return resType == null ? null : providers.get(resType);
    }

    public List<DmsResourceProvider> all() {
        return new ArrayList<>(providers.values());
    }

    /** The record must exist in the current organization (404 otherwise). */
    public DmsResourceProvider requireRecord(String resType, String resId) {
        DmsResourceProvider p = get(resType);
        if (resId == null || resId.isBlank() || p.displayName(resId) == null) {
            throw ApiException.notFound("Record not found");
        }
        return p;
    }

    public static boolean canRead(DmsResourceProvider p) {
        return p.readPermission() == null || TenantContext.has(p.readPermission());
    }

    public static boolean canWrite(DmsResourceProvider p) {
        return p.writePermission() == null || TenantContext.has(p.writePermission());
    }

    /** Built-in record types (label = one SQL expression over the owning table). */
    static List<DmsResourceProvider> builtIns(JdbcClient jdbc) {
        return List.of(
                new TableResource(jdbc, "crm_deal", "Opportunity", Permissions.CRM__DEAL_READ,
                        Permissions.CRM__DEAL_WRITE, "crm", "SELECT name FROM \"Deal\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", "/crm/opportunities/"),
                new TableResource(jdbc, "crm_case", "Case", Permissions.CRM__CASE_READ, Permissions.CRM__CASE_WRITE,
                        "crm", "SELECT code || ' ' || subject FROM \"CrmCase\" WHERE id = ? AND \"organizationId\" = ?",
                        "/crm/cases/"),
                new TableResource(jdbc, "sales_quotation", "Sales quotation", Permissions.SALES__QUOTATION_READ,
                        Permissions.SALES__QUOTATION_WRITE, "sales", "SELECT number FROM \"SalesQuotation\" WHERE"
                        + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", null),
                new TableResource(jdbc, "sales_order", "Sales order", Permissions.SALES__ORDER_READ,
                        Permissions.SALES__ORDER_WRITE, "sales", "SELECT number FROM \"SalesOrder\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", "/sales/orders/"),
                new TableResource(jdbc, "purchase_order", "Purchase order",
                        Permissions.PROCUREMENT__PURCHASE_ORDER__READ, Permissions.PROCUREMENT__PURCHASE_ORDER__UPDATE,
                        "purchase", "SELECT \"orderNumber\" FROM \"PurchaseOrder\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", "/procurement/purchase-orders/"),
                new TableResource(jdbc, "rfq", "Request for quotation", Permissions.RFQ__READ, Permissions.RFQ__MANAGE,
                        "purchase", "SELECT title FROM \"Rfq\" WHERE id = ? AND \"organizationId\" = ?", null),
                new TableResource(jdbc, "expense", "Expense", Permissions.EXPENSE__READ, Permissions.EXPENSE__UPDATE,
                        "expenses", "SELECT title FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL", "/expenses?open="),
                new TableResource(jdbc, "vendor_bill", "Vendor bill", Permissions.EXPENSE__READ,
                        Permissions.EXPENSE__UPDATE, "purchase", "SELECT coalesce(\"documentNumber\", reference, id)"
                        + " FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?", "/vendor-bills/"),
                new TableResource(jdbc, "invoice", "Invoice", Permissions.INVOICE__READ, Permissions.INVOICE__UPDATE,
                        "finance", "SELECT label FROM (SELECT coalesce(\"documentNumber\", reference, id) AS label"
                        + " FROM \"Document\" WHERE id = ?1 AND \"organizationId\" = ?2 UNION ALL"
                        + " SELECT coalesce(\"invoiceNumber\", reference, id) FROM \"Invoice\" WHERE id = ?1 AND"
                        + " \"organizationId\" = ?2) x LIMIT 1", "/invoices/"),
                new TableResource(jdbc, "partner", "Partner", Permissions.PARTNER__READ, Permissions.PARTNER__UPDATE,
                        "general", "SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL", "/crm/customers/"),
                new TableResource(jdbc, "product", "Product", Permissions.PRODUCT__READ, Permissions.PRODUCT__UPDATE,
                        "general", "SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL", "/products/"),
                new TableResource(jdbc, "employee", "Employee", Permissions.HR__READ, Permissions.HR__EMPLOYEE, "hr",
                        "SELECT trim(coalesce(\"firstName\", '') || ' ' || coalesce(\"lastName\", '')) FROM"
                        + " \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                        "/hr/employees/"),
                new TableResource(jdbc, "asset", "Fixed asset", Permissions.FIXED_ASSET__READ,
                        Permissions.FIXED_ASSET__UPDATE, "finance", "SELECT name FROM \"Asset\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", "/fixed-assets/"));
    }

    /** A record type whose label comes from one SQL query ({@code ?1}/{@code ?} = id, {@code ?2}/{@code ?} = org). */
    record TableResource(JdbcClient jdbc, String resType, String label, String readPermission, String writePermission,
            String workspaceCode, String labelSql, String pathPrefix) implements DmsResourceProvider {

        @Override
        public String displayName(String resId) {
            String sql = labelSql.replace("?1", ":id").replace("?2", ":org");
            JdbcClient.StatementSpec spec;
            if (sql.contains(":id")) {
                spec = jdbc.sql(sql).param("id", resId).param("org", TenantContext.organizationId());
            } else {
                spec = jdbc.sql(sql).params(resId, TenantContext.organizationId());
            }
            List<String> rows = spec.query(String.class).list();
            if (rows.isEmpty()) {
                return null;
            }
            return rows.getFirst() == null || rows.getFirst().isBlank() ? resId : rows.getFirst();
        }

        @Override
        public String webPath(String resId) {
            return pathPrefix == null ? null : pathPrefix + resId;
        }
    }
}
