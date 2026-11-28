package com.inventoryerp.pos.application;

import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.SaleStockDrain;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IPR 1b (count spec section 5): the POS side of {@link SaleStockDrain}. A job issues at its invoice's till location
 * (else, for an order-timed job without its invoice, its order's till), resolved like the job itself does
 * ({@link PosStockLocation}). Jobs run outside the caller's transaction (it is suspended), so each line commits on its
 * own as in the worker; the caller holds no lock while they run.
 */
@Component
public class PosSaleStockDrain implements SaleStockDrain {

    private static final Logger log = LoggerFactory.getLogger(PosSaleStockDrain.class);

    private final JdbcClient jdbc;
    private final PosInvoiceService billing;
    private final PosStockLocation locations;
    private final TransactionTemplate outside;

    public PosSaleStockDrain(JdbcClient jdbc, PosInvoiceService billing, PosStockLocation locations,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.locations = locations;
        this.outside = new TransactionTemplate(txm);
        this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public int drainAt(String locationId) {
        List<String> jobs = jobsAt(locationId, null);
        if (jobs.isEmpty()) {
            return 0;
        }
        Integer n = com.inventoryerp.kernel.common.EffectiveTime.backOffice(() -> outside.execute(st -> {
            int done = 0;
            for (String job : jobs) {
                try {
                    billing.processStockPostingJob(job);
                    done++;
                } catch (RuntimeException e) {
                    log.warn("stock posting {} before count submit failed: {}", job, e.toString());
                }
            }
            return done;
        }));
        return n == null ? 0 : n;
    }

    @Override
    public int stuckAt(String locationId, java.util.Collection<String> productIds) {
        return jobsAt(locationId, productIds.toArray(String[]::new)).size();
    }

    /**
     * Not-done, not-abandoned jobs issuing at {@code locationId}; with {@code stuckProducts}: only the failed or failing
     * ones that sell one of those products or a menu item.
     */
    private List<String> jobsAt(String locationId, String[] stuckProducts) {
        String org = TenantContext.organizationId();
        boolean stuck = stuckProducts != null;
        List<Object> params = new ArrayList<>(List.of(org));
        if (stuck) {
            params.add(stuckProducts);
            params.add(stuckProducts);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT j.id, CASE WHEN j.\"invoiceId\" IS NOT NULL"
                        + " THEN COALESCE(cs1.\"registerLocationId\", cr1.\"locationId\")"
                        + " ELSE COALESCE(cs2.\"registerLocationId\", cr2.\"locationId\") END AS preferred"
                        + " FROM \"StockPostingJob\" j"
                        + " LEFT JOIN \"Invoice\" i ON i.id = j.\"invoiceId\""
                        + " LEFT JOIN \"CashSession\" cs1 ON cs1.id = i.\"cashSessionId\""
                        + " LEFT JOIN \"CashRegister\" cr1 ON cr1.id = cs1.\"cashRegisterId\""
                        + " LEFT JOIN \"Order\" o ON o.id = j.\"orderId\" AND j.\"invoiceId\" IS NULL"
                        + " LEFT JOIN \"CashSession\" cs2 ON cs2.id = o.\"cashSessionId\""
                        + " LEFT JOIN \"CashRegister\" cr2 ON cr2.id = cs2.\"cashRegisterId\""
                        + " WHERE j.\"organizationId\" = ? AND j.status NOT IN ('done', 'abandoned')"
                        + (stuck ? " AND (j.status = 'failed' OR (j.status = 'pending' AND j.attempts > 0))"
                                + " AND (EXISTS (SELECT 1 FROM \"InvoiceItem\" ii WHERE ii.\"invoiceId\" = j.\"invoiceId\""
                                + " AND (ii.\"productId\" = ANY(?) OR ii.\"menuItemId\" IS NOT NULL))"
                                + " OR EXISTS (SELECT 1 FROM \"OrderItem\" oi WHERE j.\"invoiceId\" IS NULL"
                                + " AND oi.\"orderId\" = j.\"orderId\" AND (oi.\"productId\" = ANY(?)"
                                + " OR oi.\"menuItemId\" IS NOT NULL)))" : "")
                        + " ORDER BY j.\"createdAt\", j.id")
                .params(params));
        List<String> out = new ArrayList<>();
        Map<String, String> resolved = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            String preferred = (String) r.get("preferred");
            String at = resolved.computeIfAbsent(preferred == null ? "" : preferred,
                    k -> String.valueOf(locations.resolve(org, preferred)));
            if (Objects.equals(locationId, at)) {
                out.add((String) r.get("id"));
            }
        }
        return out;
    }
}
