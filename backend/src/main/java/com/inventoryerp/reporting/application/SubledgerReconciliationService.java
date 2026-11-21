package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.reporting.spi.SubledgerReconciliation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link SubledgerReconciliation} over the engine's AR / AP reconciliation reports (recorded as VERIFY runs). */
@Service
public class SubledgerReconciliationService implements SubledgerReconciliation {

    private final ReportEngine engine;
    private final ReportRegistry registry;

    public SubledgerReconciliationService(ReportEngine engine, ReportRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    @Override
    public Result reconcile(String organizationId, String side) {
        String id = "AP".equals(side) ? "ap.reconciliation" : "ar.reconciliation";
        TenantStore store = TenantStore.organization(organizationId);
        registry.find(id).ifPresent(d -> store.setPermissions(List.of(d.permission())));
        ReportEngine.ReportResult r = TenantContext.run(store, () -> engine.run(id, ReportRequest.empty(), "VERIFY"));
        boolean ok = true;
        List<String> failed = new ArrayList<>();
        for (ReportBody.Check c : r.checks()) {
            if (!c.ok()) {
                ok = false;
                failed.add(c.label() + (c.detail() == null ? "" : " (" + c.detail() + ")"));
            }
        }
        return new Result("AP".equals(side) ? "AP" : "AR", ok, dec(r.totals().get("subledger")),
                dec(r.totals().get("gl")), ok ? "Reconciles" : String.join("; ", failed));
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
