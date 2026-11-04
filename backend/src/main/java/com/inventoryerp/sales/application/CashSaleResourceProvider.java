package com.inventoryerp.sales.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Branch cash sales as a DMS record type (ADR-S056): payment receipts, signed delivery notes and other supporting
 * documents land in the "sales" workspace.
 */
@Component
public class CashSaleResourceProvider implements DmsResourceProvider {

    public static final String RES_TYPE = "sales_submission";

    private final JdbcClient jdbc;

    public CashSaleResourceProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resType() {
        return RES_TYPE;
    }

    @Override
    public String label() {
        return "Cash sale";
    }

    @Override
    public String readPermission() {
        return Permissions.SALES__CASH_SALE_READ;
    }

    @Override
    public String writePermission() {
        return Permissions.SALES__CASH_SALE_CREATE;
    }

    @Override
    public String workspaceCode() {
        return "sales";
    }

    @Override
    public String displayName(String resId) {
        List<String> rows = jdbc.sql("SELECT number FROM \"SalesSubmission\" WHERE id = ? AND \"organizationId\" = ?")
                .params(resId, TenantContext.organizationId()).query(String.class).list();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public String webPath(String resId) {
        return "/sales/cash-sales/" + resId;
    }
}
